package com.book.spreadsheet.security;

import java.time.*;
import java.util.*;
import com.book.spreadsheet.api.*;
import com.book.user.security.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SpreadsheetServiceSecurityTests {
    @Test void internalJsonUsesServiceBearerWithoutBrowserSessionAndClearsTenantAfterDispatch(){
        new WebApplicationContextRunner().withUserConfiguration(Fixture.class,SpreadsheetServiceSecurity.class).run(context->{
            var mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
            var result=mvc.perform(get("/api/internal/spreadsheets/1/sheets/2/data").header("Authorization","Bearer service"))
                    .andExpect(request().asyncStarted()).andReturn();
            mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andExpect(content().json("{\"service\":true}"));
            assertThat(TenantContext.currentTenantId()).isNull();
            mvc.perform(get("/api/internal/spreadsheets/1/sheets/2/data")).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/internal/spreadsheets/1/sheets/2/data").header("Authorization","Bearer forged")).andExpect(status().isUnauthorized());
        });
    }
    @TestConfiguration(proxyBeanMethods=false) @EnableWebMvc @EnableWebSecurity
    @Import(SpreadsheetController.class)
    static class Fixture {
        @Bean Clock clock(){return Clock.systemUTC();}
        @Bean JdbcTemplate jdbc(){var jdbc=mock(JdbcTemplate.class);when(jdbc.queryForObject(anyString(),eq(Long.class),eq(42L))).thenReturn(1L);return jdbc;}
        @Bean SpreadsheetReadService reads(){var service=mock(SpreadsheetReadService.class);when(service.data(42,1L,2L)).thenAnswer(invocation->{assertThat(TenantContext.requireTenantId()).isEqualTo(42);return (org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody)output->output.write("{\"service\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8));});return service;}
        @Bean SpreadsheetServiceTokens serviceTokens(){
            var now=Instant.now();var jwt=Jwt.withTokenValue("service").header("alg","RS256").issuer("https://issuer.example").audience(List.of("sheets-api")).issuedAt(now.minusSeconds(1)).expiresAt(now.plusSeconds(300)).claim("client_id","report-service").claim("scope","spreadsheets.read").build();
            return new SpreadsheetServiceTokens(raw->{if(!raw.equals("service"))throw new org.springframework.security.oauth2.jwt.BadJwtException("invalid");return jwt;},"https://issuer.example","sheets-api",Map.of("report-service",42L),Clock.systemUTC());
        }
    }
}
