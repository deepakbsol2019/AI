package com.book.user;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.web.server.ResponseStatusException;
import com.book.user.provider.*;
import com.book.user.repository.AccountRepository;
import com.book.user.service.PasswordService;
import com.book.user.service.TenantProvisioningService;

class OidcAccountTests {
    private static final Instant NOW=Instant.parse("2026-11-25T00:00:00Z");
    private JdbcTemplate jdbc;
    private AccountRepository accounts;
    private TenantProvisioningService provisioning;
    private TrustedOidcProviders trusted;
    private OidcIdentityService identities;
    @BeforeEach void setup() {
        jdbc=AuthTestDatabase.create();accounts=new AccountRepository(jdbc);
        provisioning=new TenantProvisioningService(jdbc,Clock.fixed(NOW,ZoneOffset.UTC), com.book.user.AuthTestDatabase.storage());
        trusted=mock(TrustedOidcProviders.class);
        identities=new OidcIdentityService(accounts,trusted,provisioning,Clock.fixed(NOW,ZoneOffset.UTC),new DataSourceTransactionManager(jdbc.getDataSource()));
    }
    @Test void bothDisabledRejectInitiationAndDirectRegistrationsWithoutSecretsOrDatabaseReads() {
        JdbcTemplate untouched=mock(JdbcTemplate.class);
        var registry=new TrustedOidcProviders(untouched,new MockEnvironment());
        var controller=new OidcIntegration.InitiationController(registry,identities);
        assertThatThrownBy(controller::google).isInstanceOf(ResponseStatusException.class).hasMessageContaining("unavailable");
        assertThatThrownBy(() -> controller.sso(new OidcIntegration.InitiationController.SsoRequest("company",null),new MockHttpServletRequest()))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> registry.findByRegistrationId("google")).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(untouched);
    }
    @Test void googleCreatesIndividualAdminExactlyOnceWithoutLocalPassword() {
        provider(null,"GOOGLE","google","https://accounts.google.com");
        var first=identities.resolve("google",oidc("https://accounts.google.com","subject-1","new@example.com",true),null);
        var second=identities.resolve("google",oidc("https://accounts.google.com","subject-1","new@example.com",true),null);
        assertThat(first.userId()).isEqualTo(second.userId());assertThat(first.authMethod()).isEqualTo("GOOGLE");
        var account=accounts.byId(first.userId(),false).orElseThrow();
        assertThat(account.active()).isTrue();assertThat(account.tenantType()).isEqualTo("INDIVIDUAL");
        assertThat(account.passwordHash()).isNull();assertThat(account.securityVersion()).isEqualTo(first.securityVersion());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_roles ur JOIN roles r ON r.role_id=ur.role_id WHERE r.role_code='OWNER'",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM local_credentials",Long.class)).isZero();
    }
    @Test void googleNeverMergesByMatchingEmailOrAddsAnotherCurrentMembership() {
        provisioning.create("INDIVIDUAL","PASSWORD","existing@example.com","Existing",null,null,true);
        provider(null,"GOOGLE","google","https://accounts.google.com");
        assertThatThrownBy(() -> identities.resolve("google",oidc("https://accounts.google.com","new-subject","existing@example.com",true),null))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("ownership verification");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM external_identities",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tenant_memberships",Long.class)).isEqualTo(1);
    }
    @Test void googleRejectsUnverifiedOrMissingClaimsAndWrongIssuer() {
        provider(null,"GOOGLE","google","https://accounts.google.com");
        assertThatThrownBy(() -> identities.resolve("google",oidc("https://accounts.google.com","sub","new@example.com",false),null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> identities.resolve("google",oidc("https://accounts.google.com","sub",null,true),null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> identities.resolve("google",oidc("https://attacker.example","sub","new@example.com",true),null)).isInstanceOf(ResponseStatusException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users",Long.class)).isZero();
    }
    @Test void newVerifiedThirdPartyGoogleEmailWithoutHostedDomainCannotProvisionAnyAccountRows() {
        provider(null,"GOOGLE","google","https://accounts.google.com");
        for(String domain:new String[]{null,"   "}) {
            assertThatThrownBy(() -> identities.resolve("google",oidc("https://accounts.google.com","third-party","new@example.com",true,domain),null))
                    .isInstanceOf(ResponseStatusException.class).hasMessageContaining("email/password registration");
        }
        for(String table:List.of("users","tenants","tenant_memberships","roles","user_roles","external_identities","local_credentials"))
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class)).as(table).isZero();
    }
    @Test void newVerifiedGoogleHostedEmailCanProvisionAnIndividualWithoutACompanyMembership() {
        provider(null,"GOOGLE","google","https://accounts.google.com");
        var created=identities.resolve("google",oidc("https://accounts.google.com","hosted","hosted@example.com",true,"example.com"),null);
        var account=accounts.byId(created.userId(),false).orElseThrow();
        assertThat(account.tenantType()).isEqualTo("INDIVIDUAL");assertThat(account.passwordHash()).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tenant_memberships WHERE user_id=?",Long.class,created.userId())).isEqualTo(1);
    }
    @Test void newGmailIdentityDoesNotNeedAHostedDomainClaim() {
        provider(null,"GOOGLE","google","https://accounts.google.com");
        var created=identities.resolve("google",oidc("https://accounts.google.com","gmail","Person@GMAIL.com",true,null),null);
        assertThat(accounts.byId(created.userId(),false).orElseThrow().email()).isEqualTo("person@gmail.com");
    }
    @Test void linkedGoogleSubjectStillRequiresEmailVerificationButNeverUpdatesOrRelinksEmail() {
        provider(null,"GOOGLE","google","https://accounts.google.com");
        var original=identities.resolve("google",oidc("https://accounts.google.com","linked","original@example.com",true),null);
        assertThatThrownBy(() -> identities.resolve("google",oidc("https://accounts.google.com","linked",null,true),null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> identities.resolve("google",oidc("https://accounts.google.com","linked","new@example.com",false),null)).isInstanceOf(ResponseStatusException.class);
        var reused=identities.resolve("google",oidc("https://accounts.google.com","linked","new@example.com",true,null),null);
        assertThat(reused.userId()).isEqualTo(original.userId());
        assertThat(accounts.byId(original.userId(),false).orElseThrow().email()).isEqualTo("original@example.com");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM external_identities",Long.class)).isEqualTo(1);
    }
    @Test void pendingSsoAdminCannotBeLinkedUsingMatchingEmailAlone() {
        var admin=provisioning.create("ORGANIZATION","SSO","admin@example.com","Admin","company","Company",false);
        provider(admin.tenantId(),"ORG_OIDC","org-company","https://id.company.example");
        assertThatThrownBy(() -> identities.resolve("org-company",oidc("https://id.company.example","company-sub","admin@example.com",true),null))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(accounts.byId(admin.userId(),false).orElseThrow().userStatus()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM external_identities",Long.class)).isZero();
    }
    @Test void highEntropyEnrollmentBindsIntendedProviderAndAdminAndIsSingleUse() {
        var admin=provisioning.create("ORGANIZATION","SSO","admin@example.com","Admin","company","Company",false);
        var provider=provider(admin.tenantId(),"ORG_OIDC","org-company","https://id.company.example");
        String secret=Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        accounts.issueCredential(admin.userId(),PasswordService.ENROLLMENT,"admin@example.com",PasswordService.digestSecret(secret),1,
                provider.id(),NOW,NOW.plusSeconds(1800));
        var proof=identities.enrollment("org-company",secret);
        var identity=identities.resolve("org-company",oidc("https://id.company.example","admin-subject","admin@example.com",false),proof);
        assertThat(identity.securityVersion()).isEqualTo(2);assertThat(identity.authMethod()).isEqualTo("SSO");
        assertThat(accounts.byId(admin.userId(),false).orElseThrow().active()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM local_credentials",Long.class)).isZero();
        assertThatThrownBy(() -> identities.resolve("org-company",oidc("https://id.company.example","another-subject","admin@example.com",true),proof))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM external_identities",Long.class)).isEqualTo(1);
    }
    @Test void providerBindingAndTenantIsolationRejectAValidProofForAnotherCompany() {
        var one=provisioning.create("ORGANIZATION","SSO","one@example.com","One","company-one","Company One",false);
        var first=provider(one.tenantId(),"ORG_OIDC","one","https://id.one.example");
        var two=provisioning.create("ORGANIZATION","SSO","two@example.com","Two","company-two","Company Two",false);
        provider(two.tenantId(),"ORG_OIDC","two","https://id.two.example");
        String secret=Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        accounts.issueCredential(one.userId(),PasswordService.ENROLLMENT,"one@example.com",PasswordService.digestSecret(secret),1,first.id(),NOW,NOW.plusSeconds(600));
        assertThatThrownBy(() -> identities.enrollment("two",secret)).isInstanceOf(ResponseStatusException.class);
        var proof=identities.enrollment("one",secret);
        assertThatThrownBy(() -> identities.resolve("two",oidc("https://id.two.example","wrong","one@example.com",true),proof)).isInstanceOf(ResponseStatusException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM external_identities",Long.class)).isZero();
    }
    @Test void externallyAllowlistedIssuerAndExactCallbackMustMatchDatabase() {
        var row=provider(null,"GOOGLE","google","https://accounts.google.com");
        var environment=new MockEnvironment().withProperty("app.user.google.enabled","true")
                .withProperty("app.user.providers.google.issuer-uri","https://accounts.google.com")
                .withProperty("app.user.providers.google.client-id","client")
                .withProperty("app.user.providers.google.redirect-uri","http://localhost:9090/login/oauth2/code/google");
        var registry=new TrustedOidcProviders(jdbc,environment);
        assertThat(registry.localAuthorizationUrl("google")).isEqualTo("/oauth2/authorization/google");
        jdbc.update("UPDATE identity_providers SET issuer_url='https://attacker.example' WHERE identity_provider_id=?",row.id());
        assertThatThrownBy(() -> registry.trusted("google")).isInstanceOf(ResponseStatusException.class);
        environment.withProperty("app.user.providers.google.redirect-uri","https://example.com/login/oauth2/code/other");
        assertThatThrownBy(() -> registry.definition("google")).isInstanceOf(ResponseStatusException.class);
    }
    private TrustedOidcProviders.Provider provider(Long tenant,String type,String code,String issuer) {
        var now=AccountRepository.timestamp(NOW);
        jdbc.update("INSERT INTO identity_providers(tenant_id,provider_type,provider_code,issuer_url,client_id,status,created_at,updated_at) VALUES (?,?,?,?,?,'ACTIVE',?,?)",
                tenant,type,code,issuer,"client",now,now);
        Long id=jdbc.queryForObject("SELECT identity_provider_id FROM identity_providers WHERE provider_code=?",Long.class,code);
        var provider=new TrustedOidcProviders.Provider(id,tenant,type,code,issuer,"client",null,null,"ACTIVE");
        when(trusted.trusted(code)).thenReturn(provider);return provider;
    }
    private DefaultOidcUser oidc(String issuer,String subject,String email,boolean verified) {
        return oidc(issuer,subject,email,verified,"https://accounts.google.com".equals(issuer) ? "example.com" : null);
    }
    private DefaultOidcUser oidc(String issuer,String subject,String email,boolean verified,String hostedDomain) {
        Map<String,Object> claims=new HashMap<>(Map.of("iss",issuer,"sub",subject,"aud",List.of("client"),"name","Example Person","email_verified",verified));
        if (email != null) claims.put("email",email);
        if (hostedDomain != null) claims.put("hd",hostedDomain);
        return new DefaultOidcUser(List.of(new SimpleGrantedAuthority("OIDC_USER")),new OidcIdToken("validated-token",NOW,NOW.plusSeconds(300),claims));
    }
}
