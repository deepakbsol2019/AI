package com.book.user.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Account mutations participate in the caller's transaction and lock the user before its tokens. */
@Repository
public class AccountRepository {
    private final JdbcTemplate jdbc;
    private static final String ACCOUNT = """
            SELECT u.user_id,u.primary_email,u.display_name,u.status user_status,u.security_version,u.deleted_at,
              u.email_verified_at,t.tenant_id,t.tenant_code,t.tenant_type,t.login_policy,t.status tenant_status,
              m.membership_id,m.status membership_status,c.password_hash,c.failed_attempts,c.locked_until
            FROM users u JOIN tenant_memberships m ON m.user_id=u.user_id AND m.status IN ('ACTIVE','SUSPENDED')
            JOIN tenants t ON t.tenant_id=m.tenant_id LEFT JOIN local_credentials c ON c.user_id=u.user_id
            """;

    public AccountRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<Account> byEmail(String email, boolean lock) {
        return one(jdbc.query(ACCOUNT + " WHERE u.primary_email=?" + (lock ? " FOR UPDATE" : ""),
                AccountRepository::account, email));
    }

    public Optional<Account> byId(long id, boolean lock) {
        return one(jdbc.query(ACCOUNT + " WHERE u.user_id=?" + (lock ? " FOR UPDATE" : ""),
                AccountRepository::account, id));
    }

    public boolean emailExists(String email) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE primary_email=?", Long.class, email) > 0;
    }

    public void failedPassword(Account account, int maximum, java.time.Duration lockout, Instant now) {
        int attempts = account.lockedUntil() != null && !now.isBefore(account.lockedUntil())
                ? 1 : account.failedAttempts() + 1;
        jdbc.update("UPDATE local_credentials SET failed_attempts=?,locked_until=?,updated_at=? WHERE user_id=?",
                attempts, attempts >= maximum ? timestamp(now.plus(lockout)) : null, timestamp(now), account.userId());
        audit(account.tenantId(), account.userId(), "PASSWORD_LOGIN", "FAILURE", now);
    }

    public void clearFailures(long userId, Instant now) {
        jdbc.update("UPDATE local_credentials SET failed_attempts=0,locked_until=NULL,updated_at=? WHERE user_id=?",
                timestamp(now), userId);
    }

    public void issueCredential(long userId, String purpose, String email, byte[] digest, long version,
            Long providerId, Instant now, Instant expiry) {
        jdbc.update("""
                UPDATE credential_tokens SET revoked_at=? WHERE user_id=? AND purpose=?
                AND consumed_at IS NULL AND revoked_at IS NULL
                """, timestamp(now), userId, purpose);
        jdbc.update("""
                INSERT INTO credential_tokens(user_id,purpose,target_email,token_digest,issued_security_version,
                  identity_provider_id,expires_at,created_at) VALUES (?,?,?,?,?,?,?,?)
                """, userId, purpose, email, digest, version, providerId, timestamp(expiry), timestamp(now));
    }

    public Optional<CredentialToken> token(byte[] digest, boolean lock) {
        return one(jdbc.query("""
                SELECT token_id,user_id,purpose,target_email,issued_security_version,identity_provider_id,
                  expires_at,consumed_at,revoked_at FROM credential_tokens WHERE token_digest=?
                """ + (lock ? " FOR UPDATE" : ""), (rs, row) -> new CredentialToken(rs.getLong("token_id"),
                rs.getLong("user_id"), rs.getString("purpose"), rs.getString("target_email"),
                rs.getLong("issued_security_version"), rs.getObject("identity_provider_id", Long.class),
                instant(rs,"expires_at"), instant(rs,"consumed_at"), instant(rs,"revoked_at")), digest));
    }

    public boolean consume(long tokenId, Instant now) {
        return jdbc.update("""
                UPDATE credential_tokens SET consumed_at=? WHERE token_id=? AND consumed_at IS NULL
                AND revoked_at IS NULL AND expires_at>?
                """, timestamp(now), tokenId, timestamp(now)) == 1;
    }

    public void changePassword(Account account, String hash, boolean setup, Instant now) {
        int changed = jdbc.update("""
                UPDATE local_credentials SET password_hash=?,password_changed_at=?,failed_attempts=0,
                  locked_until=NULL,updated_at=? WHERE user_id=?
                """, hash, timestamp(now), timestamp(now), account.userId());
        if (changed == 0) jdbc.update("""
                INSERT INTO local_credentials(user_id,password_hash,password_changed_at,failed_attempts,created_at,updated_at)
                VALUES (?,?,?,0,?,?)
                """, account.userId(), hash, timestamp(now), timestamp(now), timestamp(now));
        if (setup) activate(account, now);
        securityChanged(account.userId(), now);
        audit(account.tenantId(),account.userId(),setup ? "PASSWORD_SETUP" : "PASSWORD_RESET","SUCCESS",now);
    }

    public void activate(Account account, Instant now) {
        jdbc.update("UPDATE users SET status='ACTIVE',email_verified_at=COALESCE(email_verified_at,?),updated_at=? WHERE user_id=?",
                timestamp(now),timestamp(now),account.userId());
        jdbc.update("UPDATE tenants SET status='ACTIVE',updated_at=? WHERE tenant_id=? AND status IN ('PENDING','PENDING_SETUP')",
                timestamp(now),account.tenantId());
    }

    public void securityChanged(long userId, Instant now) {
        jdbc.update("UPDATE users SET security_version=security_version+1,updated_at=? WHERE user_id=?", timestamp(now),userId);
        jdbc.update("UPDATE credential_tokens SET revoked_at=? WHERE user_id=? AND consumed_at IS NULL AND revoked_at IS NULL",
                timestamp(now),userId);
        jdbc.update("UPDATE refresh_tokens SET revoked_at=? WHERE session_id IN (SELECT session_id FROM auth_sessions WHERE user_id=?) AND revoked_at IS NULL",
                timestamp(now),userId);
        jdbc.update("UPDATE auth_sessions SET revoked_at=? WHERE user_id=? AND revoked_at IS NULL",timestamp(now),userId);
    }

    public void audit(Long tenantId, Long userId, String code, String outcome, Instant now) {
        jdbc.update("INSERT INTO audit_events(tenant_id,actor_user_id,event_code,outcome,occurred_at) VALUES (?,?,?,?,?)",
                tenantId,userId,code,outcome,timestamp(now));
    }

    public JdbcTemplate jdbc() { return jdbc; }
    public static Timestamp timestamp(Instant instant) { return instant == null ? null : Timestamp.from(instant); }
    private static Instant instant(ResultSet rs,String name) throws SQLException {
        Timestamp value=rs.getTimestamp(name); return value == null ? null : value.toInstant();
    }
    private static <T> Optional<T> one(List<T> values) { return values.stream().findFirst(); }
    private static Account account(ResultSet rs,int row) throws SQLException {
        return new Account(rs.getLong("user_id"),rs.getString("primary_email"),rs.getString("display_name"),
                rs.getString("user_status"),rs.getLong("security_version"),instant(rs,"email_verified_at"),
                rs.getLong("tenant_id"),rs.getString("tenant_code"),rs.getString("tenant_type"),
                rs.getString("login_policy"),rs.getString("tenant_status"),rs.getLong("membership_id"),
                rs.getString("membership_status"),rs.getString("password_hash"),rs.getInt("failed_attempts"),instant(rs,"locked_until"),instant(rs,"deleted_at"));
    }
    public record Account(long userId,String email,String displayName,String userStatus,long securityVersion,
            Instant emailVerifiedAt,long tenantId,String tenantCode,String tenantType,String loginPolicy,
            String tenantStatus,long membershipId,String membershipStatus,String passwordHash,int failedAttempts,Instant lockedUntil,Instant deletedAt) {
        @Override public String toString() { return "Account[userId="+userId+",tenantId="+tenantId+",credentials=REDACTED,profile=REDACTED]"; }
        public boolean active() { return "ACTIVE".equals(userStatus) && "ACTIVE".equals(tenantStatus)
                    && "ACTIVE".equals(membershipStatus) && emailVerifiedAt != null && deletedAt == null; }
        public boolean passwordPolicy() { return "PASSWORD".equals(loginPolicy); }
    }
    public record CredentialToken(long tokenId,long userId,String purpose,String email,long version,Long providerId,
            Instant expiresAt,Instant consumedAt,Instant revokedAt) {
        @Override public String toString() { return "CredentialToken[id="+tokenId+",purpose="+purpose+",target=REDACTED]"; }
        public boolean valid(Account account,String expected,Instant now) {
            return expected.equals(purpose) && userId == account.userId() && version == account.securityVersion()
                    && email.equals(account.email()) && consumedAt == null && revokedAt == null && now.isBefore(expiresAt);
        }
    }
}
