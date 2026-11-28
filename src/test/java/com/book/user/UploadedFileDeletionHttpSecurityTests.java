package com.book.user;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import com.book.spreadsheet.api.SpreadsheetExceptionHandler;
import com.book.upload.controller.UploadedFileDeletionController;
import com.book.upload.service.UploadedFileDeletionService;
import com.book.user.dto.AuthenticatedIdentity;
import com.book.user.service.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import tools.jackson.databind.json.JsonMapper;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real bearer/policy/CSRF chain; service ownership and filesystem behavior use isolated tests. */
class UploadedFileDeletionHttpSecurityTests {
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    @Import({UserSecurityIntegrationTests.Config.class,UploadedFileDeletionController.class,SpreadsheetExceptionHandler.class})
    static class Config {@Bean UploadedFileDeletionService deletion(){return mock(UploadedFileDeletionService.class);}}
    AnnotationConfigWebApplicationContext context;
    MockMvc mvc;
    JdbcTemplate jdbc;
    SessionService sessions;
    AuthenticatedIdentity actor;
    Cookie cookie;
    String csrf;
    UploadedFileDeletionService deletion;
    @BeforeEach void setup()throws Exception {
        context=new AnnotationConfigWebApplicationContext();context.setServletContext(new MockServletContext());
        context.setEnvironment(new MockEnvironment().withProperty("app.user.jwt.secret",Base64.getEncoder().encodeToString("delete-http-security-test-key-32-bytes!".getBytes(StandardCharsets.UTF_8))).withProperty("app.user.cookie.secure","true"));
        context.register(Config.class);context.refresh();
        jdbc=context.getBean(JdbcTemplate.class);sessions=context.getBean(SessionService.class);deletion=context.getBean(UploadedFileDeletionService.class);
        var created=context.getBean(TenantProvisioningService.class).create("INDIVIDUAL","PASSWORD","deletion@example.com","Uploader",null,null,true);
        jdbc.update("INSERT INTO local_credentials(user_id,password_hash,password_changed_at,failed_attempts,created_at,updated_at) VALUES(?,'fixture',CURRENT_TIMESTAMP,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",created.userId());
        actor=new AuthenticatedIdentity(created.userId(),created.tenantId(),created.membershipId(),"PASSWORD",null,null,1);
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var initial=mvc.perform(get("/api/user/auth/csrf")).andExpect(status().isOk()).andReturn();cookie=initial.getResponse().getCookie("XSRF-TOKEN");csrf=JsonMapper.builder().build().readTree(initial.getResponse().getContentAsString()).path("token").asText();
    }
    @AfterEach void close(){if(context!=null)context.close();}
    @Test void deletionRequiresBearerDeletePermissionAndCsrf()throws Exception {
        mvc.perform(delete("/api/books/7")).andExpect(status().isUnauthorized());
        var issued=sessions.issue(actor);String bearer="Bearer "+issued.response().accessToken();
        mvc.perform(delete("/api/books/7").header("Authorization",bearer)).andExpect(status().isForbidden());
        verifyNoInteractions(deletion);
        mvc.perform(delete("/api/books/7").header("Authorization",bearer).cookie(cookie).header("X-XSRF-TOKEN",csrf)).andExpect(status().isNoContent());
        verify(deletion).deletePdf(7);
        jdbc.update("DELETE FROM role_permissions WHERE tenant_id=? AND permission_id=(SELECT permission_id FROM permissions WHERE permission_code='BOOKS.DELETE')",actor.tenantId());
        var restricted=sessions.issue(actor);
        mvc.perform(delete("/api/spreadsheets/8").header("Authorization","Bearer "+restricted.response().accessToken()).cookie(cookie).header("X-XSRF-TOKEN",csrf)).andExpect(status().isForbidden());
        verifyNoMoreInteractions(deletion);
    }
}
