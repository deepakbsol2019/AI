package com.book.tools;

import com.book.user.security.UserPrincipal;
import com.book.user.service.AuthorizationService;
import com.book.user.service.SessionService;
import org.springframework.stereotype.Component;

/** Reuses the HTTP boundary for service tools; this bean does not register or execute tools. */
@Component
public class ServiceToolAccess {
    private final SessionService sessions;
    private final AuthorizationService authorization;

    public ServiceToolAccess(SessionService sessions, AuthorizationService authorization) {
        this.sessions = sessions;
        this.authorization = authorization;
    }

    public UserPrincipal require(String method, String path) {
        var actor = sessions.validate(ToolCaller.require());
        authorization.authorize(actor, method, path);
        return actor;
    }
}
