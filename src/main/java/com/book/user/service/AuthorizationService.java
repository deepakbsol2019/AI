package com.book.user.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.book.user.security.UserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ResponseStatusException;

/** Authoritative, uncached role/policy checks. Every endpoint requirement uses ALL semantics. */
@Service
public class AuthorizationService {
    public static final String SERVICE_CODE = "BOOK_SEARCH";
    private final JdbcTemplate jdbc;
    private final AntPathMatcher paths = new AntPathMatcher();

    public AuthorizationService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public String authorizationFingerprint(long tenantId, long membershipId) {
        List<String> state = jdbc.query("""
                SELECT r.role_id,r.status,r.authz_version,p.permission_id,p.permission_code,p.component_code,
                       p.permission_kind,p.is_active,p.prerequisite_permission_id,
                       v.permission_kind AS view_kind,v.component_code AS view_component,v.is_active AS view_active,
                       r.role_code,r.is_system
                  FROM user_roles ur JOIN roles r ON r.role_id=ur.role_id AND r.tenant_id=ur.tenant_id
                  LEFT JOIN role_permissions rp ON rp.role_id=r.role_id AND rp.tenant_id=r.tenant_id
                  LEFT JOIN permissions p ON p.permission_id=rp.permission_id
                  LEFT JOIN permissions v ON v.permission_id=p.prerequisite_permission_id
                 WHERE ur.tenant_id=? AND ur.membership_id=? ORDER BY r.role_id,p.permission_id
                """, (rs, row) -> {
                    List<String> columns = new ArrayList<>();
                    for (int index = 1; index <= 14; index++) columns.add(String.valueOf(rs.getObject(index)));
                    return String.join("|", columns);
                }, tenantId, membershipId);
        if (hasSuperUserRole(tenantId, membershipId, null)) {
            state.addAll(jdbc.query("""
                    SELECT p.permission_id,p.permission_code,p.component_code,p.permission_kind,p.is_active,
                           p.prerequisite_permission_id,v.permission_kind,v.component_code,v.is_active
                      FROM permissions p LEFT JOIN permissions v ON v.permission_id=p.prerequisite_permission_id
                     ORDER BY p.permission_id
                    """, (rs, row) -> {
                        List<String> columns = new ArrayList<>();
                        columns.add("SUPER_USER_CATALOG");
                        for (int index = 1; index <= 9; index++) columns.add(String.valueOf(rs.getObject(index)));
                        return String.join("|", columns);
                    }));
        }
        return digest(String.join("\n", state));
    }

    /** A trusted operator grants this reserved system role; neither email nor a client claim grants it. */
    public boolean isSuperUser(UserPrincipal principal) {
        return principal != null && hasSuperUserRole(principal.tenantId(), principal.membershipId(), principal.userId());
    }

    /** Only the application's reserved, active ADMIN role grants tenant-wide file deletion. */
    public boolean isTenantAdmin(UserPrincipal principal) {
        return principal != null && jdbc.queryForObject("""
                SELECT COUNT(*) FROM user_roles ur JOIN roles r ON r.role_id=ur.role_id AND r.tenant_id=ur.tenant_id
                  JOIN tenant_memberships m ON m.membership_id=ur.membership_id AND m.tenant_id=ur.tenant_id
                  JOIN users u ON u.user_id=m.user_id JOIN tenants t ON t.tenant_id=m.tenant_id
                WHERE ur.tenant_id=? AND ur.membership_id=? AND m.user_id=? AND r.role_code='ADMIN'
                  AND r.is_system=TRUE AND r.status='ACTIVE' AND m.status='ACTIVE' AND u.status='ACTIVE'
                  AND u.email_verified_at IS NOT NULL AND t.status='ACTIVE'
                """,Long.class,principal.tenantId(),principal.membershipId(),principal.userId())>0;
    }

    private boolean hasSuperUserRole(long tenantId, long membershipId, Long userId) {
        String sql = """
                SELECT COUNT(*) FROM user_roles ur
                  JOIN roles r ON r.role_id=ur.role_id AND r.tenant_id=ur.tenant_id
                  JOIN tenant_memberships m ON m.membership_id=ur.membership_id AND m.tenant_id=ur.tenant_id
                  JOIN users u ON u.user_id=m.user_id JOIN tenants t ON t.tenant_id=m.tenant_id
                 WHERE ur.tenant_id=? AND ur.membership_id=? AND r.role_code='SUPER_USER' AND r.is_system=TRUE
                   AND r.status='ACTIVE' AND m.status='ACTIVE' AND u.status='ACTIVE'
                   AND u.email_verified_at IS NOT NULL AND t.status='ACTIVE'
                """;
        Object[] arguments = userId == null ? new Object[]{tenantId, membershipId}
                : new Object[]{tenantId, membershipId, userId};
        if (userId != null) sql += " AND u.user_id=?";
        return jdbc.queryForObject(sql, Long.class, arguments) > 0;
    }

    public void authorize(UserPrincipal principal, String method, String path) {
        if (principal == null) throw denied();
        if(!path.startsWith("/api/user/") && jdbc.queryForObject("SELECT COUNT(*) FROM user_deletion_jobs WHERE tenant_id=? AND status<>'COMPLETE'",Long.class,principal.tenantId())>0)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Tenant activity is paused while a deletion job awaits cleanup.");
        List<Endpoint> matching = jdbc.query("""
                SELECT endpoint_id,path_pattern,is_public FROM api_endpoints
                 WHERE service_code=? AND http_method=? AND is_active=TRUE
                """, (rs, row) -> new Endpoint(rs.getLong(1), rs.getString(2), rs.getBoolean(3)),
                SERVICE_CODE, method.toUpperCase(java.util.Locale.ROOT)).stream()
                .filter(endpoint -> paths.match(endpoint.pattern(), path)).sorted(
                        Comparator.comparing(Endpoint::pattern, paths.getPatternComparator(path)).thenComparingLong(Endpoint::id)).toList();
        if (matching.isEmpty()) throw denied();
        Endpoint selected = matching.getFirst();
        // Equally specific overlapping policies are configuration errors, not an arbitrary allow.
        if (matching.size() > 1 && paths.getPatternComparator(path).compare(selected.pattern(), matching.get(1).pattern()) == 0) throw denied();
        if (selected.isPublic()) return;
        // Active catalog registration is still required. Private services enforce owner + tenant.
        if (path.startsWith("/api/ai-help/")) return;
        if ("GET".equals(method) && ("/api/files/delete-capabilities".equals(path)
                || path.matches("/api/files/manage(?:/[0-9]+)?"))) return;
        if ("DELETE".equals(method) && path.matches("/api/(books|spreadsheets|uploads)/[0-9]+")
                && (isSuperUser(principal) || isTenantAdmin(principal))) return;
        // Authenticated self-profile is available to every signed-in user; the controller accepts no target ID.
        if("GET".equals(method) && ("/api/user/profile".equals(path) || "/api/user/profile/details".equals(path)
                || "/api/user/profile/permissions".equals(path) || "/api/user/users/capabilities".equals(path))) return;
        List<Long> required = jdbc.queryForList("SELECT permission_id FROM endpoint_permissions WHERE endpoint_id=? ORDER BY permission_id", Long.class, selected.id());
        if (required.isEmpty()) throw denied();
        boolean superUser = isSuperUser(principal);
        String grantQuery = superUser ? """
                SELECT p.permission_id,p.component_code,p.permission_kind,p.prerequisite_permission_id,
                       v.component_code,v.permission_kind,v.is_active
                  FROM permissions p LEFT JOIN permissions v ON v.permission_id=p.prerequisite_permission_id
                 WHERE p.is_active=TRUE
                """ : """
                SELECT DISTINCT p.permission_id,p.component_code,p.permission_kind,p.prerequisite_permission_id,
                       v.component_code,v.permission_kind,v.is_active
                  FROM user_roles ur JOIN roles r ON r.role_id=ur.role_id AND r.tenant_id=ur.tenant_id AND r.status='ACTIVE'
                  JOIN role_permissions rp ON rp.role_id=r.role_id AND rp.tenant_id=r.tenant_id
                  JOIN permissions p ON p.permission_id=rp.permission_id AND p.is_active=TRUE
                  LEFT JOIN permissions v ON v.permission_id=p.prerequisite_permission_id
                 WHERE ur.tenant_id=? AND ur.membership_id=?
                """;
        List<Grant> candidates = jdbc.query(grantQuery,
                (rs, row) -> new Grant(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getObject(4, Long.class), rs.getString(5), rs.getString(6), rs.getBoolean(7)),
                superUser ? new Object[0] : new Object[]{principal.tenantId(), principal.membershipId()});
        Map<Long, Grant> byId = candidates.stream().collect(Collectors.toMap(Grant::id, grant -> grant));
        Set<Long> effective = candidates.stream().filter(grant -> grant.valid(byId)).map(Grant::id).collect(Collectors.toSet());
        if (!effective.containsAll(required)) throw denied();
    }

    /** Fresh database permission identities; used again inside role-management transactions. */
    public Set<Long> effectivePermissions(UserPrincipal actor) {
        if(actor==null) throw denied();
        List<Grant> candidates=jdbc.query("""
                SELECT DISTINCT p.permission_id,p.component_code,p.permission_kind,p.prerequisite_permission_id,
                       v.component_code,v.permission_kind,v.is_active
                FROM permissions p LEFT JOIN permissions v ON v.permission_id=p.prerequisite_permission_id
                WHERE p.is_active=TRUE AND (?=TRUE OR EXISTS (
                  SELECT 1 FROM user_roles ur JOIN roles r ON r.role_id=ur.role_id AND r.tenant_id=ur.tenant_id AND r.status='ACTIVE'
                  JOIN role_permissions rp ON rp.role_id=r.role_id AND rp.tenant_id=r.tenant_id
                  WHERE ur.tenant_id=? AND ur.membership_id=? AND rp.permission_id=p.permission_id))
                """,(rs,row)->new Grant(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getObject(4,Long.class),
                rs.getString(5),rs.getString(6),rs.getBoolean(7)),isSuperUser(actor),actor.tenantId(),actor.membershipId());
        Map<Long,Grant> byId=candidates.stream().collect(Collectors.toMap(Grant::id,g->g));
        return candidates.stream().filter(g->g.valid(byId)).map(Grant::id).collect(Collectors.toSet());
    }

    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static ResponseStatusException denied() { return new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not have permission to perform this action."); }
    private record Endpoint(long id, String pattern, boolean isPublic) { }
    private record Grant(long id, String component, String kind, Long prerequisite, String viewComponent, String viewKind, boolean viewActive) {
        boolean valid(Map<Long, Grant> grants) {
            if ("VIEW".equals(kind)) return prerequisite == null;
            return prerequisite != null && viewActive && "VIEW".equals(viewKind) && component.equals(viewComponent)
                    && grants.containsKey(prerequisite) && "VIEW".equals(grants.get(prerequisite).kind())
                    && grants.get(prerequisite).prerequisite() == null;
        }
    }
}
