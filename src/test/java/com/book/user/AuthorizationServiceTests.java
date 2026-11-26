package com.book.user;

import java.time.Clock;
import com.book.user.security.UserPrincipal;
import com.book.user.service.AuthorizationService;
import com.book.user.service.TenantProvisioningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;

class AuthorizationServiceTests {
    JdbcTemplate jdbc;
    AuthorizationService authorization;
    TenantProvisioningService.ProvisionedAccount account;
    UserPrincipal principal;
    @BeforeEach void setup() {
        jdbc = AuthTestDatabase.create();
        account = new TenantProvisioningService(jdbc, Clock.systemUTC(), com.book.user.AuthTestDatabase.storage())
                .create("INDIVIDUAL", null, "admin@example.com", "Administrator", null, null, true);
        authorization = new AuthorizationService(jdbc);
        principal = new UserPrincipal(account.userId(), account.tenantId(), account.membershipId(), 1, 1, 1, 1,
                authorization.authorizationFingerprint(account.tenantId(), account.membershipId()));
    }
    @Test void tenantAdminCanInvokeSeededOperationsAndUnknownOrWrongMethodsFailClosed() {
        assertThatCode(() -> authorization.authorize(principal, "GET", "/api/search/books")).doesNotThrowAnyException();
        assertThatCode(() -> authorization.authorize(principal, "PATCH", "/api/books/42/topics/17/metadata")).doesNotThrowAnyException();
        assertDenied("GET", "/api/no-such-endpoint"); assertDenied("DELETE", "/api/search/books");
    }
    @Test void activeChildPermissionRequiresActuallyGrantedActiveSameComponentView() {
        jdbc.update("DELETE FROM role_permissions WHERE tenant_id=? AND permission_id=(SELECT permission_id FROM permissions WHERE permission_code='BOOKS.VIEW')", account.tenantId());
        assertDenied("POST", "/api/upload-file");
    }
    @Test void deactivatedRolePermissionAndEndpointEachDenyPreviouslyAllowedOperation() {
        jdbc.update("UPDATE permissions SET is_active=FALSE WHERE permission_code='BOOKS.UPLOAD'");
        assertDenied("POST", "/api/upload-file");
        jdbc.update("UPDATE permissions SET is_active=TRUE WHERE permission_code='BOOKS.UPLOAD'");
        jdbc.update("UPDATE roles SET status='SUSPENDED' WHERE tenant_id=?", account.tenantId());
        assertDenied("POST", "/api/upload-file");
        jdbc.update("UPDATE roles SET status='ACTIVE' WHERE tenant_id=?", account.tenantId());
        jdbc.update("UPDATE api_endpoints SET is_active=FALSE WHERE path_pattern='/api/upload-file'");
        assertDenied("POST", "/api/upload-file");
    }
    @Test void multipleEndpointRequirementsUseAllAndEmptyProtectedPolicyDenies() {
        jdbc.update("DELETE FROM role_permissions WHERE tenant_id=? AND permission_id=(SELECT permission_id FROM permissions WHERE permission_code='BOOKS.DOWNLOAD')", account.tenantId());
        jdbc.update("INSERT INTO endpoint_permissions(endpoint_id,permission_id,created_at) SELECT e.endpoint_id,p.permission_id,CURRENT_TIMESTAMP FROM api_endpoints e CROSS JOIN permissions p WHERE e.path_pattern='/api/upload-file' AND p.permission_code='BOOKS.DOWNLOAD'");
        assertDenied("POST", "/api/upload-file");
        jdbc.update("DELETE FROM endpoint_permissions WHERE endpoint_id=(SELECT endpoint_id FROM api_endpoints WHERE path_pattern='/api/read/book')");
        assertDenied("GET", "/api/read/book");
    }
    @Test void policiesAreRestrictedToThisServiceAndSpecificPolicyWinsOverBroadPolicy() {
        jdbc.update("INSERT INTO api_endpoints(service_code,http_method,path_pattern,description,is_public,is_active,created_at) VALUES('OTHER','GET','/api/other','Other service',TRUE,TRUE,CURRENT_TIMESTAMP)");
        assertDenied("GET", "/api/other");
        jdbc.update("INSERT INTO api_endpoints(service_code,http_method,path_pattern,description,is_public,is_active,created_at) VALUES('BOOK_SEARCH','GET','/api/**','Broad deny without grants',FALSE,TRUE,CURRENT_TIMESTAMP)");
        assertThatCode(() -> authorization.authorize(principal, "GET", "/api/search/books")).doesNotThrowAnyException();
        assertDenied("GET", "/api/unmapped");
    }
    @Test void authorizationFingerprintTracksRoleVersionGrantAndPermissionChangesDeterministically() {
        String initial = fingerprint();
        assertThat(fingerprint()).isEqualTo(initial).hasSize(64);
        jdbc.update("UPDATE roles SET authz_version=authz_version+1 WHERE tenant_id=?", account.tenantId());
        String changedRole = fingerprint(); assertThat(changedRole).isNotEqualTo(initial);
        jdbc.update("UPDATE permissions SET is_active=FALSE WHERE permission_code='BOOKS.VIEW'");
        String changedPermission = fingerprint(); assertThat(changedPermission).isNotEqualTo(changedRole);
        jdbc.update("DELETE FROM role_permissions WHERE tenant_id=? AND permission_id=(SELECT permission_id FROM permissions WHERE permission_code='BOOKS.EDIT')", account.tenantId());
        assertThat(fingerprint()).isNotEqualTo(changedPermission);
    }
    @Test void anotherTenantsMembershipCannotSupplyPermissionsToTheCurrentTenant() {
        var other = new TenantProvisioningService(jdbc, Clock.systemUTC(), com.book.user.AuthTestDatabase.storage())
                .create("INDIVIDUAL", null, "other@example.com", "Other", null, null, true);
        var forged = new UserPrincipal(other.userId(), account.tenantId(), other.membershipId(), 1, 1, 1, 1, "");
        assertThatThrownBy(() -> authorization.authorize(forged, "GET", "/api/search/books"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");
    }
    @Test void systemSuperUserReceivesEveryActiveCapabilityWithoutDependingOnExplicitGrantRows() {
        promote();
        jdbc.update("DELETE FROM role_permissions WHERE tenant_id=?", account.tenantId());
        assertThat(authorization.isSuperUser(principal)).isTrue();
        assertThatCode(() -> authorization.authorize(principal, "GET", "/api/search/books")).doesNotThrowAnyException();
        assertThatCode(() -> authorization.authorize(principal, "POST", "/api/upload-file")).doesNotThrowAnyException();
        assertThatCode(() -> authorization.authorize(principal, "PATCH", "/api/books/42/metadata")).doesNotThrowAnyException();
        // A catalogued deletion policy exercises BOOKS.DELETE without introducing a production deletion API.
        jdbc.update("INSERT INTO api_endpoints(service_code,http_method,path_pattern,description,is_public,is_active,created_at) VALUES('BOOK_SEARCH','DELETE','/api/test-delete/{id}','Test-only deletion policy',FALSE,TRUE,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO endpoint_permissions(endpoint_id,permission_id,created_at) SELECT e.endpoint_id,p.permission_id,CURRENT_TIMESTAMP FROM api_endpoints e CROSS JOIN permissions p WHERE e.path_pattern='/api/test-delete/{id}' AND p.permission_code='BOOKS.DELETE'");
        assertThatCode(() -> authorization.authorize(principal, "DELETE", "/api/test-delete/42")).doesNotThrowAnyException();
        assertDenied("DELETE", "/api/uncatalogued");
        jdbc.update("DELETE FROM endpoint_permissions WHERE endpoint_id=(SELECT endpoint_id FROM api_endpoints WHERE path_pattern='/api/test-delete/{id}')");
        assertDenied("DELETE", "/api/test-delete/42");
    }
    @Test void superUserStillRespectsDisabledCapabilitiesAndSameComponentViewPrerequisites() {
        promote();
        jdbc.update("UPDATE permissions SET is_active=FALSE WHERE permission_code='BOOKS.UPLOAD'");
        assertDenied("POST", "/api/upload-file");
        jdbc.update("UPDATE permissions SET is_active=TRUE WHERE permission_code='BOOKS.UPLOAD'");
        jdbc.update("UPDATE permissions SET is_active=FALSE WHERE permission_code='BOOKS.VIEW'");
        assertDenied("POST", "/api/upload-file");
        jdbc.update("UPDATE permissions SET is_active=TRUE WHERE permission_code='BOOKS.VIEW'");
        jdbc.update("UPDATE api_endpoints SET is_active=FALSE WHERE path_pattern='/api/upload-file'");
        assertDenied("POST", "/api/upload-file");
    }
    @Test void roleNameAloneAndForgedIdentityCannotBecomeAnApplicationSuperUser() {
        jdbc.update("UPDATE roles SET role_code='SUPER_USER',is_system=FALSE WHERE tenant_id=?", account.tenantId());
        assertThat(authorization.isSuperUser(principal)).isFalse();
        jdbc.update("DELETE FROM role_permissions WHERE tenant_id=? AND permission_id=(SELECT permission_id FROM permissions WHERE permission_code='BOOKS.EDIT')", account.tenantId());
        assertDenied("PATCH", "/api/books/42/metadata");
        jdbc.update("UPDATE roles SET is_system=TRUE WHERE tenant_id=?", account.tenantId());
        assertThat(authorization.isSuperUser(principal)).isTrue();
        var forged = new UserPrincipal(account.userId()+999, account.tenantId(), account.membershipId(), 1, 1, 1, 1, "");
        assertThat(authorization.isSuperUser(forged)).isFalse();
    }
    @Test void superUserRequiresActiveVerifiedIdentityTenantMembershipAndRole() {
        promote();
        for (String table : java.util.List.of("users", "tenants", "tenant_memberships", "roles")) {
            jdbc.update("UPDATE " + table + " SET status='SUSPENDED'");
            assertThat(authorization.isSuperUser(principal)).as(table).isFalse();
            jdbc.update("UPDATE " + table + " SET status='ACTIVE'");
        }
        jdbc.update("UPDATE users SET email_verified_at=NULL");
        assertThat(authorization.isSuperUser(principal)).isFalse();
    }
    @Test void fingerprintTracksReservedRoleMetadataAndTheEntireSuperUserPermissionCatalog() {
        String admin = fingerprint();
        jdbc.update("UPDATE roles SET role_code='SUPER_USER',is_system=FALSE WHERE tenant_id=?", account.tenantId());
        String custom = fingerprint();
        assertThat(custom).isNotEqualTo(admin);
        jdbc.update("UPDATE roles SET is_system=TRUE WHERE tenant_id=?", account.tenantId());
        String system = fingerprint();
        assertThat(system).isNotEqualTo(custom);
        jdbc.update("INSERT INTO permissions(component_code,permission_code,display_name,permission_kind,is_active,created_at) VALUES('FUTURE','FUTURE.VIEW','Future component','VIEW',TRUE,CURRENT_TIMESTAMP)");
        assertThat(fingerprint()).isNotEqualTo(system);
        // No role_permissions row is needed for a newly catalogued capability.
        jdbc.update("INSERT INTO api_endpoints(service_code,http_method,path_pattern,description,is_public,is_active,created_at) VALUES('BOOK_SEARCH','GET','/api/future','Future capability',FALSE,TRUE,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO endpoint_permissions(endpoint_id,permission_id,created_at) SELECT e.endpoint_id,p.permission_id,CURRENT_TIMESTAMP FROM api_endpoints e CROSS JOIN permissions p WHERE e.path_pattern='/api/future' AND p.permission_code='FUTURE.VIEW'");
        assertThatCode(() -> authorization.authorize(principal, "GET", "/api/future")).doesNotThrowAnyException();
    }
    private void promote() {
        jdbc.update("UPDATE roles SET role_code='SUPER_USER',is_system=TRUE WHERE tenant_id=?", account.tenantId());
    }
    private String fingerprint() { return authorization.authorizationFingerprint(account.tenantId(), account.membershipId()); }
    private void assertDenied(String method, String path) {
        assertThatThrownBy(() -> authorization.authorize(principal, method, path))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");
    }
}
