package com.book.user.service;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import com.book.user.dto.AuthenticatedIdentity;
import com.book.user.exception.AuthException;
import com.book.user.util.AuthRows;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Existing confirmed local factors are honored; this change does not mandate or auto-enroll MFA. */
@Service
public class MfaService {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Environment environment;
    private final PasswordEncoder encoder;
    public MfaService(JdbcTemplate jdbc, Clock clock, Environment environment, PasswordEncoder encoder) {
        this.jdbc = jdbc; this.clock = clock; this.environment = environment; this.encoder = encoder;
    }
    @Transactional(noRollbackFor = AuthException.class)
    public AuthenticatedIdentity complete(AuthenticatedIdentity identity, String supplied) {
        Long version=jdbc.queryForObject("SELECT security_version FROM users WHERE user_id=? FOR UPDATE",Long.class,identity.userId());
        if (version == null || version != identity.securityVersion()) throw AuthException.unauthorized();
        var factors = jdbc.queryForList("""
                SELECT * FROM local_mfa_factors WHERE user_id=? AND status='ACTIVE'
                AND confirmed_at IS NOT NULL FOR UPDATE
                """, identity.userId());
        if (factors.isEmpty()) return identity;
        var credentials=jdbc.queryForList("SELECT failed_attempts,locked_until FROM local_credentials WHERE user_id=? FOR UPDATE",identity.userId());
        Instant now=clock.instant();
        if (credentials.size()!=1) throw challenge("MFA_UNAVAILABLE","The configured local factor requires administrator attention.");
        var credential=credentials.getFirst();
        Instant locked=AuthRows.instant(credential,"locked_until");
        if (locked != null && now.isBefore(locked)) throw AuthException.unauthorized();
        if (supplied == null || supplied.isBlank()) throw challenge("MFA_REQUIRED", "Enter your authenticator or recovery code.");
        String code = supplied.strip();
        if (code.length() > 128) throw invalid(identity,credential,now);
        for (Map<String, Object> factor : factors) {
            if (!"TOTP".equals(factor.get("factor_type"))) continue;
            String reference = (String) factor.get("secret_ref");
            String encoded = reference != null && reference.startsWith("env:")
                    ? environment.getProperty(reference.substring(4)) : null;
            if (encoded == null || encoded.isBlank()) continue;
            Long accepted = AuthRows.nullableNumber(factor, "last_accepted_step");
            long step = now.getEpochSecond() / 30;
            for (long candidate = step - 1; candidate <= step + 1; candidate++) {
                if ((accepted == null || candidate > accepted) && code.equals(constantCode(encoded, candidate))) {
                    jdbc.update("UPDATE local_mfa_factors SET last_accepted_step=? WHERE factor_id=?",
                            candidate, AuthRows.number(factor, "factor_id"));
                    clearFailures(identity.userId(),now);
                    return completed(identity, now);
                }
            }
        }
        for (var recovery : jdbc.queryForList("SELECT * FROM recovery_codes WHERE user_id=? AND used_at IS NULL AND revoked_at IS NULL FOR UPDATE", identity.userId())) {
            boolean matches;
            try { matches=encoder.matches(code,(String)recovery.get("code_hash")); }
            catch (IllegalArgumentException malformed) { matches=false; }
            if (matches) {
                jdbc.update("UPDATE recovery_codes SET used_at=? WHERE recovery_code_id=?",
                        Timestamp.from(now), AuthRows.number(recovery, "recovery_code_id"));
                clearFailures(identity.userId(),now);
                return completed(identity, now);
            }
        }
        throw invalid(identity,credential,now);
    }
    private void clearFailures(long userId,Instant now) {
        jdbc.update("UPDATE local_credentials SET failed_attempts=0,locked_until=NULL,updated_at=? WHERE user_id=?",Timestamp.from(now),userId);
    }
    private AuthException invalid(AuthenticatedIdentity identity,Map<String,Object> credential,Instant now) {
        Instant previousLock=AuthRows.instant(credential,"locked_until");
        long attempts=previousLock != null && !now.isBefore(previousLock) ? 1 : AuthRows.number(credential,"failed_attempts")+1;
        int maximum=environment.getProperty("app.user.password.max-failures",Integer.class,5);
        java.time.Duration duration=environment.getProperty("app.user.password.lockout",java.time.Duration.class,java.time.Duration.ofMinutes(15));
        jdbc.update("UPDATE local_credentials SET failed_attempts=?,locked_until=?,updated_at=? WHERE user_id=?",
                attempts,attempts>=maximum ? Timestamp.from(now.plus(duration)) : null,Timestamp.from(now),identity.userId());
        jdbc.update("INSERT INTO audit_events(tenant_id,actor_user_id,event_code,outcome,occurred_at) VALUES (?,?,'MFA_LOGIN','FAILURE',?)",
                identity.tenantId(),identity.userId(),Timestamp.from(now));
        return challenge("MFA_INVALID","The authentication code is invalid or has already been used.");
    }
    private static AuthenticatedIdentity completed(AuthenticatedIdentity identity, Instant now) {
        return new AuthenticatedIdentity(identity.userId(), identity.tenantId(), identity.membershipId(),
                identity.authMethod(), identity.identityProviderId(), now, identity.securityVersion());
    }
    private static AuthException challenge(String code, String detail) {
        return new AuthException(HttpStatus.UNAUTHORIZED, code, detail);
    }
    private static String constantCode(String base32, long step) {
        try {
            String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
            var output = new java.io.ByteArrayOutputStream();
            int buffer = 0, bits = 0;
            for (char character : base32.toUpperCase(Locale.ROOT).replace("=", "").toCharArray()) {
                int value = alphabet.indexOf(character);
                if (value < 0) throw new IllegalArgumentException();
                buffer = (buffer << 5) | value;
                bits += 5;
                if (bits >= 8) { bits -= 8; output.write((buffer >> bits) & 255); }
            }
            if (output.size() < 20) throw new IllegalArgumentException();
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(output.toByteArray(), "HmacSHA1"));
            byte[] digest = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = digest[digest.length - 1] & 15;
            int number = ByteBuffer.wrap(digest, offset, 4).getInt() & 0x7fffffff;
            return String.format(Locale.ROOT, "%06d", number % 1_000_000);
        } catch (Exception failure) {
            // Invalid/unavailable secret references cannot silently bypass a configured factor.
            return null;
        }
    }
}
