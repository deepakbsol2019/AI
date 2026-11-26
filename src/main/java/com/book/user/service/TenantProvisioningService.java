package com.book.user.service;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Locale;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Provisions one new workspace only. Existing-tenant joining and implicit account linking are forbidden. */
@Service
public class TenantProvisioningService {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final com.book.storage.TenantStoragePaths storage;

    public TenantProvisioningService(JdbcTemplate jdbc, Clock clock, com.book.storage.TenantStoragePaths storage) {
        this.jdbc = jdbc; this.clock = clock; this.storage = storage;
    }

    @Transactional
    public ProvisionedAccount create(String tenantType, String loginPolicy, String email, String displayName,
            String tenantCode, String organizationName, boolean verified) {
        String type = required(tenantType, 20, "Tenant type").toUpperCase(Locale.ROOT);
        if (!type.equals("INDIVIDUAL") && !type.equals("ORGANIZATION")) throw invalid("Tenant type must be Individual or Organization.");
        String policy = type.equals("INDIVIDUAL") ? "PASSWORD"
                : required(loginPolicy, 30, "Login type").toUpperCase(Locale.ROOT);
        if (!policy.equals("PASSWORD") && !policy.equals("SSO")) throw invalid("Organization login type must be Password or SSO.");
        String normalizedEmail = required(email, 320, "Email").toLowerCase(Locale.ROOT);
        if (!normalizedEmail.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw invalid("Enter a valid email address.");
        String name = required(displayName, 150, "Name");
        String workspaceName = type.equals("INDIVIDUAL") ? name : required(organizationName, 180, "Organization name");
        String code = tenantCode == null || tenantCode.isBlank()
                ? (type.equals("INDIVIDUAL") ? "individual-" : "organization-") + UUID.randomUUID()
                : required(tenantCode, 80, "Tenant code").toLowerCase(Locale.ROOT);
        if (!code.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{2,79}")) throw invalid("Tenant code must contain 3 to 80 letters, numbers, underscores or hyphens.");
        Timestamp now = Timestamp.from(clock.instant());
        String userStatus = verified ? "ACTIVE" : "PENDING";
        String tenantStatus = policy.equals("SSO") ? "PENDING_SETUP" : verified ? "ACTIVE" : "PENDING";
        try {
            long userId = insert("users", "user_id", "display_name,primary_email,email_verified_at,status,security_version,created_at,updated_at",
                    name, normalizedEmail, verified ? now : null, userStatus, 1L, now, now);
            long tenantId = insert("tenants", "tenant_id", "tenant_type,login_policy,owner_user_id,tenant_code,display_name,status,authz_version,created_at,updated_at",
                    type, policy, userId, code, workspaceName, tenantStatus, 1L, now, now);
            long membershipId = insert("tenant_memberships", "membership_id", "tenant_id,user_id,status,authz_version,joined_at,created_at,updated_at",
                    tenantId, userId, "ACTIVE", 1L, now, now, now);
            long roleId = insert("roles", "role_id", "tenant_id,role_code,display_name,description,is_system,status,authz_version,created_at,updated_at",
                    tenantId, "OWNER", "Workspace Owner", "Initial ordinary role; protected ADMIN is assigned by a super user", false, "ACTIVE", 1L, now, now);
            jdbc.update("INSERT INTO user_roles(tenant_id,membership_id,role_id,granted_by_membership_id,granted_at) VALUES(?,?,?,?,?)",
                    tenantId, membershipId, roleId, membershipId, now);
            int grants = jdbc.update("""
                    INSERT INTO role_permissions(tenant_id,role_id,permission_id,is_explicit,created_at,updated_at)
                    SELECT ?,?,p.permission_id,TRUE,?,? FROM permissions p
                     WHERE p.is_active=TRUE AND p.is_delegable=TRUE AND p.permission_code IN ('BOOKS.VIEW','BOOKS.UPLOAD','BOOKS.DOWNLOAD','BOOKS.EDIT','BOOKS.DELETE','ACCOUNT.VIEW') AND (p.permission_kind='VIEW' OR EXISTS
                       (SELECT 1 FROM permissions v WHERE v.permission_id=p.prerequisite_permission_id
                         AND v.component_code=p.component_code AND v.permission_kind='VIEW' AND v.is_active=TRUE))
                    """, tenantId, roleId, now, now);
            if (grants == 0) throw new IllegalStateException("The authorization permission catalog has not been installed.");
            // Do not report a registered organization as storage-ready when host provisioning fails.
            storage.provision(tenantId);
            return new ProvisionedAccount(userId, tenantId, membershipId);
        } catch (DataIntegrityViolationException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "An account or workspace already exists. Sign in or use the verified account-recovery flow.", conflict);
        }
    }

    private long insert(String table, String idColumn, String columns, Object... values) {
        String sql = "INSERT INTO " + table + "(" + columns + ") VALUES(" + "?,".repeat(values.length - 1) + "?)";
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, new String[]{idColumn});
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            return statement;
        }, key);
        Number result = key.getKey();
        if (result == null) throw new IllegalStateException("The database did not return the new record ID.");
        return result.longValue();
    }

    private static String required(String value, int max, String label) {
        if (value == null || value.isBlank()) throw invalid(label + " is required.");
        String result = value.strip();
        if (result.length() > max) throw invalid(label + " must contain at most " + max + " characters.");
        return result;
    }
    private static ResponseStatusException invalid(String detail) { return new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, detail); }
    public record ProvisionedAccount(long userId, long tenantId, long membershipId) { }
}
