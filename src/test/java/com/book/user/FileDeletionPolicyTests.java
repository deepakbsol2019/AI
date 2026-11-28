package com.book.user;

import com.book.upload.service.FileDeletionPolicy;
import com.book.user.security.UserPrincipal;
import com.book.user.service.AuthorizationService;
import com.book.user.service.TenantProvisioningService;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FileDeletionPolicyTests {
    @Test void actualReservedRolesDefineGlobalTenantAndUploaderScopes() {
        var jdbc=AuthTestDatabase.create();
        var account=new TenantProvisioningService(jdbc,Clock.systemUTC(),AuthTestDatabase.storage())
                .create("INDIVIDUAL",null,"delete-role@example.com","Delete Role",null,null,true);
        var actor=new UserPrincipal(account.userId(),account.tenantId(),account.membershipId(),1,1,1,1,"test");
        var auth=new AuthorizationService(jdbc);var policy=new FileDeletionPolicy(jdbc,auth);
        var own=policy.scope(actor);
        assertThat(own.allows(account.tenantId(),account.userId())).isTrue();
        assertThat(own.allows(account.tenantId(),999)).isFalse();assertThat(own.allows(999,account.userId())).isFalse();
        jdbc.update("UPDATE roles SET role_code='ADMIN',is_system=FALSE WHERE tenant_id=?",account.tenantId());
        assertThat(policy.scope(actor).allows(account.tenantId(),999)).isFalse();
        jdbc.update("UPDATE roles SET is_system=TRUE WHERE tenant_id=?",account.tenantId());
        var admin=policy.scope(actor);assertThat(admin.admin()).isTrue();assertThat(admin.global()).isFalse();
        assertThat(admin.allows(account.tenantId(),999)).isTrue();assertThat(admin.allows(999,999)).isFalse();
        jdbc.update("DELETE FROM role_permissions WHERE tenant_id=?",account.tenantId());
        auth.authorize(actor,"DELETE","/api/books/5");
        jdbc.update("UPDATE roles SET role_code='SUPER_USER' WHERE tenant_id=?",account.tenantId());
        assertThat(policy.scope(actor).allows(999,999)).isTrue();
        jdbc.update("UPDATE roles SET status='SUSPENDED' WHERE tenant_id=?",account.tenantId());
        assertThat(policy.scope(actor).permitted()).isFalse();
    }
}
