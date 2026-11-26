package com.book.user;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import com.book.storage.TenantStoragePaths;
import com.book.user.security.UserPrincipal;
import com.book.user.service.AuthorizationService;
import com.book.user.service.TenantProvisioningService;
import com.book.user.service.UserDeletionFiles;
import com.book.user.service.UserDeletionService;
import com.book.user.service.UserManagementService;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;

/** Real scoped SQL with either H2 Timestamp values or MySQL Connector/J DATETIME map values. */
class UserManagementTimestampTests {
    private static final Instant CREATED = Instant.parse("2026-11-26T02:34:56.123456Z");
    @TempDir Path temporary;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void profileListAndDetailsAcceptBothJdbcTimestampRepresentations(boolean mysqlDates) {
        var fixture = fixture(mysqlDates);
        Object raw = fixture.jdbc.queryForMap("SELECT created_at FROM users WHERE user_id=?", fixture.operator.userId()).get("created_at");
        assertThat(raw).isInstanceOf(mysqlDates ? LocalDateTime.class : Timestamp.class);

        var profile = fixture.users.current(fixture.operator);
        assertThat(profile.createdAt()).isEqualTo(CREATED);
        assertThat(profile.lastLogin()).isNull();

        var page = fixture.users.list(fixture.operator, "", "ALL", null, 0, 20);
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.users()).allSatisfy(user -> assertThat(user.createdAt()).isEqualTo(CREATED));

        var details = fixture.users.details(fixture.operator, fixture.target);
        assertThat(details.emailVerifiedAt()).isEqualTo(CREATED);
        assertThat(details.updatedAt()).isEqualTo(CREATED);
        assertThat(details.user().createdAt()).isEqualTo(CREATED);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nullableProfileDatesRemainMissingWithoutInventingValues(boolean mysqlDates) {
        var fixture = fixture(mysqlDates);
        fixture.jdbc.update("UPDATE users SET email_verified_at=NULL WHERE user_id=?", fixture.target);
        var details = fixture.users.details(fixture.operator, fixture.target);
        assertThat(details.emailVerifiedAt()).isNull();
        assertThat(details.user().lastLogin()).isNull();
        assertThat(details.updatedAt()).isEqualTo(CREATED);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void durableJobStatusUsesTheSameUtcTimestampConversion(boolean mysqlDates) {
        var fixture = fixture(mysqlDates);
        fixture.jdbc.update("""
                INSERT INTO user_deletion_jobs(job_key,tenant_id,target_user_id,actor_user_id,deletion_scope,
                    status,manifest,db_purged,attempts,created_at,updated_at)
                VALUES('timestamp-fixture',?, ?,?,'TENANT_PURGE','FAILED','{}',FALSE,1,?,?)
                """, fixture.operator.tenantId(), fixture.target, fixture.operator.userId(), Timestamp.from(CREATED), Timestamp.from(CREATED));
        long jobId = fixture.jdbc.queryForObject("SELECT job_id FROM user_deletion_jobs WHERE job_key='timestamp-fixture'", Long.class);
        assertThat(fixture.deletion.get(fixture.operator, jobId).updatedAt()).isEqualTo(CREATED);
        assertThat(fixture.deletion.list(fixture.operator)).singleElement().satisfies(job -> {
            assertThat(job.updatedAt()).isEqualTo(CREATED);
            assertThat(job.status()).isEqualTo("FAILED");
        });
    }

    private Fixture fixture(boolean mysqlDates) {
        var source = AuthTestDatabase.create().getDataSource();
        var jdbc = new JdbcTemplate(source) {
            @Override protected RowMapper<Map<String, Object>> getColumnMapRowMapper() {
                return new ColumnMapRowMapper() {
                    @Override protected Object getColumnValue(ResultSet rs, int index) throws SQLException {
                        Object value = super.getColumnValue(rs, index);
                        // The production connection stores UTC DATETIME fields. Emulate Connector/J's
                        // UTC wall-clock representation rather than the host JVM's local time zone.
                        return mysqlDates && value instanceof Timestamp timestamp
                                ? LocalDateTime.ofInstant(timestamp.toInstant(), ZoneOffset.UTC) : value;
                    }
                };
            }
        };
        var storage = new TenantStoragePaths(temporary.toString());
        var clock = Clock.fixed(CREATED, ZoneOffset.UTC);
        var provisioning = new TenantProvisioningService(jdbc, clock, storage);
        var operator = provisioning.create("INDIVIDUAL", "PASSWORD", "dates-operator@example.com", "Date operator", null, null, true);
        jdbc.update("UPDATE roles SET role_code='SUPER_USER',is_system=TRUE WHERE tenant_id=?", operator.tenantId());
        var target = provisioning.create("INDIVIDUAL", "PASSWORD", "dates-target@example.com", "Date target", null, null, true);
        var authorization = new AuthorizationService(jdbc);
        var users = new UserManagementService(jdbc, authorization, clock);
        var actor = new UserPrincipal(operator.userId(), operator.tenantId(), operator.membershipId(), 1, 1, 1, 1,
                authorization.authorizationFingerprint(operator.tenantId(), operator.membershipId()));
        var deletion = new UserDeletionService(jdbc, users, new UserDeletionFiles(storage), storage,
                new DataSourceTransactionManager(source), clock, authorization);
        return new Fixture(jdbc, users, deletion, actor, target.userId());
    }

    private record Fixture(JdbcTemplate jdbc, UserManagementService users, UserDeletionService deletion,
                           UserPrincipal operator, long target) { }
}
