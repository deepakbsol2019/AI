package com.book.user;

import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import com.book.user.config.AuthSettings;
import com.book.user.config.CredentialDeliveryConfiguration;
import com.book.user.config.UserSecurityConfiguration;
import com.book.user.controller.AccountController;
import com.book.user.controller.AuthController;
import com.book.user.exception.UserExceptionHandler;
import com.book.user.provider.OidcIdentityService;
import com.book.user.provider.OidcIntegration;
import com.book.user.provider.TrustedOidcProviders;
import com.book.user.repository.AccountRepository;
import com.book.user.security.AuthCookies;
import com.book.user.security.AuthRateLimiter;
import com.book.user.service.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Actual MVC + Spring Security chain + real transactions/schema, with only outbound mail mocked. */
class UserSecurityIntegrationTests {
    private static final String PASSWORD = "a long integrated password";
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private JdbcTemplate jdbc;
    private CredentialMailService mail;
    private SessionSecurityTests.MutableClock clock;
    private Cookie csrfCookie;
    private String csrf;
    private final JsonMapper json = JsonMapper.builder().build();
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false) @EnableWebMvc @EnableTransactionManagement
    @Import({AuthSettings.class, UserSecurityConfiguration.class, CredentialDeliveryConfiguration.class, AuthCookies.class, AuthRateLimiter.class,
        AccessTokenService.class, SessionService.class, SecurityAuditService.class, AuthorizationService.class,
        TenantProvisioningService.class, PasswordService.class, MfaService.class, AccountRepository.class,
        AuthController.class, AccountController.class, UserExceptionHandler.class, TrustedOidcProviders.class,
        OidcIdentityService.class, OidcIntegration.class, OidcIntegration.InitiationController.class})
    static class Config {
        @Bean com.book.storage.TenantStoragePaths storage() { return AuthTestDatabase.storage(); }
        @Bean JdbcTemplate jdbc() { return AuthTestDatabase.create(); }
        @Bean PlatformTransactionManager transactions(JdbcTemplate jdbc) { return new DataSourceTransactionManager(jdbc.getDataSource()); }
        @Bean @org.springframework.context.annotation.Primary
        Clock clock() { return new SessionSecurityTests.MutableClock(); }
        @Bean CredentialMailService mail() { return mock(CredentialMailService.class); }
    }
    @BeforeEach void setup() throws Exception {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.setEnvironment(new MockEnvironment().withProperty("app.user.jwt.secret", Base64.getEncoder()
                .encodeToString("integration-test-key-over-32-bytes!".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .withProperty("app.user.cookie.secure", "true"));
        context.register(Config.class);
        context.refresh();
        jdbc = context.getBean(JdbcTemplate.class);
        mail = context.getBean(CredentialMailService.class);
        clock = (SessionSecurityTests.MutableClock) context.getBean(Clock.class);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        MvcResult initial = mvc.perform(get("/api/user/auth/csrf")).andExpect(status().isOk()).andReturn();
        csrfCookie = initial.getResponse().getCookie("XSRF-TOKEN");
        csrf = body(initial).path("token").asText();
        assertThat(csrfCookie).isNotNull();
    }
    @AfterEach void close() { if (context != null) context.close(); }

    @Test void registrationSetupLoginRefreshAndLogoutUseOneProtectedSessionAndCookieOnlyRefresh() throws Exception {
        createActive();
        var loggedIn = login();
        JsonNode data = body(loggedIn);
        String access = data.path("accessToken").asText();
        assertThat(data.has("refreshToken")).isFalse();
        assertThat(data.path("user").path("email").asText()).isEqualTo("http@example.com");
        String cookie = loggedIn.getResponse().getHeaders("Set-Cookie").stream()
                .filter(value -> value.startsWith("BOOK_REFRESH=")).findFirst().orElseThrow();
        assertThat(cookie).contains("HttpOnly", "Secure", "SameSite=Lax", "Path=/api/user/auth", "Max-Age=600");
        Cookie refresh = loggedIn.getResponse().getCookie("BOOK_REFRESH");
        mvc.perform(get("/api/user/auth/me").header("Authorization", "Bearer " + access)).andExpect(status().isOk());
        var rotated = mvc.perform(postJson("/api/user/auth/refresh", "{}").cookie(refresh))
                .andExpect(status().isOk()).andReturn();
        assertThat(body(rotated).path("accessToken").asText()).isNotEqualTo(access);
        Cookie next = rotated.getResponse().getCookie("BOOK_REFRESH");
        mvc.perform(postJson("/api/user/auth/logout", "{}").cookie(next)).andExpect(status().isNoContent());
        mvc.perform(get("/api/user/auth/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(header().doesNotExist("Location"));
    }
    @Test void csrfIsRequiredEvenOnLoginAndUntrustedOriginsCannotUseCookies() throws Exception {
        mvc.perform(post("/api/user/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"x@example.com\",\"password\":\"ignored\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_OR_ACCESS_DENIED"));
        mvc.perform(postJson("/api/user/auth/refresh", "{}").header("Origin", "https://untrusted.example"))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_sessions", Long.class)).isZero();
    }
    @Test void disabledProvidersHaveUnavailableResponsesWithoutCredentialsOrRedirects() throws Exception {
        mvc.perform(get("/api/user/auth/features")).andExpect(status().isOk())
                .andExpect(jsonPath("$.googleEnabled").value(false)).andExpect(jsonPath("$.ssoEnabled").value(false));
        mvc.perform(get("/api/user/auth/oidc/google")).andExpect(status().isServiceUnavailable())
                .andExpect(header().doesNotExist("Location"));
        mvc.perform(postJson("/api/user/auth/oidc/sso", "{\"tenantCode\":\"unknown\"}"))
                .andExpect(status().isServiceUnavailable());
    }
    @Test void unmatchedOidcCallbackAuditsFailureWithoutSecretsOrAnApplicationSession() throws Exception {
        mvc.perform(get("/login/oauth2/code/google").param("error","access_denied").param("state","unmatched-sensitive-state"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost:4200/user/provider-error"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE event_code='OIDC_LOGIN_FAILURE' AND outcome='FAILURE'",Long.class)).isEqualTo(1);
        var audit=jdbc.queryForMap("SELECT tenant_id,actor_user_id,target_type,target_id,details FROM audit_events WHERE event_code='OIDC_LOGIN_FAILURE'");
        assertThat(audit.values()).allSatisfy(value -> assertThat(value).isNull());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_sessions",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens",Long.class)).isZero();
    }
    @Test void ordinaryApisRequireBearerAndUnknownPoliciesFailClosedWith403NotLoginHtml() throws Exception {
        mvc.perform(get("/api/search/books")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED")).andExpect(header().doesNotExist("Location"));
        // Missing authentication is 401 on writes too, before CSRF can obscure it with a 403.
        mvc.perform(post("/api/upload-file")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        createActive();
        String access = body(login()).path("accessToken").asText();
        mvc.perform(get("/api/unregistered").header("Authorization", "Bearer " + access))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/user/auth/me").cookie(new Cookie("JSESSIONID", "not-an-app-session")))
                .andExpect(status().isUnauthorized());
    }
    @Test void accessExpiryCanRefreshButExactRefreshExpiryClearsCookieAndReturns401() throws Exception {
        createActive();
        var loggedIn = login();
        String access = body(loggedIn).path("accessToken").asText();
        clock.now = clock.now.plusSeconds(300);
        mvc.perform(get("/api/user/auth/me").header("Authorization", "Bearer " + access)).andExpect(status().isUnauthorized());
        var rotated = mvc.perform(postJson("/api/user/auth/refresh", "{}").cookie(loggedIn.getResponse().getCookie("BOOK_REFRESH")))
                .andExpect(status().isOk()).andReturn();
        mvc.perform(get("/api/user/auth/me").header("Authorization", "Bearer " + body(rotated).path("accessToken").asText()))
                .andExpect(status().isOk());
        clock.now = clock.now.plusSeconds(600);
        var expired = mvc.perform(postJson("/api/user/auth/refresh", "{}").cookie(rotated.getResponse().getCookie("BOOK_REFRESH")))
                .andExpect(status().isUnauthorized()).andReturn();
        assertThat(expired.getResponse().getHeaders("Set-Cookie")).anyMatch(value -> value.startsWith("BOOK_REFRESH=;") && value.contains("Max-Age=0"));
    }
    @Test void catalogPolicyAlsoProtectsOwnAccountAndUnknownLoginFailuresAreAudited() throws Exception {
        createActive();
        jdbc.update("DELETE FROM role_permissions WHERE permission_id=(SELECT permission_id FROM permissions WHERE permission_code='ACCOUNT.VIEW')");
        String access = body(login()).path("accessToken").asText();
        mvc.perform(get("/api/user/auth/me").header("Authorization", "Bearer " + access)).andExpect(status().isForbidden());
        mvc.perform(postJson("/api/user/auth/login", "{\"email\":\"missing@example.com\",\"password\":\"a long invalid password\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE event_code='LOGIN_FAILURE'", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT details FROM audit_events WHERE event_code='LOGIN_FAILURE'")).allSatisfy(row -> assertThat(row.get("details")).isNull());
    }
    @Test void recoveryIsPublicCsrfProtectedGenericAndRevokesExistingAccessAndRefresh() throws Exception {
        createActive();var loggedIn=login();String access=body(loggedIn).path("accessToken").asText();
        mvc.perform(post("/api/user/auth/password/forgot").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"http@example.com\"}"))
                .andExpect(status().isForbidden());
        var known=mvc.perform(postJson("/api/user/auth/password/forgot","{\"email\":\"HTTP@example.com\"}"))
                .andExpect(status().isAccepted()).andReturn();
        var unknown=mvc.perform(postJson("/api/user/auth/password/forgot","{\"email\":\"unknown@example.com\"}"))
                .andExpect(status().isAccepted()).andReturn();
        assertThat(known.getResponse().getContentAsString()).isEqualTo(unknown.getResponse().getContentAsString())
                .contains("If an eligible account exists, a password reset email will be sent.");
        var secret=ArgumentCaptor.forClass(String.class);
        verify(mail,timeout(3000)).send(eq("http@example.com"),eq("PASSWORD_RESET"),secret.capture(),any(),any());
        mvc.perform(postJson("/api/user/auth/password/reset",json.writeValueAsString(Map.of("token",secret.getValue(),"password","abcde","confirmation","other"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("New Password and Confirm Password must match exactly."));
        mvc.perform(postJson("/api/user/auth/password/reset",json.writeValueAsString(Map.of("token",secret.getValue(),"password","abcde","confirmation","abcde"))))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/user/auth/me").header("Authorization","Bearer "+access)).andExpect(status().isUnauthorized());
        mvc.perform(postJson("/api/user/auth/refresh","{}").cookie(loggedIn.getResponse().getCookie("BOOK_REFRESH"))).andExpect(status().isUnauthorized());
        mvc.perform(postJson("/api/user/auth/login","{\"email\":\"http@example.com\",\"password\":\"abcde\"}"))
                .andExpect(status().isOk());
    }
    private void createActive() throws Exception {
        mvc.perform(postJson("/api/user/auth/register", json.writeValueAsString(Map.of("tenantType", "INDIVIDUAL", "email", "http@example.com", "displayName", "HTTP User"))))
                .andExpect(status().isAccepted());
        var secret = ArgumentCaptor.forClass(String.class);
        verify(mail).send(eq("http@example.com"), eq("SET_PASSWORD"), secret.capture(), any(), any());
        mvc.perform(postJson("/api/user/auth/password/setup", json.writeValueAsString(Map.of("token", secret.getValue(), "password", PASSWORD))))
                .andExpect(status().isNoContent());
    }
    private MvcResult login() throws Exception {
        return mvc.perform(postJson("/api/user/auth/login", json.writeValueAsString(Map.of("email", "http@example.com", "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn();
    }
    private MockHttpServletRequestBuilder postJson(String path, String body) {
        return post(path).cookie(csrfCookie).header("X-XSRF-TOKEN", csrf).contentType(MediaType.APPLICATION_JSON).content(body);
    }
    private JsonNode body(MvcResult result) throws java.io.UnsupportedEncodingException { return json.readTree(result.getResponse().getContentAsString()); }
}
