package com.book.user.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.book.user.security.UserPrincipal;

/** No inheritance: each role has explicit grants. Immutable system identity is (is_system, role_code). */
@Service
public class RoleManagementService {
    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final Clock clock;
    public RoleManagementService(JdbcTemplate jdbc,AuthorizationService authorization,Clock clock) {
        this.jdbc=jdbc;this.authorization=authorization;this.clock=clock;
    }
    public record Write(Long tenantId,String name,String description,List<Long> permissionIds,Long version,String systemRole) {}
    public record Role(long roleId,long tenantId,String name,String description,String systemRole,long version,Set<Long> permissionIds,boolean editable) {}
    public record Permission(long permissionId,String code,String name,Long prerequisiteId,List<Route> routes) {}
    public record Route(String method,String path) {}
    public record Catalog(long tenantId,boolean superUser,boolean canCreate,boolean canEdit,boolean canAssign,List<Permission> permissions) {}
    public record Organization(long tenantId,String name) {}

    @Transactional
    public List<Organization> organizations(UserPrincipal actor) {
        if(actor==null) throw denied();
        begin(actor,actor.tenantId(),"ROLE_VIEW");
        return jdbc.query("SELECT tenant_id,display_name FROM tenants WHERE (?=TRUE OR tenant_id=?) AND status<>'DELETED' ORDER BY tenant_id",
                (rs,row)->new Organization(rs.getLong(1),rs.getString(2)),authorization.isSuperUser(actor),actor.tenantId());
    }
    @Transactional
    public Catalog catalog(UserPrincipal actor,Long requestedTenant) {
        long tenant=scope(actor,requestedTenant);begin(actor,tenant,"ROLE_VIEW");
        boolean superUser=authorization.isSuperUser(actor);Set<Long> grants=grantable(actor);
        List<Permission> permissions=jdbc.query("SELECT permission_id,permission_code,display_name,prerequisite_permission_id FROM permissions WHERE is_active=TRUE ORDER BY component_code,permission_id",
                (rs,row)->new Permission(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getObject(4,Long.class),routes(rs.getLong(1))));
        return new Catalog(tenant,superUser,has(actor,"ROLE_CREATE"),has(actor,"ROLE_EDIT"),has(actor,"ROLE_ASSIGN"),
                permissions.stream().filter(p->grants.contains(p.permissionId())).toList());
    }
    @Transactional
    public List<Role> list(UserPrincipal actor,Long requestedTenant) {
        long tenant=scope(actor,requestedTenant);begin(actor,tenant,"ROLE_VIEW");
        return jdbc.queryForList("SELECT role_id FROM roles WHERE tenant_id=? ORDER BY role_id",Long.class,tenant)
                .stream().map(id->read(actor,tenant,id)).toList();
    }
    @Transactional
    public Role get(UserPrincipal actor,Long requestedTenant,long id) {
        long tenant=scope(actor,requestedTenant);begin(actor,tenant,"ROLE_VIEW");return read(actor,tenant,id);
    }
    /** Create User exposes the same assignment ceiling, without granting separate role-management access. */
    @Transactional
    public List<Role> creationChoices(UserPrincipal actor,long tenant) {
        scope(actor,tenant);
        if(!has(actor,"ROLE_ASSIGN")) return List.of();
        begin(actor,tenant,"ROLE_ASSIGN");
        return jdbc.queryForList("SELECT role_id FROM roles WHERE tenant_id=? AND status='ACTIVE' ORDER BY display_name,role_id",Long.class,tenant)
                .stream().map(id->read(actor,tenant,id)).filter(role->assignmentAllowed(actor,role)).toList();
    }
    @Transactional
    public Role create(UserPrincipal actor,Write input) {
        if(input==null) throw invalid("Provide role details.");
        long tenant=scope(actor,input.tenantId());begin(actor,tenant,"ROLE_CREATE");
        boolean system=input.systemRole()!=null;
        if(system && (!authorization.isSuperUser(actor) || !"ADMIN".equals(input.systemRole()))) throw denied();
        String name=name(input.name(),system);String description=description(input.description());
        Set<Long> desired=validateGrants(actor,input.permissionIds());ensureUniqueName(tenant,name,null);
        if(system && jdbc.queryForObject("SELECT COUNT(*) FROM roles WHERE tenant_id=? AND role_code='ADMIN'",Long.class,tenant)>0)
            throw conflict("This organization already has an ADMIN role. Edit that role instead.");
        Timestamp now=Timestamp.from(clock.instant());GeneratedKeyHolder key=new GeneratedKeyHolder();
        jdbc.update(connection->{var statement=connection.prepareStatement("""
            INSERT INTO roles(tenant_id,role_code,display_name,description,is_system,status,authz_version,created_at,updated_at)
            VALUES(?,?,?,?,?,'ACTIVE',1,?,?)
            """,new String[]{"role_id"});
            Object[] values={tenant,system?"ADMIN":"ROLE_"+UUID.randomUUID().toString().replace("-",""),name,description,system,now,now};
            for(int i=0;i<values.length;i++) statement.setObject(i+1,values[i]);return statement;},key);
        long id=Objects.requireNonNull(key.getKey()).longValue();replaceGrants(tenant,id,desired,now);
        audit(actor,tenant,id,"ROLE_CREATE",Set.of(),desired,now);invalidate(tenant,now);
        return read(actor,tenant,id);
    }
    @Transactional
    public Role update(UserPrincipal actor,long id,Write input) {
        if(input==null) throw invalid("Provide role details.");
        long tenant=scope(actor,input.tenantId());begin(actor,tenant,"ROLE_EDIT");
        Role current=read(actor,tenant,id);
        if(!current.editable() || input.systemRole()!=null) throw denied();
        if(input.version()==null || input.version()!=current.version()) throw conflict("This role changed. Reload it before saving.");
        Set<Long> desired=validateGrants(actor,input.permissionIds());
        String name=name(input.name(),"ADMIN".equals(current.systemRole()));ensureUniqueName(tenant,name,id);
        Timestamp now=Timestamp.from(clock.instant());
        int changed=jdbc.update("UPDATE roles SET display_name=?,description=?,authz_version=authz_version+1,updated_at=? WHERE tenant_id=? AND role_id=? AND authz_version=?",
                name,description(input.description()),now,tenant,id,input.version());
        if(changed!=1) throw conflict("This role changed. Reload it before saving.");
        replaceGrants(tenant,id,desired,now);audit(actor,tenant,id,"ROLE_EDIT",current.permissionIds(),desired,now);invalidate(tenant,now);
        return read(actor,tenant,id);
    }
    @Transactional
    public void assign(UserPrincipal actor,Long requestedTenant,long roleId,long membershipId,boolean remove) {
        long tenant=scope(actor,requestedTenant);begin(actor,tenant,"ROLE_ASSIGN");Role role=read(actor,tenant,roleId);
        if(!assignmentAllowed(actor,role) || !remove && jdbc.queryForObject("SELECT COUNT(*) FROM roles WHERE tenant_id=? AND role_id=? AND status='ACTIVE'",Long.class,tenant,roleId)!=1) throw denied();
        if(jdbc.queryForObject("SELECT COUNT(*) FROM tenant_memberships m JOIN users u ON u.user_id=m.user_id WHERE m.tenant_id=? AND m.membership_id=? AND m.status='ACTIVE' AND u.deleted_at IS NULL",Long.class,tenant,membershipId)!=1) throw missing();
        if(jdbc.queryForObject("SELECT COUNT(*) FROM user_roles ur JOIN roles r ON r.role_id=ur.role_id AND r.tenant_id=ur.tenant_id WHERE ur.tenant_id=? AND ur.membership_id=? AND r.is_system=TRUE AND r.role_code='SUPER_USER'",Long.class,tenant,membershipId)>0)throw denied();
        if(!authorization.isSuperUser(actor) && jdbc.queryForObject("SELECT COUNT(*) FROM user_roles ur JOIN roles r ON r.role_id=ur.role_id AND r.tenant_id=ur.tenant_id WHERE ur.tenant_id=? AND ur.membership_id=? AND r.is_system=TRUE AND r.role_code='ADMIN'",Long.class,tenant,membershipId)>0)throw denied();
        Timestamp now=Timestamp.from(clock.instant());
        if(remove) jdbc.update("DELETE FROM user_roles WHERE tenant_id=? AND membership_id=? AND role_id=?",tenant,membershipId,roleId);
        else if(jdbc.queryForObject("SELECT COUNT(*) FROM user_roles WHERE tenant_id=? AND membership_id=? AND role_id=?",Long.class,tenant,membershipId,roleId)==0)
            jdbc.update("INSERT INTO user_roles(tenant_id,membership_id,role_id,granted_by_membership_id,granted_at) VALUES(?,?,?,?,?)",tenant,membershipId,roleId,actor.tenantId()==tenant?actor.membershipId():null,now);
        audit(actor,tenant,roleId,remove?"ROLE_UNASSIGN":"ROLE_ASSIGN",remove?Set.of(membershipId):Set.of(),remove?Set.of():Set.of(membershipId),now);
        jdbc.update("UPDATE tenant_memberships SET authz_version=authz_version+1,updated_at=? WHERE tenant_id=? AND membership_id=?",now,tenant,membershipId);
        invalidate(tenant,now);
    }
    private void begin(UserPrincipal actor,long tenant,String ability) {
        if(actor==null) throw denied();
        // All management operations serialize with one another in tenant-ID order, including the actor's tenant.
        var locked=jdbc.queryForList("SELECT tenant_id FROM tenants WHERE tenant_id IN (?,?) ORDER BY tenant_id FOR UPDATE",Long.class,actor.tenantId(),tenant);
        if(!locked.contains(tenant)) throw missing();
        if(jdbc.queryForObject("SELECT COUNT(*) FROM user_deletion_jobs WHERE tenant_id=? AND status<>'COMPLETE'",Long.class,tenant)>0)throw conflict("Role changes are blocked while deletion is pending.");
        Long valid=jdbc.queryForObject("""
            SELECT COUNT(*) FROM users u JOIN tenant_memberships m ON m.user_id=u.user_id JOIN tenants t ON t.tenant_id=m.tenant_id
            WHERE u.user_id=? AND m.membership_id=? AND m.tenant_id=? AND u.status='ACTIVE' AND u.deleted_at IS NULL AND u.email_verified_at IS NOT NULL
            AND u.security_version=? AND m.status='ACTIVE' AND t.status='ACTIVE' AND t.authz_version=? AND m.authz_version=?
            """,Long.class,actor.userId(),actor.membershipId(),actor.tenantId(),actor.securityVersion(),actor.tenantAuthzVersion(),actor.membershipAuthzVersion());
        if(valid!=1 || !actor.authorizationFingerprint().equals(authorization.authorizationFingerprint(actor.tenantId(),actor.membershipId()))) throw denied();
        if(!has(actor,ability)) throw denied();
    }
    private boolean has(UserPrincipal actor,String code) {
        Long id=jdbc.queryForObject("SELECT permission_id FROM permissions WHERE permission_code=? AND is_active=TRUE",Long.class,code);
        return authorization.effectivePermissions(actor).contains(id);
    }
    private long scope(UserPrincipal actor,Long tenant) {
        if(actor==null) throw denied();long result=tenant==null?actor.tenantId():tenant;
        if(result<=0 || (result!=actor.tenantId() && !authorization.isSuperUser(actor))) throw missing();return result;
    }
    private Set<Long> grantable(UserPrincipal actor) {
        Set<Long> result=new HashSet<>(authorization.effectivePermissions(actor));
        if(!authorization.isSuperUser(actor)) result.retainAll(jdbc.queryForList("SELECT permission_id FROM permissions WHERE is_delegable=TRUE",Long.class));
        return result;
    }
    private boolean assignmentAllowed(UserPrincipal actor,Role role) {
        boolean highest=authorization.isSuperUser(actor);
        return !"SUPER_USER".equals(role.systemRole()) && (highest || role.systemRole()==null && grantable(actor).containsAll(role.permissionIds()));
    }
    private Role read(UserPrincipal actor,long tenant,long id) {
        var rows=jdbc.queryForList("SELECT * FROM roles WHERE tenant_id=? AND role_id=?",tenant,id);if(rows.size()!=1) throw missing();
        var row=rows.getFirst();Set<Long> grants=new TreeSet<>(jdbc.queryForList("SELECT permission_id FROM role_permissions WHERE tenant_id=? AND role_id=?",Long.class,tenant,id));
        String system=Boolean.TRUE.equals(row.get("is_system"))?row.get("role_code").toString():null;
        boolean editable=!"SUPER_USER".equals(system) && (authorization.isSuperUser(actor) || system==null && grantable(actor).containsAll(grants)) && has(actor,"ROLE_EDIT");
        return new Role(id,tenant,row.get("display_name").toString(),(String)row.get("description"),system,((Number)row.get("authz_version")).longValue(),grants,editable);
    }
    private Set<Long> validateGrants(UserPrincipal actor,List<Long> values) {
        if(values==null || values.size()>1000 || values.stream().anyMatch(Objects::isNull)) throw invalid("Choose a valid permission set.");
        Set<Long> desired=new TreeSet<>(values);if(desired.size()!=values.size() || !grantable(actor).containsAll(desired)) throw denied();
        for(Long id:desired) {
            Long prerequisite=jdbc.queryForObject("SELECT prerequisite_permission_id FROM permissions WHERE permission_id=?",Long.class,id);
            if(prerequisite!=null && !desired.contains(prerequisite)) throw invalid("Include the required View permission for each action.");
        }
        return desired;
    }
    private void replaceGrants(long tenant,long id,Set<Long> desired,Timestamp now) {
        jdbc.update("DELETE FROM role_permissions WHERE tenant_id=? AND role_id=?",tenant,id);
        for(Long permission:desired) jdbc.update("INSERT INTO role_permissions(tenant_id,role_id,permission_id,is_explicit,created_at,updated_at) VALUES(?,?,?,TRUE,?,?)",tenant,id,permission,now,now);
    }
    private List<Route> routes(long permission) {return jdbc.query("SELECT e.http_method,e.path_pattern FROM api_endpoints e JOIN endpoint_permissions ep ON ep.endpoint_id=e.endpoint_id WHERE ep.permission_id=? AND e.is_active=TRUE ORDER BY e.http_method,e.path_pattern",
            (rs,row)->new Route(rs.getString(1),rs.getString(2)),permission);}
    private String name(String raw,boolean system) {
        if(raw==null || raw.isBlank() || raw.strip().length()>120) throw invalid("Role name must contain 1 to 120 characters.");
        String name=raw.strip();String canonical=java.text.Normalizer.normalize(name,java.text.Normalizer.Form.NFKC).toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]","");
        if(!system && Set.of("ADMIN","ADMINISTRATOR","SUPERUSER","SUPERADMIN","SUPERADMINISTRATOR").contains(canonical)) throw denied();
        return name;
    }
    private String description(String raw) {if(raw!=null && raw.length()>2000) throw invalid("Description must contain at most 2000 characters.");return raw;}
    private void ensureUniqueName(long tenant,String name,Long excluding) {
        if(jdbc.queryForObject("SELECT COUNT(*) FROM roles WHERE tenant_id=? AND LOWER(TRIM(display_name))=LOWER(?) AND (? IS NULL OR role_id<>?)",Long.class,tenant,name,excluding,excluding)>0)
            throw conflict("A role with this name already exists in this organization.");
    }
    private void invalidate(long tenant,Timestamp now) {jdbc.update("UPDATE tenants SET authz_version=authz_version+1,updated_at=? WHERE tenant_id=?",now,tenant);}
    private void audit(UserPrincipal actor,long tenant,long role,String event,Set<Long> oldSet,Set<Long> newSet,Timestamp now) {
        String details="{\"actorTenantId\":"+actor.tenantId()+",\"oldIds\":"+new TreeSet<>(oldSet)+",\"newIds\":"+new TreeSet<>(newSet)+"}";
        jdbc.update("INSERT INTO audit_events(tenant_id,actor_user_id,event_code,target_type,target_id,outcome,details,occurred_at) VALUES(?,?,?,'ROLE',?,'SUCCESS',?,?)",tenant,actor.userId(),event,Long.toString(role),details,now);
    }
    private static ResponseStatusException denied(){return new ResponseStatusException(HttpStatus.FORBIDDEN,"This role or permission set is outside your current role-management authority.");}
    private static ResponseStatusException missing(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"Organization or role not found.");}
    private static ResponseStatusException invalid(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
    private static ResponseStatusException conflict(String message){return new ResponseStatusException(HttpStatus.CONFLICT,message);}
}
