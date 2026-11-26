package com.book.user;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import com.book.user.config.AuthSettings;
import com.book.user.dto.AuthenticatedIdentity;
import com.book.user.exception.AuthException;
import com.book.user.service.AccessTokenService;
import com.book.user.service.AuthorizationService;
import com.book.user.service.SecurityAuditService;
import com.book.user.service.SessionService;
import com.book.user.service.SessionService.IssuedSession;
import com.book.user.service.TenantProvisioningService;
import com.book.user.util.SecretDigests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import static org.assertj.core.api.Assertions.*;

class SessionSecurityTests {
    private static final Instant START = Instant.parse("2026-11-25T00:00:00Z");
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private MutableClock clock;
    private SessionService sessions;
    private AccessTokenService tokens;
    private AuthenticatedIdentity identity;
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false) @EnableTransactionManagement
    static class Transactions { }
    @BeforeEach void setup() {
        jdbc = AuthTestDatabase.create();
        clock = new MutableClock();
        var environment = new MockEnvironment().withProperty("app.user.jwt.secret",
                Base64.getEncoder().encodeToString("test-only-key-32-bytes-long-exactly!!".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        context = new AnnotationConfigApplicationContext();
        context.setEnvironment(environment);
        context.register(Transactions.class, AuthSettings.class, AccessTokenService.class, AuthorizationService.class,
                SecurityAuditService.class, SessionService.class, TenantProvisioningService.class);
        context.registerBean(JdbcTemplate.class, () -> jdbc);
        context.registerBean(Clock.class, () -> clock);
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(jdbc.getDataSource()));
        context.registerBean(com.book.storage.TenantStoragePaths.class, AuthTestDatabase::storage);
        context.refresh();
        sessions = context.getBean(SessionService.class);
        tokens = context.getBean(AccessTokenService.class);
        var created = context.getBean(TenantProvisioningService.class)
                .create("INDIVIDUAL", "PASSWORD", "session@example.com", "Session User", null, null, true);
        jdbc.update("INSERT INTO local_credentials(user_id,password_hash,password_changed_at,failed_attempts,created_at,updated_at) VALUES(?,?,?,0,?,?)",
                created.userId(), "{pbkdf2}not-used-by-these-session-tests", Timestamp.from(START), Timestamp.from(START), Timestamp.from(START));
        identity = new AuthenticatedIdentity(created.userId(), created.tenantId(), created.membershipId(), "PASSWORD", null, null, 1);
    }
    @AfterEach void close() { if (context != null) context.close(); }

    @Test void loginIssuesExactLifetimesMemoryResponseAndOnlyDigestsInDatabase() {
        var issued = sessions.issue(identity);
        assertThat(issued.response().accessExpiresAt()).isEqualTo(START.plusSeconds(300));
        assertThat(issued.response().refreshExpiresAt()).isEqualTo(START.plusSeconds(600));
        assertThat(issued.response().absoluteExpiresAt()).isNull();
        var principal = tokens.verify(issued.response().accessToken());
        assertThat(principal.userId()).isEqualTo(identity.userId());
        assertThat(principal.tenantId()).isEqualTo(identity.tenantId());
        assertThat(principal.membershipId()).isEqualTo(identity.membershipId());
        assertThat(jdbc.queryForObject("SELECT token_digest FROM refresh_tokens", byte[].class)).isEqualTo(SecretDigests.digest(issued.refreshToken()));
        assertThat(issued.refreshToken()).hasSize(43);
        assertThat(jdbc.queryForObject("SELECT refresh_expires_at FROM auth_sessions", Timestamp.class).toInstant()).isEqualTo(START.plusSeconds(600));
    }
    @Test void accessExpiresAtFiveMinutesButRefreshStillRotatesAfterAccessExpiry() {
        var issued = sessions.issue(identity);
        clock.now = START.plusSeconds(299);
        sessions.validate(tokens.verify(issued.response().accessToken()));
        clock.now = START.plusSeconds(300);
        assertThatThrownBy(() -> tokens.verify(issued.response().accessToken())).isInstanceOf(AuthException.class);
        clock.now = START.plusSeconds(301);
        var rotated = sessions.refresh(issued.refreshToken());
        assertThat(rotated.response().accessExpiresAt()).isEqualTo(clock.now.plusSeconds(300));
        assertThat(rotated.response().refreshExpiresAt()).isEqualTo(clock.now.plusSeconds(600));
        assertThat(rotated.refreshToken()).isNotEqualTo(issued.refreshToken());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens WHERE parent_refresh_token_id IS NOT NULL", Long.class)).isEqualTo(1);
        assertThat(activeRefreshes()).isEqualTo(1);
    }
    @Test void fourMinuteRotationCreatesNineAndFourteenMinuteDeadlinesWithNoAbsoluteCap() {
        var issued = sessions.issue(identity);
        clock.now = START.plusSeconds(240);
        var rotated = sessions.refresh(issued.refreshToken());
        assertThat(rotated.response().accessExpiresAt()).isEqualTo(START.plusSeconds(540));
        assertThat(rotated.response().refreshExpiresAt()).isEqualTo(START.plusSeconds(840));
        clock.now = START.plusSeconds(839);
        assertThat(sessions.refresh(rotated.refreshToken()).response().refreshExpiresAt()).isEqualTo(clock.now.plusSeconds(600));
    }
    @Test void equalityWithRefreshDeadlineIsExpiredAndNormalValidationDoesNotSlideIt() {
        var issued = sessions.issue(identity);
        var principal = tokens.verify(issued.response().accessToken());
        clock.now = START.plusSeconds(240);
        var rotated = sessions.refresh(issued.refreshToken());
        var refreshedPrincipal = tokens.verify(rotated.response().accessToken());
        clock.now = START.plusSeconds(500);
        sessions.validate(refreshedPrincipal);
        assertThat(jdbc.queryForObject("SELECT refresh_expires_at FROM auth_sessions", Timestamp.class).toInstant()).isEqualTo(START.plusSeconds(840));
        clock.now = START.plusSeconds(840);
        assertThatThrownBy(() -> sessions.refresh(rotated.refreshToken())).isInstanceOf(AuthException.class);
        assertThat(activeRefreshes()).isZero();
        assertThatThrownBy(() -> sessions.validate(principal)).isInstanceOf(AuthException.class);
    }
    @Test void replayRevokesNewFamilyAndTheRevocationCommitsDespiteUnauthorizedReturn() {
        var issued = sessions.issue(identity);
        var rotated = sessions.refresh(issued.refreshToken());
        assertThatThrownBy(() -> sessions.refresh(issued.refreshToken())).isInstanceOf(AuthException.class);
        assertThat(jdbc.queryForObject("SELECT revoked_at FROM auth_sessions", Timestamp.class)).isNotNull();
        assertThat(activeRefreshes()).isZero();
        assertThatThrownBy(() -> sessions.refresh(rotated.refreshToken())).isInstanceOf(AuthException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE event_code='REFRESH_REUSE'", Long.class)).isEqualTo(1);
    }
    @Test void concurrentUseHasOneRotationThenReplayRevokesTheFamily() throws Exception {
        var issued = sessions.issue(identity);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = List.of(executor.submit(() -> attempt(start, issued)), executor.submit(() -> attempt(start, issued)));
            start.countDown();
            var outcomes = List.of(tasks.get(0).get(10, TimeUnit.SECONDS), tasks.get(1).get(10, TimeUnit.SECONDS));
            assertThat(outcomes).containsExactlyInAnyOrder("ROTATED", "REJECTED");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens", Long.class)).isEqualTo(2);
        assertThat(activeRefreshes()).isZero();
    }
    private String attempt(CountDownLatch start, IssuedSession issued) throws InterruptedException {
        start.await();
        try { sessions.refresh(issued.refreshToken()); return "ROTATED"; }
        catch (AuthException failure) { return "REJECTED"; }
    }
    @Test void logoutImmediatelyInvalidatesStillUnexpiredAccessAndEveryRefresh() {
        var issued = sessions.issue(identity);
        var principal = tokens.verify(issued.response().accessToken());
        sessions.logout(issued.refreshToken());
        tokens.verify(issued.response().accessToken()); // Signature/exp alone still pass, authoritative state must not.
        assertThatThrownBy(() -> sessions.validate(principal)).isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> sessions.refresh(issued.refreshToken())).isInstanceOf(AuthException.class);
        assertThat(activeRefreshes()).isZero();
    }
    @Test void userSecurityChangesRejectOldTokensAndStaleAuthenticationResults() {
        var issued = sessions.issue(identity);
        jdbc.update("UPDATE users SET security_version=security_version+1");
        assertInvalid(issued);
        assertThatThrownBy(() -> sessions.issue(identity)).isInstanceOf(AuthException.class);
    }
    @Test void tenantAuthorizationVersionChangesRejectAccessAndRefresh() {
        var issued = sessions.issue(identity);
        jdbc.update("UPDATE tenants SET authz_version=authz_version+1");
        assertInvalid(issued);
    }
    @Test void membershipAuthorizationVersionChangesRejectAccessAndRefresh() {
        var issued = sessions.issue(identity);
        jdbc.update("UPDATE tenant_memberships SET authz_version=authz_version+1");
        assertInvalid(issued);
    }
    @Test void roleVersionAndPermissionStatusAreEnforcedBeyondJwtClaims() {
        var issued = sessions.issue(identity);
        jdbc.update("UPDATE roles SET authz_version=authz_version+1");
        assertInvalid(issued);
        var fresh = sessions.issue(identity);
        jdbc.update("UPDATE permissions SET is_active=FALSE WHERE permission_code='BOOKS.VIEW'");
        assertInvalid(fresh);
    }
    @Test void revokedMembershipAndCrossTenantIdentitiesNeverAuthorize() {
        var issued = sessions.issue(identity);
        var other = context.getBean(TenantProvisioningService.class).create("INDIVIDUAL", "PASSWORD", "other@example.com", "Other", null, null, true);
        assertThatThrownBy(() -> sessions.issue(new AuthenticatedIdentity(identity.userId(), other.tenantId(), identity.membershipId(), "PASSWORD", null, null, 1)))
                .isInstanceOf(AuthException.class);
        jdbc.update("UPDATE tenant_memberships SET status='SUSPENDED' WHERE membership_id=?", identity.membershipId());
        assertInvalid(issued);
    }
    @Test void nullableAbsoluteDeadlineIsEnforcedWhenExplicitlySet() {
        var issued = sessions.issue(identity);
        var principal = tokens.verify(issued.response().accessToken());
        jdbc.update("UPDATE auth_sessions SET absolute_expires_at=?", Timestamp.from(START.plusSeconds(120)));
        clock.now = START.plusSeconds(119);
        sessions.validate(principal);
        clock.now = START.plusSeconds(120);
        assertThatThrownBy(() -> sessions.validate(principal)).isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> sessions.refresh(issued.refreshToken())).isInstanceOf(AuthException.class);
    }
    @Test void refreshRetainsSubsecondLifetimeAndCannotPassAFractionalAbsoluteDeadline() {
        clock.now = START.plusNanos(900_000_000);
        var issued = sessions.issue(identity);
        assertThat(issued.response().refreshExpiresAt()).isEqualTo(clock.now.plusSeconds(600));
        clock.now = START.plusSeconds(240).plusNanos(650_000_000);
        var rotated = sessions.refresh(issued.refreshToken());
        assertThat(rotated.response().refreshExpiresAt()).isEqualTo(clock.now.plusSeconds(600));
        var absolute = START.plusSeconds(250).plusNanos(100_000_000);
        jdbc.update("UPDATE auth_sessions SET absolute_expires_at=?", Timestamp.from(absolute));
        clock.now = absolute;
        assertThatThrownBy(() -> sessions.refresh(rotated.refreshToken())).isInstanceOf(AuthException.class);
    }
    @Test void malformedAndUnknownRefreshSecretsReturnUnauthorizedWithoutCreatingRecords() {
        assertThatThrownBy(() -> sessions.refresh(null)).isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> sessions.refresh(SecretDigests.randomSecret())).isInstanceOf(AuthException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_sessions", Long.class)).isZero();
    }
    @Test void configuredLocalFactorCannotBeSkippedOrReplacedByAnUnverifiedTimestamp() {
        jdbc.update("INSERT INTO local_mfa_factors(user_id,factor_type,secret_ref,status,confirmed_at,created_at) VALUES(?,'TOTP','env:UNCONFIGURED','ACTIVE',?,?)",
                identity.userId(), Timestamp.from(START), Timestamp.from(START));
        assertThatThrownBy(() -> sessions.issue(identity)).isInstanceOf(AuthException.class).hasMessageContaining("factor");
    }
    private void assertInvalid(IssuedSession issued) {
        assertThatThrownBy(() -> sessions.validate(tokens.verify(issued.response().accessToken()))).isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> sessions.refresh(issued.refreshToken())).isInstanceOf(AuthException.class);
    }
    private long activeRefreshes() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens WHERE consumed_at IS NULL AND revoked_at IS NULL", Long.class);
    }
    static class MutableClock extends Clock {
        volatile Instant now = START;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
