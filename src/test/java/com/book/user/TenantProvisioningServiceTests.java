package com.book.user;

import com.book.user.service.TenantProvisioningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import static org.assertj.core.api.Assertions.*;

class TenantProvisioningServiceTests {
    JdbcTemplate jdbc;
    TenantProvisioningService service;
    TransactionTemplate transaction;
    @BeforeEach void setup() {
        jdbc = AuthTestDatabase.create();
        service = new TenantProvisioningService(jdbc, Clock.fixed(Instant.parse("2026-11-25T00:00:00Z"), ZoneOffset.UTC), com.book.user.AuthTestDatabase.storage());
        transaction = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    }
    @Test void individualForcesPasswordAndOwnsOnlyItsTenantAdmin() {
        var first = create("INDIVIDUAL", "SSO", "First@Example.com", null, false);
        var second = create("INDIVIDUAL", "PASSWORD", "second@example.com", null, true);
        assertThat(jdbc.queryForObject("SELECT login_policy FROM tenants WHERE tenant_id=?", String.class, first.tenantId())).isEqualTo("PASSWORD");
        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE user_id=?", String.class, first.userId())).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT primary_email FROM users WHERE user_id=?", String.class, first.userId())).isEqualTo("first@example.com");
        assertThat(jdbc.queryForObject("SELECT owner_user_id FROM tenants WHERE tenant_id=?", Long.class, first.tenantId())).isEqualTo(first.userId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_roles WHERE tenant_id=? AND membership_id=?", Integer.class, first.tenantId(), first.membershipId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_roles WHERE tenant_id=? AND membership_id=?", Integer.class, second.tenantId(), first.membershipId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM local_credentials", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM role_permissions WHERE tenant_id=?", Integer.class, first.tenantId()))
                .isEqualTo(jdbc.queryForObject("SELECT COUNT(*) FROM permissions WHERE is_active=TRUE AND component_code IN ('BOOKS','ACCOUNT') AND is_delegable=TRUE", Integer.class));
    }
    @Test void organizationPasswordAndSsoProvisionPendingStatesWithoutLocalPassword() {
        var password = create("ORGANIZATION", "PASSWORD", "password@example.com", "Password Company", false);
        var sso = create("ORGANIZATION", "SSO", "sso@example.com", "SSO Company", false);
        assertThat(jdbc.queryForObject("SELECT status FROM tenants WHERE tenant_id=?", String.class, password.tenantId())).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT status FROM tenants WHERE tenant_id=?", String.class, sso.tenantId())).isEqualTo("PENDING_SETUP");
        assertThat(jdbc.queryForObject("SELECT role_code FROM roles WHERE tenant_id=?", String.class, sso.tenantId())).isEqualTo("OWNER");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM local_credentials", Integer.class)).isZero();
    }
    @Test void duplicateExistingAccountRollsBackEveryPartOfNewRegistration() {
        create("INDIVIDUAL", null, "existing@example.com", null, false);
        assertThatThrownBy(() -> create("ORGANIZATION", "PASSWORD", "existing@example.com", "Other Company", false))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tenants", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM roles", Integer.class)).isEqualTo(1);
    }
    @Test void conflictingWorkspaceCodeRollsBackAlreadyInsertedNewUser() {
        transaction.execute(status -> service.create("ORGANIZATION", "PASSWORD", "one@example.com", "One", "same-code", "Company", false));
        assertThatThrownBy(() -> transaction.execute(status -> service.create("ORGANIZATION", "PASSWORD", "two@example.com", "Two", "same-code", "Company", false)))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isEqualTo(1);
    }
    @Test void missingPermissionCatalogFailsAndRollsBackRatherThanCreatingAnUnusableAdmin() {
        jdbc.update("DELETE FROM endpoint_permissions");
        jdbc.update("DELETE FROM permissions WHERE permission_kind<>'VIEW'");
        jdbc.update("DELETE FROM permissions");
        assertThatThrownBy(() -> create("INDIVIDUAL", null, "new@example.com", null, false)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isZero();
    }
    @Test void generatedCurrentMembershipAndCompositeRoleFksRejectCrossTenantGrants() {
        var first = create("INDIVIDUAL", null, "first@example.com", null, true);
        var second = create("INDIVIDUAL", null, "second@example.com", null, true);
        Long foreignRole = jdbc.queryForObject("SELECT role_id FROM roles WHERE tenant_id=?", Long.class, second.tenantId());
        assertThatThrownBy(() -> jdbc.update("INSERT INTO user_roles(tenant_id,membership_id,role_id,granted_at) VALUES(?,?,?,CURRENT_TIMESTAMP)",
                first.tenantId(), first.membershipId(), foreignRole)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO tenant_memberships(tenant_id,user_id,status,authz_version,joined_at,created_at,updated_at) VALUES(?,?,'SUSPENDED',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                second.tenantId(), first.userId())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    private TenantProvisioningService.ProvisionedAccount create(String type, String policy, String email, String organization, boolean verified) {
        return transaction.execute(status -> service.create(type, policy, email, "Example User", null, organization, verified));
    }
}
