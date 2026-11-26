package com.book.user;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;

import com.book.user.dto.AuthenticatedIdentity;
import com.book.user.security.TenantContext;
import com.book.user.service.SessionService;
import com.book.user.service.SessionService.IssuedSession;
import com.book.user.service.TenantProvisioningService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real MVC/security/transactional session chain; only outbound mail is mocked by the shared fixture. */
class SuperUserHttpSecurityTests {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private JdbcTemplate jdbc;
    private SessionService sessions;
    private SessionSecurityTests.MutableClock clock;
    private AuthenticatedIdentity ordinary;
    private AuthenticatedIdentity foreign;
    private Cookie csrfCookie;
    private String csrf;
    private final JsonMapper json = JsonMapper.builder().build();

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    @Import({UserSecurityIntegrationTests.Config.class, CatalogProbe.class})
    static class Config {
        @Bean RecordOwners recordOwners() { return new RecordOwners(); }
    }

    static class RecordOwners {
        long foreignTenantId;
    }

    /** Fixture records have fixed server-side owners; client ownership/role fields are never consulted. */
    @RestController
    @org.springframework.boot.test.context.TestComponent
    static class CatalogProbe {
        private final RecordOwners owners;
        CatalogProbe(RecordOwners owners) { this.owners = owners; }

        @GetMapping("/api/search/books")
        Map<String, Object> read(@RequestParam(name = "record", defaultValue = "own") String record) {
            Long owner = switch (record) {
                case "foreign" -> owners.foreignTenantId;
                case "unassigned" -> null;
                case "invalid" -> 0L;
                default -> TenantContext.requireTenantId();
            };
            TenantContext.requireOwned(owner);
            return trustedScope();
        }

        @PostMapping("/api/upload-file")
        Map<String, Object> write() { return trustedScope(); }

        @GetMapping("/api/unregistered-super-probe")
        Map<String, Object> unregistered() { return trustedScope(); }

        private Map<String, Object> trustedScope() {
            return Map.of("tenantId", TenantContext.requireTenantId(), "superUser", TenantContext.isSuperUser());
        }
    }

    @BeforeEach void setup() throws Exception {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.setEnvironment(new MockEnvironment().withProperty("app.user.jwt.secret", Base64.getEncoder()
                .encodeToString("super-user-http-test-key-over-32-bytes".getBytes(StandardCharsets.UTF_8)))
                .withProperty("app.user.cookie.secure", "true"));
        context.register(Config.class);
        context.refresh();
        jdbc = context.getBean(JdbcTemplate.class);
        sessions = context.getBean(SessionService.class);
        clock = (SessionSecurityTests.MutableClock) context.getBean(Clock.class);
        ordinary = account("ordinary@example.com");
        foreign = account("foreign@example.com");
        context.getBean(RecordOwners.class).foreignTenantId = foreign.tenantId();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        MvcResult initial = mvc.perform(get("/api/user/auth/csrf")).andExpect(status().isOk()).andReturn();
        csrfCookie = initial.getResponse().getCookie("XSRF-TOKEN");
        csrf = body(initial).path("token").asText();
        assertThat(csrfCookie).isNotNull();
    }

    @AfterEach void close() {
        assertThat(TenantContext.currentTenantId()).isNull();
        assertThat(TenantContext.isSuperUser()).isFalse();
        if (context != null) context.close();
    }

    @Test void ordinaryAdminRemainsTenantScopedAndClientClaimsCannotElevateIt() throws Exception {
        var issued = sessions.issue(ordinary);
        mvc.perform(read(issued, "own").param("tenantId", Long.toString(foreign.tenantId()))
                        .param("role", "SUPER_USER").param("superUser", "true")
                        .header("X-Tenant-Id", foreign.tenantId()).header("X-Role", "SUPER_USER")
                        .header("X-Super-User", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tenantId").value(ordinary.tenantId()))
                .andExpect(jsonPath("$.superUser").value(false));
        mvc.perform(read(issued, "foreign").param("tenantId", Long.toString(foreign.tenantId()))
                        .header("X-Tenant-Id", foreign.tenantId()).header("X-Role", "SUPER_USER"))
                .andExpect(status().isNotFound());
        mvc.perform(read(issued, "unassigned")).andExpect(status().isNotFound());

        String access = issued.response().accessToken();
        String[] jwt = access.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(jwt[1]), StandardCharsets.UTF_8);
        String changed = payload.replace("\"tid\":" + ordinary.tenantId(), "\"tid\":" + foreign.tenantId());
        assertThat(changed).isNotEqualTo(payload);
        String forged = jwt[0] + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(changed.getBytes(StandardCharsets.UTF_8)) + "." + jwt[2];
        mvc.perform(get("/api/search/books").header("Authorization", "Bearer " + forged)
                        .header("X-Role", "SUPER_USER"))
                .andExpect(status().isUnauthorized());
    }

    @Test void assignedSystemSuperUserReadsAssignedForeignRecordsButNeverUnassignedRecords() throws Exception {
        grantSuperUser(ordinary, true);
        var issued = sessions.issue(ordinary);
        mvc.perform(read(issued, "own")).andExpect(status().isOk()).andExpect(jsonPath("$.superUser").value(true));
        mvc.perform(read(issued, "foreign").param("tenantId", Long.toString(foreign.tenantId())))
                .andExpect(status().isNotFound());
        mvc.perform(read(issued, "unassigned")).andExpect(status().isNotFound());
        mvc.perform(read(issued, "invalid")).andExpect(status().isNotFound());
    }

    @Test void aCustomRoleNamedSuperUserDoesNotGrantReservedSystemPrivilege() throws Exception {
        grantSuperUser(ordinary, false);
        var issued = sessions.issue(ordinary);
        mvc.perform(read(issued, "own")).andExpect(status().isOk()).andExpect(jsonPath("$.superUser").value(false));
        mvc.perform(read(issued, "foreign")).andExpect(status().isNotFound());
    }

    @Test void anotherTenantsReservedRoleCannotElevateTheCurrentMembership() throws Exception {
        grantSuperUser(foreign, true);
        var issued = sessions.issue(ordinary);
        mvc.perform(read(issued, "own").param("membershipId", Long.toString(foreign.membershipId()))
                        .header("X-Tenant-Id", foreign.tenantId()).header("X-Super-User", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.superUser").value(false));
        mvc.perform(read(issued, "foreign")).andExpect(status().isNotFound());
    }

    @Test void removingTheReservedGrantInvalidatesExistingAccessAndRefreshImmediately() throws Exception {
        long role = grantSuperUser(ordinary, true);
        var issued = sessions.issue(ordinary);
        jdbc.update("DELETE FROM user_roles WHERE membership_id=? AND role_id=?", ordinary.membershipId(), role);
        assertSessionRejected(issued);
        var fresh = sessions.issue(ordinary);
        mvc.perform(read(fresh, "own")).andExpect(status().isOk()).andExpect(jsonPath("$.superUser").value(false));
        mvc.perform(read(fresh, "foreign")).andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"is_system=FALSE", "role_code='TENANT_HELPER'", "status='SUSPENDED'"})
    void reservedRoleDowngradesInvalidateTokensEvenWithoutAnAuthzVersionBump(String downgrade) throws Exception {
        long role = grantSuperUser(ordinary, true);
        var issued = sessions.issue(ordinary);
        jdbc.update("UPDATE roles SET " + downgrade + " WHERE role_id=?", role);
        assertSessionRejected(issued);
        var fresh = sessions.issue(ordinary);
        mvc.perform(read(fresh, "own")).andExpect(status().isOk()).andExpect(jsonPath("$.superUser").value(false));
        mvc.perform(read(fresh, "foreign")).andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "email", "tenant", "membership"})
    void superUserCannotBypassActiveVerifiedUserTenantAndMembershipRequirements(String inactive) throws Exception {
        grantSuperUser(ordinary, true);
        var issued = sessions.issue(ordinary);
        switch (inactive) {
            case "user" -> jdbc.update("UPDATE users SET status='SUSPENDED' WHERE user_id=?", ordinary.userId());
            case "email" -> jdbc.update("UPDATE users SET email_verified_at=NULL WHERE user_id=?", ordinary.userId());
            case "tenant" -> jdbc.update("UPDATE tenants SET status='SUSPENDED' WHERE tenant_id=?", ordinary.tenantId());
            case "membership" -> jdbc.update("UPDATE tenant_memberships SET status='SUSPENDED' WHERE membership_id=?", ordinary.membershipId());
            default -> throw new AssertionError("Unexpected fixture");
        }
        assertSessionRejected(issued);
    }

    @Test void superUserStillNeedsBearerKnownPolicyCsrfAndTrustedOrigin() throws Exception {
        grantSuperUser(ordinary, true);
        var issued = sessions.issue(ordinary);
        mvc.perform(get("/api/search/books").header("X-Role", "SUPER_USER"))
                .andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("Location"));
        mvc.perform(get("/api/unregistered-super-probe").header("Authorization", bearer(issued)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(post("/api/upload-file").header("Authorization", bearer(issued)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_OR_ACCESS_DENIED"));
        mvc.perform(postJson("/api/upload-file", "{}").header("Authorization", bearer(issued)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.superUser").value(true));
        mvc.perform(postJson("/api/upload-file", "{}").header("Authorization", bearer(issued))
                        .header("Origin", "https://untrusted.example"))
                .andExpect(status().isForbidden());
    }

    @Test void superUserStillExpiresAccessAtFiveMinutesAndRefreshAtItsExactDeadline() throws Exception {
        grantSuperUser(ordinary, true);
        var issued = sessions.issue(ordinary);
        clock.now = clock.now.plusSeconds(300);
        mvc.perform(read(issued, "foreign")).andExpect(status().isUnauthorized());
        var rotated = mvc.perform(postJson("/api/user/auth/refresh", "{}")
                        .cookie(new Cookie("BOOK_REFRESH", issued.refreshToken())))
                .andExpect(status().isOk()).andReturn();
        String nextAccess = body(rotated).path("accessToken").asText();
        mvc.perform(get("/api/search/books").param("record", "foreign").header("Authorization", "Bearer " + nextAccess))
                .andExpect(status().isNotFound());
        clock.now = clock.now.plusSeconds(600);
        mvc.perform(postJson("/api/user/auth/refresh", "{}").cookie(rotated.getResponse().getCookie("BOOK_REFRESH")))
                .andExpect(status().isUnauthorized());
    }

    @Test void publicRegistrationCannotChooseSuperUserOrAnExistingTenant() throws Exception {
        var result = mvc.perform(postJson("/api/user/auth/register", """
                {"tenantType":"INDIVIDUAL","email":"new-registration@example.com","displayName":"New user",
                 "roleCode":"SUPER_USER","is_system":true,"superUser":true,"tenantId":%d}
                """.formatted(foreign.tenantId()))).andReturn();
        // Ignoring unknown fields and rejecting them are both safe; neither may grant this reserved role.
        assertThat(result.getResponse().getStatus()).isIn(202, 400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM roles WHERE role_code='SUPER_USER'", Long.class)).isZero();
        if (result.getResponse().getStatus() == 202) {
            var account = jdbc.queryForMap("""
                    SELECT m.tenant_id,r.role_code FROM users u JOIN tenant_memberships m ON m.user_id=u.user_id
                    JOIN user_roles ur ON ur.membership_id=m.membership_id AND ur.tenant_id=m.tenant_id
                    JOIN roles r ON r.role_id=ur.role_id AND r.tenant_id=ur.tenant_id
                    WHERE u.primary_email='new-registration@example.com'
                    """);
            assertThat(((Number) account.get("tenant_id")).longValue()).isNotEqualTo(foreign.tenantId());
            assertThat(account.get("role_code")).isEqualTo("OWNER");
        } else {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE primary_email='new-registration@example.com'", Long.class)).isZero();
        }
    }

    private AuthenticatedIdentity account(String email) {
        var created = context.getBean(TenantProvisioningService.class)
                .create("INDIVIDUAL", "PASSWORD", email, email, null, null, true);
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("INSERT INTO local_credentials(user_id,password_hash,password_changed_at,failed_attempts,created_at,updated_at) VALUES(?,?,?,0,?,?)",
                created.userId(), "{pbkdf2}not-used-by-http-session-issuance", now, now, now);
        return new AuthenticatedIdentity(created.userId(), created.tenantId(), created.membershipId(), "PASSWORD", null, null, 1);
    }

    private long grantSuperUser(AuthenticatedIdentity account, boolean system) {
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
                INSERT INTO roles(tenant_id,role_code,display_name,description,is_system,status,authz_version,created_at,updated_at)
                VALUES(?,'SUPER_USER','System super user','Controlled test assignment',?,'ACTIVE',1,?,?)
                """, account.tenantId(), system, now, now);
        long role = jdbc.queryForObject("SELECT role_id FROM roles WHERE tenant_id=? AND role_code='SUPER_USER'", Long.class, account.tenantId());
        jdbc.update("INSERT INTO user_roles(tenant_id,membership_id,role_id,granted_by_membership_id,granted_at) VALUES(?,?,?,?,?)",
                account.tenantId(), account.membershipId(), role, account.membershipId(), now);
        return role;
    }

    private void assertSessionRejected(IssuedSession issued) throws Exception {
        mvc.perform(read(issued, "foreign")).andExpect(status().isUnauthorized());
        mvc.perform(postJson("/api/user/auth/refresh", "{}").cookie(new Cookie("BOOK_REFRESH", issued.refreshToken())))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT revoked_at FROM auth_sessions WHERE session_id=?", Timestamp.class,
                issued.response().sessionId())).isNotNull();
    }

    private MockHttpServletRequestBuilder read(IssuedSession issued, String record) {
        return get("/api/search/books").param("record", record).header("Authorization", bearer(issued));
    }
    private String bearer(IssuedSession issued) { return "Bearer " + issued.response().accessToken(); }
    private MockHttpServletRequestBuilder postJson(String path, String content) {
        return post(path).cookie(csrfCookie).header("X-XSRF-TOKEN", csrf)
                .contentType(MediaType.APPLICATION_JSON).content(content);
    }
    private JsonNode body(MvcResult result) throws java.io.UnsupportedEncodingException {
        return json.readTree(result.getResponse().getContentAsString());
    }
}
