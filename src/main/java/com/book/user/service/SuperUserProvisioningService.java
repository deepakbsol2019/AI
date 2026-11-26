package com.book.user.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import com.book.user.util.AuthRows;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Explicit operator provisioning only; ordinary registration never calls this service. */
@Service
public class SuperUserProvisioningService {
    public static final String ROLE_CODE = "SUPER_USER";
    private static final String ROLE_NAME = "Super User";
    private static final String ROLE_DESCRIPTION = "Application-wide access to assigned tenant data";
    private final JdbcTemplate jdbc;
    private final TenantProvisioningService tenants;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public SuperUserProvisioningService(JdbcTemplate jdbc, TenantProvisioningService tenants, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public ProvisionedSuperUser provision(String submittedEmail, String submittedName) {
        String email = PasswordService.normalizeEmail(submittedEmail);
        return Objects.requireNonNull(transactions.execute(status -> {
            List<Map<String, Object>> users = jdbc.queryForList(
                    "SELECT * FROM users WHERE primary_email=? FOR UPDATE", email);
            if (users.size() > 1) throw conflict("The operator account is ambiguous.");
            boolean created = users.isEmpty();
            Map<String, Object> user;
            if (created) {
                String name = submittedName == null || submittedName.isBlank() ? email : submittedName.strip();
                if (name.length() > 150) throw conflict("Supply a display name containing at most 150 characters.");
                var pending = tenants.create("INDIVIDUAL", "PASSWORD", email, name, null, null, false);
                user = jdbc.queryForMap("SELECT * FROM users WHERE user_id=? FOR UPDATE", pending.userId());
            } else {
                user = users.getFirst();
            }
            long userId = AuthRows.number(user, "user_id");
            List<Map<String, Object>> memberships = jdbc.queryForList("""
                    SELECT m.membership_id,m.tenant_id,m.status membership_status,
                           t.status tenant_status,t.login_policy
                      FROM tenant_memberships m JOIN tenants t ON t.tenant_id=m.tenant_id
                     WHERE m.user_id=? AND m.status IN ('ACTIVE','SUSPENDED') FOR UPDATE
                    """, userId);
            if (memberships.size() != 1 || !"ACTIVE".equals(memberships.getFirst().get("membership_status"))) {
                throw conflict("The operator account requires exactly one ACTIVE current membership.");
            }
            Map<String, Object> membership = memberships.getFirst();
            boolean pending = requireUsableAccount(user, membership);
            long tenantId = AuthRows.number(membership, "tenant_id");
            long membershipId = AuthRows.number(membership, "membership_id");
            Timestamp now = Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MICROS));
            List<Map<String, Object>> roles = jdbc.queryForList(
                    "SELECT * FROM roles WHERE tenant_id=? AND role_code=? FOR UPDATE", tenantId, ROLE_CODE);
            if (roles.size() > 1) throw conflict("The reserved superuser role is ambiguous.");
            boolean newRole = roles.isEmpty();
            long roleId;
            boolean metadataChanged = false;
            if (newRole) {
                roleId = createRole(tenantId, now);
            } else {
                Map<String, Object> role = roles.getFirst();
                if (!Boolean.TRUE.equals(role.get("is_system"))) {
                    throw conflict("The reserved SUPER_USER code belongs to a non-system role. Resolve the collision explicitly.");
                }
                roleId = AuthRows.number(role, "role_id");
                metadataChanged = !"ACTIVE".equals(role.get("status"))
                        || !ROLE_NAME.equals(role.get("display_name"))
                        || !ROLE_DESCRIPTION.equals(role.get("description"));
            }
            boolean grantsChanged = synchronizeGrants(tenantId, roleId, now);
            if (!newRole && (metadataChanged || grantsChanged)) {
                jdbc.update("""
                        UPDATE roles SET display_name=?,description=?,status='ACTIVE',authz_version=authz_version+1,
                          updated_at=? WHERE tenant_id=? AND role_id=?
                        """, ROLE_NAME, ROLE_DESCRIPTION, now, tenantId, roleId);
            }
            boolean assigned = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM user_roles WHERE tenant_id=? AND membership_id=? AND role_id=?
                    """, Long.class, tenantId, membershipId, roleId) == 1;
            if (!assigned) {
                jdbc.update("""
                        INSERT INTO user_roles(tenant_id,membership_id,role_id,granted_by_membership_id,granted_at)
                        VALUES (?,?,?,NULL,?)
                        """, tenantId, membershipId, roleId, now);
            }
            boolean changed = newRole || metadataChanged || grantsChanged || !assigned;
            if (changed) {
                jdbc.update("""
                        UPDATE tenant_memberships SET authz_version=authz_version+1,updated_at=?
                        WHERE tenant_id=? AND membership_id=? AND user_id=?
                        """, now, tenantId, membershipId, userId);
                jdbc.update("UPDATE auth_sessions SET revoked_at=? WHERE user_id=? AND revoked_at IS NULL", now, userId);
                jdbc.update("""
                        UPDATE refresh_tokens SET revoked_at=? WHERE revoked_at IS NULL AND session_id IN
                          (SELECT session_id FROM auth_sessions WHERE user_id=?)
                        """, now, userId);
                jdbc.update("""
                        INSERT INTO audit_events(tenant_id,actor_user_id,event_code,target_type,target_id,outcome,occurred_at)
                        VALUES (?,NULL,'SUPER_USER_GRANTED','USER',?,'SUCCESS',?)
                        """, tenantId, Long.toString(userId), now);
            }
            return new ProvisionedSuperUser(userId, tenantId, membershipId, created, changed, pending);
        }));
    }

    private boolean requireUsableAccount(Map<String, Object> user, Map<String, Object> membership) {
        if ("ACTIVE".equals(user.get("status")) && user.get("email_verified_at") != null
                && "ACTIVE".equals(membership.get("tenant_status"))) return false;
        if ("PENDING".equals(user.get("status")) && "PENDING".equals(membership.get("tenant_status"))
                && "PASSWORD".equals(membership.get("login_policy"))) {
            long userId = AuthRows.number(user, "user_id");
            if (jdbc.queryForObject("SELECT COUNT(*) FROM local_credentials WHERE user_id=?", Long.class, userId) == 0
                    && jdbc.queryForObject("SELECT COUNT(*) FROM external_identities WHERE user_id=?", Long.class, userId) == 0) return true;
        }
        throw conflict("The operator account must be verified and ACTIVE, or a normal pending PASSWORD account awaiting setup.");
    }

    private long createRole(long tenantId, Timestamp now) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO roles(tenant_id,role_code,display_name,description,is_system,status,authz_version,created_at,updated_at)
                    VALUES (?,?,?,?,TRUE,'ACTIVE',1,?,?)
                    """, new String[]{"role_id"});
            statement.setLong(1, tenantId);
            statement.setString(2, ROLE_CODE);
            statement.setString(3, ROLE_NAME);
            statement.setString(4, ROLE_DESCRIPTION);
            statement.setTimestamp(5, now);
            statement.setTimestamp(6, now);
            return statement;
        }, keys);
        return Objects.requireNonNull(keys.getKey(), "The database did not return the reserved role ID.").longValue();
    }

    private boolean synchronizeGrants(long tenantId, long roleId, Timestamp now) {
        Set<Long> eligible = new HashSet<>(jdbc.queryForList("""
                SELECT p.permission_id FROM permissions p WHERE p.is_active=TRUE
                  AND ((p.permission_kind='VIEW' AND p.prerequisite_permission_id IS NULL) OR
                    (p.permission_kind<>'VIEW' AND EXISTS
                      (SELECT 1 FROM permissions v WHERE v.permission_id=p.prerequisite_permission_id
                        AND v.component_code=p.component_code AND v.permission_kind='VIEW'
                        AND v.prerequisite_permission_id IS NULL AND v.is_active=TRUE)))
                """, Long.class));
        if (eligible.isEmpty()) throw new IllegalStateException("The active authorization permission catalog has not been installed.");
        Map<Long, Boolean> current = new HashMap<>();
        jdbc.query("SELECT permission_id,is_explicit FROM role_permissions WHERE tenant_id=? AND role_id=? FOR UPDATE",
                rs -> { current.put(rs.getLong("permission_id"), rs.getBoolean("is_explicit")); }, tenantId, roleId);
        boolean changed = false;
        for (Long permissionId : current.keySet()) {
            if (!eligible.contains(permissionId)) {
                jdbc.update("DELETE FROM role_permissions WHERE tenant_id=? AND role_id=? AND permission_id=?", tenantId, roleId, permissionId);
                changed = true;
            }
        }
        for (Long permissionId : eligible) {
            if (!current.containsKey(permissionId)) {
                jdbc.update("""
                        INSERT INTO role_permissions(tenant_id,role_id,permission_id,is_explicit,created_at,updated_at)
                        VALUES (?,?,?,TRUE,?,?)
                        """, tenantId, roleId, permissionId, now, now);
                changed = true;
            } else if (!current.get(permissionId)) {
                jdbc.update("UPDATE role_permissions SET is_explicit=TRUE,updated_at=? WHERE tenant_id=? AND role_id=? AND permission_id=?",
                        now, tenantId, roleId, permissionId);
                changed = true;
            }
        }
        return changed;
    }

    private static ResponseStatusException conflict(String detail) {
        return new ResponseStatusException(HttpStatus.CONFLICT, detail);
    }

    public record ProvisionedSuperUser(long userId, long tenantId, long membershipId,
            boolean created, boolean changed, boolean passwordSetupRequired) { }
}
