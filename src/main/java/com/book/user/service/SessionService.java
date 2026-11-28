package com.book.user.service;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import com.book.user.config.AuthSettings;
import com.book.user.dto.AuthenticatedIdentity;
import com.book.user.dto.SessionResponse;
import com.book.user.dto.SessionResponse.SessionUser;
import com.book.user.exception.AuthException;
import com.book.user.security.UserPrincipal;
import com.book.user.util.AuthRows;
import com.book.user.util.SecretDigests;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Database-authoritative sessions; no normal request extends the refresh deadline. */
@Service
public class SessionService {
    public record CurrentSessionStatus(String status, String authenticationMethod, boolean mfaCompleted,
            Instant refreshExpiresAt, Instant absoluteExpiresAt) { }

    @org.springframework.ai.tool.annotation.Tool(name = "currentSessionStatus", description = "Validate the signed-in user's current session and report login method, MFA completion and expiry times for access troubleshooting. Never accepts or returns credentials or session secrets.")
    @Transactional(readOnly = true)
    public CurrentSessionStatus currentSessionStatus() {
        var actor = validate(com.book.tools.ToolCaller.require());
        authorization.authorize(actor, "GET", "/api/user/profile/details");
        var session = one("SELECT auth_method,mfa_completed_at,refresh_expires_at,absolute_expires_at FROM auth_sessions WHERE session_id=?", actor.sessionId());
        return new CurrentSessionStatus("AUTHENTICATED", (String) session.get("auth_method"),
                AuthRows.instant(session, "mfa_completed_at") != null,
                AuthRows.instant(session, "refresh_expires_at"), AuthRows.instant(session, "absolute_expires_at"));
    }

    private static final String ACCOUNT = """
            SELECT u.user_id,u.primary_email,u.display_name,u.status user_status,u.email_verified_at,u.deleted_at,
              u.security_version current_security_version,t.tenant_id,t.display_name tenant_name,
              t.tenant_type,t.login_policy,t.status tenant_status,t.authz_version current_tenant_version,
              m.membership_id,m.status membership_status,m.authz_version current_membership_version
            FROM users u JOIN tenant_memberships m ON m.user_id=u.user_id
            JOIN tenants t ON t.tenant_id=m.tenant_id
            """;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AccessTokenService tokens;
    private final AuthorizationService authorization;
    private final SecurityAuditService audit;
    private final AuthSettings settings;

    public SessionService(JdbcTemplate jdbc, Clock clock, AccessTokenService tokens,
            AuthorizationService authorization, SecurityAuditService audit, AuthSettings settings) {
        this.jdbc = jdbc; this.clock = clock; this.tokens = tokens; this.authorization = authorization;
        this.audit = audit; this.settings = settings;
    }
    public record IssuedSession(SessionResponse response, String refreshToken) {
        @Override public String toString() { return "IssuedSession[REDACTED]"; }
    }

    @Transactional
    public IssuedSession issue(AuthenticatedIdentity identity) {
        tokens.requireConfigured();
        lockUser(identity.userId());
        Map<String, Object> account = one(ACCOUNT + " WHERE u.user_id=? AND t.tenant_id=? AND m.membership_id=? FOR UPDATE",
                identity.userId(), identity.tenantId(), identity.membershipId());
        requireActive(account);
        if (AuthRows.number(account, "current_security_version") != identity.securityVersion()) throw AuthException.unauthorized();
        requireMethod(account, identity.authMethod(), identity.identityProviderId());
        if (identity.mfaCompletedAt() == null && hasMfa(identity.userId())) {
            throw new AuthException(HttpStatus.UNAUTHORIZED, "MFA_REQUIRED", "Complete your configured authentication factor before login.");
        }
        Instant now = now();
        String fingerprint = authorization.authorizationFingerprint(identity.tenantId(), identity.membershipId());
        long sessionId = insert("""
                INSERT INTO auth_sessions(user_id,tenant_id,membership_id,auth_method,identity_provider_id,
                  mfa_completed_at,user_security_version,tenant_authz_version,membership_authz_version,
                  authorization_fingerprint,created_at,last_seen_at,refresh_expires_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, identity.userId(), identity.tenantId(), identity.membershipId(), identity.authMethod(),
                identity.identityProviderId(), timestamp(identity.mfaCompletedAt()), identity.securityVersion(),
                AuthRows.number(account, "current_tenant_version"), AuthRows.number(account, "current_membership_version"),
                fingerprint, timestamp(now), timestamp(now), timestamp(now.plusSeconds(600)));
        String secret = SecretDigests.randomSecret();
        insertRefresh(sessionId, null, secret, now);
        var principal = new UserPrincipal(identity.userId(), identity.tenantId(), identity.membershipId(), sessionId,
                identity.securityVersion(), AuthRows.number(account, "current_tenant_version"),
                AuthRows.number(account, "current_membership_version"), fingerprint);
        audit.record(identity.tenantId(), identity.userId(), "LOGIN", "SUCCESS", sessionId);
        return new IssuedSession(response(principal, account, now, now.plusSeconds(600), null), secret);
    }

    /** Authentication errors must not roll back a detected replay's committed family revocation. */
    @Transactional(noRollbackFor = AuthException.class)
    public IssuedSession refresh(String secret) {
        Map<String, Object> presented = presented(secret);
        if (presented == null) {
            audit.record(null, null, "REFRESH_FAILURE", "FAILURE", null);
            throw AuthException.unauthorized();
        }
        long sessionId = AuthRows.number(presented, "session_id");
        lockUser(AuthRows.number(presented, "user_id"));
        Map<String, Object> session = one("SELECT * FROM auth_sessions WHERE session_id=? FOR UPDATE", sessionId);
        Map<String, Object> token = one("SELECT * FROM refresh_tokens WHERE token_digest=? FOR UPDATE", SecretDigests.digest(secret));
        Instant now = now();
        if (AuthRows.instant(token, "consumed_at") != null) {
            revoke(session, now, "REFRESH_REUSE");
            throw AuthException.unauthorized();
        }
        try {
            if (AuthRows.instant(token, "revoked_at") != null || !now.isBefore(AuthRows.instant(token, "expires_at"))) {
                throw AuthException.unauthorized();
            }
            Map<String, Object> account = currentAccount(session);
            validateState(session, account, now);
            int consumed = jdbc.update("""
                    UPDATE refresh_tokens SET consumed_at=? WHERE refresh_token_id=? AND consumed_at IS NULL
                    AND revoked_at IS NULL AND expires_at>?
                    """, timestamp(now), AuthRows.number(token, "refresh_token_id"), timestamp(now));
            if (consumed != 1) throw AuthException.unauthorized();
            String rotated = SecretDigests.randomSecret();
            insertRefresh(sessionId, AuthRows.number(token, "refresh_token_id"), rotated, now);
            jdbc.update("UPDATE auth_sessions SET refresh_expires_at=?,last_seen_at=? WHERE session_id=?",
                    timestamp(now.plusSeconds(600)), timestamp(now), sessionId);
            UserPrincipal principal = principal(session);
            audit.record(principal.tenantId(), principal.userId(), "REFRESH", "SUCCESS", sessionId);
            return new IssuedSession(response(principal, account, now, now.plusSeconds(600),
                    AuthRows.instant(session, "absolute_expires_at")), rotated);
        } catch (AuthException failure) {
            revoke(session, now, "REFRESH_FAILURE");
            throw failure;
        }
    }

    @Transactional(readOnly = true)
    public UserPrincipal validate(UserPrincipal submitted) {
        Map<String, Object> session = one("SELECT * FROM auth_sessions WHERE session_id=?", submitted.sessionId());
        validateState(session, currentAccount(session), clock.instant());
        if (!principal(session).equals(submitted)) throw AuthException.unauthorized();
        return submitted;
    }

    @Transactional(readOnly = true)
    public SessionUser user(UserPrincipal principal) {
        var account = one(ACCOUNT + " WHERE u.user_id=? AND t.tenant_id=? AND m.membership_id=?",
                principal.userId(), principal.tenantId(), principal.membershipId());
        requireActive(account);
        return user(account);
    }

    @Transactional
    public void logout(String secret) {
        var presented = presented(secret);
        if (presented == null) return;
        lockUser(AuthRows.number(presented, "user_id"));
        var session = one("SELECT * FROM auth_sessions WHERE session_id=? FOR UPDATE", AuthRows.number(presented, "session_id"));
        revoke(session, now(), "LOGOUT");
    }

    private void validateState(Map<String, Object> session, Map<String, Object> account, Instant now) {
        requireActive(account);
        Instant absolute = AuthRows.instant(session, "absolute_expires_at");
        if (AuthRows.instant(session, "revoked_at") != null || !now.isBefore(AuthRows.instant(session, "refresh_expires_at"))
                || (absolute != null && !now.isBefore(absolute))
                || AuthRows.number(session, "user_security_version") != AuthRows.number(account, "current_security_version")
                || AuthRows.number(session, "tenant_authz_version") != AuthRows.number(account, "current_tenant_version")
                || AuthRows.number(session, "membership_authz_version") != AuthRows.number(account, "current_membership_version")
                || !session.get("authorization_fingerprint").equals(authorization.authorizationFingerprint(
                        AuthRows.number(session, "tenant_id"), AuthRows.number(session, "membership_id")))) {
            throw AuthException.unauthorized();
        }
        requireMethod(account, (String) session.get("auth_method"), AuthRows.nullableNumber(session, "identity_provider_id"));
        if (AuthRows.instant(session, "mfa_completed_at") == null && hasMfa(AuthRows.number(session, "user_id"))) {
            throw AuthException.unauthorized();
        }
    }
    private void requireMethod(Map<String, Object> account, String method, Long providerId) {
        if ("PASSWORD".equals(method)) {
            if (!"PASSWORD".equals(account.get("login_policy")) || providerId != null
                    || jdbc.queryForObject("SELECT COUNT(*) FROM local_credentials WHERE user_id=?", Long.class,
                            AuthRows.number(account, "user_id")) != 1) throw AuthException.unauthorized();
            return;
        }
        if (providerId == null || (!"GOOGLE".equals(method) && !"SSO".equals(method))) throw AuthException.unauthorized();
        var provider = one("SELECT * FROM identity_providers WHERE identity_provider_id=?", providerId);
        if (!"ACTIVE".equals(provider.get("status"))) throw AuthException.unauthorized();
        if ("GOOGLE".equals(method)) {
            if (!settings.googleEnabled() || !"GOOGLE".equals(provider.get("provider_type"))
                    || provider.get("tenant_id") != null || !"INDIVIDUAL".equals(account.get("tenant_type"))) throw AuthException.unauthorized();
        } else if (!settings.ssoEnabled() || !"ORG_OIDC".equals(provider.get("provider_type"))
                || !Long.valueOf(AuthRows.number(account, "tenant_id")).equals(AuthRows.nullableNumber(provider, "tenant_id"))
                || !"SSO".equals(account.get("login_policy"))) throw AuthException.unauthorized();
        if (jdbc.queryForObject("SELECT COUNT(*) FROM external_identities WHERE identity_provider_id=? AND user_id=?",
                Long.class, providerId, AuthRows.number(account, "user_id")) != 1) throw AuthException.unauthorized();
    }
    private boolean hasMfa(long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM local_mfa_factors WHERE user_id=? AND status='ACTIVE' AND confirmed_at IS NOT NULL", Long.class, userId) > 0;
    }
    private Map<String, Object> currentAccount(Map<String, Object> session) {
        return one(ACCOUNT + " WHERE u.user_id=? AND t.tenant_id=? AND m.membership_id=?",
                AuthRows.number(session, "user_id"), AuthRows.number(session, "tenant_id"), AuthRows.number(session, "membership_id"));
    }
    private void requireActive(Map<String, Object> account) {
        if (!"ACTIVE".equals(account.get("user_status")) || account.get("deleted_at") != null || account.get("email_verified_at") == null
                || !"ACTIVE".equals(account.get("tenant_status")) || !"ACTIVE".equals(account.get("membership_status"))) throw AuthException.unauthorized();
    }
    private SessionResponse response(UserPrincipal principal, Map<String, Object> account, Instant now, Instant refreshExpiry, Instant absolute) {
        return new SessionResponse(tokens.issue(principal, now), "Bearer", now.truncatedTo(ChronoUnit.SECONDS).plusSeconds(300), refreshExpiry,
                absolute, now, principal.sessionId(), user(account));
    }
    private SessionUser user(Map<String, Object> account) {
        return new SessionUser(AuthRows.number(account, "user_id"), AuthRows.number(account, "tenant_id"),
                AuthRows.number(account, "membership_id"), (String) account.get("primary_email"),
                (String) account.get("display_name"), (String) account.get("tenant_name"));
    }
    private UserPrincipal principal(Map<String, Object> session) {
        return new UserPrincipal(AuthRows.number(session, "user_id"), AuthRows.number(session, "tenant_id"),
                AuthRows.number(session, "membership_id"), AuthRows.number(session, "session_id"),
                AuthRows.number(session, "user_security_version"), AuthRows.number(session, "tenant_authz_version"),
                AuthRows.number(session, "membership_authz_version"), (String) session.get("authorization_fingerprint"));
    }
    private void insertRefresh(long sessionId, Long parent, String secret, Instant now) {
        jdbc.update("INSERT INTO refresh_tokens(session_id,parent_refresh_token_id,token_digest,issued_at,expires_at) VALUES(?,?,?,?,?)",
                sessionId, parent, SecretDigests.digest(secret), timestamp(now), timestamp(now.plusSeconds(600)));
    }
    private void revoke(Map<String, Object> session, Instant now, String event) {
        long sessionId = AuthRows.number(session, "session_id");
        jdbc.update("UPDATE auth_sessions SET revoked_at=COALESCE(revoked_at,?) WHERE session_id=?", timestamp(now), sessionId);
        jdbc.update("UPDATE refresh_tokens SET revoked_at=COALESCE(revoked_at,?) WHERE session_id=?", timestamp(now), sessionId);
        audit.record(AuthRows.number(session, "tenant_id"), AuthRows.number(session, "user_id"), event,
                "LOGOUT".equals(event) ? "SUCCESS" : "FAILURE", sessionId);
    }
    private Map<String, Object> presented(String secret) {
        if (secret == null || !secret.matches("[A-Za-z0-9_-]{43}")) return null;
        var rows = jdbc.queryForList("SELECT r.session_id,s.user_id FROM refresh_tokens r JOIN auth_sessions s ON s.session_id=r.session_id WHERE r.token_digest=?", SecretDigests.digest(secret));
        return rows.size() == 1 ? rows.getFirst() : null;
    }
    private void lockUser(long userId) { one("SELECT user_id FROM users WHERE user_id=? FOR UPDATE", userId); }
    private Map<String, Object> one(String sql, Object... arguments) {
        var rows = jdbc.queryForList(sql, arguments);
        if (rows.size() != 1) throw AuthException.unauthorized();
        return rows.getFirst();
    }
    private long insert(String sql, Object... arguments) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < arguments.length; i++) statement.setObject(i + 1, arguments[i]);
            return statement;
        }, keys);
        return java.util.Objects.requireNonNull(keys.getKey()).longValue();
    }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
    private static Timestamp timestamp(Instant instant) { return instant == null ? null : Timestamp.from(instant); }
}
