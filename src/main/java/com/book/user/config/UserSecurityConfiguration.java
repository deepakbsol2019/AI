package com.book.user.config;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import com.book.user.provider.OidcIntegration;
import com.book.user.security.AuthCookies;
import com.book.user.security.AuthErrors;
import com.book.user.security.AuthRateLimiter;
import com.book.user.security.UserAuthenticationFilter;
import com.book.user.service.AccessTokenService;
import com.book.user.service.AuthorizationService;
import com.book.user.service.SessionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
public class UserSecurityConfiguration {
    @Bean @ConditionalOnMissingBean(Clock.class)
    Clock userClock() { return Clock.systemUTC(); }
    @Bean @ConditionalOnMissingBean(PasswordEncoder.class)
    PasswordEncoder userPasswordEncoder() {
        return new DelegatingPasswordEncoder("pbkdf2", Map.of("pbkdf2", Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
    }
    @Bean
    SecurityFilterChain userSecurity(HttpSecurity http, AuthSettings settings, AccessTokenService tokens,
            SessionService sessions, AuthorizationService authorization, AuthRateLimiter limits,
            AuthCookies cookies, OidcIntegration oidc) throws Exception {
        var csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookieCustomizer(cookie -> cookie.path("/").sameSite("Lax").secure(settings.secureCookie()));
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(settings.trustedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-XSRF-TOKEN"));
        cors.setExposedHeaders(List.of("Content-Disposition"));
        cors.setAllowCredentials(true);
        var origins = new UrlBasedCorsConfigurationSource();
        origins.registerCorsConfiguration("/**", cors);
        http.cors(value -> value.configurationSource(origins))
                .csrf(value -> value.csrfTokenRepository(csrfRepository).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .sessionManagement(value -> value.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .requestCache(value -> value.disable())
                .formLogin(value -> value.disable()).httpBasic(value -> value.disable()).logout(value -> value.disable())
                .authorizeHttpRequests(value -> value.anyRequest().permitAll())
                .exceptionHandling(value -> value
                        .authenticationEntryPoint((request, response, failure) -> AuthErrors.write(response, 401, "AUTHENTICATION_REQUIRED", "Please log in again."))
                        .accessDeniedHandler((request, response, failure) -> AuthErrors.write(response, 403, "CSRF_OR_ACCESS_DENIED", "The request could not be authorized. Reload the page and try again.")))
                .addFilterBefore(new UserAuthenticationFilter(tokens, sessions, authorization, limits, cookies), CsrfFilter.class);
        oidc.configure(http);
        return http.build();
    }
}
