package com.book.user;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import com.book.user.repository.AccountRepository;
import com.book.user.service.CredentialMailService;
import com.book.user.service.PasswordService;
import com.book.user.service.PasswordService.Registration;
import com.book.user.service.TenantProvisioningService;

class AccountFlowsTests {
    private static final String PASSWORD="a long test password";
    private JdbcTemplate jdbc;
    private AccountRepository accounts;
    private CredentialMailService mail;
    private PasswordService passwords;
    private MutableClock clock;
    private MockEnvironment environment;
    private PasswordEncoder encoder;
    private ControlledExecutor delivery;
    private DataSourceTransactionManager transactionManager;
    @BeforeEach void setup() {
        jdbc=AuthTestDatabase.create(); accounts=new AccountRepository(jdbc);
        clock=new MutableClock(); mail=mock(CredentialMailService.class);
        environment=new MockEnvironment();
        encoder=new DelegatingPasswordEncoder("pbkdf2",Map.of("pbkdf2",Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
        delivery=new ControlledExecutor();transactionManager=new DataSourceTransactionManager(jdbc.getDataSource());
        passwords=new PasswordService(accounts,new TenantProvisioningService(jdbc,clock, com.book.user.AuthTestDatabase.storage()),mail,encoder,clock,environment,
                transactionManager,delivery);
    }
    @Test void individualForcesPasswordProvisionsOnlyTenantAdminAndSetupVerifiesEmail() {
        passwords.register(new Registration("INDIVIDUAL","SSO","  PERSON@Example.com ","Person",null,null));
        var pending=accounts.byEmail("person@example.com",false).orElseThrow();
        assertThat(pending.userStatus()).isEqualTo("PENDING");
        assertThat(pending.loginPolicy()).isEqualTo("PASSWORD");
        assertThat(pending.passwordHash()).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_roles ur JOIN roles r ON r.role_id=ur.role_id AND r.tenant_id=ur.tenant_id WHERE r.role_code='OWNER' AND ur.membership_id=?",Long.class,pending.membershipId())).isEqualTo(1L);
        String token=lastToken(PasswordService.SETUP);
        assertThat(jdbc.queryForObject("SELECT token_digest FROM credential_tokens",byte[].class)).isEqualTo(PasswordService.digestSecret(token));
        passwords.completeSetup(token,PASSWORD);
        var active=accounts.byId(pending.userId(),false).orElseThrow();
        assertThat(active.active()).isTrue(); assertThat(active.securityVersion()).isEqualTo(2);
        assertThat(active.passwordHash()).startsWith("{pbkdf2}").doesNotContain(PASSWORD);
        assertThat(encoder.matches(PASSWORD,active.passwordHash())).isTrue();
        assertThat(passwords.authenticate("PERSON@example.com",PASSWORD).securityVersion()).isEqualTo(2);
        assertThatThrownBy(() -> passwords.completeSetup(token,PASSWORD)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void organizationBranchesSendPasswordSetupOrCreatePendingSsoWithoutCredentials() {
        passwords.register(new Registration("ORGANIZATION","PASSWORD","admin@example.com","Admin","books-org","Books Org"));
        lastToken(PasswordService.SETUP);
        reset(mail);
        passwords.register(new Registration("ORGANIZATION","SSO","sso@example.com","SSO Admin","sso-org","SSO Org"));
        verifyNoInteractions(mail);
        var sso=accounts.byEmail("sso@example.com",false).orElseThrow();
        assertThat(sso.tenantStatus()).isEqualTo("PENDING_SETUP"); assertThat(sso.passwordHash()).isNull();
        assertThat(jdbc.queryForMap("SELECT provider_type,status,issuer_url,client_id FROM identity_providers WHERE tenant_id=?",sso.tenantId()))
                .containsEntry("PROVIDER_TYPE","ORG_OIDC").containsEntry("STATUS","PENDING").containsEntry("ISSUER_URL",null).containsEntry("CLIENT_ID",null);
        assertThatThrownBy(() -> passwords.authenticate("sso@example.com",PASSWORD)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void mailFailureRollsBackWholeProvisionAndExistingEmailDoesNotCreateAnotherTenant() {
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)).when(mail).send(anyString(),anyString(),anyString(),any(),any());
        assertThatThrownBy(() -> individual("failure@example.com")).isInstanceOf(ResponseStatusException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tenants",Long.class)).isZero();
        reset(mail);individual("same@example.com");
        assertThatThrownBy(() -> individual("SAME@example.com")).isInstanceOf(ResponseStatusException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tenant_memberships",Long.class)).isEqualTo(1);
    }
    @Test void unavailableMailRejectsPasswordRegistrationBeforeAnyRows() {
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)).when(mail).requireAvailable();
        assertThatThrownBy(() -> individual("unavailable@example.com")).isInstanceOf(ResponseStatusException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users",Long.class)).isZero();
    }
    @Test void setupPurposeExpiryVersionAndIntendedEmailAreAuthoritative() {
        individual("pending@example.com");String token=lastToken(PasswordService.SETUP);
        assertThatThrownBy(() -> passwords.completeReset(token,PASSWORD,PASSWORD)).isInstanceOf(ResponseStatusException.class);
        clock.now=clock.now.plus(Duration.ofHours(24));
        assertThatThrownBy(() -> passwords.completeSetup(token,PASSWORD)).isInstanceOf(ResponseStatusException.class);
        clock.now=clock.now.minus(Duration.ofHours(24));
        jdbc.update("UPDATE users SET primary_email='different@example.com'");
        assertThatThrownBy(() -> passwords.completeSetup(token,PASSWORD)).isInstanceOf(ResponseStatusException.class);
        jdbc.update("UPDATE users SET primary_email='pending@example.com',security_version=security_version+1");
        assertThatThrownBy(() -> passwords.completeSetup(token,PASSWORD)).isInstanceOf(ResponseStatusException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM local_credentials",Long.class)).isZero();
    }
    @Test void wrongPasswordsPersistLockoutWithGenericErrorsAndRecoverOnlyAfterDeadline() {
        activate("login@example.com");
        for (int i=0;i<5;i++) assertThatThrownBy(() -> passwords.authenticate("login@example.com","wrong password"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Email or password is invalid");
        assertThat(accounts.byEmail("login@example.com",false).orElseThrow().failedAttempts()).isEqualTo(5);
        assertThatThrownBy(() -> passwords.authenticate("login@example.com",PASSWORD)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> passwords.authenticate("missing@example.com",PASSWORD)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Email or password is invalid");
        clock.now=clock.now.plus(Duration.ofMinutes(15));
        assertThat(passwords.authenticate("login@example.com",PASSWORD).userId()).isPositive();
        assertThat(accounts.byEmail("login@example.com",false).orElseThrow().failedAttempts()).isZero();
    }
    @Test void forgotSkipsMissingAndSsoAccountsAndResetCannotBeUsedForSetup() {
        passwords.requestReset("missing@example.com"); verify(mail).requireAvailable(); verifyNoMoreInteractions(mail);
        activate("reset@example.com"); reset(mail);
        passwords.requestReset("reset@example.com");String token=lastToken(PasswordService.RESET);
        assertThatThrownBy(() -> passwords.completeSetup(token,PASSWORD)).isInstanceOf(ResponseStatusException.class);
        clock.now=clock.now.plus(Duration.ofMinutes(30));
        assertThatThrownBy(() -> passwords.completeReset(token,"another long password","another long password")).isInstanceOf(ResponseStatusException.class);
    }
    @Test void forgotKnownAndUnknownReturnWithoutWaitingForMailerOrCreatingAnUnknownDeliveryJob() {
        activate("queued@example.com");reset(mail);
        passwords.requestReset("queued@example.com");
        passwords.requestReset("missing@example.com");
        verify(mail,never()).send(anyString(),anyString(),anyString(),any(),any());
        assertThat(delivery.tasks).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM credential_tokens WHERE purpose='PASSWORD_RESET'",Long.class)).isEqualTo(1);
        delivery.runAll();
        verify(mail).send(eq("queued@example.com"),eq(PasswordService.RESET),anyString(),any(),any());
    }
    @Test void smtpOutageDoesNotExposeResetEligibilityAndRevokesItsUndeliveredToken() {
        activate("delivery@example.com");reset(mail);
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"SMTP unavailable"))
                .when(mail).send(anyString(),eq(PasswordService.RESET),anyString(),any(),any());
        assertThatCode(() -> passwords.requestReset("delivery@example.com")).doesNotThrowAnyException();
        assertThatCode(() -> passwords.requestReset("missing@example.com")).doesNotThrowAnyException();
        verify(mail,never()).send(anyString(),anyString(),anyString(),any(),any());
        assertThat(delivery.tasks).hasSize(1);
        delivery.runAll();
        String failed=lastToken(PasswordService.RESET);
        assertThatThrownBy(() -> passwords.completeReset(failed,"another long password","another long password")).isInstanceOf(ResponseStatusException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM credential_tokens WHERE purpose='PASSWORD_RESET' AND revoked_at IS NOT NULL",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE event_code='PASSWORD_RESET_DELIVERY' AND outcome='FAILURE'",Long.class)).isEqualTo(1);
    }
    @Test void rejectedDeliveryStillReturnsNormallyAndRevokesOnlyItsCommittedToken() {
        activate("rejected@example.com");reset(mail);delivery.reject=true;
        assertThatCode(() -> passwords.requestReset("rejected@example.com")).doesNotThrowAnyException();
        verify(mail,never()).send(anyString(),anyString(),anyString(),any(),any());
        assertThat(delivery.tasks).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM credential_tokens WHERE purpose='PASSWORD_RESET' AND revoked_at IS NOT NULL",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE event_code='PASSWORD_RESET_DELIVERY'",Long.class)).isEqualTo(1);
    }
    @Test void transactionRollbackCannotEnqueueOrSendAnUncommittedCredentialLink() {
        activate("rollback@example.com");reset(mail);
        var outer=new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> outer.executeWithoutResult(status -> {
            passwords.requestReset("rollback@example.com");
            assertThat(delivery.tasks).isEmpty();
            throw new IllegalStateException("roll back this request");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(delivery.tasks).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM credential_tokens WHERE purpose='PASSWORD_RESET'",Long.class)).isZero();
        verify(mail,never()).send(anyString(),anyString(),anyString(),any(),any());
    }
    @Test void cleanupOutageCannotExposeEligibilityOrSecretsAndDoesNotMaskPreCommitFailures() {
        activate("cleanup@example.com");reset(mail);
        var failing=spy(accounts);
        doThrow(new IllegalStateException("private cleanup@example.com token-bearing-link"))
                .when(failing).audit(isNull(),isNull(),eq("PASSWORD_RESET_DELIVERY"),eq("FAILURE"),any(Instant.class));
        var service=new PasswordService(failing,new TenantProvisioningService(jdbc,clock, com.book.user.AuthTestDatabase.storage()),mail,encoder,clock,environment,
                transactionManager,delivery);
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(PasswordService.class);
        var events=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        events.start();logger.addAppender(events);
        try {
            delivery.reject=true;
            assertThatCode(() -> service.requestReset("cleanup@example.com")).doesNotThrowAnyException();
            assertThatCode(() -> service.requestReset("missing@example.com")).doesNotThrowAnyException();
            assertThat(delivery.tasks).isEmpty();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM credential_tokens WHERE purpose='PASSWORD_RESET'",Long.class)).isEqualTo(1);

            delivery.reject=false;
            doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"private SMTP token-bearing-link"))
                    .when(mail).send(anyString(),eq(PasswordService.RESET),anyString(),any(),any());
            assertThatCode(() -> service.requestReset("cleanup@example.com")).doesNotThrowAnyException();
            assertThatCode(() -> service.requestReset("missing@example.com")).doesNotThrowAnyException();
            assertThat(delivery.tasks).hasSize(1);
            assertThatCode(delivery::runAll).doesNotThrowAnyException();
            assertThat(events.list).hasSize(2).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).isEqualTo("CREDENTIAL_DELIVERY_CLEANUP_FAILED");
                assertThat(event.getThrowableProxy()).isNull();
            });

            doThrow(new IllegalStateException("primary issuance failure"))
                    .when(failing).issueCredential(anyLong(),anyString(),anyString(),any(byte[].class),anyLong(),isNull(),any(),any());
            assertThatThrownBy(() -> service.requestReset("cleanup@example.com"))
                    .isInstanceOf(IllegalStateException.class).hasMessage("primary issuance failure");
            assertThat(events.list).hasSize(2);
            assertThat(delivery.tasks).isEmpty();
        } finally {
            logger.detachAppender(events);events.stop();
        }
    }
    @Test void lateFailureForAnOlderDeliveryDoesNotRevokeTheNewerDigest() {
        activate("newer@example.com");reset(mail);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(invocation -> {
            if(calls.incrementAndGet()==1) throw new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
            return null;
        }).when(mail).send(anyString(),eq(PasswordService.RESET),anyString(),any(),any());
        passwords.requestReset("newer@example.com");passwords.requestReset("newer@example.com");
        delivery.runNext();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM credential_tokens WHERE purpose='PASSWORD_RESET' AND revoked_at IS NULL",Long.class)).isEqualTo(1);
        delivery.runNext();
        String latest=lastToken(PasswordService.RESET);
        passwords.completeReset(latest,"newer long password","newer long password");
        assertThat(passwords.authenticate("newer@example.com","newer long password").userId()).isPositive();
    }
    @Test void aLinkThatExpiresInTheQueueIsNotDeliveredOrMadeUsable() {
        activate("expired-queue@example.com");reset(mail);
        passwords.requestReset("expired-queue@example.com");
        clock.now=clock.now.plus(Duration.ofMinutes(30));delivery.runAll();
        verify(mail,never()).send(anyString(),anyString(),anyString(),any(),any());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM credential_tokens WHERE purpose='PASSWORD_RESET' AND revoked_at IS NOT NULL",Long.class)).isEqualTo(1);
    }
    @Test void forgotRecoversExpiredPendingSetupWithADistinctFreshSingleUseSetupLink() {
        individual("pending-resend@example.com");String expired=lastToken(PasswordService.SETUP);
        clock.now=clock.now.plus(Duration.ofHours(24));
        passwords.requestReset("pending-resend@example.com");String fresh=lastToken(PasswordService.SETUP);
        assertThat(fresh).isNotEqualTo(expired);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM credential_tokens WHERE purpose='PASSWORD_RESET'",Long.class)).isZero();
        assertThatThrownBy(() -> passwords.completeSetup(expired,PASSWORD)).isInstanceOf(ResponseStatusException.class);
        passwords.completeSetup(fresh,PASSWORD);
        assertThat(accounts.byEmail("pending-resend@example.com",false).orElseThrow().active()).isTrue();
    }
    @Test void perUserEmailBurstLimitIncludesRevokedLinksAndReturnsNormallyUntilWindowExpires() {
        individual("limited@example.com");reset(mail);
        for(int request=0;request<7;request++) assertThatCode(() -> passwords.requestReset("limited@example.com")).doesNotThrowAnyException();
        delivery.runAll();
        verify(mail,times(4)).send(eq("limited@example.com"),eq(PasswordService.SETUP),anyString(),any(),any());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM credential_tokens",Long.class)).isEqualTo(5);
        clock.now=clock.now.plus(Duration.ofMinutes(15));
        passwords.requestReset("limited@example.com");
        delivery.runAll();
        verify(mail,times(5)).send(eq("limited@example.com"),eq(PasswordService.SETUP),anyString(),any(),any());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM credential_tokens",Long.class)).isEqualTo(6);
    }
    @Test void resetBumpsSecurityVersionAndRevokesSessionsAndOutstandingCredentialLinks() {
        activate("revoke@example.com");var account=accounts.byEmail("revoke@example.com",false).orElseThrow();
        var now=AccountRepository.timestamp(clock.instant());
        jdbc.update("""
                INSERT INTO auth_sessions(user_id,tenant_id,membership_id,auth_method,user_security_version,
                tenant_authz_version,membership_authz_version,authorization_fingerprint,created_at,last_seen_at,refresh_expires_at)
                VALUES (?,?,?,'PASSWORD',2,1,1,'fingerprint',?,?,?)
                """,account.userId(),account.tenantId(),account.membershipId(),now,now,AccountRepository.timestamp(clock.now.plusSeconds(600)));
        long session=jdbc.queryForObject("SELECT session_id FROM auth_sessions",Long.class);
        jdbc.update("INSERT INTO refresh_tokens(session_id,token_digest,issued_at,expires_at) VALUES (?,?,?,?)",session,new byte[32],now,AccountRepository.timestamp(clock.now.plusSeconds(600)));
        reset(mail);passwords.requestReset("revoke@example.com");String token=lastToken(PasswordService.RESET);
        passwords.completeReset(token,"another long password","another long password");
        assertThat(accounts.byId(account.userId(),false).orElseThrow().securityVersion()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT revoked_at FROM auth_sessions",java.sql.Timestamp.class)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT revoked_at FROM refresh_tokens",java.sql.Timestamp.class)).isNotNull();
        assertThatThrownBy(() -> passwords.completeReset(token,PASSWORD,PASSWORD)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> passwords.authenticate("revoke@example.com",PASSWORD)).isInstanceOf(ResponseStatusException.class);
        assertThat(passwords.authenticate("revoke@example.com","another long password").securityVersion()).isEqualTo(3);
    }
    @Test void concurrentSetupCanConsumeTheSecretOnlyOnce() throws Exception {
        individual("race@example.com");String token=lastToken(PasswordService.SETUP);
        CountDownLatch start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Boolean> attempt=() -> { start.await();try { passwords.completeSetup(token,PASSWORD);return true; }
                catch(ResponseStatusException expected) { return false; } };
            Future<Boolean> first=pool.submit(attempt),second=pool.submit(attempt);start.countDown();
            assertThat(List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM local_credentials",Long.class)).isEqualTo(1);
        assertThat(accounts.byEmail("race@example.com",false).orElseThrow().securityVersion()).isEqualTo(2);
    }
    @Test void passwordAndEnrollmentDtosNeverPrintSecrets() {
        assertThat(new com.book.user.controller.AccountController.Password("secret-token","secret-password").toString()).doesNotContain("secret-token","secret-password");
        assertThat(new com.book.user.provider.OidcIntegration.InitiationController.SsoRequest("company","secret-token").toString()).doesNotContain("secret-token");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"abcde","aaaaaaaaaaaaaaaaaaaaaaaaa","     ","12345","\uD83D\uDE00\uD83D\uDE00a"," e\u0301 x"})
    void resetAcceptsExactBoundariesUnicodeAndNoCompositionRules(String candidate) {
        activate("boundary@example.com");reset(mail);
        passwords.requestReset(" BOUNDARY@EXAMPLE.COM ");String token=lastToken(PasswordService.RESET);
        verify(mail).send(eq("boundary@example.com"),eq("PASSWORD_RESET"),eq(token),isNull(),eq(Duration.ofMinutes(15)));
        passwords.completeReset(token,candidate,candidate);
        assertThat(encoder.matches(candidate,accounts.byEmail("boundary@example.com",false).orElseThrow().passwordHash())).isTrue();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"abcd","aaaaaaaaaaaaaaaaaaaaaaaaaa","\uD83D\uDE00\uD83D\uDE00","\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00"})
    void resetRejectsInvalidLengthsWithoutConsumingToken(String candidate) {
        activate("boundary@example.com");reset(mail);
        passwords.requestReset("boundary@example.com");String token=lastToken(PasswordService.RESET);
        assertThatThrownBy(() -> passwords.completeReset(token,candidate,candidate)).hasMessageContaining("5 to 25");
        passwords.completeReset(token,"abcde","abcde");
    }
    @Test void resetRequiresBackendConfirmationAndAuditsFailureWithoutSecrets() {
        activate("match@example.com");reset(mail);passwords.requestReset("match@example.com");String token=lastToken(PasswordService.RESET);
        assertThatThrownBy(() -> passwords.completeReset(token,"abcde",null)).hasMessageContaining("match exactly");
        assertThatThrownBy(() -> passwords.completeReset(token,"abcde","ABCDE")).hasMessageContaining("match exactly");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE event_code='PASSWORD_RESET' AND outcome='FAILURE'",Long.class)).isEqualTo(2);
        passwords.completeReset(token,"abcde","abcde");
    }
    @Test void resetRejectsExpiredRevokedWrongBindingAndProviderOnlyAccounts() {
        activate("invalid@example.com");reset(mail);passwords.requestReset("invalid@example.com");String token=lastToken(PasswordService.RESET);
        clock.now=clock.now.plusSeconds(900);
        assertThatThrownBy(() -> passwords.completeReset(token,"abcde","abcde")).hasMessageContaining("invalid or expired");
        clock.now=clock.now.minusSeconds(900);
        jdbc.update("UPDATE credential_tokens SET revoked_at=created_at WHERE purpose=?",PasswordService.RESET);
        assertThatThrownBy(() -> passwords.completeReset(token,"abcde","abcde")).hasMessageContaining("invalid or expired");
        jdbc.update("UPDATE credential_tokens SET revoked_at=NULL,target_email='other@example.com' WHERE purpose=?",PasswordService.RESET);
        assertThatThrownBy(() -> passwords.completeReset(token,"abcde","abcde")).hasMessageContaining("invalid or expired");
        jdbc.update("UPDATE credential_tokens SET target_email='invalid@example.com' WHERE purpose=?",PasswordService.RESET);
        jdbc.update("UPDATE users SET security_version=security_version+1");
        assertThatThrownBy(() -> passwords.completeReset(token,"abcde","abcde")).hasMessageContaining("invalid or expired");
        jdbc.update("DELETE FROM local_credentials");reset(mail);
        passwords.requestReset("invalid@example.com");delivery.runAll();
        verify(mail,never()).send(anyString(),anyString(),anyString(),any(),any());
        assertThatThrownBy(() -> passwords.completeReset("x".repeat(43),"abcde","abcde")).hasMessageContaining("invalid or expired");
        passwords.register(new Registration("ORGANIZATION","SSO","provider@example.com","Provider","provider-org","Provider"));
        passwords.requestReset("provider@example.com");delivery.runAll();
        verify(mail,never()).send(anyString(),anyString(),anyString(),any(),any());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM local_credentials",Long.class)).isZero();
    }
    @Test void concurrentResetSucceedsOnlyOnceAndClearsLockout() throws Exception {
        activate("race-reset@example.com");reset(mail);passwords.requestReset("race-reset@example.com");String token=lastToken(PasswordService.RESET);
        jdbc.update("UPDATE local_credentials SET failed_attempts=5,locked_until=?",AccountRepository.timestamp(clock.now.plusSeconds(900)));
        CountDownLatch start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Boolean> attempt=() -> { start.await();try { passwords.completeReset(token,"abcde","abcde");return true; }
                catch(ResponseStatusException expected) { return false; } };
            var first=pool.submit(attempt);var second=pool.submit(attempt);start.countDown();
            assertThat(List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        var account=accounts.byEmail("race-reset@example.com",false).orElseThrow();
        assertThat(account.securityVersion()).isEqualTo(3);assertThat(account.failedAttempts()).isZero();assertThat(account.lockedUntil()).isNull();
    }
    private void individual(String email) { passwords.register(new Registration("INDIVIDUAL",null,email,"Person",null,null)); }
    private void activate(String email) { individual(email);passwords.completeSetup(lastToken(PasswordService.SETUP),PASSWORD); }
    private String lastToken(String purpose) {
        delivery.runAll();
        ArgumentCaptor<String> token=ArgumentCaptor.forClass(String.class);
        verify(mail,atLeastOnce()).send(anyString(),eq(purpose),token.capture(),any(),any());
        return token.getValue();
    }
    private static final class ControlledExecutor implements TaskExecutor {
        final Queue<Runnable> tasks=new ArrayDeque<>();
        boolean reject;
        @Override public void execute(Runnable task) {
            if(reject) throw new RejectedExecutionException("Delivery queue is full.");
            tasks.add(task);
        }
        void runNext() { tasks.remove().run(); }
        void runAll() { while(!tasks.isEmpty()) runNext(); }
    }
    private static class MutableClock extends Clock {
        Instant now=Instant.parse("2026-11-25T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
