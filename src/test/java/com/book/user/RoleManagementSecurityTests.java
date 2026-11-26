package com.book.user;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import com.book.user.security.UserPrincipal;
import com.book.user.service.*;
import com.book.user.service.RoleManagementService.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;

class RoleManagementSecurityTests {
    JdbcTemplate jdbc;AuthorizationService auth;RoleManagementService roles;TransactionTemplate tx;
    TenantProvisioningService.ProvisionedAccount owner,other;Clock clock=Clock.systemUTC();
    @BeforeEach void setup() {
        jdbc=AuthTestDatabase.create();auth=new AuthorizationService(jdbc);
        tx=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        var tenants=new TenantProvisioningService(jdbc,clock,AuthTestDatabase.storage());
        owner=tx.execute(s->tenants.create("ORGANIZATION","PASSWORD","owner@example.com","Owner","owner-company","Owner Company",true));
        other=tx.execute(s->tenants.create("ORGANIZATION","PASSWORD","other@example.com","Other","other-company","Other Company",true));
        roles=new RoleManagementService(jdbc,auth,clock);
    }
    @Test void registrationCannotMintAdminAndDoesNotImplicitlyGrantManagement() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM roles WHERE role_code='ADMIN'",Long.class)).isZero();
        assertThatThrownBy(()->run(()->roles.create(principal(owner),write("Reader",List.of(id("BOOKS.VIEW")))))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");
    }
    @Test void createUserCapabilityDoesNotGrantRoleCreationOrForeignRoleAccess() {
        grant(owner,"USER_CREATE");
        assertThatThrownBy(()->run(()->roles.create(principal(owner),write("Unapproved role",List.of())))).hasMessageContaining("403");
        assertThatThrownBy(()->run(()->roles.list(principal(owner),other.tenantId()))).hasMessageContaining("404");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM roles WHERE display_name='Unapproved role'",Long.class)).isZero();
    }
    @Test void currentIdentitySubsetAllowsEqualAndSmallerButNeverOneUnavailableIdentity() {
        grant(owner,"ROLE_VIEW","ROLE_CREATE","ROLE_EDIT");
        var catalog=run(()->roles.catalog(principal(owner),null));
        List<Long> equal=catalog.permissions().stream().map(Permission::permissionId).toList();
        var full=run(()->roles.create(principal(owner),write("Equal",equal)));
        assertThat(full.permissionIds()).containsExactlyInAnyOrderElementsOf(equal);
        var small=run(()->roles.create(principal(owner),write("Small",List.of(id("BOOKS.VIEW")))));
        assertThat(small.permissionIds()).containsExactly(id("BOOKS.VIEW"));
        assertThatThrownBy(()->run(()->roles.create(principal(owner),write("Forbidden",List.of(id("ROLE_VIEW"),id("ROLE_ASSIGN")))))).hasMessageContaining("403");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM roles WHERE display_name='Forbidden'",Long.class)).isZero();
    }
    @Test void createDoesNotImplyEditAndCustomRoleManagersHaveTheSameRules() {
        grant(owner,"ROLE_VIEW","ROLE_CREATE");
        jdbc.update("UPDATE roles SET role_code='CUSTOM_MANAGER',is_system=FALSE WHERE tenant_id=?",owner.tenantId());
        var created=run(()->roles.create(principal(owner),write("Created",List.of(id("BOOKS.VIEW")))));
        assertThatThrownBy(()->run(()->roles.update(principal(owner),created.roleId(),edit(created,List.of(id("BOOKS.VIEW")))))).hasMessageContaining("403");
        grant(owner,"ROLE_EDIT");
        var saved=run(()->roles.update(principal(owner),created.roleId(),edit(created,List.of())));
        assertThat(saved.permissionIds()).isEmpty();
    }
    @Test void protectedAdminSystemIdentitySurvivesRenameAndCannotBeEditedAssignedOrForged() {
        grant(owner,"ROLE_VIEW","ROLE_CREATE","ROLE_EDIT","ROLE_ASSIGN");
        long admin=systemAdmin(owner.tenantId());
        jdbc.update("UPDATE roles SET display_name='Trusted protected role' WHERE role_id=?",admin);
        var current=run(()->roles.get(principal(owner),null,admin));assertThat(current.editable()).isFalse();
        assertThatThrownBy(()->run(()->roles.update(principal(owner),admin,edit(current,List.of())))).hasMessageContaining("403");
        assertThatThrownBy(()->run(()->{roles.assign(principal(owner),null,admin,owner.membershipId(),false);return null;})).hasMessageContaining("403");
        assertThatThrownBy(()->run(()->{roles.assign(principal(owner),null,admin,owner.membershipId(),true);return null;})).hasMessageContaining("403");
        jdbc.update("INSERT INTO roles(tenant_id,role_code,display_name,is_system,status,authz_version,created_at,updated_at) VALUES(?,'SUPER_USER','Reserved operator',TRUE,'ACTIVE',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",owner.tenantId());
        long operatorRole=jdbc.queryForObject("SELECT role_id FROM roles WHERE tenant_id=? AND role_code='SUPER_USER'",Long.class,owner.tenantId());
        var reserved=run(()->roles.get(principal(owner),null,operatorRole));
        assertThatThrownBy(()->run(()->roles.update(principal(owner),operatorRole,edit(reserved,List.of())))).hasMessageContaining("403");
        assertThatThrownBy(()->run(()->{roles.assign(principal(owner),null,operatorRole,owner.membershipId(),false);return null;})).hasMessageContaining("403");
        for(String alias:List.of("admin","AdMiN","Administrator","super_user","SUPER USER","ＡＤＭＩＮ"))
            assertThatThrownBy(()->run(()->roles.create(principal(owner),write(alias,List.of())))).hasMessageContaining("403");
        assertThatThrownBy(()->run(()->roles.create(principal(owner),new Write(null,"Fake","",List.of(),null,"ADMIN")))).hasMessageContaining("403");
    }
    @Test void actorCannotTakeControlOfRoleAboveCurrentCeilingByReducingIt() {
        superUser(other);var high=run(()->roles.create(principal(other),new Write(owner.tenantId(),"High","",List.of(id("ROLE_VIEW"),id("ROLE_ASSIGN")),null,null)));
        grant(owner,"ROLE_VIEW","ROLE_EDIT");
        assertThatThrownBy(()->run(()->roles.update(principal(owner),high.roleId(),edit(high,List.of())))).hasMessageContaining("403");
    }
    @Test void crossOrganizationKnownIdsForgedTenantAndRoleReclassificationFail() {
        grant(owner,"ROLE_VIEW","ROLE_CREATE","ROLE_EDIT");grant(other,"ROLE_VIEW","ROLE_CREATE");
        var foreign=run(()->roles.create(principal(other),new Write(other.tenantId(),"Foreign","",List.of(),null,null)));
        assertThatThrownBy(()->run(()->roles.get(principal(owner),other.tenantId(),foreign.roleId()))).hasMessageContaining("404");
        assertThatThrownBy(()->run(()->roles.get(principal(owner),null,foreign.roleId()))).hasMessageContaining("404");
        assertThatThrownBy(()->run(()->roles.create(principal(owner),new Write(other.tenantId(),"Forged","",List.of(),null,null)))).hasMessageContaining("404");
        var ordinary=run(()->roles.create(principal(owner),write("Ordinary",List.of())));
        assertThatThrownBy(()->run(()->roles.update(principal(owner),ordinary.roleId(),new Write(null,"Changed","",List.of(),ordinary.version(),"ADMIN")))).hasMessageContaining("403");
    }
    @Test void superUserCanCreateEditAndDelegateAdminInExplicitOrganizationButCannotMintSuperIdentity() {
        superUser(owner);
        var admin=run(()->roles.create(principal(owner),new Write(other.tenantId(),"Administrator","",List.of(id("BOOKS.VIEW")),null,"ADMIN")));
        var updated=run(()->roles.update(principal(owner),admin.roleId(),new Write(other.tenantId(),"Protected admin","",List.of(id("ROLE_VIEW"),id("ROLE_CREATE"),id("ROLE_EDIT")),admin.version(),null)));
        run(()->{roles.assign(principal(owner),other.tenantId(),admin.roleId(),other.membershipId(),false);return null;});
        var delegated=run(()->roles.catalog(principal(other),null));assertThat(delegated.canCreate()).isTrue();assertThat(delegated.canEdit()).isTrue();
        assertThat(updated.systemRole()).isEqualTo("ADMIN");
        assertThatThrownBy(()->run(()->roles.update(principal(other),updated.roleId(),edit(updated,List.of())))).hasMessageContaining("403");
        assertThatThrownBy(()->run(()->roles.create(principal(other),new Write(null,"Super","",List.of(),null,"SUPER_USER")))).hasMessageContaining("403");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE event_code IN ('ROLE_CREATE','ROLE_EDIT','ROLE_ASSIGN') AND tenant_id=? AND actor_user_id=?",Long.class,other.tenantId(),owner.userId())).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT details FROM audit_events WHERE event_code='ROLE_EDIT'",String.class)).contains("oldIds","newIds");
    }
    @Test void stalePermissionSnapshotAndNonDelegableCapabilitiesAreRejected() {
        grant(owner,"ROLE_VIEW","ROLE_CREATE","ROLE_EDIT");UserPrincipal stale=principal(owner);
        jdbc.update("DELETE FROM role_permissions WHERE role_id IN(SELECT role_id FROM roles WHERE tenant_id=?) AND permission_id=?",owner.tenantId(),id("BOOKS.UPLOAD"));
        assertThatThrownBy(()->run(()->roles.create(stale,write("Stale",List.of())))).hasMessageContaining("403");
        jdbc.update("UPDATE permissions SET is_delegable=FALSE WHERE permission_code='BOOKS.DOWNLOAD'");
        assertThatThrownBy(()->run(()->roles.create(principal(owner),write("Protected capability",List.of(id("BOOKS.VIEW"),id("BOOKS.DOWNLOAD")))))).hasMessageContaining("403");
    }
    @Test void ordinaryRoleAssignmentsCannotChangeProtectedAdminOrSuperUserMembers() {
        grant(owner,"ROLE_VIEW","ROLE_CREATE","ROLE_EDIT","ROLE_ASSIGN");
        var ordinary=run(()->roles.create(principal(owner),write("Assignable ordinary",List.of())));
        long admin=systemAdmin(owner.tenantId());
        jdbc.update("INSERT INTO user_roles(tenant_id,membership_id,role_id,granted_at) VALUES(?,?,?,CURRENT_TIMESTAMP)",owner.tenantId(),owner.membershipId(),admin);
        assertThatThrownBy(()->run(()->{roles.assign(principal(owner),null,ordinary.roleId(),owner.membershipId(),false);return null;})).hasMessageContaining("403");
        jdbc.update("UPDATE roles SET role_code='SUPER_USER',is_system=TRUE WHERE tenant_id=? AND role_code='OWNER'",owner.tenantId());
        assertThatThrownBy(()->run(()->{roles.assign(principal(owner),null,ordinary.roleId(),owner.membershipId(),false);return null;})).hasMessageContaining("403");
    }
    @Test void deletedMembersCannotBeAssignedRolesThroughTheSeparateRoleApi() {
        superUser(owner);
        long role=jdbc.queryForObject("SELECT MIN(role_id) FROM roles WHERE tenant_id=?",Long.class,other.tenantId());
        jdbc.update("UPDATE users SET status='INACTIVE',deleted_at=CURRENT_TIMESTAMP WHERE user_id=?",other.userId());
        assertThatThrownBy(()->run(()->{roles.assign(principal(owner),other.tenantId(),role,other.membershipId(),false);return null;})).hasMessageContaining("404");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_roles WHERE membership_id=?",Long.class,other.membershipId())).isEqualTo(1);
    }
    @Test void copiedPermissionSelectionCreatesASeparateRoleAndLeavesSourceUnchanged() {
        grant(owner,"ROLE_VIEW","ROLE_CREATE","ROLE_EDIT");
        var source=run(()->roles.create(principal(owner),write("Tester",List.of(id("BOOKS.VIEW")))));
        var copied=run(()->roles.create(principal(owner),write("Developer",List.of(id("BOOKS.VIEW"),id("BOOKS.EDIT")))));
        assertThat(copied.roleId()).isNotEqualTo(source.roleId());
        assertThat(copied.permissionIds()).containsExactlyInAnyOrder(id("BOOKS.VIEW"),id("BOOKS.EDIT"));
        var retained=run(()->roles.get(principal(owner),null,source.roleId()));
        assertThat(retained.name()).isEqualTo("Tester");assertThat(retained.version()).isEqualTo(source.version());
        assertThat(retained.permissionIds()).containsExactly(id("BOOKS.VIEW"));
        assertThatThrownBy(()->run(()->roles.get(principal(other),owner.tenantId(),source.roleId()))).hasMessageContaining("404");
    }
    @Test void concurrentVersionedUpdatesCannotBothSucceed() throws Exception {
        superUser(owner);var role=run(()->roles.create(principal(owner),write("Concurrent",List.of())));
        var input=edit(role,List.of(id("BOOKS.VIEW")));var actor=principal(owner);CountDownLatch start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Boolean> attempt=()->{start.await();try{run(()->roles.update(actor,role.roleId(),input));return true;}catch(ResponseStatusException failure){return false;}};
            var first=pool.submit(attempt);var second=pool.submit(attempt);start.countDown();
            assertThat(List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(jdbc.queryForObject("SELECT authz_version FROM roles WHERE role_id=?",Long.class,role.roleId())).isEqualTo(2);
    }
    private Write write(String name,List<Long> values){return new Write(null,name,"",values,null,null);}
    private Write edit(Role role,List<Long> values){return new Write(role.tenantId(),role.name(),"",values,role.version(),null);}
    private <T>T run(Supplier<T> action){return tx.execute(s->action.get());}
    private long id(String code){return jdbc.queryForObject("SELECT permission_id FROM permissions WHERE permission_code=?",Long.class,code);}
    private void grant(TenantProvisioningService.ProvisionedAccount actor,String... codes){
        long role=jdbc.queryForObject("SELECT MIN(role_id) FROM roles WHERE tenant_id=?",Long.class,actor.tenantId());
        for(String code:codes) jdbc.update("INSERT INTO role_permissions(tenant_id,role_id,permission_id,is_explicit,created_at,updated_at) VALUES(?,?,?,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",actor.tenantId(),role,id(code));
    }
    private void superUser(TenantProvisioningService.ProvisionedAccount actor){jdbc.update("UPDATE roles SET role_code='SUPER_USER',is_system=TRUE WHERE tenant_id=?",actor.tenantId());}
    private long systemAdmin(long tenant){
        jdbc.update("INSERT INTO roles(tenant_id,role_code,display_name,is_system,status,authz_version,created_at,updated_at) VALUES(?,'ADMIN','Admin',TRUE,'ACTIVE',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",tenant);
        return jdbc.queryForObject("SELECT role_id FROM roles WHERE tenant_id=? AND role_code='ADMIN'",Long.class,tenant);
    }
    private UserPrincipal principal(TenantProvisioningService.ProvisionedAccount actor){
        var user=jdbc.queryForMap("SELECT u.security_version,t.authz_version tv,m.authz_version mv FROM users u JOIN tenant_memberships m ON m.user_id=u.user_id JOIN tenants t ON t.tenant_id=m.tenant_id WHERE u.user_id=?",actor.userId());
        return new UserPrincipal(actor.userId(),actor.tenantId(),actor.membershipId(),1,((Number)user.get("security_version")).longValue(),((Number)user.get("tv")).longValue(),((Number)user.get("mv")).longValue(),auth.authorizationFingerprint(actor.tenantId(),actor.membershipId()));
    }
}
