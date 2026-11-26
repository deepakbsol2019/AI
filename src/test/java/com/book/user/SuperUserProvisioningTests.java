package com.book.user;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import com.book.user.config.SuperUserSetup;
import com.book.user.service.SuperUserProvisioningService;
import com.book.user.service.TenantProvisioningService;
import com.book.user.service.TenantProvisioningService.ProvisionedAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;

class SuperUserProvisioningTests {
    private static final Instant NOW = Instant.parse("2026-11-25T00:00:00Z");
    private static final Timestamp TIME = Timestamp.from(NOW);
    private JdbcTemplate jdbc;
    private TenantProvisioningService tenants;
    private SuperUserProvisioningService service;
    private TransactionTemplate transactions;

    @BeforeEach void setup() {
        jdbc = AuthTestDatabase.create();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var manager = new DataSourceTransactionManager(jdbc.getDataSource());
        tenants = new TenantProvisioningService(jdbc, clock, com.book.user.AuthTestDatabase.storage());
        service = new SuperUserProvisioningService(jdbc, tenants, clock, manager);
        transactions = new TransactionTemplate(manager);
    }

    @Test void absentOperatorArgumentNeverElevatesAnOrdinaryAccountByItsEmailOrName() {
        var ordinary = create("INDIVIDUAL", "PASSWORD", "ordinary@example.com", true);
        var environment = new MockEnvironment().withProperty("app.user.super-user.display-name", "Super User");
        new SuperUserSetup(environment, service).run(new DefaultApplicationArguments());
        environment.withProperty("app.user.super-user.email", "   ");
        new SuperUserSetup(environment, service).run(new DefaultApplicationArguments());
        assertThat(roleCodes(ordinary.membershipId())).containsExactly("OWNER");
        assertThat(count("users")).isEqualTo(1);
        assertThat(grantAudits()).isZero();
    }

    @Test void operatorCreatesOnlyPendingIndividualWithAdminAndAllSuperUserCatalogGrants() {
        var result = service.provision("  Operator@Example.com  ", "Operator");
        assertThat(result.created()).isTrue();
        assertThat(result.changed()).isTrue();
        assertThat(result.passwordSetupRequired()).isTrue();
        var user = jdbc.queryForMap("SELECT * FROM users WHERE user_id=?", result.userId());
        assertThat(user.get("primary_email")).isEqualTo("operator@example.com");
        assertThat(user.get("display_name")).isEqualTo("Operator");
        assertThat(user.get("status")).isEqualTo("PENDING");
        assertThat(user.get("email_verified_at")).isNull();
        assertThat(((Number) user.get("security_version")).longValue()).isEqualTo(1);
        var tenant = jdbc.queryForMap("SELECT * FROM tenants WHERE tenant_id=?", result.tenantId());
        assertThat(tenant.get("tenant_type")).isEqualTo("INDIVIDUAL");
        assertThat(tenant.get("login_policy")).isEqualTo("PASSWORD");
        assertThat(tenant.get("status")).isEqualTo("PENDING");
        assertThat(((Number) tenant.get("owner_user_id")).longValue()).isEqualTo(result.userId());
        assertThat(roleCodes(result.membershipId())).containsExactlyInAnyOrder("OWNER", "SUPER_USER");
        var role = jdbc.queryForMap("SELECT * FROM roles WHERE tenant_id=? AND role_code='SUPER_USER'", result.tenantId());
        assertThat(role.get("is_system")).isEqualTo(true);
        assertThat(role.get("status")).isEqualTo("ACTIVE");
        assertThat(role.get("description").toString()).contains("Application-wide");
        assertThat(superGrants(result.tenantId())).containsExactlyInAnyOrderElementsOf(
                jdbc.queryForList("SELECT permission_code FROM permissions WHERE is_active=TRUE", String.class));
        assertThat(membershipVersion(result.membershipId())).isEqualTo(2);
        assertThat(grantAudits()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT actor_user_id FROM audit_events WHERE event_code='SUPER_USER_GRANTED'", Long.class)).isNull();
        for (String table : List.of("local_credentials", "credential_tokens", "auth_sessions", "refresh_tokens", "external_identities")) {
            assertThat(count(table)).as(table).isZero();
        }
    }

    @Test void runnerUsesOnlyExplicitConfigurationAndDefaultsNewDisplayNameToEmail() {
        var environment = new MockEnvironment().withProperty("app.user.super-user.email", "runner@example.com");
        new SuperUserSetup(environment, service).run(new DefaultApplicationArguments());
        assertThat(jdbc.queryForObject("SELECT display_name FROM users", String.class)).isEqualTo("runner@example.com");
        assertThat(jdbc.queryForObject("SELECT status FROM users", String.class)).isEqualTo("PENDING");
        assertThat(count("credential_tokens")).isZero();
        assertThat(count("local_credentials")).isZero();
    }

    @Test void normalizedRerunIsIdempotentAndDoesNotRenameOrVerifyThePendingAccount() {
        var first = service.provision("existing@example.com", "Original Name");
        var second = service.provision(" EXISTING@EXAMPLE.COM ", "Ignored Replacement");
        assertThat(second.created()).isFalse();
        assertThat(second.changed()).isFalse();
        assertThat(second.userId()).isEqualTo(first.userId());
        assertThat(second.membershipId()).isEqualTo(first.membershipId());
        assertThat(count("users")).isEqualTo(1);
        assertThat(count("tenants")).isEqualTo(1);
        assertThat(count("tenant_memberships")).isEqualTo(1);
        assertThat(count("user_roles")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT display_name FROM users", String.class)).isEqualTo("Original Name");
        assertThat(jdbc.queryForObject("SELECT email_verified_at FROM users", Timestamp.class)).isNull();
        assertThat(membershipVersion(first.membershipId())).isEqualTo(2);
        assertThat(grantAudits()).isEqualTo(1);
    }

    @Test void existingPasswordAndVerificationStayUnchangedAndOnlyChangedTargetsSessionsAreRevoked() {
        var target = create("INDIVIDUAL", "PASSWORD", "active@example.com", true);
        var other = create("INDIVIDUAL", "PASSWORD", "other@example.com", true);
        jdbc.update("""
                INSERT INTO local_credentials(user_id,password_hash,password_changed_at,failed_attempts,created_at,updated_at)
                VALUES (?,'{pbkdf2}existing-untouched-hash',?,3,?,?)
                """, target.userId(), TIME, TIME, TIME);
        long first = session(target), second = session(target), unrelated = session(other);
        var result = service.provision("active@example.com", "Must Not Rename");
        assertThat(result.passwordSetupRequired()).isFalse();
        assertThat(jdbc.queryForObject("SELECT display_name FROM users WHERE user_id=?", String.class, target.userId())).isEqualTo("Example User");
        assertThat(jdbc.queryForObject("SELECT email_verified_at FROM users WHERE user_id=?", Timestamp.class, target.userId())).isEqualTo(TIME);
        assertThat(jdbc.queryForObject("SELECT password_hash FROM local_credentials WHERE user_id=?", String.class, target.userId())).isEqualTo("{pbkdf2}existing-untouched-hash");
        assertThat(jdbc.queryForObject("SELECT failed_attempts FROM local_credentials WHERE user_id=?", Integer.class, target.userId())).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT security_version FROM users WHERE user_id=?", Long.class, target.userId())).isEqualTo(1);
        assertThat(revoked(first)).isTrue();
        assertThat(revoked(second)).isTrue();
        assertThat(revoked(unrelated)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens WHERE session_id IN (?,?) AND revoked_at IS NOT NULL", Long.class, first, second)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT revoked_at FROM refresh_tokens WHERE session_id=?", Timestamp.class, unrelated)).isNull();
        long fresh = session(target);
        assertThat(service.provision("active@example.com", null).changed()).isFalse();
        assertThat(revoked(fresh)).isFalse();
        assertThat(membershipVersion(target.membershipId())).isEqualTo(2);
        assertThat(grantAudits()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GOOGLE", "SSO"})
    void verifiedActiveProviderOnlyAccountsKeepTheirProviderAndNeverAcquireLocalCredentials(String method) {
        boolean company = method.equals("SSO");
        var account = create(company ? "ORGANIZATION" : "INDIVIDUAL", company ? "SSO" : "PASSWORD", "provider@example.com", true);
        if (company) jdbc.update("UPDATE tenants SET status='ACTIVE' WHERE tenant_id=?", account.tenantId());
        long provider = provider(company ? account.tenantId() : null, company ? "ORG_OIDC" : "GOOGLE");
        jdbc.update("INSERT INTO external_identities(identity_provider_id,user_id,subject,created_at) VALUES (?,?,'existing-subject',?)", provider, account.userId(), TIME);
        var before = jdbc.queryForMap("SELECT * FROM identity_providers WHERE identity_provider_id=?", provider);
        var result = service.provision("provider@example.com", null);
        assertThat(result.passwordSetupRequired()).isFalse();
        assertThat(result.userId()).isEqualTo(account.userId());
        assertThat(jdbc.queryForMap("SELECT * FROM identity_providers WHERE identity_provider_id=?", provider)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT subject FROM external_identities WHERE user_id=?", String.class, account.userId())).isEqualTo("existing-subject");
        assertThat(count("local_credentials")).isZero();
        assertThat(count("credential_tokens")).isZero();
        assertThat(count("users")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER_SUSPENDED", "UNVERIFIED_ACTIVE", "TENANT_SUSPENDED", "MEMBERSHIP_SUSPENDED", "MEMBERSHIP_LEFT", "PENDING_SSO", "PENDING_WITH_HASH", "PENDING_WITH_PROVIDER"})
    void invalidOrProtectedStatesCannotBeReactivatedOrElevated(String state) {
        boolean pending = state.startsWith("PENDING");
        boolean sso = state.equals("PENDING_SSO");
        var account = create(sso ? "ORGANIZATION" : "INDIVIDUAL", sso ? "SSO" : "PASSWORD", "blocked@example.com", !pending);
        switch (state) {
            case "USER_SUSPENDED" -> jdbc.update("UPDATE users SET status='SUSPENDED' WHERE user_id=?", account.userId());
            case "UNVERIFIED_ACTIVE" -> jdbc.update("UPDATE users SET email_verified_at=NULL WHERE user_id=?", account.userId());
            case "TENANT_SUSPENDED" -> jdbc.update("UPDATE tenants SET status='SUSPENDED' WHERE tenant_id=?", account.tenantId());
            case "MEMBERSHIP_SUSPENDED" -> jdbc.update("UPDATE tenant_memberships SET status='SUSPENDED' WHERE membership_id=?", account.membershipId());
            case "MEMBERSHIP_LEFT" -> jdbc.update("UPDATE tenant_memberships SET status='LEFT' WHERE membership_id=?", account.membershipId());
            case "PENDING_WITH_HASH" -> jdbc.update("INSERT INTO local_credentials(user_id,password_hash,password_changed_at,failed_attempts,created_at,updated_at) VALUES (?,'existing-hash',?,0,?,?)", account.userId(), TIME, TIME, TIME);
            case "PENDING_WITH_PROVIDER" -> jdbc.update("INSERT INTO external_identities(identity_provider_id,user_id,subject,created_at) VALUES (?,?,'pending-subject',?)", provider(null, "GOOGLE"), account.userId(), TIME);
            default -> { }
        }
        assertThatThrownBy(() -> service.provision("blocked@example.com", null)).isInstanceOf(ResponseStatusException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM roles WHERE role_code='SUPER_USER'", Long.class)).isZero();
        assertThat(membershipVersion(account.membershipId())).isEqualTo(1);
        assertThat(grantAudits()).isZero();
    }

    @Test void absentOrAmbiguousCurrentMembershipDoesNotCreateAnotherAccountOrPickATenant() {
        var account = create("INDIVIDUAL", "PASSWORD", "ambiguous@example.com", true);
        var other = create("INDIVIDUAL", "PASSWORD", "workspace@example.com", true);
        jdbc.execute("ALTER TABLE tenant_memberships DROP CONSTRAINT uk_membership_current_user");
        jdbc.update("""
                INSERT INTO tenant_memberships(tenant_id,user_id,status,authz_version,joined_at,created_at,updated_at)
                VALUES (?,?,'ACTIVE',1,?,?,?)
                """, other.tenantId(), account.userId(), TIME, TIME, TIME);
        assertThatThrownBy(() -> service.provision("ambiguous@example.com", null)).isInstanceOf(ResponseStatusException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM roles WHERE role_code='SUPER_USER'", Long.class)).isZero();
        assertThat(count("users")).isEqualTo(2);
    }

    @Test void customReservedRoleCollisionFailsWithoutTurningItIntoAnApplicationWideRole() {
        var account = create("INDIVIDUAL", "PASSWORD", "collision@example.com", true);
        jdbc.update("""
                INSERT INTO roles(tenant_id,role_code,display_name,description,is_system,status,authz_version,created_at,updated_at)
                VALUES (?,'SUPER_USER','Custom Role','Untrusted custom role',FALSE,'ACTIVE',1,?,?)
                """, account.tenantId(), TIME, TIME);
        assertThatThrownBy(() -> service.provision("collision@example.com", null)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("non-system");
        assertThat(jdbc.queryForObject("SELECT is_system FROM roles WHERE role_code='SUPER_USER'", Boolean.class)).isFalse();
        assertThat(roleCodes(account.membershipId())).containsExactly("OWNER");
        assertThat(membershipVersion(account.membershipId())).isEqualTo(1);
    }

    @Test void explicitRerunRestoresTrustedRoleAndResynchronizesOnlyItsValidActiveCatalogGrants() {
        var initial = service.provision("resync@example.com", null);
        long role = jdbc.queryForObject("SELECT role_id FROM roles WHERE tenant_id=? AND role_code='SUPER_USER'", Long.class, initial.tenantId());
        jdbc.update("UPDATE roles SET status='SUSPENDED',display_name='Old',description='Old' WHERE role_id=?", role);
        jdbc.update("DELETE FROM role_permissions WHERE role_id=? AND permission_id=(SELECT permission_id FROM permissions WHERE permission_code='BOOKS.DOWNLOAD')", role);
        jdbc.update("UPDATE role_permissions SET is_explicit=FALSE WHERE role_id=? AND permission_id=(SELECT permission_id FROM permissions WHERE permission_code='BOOKS.VIEW')", role);
        jdbc.update("UPDATE permissions SET is_active=FALSE WHERE permission_code='BOOKS.EDIT'");
        jdbc.update("INSERT INTO permissions(component_code,permission_code,display_name,permission_kind,is_active,created_at) VALUES ('EXTRA','EXTRA.VIEW','View extra','VIEW',TRUE,?)", TIME);
        long view = jdbc.queryForObject("SELECT permission_id FROM permissions WHERE permission_code='EXTRA.VIEW'", Long.class);
        jdbc.update("INSERT INTO permissions(component_code,permission_code,display_name,permission_kind,prerequisite_permission_id,is_active,created_at) VALUES ('EXTRA','EXTRA.ACTION','Extra action','ACTION',?,TRUE,?)", view, TIME);
        assertThat(service.provision("resync@example.com", null).changed()).isTrue();
        assertThat(superGrants(initial.tenantId())).contains("BOOKS.DOWNLOAD", "EXTRA.VIEW", "EXTRA.ACTION").doesNotContain("BOOKS.EDIT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM role_permissions WHERE role_id=? AND is_explicit=FALSE", Long.class, role)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM roles WHERE role_id=?", String.class, role)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT authz_version FROM roles WHERE role_id=?", Long.class, role)).isEqualTo(2);
        assertThat(membershipVersion(initial.membershipId())).isEqualTo(3);
        assertThat(grantAudits()).isEqualTo(2);
        assertThat(service.provision("resync@example.com", null).changed()).isFalse();
        assertThat(grantAudits()).isEqualTo(2);
    }

    @Test void inactiveViewPrerequisitesAreNotGrantedAsUsableActions() {
        jdbc.update("UPDATE permissions SET is_active=FALSE WHERE permission_code='BOOKS.VIEW'");
        var result = service.provision("prerequisites@example.com", null);
        assertThat(superGrants(result.tenantId())).containsExactlyInAnyOrderElementsOf(jdbc.queryForList("SELECT permission_code FROM permissions WHERE is_active=TRUE AND component_code<>\'BOOKS\'",String.class));
    }

    @Test void missingCatalogRollsBackANewPendingAccountAndInvalidEmailNeverCreatesOne() {
        assertThatThrownBy(() -> service.provision("not-an-email", null)).isInstanceOf(ResponseStatusException.class);
        jdbc.update("DELETE FROM endpoint_permissions");
        jdbc.update("DELETE FROM permissions WHERE permission_kind<>'VIEW'");
        jdbc.update("DELETE FROM permissions");
        assertThatThrownBy(() -> service.provision("empty-catalog@example.com", null)).isInstanceOf(IllegalStateException.class);
        assertThat(count("users")).isZero();
        assertThat(count("tenants")).isZero();
        assertThat(count("roles")).isZero();
    }

    private ProvisionedAccount create(String type, String policy, String email, boolean verified) {
        return transactions.execute(status -> tenants.create(type, policy, email, "Example User", null,
                type.equals("ORGANIZATION") ? "Company" : null, verified));
    }
    private Set<String> roleCodes(long membership) {
        return Set.copyOf(jdbc.queryForList("SELECT r.role_code FROM user_roles ur JOIN roles r ON r.role_id=ur.role_id AND r.tenant_id=ur.tenant_id WHERE ur.membership_id=?", String.class, membership));
    }
    private List<String> superGrants(long tenant) {
        return jdbc.queryForList("SELECT p.permission_code FROM role_permissions rp JOIN roles r ON r.role_id=rp.role_id AND r.tenant_id=rp.tenant_id JOIN permissions p ON p.permission_id=rp.permission_id WHERE r.tenant_id=? AND r.role_code='SUPER_USER' ORDER BY p.permission_code", String.class, tenant);
    }
    private long membershipVersion(long membership) {
        return jdbc.queryForObject("SELECT authz_version FROM tenant_memberships WHERE membership_id=?", Long.class, membership);
    }
    private long grantAudits() { return jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE event_code='SUPER_USER_GRANTED'", Long.class); }
    private long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
    private boolean revoked(long session) { return jdbc.queryForObject("SELECT revoked_at FROM auth_sessions WHERE session_id=?", Timestamp.class, session) != null; }
    private long session(ProvisionedAccount account) {
        jdbc.update("""
                INSERT INTO auth_sessions(user_id,tenant_id,membership_id,auth_method,user_security_version,tenant_authz_version,
                  membership_authz_version,authorization_fingerprint,created_at,last_seen_at,refresh_expires_at)
                VALUES (?,?,?,'PASSWORD',1,1,?,?,?, ?,?)
                """, account.userId(), account.tenantId(), account.membershipId(), membershipVersion(account.membershipId()),
                "a".repeat(64), TIME, TIME, Timestamp.from(NOW.plusSeconds(600)));
        long id = jdbc.queryForObject("SELECT MAX(session_id) FROM auth_sessions WHERE user_id=?", Long.class, account.userId());
        byte[] digest = new byte[32]; digest[0] = (byte) id;
        jdbc.update("INSERT INTO refresh_tokens(session_id,token_digest,issued_at,expires_at) VALUES (?,?,?,?)", id, digest, TIME, Timestamp.from(NOW.plusSeconds(600)));
        return id;
    }
    private long provider(Long tenant, String type) {
        String code = "provider-" + type;
        String issuer = type.equals("GOOGLE") ? "https://accounts.google.com" : "https://id.example.invalid";
        jdbc.update("INSERT INTO identity_providers(tenant_id,provider_type,provider_code,issuer_url,client_id,status,created_at,updated_at) VALUES (?,?,?,?,'client','ACTIVE',?,?)", tenant, type, code, issuer, TIME, TIME);
        return jdbc.queryForObject("SELECT identity_provider_id FROM identity_providers WHERE provider_code=?", Long.class, code);
    }
}
