package com.book.user.provider;

import java.net.URI;
import java.util.Map;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Database rows select trusted external configuration; login input never supplies an issuer or secret. */
@Component
public class TrustedOidcProviders implements ClientRegistrationRepository {
    private final JdbcTemplate jdbc;
    private final Environment environment;
    public TrustedOidcProviders(JdbcTemplate jdbc,Environment environment) { this.jdbc=jdbc;this.environment=environment; }

    public boolean googleEnabled() { return environment.getProperty("app.user.google.enabled",Boolean.class,false); }
    public boolean ssoEnabled() { return environment.getProperty("app.user.sso.enabled",Boolean.class,false); }
    public void requireEnabled(String type) {
        if (!("GOOGLE".equals(type) ? googleEnabled() : "ORG_OIDC".equals(type) && ssoEnabled()))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"This sign-in provider is unavailable.");
    }
    public Definition definition(String code) {
        Map<String,Definition> definitions=Binder.get(environment)
                .bind("app.user.providers",Bindable.mapOf(String.class,Definition.class)).orElse(Map.of());
        Definition definition=definitions.get(code);
        if (definition == null || definition.issuerUri() == null || definition.clientId() == null
                || definition.clientId().isBlank() || definition.redirectUri() == null)
            throw unavailable();
        URI issuer=URI.create(definition.issuerUri()), redirect=URI.create(definition.redirectUri());
        if (!"https".equals(issuer.getScheme()) || issuer.getHost() == null || issuer.getUserInfo() != null
                || issuer.getQuery() != null || issuer.getFragment() != null || redirect.getHost() == null
                || redirect.getUserInfo() != null || redirect.getFragment() != null || redirect.getQuery() != null
                || !redirect.getPath().equals("/login/oauth2/code/"+code)
                || !("https".equals(redirect.getScheme()) || "http".equals(redirect.getScheme())
                    && (redirect.getHost().equals("localhost") || redirect.getHost().equals("127.0.0.1")))) throw unavailable();
        return definition;
    }
    public Provider byCode(String code) {
        if (!googleEnabled() && !ssoEnabled()) throw unavailable();
        if (code == null || !code.matches("[a-zA-Z0-9_-]{1,80}")) throw unavailable();
        return jdbc.query("""
                SELECT identity_provider_id,tenant_id,provider_type,provider_code,issuer_url,client_id,
                client_secret_ref,external_tenant_id,status FROM identity_providers WHERE provider_code=?
                """,(rs,row) -> new Provider(rs.getLong("identity_provider_id"),rs.getObject("tenant_id",Long.class),
                rs.getString("provider_type"),rs.getString("provider_code"),rs.getString("issuer_url"),
                rs.getString("client_id"),rs.getString("client_secret_ref"),rs.getString("external_tenant_id"),rs.getString("status")),code)
                .stream().findFirst().orElseThrow(TrustedOidcProviders::unavailable);
    }
    public Provider trusted(String code) {
        Provider provider=byCode(code);
        requireEnabled(provider.type());
        Definition definition=definition(code);
        if (!"ACTIVE".equals(provider.status()) || !definition.issuerUri().equals(provider.issuer())
                || !definition.clientId().equals(provider.clientId())
                || !java.util.Objects.equals(empty(definition.secretRef()),empty(provider.secretRef()))
                || !java.util.Objects.equals(empty(definition.externalTenantId()),empty(provider.externalTenantId()))
                || ("GOOGLE".equals(provider.type()) ? provider.tenantId() != null
                    || !"https://accounts.google.com".equals(provider.issuer()) : provider.tenantId() == null)) throw unavailable();
        return provider;
    }
    @Override
    public ClientRegistration findByRegistrationId(String code) {
        Provider provider=trusted(code);
        Definition definition=definition(code);
        String secret=empty(definition.secretRef()) == null ? null : environment.getProperty(definition.secretRef());
        if (definition.secretRef() != null && (secret == null || secret.isBlank())) throw unavailable();
        // Discovery is performed only for an explicitly enabled, externally allowlisted provider.
        return ClientRegistrations.fromIssuerLocation(provider.issuer()).registrationId(code).clientId(provider.clientId())
                .clientSecret(secret == null ? "" : secret)
                .clientAuthenticationMethod(secret == null ? ClientAuthenticationMethod.NONE : ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).scope("openid","profile","email")
                .redirectUri(definition.redirectUri()).clientName(code).build();
    }
    public String companyCode(String tenantCode) {
        requireEnabled("ORG_OIDC");
        var rows=jdbc.query("""
                SELECT p.provider_code FROM identity_providers p JOIN tenants t ON t.tenant_id=p.tenant_id
                WHERE t.tenant_code=? AND t.tenant_type='ORGANIZATION' AND t.login_policy='SSO'
                  AND p.provider_type='ORG_OIDC' AND p.status='ACTIVE'
                """,(rs,row) -> rs.getString(1),tenantCode);
        if (rows.size()!=1) throw unavailable();
        return rows.getFirst();
    }
    public String localAuthorizationUrl(String code) { trusted(code); return "/oauth2/authorization/"+code; }
    public String frontendBase() { return environment.getProperty("app.user.public-base-url","http://localhost:4200").replaceAll("/$",""); }
    private static String empty(String value) { return value == null || value.isBlank() ? null : value; }
    public static ResponseStatusException unavailable() { return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"This trusted sign-in provider has not been enabled and configured."); }
    public record Definition(String issuerUri,String clientId,String secretRef,String redirectUri,String externalTenantId) { }
    public record Provider(long id,Long tenantId,String type,String code,String issuer,String clientId,String secretRef,String externalTenantId,String status) { }
}
