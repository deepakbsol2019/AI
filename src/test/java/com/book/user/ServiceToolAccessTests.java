package com.book.user;

import com.book.tools.ServiceToolAccess;
import com.book.user.exception.AuthException;
import com.book.user.security.TenantContext;
import com.book.user.security.UserPrincipal;
import com.book.user.service.AuthorizationService;
import com.book.user.service.SessionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ServiceToolAccessTests {
    private final SessionService sessions = mock(SessionService.class);
    private final AuthorizationService authorization = mock(AuthorizationService.class);
    private final ServiceToolAccess access = new ServiceToolAccess(sessions, authorization);
    private final UserPrincipal actor = new UserPrincipal(4, 8, 12, 16, 1, 1, 1, "server-state");

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void rejectsMissingAuthenticationBeforeAnyDatabaseAccess() {
        assertThatThrownBy(() -> access.require("GET", "/api/user/profile/details")).isInstanceOf(AuthException.class);
        verifyNoInteractions(sessions, authorization);
    }

    @Test
    void rejectsMismatchedTrustedTenantBeforeAnyDatabaseAccess() {
        signedIn();
        try (var ignored = TenantContext.use(9, 4L, false)) {
            assertThatThrownBy(() -> access.require("GET", "/api/spreadsheets")).isInstanceOf(AuthException.class);
        }
        verifyNoInteractions(sessions, authorization);
    }

    @Test
    void revalidatesSessionAndAuthorizesExactOperation() {
        signedIn(); when(sessions.validate(actor)).thenReturn(actor);
        try (var ignored = TenantContext.use(8, 4L, false)) {
            assertThat(access.require("DELETE", "/api/books/27")).isEqualTo(actor);
        }
        var order = inOrder(sessions, authorization);
        order.verify(sessions).validate(actor);
        order.verify(authorization).authorize(actor, "DELETE", "/api/books/27");
    }

    @Test
    void revokedSessionCannotReachAuthorizationOrOperation() {
        signedIn(); when(sessions.validate(actor)).thenThrow(AuthException.unauthorized());
        try (var ignored = TenantContext.use(8, 4L, false)) {
            assertThatThrownBy(() -> access.require("GET", "/api/spreadsheets")).isInstanceOf(AuthException.class);
        }
        verifyNoInteractions(authorization);
    }

    private void signedIn() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor, null, List.of()));
    }
}
