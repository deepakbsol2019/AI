package com.book.user;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import com.book.user.config.AuthSettings;
import com.book.user.exception.AuthException;
import com.book.user.security.AuthCookies;
import com.book.user.security.UserPrincipal;
import com.book.user.service.AccessTokenService;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import static org.assertj.core.api.Assertions.*;

class AccessTokenValidationTests {
    private static final Instant NOW = Instant.parse("2026-11-25T00:00:00Z");
    private static final byte[] KEY = "a test signing key of at least 48 bytes for HS384 too!".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final UserPrincipal PRINCIPAL = new UserPrincipal(1, 2, 3, 4, 1, 1, 1, "a".repeat(64));
    private AuthSettings settings(String issuer, String audience) {
        return new AuthSettings(new MockEnvironment().withProperty("app.user.jwt.secret", Base64.getEncoder().encodeToString(KEY))
                .withProperty("app.user.jwt.issuer", issuer).withProperty("app.user.jwt.audience", audience));
    }
    private AccessTokenService service() { return new AccessTokenService(settings("issuer", "audience"), Clock.fixed(NOW, ZoneOffset.UTC)); }
    @Test void validJwtHasExactFiveMinuteDeclaredLifetimeAndNoProfileOrRefreshSecrets() {
        String token = service().issue(PRINCIPAL, NOW);
        assertThat(service().verify(token)).isEqualTo(PRINCIPAL);
        String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(payload).doesNotContain("email", "password", "refresh", "displayName");
        assertThat(payload).contains("\"iat\":" + NOW.getEpochSecond(), "\"exp\":" + NOW.plusSeconds(300).getEpochSecond());
    }
    @Test void wrongIssuerAndAudienceAreRejectedEvenWithValidSignature() {
        var issuer = new AccessTokenService(settings("wrong", "audience"), Clock.fixed(NOW, ZoneOffset.UTC));
        var audience = new AccessTokenService(settings("issuer", "wrong"), Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> service().verify(issuer.issue(PRINCIPAL, NOW))).isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> service().verify(audience.issue(PRINCIPAL, NOW))).isInstanceOf(AuthException.class);
    }
    @Test void otherAlgorithmUnsignedAndTamperedTokensAreRejected() {
        String alternate = signed(MacAlgorithm.HS384, NOW, NOW.plusSeconds(300));
        assertThatThrownBy(() -> service().verify(alternate)).isInstanceOf(AuthException.class);
        String valid = service().issue(PRINCIPAL, NOW);
        String[] parts = valid.split("\\.");
        assertThatThrownBy(() -> service().verify(parts[0] + "." + parts[1] + ".wrong-signature")).isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> service().verify("eyJhbGciOiJub25lIn0.e30.")).isInstanceOf(AuthException.class);
    }
    @Test void futureIssuanceAndExtendedExpiryAreRejectedWithoutClockSkew() {
        assertThatThrownBy(() -> service().verify(signed(MacAlgorithm.HS256, NOW.plusSeconds(1), NOW.plusSeconds(301))))
                .isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> service().verify(signed(MacAlgorithm.HS256, NOW, NOW.plusSeconds(301))))
                .isInstanceOf(AuthException.class);
        String token = service().issue(PRINCIPAL, NOW);
        var expired = new AccessTokenService(settings("issuer", "audience"), Clock.fixed(NOW.plusSeconds(300), ZoneOffset.UTC));
        assertThatThrownBy(() -> expired.verify(token)).isInstanceOf(AuthException.class);
    }
    @Test void missingKeysFailClosedWithoutPreventingConfigurationConstruction() {
        var unconfigured = new AccessTokenService(new AuthSettings(new MockEnvironment()), Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> unconfigured.issue(PRINCIPAL, NOW)).isInstanceOf(AuthException.class).hasMessageContaining("not configured");
        assertThatThrownBy(() -> unconfigured.verify(service().issue(PRINCIPAL, NOW))).isInstanceOf(AuthException.class);
    }
    @Test void refreshCookieRoundsRemainingFractionUpAndRemainsHttpOnlyScopedAndSecure() {
        var response = new MockHttpServletResponse();
        var cookies = new AuthCookies(settings("issuer", "audience"), Clock.fixed(NOW.plusNanos(900_000_000), ZoneOffset.UTC));
        cookies.write(response, "test-opaque-secret", NOW.plusSeconds(600));
        assertThat(response.getHeader("Set-Cookie")).contains("Max-Age=600", "HttpOnly", "Secure", "SameSite=Lax", "Path=/api/user/auth");
    }
    private String signed(MacAlgorithm algorithm, Instant issued, Instant expiry) {
        var claims = JwtClaimsSet.builder().issuer("issuer").audience(List.of("audience")).subject("1")
                .issuedAt(issued).notBefore(issued).expiresAt(expiry).id("test-jti")
                .claim("tid", 2L).claim("mid", 3L).claim("sid", 4L).claim("sv", 1L).claim("tv", 1L).claim("mv", 1L).claim("av", "a".repeat(64)).build();
        var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(KEY, "Hmac" + algorithm.getName().substring(2))));
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(algorithm).build(), claims)).getTokenValue();
    }
}
