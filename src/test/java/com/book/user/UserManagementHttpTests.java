package com.book.user;
import java.util.*;
import com.book.user.controller.UserManagementController;
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

class UserManagementHttpTests {
 @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
 @Import({UserSecurityIntegrationTests.Config.class,UserManagementController.class,UserManagementService.class,UserDeletionService.class,UserDeletionFiles.class})static class Config{}
 AnnotationConfigWebApplicationContext context;MockMvc mvc;JdbcTemplate jdbc;SessionService sessions;long operator,admin,employee,tenant;Cookie csrfCookie;String csrf;JsonMapper json=JsonMapper.builder().build();
 @BeforeEach void setup()throws Exception{
  context=new AnnotationConfigWebApplicationContext();context.setServletContext(new MockServletContext());context.setEnvironment(new MockEnvironment().withProperty("app.user.jwt.secret",Base64.getEncoder().encodeToString(new byte[32])));context.register(Config.class);context.refresh();jdbc=context.getBean(JdbcTemplate.class);sessions=context.getBean(SessionService.class);
  jdbc.execute("CREATE TABLE pdf_file_author_details(aouthor_id BIGINT AUTO_INCREMENT PRIMARY KEY)");jdbc.execute("CREATE TABLE pdf_file_status(book_upload_id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_id BIGINT,status VARCHAR(16))");jdbc.execute("CREATE TABLE pdf_file_details(book_id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_id BIGINT,author_id BIGINT)");jdbc.execute("CREATE TABLE pdf_file_topic_details(topic_id BIGINT AUTO_INCREMENT PRIMARY KEY,book_id BIGINT,pdf_path VARCHAR(2048))");
  var provisioning=context.getBean(TenantProvisioningService.class);operator=provisioning.create("INDIVIDUAL","PASSWORD","operator-http@example.com","Operator",null,null,true).userId();
  jdbc.update("UPDATE roles SET role_code='SUPER_USER',is_system=TRUE WHERE tenant_id IN(SELECT tenant_id FROM tenant_memberships WHERE user_id=?)",operator);
  var company=provisioning.create("ORGANIZATION","PASSWORD","admin-http@example.com","Admin","http-company","HTTP Company",true);admin=company.userId();tenant=company.tenantId();jdbc.update("UPDATE roles SET role_code='ADMIN',is_system=TRUE WHERE tenant_id=?",tenant);
  jdbc.update("INSERT INTO users(display_name,primary_email,email_verified_at,status,security_version,created_at,updated_at) VALUES('Employee','employee-http@example.com',CURRENT_TIMESTAMP,'ACTIVE',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");employee=jdbc.queryForObject("SELECT user_id FROM users WHERE primary_email='employee-http@example.com'",Long.class);
  jdbc.update("INSERT INTO tenant_memberships(tenant_id,user_id,status,authz_version,joined_at,created_at,updated_at) VALUES(?,?,'ACTIVE',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",tenant,employee);
  for(long id:List.of(operator,admin,employee))jdbc.update("INSERT INTO local_credentials(user_id,password_hash,password_changed_at,failed_attempts,created_at,updated_at) VALUES(?,'immutable-fixture',CURRENT_TIMESTAMP,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",id);
  mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();var response=mvc.perform(get("/api/user/auth/csrf")).andReturn();csrfCookie=response.getResponse().getCookie("XSRF-TOKEN");csrf=json.readTree(response.getResponse().getContentAsString()).path("token").asText();
 }
 @AfterEach void close(){context.close();}
 @Test void currentProfileIsAuthenticatedSelfOnlyWithRolesAndNeverPermissionsOrCredentials()throws Exception{
  mvc.perform(get("/api/user/profile")).andExpect(status().isUnauthorized());
  var ordinary=issue(employee);var response=mvc.perform(get("/api/user/profile").param("userId",Long.toString(operator)).header("Authorization",bearer(ordinary)))
   .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("employee-http@example.com")).andExpect(jsonPath("$.roles").isArray()).andExpect(jsonPath("$.effectivePermissions").doesNotExist()).andReturn();
  assertThat(response.getResponse().getContentAsString()).doesNotContain("immutable-fixture","password_hash","accessToken","permissions");
  mvc.perform(get("/api/user/users").header("Authorization",bearer(ordinary))).andExpect(status().isForbidden());
 }
 @Test void directProfileMassAssignmentIsRejectedAndUsernamePasswordStayImmutable()throws Exception{
  var current=issue(operator);var details=mvc.perform(get("/api/user/users/"+employee).header("Authorization",bearer(current))).andExpect(status().isOk()).andReturn();
  String version=json.readTree(details.getResponse().getContentAsString()).path("user").path("version").asText();
  for(String forbidden:List.of("username","password","primary_email","tenantId","status","roles","is_system")){
   var body=new LinkedHashMap<String,Object>();body.put("displayName","Allowed name");body.put("version",version);body.put(forbidden,"forged");
   mvc.perform(mutate(put("/api/user/users/"+employee),json.writeValueAsString(body)).header("Authorization",bearer(current))).andExpect(status().isBadRequest());
  }
  assertThat(jdbc.queryForObject("SELECT primary_email FROM users WHERE user_id=?",String.class,employee)).isEqualTo("employee-http@example.com");assertThat(jdbc.queryForObject("SELECT password_hash FROM local_credentials WHERE user_id=?",String.class,employee)).isEqualTo("immutable-fixture");
  mvc.perform(put("/api/user/users/"+employee).contentType("application/json").content(json.writeValueAsString(Map.of("displayName","Allowed","version",version))).header("Authorization",bearer(current))).andExpect(status().isForbidden());
 }
 @Test void softDeleteAndSelectedActivationRejectOldAccessAndRefreshAndDoNotCreateAccounts()throws Exception{
  var actor=issue(operator);var old=issue(employee);var preview=mvc.perform(get("/api/user/users/"+employee+"/deletion-preview").param("hard","false").header("Authorization",bearer(actor))).andExpect(status().isOk()).andReturn();
  var data=json.readTree(preview.getResponse().getContentAsString());String body=json.writeValueAsString(Map.of("version",data.path("version").asText(),"scope",data.path("scope").asText(),"affectedUsers",data.path("affectedUsers").asInt(),"confirmation",data.path("confirmation").asText()));
  mvc.perform(mutate(post("/api/user/users/"+employee+"/soft-delete"),body).header("Authorization",bearer(actor))).andExpect(status().isNoContent());
  mvc.perform(get("/api/user/profile").header("Authorization",bearer(old))).andExpect(status().isUnauthorized());
  var detail=mvc.perform(get("/api/user/users/"+employee).header("Authorization",bearer(actor))).andReturn();String version=json.readTree(detail.getResponse().getContentAsString()).path("user").path("version").asText();
  mvc.perform(mutate(post("/api/user/users/"+employee+"/activate"),json.writeValueAsString(Map.of("version",version,"resumeTenant",false))).header("Authorization",bearer(actor))).andExpect(status().isConflict());
  jdbc.update("UPDATE users SET status='INACTIVE' WHERE user_id=?",admin);
  var inactive=mvc.perform(get("/api/user/users/"+admin).header("Authorization",bearer(actor))).andReturn();
  String inactiveVersion=json.readTree(inactive.getResponse().getContentAsString()).path("user").path("version").asText();
  mvc.perform(mutate(post("/api/user/users/"+admin+"/activate"),json.writeValueAsString(Map.of("version",inactiveVersion,"resumeTenant",false))).header("Authorization",bearer(actor))).andExpect(status().isOk());
  assertThatThrownBy(()->sessions.refresh(old.refreshToken())).isInstanceOf(RuntimeException.class);
  mvc.perform(mutate(post("/api/user/users/999999/activate"),"{\"version\":\"missing\",\"resumeTenant\":false}").header("Authorization",bearer(actor))).andExpect(status().isNotFound());
 }
 @Test void delegatedAdminDirectQueriesAreScopedBeforeCountsAndDetails()throws Exception{
  jdbc.update("INSERT INTO role_permissions(tenant_id,role_id,permission_id,is_explicit,created_at,updated_at) SELECT r.tenant_id,r.role_id,p.permission_id,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP FROM roles r CROSS JOIN permissions p WHERE r.tenant_id=? AND p.permission_code IN ('USER_VIEW','USER_EDIT')",tenant);
  var actor=issue(admin);
  mvc.perform(get("/api/user/users").param("size","1").header("Authorization",bearer(actor))).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.users[0].userId").value(employee));
  mvc.perform(get("/api/user/users/"+employee).header("Authorization",bearer(actor))).andExpect(status().isOk());
  mvc.perform(get("/api/user/users/"+operator).header("Authorization",bearer(actor))).andExpect(status().isNotFound());
  mvc.perform(get("/api/user/users/"+admin).header("Authorization",bearer(actor))).andExpect(status().isNotFound());
  mvc.perform(get("/api/user/users").param("tenantId",Long.toString(tenant+999)).header("Authorization",bearer(actor))).andExpect(status().isNotFound());
 }
 @Test void undelegatedAdminCannotListOtherUsersOrUseDetailPermissionAndAssignmentEndpoints()throws Exception{
  var actor=issue(admin);
  for(String path:List.of("/api/user/users","/api/user/users/"+employee,"/api/user/users/"+operator,"/api/user/users/"+employee+"/assignable-roles"))mvc.perform(get(path).header("Authorization",bearer(actor))).andExpect(status().isForbidden());
  mvc.perform(mutate(put("/api/user/users/"+employee+"/roles"),"{\"roleIds\":[],\"version\":\"forged\"}").header("Authorization",bearer(actor))).andExpect(status().isForbidden());
 }
 SessionService.IssuedSession issue(long user){var row=jdbc.queryForMap("SELECT m.tenant_id,m.membership_id,u.security_version FROM users u JOIN tenant_memberships m ON m.user_id=u.user_id WHERE u.user_id=?",user);return sessions.issue(new AuthenticatedIdentity(user,((Number)row.get("tenant_id")).longValue(),((Number)row.get("membership_id")).longValue(),"PASSWORD",null,null,((Number)row.get("security_version")).longValue()));}
 String bearer(SessionService.IssuedSession session){return "Bearer "+session.response().accessToken();}
 MockHttpServletRequestBuilder mutate(MockHttpServletRequestBuilder request,String body){return request.cookie(csrfCookie).header("X-XSRF-TOKEN",csrf).contentType("application/json").content(body);}
}
