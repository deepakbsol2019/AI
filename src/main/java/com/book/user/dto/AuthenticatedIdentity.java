package com.book.user.dto;

import java.time.Instant;

/** A verified authentication result; session issuance rechecks the authoritative account. */
public record AuthenticatedIdentity(long userId, long tenantId, long membershipId,
        String authMethod, Long identityProviderId, Instant mfaCompletedAt, long securityVersion) { }
