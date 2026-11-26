package com.book.user.service;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import com.book.user.security.UserPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.fasterxml.jackson.annotation.JsonAnySetter;

/** Explicit non-credential DTOs; all target classification and ceilings come from current database state. */
@Service
public class UserManagementService {
    final JdbcTemplate jdbc; final AuthorizationService auth; final Clock clock;
    public UserManagementService(JdbcTemplate jdbc,AuthorizationService auth,Clock clock){this.jdbc=jdbc;this.auth=auth;this.clock=clock;}
    static final String BASE="""
        SELECT u.*,m.tenant_id,m.membership_id,m.status membership_status,m.authz_version membership_version,
        t.display_name tenant_name,t.tenant_type,t.status tenant_status,t.owner_user_id,t.authz_version tenant_version
        FROM users u JOIN tenant_memberships m ON m.user_id=u.user_id AND m.status IN ('ACTIVE','SUSPENDED')
        JOIN tenants t ON t.tenant_id=m.tenant_id
        """;
    public record Role(long roleId,String name,String code,boolean system,String status,String description,List<String> permissions){}
    public record User(long userId,String username,String displayName,String tenantName,long tenantId,String tenantType,
        List<Role> roles,String status,Instant createdAt,Instant lastLogin,String version,boolean canEdit,boolean canSoftDelete,
        boolean canHardDelete,boolean canActivate,boolean canAssignRoles,Long pendingJobId,boolean deleted,Instant deletedAt){}
    public record Page(List<User> users,long total,int page,int size,boolean superUser){}
    public record Grant(String code,String name,List<String> sourceRoles){}
    public record Details(User user,String email,Instant emailVerifiedAt,Instant updatedAt,String tenantStatus,String membershipStatus,
        List<Grant> effectivePermissions,boolean directGrantsSupported){}
    public record Profile(String username,String displayName,String email,String tenantName,String tenantType,List<String> roles,
        String status,Instant createdAt,Instant lastLogin){}
    public record Edit(String displayName,String version){@JsonAnySetter public void unsupported(String key,Object ignored){throw invalid("Only displayName and version are editable.");}}
    public record Activate(String version,boolean resumeTenant){@JsonAnySetter public void unsupported(String key,Object ignored){throw invalid("Unsupported activation field.");}}
    public record Assign(List<Long> roleIds,String version){@JsonAnySetter public void unsupported(String key,Object ignored){throw invalid("Unsupported role assignment field.");}}
    public record Confirm(String version,String scope,int affectedUsers,String confirmation){@JsonAnySetter public void unsupported(String key,Object ignored){throw invalid("Unsupported deletion field.");}}
    public record Preview(long userId,long tenantId,String tenantName,String scope,int affectedUsers,boolean permanent,
        boolean preservesFiles,String confirmation,String version,String explanation){}

    @Transactional(readOnly=true)
    public Page list(UserPrincipal actor,String search,String status,Long tenantId,int page,int size){
        authorize(actor,"USER_VIEW");if(page<0 || size<1 || size>100 || (long)page*size>Integer.MAX_VALUE)throw invalid("Invalid page or size.");
        boolean superUser=auth.isSuperUser(actor);var args=new ArrayList<Object>();String where=" WHERE 1=1";
        if(!superUser){where+=" AND m.tenant_id=? AND t.tenant_type='ORGANIZATION' AND u.user_id<>? AND (t.owner_user_id IS NULL OR t.owner_user_id<>u.user_id) AND NOT EXISTS(SELECT 1 FROM user_roles ur JOIN roles r ON r.role_id=ur.role_id AND r.tenant_id=ur.tenant_id WHERE ur.membership_id=m.membership_id AND r.is_system=TRUE AND r.role_code IN ('ADMIN','SUPER_USER'))";args.add(actor.tenantId());args.add(actor.userId());}
        if(tenantId!=null){if(tenantId<=0 || !superUser && tenantId!=actor.tenantId())throw missing();where+=" AND m.tenant_id=?";args.add(tenantId);}
        if(status==null)status="ACTIVE";
        if(!status.equals("ALL")){if(status.equals("ACTIVE"))where+=" AND u.status='ACTIVE' AND u.deleted_at IS NULL";else if(status.equals("INACTIVE"))where+=" AND u.status<>'ACTIVE' AND u.deleted_at IS NULL";else if(status.equals("DELETED"))where+=" AND u.deleted_at IS NOT NULL";else throw invalid("Choose ACTIVE, INACTIVE, DELETED or ALL.");}
        if(search!=null && !search.isBlank()){if(search.length()>200)throw invalid("Search is too long.");String value="%"+search.strip().toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%";
            where+=" AND (LOWER(u.primary_email) LIKE ? ESCAPE '!' OR LOWER(u.display_name) LIKE ? ESCAPE '!' OR LOWER(t.display_name) LIKE ? ESCAPE '!')";args.add(value);args.add(value);args.add(value);}
        long count=jdbc.queryForObject("SELECT COUNT(*) FROM ("+BASE+where+") scoped_users",Long.class,args.toArray());
        args.add(size);args.add((long)page*size);
        var records=jdbc.queryForList(BASE+where+" ORDER BY u.user_id LIMIT ? OFFSET ?",args.toArray());
        return new Page(records.stream().map(row->view(actor,row)).toList(),count,page,size,superUser);
    }
    @Transactional(readOnly=true)
    public Details details(UserPrincipal actor,long id){authorize(actor,"USER_VIEW");var target=row(id);scope(actor,target);return details(actor,target);}
    @Transactional(readOnly=true)
    public Profile current(UserPrincipal actor){if(actor==null)throw denied();var target=row(actor.userId());
        return new Profile(str(target,"primary_email"),str(target,"display_name"),str(target,"primary_email"),str(target,"tenant_name"),str(target,"tenant_type"),roles(target).stream().map(Role::name).toList(),str(target,"status"),instant(target,"created_at"),lastLogin(id(target,"user_id")));}
    @Transactional
    public Details edit(UserPrincipal actor,long userId,Edit input){var target=lock(actor,userId,"USER_EDIT");version(target,input.version());
        if(target.get("deleted_at")!=null)throw conflict("A soft-deleted account cannot be edited.");
        if(input.displayName()==null || input.displayName().isBlank() || input.displayName().strip().length()>150)throw invalid("Name must contain 1 to 150 characters.");
        jdbc.update("UPDATE users SET display_name=?,management_version=management_version+1,updated_at=? WHERE user_id=?",input.displayName().strip(),now(),userId);
        audit(actor,id(target,"tenant_id"),userId,"USER_PROFILE_EDIT",Map.of("changedFields",List.of("display_name")));
        return details(actor,row(userId));}
    @Transactional
    public Details activate(UserPrincipal actor,long userId,Activate input){var target=lock(actor,userId,"USER_ACTIVATE");requireSuper(actor);protect(actor,target);version(target,input.version());
        if(target.get("deleted_at")!=null)throw conflict("A soft-deleted account cannot be activated. Inactive and deleted accounts are distinct.");
        if(!str(target,"status").equals("INACTIVE"))throw conflict("Only an inactive, existing account can be activated.");
        String tenantStatus=str(target,"tenant_status");if(!tenantStatus.equals("ACTIVE")){
            if(!input.resumeTenant() || !Set.of("INACTIVE","SUSPENDED").contains(tenantStatus))throw conflict("Tenant policy blocks login. Explicitly confirm resuming an inactive/suspended tenant.");
            jdbc.update("UPDATE tenants SET status='ACTIVE',authz_version=authz_version+1,updated_at=? WHERE tenant_id=?",now(),id(target,"tenant_id"));
        }
        revoke(userId);jdbc.update("UPDATE users SET status='ACTIVE',security_version=security_version+1,management_version=management_version+1,updated_at=? WHERE user_id=?",now(),userId);
        audit(actor,id(target,"tenant_id"),userId,"USER_ACTIVATE",Map.of("selectedAccountOnly",true,"tenantResumed",!tenantStatus.equals("ACTIVE")));
        return details(actor,row(userId));}
    @Transactional(readOnly=true)
    public List<Role> assignable(UserPrincipal actor,long userId){authorize(actor,"USER_ROLE_ASSIGN");requireSuper(actor);var target=row(userId);protect(actor,target);return tenantRoles(id(target,"tenant_id"));}
    @Transactional
    public Details assign(UserPrincipal actor,long userId,Assign input){var target=lock(actor,userId,"USER_ROLE_ASSIGN");requireSuper(actor);protect(actor,target);version(target,input.version());
        if(target.get("deleted_at")!=null)throw conflict("A soft-deleted account cannot be assigned roles.");
        if(input.roleIds()==null || input.roleIds().size()>100 || input.roleIds().stream().anyMatch(Objects::isNull))throw invalid("Choose valid assigned roles.");
        var desired=new TreeSet<>(input.roleIds());if(desired.size()!=input.roleIds().size())throw invalid("Duplicate roles are not allowed.");
        Set<Long> allowed=new HashSet<>(tenantRoles(id(target,"tenant_id")).stream().map(Role::roleId).toList());if(!allowed.containsAll(desired))throw denied();
        List<Long> old=roles(target).stream().map(Role::roleId).toList();
        jdbc.update("DELETE FROM user_roles WHERE tenant_id=? AND membership_id=?",id(target,"tenant_id"),id(target,"membership_id"));
        for(long role:desired)jdbc.update("INSERT INTO user_roles(tenant_id,membership_id,role_id,granted_by_membership_id,granted_at) VALUES(?,?,?,?,?)",id(target,"tenant_id"),id(target,"membership_id"),role,actor.tenantId()==id(target,"tenant_id")?actor.membershipId():null,now());
        jdbc.update("UPDATE tenant_memberships SET authz_version=authz_version+1,updated_at=? WHERE membership_id=?",now(),id(target,"membership_id"));
        jdbc.update("UPDATE users SET management_version=management_version+1,updated_at=? WHERE user_id=?",now(),userId);revoke(userId);
        audit(actor,id(target,"tenant_id"),userId,"USER_ROLES_CHANGED",Map.of("oldRoleIds",old,"newRoleIds",desired));return details(actor,row(userId));}
    @Transactional(readOnly=true)
    public Preview preview(UserPrincipal actor,long userId,boolean hard){authorize(actor,hard?"USER_HARD_DELETE":"USER_SOFT_DELETE");var target=row(userId);scope(actor,target);protect(actor,target);return preview(actor,target,hard);}
    @Transactional
    public void softDelete(UserPrincipal actor,long userId,Confirm input){var target=lock(actor,userId,"USER_SOFT_DELETE");protect(actor,target);var plan=preview(actor,target,false);confirm(plan,input);
        List<Long> affected=plan.scope().equals("TENANT_DEACTIVATE")?tenantUsers(plan.tenantId()):List.of(userId);
        for(long id:affected){revoke(id);jdbc.update("UPDATE users SET status='INACTIVE',deleted_at=COALESCE(deleted_at,?),security_version=security_version+1,management_version=management_version+1,updated_at=? WHERE user_id=?",now(),now(),id);}
        audit(actor,plan.tenantId(),userId,"USER_SOFT_DELETE",Map.of("scope",plan.scope(),"affectedUserIds",affected,"filesPreserved",true));}
    Preview preview(UserPrincipal actor,Map<String,Object> target,boolean hard){
        boolean all=auth.isSuperUser(actor) && admin(target) && str(target,"tenant_type").equals("ORGANIZATION");
        String scope=hard?(all || str(target,"tenant_type").equals("INDIVIDUAL")?"TENANT_PURGE":auth.isSuperUser(actor)?"ACCOUNT_PURGE":"ACCOUNT_PRESERVE"):(all?"TENANT_DEACTIVATE":"USER_DEACTIVATE");
        long tenant=id(target,"tenant_id"),user=id(target,"user_id");int count=(scope.startsWith("TENANT"))?tenantUsers(tenant).size():1;
        if(scope.startsWith("TENANT"))assertNoHighestInTenant(tenant);
        if(hard && str(target,"tenant_type").equals("INDIVIDUAL") && (count!=1 || !Objects.equals(target.get("owner_user_id"),target.get("user_id"))))throw conflict("Standalone ownership is ambiguous. Operator review is required.");
        String phrase=(hard?"DELETE ":"DEACTIVATE ")+(scope.startsWith("TENANT")?"TENANT "+tenant:"USER "+user);
        return new Preview(user,tenant,str(target,"tenant_name"),scope,count,hard,!hard || scope.equals("ACCOUNT_PRESERVE"),phrase,version(target),
            scope.equals("TENANT_PURGE")?"Permanently deletes this tenant, all its users, tenant content and its entire storage directory.":
            scope.equals("TENANT_DEACTIVATE")?"Deactivates EVERY tenant user. Company data and every file/folder are preserved.":
            scope.equals("ACCOUNT_PRESERVE")?"Deletes this employee account and authentication associations; all tenant content, files and folders are retained.":
            hard?"Deletes the selected account and exclusively owned data. Ambiguous employee file ownership blocks destructive cleanup.":"Deactivates only this account. Data and files are preserved.");
    }
    Map<String,Object> lock(UserPrincipal actor,long userId,String ability){authorize(actor,ability);var hint=row(userId);scope(actor,hint);
        jdbc.queryForList("SELECT tenant_id FROM tenants WHERE tenant_id IN (?,?) ORDER BY tenant_id FOR UPDATE",actor.tenantId(),id(hint,"tenant_id"));
        jdbc.queryForList("SELECT user_id FROM users WHERE user_id=? FOR UPDATE",userId);
        authorize(actor,ability);var target=row(userId);scope(actor,target);idle(id(target,"tenant_id"));
        if(str(target,"status").equals("DELETING"))throw conflict("Account deletion is pending.");return target;}
    void authorize(UserPrincipal actor,String ability){if(actor==null)throw denied();
        var accounts=jdbc.queryForList("""
            SELECT u.security_version,u.status,u.deleted_at,u.email_verified_at,t.status tenant_status,t.authz_version tv,m.status ms,m.authz_version mv
            FROM users u JOIN tenant_memberships m ON m.user_id=u.user_id JOIN tenants t ON t.tenant_id=m.tenant_id
            WHERE u.user_id=? AND m.membership_id=? AND m.tenant_id=?
            """,actor.userId(),actor.membershipId(),actor.tenantId());
        if(accounts.size()!=1)throw denied();var a=accounts.getFirst();
        if(!"ACTIVE".equals(a.get("status")) || a.get("deleted_at")!=null || a.get("email_verified_at")==null || !"ACTIVE".equals(a.get("tenant_status")) || !"ACTIVE".equals(a.get("ms"))
            || id(a,"security_version")!=actor.securityVersion() || id(a,"tv")!=actor.tenantAuthzVersion() || id(a,"mv")!=actor.membershipAuthzVersion()
            || !auth.authorizationFingerprint(actor.tenantId(),actor.membershipId()).equals(actor.authorizationFingerprint()))throw denied();
        if(!auth.isSuperUser(actor) && jdbc.queryForObject("SELECT COUNT(*) FROM user_roles ur JOIN roles r ON r.role_id=ur.role_id AND r.tenant_id=ur.tenant_id WHERE ur.tenant_id=? AND ur.membership_id=? AND r.is_system=TRUE AND r.role_code='ADMIN' AND r.status='ACTIVE'",Long.class,actor.tenantId(),actor.membershipId())==0)throw denied();
        if(!has(actor,ability))throw denied();}
    boolean has(UserPrincipal actor,String code){var ids=jdbc.queryForList("SELECT permission_id FROM permissions WHERE permission_code=? AND is_active=TRUE",Long.class,code);return !ids.isEmpty() && auth.effectivePermissions(actor).contains(ids.getFirst());}
    void requireSuper(UserPrincipal actor){if(!auth.isSuperUser(actor))throw denied();}
    void scope(UserPrincipal actor,Map<String,Object> target){if(auth.isSuperUser(actor))return;
        if(id(target,"tenant_id")!=actor.tenantId() || !str(target,"tenant_type").equals("ORGANIZATION") || admin(target) || highest(target)
            || id(target,"user_id")==actor.userId() || Objects.equals(target.get("owner_user_id"),target.get("user_id")))throw missing();}
    void protect(UserPrincipal actor,Map<String,Object> target){if(id(target,"user_id")==actor.userId() || highest(target))throw denied();}
    void idle(long tenant){if(jdbc.queryForObject("SELECT COUNT(*) FROM user_deletion_jobs WHERE tenant_id=? AND status<>'COMPLETE'",Long.class,tenant)>0)throw conflict("A deletion job is pending for this tenant. Review its cleanup status first.");}
    boolean admin(Map<String,Object> target){return hasSystem(id(target,"tenant_id"),id(target,"membership_id"),"ADMIN");}
    boolean highest(Map<String,Object> target){return hasSystem(id(target,"tenant_id"),id(target,"membership_id"),"SUPER_USER");}
    boolean hasSystem(long tenant,long membership,String code){return jdbc.queryForObject("SELECT COUNT(*) FROM user_roles ur JOIN roles r ON r.role_id=ur.role_id AND r.tenant_id=ur.tenant_id WHERE ur.tenant_id=? AND ur.membership_id=? AND r.is_system=TRUE AND r.role_code=?",Long.class,tenant,membership,code)>0;}
    void assertNoHighestInTenant(long tenant){if(jdbc.queryForObject("SELECT COUNT(*) FROM user_roles ur JOIN roles r ON r.role_id=ur.role_id AND r.tenant_id=ur.tenant_id WHERE ur.tenant_id=? AND r.is_system=TRUE AND r.role_code='SUPER_USER'",Long.class,tenant)>0)throw conflict("This tenant contains a protected super-user account. Operator review is required.");}
    Map<String,Object> row(long userId){if(userId<=0)throw missing();var rows=jdbc.queryForList(BASE+" WHERE u.user_id=?",userId);if(rows.size()!=1)throw missing();return rows.getFirst();}
    List<Long> tenantUsers(long tenant){return jdbc.queryForList("SELECT user_id FROM tenant_memberships WHERE tenant_id=? AND status IN ('ACTIVE','SUSPENDED') ORDER BY user_id",Long.class,tenant);}
    String version(Map<String,Object> target){return id(target,"management_version")+":"+id(target,"security_version")+":"+id(target,"membership_version")+":"+auth.authorizationFingerprint(id(target,"tenant_id"),id(target,"membership_id"));}
    void version(Map<String,Object> target,String version){if(!version(target).equals(version))throw conflict("User details changed. Reload before saving.");}
    void confirm(Preview plan,Confirm input){if(input==null || !plan.version().equals(input.version()) || !plan.scope().equals(input.scope()) || plan.affectedUsers()!=input.affectedUsers())throw conflict("The affected scope changed. Review a fresh confirmation.");
        if(!plan.confirmation().equals(input.confirmation()))throw invalid("Enter the exact confirmation phrase.");}
    Details details(UserPrincipal actor,Map<String,Object> target){var principal=principal(target);var effective=target.get("deleted_at")==null && "ACTIVE".equals(target.get("status")) && "ACTIVE".equals(target.get("tenant_status")) && "ACTIVE".equals(target.get("membership_status"))?auth.effectivePermissions(principal):Set.<Long>of();
        List<Grant> grants=jdbc.query("SELECT permission_id,permission_code,display_name FROM permissions WHERE is_active=TRUE ORDER BY component_code,permission_id",(rs,n)->{
            String code=rs.getString(2);return new Grant(code,rs.getString(3),auth.isSuperUser(principal)?List.of("SUPER_USER policy"):roles(target).stream().filter(r->r.status().equals("ACTIVE") && r.permissions().contains(code)).map(Role::name).toList());
        }).stream().filter(g->effective.contains(jdbc.queryForObject("SELECT permission_id FROM permissions WHERE permission_code=?",Long.class,g.code()))).toList();
        return new Details(view(actor,target),str(target,"primary_email"),instant(target,"email_verified_at"),instant(target,"updated_at"),str(target,"tenant_status"),str(target,"membership_status"),grants,false);}
    User view(UserPrincipal actor,Map<String,Object> row){boolean superUser=auth.isSuperUser(actor),protectedAccount=highest(row) || id(row,"user_id")==actor.userId();
        var jobs=jdbc.queryForList("SELECT job_id FROM user_deletion_jobs WHERE tenant_id=? AND status<>'COMPLETE' ORDER BY job_id",Long.class,id(row,"tenant_id"));boolean idle=jobs.isEmpty() && !"DELETING".equals(row.get("status"));
        boolean deleted=row.get("deleted_at")!=null;
        return new User(id(row,"user_id"),str(row,"primary_email"),str(row,"display_name"),str(row,"tenant_name"),id(row,"tenant_id"),str(row,"tenant_type"),roles(row),str(row,"status"),instant(row,"created_at"),lastLogin(id(row,"user_id")),version(row),idle && !deleted && has(actor,"USER_EDIT"),idle && !deleted && !protectedAccount && has(actor,"USER_SOFT_DELETE"),idle && !protectedAccount && has(actor,"USER_HARD_DELETE"),idle && !deleted && superUser && !protectedAccount && "INACTIVE".equals(row.get("status")),idle && !deleted && superUser && !protectedAccount,jobs.isEmpty()?null:jobs.getFirst(),deleted,instant(row,"deleted_at"));}
    List<Role> roles(Map<String,Object> row){return jdbc.query("SELECT r.* FROM roles r JOIN user_roles ur ON ur.role_id=r.role_id AND ur.tenant_id=r.tenant_id WHERE ur.tenant_id=? AND ur.membership_id=? ORDER BY r.role_id",(rs,n)->role(rs.getLong("role_id"),rs.getString("display_name"),rs.getString("role_code"),rs.getBoolean("is_system"),rs.getString("status"),rs.getString("description")),id(row,"tenant_id"),id(row,"membership_id"));}
    List<Role> tenantRoles(long tenant){return jdbc.query("SELECT * FROM roles WHERE tenant_id=? AND status='ACTIVE' AND NOT(is_system=TRUE AND role_code='SUPER_USER') ORDER BY role_id",(rs,n)->role(rs.getLong("role_id"),rs.getString("display_name"),rs.getString("role_code"),rs.getBoolean("is_system"),rs.getString("status"),rs.getString("description")),tenant);}
    Role role(long id,String name,String code,boolean system,String status,String description){return new Role(id,name,code,system,status,description,jdbc.queryForList("SELECT p.permission_code FROM permissions p JOIN role_permissions rp ON rp.permission_id=p.permission_id WHERE rp.role_id=? AND p.is_active=TRUE ORDER BY p.permission_code",String.class,id));}
    UserPrincipal principal(Map<String,Object> row){return new UserPrincipal(id(row,"user_id"),id(row,"tenant_id"),id(row,"membership_id"),0,id(row,"security_version"),id(row,"tenant_version"),id(row,"membership_version"),auth.authorizationFingerprint(id(row,"tenant_id"),id(row,"membership_id")));}
    void revoke(long user){Timestamp now=now();jdbc.update("UPDATE refresh_tokens SET revoked_at=? WHERE session_id IN(SELECT session_id FROM auth_sessions WHERE user_id=?) AND revoked_at IS NULL",now,user);jdbc.update("UPDATE auth_sessions SET revoked_at=? WHERE user_id=? AND revoked_at IS NULL",now,user);jdbc.update("UPDATE credential_tokens SET revoked_at=? WHERE user_id=? AND consumed_at IS NULL AND revoked_at IS NULL",now,user);}
    Instant lastLogin(long user){Timestamp result=jdbc.queryForObject("SELECT MAX(created_at) FROM auth_sessions WHERE user_id=?",Timestamp.class,user);return result==null?null:result.toInstant();}
    void audit(UserPrincipal actor,long tenant,long target,String event,Object details){jdbc.update("INSERT INTO audit_events(tenant_id,actor_user_id,event_code,target_type,target_id,outcome,details,occurred_at) VALUES(?,?,?,'USER',?,'SUCCESS',?,?)",tenant,actor.userId(),event,Long.toString(target),tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(details),now());}
    Timestamp now(){return Timestamp.from(clock.instant());}
    static long id(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    static String str(Map<String,Object> row,String key){return (String)row.get(key);}
    static Instant instant(Map<String,Object> row,String key){return com.book.user.util.AuthRows.instant(row,key);}
    static ResponseStatusException denied(){return new ResponseStatusException(HttpStatus.FORBIDDEN,"You do not have authority to manage this account or action.");}
    static ResponseStatusException missing(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"User not found in your authorized scope.");}
    static ResponseStatusException invalid(String detail){return new ResponseStatusException(HttpStatus.BAD_REQUEST,detail);}
    static ResponseStatusException conflict(String detail){return new ResponseStatusException(HttpStatus.CONFLICT,detail);}
}
