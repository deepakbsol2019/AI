package com.book.user.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import com.book.storage.TenantStoragePaths;
import com.book.user.provider.TrustedOidcProviders;
import com.book.user.security.UserPrincipal;
import com.book.user.util.AuthRows;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Tenant membership invitation is an explicit capability, never public registration or tenant selection. */
@Service
public class TenantUserCreationService {
    private final JdbcTemplate jdbc;private final AuthorizationService authorization;private final PasswordService passwords;
    private final TrustedOidcProviders providers;private final TenantStoragePaths storage;private final Clock clock;
    public TenantUserCreationService(JdbcTemplate jdbc,AuthorizationService authorization,PasswordService passwords,
            TrustedOidcProviders providers,TenantStoragePaths storage,Clock clock){
        this.jdbc=jdbc;this.authorization=authorization;this.passwords=passwords;this.providers=providers;this.storage=storage;this.clock=clock;
    }
    public record Create(String email,String displayName,Long tenantId){@JsonAnySetter public void unsupported(String key,Object value){throw invalid("Only email, displayName and an authorized tenantId are accepted. Roles and authentication method are server-controlled.");}}
    public record Options(long tenantId,String tenantName,String tenantType,String loginPolicy){}
    public record Capabilities(boolean canViewUsers,boolean canCreateUsers,boolean canManageRoles,long tenantId,String tenantName,String tenantType,boolean superUser){}
    public record Created(long userId,String username,String displayName,String tenantName,String tenantType,String status,String message){}

    @Transactional(readOnly=true)
    public Capabilities capabilities(UserPrincipal actor){
        var row=current(actor);boolean highest=authorization.isSuperUser(actor);
        boolean admin=jdbc.queryForObject("SELECT COUNT(*) FROM user_roles ur JOIN roles r ON r.role_id=ur.role_id AND r.tenant_id=ur.tenant_id WHERE ur.tenant_id=? AND ur.membership_id=? AND r.is_system=TRUE AND r.role_code='ADMIN' AND r.status='ACTIVE'",Long.class,actor.tenantId(),actor.membershipId())>0;
        return new Capabilities(has(actor,"USER_VIEW") && (highest || admin && "ORGANIZATION".equals(row.get("tenant_type"))),
                has(actor,"USER_CREATE"),has(actor,"ROLE_VIEW"),actor.tenantId(),(String)row.get("tenant_name"),(String)row.get("tenant_type"),highest);
    }
    @Transactional(readOnly=true)
    public Options options(UserPrincipal actor){return options(actor,null);}
    @Transactional(readOnly=true)
    public Options options(UserPrincipal actor,Long selectedTenant){requireCreate(actor);long target=targetTenant(actor,selectedTenant);return options(target,tenant(target));}
    @Transactional(readOnly=true)
    public List<Options> tenants(UserPrincipal actor){
        requireCreate(actor);boolean highest=authorization.isSuperUser(actor);
        return jdbc.queryForList("SELECT tenant_id,display_name tenant_name,tenant_type,login_policy,status tenant_status FROM tenants WHERE status='ACTIVE' AND (?=TRUE OR tenant_id=?) AND NOT EXISTS(SELECT 1 FROM user_deletion_jobs j WHERE j.tenant_id=tenants.tenant_id AND j.status<>'COMPLETE') ORDER BY display_name,tenant_id",highest,actor.tenantId())
                .stream().map(row->options(AuthRows.number(row,"tenant_id"),row)).toList();
    }

    @Transactional
    public Created create(UserPrincipal actor,Create input){
        requireCreate(actor);
        if(input==null)throw invalid("Provide user details.");
        long target=targetTenant(actor,input.tenantId());
        jdbc.queryForList("SELECT tenant_id FROM tenants WHERE tenant_id IN(?,?) ORDER BY tenant_id FOR UPDATE",actor.tenantId(),target);
        jdbc.queryForList("SELECT user_id FROM users WHERE user_id=? FOR UPDATE",actor.userId());
        requireCreate(actor);targetTenant(actor,target);var tenant=tenant(target);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM user_deletion_jobs WHERE tenant_id=? AND status<>'COMPLETE'",Long.class,target)>0)throw conflict("A deletion job is pending for this tenant.");
        String email=PasswordService.normalizeEmail(input.email());
        String name=input.displayName()==null?null:input.displayName().strip();
        if(name==null || name.isBlank() || name.length()>150)throw invalid("Name must contain 1 to 150 characters.");
        Long provider=null;
        if("SSO".equals(tenant.get("login_policy"))){
            var codes=jdbc.queryForList("SELECT provider_code FROM identity_providers WHERE tenant_id=? AND provider_type='ORG_OIDC' AND status='ACTIVE'",String.class,target);
            if(codes.size()!=1)throw conflict("This organisation's sign-in provider must be configured before inviting users.");
            var trusted=providers.trusted(codes.getFirst());
            if(!Objects.equals(trusted.tenantId(),target))throw denied();
            provider=trusted.id();
        }
        // Reuse the existing root; an employee never receives another tenant or directory.
        storage.provision(target);
        Timestamp now=Timestamp.from(clock.instant());var holder=new GeneratedKeyHolder();
        try {
            jdbc.update(connection->{var statement=connection.prepareStatement("INSERT INTO users(display_name,primary_email,status,security_version,created_at,updated_at) VALUES(?,?,'PENDING',1,?,?)",new String[]{"user_id"});
                statement.setString(1,name);statement.setString(2,email);statement.setTimestamp(3,now);statement.setTimestamp(4,now);return statement;},holder);
            long user=holder.getKey().longValue();
            jdbc.update("INSERT INTO tenant_memberships(tenant_id,user_id,status,authz_version,joined_at,created_at,updated_at) VALUES(?,?,'ACTIVE',1,?,?,?)",target,user,now,now,now);
            // No role is implicitly copied from the creator. Separate role assignment retains its existing authority.
            passwords.inviteMember(user,provider);
            jdbc.update("INSERT INTO audit_events(tenant_id,actor_user_id,event_code,target_type,target_id,outcome,details,occurred_at) VALUES(?,?,'USER_CREATE','USER',?,'SUCCESS',?,?)",target,actor.userId(),Long.toString(user),"{\"invitationSent\":true,\"rolesAssigned\":false}",now);
            return new Created(user,email,name,(String)tenant.get("tenant_name"),(String)tenant.get("tenant_type"),"PENDING","User created. "+("PASSWORD".equals(tenant.get("login_policy"))?"A password setup link":"An SSO enrollment invitation")+" has been sent; role assignment is separate.");
        }catch(DataIntegrityViolationException duplicate){throw conflict("A user with this username already exists. Existing accounts cannot be joined or moved through Create User.");}
    }
    private Options options(long tenant,Map<String,Object> row){return new Options(tenant,(String)row.get("tenant_name"),(String)row.get("tenant_type"),(String)row.get("login_policy"));}
    private Map<String,Object> requireCreate(UserPrincipal actor){var row=current(actor);if(!has(actor,"USER_CREATE"))throw denied();return row;}
    private long targetTenant(UserPrincipal actor,Long selected){long target=selected==null?actor.tenantId():selected;
        if(target<=0 || target!=actor.tenantId()&&!authorization.isSuperUser(actor))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Tenant not found in your authorized scope.");return target;}
    private Map<String,Object> tenant(long id){var rows=jdbc.queryForList("SELECT tenant_id,display_name tenant_name,tenant_type,login_policy,status tenant_status FROM tenants WHERE tenant_id=?",id);
        if(rows.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Tenant not found in your authorized scope.");var row=rows.getFirst();
        if(!"ACTIVE".equals(row.get("tenant_status")))throw conflict("Select an active tenant before adding a user.");return row;}
    private boolean has(UserPrincipal actor,String code){var ids=jdbc.queryForList("SELECT permission_id FROM permissions WHERE permission_code=? AND is_active=TRUE",Long.class,code);return !ids.isEmpty() && authorization.effectivePermissions(actor).contains(ids.getFirst());}
    private Map<String,Object> current(UserPrincipal actor){
        if(actor==null)throw denied();
        var rows=jdbc.queryForList("SELECT u.status,u.deleted_at,u.email_verified_at,u.security_version,t.status tenant_status,t.tenant_type,t.display_name tenant_name,t.login_policy,t.authz_version tv,m.status membership_status,m.authz_version mv FROM users u JOIN tenant_memberships m ON m.user_id=u.user_id JOIN tenants t ON t.tenant_id=m.tenant_id WHERE u.user_id=? AND m.membership_id=? AND t.tenant_id=?",actor.userId(),actor.membershipId(),actor.tenantId());
        if(rows.size()!=1)throw denied();var row=rows.getFirst();
        if(!"ACTIVE".equals(row.get("status")) || row.get("deleted_at")!=null || row.get("email_verified_at")==null || !"ACTIVE".equals(row.get("tenant_status")) || !"ACTIVE".equals(row.get("membership_status"))
                || AuthRows.number(row,"security_version")!=actor.securityVersion() || AuthRows.number(row,"tv")!=actor.tenantAuthzVersion() || AuthRows.number(row,"mv")!=actor.membershipAuthzVersion()
                || !authorization.authorizationFingerprint(actor.tenantId(),actor.membershipId()).equals(actor.authorizationFingerprint()))throw denied();
        return row;
    }
    private static ResponseStatusException invalid(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
    private static ResponseStatusException conflict(String message){return new ResponseStatusException(HttpStatus.CONFLICT,message);}
    private static ResponseStatusException denied(){return new ResponseStatusException(HttpStatus.FORBIDDEN,"You do not have the Create User capability in this tenant.");}
}
