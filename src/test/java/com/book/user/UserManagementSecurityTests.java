package com.book.user;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import com.book.storage.TenantStoragePaths;
import com.book.user.service.*;
import com.book.user.service.UserManagementService.*;
import com.book.user.security.UserPrincipal;
import com.book.user.config.AuthSettings;
import com.book.user.dto.AuthenticatedIdentity;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

class UserManagementSecurityTests {
    @TempDir Path root;AnnotationConfigApplicationContext context;JdbcTemplate jdbc;UserManagementService users;UserDeletionService deletion;
    TenantProvisioningService tenants;TenantStoragePaths storage;SessionService sessions;TransactionTemplate tx;long operator,admin,secondAdmin,employee,secondEmployee,foreign,individual;boolean failCleanup;
    @Configuration(proxyBeanMethods=false) @EnableTransactionManagement static class Config{}
    @BeforeEach void setup(){
        jdbc=AuthTestDatabase.create();jdbc.execute("CREATE TABLE pdf_file_author_details(aouthor_id BIGINT AUTO_INCREMENT PRIMARY KEY,author_name VARCHAR(200))");
        jdbc.execute("CREATE TABLE pdf_file_status(book_upload_id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_id BIGINT NOT NULL REFERENCES tenants(tenant_id),status VARCHAR(16),message VARCHAR(200))");
        jdbc.execute("CREATE TABLE pdf_file_details(book_id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_id BIGINT NOT NULL REFERENCES tenants(tenant_id),author_id BIGINT REFERENCES pdf_file_author_details(aouthor_id),book_upload_id BIGINT REFERENCES pdf_file_status(book_upload_id),book_name VARCHAR(255))");
        jdbc.execute("CREATE TABLE pdf_file_topic_details(topic_id BIGINT AUTO_INCREMENT PRIMARY KEY,book_id BIGINT NOT NULL REFERENCES pdf_file_details(book_id),pdf_path VARCHAR(2048))");
        storage=new TenantStoragePaths(root.toString());context=new AnnotationConfigApplicationContext();
        context.setEnvironment(new MockEnvironment().withProperty("app.user.jwt.secret",Base64.getEncoder().encodeToString(new byte[32])));
        context.register(Config.class,AuthorizationService.class,UserManagementService.class,UserDeletionService.class,TenantProvisioningService.class,AuthSettings.class,SessionService.class,AccessTokenService.class,SecurityAuditService.class,RoleManagementService.class);
        context.registerBean(JdbcTemplate.class,()->jdbc);context.registerBean(Clock.class,Clock::systemUTC);context.registerBean(TenantStoragePaths.class,()->storage);
        context.registerBean(PlatformTransactionManager.class,()->new DataSourceTransactionManager(jdbc.getDataSource()));
        context.registerBean(UserDeletionFiles.class,()->new UserDeletionFiles(storage){@Override public void delete(long tenant,Manifest manifest)throws java.io.IOException{if(failCleanup)throw new java.io.IOException("fixture failure");super.delete(tenant,manifest);}});
        context.refresh();users=context.getBean(UserManagementService.class);deletion=context.getBean(UserDeletionService.class);tenants=context.getBean(TenantProvisioningService.class);sessions=context.getBean(SessionService.class);tx=new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        operator=create("INDIVIDUAL","operator@example.com","Operator",null);jdbc.update("UPDATE roles SET role_code='SUPER_USER',is_system=TRUE WHERE tenant_id=?",tenant(operator));
        admin=create("ORGANIZATION","admin@example.com","Company admin","company-a");jdbc.update("UPDATE roles SET role_code='ADMIN',is_system=TRUE WHERE tenant_id=?",tenant(admin));
        long adminRole=jdbc.queryForObject("SELECT role_id FROM roles WHERE tenant_id=?",Long.class,tenant(admin));
        grant(adminRole,"USER_VIEW","USER_EDIT","USER_SOFT_DELETE","USER_HARD_DELETE");
        secondAdmin=member(tenant(admin),"other-admin@example.com","Other Admin",adminRole);
        jdbc.update("INSERT INTO roles(tenant_id,role_code,display_name,is_system,status,authz_version,created_at,updated_at) VALUES(?,'STAFF','Employee',FALSE,'ACTIVE',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",tenant(admin));
        long staff=jdbc.queryForObject("SELECT role_id FROM roles WHERE tenant_id=? AND role_code='STAFF'",Long.class,tenant(admin));grant(staff,"BOOKS.VIEW","ACCOUNT.VIEW");
        employee=member(tenant(admin),"employee@example.com","Employee",staff);secondEmployee=member(tenant(admin),"employee2@example.com","Second Employee",staff);
        foreign=create("ORGANIZATION","foreign@example.com","Other Company","company-b");individual=create("INDIVIDUAL","individual@example.com","Standalone",null);
    }
    @AfterEach void close(){context.close();}
    @Test void globalAndAdminListsApplyScopeSearchFiltersAndCountsBeforePagination(){
        assertThat(users.list(actor(operator),"","ALL",null,0,100).total()).isEqualTo(7);
        var first=users.list(actor(admin),"employee","ALL",null,0,1);assertThat(first.total()).isEqualTo(2);assertThat(first.users()).hasSize(1);
        var second=users.list(actor(admin),"employee","ALL",null,1,1);assertThat(second.users().getFirst().userId()).isNotEqualTo(first.users().getFirst().userId());
        jdbc.update("UPDATE users SET status='INACTIVE' WHERE user_id=?",employee);
        assertThat(users.list(actor(admin),"","INACTIVE",null,0,20).users()).extracting(User::userId).containsExactly(employee);
        assertThatThrownBy(()->users.list(actor(admin),"","ALL",tenant(foreign),0,20)).hasMessageContaining("404");
        assertThatThrownBy(()->users.details(actor(admin),foreign)).hasMessageContaining("404");
        assertThatThrownBy(()->users.details(actor(admin),secondAdmin)).hasMessageContaining("404");
        assertThatThrownBy(()->users.list(actor(employee),"","ALL",null,0,20)).hasMessageContaining("403");
    }
    @Test void detailsContainActualRolePermissionsNoSecretsAndVersionedProfileCannotReactivate(){
        var detail=users.details(actor(admin),employee);assertThat(detail.effectivePermissions()).extracting(Grant::code).contains("BOOKS.VIEW","ACCOUNT.VIEW");
        assertThat(detail.user().roles().getFirst().name()).isEqualTo("Employee");assertThat(detail.directGrantsSupported()).isFalse();
        String serialized=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(detail);
        assertThat(serialized).doesNotContain("password_hash","token_digest","accessToken","refreshToken","secret_ref");
        users.edit(actor(admin),employee,new Edit("Updated employee",detail.user().version()));
        assertThatThrownBy(()->users.edit(actor(admin),employee,new Edit("Stale",detail.user().version()))).hasMessageContaining("409");
        jdbc.update("UPDATE users SET status='INACTIVE' WHERE user_id=?",employee);var inactive=users.details(actor(operator),employee);users.edit(actor(operator),employee,new Edit("Still inactive",inactive.user().version()));
        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE user_id=?",String.class,employee)).isEqualTo("INACTIVE");
        assertThat(jdbc.queryForObject("SELECT primary_email FROM users WHERE user_id=?",String.class,employee)).isEqualTo("employee@example.com");
    }
    @Test void superSoftDeletingAdminMarksAllTenantUsersDeletedPreservesEverythingAndCannotActivateDeleted()throws Exception{
        Path file=content(tenant(admin),"shared.pdf");var oldAdmin=session(admin);var oldEmployee=session(employee);soft(operator,admin);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE status='INACTIVE'",Long.class)).isEqualTo(4);
        assertThat(Files.readString(file)).isEqualTo("fixture content");assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pdf_file_details",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM tenants WHERE tenant_id=?",String.class,tenant(admin))).isEqualTo("ACTIVE");
        assertThatThrownBy(()->sessions.validate(oldAdmin.response().accessToken()==null?null:context.getBean(AccessTokenService.class).verify(oldAdmin.response().accessToken()))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->sessions.refresh(oldEmployee.refreshToken())).isInstanceOf(RuntimeException.class);
        var detail=users.details(actor(operator),admin);assertThatThrownBy(()->users.activate(actor(operator),admin,new Activate(detail.user().version(),false))).hasMessageContaining("soft-deleted");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE status='INACTIVE' AND deleted_at IS NOT NULL",Long.class)).isEqualTo(4);
        assertThatThrownBy(()->sessions.refresh(oldAdmin.refreshToken())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->session(admin)).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE user_id=?",String.class,foreign)).isEqualTo("ACTIVE");
    }
    @Test void ordinarySoftDeletionAndStandaloneActivationNeverTouchOtherAccountsOrFolders()throws Exception{
        Path path=content(tenant(individual),"individual.pdf");soft(operator,individual);assertThat(Files.exists(path)).isTrue();
        var detail=users.details(actor(operator),individual);assertThat(detail.user().deleted()).isTrue();
        assertThatThrownBy(()->users.activate(actor(operator),individual,new Activate(detail.user().version(),false))).hasMessageContaining("soft-deleted");
        jdbc.update("UPDATE users SET status='INACTIVE',deleted_at=NULL WHERE user_id=?",foreign);
        users.activate(actor(operator),foreign,new Activate(users.details(actor(operator),foreign).user().version(),false));
        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE user_id=?",String.class,foreign)).isEqualTo("ACTIVE");
        soft(admin,employee);assertThat(jdbc.queryForObject("SELECT status FROM users WHERE user_id=?",String.class,secondEmployee)).isEqualTo("ACTIVE");
        assertThatThrownBy(()->users.activate(actor(admin),employee,new Activate(users.details(actor(admin),employee).user().version(),false))).hasMessageContaining("403");
    }
    @Test void adminHardDeletionPhysicallyRemovesOnlyEmployeeAndKeepsAllContentPaths()throws Exception{
        Path file=content(tenant(admin),"retained.pdf");long tenant=tenant(admin);long book=jdbc.queryForObject("SELECT book_id FROM pdf_file_details",Long.class);
        var job=hard(admin,employee);deletion.process(job.jobId());assertThat(deletion.get(actor(admin),job.jobId()).status()).isEqualTo("COMPLETE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE user_id=?",Long.class,employee)).isZero();
        assertThat(jdbc.queryForObject("SELECT tenant_id FROM pdf_file_details WHERE book_id=?",Long.class,book)).isEqualTo(tenant);
        assertThat(jdbc.queryForObject("SELECT pdf_path FROM pdf_file_topic_details WHERE book_id=?",String.class,book)).isEqualTo(file.toString());
        assertThat(Files.readString(file)).isEqualTo("fixture content");assertThat(storage.tenantPath(tenant)).isDirectory();
    }
    @Test void superEmployeeDeletionKeepsSharedRootAndBlocksAmbiguousContent()throws Exception{
        long tenant=tenant(admin);var job=hard(operator,employee);deletion.process(job.jobId());assertThat(deletion.get(actor(operator),job.jobId()).status()).isEqualTo("COMPLETE");assertThat(storage.tenantPath(tenant)).isDirectory();
        Path file=content(tenant,"ambiguous.pdf");assertThatThrownBy(()->hard(operator,secondEmployee)).hasMessageContaining("ambiguous");
        assertThat(Files.readString(file)).isEqualTo("fixture content");assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE user_id=?",Long.class,secondEmployee)).isEqualTo(1);
    }
    @Test void superHardDeletingIndividualPurgesExclusiveTenantAndCannotActivateDeletedTarget()throws Exception{
        long personal=tenant(individual);Path owned=content(personal,"owned.pdf"),untouched=content(tenant(foreign),"foreign.pdf");
        var job=hard(operator,individual);deletion.process(job.jobId());assertThat(deletion.get(actor(operator),job.jobId()).status()).isEqualTo("COMPLETE");
        assertThat(storage.tenantPath(personal)).doesNotExist();assertThat(Files.exists(untouched)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tenants WHERE tenant_id=?",Long.class,personal)).isZero();
        assertThatThrownBy(()->users.activate(actor(operator),individual,new Activate("missing",false))).hasMessageContaining("404");
    }
    @Test void superHardDeletingAdminDeletesWholeCompanyEvenWithOtherAdminsAndRetainsAudits()throws Exception{
        long company=tenant(admin);content(company,"company.pdf");Path other=content(tenant(foreign),"other.pdf");
        var job=hard(operator,admin);deletion.process(job.jobId());assertThat(deletion.get(actor(operator),job.jobId()).status()).isEqualTo("COMPLETE");
        for(long user:List.of(admin,secondAdmin,employee,secondEmployee))assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE user_id=?",Long.class,user)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tenants WHERE tenant_id=?",Long.class,company)).isZero();assertThat(storage.tenantPath(company)).doesNotExist();assertThat(Files.readString(other)).isEqualTo("fixture content");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE event_code='USER_HARD_DELETE_REQUEST'",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT details FROM audit_events WHERE event_code='USER_HARD_DELETE_REQUEST'",String.class)).contains("retainedTenantId");
    }
    @Test void failedCleanupIsDurableRetryableDoesNotClaimSuccessAndCannotResurrectAccount()throws Exception{
        long tenant=tenant(individual);Path path=content(tenant,"retry.pdf");failCleanup=true;var job=hard(operator,individual);deletion.process(job.jobId());
        var failed=deletion.get(actor(operator),job.jobId());assertThat(failed.status()).isEqualTo("FAILED");assertThat(failed.databasePurged()).isTrue();assertThat(Files.exists(path)).isTrue();
        assertThatThrownBy(()->storage.provision(tenant)).hasMessageContaining("locked");
        failCleanup=false;deletion.retry(actor(operator),job.jobId());deletion.process(job.jobId());deletion.process(job.jobId());
        assertThat(deletion.get(actor(operator),job.jobId()).status()).isEqualTo("COMPLETE");assertThat(storage.tenantPath(tenant)).doesNotExist();
    }
    @Test void superRoleAssignmentIsTenantValidatedInvalidatesSessionsAndCannotMintHighest(){
        var before=session(employee);var detail=users.details(actor(operator),employee);var roles=users.assignable(actor(operator),employee);long adminRole=roles.stream().filter(r->r.code().equals("ADMIN")).findFirst().orElseThrow().roleId();
        users.assign(actor(operator),employee,new Assign(List.of(adminRole),detail.user().version()));
        assertThatThrownBy(()->sessions.refresh(before.refreshToken())).isInstanceOf(RuntimeException.class);
        assertThat(users.details(actor(operator),employee).user().roles()).extracting(Role::code).containsExactly("ADMIN");
        assertThatThrownBy(()->users.details(actor(admin),employee)).hasMessageContaining("404");
        long foreignRole=jdbc.queryForObject("SELECT MIN(role_id) FROM roles WHERE tenant_id=?",Long.class,tenant(foreign));
        assertThatThrownBy(()->users.assign(actor(operator),employee,new Assign(List.of(foreignRole),users.details(actor(operator),employee).user().version()))).hasMessageContaining("403");
        assertThatThrownBy(()->users.assignable(actor(admin),secondEmployee)).hasMessageContaining("403");
        assertThatThrownBy(()->hard(operator,operator)).hasMessageContaining("403");
    }
    @Test void confirmationScopeAndPendingJobsPreventReclassificationAndDoubleDeletion(){
        var preview=users.preview(actor(operator),employee,true);long adminRole=jdbc.queryForObject("SELECT role_id FROM roles WHERE tenant_id=? AND role_code='ADMIN'",Long.class,tenant(admin));
        users.assign(actor(operator),employee,new Assign(List.of(adminRole),users.details(actor(operator),employee).user().version()));
        assertThatThrownBy(()->deletion.request(actor(operator),employee,confirm(preview))).hasMessageContaining("409");
        var job=hard(operator,individual);assertThatThrownBy(()->hard(operator,individual)).hasMessageContaining("pending");
        assertThatThrownBy(()->users.edit(actor(operator),individual,new Edit("No", "stale"))).hasMessageContaining("pending");
    }
    @Test void allNewStandaloneRegistrationsProvisionOwnNumericDirectoryAndRetryIsIdempotent(){
        assertThat(storage.tenantPath(tenant(individual))).isDirectory();assertThat(storage.provision(tenant(individual))).isEqualTo(storage.tenantPath(tenant(individual)));
        assertThat(tenant(employee)).isEqualTo(tenant(admin));assertThat(storage.provision(tenant(employee))).isEqualTo(storage.tenantPath(tenant(admin)));
    }
    @Test void workerRevalidatesCurrentAuthorityAndSuperUserCanRecoverTheQueuedJob(){
        var job=hard(admin,employee);
        jdbc.update("DELETE FROM role_permissions WHERE role_id IN(SELECT role_id FROM roles WHERE tenant_id=?) AND permission_id IN(SELECT permission_id FROM permissions WHERE permission_code='USER_HARD_DELETE')",tenant(admin));
        deletion.process(job.jobId());assertThat(deletion.get(actor(operator),job.jobId()).status()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE user_id=?",Long.class,employee)).isEqualTo(1);
        deletion.retry(actor(operator),job.jobId());deletion.process(job.jobId());assertThat(deletion.get(actor(operator),job.jobId()).status()).isEqualTo("COMPLETE");
    }
    @Test void changedFilesDoNotGetDeletedAndAnExactManifestCanResumeAfterCorrection()throws Exception{
        Path path=content(tenant(individual),"changed.pdf");var job=hard(operator,individual);Files.writeString(path,"changed after confirmation");
        deletion.process(job.jobId());assertThat(deletion.get(actor(operator),job.jobId()).status()).isEqualTo("FAILED");assertThat(Files.readString(path)).isEqualTo("changed after confirmation");
        Files.writeString(path,"fixture content");deletion.retry(actor(operator),job.jobId());deletion.process(job.jobId());assertThat(deletion.get(actor(operator),job.jobId()).status()).isEqualTo("COMPLETE");
    }
    @Test void optInBackfillCreatesOnlyPersistedIndividualRootsAndNeverAdoptsSharedFiles()throws Exception{
        long personal=tenant(individual),company=tenant(admin);Files.delete(storage.tenantPath(personal));Files.delete(storage.tenantPath(company));Path legacy=Files.writeString(root.resolve("legacy-shared.pdf"),"preserve");
        var backfill=new com.book.user.config.IndividualStorageBackfill(jdbc,storage,new MockEnvironment());var result=backfill.backfill();
        assertThat(result.failedTenantIds()).isEmpty();assertThat(storage.tenantPath(personal)).isDirectory();assertThat(storage.tenantPath(personal).resolve("legacy-shared.pdf")).doesNotExist();
        assertThat(storage.tenantPath(company)).doesNotExist();assertThat(Files.readString(legacy)).isEqualTo("preserve");assertThat(backfill.backfill().failedTenantIds()).isEmpty();
    }
    long create(String type,String email,String name,String code){var result=tenants.create(type,"PASSWORD",email,name,code,type.equals("ORGANIZATION")?name:null,true);credential(result.userId());return result.userId();}
    long member(long tenant,String email,String name,long role){jdbc.update("INSERT INTO users(display_name,primary_email,email_verified_at,status,security_version,created_at,updated_at) VALUES(?,?,CURRENT_TIMESTAMP,'ACTIVE',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",name,email);long user=jdbc.queryForObject("SELECT user_id FROM users WHERE primary_email=?",Long.class,email);
        jdbc.update("INSERT INTO tenant_memberships(tenant_id,user_id,status,authz_version,joined_at,created_at,updated_at) VALUES(?,?,'ACTIVE',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",tenant,user);long member=jdbc.queryForObject("SELECT membership_id FROM tenant_memberships WHERE user_id=?",Long.class,user);
        jdbc.update("INSERT INTO user_roles(tenant_id,membership_id,role_id,granted_at) VALUES(?,?,?,CURRENT_TIMESTAMP)",tenant,member,role);credential(user);return user;}
    void credential(long user){jdbc.update("INSERT INTO local_credentials(user_id,password_hash,password_changed_at,failed_attempts,created_at,updated_at) VALUES(?,'fixture-hash',CURRENT_TIMESTAMP,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",user);}
    void grant(long role,String...codes){long tenant=jdbc.queryForObject("SELECT tenant_id FROM roles WHERE role_id=?",Long.class,role);for(String code:codes)jdbc.update("INSERT INTO role_permissions(tenant_id,role_id,permission_id,is_explicit,created_at,updated_at) SELECT ?,?,permission_id,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP FROM permissions WHERE permission_code=?",tenant,role,code);}
    long tenant(long user){return jdbc.queryForObject("SELECT tenant_id FROM tenant_memberships WHERE user_id=? AND status='ACTIVE'",Long.class,user);}
    UserPrincipal actor(long user){var row=jdbc.queryForMap("SELECT u.security_version,m.membership_id,m.tenant_id,m.authz_version mv,t.authz_version tv FROM users u JOIN tenant_memberships m ON m.user_id=u.user_id JOIN tenants t ON t.tenant_id=m.tenant_id WHERE u.user_id=? AND m.status='ACTIVE'",user);long t=((Number)row.get("tenant_id")).longValue(),m=((Number)row.get("membership_id")).longValue();return new UserPrincipal(user,t,m,1,((Number)row.get("security_version")).longValue(),((Number)row.get("tv")).longValue(),((Number)row.get("mv")).longValue(),context.getBean(AuthorizationService.class).authorizationFingerprint(t,m));}
    SessionService.IssuedSession session(long user){var a=actor(user);return sessions.issue(new AuthenticatedIdentity(user,a.tenantId(),a.membershipId(),"PASSWORD",null,null,a.securityVersion()));}
    Confirm confirm(Preview p){return new Confirm(p.version(),p.scope(),p.affectedUsers(),p.confirmation());}
    void soft(long actor,long target){users.softDelete(actor(actor),target,confirm(users.preview(actor(actor),target,false)));}
    UserDeletionService.Job hard(long actor,long target){return deletion.request(actor(actor),target,confirm(users.preview(actor(actor),target,true)));}
    Path content(long tenant,String filename)throws Exception{Path folder=Files.createDirectories(storage.tenantPath(tenant).resolve("fixture"));Path file=Files.writeString(folder.resolve(filename),"fixture content");jdbc.update("INSERT INTO pdf_file_author_details(author_name) VALUES('Author')");long author=jdbc.queryForObject("SELECT MAX(aouthor_id) FROM pdf_file_author_details",Long.class);jdbc.update("INSERT INTO pdf_file_status(tenant_id,status,message) VALUES(?,'Success','Fixture')",tenant);long receipt=jdbc.queryForObject("SELECT MAX(book_upload_id) FROM pdf_file_status",Long.class);jdbc.update("INSERT INTO pdf_file_details(tenant_id,author_id,book_upload_id,book_name) VALUES(?,?,?,'Fixture')",tenant,author,receipt);long book=jdbc.queryForObject("SELECT MAX(book_id) FROM pdf_file_details",Long.class);jdbc.update("INSERT INTO pdf_file_topic_details(book_id,pdf_path) VALUES(?,?)",book,file.toString());return file;}
}
