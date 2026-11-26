package com.book.user.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import com.book.user.config.AuthSettings;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class AuthCookies {
    public static final String REFRESH_COOKIE = "BOOK_REFRESH";
    public static final String AUTH_PATH = "/api/user/auth";
    private final AuthSettings settings;
    private final Clock clock;
    public AuthCookies(AuthSettings settings, Clock clock) { this.settings = settings; this.clock = clock; }
    public void write(HttpServletResponse response, String secret, Instant expiry) {
        long remainingMillis = Duration.between(clock.instant(), expiry).toMillis();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(secret,
                Math.max(0, (remainingMillis + 999) / 1000)).toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader("Pragma", "no-cache");
    }
    public void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("", 0).toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }
    public String read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        String value = null;
        for (Cookie cookie : cookies) if (REFRESH_COOKIE.equals(cookie.getName())) {
            if (value != null) return null; // Reject ambiguous cookie paths rather than choosing a secret.
            value = cookie.getValue();
        }
        return value;
    }
    private ResponseCookie cookie(String secret, long seconds) {
        return ResponseCookie.from(REFRESH_COOKIE, secret).httpOnly(true).secure(settings.secureCookie())
                .sameSite("Lax").path(AUTH_PATH).maxAge(seconds).build();
    }
}
