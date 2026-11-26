package com.book.user.dto;

import java.time.Instant;

/** The refresh secret is deliberately absent; it is delivered only in an HttpOnly cookie. */
public record SessionResponse(String accessToken, String tokenType, Instant accessExpiresAt,
        Instant refreshExpiresAt, Instant absoluteExpiresAt, Instant serverTime, long sessionId,
        SessionUser user) {
    @Override public String toString() { return "SessionResponse[REDACTED]"; }
    public record SessionUser(long userId, long tenantId, long membershipId,
            String email, String displayName, String tenantName) { }
}
