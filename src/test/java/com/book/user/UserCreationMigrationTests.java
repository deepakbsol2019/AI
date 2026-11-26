package com.book.user;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.assertj.core.api.Assertions.assertThat;

/** An older migration rerun must never make the creation capability depend on USER_VIEW. */
class UserCreationMigrationTests {
    @Test void olderManagementAndCreationCatalogRerunsKeepNewEntryPoliciesIndependent() throws Exception {
        var jdbc=AuthTestDatabase.create();
        addUnrelatedPolicy(jdbc);
        rerunCatalog(jdbc,"20261126_user_management.sql");
        rerunCatalog(jdbc,"20261126_user_management.sql");
        assertCreationPolicies(jdbc);
        rerunCatalog(jdbc,"20261126_user_creation_soft_delete.sql");
        rerunCatalog(jdbc,"20261126_user_creation_soft_delete.sql");
        assertCreationPolicies(jdbc);
        assertThat(grants(jdbc,"GET","/api/user/users")).containsExactly("USER_VIEW");
        assertThat(grants(jdbc,"GET","/api/user/users/custom-extension")).containsExactly("BOOKS.VIEW");
    }

    @Test void creationRerunRepairsOnlyObsoleteUserViewOnTheThreeExactEntryRoutes() throws Exception {
        var jdbc=AuthTestDatabase.create();addUnrelatedPolicy(jdbc);
        jdbc.update("""
            INSERT INTO endpoint_permissions(endpoint_id,permission_id,created_at)
            SELECT e.endpoint_id,p.permission_id,CURRENT_TIMESTAMP FROM api_endpoints e CROSS JOIN permissions p
            WHERE p.permission_code='USER_VIEW' AND e.service_code='BOOK_SEARCH' AND
              ((e.http_method='GET' AND e.path_pattern IN('/api/user/users/create-options','/api/user/users/capabilities'))
              OR (e.http_method='POST' AND e.path_pattern='/api/user/users'))
            """);
        assertThat(grants(jdbc,"POST","/api/user/users")).containsExactlyInAnyOrder("USER_CREATE","USER_VIEW");
        rerunCatalog(jdbc,"20261126_user_creation_soft_delete.sql");
        rerunCatalog(jdbc,"20261126_user_creation_soft_delete.sql");
        assertCreationPolicies(jdbc);
        assertThat(grants(jdbc,"GET","/api/user/users")).containsExactly("USER_VIEW");
        assertThat(grants(jdbc,"GET","/api/user/users/custom-extension")).containsExactly("BOOKS.VIEW");
    }

    private void assertCreationPolicies(JdbcTemplate jdbc){
        assertThat(grants(jdbc,"POST","/api/user/users")).containsExactly("USER_CREATE");
        assertThat(grants(jdbc,"GET","/api/user/users/create-options")).containsExactly("USER_CREATE");
        assertThat(grants(jdbc,"GET","/api/user/users/capabilities")).containsExactly("ACCOUNT.VIEW");
    }
    @Test void ownProfileAndTenantChoiceCatalogRerunsRemainIndependentOfManagementViewing() throws Exception {
        var jdbc=AuthTestDatabase.create();
        rerunCatalog(jdbc,"20261126_user_management.sql");
        rerunCatalog(jdbc,"20261126_user_creation_soft_delete.sql");
        rerunCatalog(jdbc,"20261126_profile_permissions_add_user.sql");
        rerunCatalog(jdbc,"20261126_profile_permissions_add_user.sql");
        assertThat(grants(jdbc,"GET","/api/user/profile/details")).containsExactly("ACCOUNT.VIEW");
        assertThat(grants(jdbc,"GET","/api/user/profile/permissions")).containsExactly("ACCOUNT.VIEW");
        assertThat(grants(jdbc,"GET","/api/user/users/create-tenants")).containsExactly("USER_CREATE");
        assertCreationPolicies(jdbc);
    }
    private List<String> grants(JdbcTemplate jdbc,String method,String path){return jdbc.queryForList("SELECT p.permission_code FROM endpoint_permissions ep JOIN permissions p ON p.permission_id=ep.permission_id JOIN api_endpoints e ON e.endpoint_id=ep.endpoint_id WHERE e.service_code='BOOK_SEARCH' AND e.http_method=? AND e.path_pattern=? ORDER BY p.permission_code",String.class,method,path);}
    private void addUnrelatedPolicy(JdbcTemplate jdbc){
        jdbc.update("INSERT INTO api_endpoints(service_code,http_method,path_pattern,description,is_public,is_active,created_at) VALUES('BOOK_SEARCH','GET','/api/user/users/custom-extension','Unrelated extension',FALSE,TRUE,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO endpoint_permissions(endpoint_id,permission_id,created_at) SELECT e.endpoint_id,p.permission_id,CURRENT_TIMESTAMP FROM api_endpoints e CROSS JOIN permissions p WHERE e.path_pattern='/api/user/users/custom-extension' AND p.permission_code='BOOKS.VIEW'");
    }
    private void rerunCatalog(JdbcTemplate jdbc,String name)throws Exception{
        String source=new ClassPathResource("db/manual/"+name).getContentAsString(StandardCharsets.UTF_8);
        // Actual production catalog SQL after the already-applied MySQL-only ALTER procedure.
        String sql=(source.contains("DELIMITER ;")?source.substring(source.indexOf("DELIMITER ;")+"DELIMITER ;".length()):source)
                .replaceAll("(?m)^SET time_zone[^;]*;", "")
                .replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci","")
                .replace(") STORED",")").replace("UTC_TIMESTAMP(6)","CURRENT_TIMESTAMP");
        new ResourceDatabasePopulator(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8))).execute(jdbc.getDataSource());
    }
}
