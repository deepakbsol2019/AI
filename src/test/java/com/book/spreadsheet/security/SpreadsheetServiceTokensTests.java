package com.book.spreadsheet.security;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.*;
import static org.assertj.core.api.Assertions.*;

class SpreadsheetServiceTokensTests {
    private final Instant now=Instant.parse("2026-11-26T12:00:00Z");
    private Jwt.Builder token(){return Jwt.withTokenValue("signed").header("alg","RS256").issuer("https://issuer.example").audience(List.of("sheets-api")).subject("report-service").issuedAt(now.minusSeconds(1)).expiresAt(now.plusSeconds(300)).claim("client_id","report-service").claim("scope","spreadsheets.read");}
    private SpreadsheetServiceTokens verifier(Jwt token){return new SpreadsheetServiceTokens(raw->token,"https://issuer.example","sheets-api",Map.of("report-service",42L),Clock.fixed(now,ZoneOffset.UTC));}
    @Test void configuredServiceClientUsesServerTenantGrantWithoutBrowserSession(){assertThat(verifier(token().build()).tenant("Bearer signed")).isEqualTo(42);}
    @Test void rejectsWrongTenantScopeClientAudienceIssuerAndExpiredTokens(){
        for(Jwt value:List.of(token().claim("tid",99).build(),token().claim("scope","other").build(),token().claim("client_id","unknown").build(),token().audience(List.of("other")).build(),token().issuer("https://other.example").build(),token().expiresAt(now.minusMillis(1)).build()))
            assertThatThrownBy(()->verifier(value).tenant("Bearer signed")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void realRsaSignatureIsVerifiedIndependentlyOfBrowserSigningKeys()throws Exception {
        var key=new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048).keyID("service-test").generate();
        var encoder=new org.springframework.security.oauth2.jwt.NimbusJwtEncoder(new com.nimbusds.jose.jwk.source.ImmutableJWKSet<>(new com.nimbusds.jose.jwk.JWKSet(key)));
        var issued=Instant.now();
        var claims=org.springframework.security.oauth2.jwt.JwtClaimsSet.builder().issuer("https://issuer.example").audience(List.of("sheets-api")).issuedAt(issued.minusSeconds(1)).expiresAt(issued.plusSeconds(300)).claim("client_id","report-service").claim("scope","spreadsheets.read").build();
        var header=org.springframework.security.oauth2.jwt.JwsHeader.with(org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256).keyId("service-test").build();
        String signed=encoder.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(header,claims)).getTokenValue();
        var decoder=org.springframework.security.oauth2.jwt.NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
        var verifier=new SpreadsheetServiceTokens(decoder,"https://issuer.example","sheets-api",Map.of("report-service",42L),Clock.systemUTC());
        assertThat(verifier.tenant("Bearer "+signed)).isEqualTo(42);
        String[] pieces=signed.split("\\.");pieces[2]=(pieces[2].startsWith("A")?"B":"A")+pieces[2].substring(1);
        assertThatThrownBy(()->verifier.tenant("Bearer "+String.join(".",pieces))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void missingConfigurationAndInvalidSignaturesFailClosed(){
        assertThatThrownBy(()->new SpreadsheetServiceTokens(null,"","",Map.of(),Clock.systemUTC()).tenant("Bearer anything")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        var invalid=new SpreadsheetServiceTokens(raw->{throw new BadJwtException("bad signature");},"https://issuer.example","sheets-api",Map.of("report-service",42L),Clock.systemUTC());
        assertThatThrownBy(()->invalid.tenant("Bearer forged")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(()->verifier(token().build()).tenant(null)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
}
