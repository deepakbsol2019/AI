package com.book.user.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import com.book.user.config.AuthSettings;
import com.book.user.exception.AuthException;
import com.book.user.security.UserPrincipal;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

@Service
public class AccessTokenService {
    private final AuthSettings settings;
    private final Clock clock;
    public AccessTokenService(AuthSettings settings, Clock clock) { this.settings = settings; this.clock = clock; }

    public String issue(UserPrincipal principal, Instant issuedAt) {
        // JWT NumericDate uses integral seconds; refresh/session timestamps retain database microseconds.
        issuedAt = issuedAt.truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(signingKey()));
        var claims = JwtClaimsSet.builder().issuer(settings.issuer()).audience(List.of(settings.audience()))
                .subject(Long.toString(principal.userId())).issuedAt(issuedAt).notBefore(issuedAt)
                .expiresAt(issuedAt.plusSeconds(300)).id(UUID.randomUUID().toString())
                .claim("tid", principal.tenantId()).claim("mid", principal.membershipId())
                .claim("sid", principal.sessionId()).claim("sv", principal.securityVersion())
                .claim("tv", principal.tenantAuthzVersion()).claim("mv", principal.membershipAuthzVersion())
                .claim("av", principal.authorizationFingerprint()).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    public UserPrincipal verify(String token) {
        try {
            if (token == null || token.length() > 8192) throw AuthException.unauthorized();
            var decoder = NimbusJwtDecoder.withSecretKey(signingKey()).macAlgorithm(MacAlgorithm.HS256).build();
            decoder.setJwtValidator(jwt -> valid(jwt) ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token")));
            Jwt jwt = decoder.decode(token);
            String fingerprint = jwt.getClaimAsString("av");
            if (fingerprint == null || !fingerprint.matches("[a-f0-9]{64}")) throw AuthException.unauthorized();
            return new UserPrincipal(Long.parseLong(jwt.getSubject()), positive(jwt, "tid"), positive(jwt, "mid"),
                    positive(jwt, "sid"), version(jwt, "sv"), version(jwt, "tv"), version(jwt, "mv"), fingerprint);
        } catch (RuntimeException failure) {
            throw AuthException.unauthorized();
        }
    }

    public void requireConfigured() { signingKey(); }

    private SecretKeySpec signingKey() {
        try {
            byte[] key = Base64.getDecoder().decode(settings.signingSecret());
            if (key.length < 32) throw new IllegalArgumentException();
            return new SecretKeySpec(key, "HmacSHA256");
        } catch (IllegalArgumentException failure) {
            throw new AuthException(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_NOT_CONFIGURED",
                    "Authentication signing keys are not configured. Contact the administrator.");
        }
    }
    private boolean valid(Jwt jwt) {
        Instant now = clock.instant();
        Instant issued = jwt.getIssuedAt();
        Instant expires = jwt.getExpiresAt();
        return settings.issuer().equals(jwt.getClaimAsString("iss"))
                && jwt.getAudience().equals(List.of(settings.audience())) && issued != null && expires != null
                && !now.isBefore(issued) && now.isBefore(expires) && expires.equals(issued.plusSeconds(300))
                && jwt.getNotBefore() != null && jwt.getNotBefore().equals(issued)
                && jwt.getId() != null && jwt.getSubject() != null && Long.parseLong(jwt.getSubject()) > 0;
    }
    private static long positive(Jwt jwt, String key) {
        long number = version(jwt, key);
        if (number == 0) throw AuthException.unauthorized();
        return number;
    }
    private static long version(Jwt jwt, String key) {
        Object value = jwt.getClaims().get(key);
        if (!(value instanceof Number number) || number.longValue() < 0
                || number.doubleValue() != (double) number.longValue()) throw AuthException.unauthorized();
        return number.longValue();
    }
}
