package com.book.user.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import com.book.user.exception.AuthException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** A bounded ingress limit complements persistent account lockout; proxies must supply trusted remote addresses. */
@Component
public class AuthRateLimiter {
    private record Window(Instant until, int attempts) { }
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;
    public AuthRateLimiter(Clock clock) { this.clock = clock; }
    public void check(String key, int maximum, Duration duration) {
        Instant now = clock.instant();
        if (windows.size() >= 10_000) windows.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().until()));
        if (windows.size() >= 10_000 && !windows.containsKey(key)) throw limited();
        Window result = windows.compute(key, (ignored, window) -> window == null || !now.isBefore(window.until())
                ? new Window(now.plus(duration), 1) : new Window(window.until(), Math.min(window.attempts() + 1, maximum + 1)));
        if (result.attempts() > maximum) throw limited();
    }
    private static AuthException limited() {
        return new AuthException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many authentication attempts. Please try again later.");
    }
}
