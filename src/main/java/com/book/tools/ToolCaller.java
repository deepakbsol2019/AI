package com.book.tools;

import com.book.user.exception.AuthException;
import com.book.user.security.TenantContext;
import com.book.user.security.UserPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;

/** Only server-established request identity may supply an annotated operation's actor. */
public final class ToolCaller {
    private ToolCaller() { }

    public static UserPrincipal require() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof UserPrincipal actor)) {
            throw AuthException.unauthorized();
        }
        if (actor.tenantId() != TenantContext.requireTenantId()
                || actor.userId() != TenantContext.requireUserId()) {
            throw AuthException.unauthorized();
        }
        return actor;
    }
}
