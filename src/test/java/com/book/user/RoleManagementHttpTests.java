package com.book.user;

import java.time.Clock;
import java.util.*;
import com.book.user.controller.RoleManagementController;
import com.book.user.service.*;
import com.book.user.dto.AuthenticatedIdentity;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RoleManagementHttpTests {
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    @Import({UserSecurityIntegrationTests.Config.class,RoleManagementService.class,RoleManagementController.class}) static class Config {}
    AnnotationConfigWebApplicationContext context;MockMvc mvc;JdbcTemplate jdbc;SessionService sessions;
    TenantProvisioningService.ProvisionedAccount own,other;Cookie csrfCookie;String csrf;
    JsonMapper json=JsonMapper.builder().build();
    @BeforeEach void setup() throws Exception {
        context=new AnnotationConfigWebApplicationContext();context.setServletContext(new MockServletContext());
        context.setEnvironment(new MockEnvironment().withProperty("app.user.jwt.secret",Base64.getEncoder().encodeToString(new byte[32])));
        context.register(Config.class);context.refresh();jdbc=context.getBean(JdbcTemplate.class);sessions=context.getBean(SessionService.class);
        own=account("own-http@example.com");other=account("other-http@example.com");
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var response=mvc.perform(get("/api/user/auth/csrf")).andReturn();csrfCookie=response.getResponse().getCookie("XSRF-TOKEN");csrf=json.readTree(response.getResponse().getContentAsString()).path("token").asText();
    }
    @AfterEach void close(){context.close();}
    @Test void managementRequiresBearerExplicitAbilityAndCsrfEvenForDirectPayloads() throws Exception {
        mvc.perform(get("/api/user/roles")).andExpect(status().isUnauthorized());
        var ordinary=issue(own);
        mvc.perform(get("/api/user/roles").header("Authorization","Bearer "+ordinary.response().accessToken())).andExpect(status().isForbidden());
        mvc.perform(mutate(post("/api/user/roles"),"{\"name\":\"ADMIN\",\"permissionIds\":[],\"is_system\":true}").header("Authorization","Bearer "+ordinary.response().accessToken())).andExpect(status().isForbidden());
        promote();var admin=issue(own);
        mvc.perform(post("/api/user/roles").header("Authorization","Bearer "+admin.response().accessToken()).contentType("application/json").content("{\"name\":\"Reader\",\"permissionIds\":[]}"))
                .andExpect(status().isForbidden());
    }
    @Test void superUserDelegatesManagementButRecipientCannotEditAdminOrCrossOrganization() throws Exception {
        promote();var operator=issue(own);var oldTarget=issue(other);
        var ids=jdbc.queryForList("SELECT permission_id FROM permissions WHERE permission_code IN ('ROLE_VIEW','ROLE_CREATE','ROLE_EDIT') ORDER BY permission_id",Long.class);
        var created=mvc.perform(mutate(post("/api/user/roles"),json.writeValueAsString(Map.of("tenantId",other.tenantId(),"name","Administrator","systemRole","ADMIN","permissionIds",ids)))
                .header("Authorization","Bearer "+operator.response().accessToken())).andExpect(status().isCreated()).andReturn();
        long role=json.readTree(created.getResponse().getContentAsString()).path("roleId").asLong();
        mvc.perform(mutate(put("/api/user/roles/"+role+"/members/"+other.membershipId()).param("tenantId",Long.toString(other.tenantId())),"{}")
                .header("Authorization","Bearer "+operator.response().accessToken())).andExpect(status().isNoContent());
        mvc.perform(get("/api/user/auth/me").header("Authorization","Bearer "+oldTarget.response().accessToken())).andExpect(status().isUnauthorized());
        assertThatThrownBy(()->sessions.refresh(oldTarget.refreshToken())).isInstanceOf(com.book.user.exception.AuthException.class);
        var delegated=issue(other);String bearer="Bearer "+delegated.response().accessToken();
        mvc.perform(get("/api/user/roles/catalog").header("Authorization",bearer)).andExpect(status().isOk()).andExpect(jsonPath("$.canCreate").value(true)).andExpect(jsonPath("$.canEdit").value(true));
        mvc.perform(mutate(put("/api/user/roles/"+role),"{\"name\":\"Reduced\",\"version\":1,\"permissionIds\":[]}").header("Authorization",bearer)).andExpect(status().isForbidden());
        mvc.perform(get("/api/user/roles").param("tenantId",Long.toString(own.tenantId())).header("Authorization",bearer)).andExpect(status().isNotFound());
        mvc.perform(mutate(post("/api/user/roles"),"{\"name\":\"Readers\",\"permissionIds\":[]}").header("Authorization",bearer)).andExpect(status().isCreated());
        mvc.perform(get("/api/user/roles").header("Authorization",bearer)).andExpect(status().isUnauthorized());
    }
    private void promote(){jdbc.update("UPDATE roles SET role_code='SUPER_USER',is_system=TRUE WHERE tenant_id=?",own.tenantId());}
    private TenantProvisioningService.ProvisionedAccount account(String email){
        var result=context.getBean(TenantProvisioningService.class).create("INDIVIDUAL","PASSWORD",email,"User",null,null,true);
        jdbc.update("INSERT INTO local_credentials(user_id,password_hash,password_changed_at,failed_attempts,created_at,updated_at) VALUES(?,'unused',CURRENT_TIMESTAMP,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",result.userId());return result;
    }
    private SessionService.IssuedSession issue(TenantProvisioningService.ProvisionedAccount account){return sessions.issue(new AuthenticatedIdentity(account.userId(),account.tenantId(),account.membershipId(),"PASSWORD",null,null,1));}
    private MockHttpServletRequestBuilder mutate(MockHttpServletRequestBuilder request,String body){return request.cookie(csrfCookie).header("X-XSRF-TOKEN",csrf).contentType("application/json").content(body);}
}
