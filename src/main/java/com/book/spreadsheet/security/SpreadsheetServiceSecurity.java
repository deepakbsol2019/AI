package com.book.spreadsheet.security;

import java.io.IOException;
import java.time.Clock;
import java.util.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;
import com.book.user.security.AuthErrors;
import com.book.user.security.TenantContext;

@Configuration
public class SpreadsheetServiceSecurity {
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(SpreadsheetServiceTokens.class)
    SpreadsheetServiceTokens spreadsheetServiceTokens(Environment environment,Clock clock) {
        String issuer=environment.getProperty("app.spreadsheet-service.issuer","");
        String jwks=environment.getProperty("app.spreadsheet-service.jwk-set-uri","");
        String audience=environment.getProperty("app.spreadsheet-service.audience","");
        Map<String,Long> clients=new HashMap<>();
        for(String entry:environment.getProperty("app.spreadsheet-service.client-tenants","").split(",")){
            if(entry.isBlank())continue;
            String[] pair=entry.strip().split("=",-1);
            if(pair.length!=2||pair[0].isBlank())throw new IllegalArgumentException("Configure spreadsheet client-tenants as client-id=positive-tenant-id pairs.");
            long tenant=Long.parseLong(pair[1]);
            if(tenant<=0||clients.putIfAbsent(pair[0],tenant)!=null)throw new IllegalArgumentException("Invalid spreadsheet client tenant grant.");
        }
        NimbusJwtDecoder decoder=null;
        if(!issuer.isBlank()&&!jwks.isBlank()&&!audience.isBlank()) {
            requireSecureUri(issuer);requireSecureUri(jwks);
            decoder=NimbusJwtDecoder.withJwkSetUri(jwks).build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),
                    jwt->jwt.getAudience().contains(audience)?OAuth2TokenValidatorResult.success():OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"))));
        }
        return new SpreadsheetServiceTokens(decoder,issuer,audience,clients,clock);
    }
    private static void requireSecureUri(String value){
        var uri=java.net.URI.create(value);
        if(uri.getHost()==null||!("https".equals(uri.getScheme())||"http".equals(uri.getScheme())&&Set.of("localhost","127.0.0.1","::1").contains(uri.getHost())))
            throw new IllegalArgumentException("Spreadsheet service issuer/JWKS must use HTTPS (HTTP loopback is allowed for local tests).");
    }
    @Bean @Order(0)
    SecurityFilterChain spreadsheetServiceChain(HttpSecurity http,SpreadsheetServiceTokens tokens,JdbcTemplate jdbc) throws Exception {
        return http.securityMatcher("/api/internal/spreadsheets/**")
                .csrf(csrf->csrf.disable()).sessionManagement(session->session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache->cache.disable()).formLogin(form->form.disable()).httpBasic(basic->basic.disable()).logout(logout->logout.disable())
                .authorizeHttpRequests(access->access.anyRequest().permitAll())
                .addFilterBefore(new OncePerRequestFilter(){
                    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException {
                        response.setHeader("Cache-Control","no-store");
                        try {
                            long tenant=tokens.tenant(request.getHeader("Authorization"));
                            Long active=jdbc.queryForObject("SELECT COUNT(*) FROM tenants WHERE tenant_id=? AND status='ACTIVE' AND NOT EXISTS(SELECT 1 FROM user_deletion_jobs j WHERE j.tenant_id=tenants.tenant_id AND j.status<>'COMPLETE')",Long.class,tenant);
                            if(active==null||active!=1){AuthErrors.write(response,403,"FORBIDDEN","Spreadsheet service tenant is unavailable.");return;}
                            try(var ignored=TenantContext.use(tenant,null,false)){chain.doFilter(request,response);}
                        } catch(ResponseStatusException failure){AuthErrors.write(response,failure.getStatusCode().value(),"SERVICE_ACCESS_DENIED",failure.getReason());}
                    }
                },CsrfFilter.class).build();
    }
}
