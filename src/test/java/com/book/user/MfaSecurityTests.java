package com.book.user;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import com.book.user.exception.AuthException;
import com.book.user.config.CredentialDeliveryConfiguration;
import com.book.user.repository.AccountRepository;
import com.book.user.service.*;

class MfaSecurityTests {
    private static final String PASSWORD="correct first factor";
    private static final String RECOVERY="one use recovery secret";
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private MutableClock clock;
    private PasswordService passwords;
    private MfaService mfa;
    private long user;
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false) @EnableTransactionManagement
    static class Transactions { }
    @BeforeEach void setup() {
        jdbc=AuthTestDatabase.create();clock=new MutableClock();
        var encoder=new DelegatingPasswordEncoder("pbkdf2",Map.of("pbkdf2",Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
        var environment=new MockEnvironment().withProperty("TEST_TOTP_SECRET","GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        context=new AnnotationConfigApplicationContext();context.setEnvironment(environment);
        context.register(Transactions.class,CredentialDeliveryConfiguration.class,AccountRepository.class,TenantProvisioningService.class,CredentialMailService.class,PasswordService.class,MfaService.class);
        context.registerBean(JdbcTemplate.class,() -> jdbc);context.registerBean(Clock.class,() -> clock);
        context.registerBean(PasswordEncoder.class,() -> encoder);
        context.registerBean(PlatformTransactionManager.class,() -> new DataSourceTransactionManager(jdbc.getDataSource()));
        context.registerBean(com.book.storage.TenantStoragePaths.class, AuthTestDatabase::storage);
        context.refresh();passwords=context.getBean(PasswordService.class);mfa=context.getBean(MfaService.class);
        var created=context.getBean(TenantProvisioningService.class).create("INDIVIDUAL","PASSWORD","mfa@example.com","MFA User",null,null,true);
        user=created.userId();var now=AccountRepository.timestamp(clock.instant());
        jdbc.update("INSERT INTO local_credentials(user_id,password_hash,password_changed_at,failed_attempts,created_at,updated_at) VALUES (?,?,?,0,?,?)",user,encoder.encode(PASSWORD),now,now,now);
        jdbc.update("INSERT INTO local_mfa_factors(user_id,factor_type,secret_ref,status,confirmed_at,created_at) VALUES (?,'TOTP','env:TEST_TOTP_SECRET','ACTIVE',?,?)",user,now,now);
        jdbc.update("INSERT INTO recovery_codes(user_id,code_hash,created_at) VALUES (?,?,?)",user,encoder.encode(RECOVERY),now);
    }
    @AfterEach void close() { if (context != null) context.close(); }
    @Test void correctPasswordCannotResetPersistentMfaFailuresOrBypassLockout() {
        for(int attempt=1;attempt<=5;attempt++) {
            var identity=passwords.authenticate("mfa@example.com",PASSWORD);
            assertThat(failures()).isEqualTo(attempt-1);
            assertThatThrownBy(() -> mfa.complete(identity,"not a valid code")).isInstanceOf(AuthException.class);
            assertThat(failures()).isEqualTo(attempt);
        }
        assertThatThrownBy(() -> passwords.authenticate("mfa@example.com",PASSWORD)).hasMessageContaining("Email or password is invalid");
        assertThat(jdbc.queryForObject("SELECT locked_until FROM local_credentials",java.sql.Timestamp.class)).isNotNull();
        clock.now=clock.now.plus(Duration.ofMinutes(15));
        var completed=mfa.complete(passwords.authenticate("mfa@example.com",PASSWORD),RECOVERY);
        assertThat(completed.mfaCompletedAt()).isEqualTo(clock.instant());assertThat(failures()).isZero();
        assertThat(jdbc.queryForObject("SELECT locked_until FROM local_credentials",java.sql.Timestamp.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT used_at FROM recovery_codes",java.sql.Timestamp.class)).isNotNull();
        assertThatThrownBy(() -> mfa.complete(passwords.authenticate("mfa@example.com",PASSWORD),RECOVERY)).isInstanceOf(AuthException.class);
    }
    @Test void missingFactorChallengesWithoutClearingOrIncrementingAttempts() {
        jdbc.update("UPDATE local_credentials SET failed_attempts=2");
        var identity=passwords.authenticate("mfa@example.com",PASSWORD);
        assertThatThrownBy(() -> mfa.complete(identity,null)).isInstanceOf(AuthException.class).hasMessageContaining("authenticator");
        assertThat(failures()).isEqualTo(2);
    }
    @Test void knownRfcTotpAcceptsOnceAndClearsFailuresOnlyOnCompletedAuthentication() {
        jdbc.update("UPDATE local_credentials SET failed_attempts=3");
        var completed=mfa.complete(passwords.authenticate("mfa@example.com",PASSWORD),"287082");
        assertThat(completed.mfaCompletedAt()).isEqualTo(clock.instant());assertThat(failures()).isZero();
        assertThat(jdbc.queryForObject("SELECT last_accepted_step FROM local_mfa_factors",Long.class)).isEqualTo(1);
        assertThatThrownBy(() -> mfa.complete(passwords.authenticate("mfa@example.com",PASSWORD),"287082")).isInstanceOf(AuthException.class);
        assertThat(failures()).isEqualTo(1);
    }
    @Test void passwordResetBetweenFactorsCannotConsumeAnAuthenticatorCode() {
        var stale=passwords.authenticate("mfa@example.com",PASSWORD);
        jdbc.update("UPDATE users SET security_version=security_version+1 WHERE user_id=?",user);
        assertThatThrownBy(() -> mfa.complete(stale,"287082")).isInstanceOf(AuthException.class);
        assertThat(jdbc.queryForObject("SELECT last_accepted_step FROM local_mfa_factors",Long.class)).isNull();
        assertThat(failures()).isZero();
    }
    @Test void accountWithoutConfirmedMfaKeepsNormalPasswordSuccessBehavior() {
        jdbc.update("UPDATE local_mfa_factors SET confirmed_at=NULL");
        jdbc.update("UPDATE local_credentials SET failed_attempts=3");
        var identity=passwords.authenticate("mfa@example.com",PASSWORD);
        assertThat(failures()).isZero();assertThat(mfa.complete(identity,null)).isEqualTo(identity);
    }
    private int failures() { return jdbc.queryForObject("SELECT failed_attempts FROM local_credentials WHERE user_id=?",Integer.class,user); }
    private static final class MutableClock extends Clock {
        Instant now=Instant.ofEpochSecond(59);
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
