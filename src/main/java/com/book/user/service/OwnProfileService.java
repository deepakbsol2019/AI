package com.book.user.service;

import java.time.Instant;
import java.util.*;
import com.book.user.security.UserPrincipal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Authenticated self-only information. No request field can select another account. */
@Service
public class OwnProfileService {
    private final SessionService sessions;
    private final UserManagementService users;
    private final AuthorizationService authorization;
    private final JdbcTemplate jdbc;
    public OwnProfileService(SessionService sessions,UserManagementService users,AuthorizationService authorization,JdbcTemplate jdbc){
        this.sessions=sessions;this.users=users;this.authorization=authorization;this.jdbc=jdbc;
    }
    public record Details(String username,String displayName,String email,String tenantName,String tenantType,List<String> roles,
            String status,Instant createdAt,Instant lastLogin,long tenantId){}
    public record Route(String service,String method,String url,List<String> operations){}
    public record Permission(long permissionId,String name,String description,String code,String component,String kind,
            List<String> sourceRoles,List<Route> routes){}
    public record Permissions(String username,List<Permission> permissions){}

    @Transactional(readOnly=true)
    public Details details(UserPrincipal actor){
        sessions.validate(actor);var profile=users.current(actor);
        return new Details(profile.username(),profile.displayName(),profile.email(),profile.tenantName(),profile.tenantType(),
                profile.roles(),profile.status(),profile.createdAt(),profile.lastLogin(),actor.tenantId());
    }
    @Transactional(readOnly=true)
    public Permissions permissions(UserPrincipal actor){
        sessions.validate(actor);var profile=users.current(actor);Set<Long> effective=authorization.effectivePermissions(actor);
        boolean highest=authorization.isSuperUser(actor);
        List<Permission> permissions=jdbc.query("SELECT permission_id,display_name,description,permission_code,component_code,permission_kind FROM permissions WHERE is_active=TRUE ORDER BY component_code,permission_id",
                (rs,row)->new Permission(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),
                        highest?List.of("SUPER_USER policy"):sources(actor,rs.getLong(1)),routes(rs.getLong(1))))
                .stream().filter(permission->effective.contains(permission.permissionId())).toList();
        return new Permissions(profile.username(),permissions);
    }
    private List<String> sources(UserPrincipal actor,long permission){return jdbc.queryForList("""
            SELECT DISTINCT r.display_name FROM user_roles ur JOIN roles r ON r.role_id=ur.role_id AND r.tenant_id=ur.tenant_id
            JOIN role_permissions rp ON rp.role_id=r.role_id AND rp.tenant_id=r.tenant_id
            WHERE ur.tenant_id=? AND ur.membership_id=? AND r.status='ACTIVE' AND rp.permission_id=? ORDER BY r.display_name
            """,String.class,actor.tenantId(),actor.membershipId(),permission);}
    private List<Route> routes(long permission){return jdbc.query("""
            SELECT DISTINCT e.service_code,e.http_method,e.path_pattern FROM endpoint_permissions ep
            JOIN api_endpoints e ON e.endpoint_id=ep.endpoint_id
            WHERE ep.permission_id=? AND e.is_active=TRUE ORDER BY e.service_code,e.path_pattern,e.http_method
            """,(rs,row)->new Route(rs.getString(1),rs.getString(2),rs.getString(3),operations(rs.getString(2))),permission);}
    private static List<String> operations(String method){return switch(method.toUpperCase(Locale.ROOT)){
        case "GET","HEAD"->List.of("Read");case "POST","PUT","PATCH"->List.of("Write");case "DELETE"->List.of("Delete");default->List.of(method);
    };}
}
