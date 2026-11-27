package com.book.user;

import java.util.*;
import com.book.user.controller.TenantUserCreationController;
import com.book.user.controller.OwnProfileController;
import com.book.user.controller.UserManagementController;
import com.book.user.dto.AuthenticatedIdentity;
import com.book.user.service.*;
import com.book.user.repository.AccountRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real MVC/security/current-tenant SQL and invitation transactions; only mail is mocked. */
class TenantUserCreationTests {
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    @Import({UserSecurityIntegrationTests.Config.class,TenantUserCreationController.class,TenantUserCreationService.class,
        UserManagementController.class,UserManagementService.class,UserDeletionService.class,UserDeletionFiles.class,
        OwnProfileController.class,OwnProfileService.class,RoleManagementService.class}) static class Config{}
    AnnotationConfigWebApplicationContext context;MockMvc mvc;JdbcTemplate jdbc;SessionService sessions;PasswordService passwords;
    CredentialMailService mail;long creator,tenant,foreign,operator;Cookie csrfCookie;String csrf;JsonMapper json=JsonMapper.builder().build();
    @BeforeEach void setup()throws Exception{
        context=new AnnotationConfigWebApplicationContext();context.setServletContext(new MockServletContext());
        context.setEnvironment(new MockEnvironment().withProperty("app.user.jwt.secret",Base64.getEncoder().encodeToString(new byte[32])));
        context.register(Config.class);context.refresh();jdbc=context.getBean(JdbcTemplate.class);sessions=context.getBean(SessionService.class);passwords=context.getBean(PasswordService.class);mail=context.getBean(CredentialMailService.class);
        var provisioning=context.getBean(TenantProvisioningService.class);
        var account=provisioning.create("ORGANIZATION","PASSWORD","creator@example.com","Creator","create-company","Create Company",true);creator=account.userId();tenant=account.tenantId();
        foreign=provisioning.create("ORGANIZATION","PASSWORD","other@example.com","Other","other-company","Other Company",true).userId();
        operator=provisioning.create("INDIVIDUAL","PASSWORD","operator-create@example.com","Operator",null,null,true).userId();
        jdbc.update("UPDATE roles SET role_code='SUPER_USER',is_system=TRUE WHERE tenant_id IN(SELECT tenant_id FROM tenant_memberships WHERE user_id=?)",operator);
        for(long id:List.of(creator,foreign,operator))jdbc.update("INSERT INTO local_credentials(user_id,password_hash,password_changed_at,failed_attempts,created_at,updated_at) VALUES(?,'fixture',CURRENT_TIMESTAMP,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",id);
        jdbc.execute("CREATE TABLE pdf_file_author_details(aouthor_id BIGINT AUTO_INCREMENT PRIMARY KEY)");jdbc.execute("CREATE TABLE pdf_file_status(book_upload_id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_id BIGINT,status VARCHAR(16))");jdbc.execute("CREATE TABLE pdf_file_details(book_id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_id BIGINT,author_id BIGINT)");jdbc.execute("CREATE TABLE pdf_file_topic_details(topic_id BIGINT AUTO_INCREMENT PRIMARY KEY,book_id BIGINT,pdf_path VARCHAR(2048))");
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();var initial=mvc.perform(get("/api/user/auth/csrf")).andReturn();csrfCookie=initial.getResponse().getCookie("XSRF-TOKEN");csrf=json.readTree(initial.getResponse().getContentAsString()).path("token").asText();
    }
    @AfterEach void close(){context.close();}
    @Test void createRequiresExplicitCapabilityNotRoleLabelAndDoesNotGrantViewDeleteOrRoleCreation()throws Exception{
        jdbc.update("UPDATE roles SET display_name='Create User' WHERE tenant_id=?",tenant);
        var unprivileged=issue(creator);
        mvc.perform(get("/api/user/users/create-options").header("Authorization",bearer(unprivileged))).andExpect(status().isForbidden());
        mvc.perform(mutate(post("/api/user/users"),"{\"email\":\"new@example.com\",\"displayName\":\"New\"}").header("Authorization",bearer(unprivileged))).andExpect(status().isForbidden());
        grant("USER_CREATE");var allowed=issue(creator);
        mvc.perform(get("/api/user/users/capabilities").header("Authorization",bearer(allowed))).andExpect(status().isOk())
            .andExpect(jsonPath("$.canCreateUsers").value(true)).andExpect(jsonPath("$.canViewUsers").value(false)).andExpect(jsonPath("$.canManageRoles").value(false));
        mvc.perform(get("/api/user/users").header("Authorization",bearer(allowed))).andExpect(status().isForbidden());
        mvc.perform(mutate(post("/api/user/roles"),"{\"name\":\"Forged\",\"permissionIds\":[]}").header("Authorization",bearer(allowed))).andExpect(status().isForbidden());
        verifyNoInteractions(mail);
    }
    @Test void permittedNonAdminCreatesOnlyCurrentTenantMemberAndReusesPasswordSetupWithoutRoleEscalation()throws Exception{
        grant("USER_CREATE");var allowed=issue(creator);long tenants=jdbc.queryForObject("SELECT COUNT(*) FROM tenants",Long.class);
        mvc.perform(get("/api/user/users/create-options").header("Authorization",bearer(allowed))).andExpect(status().isOk()).andExpect(jsonPath("$.tenantId").value(tenant)).andExpect(jsonPath("$.tenantName").value("Create Company"));
        var result=mvc.perform(mutate(post("/api/user/users"),"{\"email\":\" New@Example.com \",\"displayName\":\"New employee\"}").header("Authorization",bearer(allowed)))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.username").value("new@example.com")).andExpect(jsonPath("$.status").value("PENDING")).andReturn();
        long id=json.readTree(result.getResponse().getContentAsString()).path("userId").asLong();
        assertThat(jdbc.queryForObject("SELECT tenant_id FROM tenant_memberships WHERE user_id=?",Long.class,id)).isEqualTo(tenant);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tenants",Long.class)).isEqualTo(tenants);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_roles WHERE membership_id IN(SELECT membership_id FROM tenant_memberships WHERE user_id=?)",Long.class,id)).isZero();
        var secret=ArgumentCaptor.forClass(String.class);verify(mail).send(eq("new@example.com"),eq(PasswordService.SETUP),secret.capture(),isNull(),any());
        passwords.completeSetup(secret.getValue(),"a long invited password");assertThat(passwords.authenticate("new@example.com","a long invited password").userId()).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT status FROM tenants WHERE tenant_id=?",String.class,tenant)).isEqualTo("ACTIVE");
    }
    @Test void forgedTenantRoleAndCredentialFieldsAreRejectedAndDuplicateEmailsNeverLinkAcrossTenants()throws Exception{
        grant("USER_CREATE");var allowed=issue(creator);long before=jdbc.queryForObject("SELECT COUNT(*) FROM users",Long.class);
        for(String field:List.of("tenantCode","roleIds","password","status","is_system","authenticationType","loginPolicy")){
            var body=new LinkedHashMap<String,Object>();body.put("email","forged@example.com");body.put("displayName","Forged");body.put(field,999);
            mvc.perform(mutate(post("/api/user/users"),json.writeValueAsString(body)).header("Authorization",bearer(allowed))).andExpect(status().isBadRequest());
        }
        long foreignTenant=jdbc.queryForObject("SELECT tenant_id FROM tenant_memberships WHERE user_id=?",Long.class,foreign);
        mvc.perform(mutate(post("/api/user/users"),json.writeValueAsString(Map.of("email","forged@example.com","displayName","Forged","tenantId",foreignTenant))).header("Authorization",bearer(allowed))).andExpect(status().isNotFound());
        mvc.perform(mutate(post("/api/user/users"),"{\"email\":\"other@example.com\",\"displayName\":\"Do not link\"}").header("Authorization",bearer(allowed))).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users",Long.class)).isEqualTo(before);
        verifyNoInteractions(mail);
    }
    @Test void invitationFailureRollsBackAccountMembershipAndCredentialWithoutHidingIndividualCapability()throws Exception{
        grant("USER_CREATE");var allowed=issue(creator);long before=jdbc.queryForObject("SELECT COUNT(*) FROM users",Long.class);
        doThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"Email unavailable")).when(mail).send(anyString(),anyString(),anyString(),any(),any());
        mvc.perform(mutate(post("/api/user/users"),"{\"email\":\"failure@example.com\",\"displayName\":\"No orphan\"}").header("Authorization",bearer(allowed))).andExpect(status().isServiceUnavailable());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users",Long.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM credential_tokens",Long.class)).isZero();
        mvc.perform(get("/api/user/users/create-options").header("Authorization",bearer(issue(operator)))).andExpect(status().isOk()).andExpect(jsonPath("$.tenantType").value("INDIVIDUAL"));
    }
    @Test void activeSsoTenantInvitesEmployeeWithProviderBoundSingleUseProofWithoutWeakeningOwnerOrEmailChecks()throws Exception{
        grant("USER_CREATE");
        context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("sso-invitation-fixture",Map.of(
                "app.user.sso.enabled","true","app.user.providers.invited.issuer-uri","https://id.fixture.example",
                "app.user.providers.invited.client-id","fixture-client","app.user.providers.invited.redirect-uri","http://localhost:9090/login/oauth2/code/invited")));
        jdbc.update("UPDATE tenants SET login_policy='SSO' WHERE tenant_id=?",tenant);
        jdbc.update("INSERT INTO identity_providers(tenant_id,provider_type,provider_code,issuer_url,client_id,status,created_at,updated_at) VALUES(?,'ORG_OIDC','invited','https://id.fixture.example','fixture-client','ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",tenant);
        long provider=jdbc.queryForObject("SELECT identity_provider_id FROM identity_providers WHERE provider_code='invited'",Long.class);
        jdbc.update("INSERT INTO external_identities(identity_provider_id,user_id,subject,created_at) VALUES(?,?,'creator-subject',CURRENT_TIMESTAMP)",provider,creator);
        long membership=jdbc.queryForObject("SELECT membership_id FROM tenant_memberships WHERE user_id=?",Long.class,creator);
        var actor=sessions.issue(new AuthenticatedIdentity(creator,tenant,membership,"SSO",provider,null,1));
        var response=mvc.perform(mutate(post("/api/user/users"),"{\"email\":\"sso-employee@example.com\",\"displayName\":\"SSO employee\"}").header("Authorization",bearer(actor))).andExpect(status().isCreated()).andReturn();
        long id=json.readTree(response.getResponse().getContentAsString()).path("userId").asLong();var secret=ArgumentCaptor.forClass(String.class);
        verify(mail).send(eq("sso-employee@example.com"),eq(PasswordService.ENROLLMENT),secret.capture(),eq("create-company"),any());
        var identities=context.getBean(com.book.user.provider.OidcIdentityService.class);var proof=identities.enrollment("invited",secret.getValue());
        assertThatThrownBy(()->identities.resolve("invited",oidc("wrong@example.com","new-subject"),proof)).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM external_identities WHERE user_id=?",Long.class,id)).isZero();
        var identity=identities.resolve("invited",oidc("sso-employee@example.com","new-subject"),proof);
        assertThat(identity.userId()).isEqualTo(id);assertThat(identity.tenantId()).isEqualTo(tenant);
        assertThat(jdbc.queryForObject("SELECT owner_user_id FROM tenants WHERE tenant_id=?",Long.class,tenant)).isEqualTo(creator);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM local_credentials WHERE user_id=?",Long.class,id)).isZero();
        assertThatThrownBy(()->identities.resolve("invited",oidc("sso-employee@example.com","different-subject"),proof)).isInstanceOf(RuntimeException.class);
    }
    @Test void deletedMarkerIsDistinctFromInactiveAndCannotBeRevivedByActivationRecoveryOrOldSessions()throws Exception{
        grant("USER_CREATE");var allowed=issue(creator);
        var result=mvc.perform(mutate(post("/api/user/users"),"{\"email\":\"deleted@example.com\",\"displayName\":\"Deleted employee\"}").header("Authorization",bearer(allowed))).andExpect(status().isCreated()).andReturn();
        long id=json.readTree(result.getResponse().getContentAsString()).path("userId").asLong();var secret=ArgumentCaptor.forClass(String.class);verify(mail).send(eq("deleted@example.com"),eq(PasswordService.SETUP),secret.capture(),isNull(),any());passwords.completeSetup(secret.getValue(),"a long invited password");
        var old=issue(id);var actor=issue(operator);var preview=mvc.perform(get("/api/user/users/"+id+"/deletion-preview").param("hard","false").header("Authorization",bearer(actor))).andReturn();
        mvc.perform(mutate(post("/api/user/users/"+id+"/soft-delete"),confirm(preview)).header("Authorization",bearer(actor))).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE user_id=? AND deleted_at IS NOT NULL AND status='INACTIVE'",Long.class,id)).isEqualTo(1);
        var detail=mvc.perform(get("/api/user/users/"+id).header("Authorization",bearer(actor))).andExpect(status().isOk()).andExpect(jsonPath("$.user.deleted").value(true)).andReturn();
        String version=json.readTree(detail.getResponse().getContentAsString()).path("user").path("version").asText();
        mvc.perform(mutate(post("/api/user/users/"+id+"/activate"),json.writeValueAsString(Map.of("version",version,"resumeTenant",false))).header("Authorization",bearer(actor))).andExpect(status().isConflict());
        mvc.perform(get("/api/user/profile").header("Authorization",bearer(old))).andExpect(status().isUnauthorized());
        assertThatThrownBy(()->passwords.authenticate("deleted@example.com","a long invited password")).isInstanceOf(RuntimeException.class);
        reset(mail);passwords.requestReset("deleted@example.com");verify(mail).requireAvailable();verifyNoMoreInteractions(mail);
        mvc.perform(get("/api/user/users").param("status","DELETED").header("Authorization",bearer(actor))).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/user/users").param("status","INACTIVE").header("Authorization",bearer(actor))).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
    }
    @Test void tenantChoicesAndCreationAreSuperGlobalButOrdinaryCreatorsCannotForgeAnotherTenant()throws Exception{
        grant("USER_CREATE");var actor=issue(creator);long foreignTenant=jdbc.queryForObject("SELECT tenant_id FROM tenant_memberships WHERE user_id=?",Long.class,foreign);
        mvc.perform(get("/api/user/users/create-tenants").header("Authorization",bearer(actor))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].tenantId").value(tenant));
        mvc.perform(get("/api/user/users/create-options").param("tenantId",Long.toString(foreignTenant)).header("Authorization",bearer(actor))).andExpect(status().isNotFound());
        var highest=issue(operator);
        mvc.perform(get("/api/user/users/create-tenants").header("Authorization",bearer(highest))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));
        mvc.perform(get("/api/user/users/create-options").param("tenantId",Long.toString(foreignTenant)).header("Authorization",bearer(highest))).andExpect(status().isOk()).andExpect(jsonPath("$.tenantId").value(foreignTenant));
        var result=mvc.perform(mutate(post("/api/user/users"),json.writeValueAsString(Map.of("email","foreign-selected@example.com","displayName","Selected tenant member","tenantId",foreignTenant))).header("Authorization",bearer(highest))).andExpect(status().isCreated()).andReturn();
        long user=json.readTree(result.getResponse().getContentAsString()).path("userId").asLong();
        assertThat(jdbc.queryForObject("SELECT tenant_id FROM tenant_memberships WHERE user_id=?",Long.class,user)).isEqualTo(foreignTenant);
        verify(mail).send(eq("foreign-selected@example.com"),eq(PasswordService.SETUP),anyString(),isNull(),any());
    }
    @Test void individualTenantCanAddAUserWhenExplicitCapabilityAllowsIt()throws Exception{
        long individualTenant=jdbc.queryForObject("SELECT tenant_id FROM tenant_memberships WHERE user_id=?",Long.class,operator);
        jdbc.update("UPDATE roles SET role_code='OWNER',is_system=FALSE WHERE tenant_id=?",individualTenant);
        jdbc.update("INSERT INTO role_permissions(tenant_id,role_id,permission_id,is_explicit,created_at,updated_at) SELECT r.tenant_id,r.role_id,p.permission_id,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP FROM roles r CROSS JOIN permissions p WHERE r.tenant_id=? AND p.permission_code='USER_CREATE'",individualTenant);
        var actor=issue(operator);
        mvc.perform(get("/api/user/users/capabilities").header("Authorization",bearer(actor))).andExpect(status().isOk()).andExpect(jsonPath("$.canCreateUsers").value(true));
        var result=mvc.perform(mutate(post("/api/user/users"),json.writeValueAsString(Map.of("email","individual-member@example.com","displayName","Individual member","tenantId",individualTenant))).header("Authorization",bearer(actor))).andExpect(status().isCreated()).andReturn();
        long user=json.readTree(result.getResponse().getContentAsString()).path("userId").asLong();
        assertThat(jdbc.queryForObject("SELECT tenant_id FROM tenant_memberships WHERE user_id=?",Long.class,user)).isEqualTo(individualTenant);
        assertThat(jdbc.queryForObject("SELECT tenant_type FROM tenants WHERE tenant_id=?",String.class,individualTenant)).isEqualTo("INDIVIDUAL");
        verify(mail).send(eq("individual-member@example.com"),eq(PasswordService.SETUP),anyString(),isNull(),any());
    }
    @Test void selectedTenantOptionsAndCreationUseOnlyItsActiveAssignableRoles()throws Exception{
        long selectedTenant=jdbc.queryForObject("SELECT tenant_id FROM tenant_memberships WHERE user_id=?",Long.class,foreign);
        long selectedRole=role(selectedTenant,"Selected reviewer","ACTIVE");
        role(tenant,"Other tenant reviewer","ACTIVE");role(selectedTenant,"Inactive reviewer","INACTIVE");
        var actor=issue(operator);
        var response=mvc.perform(get("/api/user/users/create-options").param("tenantId",Long.toString(selectedTenant)).header("Authorization",bearer(actor))).andExpect(status().isOk()).andReturn();
        var options=json.readTree(response.getResponse().getContentAsString()).path("roles");
        var names=new ArrayList<String>();for(var item:options)names.add(item.path("name").asText());
        assertThat(names).contains("Selected reviewer").doesNotContain("Other tenant reviewer","Inactive reviewer","SUPER_USER");
        var created=mvc.perform(mutate(post("/api/user/users"),json.writeValueAsString(Map.of("email","role-selected@example.com","displayName","Assigned member","tenantId",selectedTenant,"roleId",selectedRole))).header("Authorization",bearer(actor))).andExpect(status().isCreated()).andReturn();
        long user=json.readTree(created.getResponse().getContentAsString()).path("userId").asLong();
        assertThat(jdbc.queryForList("SELECT ur.role_id FROM user_roles ur JOIN tenant_memberships m ON m.membership_id=ur.membership_id AND m.tenant_id=ur.tenant_id WHERE m.user_id=? AND m.tenant_id=?",Long.class,user,selectedTenant)).containsExactly(selectedRole);
        verify(mail).send(eq("role-selected@example.com"),eq(PasswordService.SETUP),anyString(),isNull(),any());
    }
    @Test void delegatedCreatorUsesExistingRoleAssignmentCeilingWithoutReceivingSystemRoles()throws Exception{
        grant("USER_CREATE");grant("ROLE_VIEW");grant("ROLE_ASSIGN");
        long allowedRole=role(tenant,"Allowed reviewer","ACTIVE"),tooPowerful=role(tenant,"Restricted reviewer","ACTIVE");
        jdbc.update("INSERT INTO role_permissions(tenant_id,role_id,permission_id,is_explicit,created_at,updated_at) SELECT ?,?,permission_id,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP FROM permissions WHERE permission_code='USER_HARD_DELETE'",tenant,tooPowerful);
        var actor=issue(creator);
        var response=mvc.perform(get("/api/user/users/create-options").header("Authorization",bearer(actor))).andExpect(status().isOk()).andReturn();
        var options=json.readTree(response.getResponse().getContentAsString()).path("roles");
        var choices=new ArrayList<Long>();for(var item:options)choices.add(item.path("roleId").asLong());
        assertThat(choices).contains(allowedRole).doesNotContain(tooPowerful);
        long before=jdbc.queryForObject("SELECT COUNT(*) FROM users",Long.class);
        mvc.perform(mutate(post("/api/user/users"),json.writeValueAsString(Map.of("email","restricted-role@example.com","displayName","No escalation","roleId",tooPowerful))).header("Authorization",bearer(actor))).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users",Long.class)).isEqualTo(before);verifyNoInteractions(mail);
        var created=mvc.perform(mutate(post("/api/user/users"),json.writeValueAsString(Map.of("email","delegated-role@example.com","displayName","Allowed member","roleId",allowedRole))).header("Authorization",bearer(actor))).andExpect(status().isCreated()).andReturn();
        long user=json.readTree(created.getResponse().getContentAsString()).path("userId").asLong();
        assertThat(jdbc.queryForObject("SELECT role_id FROM user_roles WHERE membership_id IN(SELECT membership_id FROM tenant_memberships WHERE user_id=?)",Long.class,user)).isEqualTo(allowedRole);
    }
    @Test void forgedForeignInactiveAndReservedRolesCannotCreateAnAccountOrSendInvitation()throws Exception{
        grant("USER_CREATE");long allowedRole=role(tenant,"Ordinary reviewer","ACTIVE");var creatorSession=issue(creator);
        mvc.perform(get("/api/user/users/create-options").header("Authorization",bearer(creatorSession))).andExpect(status().isOk()).andExpect(jsonPath("$.roles.length()").value(0));
        long before=jdbc.queryForObject("SELECT COUNT(*) FROM users",Long.class);
        mvc.perform(mutate(post("/api/user/users"),json.writeValueAsString(Map.of("email","no-role-right@example.com","displayName","No right","roleId",allowedRole))).header("Authorization",bearer(creatorSession))).andExpect(status().isForbidden());
        long foreignTenant=jdbc.queryForObject("SELECT tenant_id FROM tenant_memberships WHERE user_id=?",Long.class,foreign);
        long foreignRole=role(foreignTenant,"Foreign reviewer","ACTIVE"),inactive=role(tenant,"Inactive reviewer","INACTIVE");
        long reserved=jdbc.queryForObject("SELECT role_id FROM roles WHERE role_code='SUPER_USER' AND is_system=TRUE",Long.class);
        var highest=issue(operator);
        for(long roleId:List.of(foreignRole,inactive,reserved)){
            mvc.perform(mutate(post("/api/user/users"),json.writeValueAsString(Map.of("email","forged-role-"+roleId+"@example.com","displayName","Forged role","tenantId",tenant,"roleId",roleId))).header("Authorization",bearer(highest))).andExpect(status().is4xxClientError());
        }
        long highestTenant=jdbc.queryForObject("SELECT tenant_id FROM tenant_memberships WHERE user_id=?",Long.class,operator);
        mvc.perform(mutate(post("/api/user/users"),json.writeValueAsString(Map.of("email","reserved-role@example.com","displayName","No reserved role","tenantId",highestTenant,"roleId",reserved))).header("Authorization",bearer(highest))).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users",Long.class)).isEqualTo(before);verifyNoInteractions(mail);
    }
    @Test void ownDetailsAndPermissionsAreAuthenticatedSelfOnlyWithActualRouteMetadata()throws Exception{
        mvc.perform(get("/api/user/profile/details")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/user/profile/permissions")).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE permissions SET description='Actual fixture permission description' WHERE permission_code='BOOKS.VIEW'");var actor=issue(creator);
        mvc.perform(get("/api/user/profile/details").param("userId",Long.toString(operator)).header("Authorization",bearer(actor))).andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("creator@example.com")).andExpect(jsonPath("$.tenantId").value(tenant)).andExpect(jsonPath("$.tenantName").value("Create Company"));
        var response=mvc.perform(get("/api/user/profile/permissions").param("tenantId","999999").header("Authorization",bearer(actor))).andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("creator@example.com")).andReturn();
        var nodes=json.readTree(response.getResponse().getContentAsString()).path("permissions");boolean found=false;
        for(var node:nodes)if("BOOKS.VIEW".equals(node.path("code").asText())){
            found=true;assertThat(node.path("description").asText()).isEqualTo("Actual fixture permission description");assertThat(node.path("component").asText()).isEqualTo("BOOKS");
            assertThat(node.path("sourceRoles").size()).isGreaterThan(0);assertThat(node.path("routes").size()).isGreaterThan(0);
            assertThat(node.path("routes").get(0).path("service").asText()).isEqualTo("BOOK_SEARCH");
            assertThat(node.path("routes").get(0).path("method").asText()).isEqualTo("GET");assertThat(node.path("routes").get(0).path("operations").get(0).asText()).isEqualTo("Read");
        }
        assertThat(found).isTrue();assertThat(response.getResponse().getContentAsString()).doesNotContain("password_hash","accessToken","refreshToken","immutable-fixture");
        mvc.perform(get("/api/user/profile/permissions/"+operator).header("Authorization",bearer(actor))).andExpect(status().isForbidden());
    }
    @Test void ownPermissionPopupWorksForAuthenticatedUserWithoutManagementOrAccountGrants()throws Exception{
        jdbc.update("DELETE FROM role_permissions WHERE tenant_id=?",tenant);var actor=issue(creator);
        mvc.perform(get("/api/user/profile/details").header("Authorization",bearer(actor))).andExpect(status().isOk()).andExpect(jsonPath("$.username").value("creator@example.com"));
        mvc.perform(get("/api/user/profile/permissions").header("Authorization",bearer(actor))).andExpect(status().isOk()).andExpect(jsonPath("$.permissions.length()").value(0));
        mvc.perform(get("/api/user/users").header("Authorization",bearer(actor))).andExpect(status().isForbidden());
    }
    String confirm(MvcResult preview)throws Exception{var node=json.readTree(preview.getResponse().getContentAsString());return json.writeValueAsString(Map.of("version",node.path("version").asText(),"scope",node.path("scope").asText(),"affectedUsers",node.path("affectedUsers").asInt(),"confirmation",node.path("confirmation").asText()));}
    void grant(String code){jdbc.update("INSERT INTO role_permissions(tenant_id,role_id,permission_id,is_explicit,created_at,updated_at) SELECT r.tenant_id,r.role_id,p.permission_id,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP FROM roles r CROSS JOIN permissions p WHERE r.tenant_id=? AND p.permission_code=?",tenant,code);}
    long role(long tenantId,String name,String status){String code="ROLE_"+UUID.randomUUID().toString().replace("-","");jdbc.update("INSERT INTO roles(tenant_id,role_code,display_name,is_system,status,authz_version,created_at,updated_at) VALUES(?,?,?,FALSE,?,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",tenantId,code,name,status);return jdbc.queryForObject("SELECT role_id FROM roles WHERE tenant_id=? AND role_code=?",Long.class,tenantId,code);}
    SessionService.IssuedSession issue(long user){var row=jdbc.queryForMap("SELECT u.security_version,m.tenant_id,m.membership_id FROM users u JOIN tenant_memberships m ON m.user_id=u.user_id WHERE u.user_id=?",user);return sessions.issue(new AuthenticatedIdentity(user,((Number)row.get("tenant_id")).longValue(),((Number)row.get("membership_id")).longValue(),"PASSWORD",null,null,((Number)row.get("security_version")).longValue()));}
    String bearer(SessionService.IssuedSession session){return "Bearer "+session.response().accessToken();}
    MockHttpServletRequestBuilder mutate(MockHttpServletRequestBuilder request,String body){return request.cookie(csrfCookie).header("X-XSRF-TOKEN",csrf).contentType("application/json").content(body);}
    org.springframework.security.oauth2.core.oidc.user.OidcUser oidc(String email,String subject){
        var now=context.getBean(java.time.Clock.class).instant();var token=new org.springframework.security.oauth2.core.oidc.OidcIdToken("fixture",now,now.plusSeconds(300),Map.of("iss","https://id.fixture.example","sub",subject,"email",email,"aud",List.of("fixture-client")));
        return new org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser(List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("OIDC_USER")),token);
    }
}
