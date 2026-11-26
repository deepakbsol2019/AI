package com.book.user.security;

public record UserPrincipal(long userId, long tenantId, long membershipId, long sessionId,
        long securityVersion, long tenantAuthzVersion, long membershipAuthzVersion,
        String authorizationFingerprint) implements java.security.Principal {
    @Override public String getName() { return Long.toString(userId); }
}
