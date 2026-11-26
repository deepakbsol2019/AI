package com.book.user;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** Production 18-table DDL/catalog, with only MySQL storage syntax adapted to H2. */
public final class AuthTestDatabase {
    public static com.book.storage.TenantStoragePaths storage() {
        try { return new com.book.storage.TenantStoragePaths(java.nio.file.Files.createTempDirectory("tenant-storage-test-").toString()); }
        catch(IOException failure) { throw new IllegalStateException(failure); }
    }
    private AuthTestDatabase() { }
    public static JdbcTemplate create() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:auth_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        initialize(jdbc);
        return jdbc;
    }
    public static void initialize(JdbcTemplate jdbc) {
        try {
            String sql = new ClassPathResource("db/manual/20261125_user_auth.sql").getContentAsString(StandardCharsets.UTF_8)
                    .replaceAll("(?m)^SET time_zone[^;]*;", "")
                    .replaceAll("(?s)-- Existing catalog rows.*?DELIMITER ;", "")
                    .replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci", "")
                    .replace(" COLLATE utf8mb4_0900_bin", "")
                    .replace(") STORED", ")")
                    .replace("UTC_TIMESTAMP(6)", "CURRENT_TIMESTAMP");
            new ResourceDatabasePopulator(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)))
                    .execute(java.util.Objects.requireNonNull(jdbc.getDataSource()));
            String superUser = new ClassPathResource("db/manual/20261125_super_user_permissions.sql")
                    .getContentAsString(StandardCharsets.UTF_8)
                    .replaceAll("(?m)^SET time_zone[^;]*;", "")
                    .replace("UTC_TIMESTAMP(6)", "CURRENT_TIMESTAMP");
            new ResourceDatabasePopulator(new ByteArrayResource(superUser.getBytes(StandardCharsets.UTF_8)))
                    .execute(java.util.Objects.requireNonNull(jdbc.getDataSource()));
            String roles=new ClassPathResource("db/manual/20261125_role_management.sql").getContentAsString(StandardCharsets.UTF_8)
                    .replaceAll("(?m)^SET time_zone[^;]*;", "")
                    .replaceAll("(?s)DELIMITER \\$\\$.*?DELIMITER ;", "ALTER TABLE permissions ADD COLUMN IF NOT EXISTS is_delegable BOOLEAN NOT NULL DEFAULT FALSE; UPDATE permissions SET is_delegable=TRUE WHERE permission_code IN ('BOOKS.VIEW','BOOKS.UPLOAD','BOOKS.DOWNLOAD','BOOKS.EDIT','BOOKS.DELETE','ACCOUNT.VIEW');")
                    .replace("UTC_TIMESTAMP(6)","CURRENT_TIMESTAMP");
            new ResourceDatabasePopulator(new ByteArrayResource(roles.getBytes(StandardCharsets.UTF_8))).execute(jdbc.getDataSource());
            String management=new ClassPathResource("db/manual/20261126_user_management.sql").getContentAsString(StandardCharsets.UTF_8)
                    .replaceAll("(?m)^SET time_zone[^;]*;", "")
                    .replaceAll("(?s)DELIMITER \\$\\$.*?DELIMITER ;", "ALTER TABLE users ADD COLUMN IF NOT EXISTS management_version BIGINT NOT NULL DEFAULT 1;")
                    .replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci", "").replace(") STORED",")").replace("UTC_TIMESTAMP(6)","CURRENT_TIMESTAMP");
            new ResourceDatabasePopulator(new ByteArrayResource(management.getBytes(StandardCharsets.UTF_8))).execute(jdbc.getDataSource());
            String creation=new ClassPathResource("db/manual/20261126_user_creation_soft_delete.sql").getContentAsString(StandardCharsets.UTF_8)
                    .replaceAll("(?m)^SET time_zone[^;]*;", "")
                    .replaceAll("(?s)DELIMITER \\$\\$.*?DELIMITER ;", "ALTER TABLE users ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP(6) NULL;")
                    .replace("UTC_TIMESTAMP(6)","CURRENT_TIMESTAMP");
            new ResourceDatabasePopulator(new ByteArrayResource(creation.getBytes(StandardCharsets.UTF_8))).execute(jdbc.getDataSource());
            String profile=new ClassPathResource("db/manual/20261126_profile_permissions_add_user.sql").getContentAsString(StandardCharsets.UTF_8)
                    .replaceAll("(?m)^SET time_zone[^;]*;", "").replace("UTC_TIMESTAMP(6)","CURRENT_TIMESTAMP");
            new ResourceDatabasePopulator(new ByteArrayResource(profile.getBytes(StandardCharsets.UTF_8))).execute(jdbc.getDataSource());
            String history=new ClassPathResource("db/manual/20261126_upload_history.sql").getContentAsString(StandardCharsets.UTF_8)
                    .replaceAll("(?m)^SET time_zone[^;]*;", "")
                    .replaceAll("(?s)DELIMITER \\$\\$.*?DELIMITER ;", "")
                    .replace("UTC_TIMESTAMP(6)","CURRENT_TIMESTAMP");
            new ResourceDatabasePopulator(new ByteArrayResource(history.getBytes(StandardCharsets.UTF_8))).execute(jdbc.getDataSource());
        } catch (IOException error) { throw new IllegalStateException("The authentication schema could not be loaded.", error); }
    }
}
