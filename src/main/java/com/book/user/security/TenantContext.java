package com.book.user.security;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Request-scoped, trusted tenant identity. Never populate this value from a client tenant parameter. */
public final class TenantContext {
    private static final ThreadLocal<AccessScope> CURRENT = new ThreadLocal<>();
    private TenantContext() { }

    public static Long currentTenantId() {
        AccessScope scope = CURRENT.get();
        return scope == null ? null : scope.tenantId();
    }

    public static boolean isSuperUser() {
        AccessScope scope = CURRENT.get();
        return scope != null && scope.superUser();
    }

    public static long requireTenantId() {
        Long id = currentTenantId();
        if (id == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required.");
        return id;
    }

    public static void requireOwned(Long ownerTenantId) {
        long current = requireTenantId();
        if (ownerTenantId == null || ownerTenantId <= 0 || ownerTenantId != current) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "The requested record was not found.");
        }
    }

    public static Scope use(long tenantId) {
        return use(tenantId, false);
    }

    /** Only the authenticated server-side security filter may grant application-wide access. */
    public static Scope use(long tenantId, boolean superUser) {
        if (tenantId <= 0) throw new IllegalArgumentException("A positive trusted tenant ID is required.");
        AccessScope previous = CURRENT.get();
        CURRENT.set(new AccessScope(tenantId, superUser));
        return new Scope(previous, Thread.currentThread());
    }

    private record AccessScope(long tenantId, boolean superUser) { }

    public static final class Scope implements AutoCloseable {
        private final AccessScope previous;
        private final Thread owner;
        private boolean closed;
        private Scope(AccessScope previous, Thread owner) { this.previous = previous; this.owner = owner; }
        @Override public void close() {
            if (closed) return;
            if (owner != Thread.currentThread()) throw new IllegalStateException("Tenant scope must close on its owning thread.");
            closed = true;
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }
}
