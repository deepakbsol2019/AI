package com.book.user;

import java.time.Clock;
import java.util.List;
import com.book.user.service.TenantProvisioningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

class AuthSchemaConstraintTests {
    JdbcTemplate jdbc;
    TenantProvisioningService.ProvisionedAccount first;
    TenantProvisioningService.ProvisionedAccount second;
    @BeforeEach void setup() {
        jdbc=AuthTestDatabase.create();
        var provisioning=new TenantProvisioningService(jdbc,Clock.systemUTC(), com.book.user.AuthTestDatabase.storage());
        first=provisioning.create("INDIVIDUAL",null,"one@example.com","One",null,null,true);
        second=provisioning.create("ORGANIZATION","SSO","two@example.com","Two","second-company","Second Company",false);
    }
    @Test void originalEighteenTableFieldsAndFiveExplicitAuthAdditionsArePresent() {
        List<String> tables=List.of("tenants","users","tenant_memberships","roles","user_roles","permissions",
                "role_permissions","api_endpoints","endpoint_permissions","auth_sessions","refresh_tokens","audit_events",
                "identity_providers","external_identities","local_credentials","credential_tokens","local_mfa_factors","recovery_codes");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public'",Integer.class)).isEqualTo(19);
        int fields=tables.stream().mapToInt(table -> jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='public' AND table_name=?",Integer.class,table)).sum();
        assertThat(fields).isEqualTo(157); // Includes the distinct nullable user deleted_at marker.
    }
    @Test void ownerAndCompositeSessionMembershipConstraintsPreventCrossTenantAssociations() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO tenants(tenant_type,login_policy,owner_user_id,tenant_code,display_name,status,authz_version,created_at,updated_at)
                VALUES('INDIVIDUAL','PASSWORD',?,'another-owned','Another','ACTIVE',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """,first.userId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> session(first.userId(),second.tenantId(),first.membershipId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> session(second.userId(),first.tenantId(),first.membershipId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void companyProviderMayBePendingWithoutInventedConfigurationButOnlyOneCanBeActive() {
        jdbc.update("INSERT INTO identity_providers(tenant_id,provider_type,provider_code,status,created_at,updated_at) VALUES(?,'ORG_OIDC','pending','PENDING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",second.tenantId());
        assertThatThrownBy(() -> jdbc.update("UPDATE identity_providers SET status='ACTIVE' WHERE provider_code='pending'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        provider("trusted-one");
        assertThatThrownBy(() -> provider("trusted-two")).isInstanceOf(DataIntegrityViolationException.class);
        Long providerId=jdbc.queryForObject("SELECT identity_provider_id FROM identity_providers WHERE provider_code='trusted-one'",Long.class);
        for(String subject:List.of("subject","Subject","subject ")) {
            jdbc.update("INSERT INTO external_identities(identity_provider_id,user_id,subject,created_at) VALUES(?,?,?,CURRENT_TIMESTAMP)",providerId,second.userId(),subject);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM external_identities",Integer.class)).isEqualTo(3);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO external_identities(identity_provider_id,user_id,subject,created_at) VALUES(?,?,'subject',CURRENT_TIMESTAMP)",providerId,first.userId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void refreshConstraintsEnforceOneActiveSuccessorAndSameSessionLineage() {
        long own=session(first.userId(),first.tenantId(),first.membershipId());
        long other=session(second.userId(),second.tenantId(),second.membershipId());
        long parent=refresh(own,null,1);
        assertThatThrownBy(() -> refresh(own,null,2)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE refresh_tokens SET consumed_at=CURRENT_TIMESTAMP WHERE refresh_token_id=?",parent);
        assertThatThrownBy(() -> refresh(other,parent,4)).isInstanceOf(DataIntegrityViolationException.class);
        long child=refresh(own,parent,3);
        jdbc.update("UPDATE refresh_tokens SET consumed_at=CURRENT_TIMESTAMP WHERE refresh_token_id=?",child);
        assertThatThrownBy(() -> refresh(own,parent,5)).isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void superUserPermissionMigrationIsAdditiveRepeatableAndPreservesDisabledCatalogState() throws Exception {
        String sql=new org.springframework.core.io.ClassPathResource("db/manual/20261125_super_user_permissions.sql")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8)
                .replaceAll("(?m)^SET time_zone[^;]*;","").replace("UTC_TIMESTAMP(6)","CURRENT_TIMESTAMP");
        var migration=new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                new org.springframework.core.io.ByteArrayResource(sql.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        jdbc.update("UPDATE roles SET role_code='SUPER_USER',is_system=TRUE WHERE tenant_id=?",first.tenantId());
        jdbc.update("DELETE FROM role_permissions WHERE tenant_id=?",first.tenantId());
        migration.execute(jdbc.getDataSource());
        migration.execute(jdbc.getDataSource());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM permissions WHERE permission_code='BOOKS.DELETE'",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM role_permissions WHERE tenant_id=?",Long.class,first.tenantId()))
                .isEqualTo(jdbc.queryForObject("SELECT COUNT(*) FROM permissions WHERE is_active=TRUE",Long.class));
        jdbc.update("UPDATE permissions SET is_active=FALSE WHERE permission_code='BOOKS.DELETE'");
        migration.execute(jdbc.getDataSource());
        assertThat(jdbc.queryForObject("SELECT is_active FROM permissions WHERE permission_code='BOOKS.DELETE'",Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public'",Integer.class)).isEqualTo(19);
    }
    @Test void roleCatalogMigrationRerunsPreserveExplicitDisabledAndNonDelegablePolicy() throws Exception {
        String source=new org.springframework.core.io.ClassPathResource("db/manual/20261125_role_management.sql")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        String sql=source.substring(source.indexOf("DELIMITER ;")+"DELIMITER ;".length()).replace("UTC_TIMESTAMP(6)","CURRENT_TIMESTAMP");
        jdbc.update("UPDATE permissions SET is_delegable=FALSE,is_active=FALSE WHERE permission_code='ROLE_CREATE'");
        var migration=new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ByteArrayResource(sql.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        migration.execute(jdbc.getDataSource());migration.execute(jdbc.getDataSource());
        assertThat(jdbc.queryForObject("SELECT is_delegable FROM permissions WHERE permission_code='ROLE_CREATE'",Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT is_active FROM permissions WHERE permission_code='ROLE_CREATE'",Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_endpoints WHERE path_pattern LIKE '/api/user/roles%'",Long.class)).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM role_permissions rp JOIN permissions p ON p.permission_id=rp.permission_id WHERE p.component_code='ROLES'",Long.class)).isZero();
    }
    private void provider(String code) {
        jdbc.update("INSERT INTO identity_providers(tenant_id,provider_type,provider_code,issuer_url,client_id,status,created_at,updated_at) VALUES(?,'ORG_OIDC',?,'https://trusted.example','client','ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",second.tenantId(),code);
    }
    private long session(long user,long tenant,long membership) {
        jdbc.update("""
                INSERT INTO auth_sessions(user_id,tenant_id,membership_id,auth_method,user_security_version,
                tenant_authz_version,membership_authz_version,authorization_fingerprint,created_at,last_seen_at,refresh_expires_at)
                VALUES(?,?,?,'PASSWORD',1,1,1,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,DATEADD('MINUTE',10,CURRENT_TIMESTAMP))
                """,user,tenant,membership,"0".repeat(64));
        return jdbc.queryForObject("SELECT MAX(session_id) FROM auth_sessions",Long.class);
    }
    private long refresh(long session,Long parent,int discriminator) {
        byte[] digest=new byte[32]; digest[0]=(byte)discriminator;
        jdbc.update("INSERT INTO refresh_tokens(session_id,parent_refresh_token_id,token_digest,issued_at,expires_at) VALUES(?,?,?,CURRENT_TIMESTAMP,DATEADD('MINUTE',10,CURRENT_TIMESTAMP))",session,parent,digest);
        return jdbc.queryForObject("SELECT MAX(refresh_token_id) FROM refresh_tokens",Long.class);
    }
}
