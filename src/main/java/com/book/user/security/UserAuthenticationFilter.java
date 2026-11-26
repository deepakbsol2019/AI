package com.book.user.security;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import com.book.user.exception.AuthException;
import com.book.user.service.AccessTokenService;
import com.book.user.service.AuthorizationService;
import com.book.user.service.SessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

/** Never trusts a servlet/OIDC handshake session as an application API authentication. */
public class UserAuthenticationFilter extends OncePerRequestFilter {
    private static final Set<String> PUBLIC_GET = Set.of("/api/user/auth/features", "/api/user/auth/csrf", "/api/user/auth/oidc/google");
    private static final Set<String> PUBLIC_POST = Set.of("/api/user/auth/login", "/api/user/auth/register",
            "/api/user/auth/refresh", "/api/user/auth/logout", "/api/user/auth/password/forgot",
            "/api/user/auth/password/setup", "/api/user/auth/password/reset", "/api/user/auth/oidc/sso");
    private final AccessTokenService tokens;
    private final SessionService sessions;
    private final AuthorizationService authorization;
    private final AuthRateLimiter limits;
    private final AuthCookies cookies;
    public UserAuthenticationFilter(AccessTokenService tokens, SessionService sessions,
            AuthorizationService authorization, AuthRateLimiter limits, AuthCookies cookies) {
        this.tokens = tokens; this.sessions = sessions; this.authorization = authorization;
        this.limits = limits; this.cookies = cookies;
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getServletPath();
        if (path == null || path.isEmpty()) path = request.getRequestURI().substring(request.getContextPath().length());
        if (!path.startsWith("/api/")) { chain.doFilter(request, response); return; }
        try {
            response.setHeader("Cache-Control", "no-store");
            if (isPublic(request.getMethod(), path)) {
                throttle(request, path);
                chain.doFilter(request, response);
                return;
            }
            String header = request.getHeader("Authorization");
            if (header == null || !header.startsWith("Bearer ") || header.indexOf(',', 7) >= 0) throw AuthException.unauthorized();
            UserPrincipal principal = sessions.validate(tokens.verify(header.substring(7)));
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
            authorization.authorize(principal, request.getMethod(), path);
            try (var ignored = TenantContext.use(principal.tenantId(), principal.userId(), authorization.isSuperUser(principal))) {
                chain.doFilter(request, response);
            }
        } catch (AuthException failure) {
            if (path.equals("/api/user/auth/refresh")) cookies.clear(response);
            AuthErrors.write(response, failure);
        } catch (ResponseStatusException failure) {
            int status = failure.getStatusCode().value();
            AuthErrors.write(response, status, status == 403 ? "FORBIDDEN" : "AUTHENTICATION_REQUIRED",
                    failure.getReason() == null ? "This request is not permitted." : failure.getReason());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
    public static boolean isPublic(String method, String path) {
        return ("GET".equals(method) && PUBLIC_GET.contains(path)) || ("POST".equals(method) && PUBLIC_POST.contains(path));
    }
    private void throttle(HttpServletRequest request, String path) {
        if (!"POST".equals(request.getMethod())) return;
        int maximum;
        Duration window;
        if (path.endsWith("/register") || path.endsWith("/password/forgot")) { maximum = 5; window = Duration.ofMinutes(15); }
        else if (path.endsWith("/login") || path.contains("/password/") || path.endsWith("/oidc/sso")) { maximum = 20; window = Duration.ofMinutes(1); }
        else return;
        limits.check(path + ":" + request.getRemoteAddr(), maximum, window);
    }
}
