package com.book.user;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.Properties;

import com.book.user.config.AuthSettings;
import com.book.user.exception.AuthException;
import com.book.user.security.UserPrincipal;
import com.book.user.service.AccessTokenService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises the real application's imports and placeholder using disposable keys and config trees. */
class LocalSigningConfigurationTests {
    @TempDir Path temporary;

    private static final String DURABLE = fixtureKey('d');
    private static final String LEGACY = fixtureKey('l');
    private static final String EXTERNAL = fixtureKey('e');
    private static final Instant NOW = Instant.parse("2026-11-26T00:00:00Z");
    private static final UserPrincipal PRINCIPAL = new UserPrincipal(1, 2, 3, 4, 1, 1, 1, "a".repeat(64));

    @Test void durableExternalConfigLoadsWithoutAnyBuildOutputDirectory() throws Exception {
        writeKey("config/local-jwt.properties", DURABLE);
        assertThat(temporary.resolve("target")).doesNotExist();
        var tokens = tokens(environment(Map.of()));
        assertThatCode(tokens::requireConfigured).doesNotThrowAnyException();
        assertThat(tokens.verify(tokens.issue(PRINCIPAL, NOW))).isEqualTo(PRINCIPAL);
    }

    @Test void durableKeyTakesPrecedenceAndSurvivesRemovalOfLegacyBuildFile() throws Exception {
        writeKey("config/local-jwt.properties", DURABLE);
        Path legacy = writeKey("target/local-jwt.properties", LEGACY);
        var beforeClean = tokens(environment(Map.of()));
        String existingToken = beforeClean.issue(PRINCIPAL, NOW);
        Files.delete(legacy);
        Files.delete(legacy.getParent());
        var afterClean = tokens(environment(Map.of()));
        assertThat(afterClean.verify(existingToken)).isEqualTo(PRINCIPAL);
        assertThat(new AuthSettings(environment(Map.of())).signingSecret().equals(DURABLE)).isTrue();
    }

    @Test void deploymentEnvironmentSecretOverridesBothLocalFiles() throws Exception {
        writeKey("config/local-jwt.properties", DURABLE);
        writeKey("target/local-jwt.properties", LEGACY);
        var env = environment(Map.of("AUTH_JWT_SECRET", EXTERNAL));
        assertThat(new AuthSettings(env).signingSecret().equals(EXTERNAL)).isTrue();
        var tokens = tokens(env);
        assertThat(tokens.verify(tokens.issue(PRINCIPAL, NOW))).isEqualTo(PRINCIPAL);
    }

    @Test void legacyDevelopmentFileStillLoadsWhenDurableFileIsAbsent() throws Exception {
        writeKey("target/local-jwt.properties", LEGACY);
        var env = environment(Map.of());
        assertThat(new AuthSettings(env).signingSecret().equals(LEGACY)).isTrue();
        assertThatCode(tokens(env)::requireConfigured).doesNotThrowAnyException();
    }

    @Test void noLocalOrEnvironmentKeyStillFailsClosed() throws Exception {
        var tokens = tokens(environment(Map.of()));
        assertThatThrownBy(tokens::requireConfigured).isInstanceOf(AuthException.class).hasMessageContaining("not configured");
        assertThatThrownBy(() -> tokens.issue(PRINCIPAL, NOW)).isInstanceOf(AuthException.class);
    }

    @Test void invalidExplicitEnvironmentKeyDoesNotFallBackToLocalDevelopmentKey() throws Exception {
        writeKey("config/local-jwt.properties", DURABLE);
        var tokens = tokens(environment(Map.of("AUTH_JWT_SECRET", "invalid-base64-secret")));
        assertThatThrownBy(tokens::requireConfigured).isInstanceOf(AuthException.class).hasMessageContaining("not configured");
    }

    private StandardEnvironment environment(Map<String, Object> processEnvironment) throws IOException {
        var actual = new Properties();
        try (var input = new ClassPathResource("application.properties").getInputStream()) {
            actual.load(input);
        }
        String imports = actual.getProperty("spring.config.import");
        assertThat(imports).contains("optional:file:./target/local-jwt.properties", "optional:file:./config/local-jwt.properties");
        // Read the real import ordering and secret placeholder; never import the developer's real files.
        var isolated = new Properties();
        isolated.setProperty("spring.config.import", imports.replace("optional:file:./", "optional:" + temporary.toUri()));
        isolated.setProperty("app.user.jwt.secret", actual.getProperty("app.user.jwt.secret"));
        var content = new StringWriter();
        isolated.store(content, "Isolated signing configuration fixture");
        Path application = temporary.resolve("application.properties");
        Files.writeString(application, content.toString());
        var env = new StandardEnvironment();
        env.getPropertySources().replace(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                new MapPropertySource(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, Map.of()));
        env.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, processEnvironment));
        env.getPropertySources().addFirst(new MapPropertySource("isolated-bootstrap", Map.of("spring.config.location", application.toUri().toString())));
        ConfigDataEnvironmentPostProcessor.applyTo(env);
        return env;
    }

    private Path writeKey(String relative, String key) throws IOException {
        Path file = temporary.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, "app.user.dev.jwt-secret=" + key + "\n");
    }

    private AccessTokenService tokens(StandardEnvironment env) {
        return new AccessTokenService(new AuthSettings(env), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static String fixtureKey(char marker) {
        return Base64.getEncoder().encodeToString(String.valueOf(marker).repeat(48).getBytes(StandardCharsets.UTF_8));
    }
}
