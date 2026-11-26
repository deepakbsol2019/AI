package com.book.user.service;

import java.sql.Timestamp;
import java.time.Clock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SecurityAuditService {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public SecurityAuditService(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }
    public void record(Long tenantId, Long userId, String event, String outcome, Long sessionId) {
        jdbc.update("""
                INSERT INTO audit_events(tenant_id,actor_user_id,event_code,target_type,target_id,outcome,occurred_at)
                VALUES(?,?,?,?,?,?,?)
                """, tenantId, userId, event, sessionId == null ? null : "AUTH_SESSION",
                sessionId == null ? null : Long.toString(sessionId), outcome, Timestamp.from(clock.instant()));
    }
}
