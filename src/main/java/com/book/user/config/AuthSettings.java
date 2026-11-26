package com.book.user.config;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class AuthSettings {
    private final Environment environment;
    public AuthSettings(Environment environment) { this.environment = environment; }
    public String issuer() { return environment.getProperty("app.user.jwt.issuer", "book-search-download"); }
    public String audience() { return environment.getProperty("app.user.jwt.audience", "book-search-ui"); }
    public String signingSecret() { return environment.getProperty("app.user.jwt.secret", ""); }
    public boolean secureCookie() { return environment.getProperty("app.user.cookie.secure", Boolean.class, true); }
    public String publicBaseUrl() {
        return environment.getProperty("app.user.public-base-url", "http://localhost:4200").replaceAll("/+$", "");
    }
    public List<String> trustedOrigins() {
        return Arrays.stream(environment.getProperty("app.user.trusted-origins", publicBaseUrl()).split(","))
                .map(String::strip).filter(value -> !value.isEmpty()).map(value -> {
                    URI uri = URI.create(value);
                    if (uri.getHost() == null || (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme()))
                            || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getUserInfo() != null
                            || (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath()))) {
                        throw new IllegalStateException("Trusted origins must be exact HTTP(S) origins without paths");
                    }
                    return value.replaceAll("/+$", "");
                }).toList();
    }
    public boolean googleEnabled() { return environment.getProperty("app.user.google.enabled", Boolean.class, false); }
    public boolean ssoEnabled() { return environment.getProperty("app.user.sso.enabled", Boolean.class, false); }
}
