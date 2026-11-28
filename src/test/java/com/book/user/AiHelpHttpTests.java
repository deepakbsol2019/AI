package com.book.user;

import com.book.help.AiHelpController;
import com.book.help.ChatService;
import com.book.help.ChatPdfExporter;
import com.book.tools.ServiceToolAccess;
import com.book.user.service.SessionService;
import com.book.user.service.TenantProvisioningService;
import jakarta.servlet.http.Cookie;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import tools.jackson.databind.json.JsonMapper;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AiHelpHttpTests {
    @Configuration
    @Import({UserSecurityIntegrationTests.Config.class, AiHelpController.class, ChatService.class, ChatPdfExporter.class, ServiceToolAccess.class})
    static class Config { }
    AnnotationConfigWebApplicationContext context;
    MockMvc mvc;
    String access, csrf;
    Cookie csrfCookie;
    long pageId;
    com.book.user.dto.AuthenticatedIdentity identity;
    SessionService.IssuedSession issued;

    @BeforeEach void setup() throws Exception {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.setEnvironment(new MockEnvironment().withProperty("app.user.jwt.secret", Base64.getEncoder().encodeToString(new byte[32])));
        context.register(Config.class);
        context.refresh();
        var jdbc = context.getBean(JdbcTemplate.class);
        String migration = new org.springframework.core.io.ClassPathResource("db/manual/20261128_ai_help.sql")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8)
                .replaceAll("(?m)^SET time_zone[^;]*;", "").replace("UTC_TIMESTAMP(6)", "CURRENT_TIMESTAMP");
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                new org.springframework.core.io.ByteArrayResource(migration.getBytes(java.nio.charset.StandardCharsets.UTF_8))).execute(jdbc.getDataSource());
        var user = context.getBean(TenantProvisioningService.class).create("INDIVIDUAL", "PASSWORD", "help@example.com", "Help User", null, null, true);
        jdbc.update("INSERT INTO local_credentials(user_id,password_hash,password_changed_at,failed_attempts,created_at,updated_at) VALUES(?,'test-fixture',CURRENT_TIMESTAMP,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", user.userId());
        identity = new com.book.user.dto.AuthenticatedIdentity(user.userId(), user.tenantId(), user.membershipId(), "PASSWORD", null, null, 1);
        issued = context.getBean(SessionService.class).issue(identity);
        access = issued.response().accessToken();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var response = mvc.perform(get("/api/user/auth/csrf")).andReturn().getResponse();
        csrfCookie = response.getCookie("XSRF-TOKEN");
        csrf = JsonMapper.builder().build().readTree(response.getContentAsString()).path("token").asText();
        var page = mvc.perform(post("/api/ai-help/pages").header("Authorization", "Bearer " + access)
                .cookie(csrfCookie).header("X-XSRF-TOKEN", csrf)).andExpect(status().isCreated()).andReturn().getResponse();
        pageId = JsonMapper.builder().build().readTree(page.getContentAsString()).path("pageId").asLong();
    }
    @AfterEach void close() { if (context != null) context.close(); }

    @Test @ExtendWith(OutputCaptureExtension.class)
    void plainTextReachesControllerAndReturnsAcknowledgment(CapturedOutput output) throws Exception {
        mvc.perform(post("/api/ai-help/chat").param("pageId", Long.toString(pageId)).param("revision", "1").header("Authorization", "Bearer " + access)
                .cookie(csrfCookie).header("X-XSRF-TOKEN", csrf).contentType("text/plain;charset=UTF-8").content("hi"))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.message.content").value("hi"))
                .andExpect(jsonPath("$.message.senderRole").value("USER"))
                .andExpect(jsonPath("$.assistantStatus").value("NOT_CONFIGURED"));
        org.assertj.core.api.Assertions.assertThat(output.getOut()).contains("hi" + System.lineSeparator());
        mvc.perform(get("/api/ai-help/pages/"+pageId+"/messages").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk()).andExpect(jsonPath("$.messages.length()").value(1)).andExpect(jsonPath("$.messages[0].content").value("hi"));
    }
    @Test void rejectsBlankMissingBodyAndJson() throws Exception {
        for (String body : new String[]{"", "  \n\t"})
            mvc.perform(post("/api/ai-help/chat").param("pageId", Long.toString(pageId)).param("revision", "1").header("Authorization", "Bearer " + access)
                    .cookie(csrfCookie).header("X-XSRF-TOKEN", csrf).contentType("text/plain").content(body))
                    .andExpect(status().isBadRequest());
        mvc.perform(post("/api/ai-help/chat").param("pageId", Long.toString(pageId)).param("revision", "1").header("Authorization", "Bearer " + access)
                .cookie(csrfCookie).header("X-XSRF-TOKEN", csrf).contentType("application/json").content("{\"query\":\"hello\"}"))
                .andExpect(status().isUnsupportedMediaType());
    }
    @Test void requiresAuthenticationCsrfAndActiveEndpointPolicy() throws Exception {
        mvc.perform(post("/api/ai-help/chat").contentType("text/plain").content("Hello")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/ai-help/chat").param("pageId", Long.toString(pageId)).param("revision", "1").header("Authorization", "Bearer " + access).contentType("text/plain").content("Hello"))
                .andExpect(status().isForbidden());
        context.getBean(JdbcTemplate.class).update("UPDATE api_endpoints SET is_active=FALSE WHERE path_pattern='/api/ai-help/chat'");
        mvc.perform(post("/api/ai-help/chat").param("pageId", Long.toString(pageId)).param("revision", "1").header("Authorization", "Bearer " + access)
                .cookie(csrfCookie).header("X-XSRF-TOKEN", csrf).contentType("text/plain").content("Hello"))
                .andExpect(status().isForbidden());
    }

    @Test void logoutAndLaterAuthenticatedSessionRestoreTheSameSelectedPrivatePage() throws Exception {
        mvc.perform(post("/api/ai-help/chat").param("pageId", Long.toString(pageId)).param("revision", "1")
                .header("Authorization", "Bearer " + access).cookie(csrfCookie).header("X-XSRF-TOKEN", csrf)
                .contentType("text/plain;charset=UTF-8").content("Persistent message")).andExpect(status().isOk());
        var sessions = context.getBean(SessionService.class); sessions.logout(issued.refreshToken());
        mvc.perform(get("/api/ai-help/pages").header("Authorization", "Bearer " + access)).andExpect(status().isUnauthorized());
        access = sessions.issue(identity).response().accessToken();
        mvc.perform(get("/api/ai-help/pages").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk()).andExpect(jsonPath("$.lastSelectedPageId").value(pageId))
                .andExpect(jsonPath("$.pages[0].messages").doesNotExist());
        mvc.perform(get("/api/ai-help/pages/"+pageId+"/messages").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk()).andExpect(jsonPath("$.messages[0].content").value("Persistent message"));
    }

    @Test void superUserHasFullOwnChatAccessWithoutExplicitRolePermissionGrants() throws Exception {
        var jdbc=context.getBean(JdbcTemplate.class);
        jdbc.update("UPDATE roles SET role_code='SUPER_USER',is_system=TRUE WHERE tenant_id=?",identity.tenantId());
        jdbc.update("DELETE FROM role_permissions WHERE tenant_id=?",identity.tenantId());
        access=context.getBean(SessionService.class).issue(identity).response().accessToken();
        String bearer="Bearer "+access;
        mvc.perform(get("/api/ai-help/pages").header("Authorization",bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pages[0].pageId").value(pageId));
        var created=mvc.perform(post("/api/ai-help/pages").header("Authorization",bearer)
                .cookie(csrfCookie).header("X-XSRF-TOKEN",csrf)).andExpect(status().isCreated()).andReturn();
        long second=JsonMapper.builder().build().readTree(created.getResponse().getContentAsString()).path("pageId").asLong();
        mvc.perform(put("/api/ai-help/selection").header("Authorization",bearer).cookie(csrfCookie).header("X-XSRF-TOKEN",csrf)
                .contentType("application/json").content("{\"pageId\":"+second+"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pageId").value(second));
        mvc.perform(patch("/api/ai-help/pages/"+second).header("Authorization",bearer).cookie(csrfCookie).header("X-XSRF-TOKEN",csrf)
                .contentType("application/json").content("{\"name\":\"Superadmin support\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Superadmin support"));
        mvc.perform(post("/api/ai-help/chat").param("pageId",Long.toString(second)).param("revision","1")
                .header("Authorization",bearer).cookie(csrfCookie).header("X-XSRF-TOKEN",csrf)
                .contentType("text/plain;charset=UTF-8").content("hi from superadmin"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message.content").value("hi from superadmin"));
        mvc.perform(get("/api/ai-help/pages/"+second+"/messages").header("Authorization",bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.messages.length()").value(1));
        mvc.perform(get("/api/ai-help/pages/"+second+"/pdf").header("Authorization",bearer))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("application/pdf"));
        mvc.perform(delete("/api/ai-help/pages/"+second+"/messages").param("revision","1").header("Authorization",bearer)
                .cookie(csrfCookie).header("X-XSRF-TOKEN",csrf)).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(2));
        mvc.perform(delete("/api/ai-help/pages/"+second).param("revision","2").header("Authorization",bearer)
                .cookie(csrfCookie).header("X-XSRF-TOKEN",csrf)).andExpect(status().isOk());
        var other=context.getBean(TenantProvisioningService.class).create("INDIVIDUAL","PASSWORD","other-help@example.com","Other User",null,null,true);
        jdbc.update("INSERT INTO ai_chat_pages(owner_user_id,tenant_id,name,created_at,updated_at,revision,next_message_order) VALUES(?,?,'Private other chat',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,1,0)",other.userId(),other.tenantId());
        long foreignPage=jdbc.queryForObject("SELECT page_id FROM ai_chat_pages WHERE owner_user_id=?",Long.class,other.userId());
        mvc.perform(get("/api/ai-help/pages/"+foreignPage+"/messages").header("Authorization",bearer)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/ai-help/pages/"+foreignPage).param("revision","1").header("Authorization",bearer)
                .cookie(csrfCookie).header("X-XSRF-TOKEN",csrf)).andExpect(status().isNotFound());
    }
}
