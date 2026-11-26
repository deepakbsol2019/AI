package com.book.user.provider;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import com.book.user.security.AuthCookies;
import com.book.user.service.SessionService;
import com.book.user.service.SecurityAuditService;

/** Standard Spring OIDC handles code exchange, state, nonce, signatures, issuer, audience and expiry. */
@Component
public class OidcIntegration {
    private static final String ENROLLMENT=OidcIntegration.class.getName()+".enrollment";
    private final TrustedOidcProviders providers;
    private final OidcIdentityService identities;
    private final SessionService sessions;
    private final AuthCookies cookies;
    private final SecurityAuditService audit;
    public OidcIntegration(TrustedOidcProviders providers,OidcIdentityService identities,SessionService sessions,AuthCookies cookies,SecurityAuditService audit) {
        this.providers=providers;this.identities=identities;this.sessions=sessions;this.cookies=cookies;
        this.audit=audit;
    }
    public void configure(HttpSecurity http) throws Exception {
        var resolver=new DefaultOAuth2AuthorizationRequestResolver(providers,"/oauth2/authorization");
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        http.oauth2Login(oauth -> oauth.clientRegistrationRepository(providers)
                .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(resolver))
                .successHandler((request,response,authentication) -> {
                    try {
                        var token=(OAuth2AuthenticationToken) authentication;
                        var session=request.getSession(false);
                        var enrollment=session == null ? null : (OidcIdentityService.Enrollment) session.getAttribute(ENROLLMENT);
                        var identity=identities.resolve(token.getAuthorizedClientRegistrationId(),(OidcUser)token.getPrincipal(),enrollment);
                        var issued=sessions.issue(identity);
                        cookies.write(response,issued.refreshToken(),issued.response().refreshExpiresAt());
                        clearTemporarySession(request);
                        response.sendRedirect(providers.frontendBase()+"/user/callback");
                    } catch (RuntimeException failure) {
                        audit.record(null,null,"OIDC_LOGIN_FAILURE","FAILURE",null);
                        clearTemporarySession(request);
                        response.sendRedirect(providers.frontendBase()+"/user/provider-error");
                    }
                })
                .failureHandler((request,response,failure) -> {
                    audit.record(null,null,"OIDC_LOGIN_FAILURE","FAILURE",null);
                    clearTemporarySession(request);
                    response.sendRedirect(providers.frontendBase()+"/user/provider-error");
                }));
    }
    private static void clearTemporarySession(HttpServletRequest request) {
        SecurityContextHolder.clearContext();
        var session=request.getSession(false);
        if (session != null) session.invalidate();
    }

    @RestController
    @RequestMapping("/api/user/auth/oidc")
    public static class InitiationController {
        private final TrustedOidcProviders providers;
        private final OidcIdentityService identities;
        public InitiationController(TrustedOidcProviders providers,OidcIdentityService identities) {
            this.providers=providers;this.identities=identities;
        }
        @GetMapping("/google")
        public ResponseEntity<Void> google() {
            providers.requireEnabled("GOOGLE");
            return ResponseEntity.status(302).header("Location",providers.localAuthorizationUrl("google")).build();
        }
        @PostMapping("/sso")
        public Map<String,String> sso(@RequestBody SsoRequest input,HttpServletRequest request) {
            providers.requireEnabled("ORG_OIDC");
            if (input.tenantCode() == null || input.tenantCode().isBlank() || input.tenantCode().length()>80) throw TrustedOidcProviders.unavailable();
            String code=providers.companyCode(input.tenantCode().strip());
            String local=providers.localAuthorizationUrl(code);
            var session=request.getSession(true);
            session.removeAttribute(ENROLLMENT);
            session.setMaxInactiveInterval(600);
            if (input.enrollmentToken() != null && !input.enrollmentToken().isBlank())
                session.setAttribute(ENROLLMENT,identities.enrollment(code,input.enrollmentToken()));
            return Map.of("authorizationUrl",local);
        }
        public record SsoRequest(String tenantCode,String enrollmentToken) {
            @Override public String toString() { return "SsoRequest[tenantCode="+tenantCode+",enrollmentToken=REDACTED]"; }
        }
    }
}
