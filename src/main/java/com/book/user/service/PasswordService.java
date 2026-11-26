package com.book.user.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import com.book.user.dto.AuthenticatedIdentity;
import com.book.user.repository.AccountRepository;
import com.book.user.repository.AccountRepository.Account;

@Service
public class PasswordService {
    private static final Logger LOGGER=LoggerFactory.getLogger(PasswordService.class);
    public static final String SETUP="SET_PASSWORD", RESET="PASSWORD_RESET", ENROLLMENT="SSO_ENROLLMENT";
    private final AccountRepository accounts;
    private final TenantProvisioningService provisioning;
    private final CredentialMailService mail;
    private final PasswordEncoder encoder;
    private final Clock clock;
    private final Environment settings;
    private final TransactionTemplate transactions;
    private final TransactionTemplate deliveryTransactions;
    private final TaskExecutor mailExecutor;
    private final SecureRandom random=new SecureRandom();
    private final String dummyHash;

    public PasswordService(AccountRepository accounts,TenantProvisioningService provisioning,CredentialMailService mail,
            PasswordEncoder encoder,Clock clock,Environment settings,PlatformTransactionManager manager,
            @Qualifier("userCredentialMailExecutor") TaskExecutor mailExecutor) {
        this.accounts=accounts;this.provisioning=provisioning;this.mail=mail;this.encoder=encoder;this.clock=clock;
        this.settings=settings;this.transactions=new TransactionTemplate(manager);
        this.deliveryTransactions=new TransactionTemplate(manager);
        this.deliveryTransactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.mailExecutor=mailExecutor;
        byte[] dummyPassword=new byte[32];random.nextBytes(dummyPassword);
        this.dummyHash=encoder.encode(Base64.getEncoder().encodeToString(dummyPassword));
    }

    public void register(Registration registration) {
        if (registration == null) throw invalid("Provide registration details.");
        String type=required(registration.tenantType(),20,"Tenant type").toUpperCase(Locale.ROOT);
        if (!type.equals("INDIVIDUAL") && !type.equals("ORGANIZATION")) throw invalid("Choose Individual or Organization.");
        String policy=type.equals("INDIVIDUAL") ? "PASSWORD"
                : required(registration.loginType(),30,"Login type").toUpperCase(Locale.ROOT);
        if (!policy.equals("PASSWORD") && !policy.equals("SSO")) throw invalid("Choose Password or SSO.");
        String email=normalizeEmail(registration.email());
        String name=required(registration.displayName(),150,"Name");
        String code=type.equals("ORGANIZATION") ? required(registration.tenantCode(),80,"Organization code") : null;
        String organization=type.equals("ORGANIZATION") ? required(registration.organizationName(),180,"Organization name") : null;
        if (policy.equals("PASSWORD")) mail.requireAvailable();
        try {
            transactions.executeWithoutResult(status -> {
                if (accounts.emailExists(email)) throw conflict();
                var created=provisioning.create(type,policy,email,name,code,organization,false);
                Account account=accounts.byId(created.userId(),true).orElseThrow();
                Instant now=clock.instant();
                if (policy.equals("SSO")) {
                    accounts.jdbc().update("""
                            INSERT INTO identity_providers(tenant_id,provider_type,provider_code,status,created_at,updated_at)
                            VALUES (?,'ORG_OIDC',?,'PENDING',?,?)
                            """,created.tenantId(),"org-"+created.tenantId(),AccountRepository.timestamp(now),AccountRepository.timestamp(now));
                } else issueAndMail(account,SETUP,null,now);
                accounts.audit(created.tenantId(),created.userId(),"REGISTRATION","SUCCESS",now);
            });
        } catch (DataIntegrityViolationException duplicate) { throw conflict(); }
    }

    public AuthenticatedIdentity authenticate(String submittedEmail,String password) {
        String email;
        try { email=normalizeEmail(submittedEmail); } catch (ResponseStatusException invalid) { email="invalid@invalid.local"; }
        String candidate=password == null ? "" : password;
        if (candidate.length()>128) candidate="";
        final String lookup=email, supplied=candidate;
        Optional<AuthenticatedIdentity> identity=transactions.execute(status -> {
            Account account=accounts.byEmail(lookup,true).orElse(null);
            Instant now=clock.instant();
            boolean allowed=account != null && account.active() && account.passwordPolicy() && account.passwordHash() != null
                    && (account.lockedUntil() == null || !now.isBefore(account.lockedUntil()));
            boolean matches=encoder.matches(supplied,allowed ? account.passwordHash() : dummyHash);
            if (!allowed || !matches) {
                if (allowed) accounts.failedPassword(account,settings.getProperty("app.user.password.max-failures",Integer.class,5),
                        duration("app.user.password.lockout",Duration.ofMinutes(15)),now);
                return Optional.empty();
            }
            if (accounts.jdbc().queryForObject("SELECT COUNT(*) FROM local_mfa_factors WHERE user_id=? AND status='ACTIVE' AND confirmed_at IS NOT NULL",
                    Long.class,account.userId()) == 0) accounts.clearFailures(account.userId(),now);
            return Optional.of(identity(account,"PASSWORD",null));
        });
        return identity.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Email or password is invalid."));
    }

    /** Called by the authorized current-tenant invitation service inside its creation transaction. */
    public void inviteMember(long userId,Long providerId) {
        mail.requireAvailable();
        transactions.executeWithoutResult(status -> {
            Account account=accounts.byId(userId,true).orElseThrow(PasswordService::invalidToken);
            if (!"PENDING".equals(account.userStatus()) || account.deletedAt()!=null || !"ACTIVE".equals(account.tenantStatus())
                    || !"ACTIVE".equals(account.membershipStatus()) || account.passwordHash()!=null) throw invalidToken();
            if (account.passwordPolicy()) {
                if (providerId!=null) throw invalidToken();
                issueAndMail(account,SETUP,null,clock.instant());
            } else {
                if (providerId==null || accounts.jdbc().queryForObject("SELECT COUNT(*) FROM identity_providers WHERE identity_provider_id=? AND tenant_id=? AND provider_type='ORG_OIDC' AND status='ACTIVE'",Long.class,providerId,account.tenantId())!=1) throw invalidToken();
                issueAndMail(account,ENROLLMENT,providerId,clock.instant());
            }
        });
    }

    public void requestReset(String submittedEmail) {
        mail.requireAvailable();
        String email=normalizeEmail(submittedEmail);
        System.out.println("email ::"+email);
        accounts.audit(null,null,"PASSWORD_RESET_REQUEST","RECEIVED",clock.instant());
        transactions.executeWithoutResult(status -> accounts.byEmail(email,true).filter(Account::passwordPolicy).ifPresent(account -> {
                boolean pending="PENDING".equals(account.userStatus()) && account.deletedAt()==null && ("PENDING".equals(account.tenantStatus()) || "ACTIVE".equals(account.tenantStatus()))
                        && "ACTIVE".equals(account.membershipStatus()) && account.passwordHash() == null;
                if (!pending && !(account.active() && account.passwordHash() != null)) return;
                Instant now=clock.instant();
                Long recent=accounts.jdbc().queryForObject("""
                        SELECT COUNT(*) FROM credential_tokens WHERE user_id=?
                        AND purpose IN ('SET_PASSWORD','RESET_PASSWORD','PASSWORD_RESET') AND created_at>?
                        """,Long.class,account.userId(),AccountRepository.timestamp(now.minus(Duration.ofMinutes(15))));
                if (recent != null && recent>=5) {
                    accounts.audit(account.tenantId(),account.userId(),"PASSWORD_LINK_LIMIT","FAILURE",now);
                    return;
                }
                CredentialDelivery delivery=issueCredential(account,pending ? SETUP : RESET,null,now);
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() { enqueue(delivery); }
                });
            }));
    }

    public void completeSetup(String secret,String password) { complete(secret,password,SETUP); }
    public void completeReset(String secret,String password,String confirmation) {
        try {
            // Count UTF-16 code units, exactly as JavaScript String.length. Do not transform passwords.
            if (password == null || password.length()<5 || password.length()>25)
                throw invalid("New Password must contain 5 to 25 characters (UTF-16 code units).");
            if (!password.equals(confirmation)) throw invalid("New Password and Confirm Password must match exactly.");
            complete(secret,password,RESET);
        } catch (RuntimeException failure) {
            deliveryTransactions.executeWithoutResult(status ->
                    accounts.audit(null,null,"PASSWORD_RESET","FAILURE",clock.instant()));
            throw failure;
        }
    }

    private void complete(String secret,String password,String purpose) {
        if (!purpose.equals(RESET)) validatePassword(password);
        byte[] digest=digestSecret(secret);
        String hash=encoder.encode(password);
        transactions.executeWithoutResult(status -> {
            var hint=accounts.token(digest,false).orElseThrow(PasswordService::invalidToken);
            Account account=accounts.byId(hint.userId(),true).orElseThrow(PasswordService::invalidToken);
            var token=accounts.token(digest,true).orElseThrow(PasswordService::invalidToken);
            Instant now=clock.instant();
            boolean setup=purpose.equals(SETUP);
            if (!token.valid(account,purpose,now) || account.deletedAt()!=null || !account.passwordPolicy() || !"ACTIVE".equals(account.membershipStatus())
                    || (setup ? !"PENDING".equals(account.userStatus()) || account.passwordHash() != null
                    : !account.active() || account.passwordHash() == null)
                    || !accounts.consume(token.tokenId(),now)) throw invalidToken();
            accounts.changePassword(account,hash,setup,now);
        });
    }

    public void sendEnrollment(long userId,long providerId) {
        mail.requireAvailable();
        transactions.executeWithoutResult(status -> {
            Account account=accounts.byId(userId,true).orElseThrow(PasswordService::invalidToken);
            Long count=accounts.jdbc().queryForObject("""
                    SELECT COUNT(*) FROM identity_providers WHERE identity_provider_id=? AND tenant_id=?
                    AND provider_type='ORG_OIDC' AND status='ACTIVE'
                    """,Long.class,providerId,account.tenantId());
            if (!"SSO".equals(account.loginPolicy()) || !"PENDING".equals(account.userStatus())
                    || !"PENDING_SETUP".equals(account.tenantStatus()) || count == null || count != 1
                    || accounts.jdbc().queryForObject("SELECT COUNT(*) FROM tenants WHERE tenant_id=? AND owner_user_id=?",
                        Long.class,account.tenantId(),account.userId()) != 1)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Enrollment requires the pending administrator and an active trusted company provider.");
            issueAndMail(account,ENROLLMENT,providerId,clock.instant());
        });
    }

    private void issueAndMail(Account account,String purpose,Long providerId,Instant now) {
        CredentialDelivery delivery=issueCredential(account,purpose,providerId,now);
        mail.send(delivery.email(),delivery.purpose(),delivery.secret(),delivery.tenantCode(),delivery.lifetime());
    }
    private CredentialDelivery issueCredential(Account account,String purpose,Long providerId,Instant now) {
        byte[] bytes=new byte[32];random.nextBytes(bytes);
        String secret=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Duration ttl=duration("app.user.password."+(purpose.equals(SETUP) ? "setup-ttl" : purpose.equals(RESET) ? "reset-ttl" : "enrollment-ttl"),
                purpose.equals(SETUP) ? Duration.ofHours(24) : purpose.equals(RESET) ? Duration.ofMinutes(15) : Duration.ofMinutes(30));
        byte[] digest=digestSecret(secret);
        accounts.issueCredential(account.userId(),purpose,account.email(),digest,account.securityVersion(),providerId,now,now.plus(ttl));
        return new CredentialDelivery(account.userId(),account.email(),purpose,secret,digest,
                purpose.equals(ENROLLMENT) ? account.tenantCode() : null,ttl,now.plus(ttl));
    }
    private void enqueue(CredentialDelivery delivery) {
        try { mailExecutor.execute(() -> deliver(delivery)); }
        catch (RejectedExecutionException rejected) { deliveryFailed(delivery); }
    }
    private void deliver(CredentialDelivery delivery) {
        Duration remaining=Duration.between(clock.instant(),delivery.expiresAt());
        if (remaining.isNegative() || remaining.isZero()) { deliveryFailed(delivery); return; }
        try { mail.send(delivery.email(),delivery.purpose(),delivery.secret(),delivery.tenantCode(),remaining); }
        catch (RuntimeException failure) { deliveryFailed(delivery); }
    }
    private void deliveryFailed(CredentialDelivery delivery) {
        try {
            deliveryTransactions.executeWithoutResult(status -> {
                // Lock the user even if its membership changed after the request. Never revoke a newer link.
                accounts.jdbc().queryForList("SELECT user_id FROM users WHERE user_id=? FOR UPDATE",delivery.userId());
                Instant now=clock.instant();
                accounts.jdbc().update("""
                        UPDATE credential_tokens SET revoked_at=? WHERE user_id=? AND token_digest=?
                        AND consumed_at IS NULL AND revoked_at IS NULL
                        """,AccountRepository.timestamp(now),delivery.userId(),delivery.digest());
                accounts.audit(null,null,"PASSWORD_RESET_DELIVERY","FAILURE",now);
            });
        } catch (RuntimeException cleanupUnavailable) {
            // A committed generic public response must not depend on cleanup storage availability.
            LOGGER.error("CREDENTIAL_DELIVERY_CLEANUP_FAILED");
        }
    }
    private Duration duration(String key,Duration fallback) {
        Duration value=settings.getProperty(key,Duration.class,fallback);
        if (value.isNegative() || value.isZero()) throw new IllegalStateException("Credential lifetime must be positive.");
        return value;
    }
    public static AuthenticatedIdentity identity(Account account,String method,Long providerId) {
        return new AuthenticatedIdentity(account.userId(),account.tenantId(),account.membershipId(),method,providerId,null,account.securityVersion());
    }
    public static byte[] digestSecret(String secret) {
        if (secret == null || !secret.matches("[A-Za-z0-9_-]{43}")) throw invalidToken();
        try { return MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.US_ASCII)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static String normalizeEmail(String raw) {
        String email=required(raw,320,"Email").toLowerCase(Locale.ROOT);
        if (!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+") || email.indexOf('\r')>=0 || email.indexOf('\n')>=0)
            throw invalid("Enter a valid email address.");
        return email;
    }
    private static void validatePassword(String value) {
        if (value == null || value.length()<12 || value.length()>128 || value.isBlank())
            throw invalid("Password must contain 12 to 128 characters.");
    }
    private static String required(String value,int maximum,String field) {
        if (value == null || value.strip().isEmpty() || value.strip().length()>maximum)
            throw invalid(field+" is required and must contain at most "+maximum+" characters.");
        return value.strip();
    }
    public static ResponseStatusException invalidToken() { return new ResponseStatusException(HttpStatus.BAD_REQUEST,"This link is invalid or expired. Request a new link."); }
    private static ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
    private static ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT,"Registration cannot be completed with these account or organization details. Sign in or recover your existing account."); }
    private record CredentialDelivery(long userId,String email,String purpose,String secret,byte[] digest,
            String tenantCode,Duration lifetime,Instant expiresAt) {
        @Override public String toString() { return "CredentialDelivery[REDACTED]"; }
    }
    public record Registration(String tenantType,String loginType,String email,String displayName,String tenantCode,String organizationName) {
        @Override public String toString() { return "Registration[tenantType="+tenantType+",loginType="+loginType+",profile=REDACTED]"; }
    }
}
