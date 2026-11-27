package com.book.spreadsheet.persistence;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** Isolated catalog fixture: the production spreadsheet DDL is reused, not a second schema. */
public final class SpreadsheetTestDatabase {
    private SpreadsheetTestDatabase() { }
    public static JdbcTemplate create() throws Exception {
        var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:spreadsheet_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        var jdbc=new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE users(user_id BIGINT PRIMARY KEY,display_name VARCHAR(150))");
        jdbc.execute("INSERT INTO users VALUES(11,'First Uploader'),(22,'Second Uploader')");
        jdbc.execute("CREATE TABLE pdf_file_details(book_id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_id BIGINT,book_name VARCHAR(255))");
        jdbc.execute("CREATE TABLE pdf_file_status(book_upload_id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_id BIGINT NOT NULL,uploader_user_id BIGINT NOT NULL,file_name VARCHAR(1024),book_name VARCHAR(255),created_at DATETIME(6),updated_at DATETIME(6),processing_state VARCHAR(16),status VARCHAR(16) NOT NULL,message LONGTEXT NOT NULL,UNIQUE(tenant_id,book_upload_id))");
        String ddl=new ClassPathResource("db/manual/20261126_spreadsheets.sql").getContentAsString(StandardCharsets.UTF_8)
                .replaceAll("(?m)^SET time_zone[^;]*;","")
                .replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci","");
        ddl=com.book.user.AuthTestDatabase.documentTableNames(ddl);
        new ResourceDatabasePopulator(new ByteArrayResource(ddl.getBytes(StandardCharsets.UTF_8))).execute(source);
        return jdbc;
    }
    @SuppressWarnings("unchecked")
    public static <T>T transactional(T target,JdbcTemplate jdbc) {
        var proxy=new ProxyFactory(target);proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(jdbc.getDataSource()),new AnnotationTransactionAttributeSource()));
        return (T)proxy.getProxy();
    }
}
