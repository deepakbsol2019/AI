package com.book.user.provider;

import java.time.Clock;
import java.util.Objects;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import com.book.user.repository.AccountRepository;
import com.book.user.service.PasswordService;

/** Explicit operator-only startup command; no HTTP endpoint can configure or select arbitrary issuers. */
@Component
public class OidcProviderSetup implements ApplicationRunner {
    private final AccountRepository accounts;
    private final TrustedOidcProviders providers;
    private final PasswordService passwords;
    private final Environment environment;
    private final Clock clock;
    private final TransactionTemplate transactions;
    public OidcProviderSetup(AccountRepository accounts,TrustedOidcProviders providers,PasswordService passwords,
            Environment environment,Clock clock,PlatformTransactionManager manager) {
        this.accounts=accounts;this.providers=providers;this.passwords=passwords;this.environment=environment;this.clock=clock;
        this.transactions=new TransactionTemplate(manager);
    }
    @Override
    public void run(ApplicationArguments arguments) {
        String code=environment.getProperty("app.user.provider-setup.code");
        Long enrollment=environment.getProperty("app.user.provider-setup.enroll-user-id",Long.class);
        if (code == null || code.isBlank()) {
            if (enrollment != null) throw new IllegalStateException("Provider setup code is required for enrollment.");
            return;
        }
        activate(code);
        if (enrollment != null) passwords.sendEnrollment(enrollment,providers.trusted(code).id());
    }
    public void activate(String code) {
        var definition=providers.definition(code);
        String type="google".equals(code) ? "GOOGLE" : "ORG_OIDC";
        providers.requireEnabled(type);
        if (type.equals("GOOGLE") && !definition.issuerUri().equals("https://accounts.google.com")) throw TrustedOidcProviders.unavailable();
        if (definition.secretRef() != null && environment.getProperty(definition.secretRef(),"").isBlank()) throw TrustedOidcProviders.unavailable();
        transactions.executeWithoutResult(status -> {
            var now=AccountRepository.timestamp(clock.instant());
            if (type.equals("GOOGLE") && accounts.jdbc().queryForObject("SELECT COUNT(*) FROM identity_providers WHERE provider_code=?",Long.class,code) == 0)
                accounts.jdbc().update("INSERT INTO identity_providers(provider_type,provider_code,status,created_at,updated_at) VALUES ('GOOGLE',?,'PENDING',?,?)",code,now,now);
            var provider=providers.byCode(code);
            accounts.jdbc().queryForObject("SELECT identity_provider_id FROM identity_providers WHERE identity_provider_id=? FOR UPDATE",Long.class,provider.id());
            Long linked=accounts.jdbc().queryForObject("SELECT COUNT(*) FROM external_identities WHERE identity_provider_id=?",Long.class,provider.id());
            if (!type.equals(provider.type()) || linked != null && linked > 0
                    && (!Objects.equals(provider.issuer(),definition.issuerUri()) || !Objects.equals(provider.clientId(),definition.clientId())
                        || !Objects.equals(provider.externalTenantId(),definition.externalTenantId())))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"A provider with linked identities cannot change issuer, client or external tenant. Configure a separately trusted provider instead.");
            if (type.equals("ORG_OIDC") && accounts.jdbc().queryForObject("SELECT COUNT(*) FROM tenants WHERE tenant_id=? AND tenant_type='ORGANIZATION' AND login_policy='SSO'",Long.class,provider.tenantId()) != 1)
                throw TrustedOidcProviders.unavailable();
            accounts.jdbc().update("""
                    UPDATE identity_providers SET issuer_url=?,client_id=?,client_secret_ref=?,external_tenant_id=?,status='ACTIVE',updated_at=?
                    WHERE identity_provider_id=?
                    """,definition.issuerUri(),definition.clientId(),definition.secretRef(),definition.externalTenantId(),now,provider.id());
            accounts.audit(provider.tenantId(),null,"OIDC_PROVIDER_CONFIGURED","SUCCESS",clock.instant());
        });
    }
}
