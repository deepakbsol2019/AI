package com.book.user.provider;

import java.io.Serializable;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import com.book.user.dto.AuthenticatedIdentity;
import com.book.user.repository.AccountRepository;
import com.book.user.repository.AccountRepository.Account;
import com.book.user.service.PasswordService;
import com.book.user.service.TenantProvisioningService;

@Service
public class OidcIdentityService {
    private final AccountRepository accounts;
    private final TrustedOidcProviders providers;
    private final TenantProvisioningService provisioning;
    private final Clock clock;
    private final TransactionTemplate transactions;
    public OidcIdentityService(AccountRepository accounts,TrustedOidcProviders providers,TenantProvisioningService provisioning,
            Clock clock,PlatformTransactionManager manager) {
        this.accounts=accounts;this.providers=providers;this.provisioning=provisioning;this.clock=clock;
        this.transactions=new TransactionTemplate(manager);
    }
    public Enrollment enrollment(String code,String secret) {
        var provider=providers.trusted(code);
        if (!"ORG_OIDC".equals(provider.type())) throw rejected();
        byte[] digest=PasswordService.digestSecret(secret);
        var token=accounts.token(digest,false).orElseThrow(PasswordService::invalidToken);
        Account account=accounts.byId(token.userId(),false).orElseThrow(PasswordService::invalidToken);
        if (!token.valid(account,PasswordService.ENROLLMENT,clock.instant()) || !Objects.equals(token.providerId(),provider.id())
                || account.tenantId() != provider.tenantId() || !"PENDING".equals(account.userStatus())
                || !enrollable(account) || !"SSO".equals(account.loginPolicy())) throw rejected();
        return new Enrollment(digest,provider.id(),account.userId());
    }

    public AuthenticatedIdentity resolve(String code,OidcUser oidc,Enrollment enrollment) {
        var provider=providers.trusted(code);
        String subject=oidc.getSubject();
        if (subject == null || subject.isBlank() || subject.length()>255 || !provider.issuer().equals(oidc.getIssuer().toString())
                || provider.externalTenantId() != null && !provider.externalTenantId().equals(oidc.getClaimAsString("tid"))) throw rejected();
        String googleEmail="GOOGLE".equals(provider.type()) ? verifiedGoogleEmail(oidc) : null;
        try {
            return transactions.execute(status -> {
                // Serializes first use of a subject and Google account provisioning against concurrent callbacks.
                accounts.jdbc().queryForObject("SELECT identity_provider_id FROM identity_providers WHERE identity_provider_id=? FOR UPDATE",Long.class,provider.id());
                var existing=accounts.jdbc().query("SELECT user_id FROM external_identities WHERE identity_provider_id=? AND subject=?",
                        (rs,row) -> rs.getLong(1),provider.id(),subject);
                Account account;
                Instant now=clock.instant();
                if (!existing.isEmpty()) {
                    account=accounts.byId(existing.getFirst(),true).orElseThrow(OidcIdentityService::rejected);
                    requireMethod(account,provider);
                    if (!account.active()) throw rejected();
                } else if ("GOOGLE".equals(provider.type())) {
                    String email=googleEmail;
                    String hostedDomain=oidc.getClaimAsString("hd");
                    if (!email.endsWith("@gmail.com") && (hostedDomain == null || hostedDomain.isBlank()))
                        throw googleRejected();
                    String name=oidc.getFullName();
                    if (name == null || name.isBlank() || name.strip().length()>150) throw rejected();
                    if (accounts.emailExists(email)) throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "An existing account requires authenticated ownership verification before linking this provider.");
                    var created=provisioning.create("INDIVIDUAL","PASSWORD",email,name.strip(),null,null,true);
                    account=accounts.byId(created.userId(),true).orElseThrow();
                    link(provider.id(),subject,account.userId(),now);
                } else {
                    if (enrollment == null || enrollment.providerId() != provider.id()) throw rejected();
                    account=accounts.byId(enrollment.userId(),true).orElseThrow(OidcIdentityService::rejected);
                    requireMethod(account,provider);
                    var token=accounts.token(enrollment.digest(),true).orElseThrow(PasswordService::invalidToken);
                    String email=PasswordService.normalizeEmail(oidc.getEmail());
                    if (!token.valid(account,PasswordService.ENROLLMENT,now) || !Objects.equals(token.providerId(),provider.id())
                            || !email.equals(account.email()) || !"PENDING".equals(account.userStatus())
                            || !enrollable(account) || !"ACTIVE".equals(account.membershipStatus())
                            || account.passwordHash() != null || !accounts.consume(token.tokenId(),now)) throw rejected();
                    link(provider.id(),subject,account.userId(),now);
                    accounts.activate(account,now);
                    accounts.securityChanged(account.userId(),now);
                    account=accounts.byId(account.userId(),true).orElseThrow();
                }
                accounts.jdbc().update("UPDATE external_identities SET last_seen_at=? WHERE identity_provider_id=? AND subject=?",
                        AccountRepository.timestamp(now),provider.id(),subject);
                accounts.audit(account.tenantId(),account.userId(),"OIDC_IDENTITY","SUCCESS",now);
                return PasswordService.identity(account,provider.type().equals("GOOGLE") ? "GOOGLE" : "SSO",provider.id());
            });
        } catch (DataIntegrityViolationException duplicate) { throw rejected(); }
    }
    private boolean enrollable(Account account) {
        // Existing first-owner proof remains owner-bound. Existing active tenants can invite a
        // pending employee only through a separately issued user/email/provider-bound secret.
        return account.deletedAt()==null && ("ACTIVE".equals(account.tenantStatus())
                || "PENDING_SETUP".equals(account.tenantStatus()) && owner(account));
    }
    private boolean owner(Account account) {
        return accounts.jdbc().queryForObject("SELECT COUNT(*) FROM tenants WHERE tenant_id=? AND owner_user_id=?",Long.class,
                account.tenantId(),account.userId()) == 1;
    }
    private static String verifiedGoogleEmail(OidcUser oidc) {
        if (!Boolean.TRUE.equals(oidc.getEmailVerified())) throw googleRejected();
        try { return PasswordService.normalizeEmail(oidc.getEmail()); }
        catch (ResponseStatusException invalid) { throw googleRejected(); }
    }
    private static ResponseStatusException googleRejected() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "This Google identity cannot sign in. Use email/password registration or a verified Google-hosted email.");
    }
    private static void requireMethod(Account account,TrustedOidcProviders.Provider provider) {
        if ("ORG_OIDC".equals(provider.type()) ? !Objects.equals(provider.tenantId(),account.tenantId())
                || !"SSO".equals(account.loginPolicy()) || !"ORGANIZATION".equals(account.tenantType())
                : !"INDIVIDUAL".equals(account.tenantType())) throw rejected();
    }
    private void link(long providerId,String subject,long userId,Instant now) {
        accounts.jdbc().update("INSERT INTO external_identities(identity_provider_id,user_id,subject,last_seen_at,created_at) VALUES (?,?,?,?,?)",
                providerId,userId,subject,AccountRepository.timestamp(now),AccountRepository.timestamp(now));
    }
    public static ResponseStatusException rejected() { return new ResponseStatusException(HttpStatus.UNAUTHORIZED,
            "This provider identity cannot sign in. Use the intended provider or a valid administrator enrollment link."); }
    public record Enrollment(byte[] digest,long providerId,long userId) implements Serializable {
        @Override public String toString() { return "Enrollment[providerId="+providerId+",proof=REDACTED]"; }
    }
}
