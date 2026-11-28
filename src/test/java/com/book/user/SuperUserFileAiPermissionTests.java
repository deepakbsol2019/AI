package com.book.user;

import com.book.user.security.UserPrincipal;
import com.book.user.service.AuthorizationService;
import com.book.user.service.TenantProvisioningService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.assertj.core.api.Assertions.*;

class SuperUserFileAiPermissionTests {
    private void apply(JdbcTemplate jdbc) throws Exception {
        String sql=new ClassPathResource("db/manual/20261128_super_user_file_ai_permissions.sql").getContentAsString(StandardCharsets.UTF_8)
                .replaceAll("(?m)^SET time_zone[^;]*;", "").replace("UTC_TIMESTAMP(6)","CURRENT_TIMESTAMP");
        new ResourceDatabasePopulator(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8))).execute(jdbc.getDataSource());
    }
    @Test void explicitlyGrantsFileDeletionAndPrivateAiChatOnlyToTheReservedSuperUser() throws Exception {
        var jdbc=AuthTestDatabase.create();var provision=new TenantProvisioningService(jdbc,Clock.systemUTC(),AuthTestDatabase.storage());
        var global=provision.create("INDIVIDUAL",null,"explicit-super@example.com","Super",null,null,true);
        var admin=provision.create("INDIVIDUAL",null,"explicit-admin@example.com","Admin",null,null,true);
        var fake=provision.create("INDIVIDUAL",null,"explicit-fake@example.com","Fake",null,null,true);
        jdbc.update("UPDATE roles SET role_code='SUPER_USER',is_system=TRUE WHERE tenant_id=?",global.tenantId());
        jdbc.update("UPDATE roles SET role_code='ADMIN',is_system=TRUE WHERE tenant_id=?",admin.tenantId());
        jdbc.update("UPDATE roles SET role_code='SUPER_USER',is_system=FALSE WHERE tenant_id=?",fake.tenantId());
        jdbc.update("DELETE FROM role_permissions");apply(jdbc);apply(jdbc);
        assertThat(jdbc.queryForList("SELECT p.permission_code FROM role_permissions rp JOIN permissions p ON p.permission_id=rp.permission_id WHERE rp.tenant_id=? ORDER BY p.permission_code",String.class,global.tenantId()))
                .containsExactly("AI_HELP.CHAT","AI_HELP.VIEW","BOOKS.DELETE","BOOKS.VIEW");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM role_permissions WHERE tenant_id<>?",Long.class,global.tenantId())).isZero();
        var actor=new UserPrincipal(global.userId(),global.tenantId(),global.membershipId(),1,1,1,1,"fixture");
        var authorization=new AuthorizationService(jdbc);
        authorization.authorize(actor,"DELETE","/api/books/7");
        authorization.authorize(actor,"POST","/api/ai-help/chat");
        var effective=authorization.effectivePermissions(actor);
        assertThat(effective).containsAll(jdbc.queryForList("SELECT permission_id FROM permissions WHERE permission_code IN ('BOOKS.DELETE','AI_HELP.CHAT')",Long.class));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM endpoint_permissions ep JOIN api_endpoints e ON e.endpoint_id=ep.endpoint_id JOIN permissions p ON p.permission_id=ep.permission_id WHERE e.path_pattern='/api/ai-help/chat' AND p.permission_code='AI_HELP.CHAT'",Long.class)).isEqualTo(1);
    }
    @Test void migrationPreservesExplicitlyDisabledCatalogEntries() throws Exception {
        var jdbc=AuthTestDatabase.create();jdbc.update("UPDATE permissions SET is_active=FALSE WHERE permission_code='AI_HELP.CHAT'");
        apply(jdbc);
        assertThat(jdbc.queryForObject("SELECT is_active FROM permissions WHERE permission_code='AI_HELP.CHAT'",Boolean.class)).isFalse();
    }
}
