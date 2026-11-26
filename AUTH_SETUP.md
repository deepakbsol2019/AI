# Authentication and tenant setup

The current [role management and storage increment](ROLE_MANAGEMENT_STORAGE.md) supersedes automatic ADMIN registration and global super-user file access: new registrations get ordinary OWNER; protected ADMIN is super-user-managed; files/search remain within the authenticated tenant.

Local signing configuration: when launched from the backend directory, `application.properties` imports optional `target/local-jwt.properties` (legacy compatibility), then ignored **`config/local-jwt.properties`** (durable development configuration). The durable file survives `mvn clean` and takes precedence over the legacy file; `AUTH_JWT_SECRET` still takes priority over both. No signing key is committed or packaged, and production must supply its own secret. This import does not change SMTP settings.

To configure local development once, run from the backend directory:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File ./scripts/Configure-LocalAuth.ps1
```

The helper creates a cryptographically random 64-byte Base64 key in `config/local-jwt.properties`, restricts the file to the current Windows account and SYSTEM, and never prints its value. If a valid legacy key exists, it preserves that key when migrating it. Repeated execution preserves the existing durable key; invalid existing keys fail explicitly rather than rotating silently. Restart the backend after initial setup. A plain password is not a signing key: Base64 decoding must yield at least 32 random bytes. Do not copy the development key into production or into `src/main/resources`. If launching from another directory, set the backend working directory or supply the private configuration through `spring.config.additional-location`.

On November 26, the missing-key warning was traced to the absent legacy target file. A private durable development key was created locally, its repeat-preservation and Git exclusion verified, and six isolated Boot configuration regressions added for clean survival, precedence, legacy compatibility and fail-closed behavior. No live login, account mutation or email was required for verification. A running JVM must restart to pick up a newly created configuration import.

Verification for this correction: normal `mvn package` passed (**676 total tests, 667 passed, 9 optional skipped, zero failures/errors**; `target/local-signing-package.log`). The actual local configuration loaded through Spring Boot and signed/verified an in-memory synthetic token without database or email access. The private key file was confirmed absent from the executable JAR. Helper fixtures verified generation, restricted ACLs, repeat preservation, legacy migration and invalid-key refusal. No shared/default signing secret or automatic production fallback was introduced.

Password reset now requires matching password/confirmation values of 5–25 UTF-16 code units with no composition rules and defaults to a 15-minute link lifetime. Setup keeps its original policy. See [PASSWORD_RESET.md](PASSWORD_RESET.md) for the current recovery contract and required legacy-link migration; it supersedes older reset-specific policy statements below.

This guide covers the existing Spring Boot 4.2 / Java 25 backend and its sibling Angular UI. New authentication Java code lives under `com.book.user`. Local password authentication is application authentication; Google and company sign-in use Spring Security's OIDC client, not a password grant or a new authorization server.

For the later, explicitly assigned application-wide system role, see [SUPER_USER_SETUP.md](SUPER_USER_SETUP.md). Public registration and ordinary ADMIN remain tenant-scoped; Super User access also excludes unassigned historical records. Verification counts in this guide describe the preceding authentication baseline, with newer role-specific results recorded in that separate guide.

## Schema references and changes

The supplied references were found at these actual local paths, without `(1)` in their filenames:

- `C:/Users/Deepak Kumar/Downloads/Organization_Table_Relationship_Chart.pdf`
- `C:/Users/Deepak Kumar/Downloads/Organization_Table_Relationship_Chart.svg`

Both were inspected and cross-checked: 18 tables, 149 reference fields, relationship arrows, nullable/unique markers and composite keys. They describe the data model, not a login sequence. They are not runtime assets and are not embedded in the application.

The existing database target is **MySQL 8**, not PostgreSQL. The versioned manual script is [20261125_user_auth.sql](src/main/resources/db/manual/20261125_user_auth.sql). It adds the 18 reference tables: `tenants`, `users`, `tenant_memberships`, `roles`, `user_roles`, `permissions`, `role_permissions`, `api_endpoints`, `endpoint_permissions`, `auth_sessions`, `refresh_tokens`, `audit_events`, `identity_providers`, `external_identities`, `local_credentials`, `credential_tokens`, `local_mfa_factors` and `recovery_codes`.

Explicit adaptations and additions are:

- Reference `TIMESTAMPTZ`, `BYTEA`, `JSONB` and `INET` become UTC `DATETIME(6)`, `VARBINARY(32)`, `JSON` and `VARCHAR(45)`. Token digests are SHA-256. Use a UTC database connection; the script sets its session time zone to `+00:00`.
- Organization login policy is `PASSWORD` or `SSO`, overriding the chart's company `SSO_ONLY` note. Individuals always have `PASSWORD` policy; an enabled Google login is an additional actual authentication method for an Individual account and creates no local password.
- Pending provider `issuer_url` and `client_id` can be null while status is `PENDING`. A configured active provider requires both. Secrets remain external; the database stores a secret reference.
- `credential_tokens.identity_provider_id` is an explicit nullable FK addition. First-admin `SSO_ENROLLMENT` links bind the intended trusted provider as well as the user, target email, purpose and security version.
- `auth_sessions.authorization_fingerprint` records the effective role/permission state. Generated unique-key columns enforce one ACTIVE/SUSPENDED membership per user, one active company provider per tenant and one unused/unrevoked refresh token per session on MySQL.
- Existing `book_details` and `book_upload_status` gain nullable tenant IDs, tenant-scoped name/receipt constraints and ownership FKs. Topic access is scoped through its parent book. No PDF content is rewritten.

**Existing catalog rows remain unassigned (`tenant_id IS NULL`) and inaccessible to newly registered tenants.** Registration never claims historical books or receipts. Keep this quarantine until an operator verifies ownership and performs a separately reviewed assignment. A new organization with a similar name or email domain does not join or take over an existing tenant.

## Install the migration and configure local authentication

1. Back up the existing database and inspect the script. Existing catalog tables and prior applicable manual migrations must already be installed. There is no Flyway/Liquibase bootstrap or replacement application.
2. Stop application writes while applying this schema change. Use a migration account with the required DDL privileges and run the script without `--force`, for example from MySQL:

   ```sql
   USE search_book_download;
   SOURCE C:/workspace/backend/Book_Search/Book-Search-Download/src/main/resources/db/manual/20261125_user_auth.sql;
   ```

3. The application now uses `spring.jpa.hibernate.ddl-auto=validate`; startup does not create or destructively update the authentication schema. Do not switch to `create`/`create-drop` to bypass a migration error.
4. Supply environment values using your process manager or secret store. [auth.env.example](config/auth.env.example) is a reference, **not an automatically loaded `.env` file**. No production credentials are supplied. `DB_PASSWORD` is external; `DB_URL` and `DB_USERNAME` can override the existing connection defaults.
5. Set `AUTH_JWT_SECRET` to Base64 encoding of at least 32 cryptographically random bytes. For the current PowerShell process, this generates a key without printing it:

   ```powershell
   $authKeyBytes = [byte[]]::new(32)
   $authKeyGenerator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
   try {
       $authKeyGenerator.GetBytes($authKeyBytes)
       $env:AUTH_JWT_SECRET = [Convert]::ToBase64String($authKeyBytes)
   } finally {
       $authKeyGenerator.Dispose()
       [Array]::Clear($authKeyBytes, 0, $authKeyBytes.Length)
       Remove-Variable authKeyBytes, authKeyGenerator
   }
   ```

   Preserve the same secret securely across application instances/restarts. The current supported signing configuration is HS256 via Nimbus/Spring Security. Missing/invalid key configuration does not require provider secrets at startup, but login/session issuance returns 503 until the signing key is configured. Rotating this key invalidates old access signatures; existing valid refresh sessions can still renew unless their sessions or user security versions are also revoked.
6. `AUTH_COOKIE_SECURE=true` is the default. For **explicit HTTP localhost development only**, set it to `false`; otherwise browsers will not send the refresh/CSRF cookies over HTTP. Production should use HTTPS and `true`. Set `AUTH_PUBLIC_BASE_URL` and comma-separated `AUTH_TRUSTED_ORIGINS` to exact trusted frontend origins; do not use wildcard origins with credentials.
7. Start the backend normally and the existing Angular app separately. The UI development proxy forwards `/api/**`, `/oauth2/**` and `/login/oauth2/**` to port 9090. Production reverse proxies must forward all three route groups while preserving callback paths and cookies; Angular routes retain their existing history fallback.

Use a same-origin or same-site HTTPS UI/API deployment with the current `SameSite=Lax` cookies. Explicit CORS configuration supports trusted origins and credentials, but does not make Lax cookies available to cross-site XHR. A fully cross-site deployment would require a deliberate cookie/CSRF configuration change.

## Mail and password registration

Mail, Google and company SSO all default to disabled. Set `AUTH_MAIL_ENABLED=true` only after configuring SMTP. The current SMTP defaults are Gmail on port 587 with authenticated STARTTLS, server-identity verification and bounded connection/read/write timeouts. Override standard `spring.mail.*` properties for a different SMTP server. Supply `SMTP_USERNAME`, `SMTP_PASSWORD` and, when different from the username, `SMTP_FROM`. Use an appropriate SMTP/app credential; never commit it. `app.mail.from` no longer has the earlier trailing hyphen.

For this Windows development workspace, [Configure-LocalSmtp.ps1](scripts/Configure-LocalSmtp.ps1) prompts privately for the sender's SMTP credential and writes `target/private-smtp/application-smtp.properties`, outside source control with file permissions restricted to the current Windows user and SYSTEM. Run it from the backend directory with `-SenderEmail YOUR_SENDER_EMAIL`; the defaults are Gmail/587/STARTTLS. It validates input/paths, escapes Java properties, writes UTF-8 without a BOM, and enables startup connection validation. It does not send email, restart services or alter accounts. Gmail app passwords require 2-Step Verification; follow [Google's instructions](https://support.google.com/accounts/answer/185833).

The ignored local runtime file imports those SMTP properties with `spring.config.import=optional:file:target/private-smtp/application-smtp.properties`. After saving a valid credential, restart the backend with its normal external local runtime file so the import takes effect, then use **Forgot password?** for the pending account. The SMTP sender and account receiving the setup link may be different. Never set the application user's password to an SMTP credential or paste future sender credentials into source/config examples. The current sender target is `kumar.dpo03@gmail.com`; the Super User recipient remains `kumar.dpk03@gmail.com`. The first supplied sender credential was rejected by Gmail in an authentication-only probe; no message was sent, the candidate secret file was removed, and the running application's SMTP settings remain unchanged until a valid private configuration is supplied.

The classpath plain-text Thymeleaf template is [credential-link.txt](src/main/resources/templates/user/credential-link.txt). It sends no passwords. Password registration requires an available configured sender; disabled/misconfigured mail returns 503 before creating an account. Registration delivery failures roll back provisioning. SMTP delivery happens inside that transaction; there is no durable outbox. A later database commit failure can therefore leave a delivered link unusable, rather than activate an uncommitted account.

Registration accepts no password. Individual registration collects email and display name; the backend ignores any requested SSO policy and generates a private tenant code. Organization registration also requires its display name, a unique tenant code and login type. Tenant codes are lowercased, contain 3–80 letters/digits/underscores/hyphens, and start with a letter or digit.

Provisioning atomically creates the user, new tenant, owner relationship, ACTIVE membership, **tenant-scoped ADMIN** role and role assignment. ADMIN receives active permissions from the seeded catalog and has no application-wide or cross-tenant privileges. There is at most one current ACTIVE/SUSPENDED membership; LEFT memberships are historical. Existing-account/code conflicts do not create another tenant or silently link an account. No unrestricted existing-tenant join endpoint is provided.

Password accounts remain pending with no credential until the recipient uses the one-time setup link. **Successful password setup itself verifies the intended email**, stores an adaptive `{pbkdf2}` hash, activates the account/workspace and advances its security version. Setup/reset then returns 204; the user signs in separately. Setup passwords must contain 12–128 characters; reset passwords require matching confirmation and 5–25 UTF-16 code units without composition rules and are never trimmed or sent by email. Display names are limited to 150 characters, email to 320 and organization names to 180.

Credential links use a 32-byte random secret; only its digest is stored. `SET_PASSWORD`, `PASSWORD_RESET` and `SSO_ENROLLMENT` are distinct purposes. Checks include intended email, issued security version, strict expiry, consumed/revoked state and atomic single use. Defaults are 24 hours for setup, 15 minutes for reset and 30 minutes for enrollment, configurable through `app.user.password.*-ttl`. Email links carry the credential secret in a URL **fragment**, which the Angular UI reads once and removes from browser history before posting it in the request body. Application access/refresh tokens never appear in URLs.

Forgot-password responses do not identify eligible accounts. This same endpoint resends `SET_PASSWORD` for pending Password accounts with no hash, including expired first-setup links; it issues `PASSWORD_RESET` only for active Password accounts with a hash. SSO/Google-only accounts do not gain a password. Globally unavailable mail returns the same 503 before lookup. An eligible request issues its token under the user lock and enqueues delivery **only after commit**, returning the same generic 202 without waiting for SMTP. Unknown/ineligible accounts create no jobs. A dedicated executor has at most two workers and a queue of 100, with rejection rather than caller-thread SMTP. Optional `app.user.mail.delivery-threads` (1–2) and `app.user.mail.delivery-queue-capacity` (1–100) can lower those bounds.

Forgot delivery failure, expiry in the queue or queue rejection revokes **only that token digest** under the user lock in a new transaction and writes a redacted failure audit; a later link is not revoked by an older job's failure. If cleanup storage is unavailable, the public response remains generic and the fixed `CREDENTIAL_DELIVERY_CLEANUP_FAILED` log event signals operator attention without an exception, email or secret. Such an outage can prevent revocation. There is no durable outbox: queued messages can be lost on process failure, and an undelivered token can remain until superseded or expired after an abrupt crash. Users can request another link subject to the same limit. Registration and operator enrollment delivery retain their synchronous transaction behavior. A user can receive at most five setup/reset links in 15 minutes, counting initial and revoked links; suppressed requests still return generic 202. Password/security changes revoke outstanding credential links, sessions and refresh tokens. Five failed password/MFA attempts lock a local credential for 15 minutes by default; missing MFA challenges do not count as guesses. Public ingress throttling is also bounded by IP/window; it is in-process, so a multi-instance deployment should enforce a shared ingress limit at its trusted gateway.

## HTTP contracts

All paths below are prefixed by `/api/user/auth`. JSON bodies are shown schematically. Obtain `GET /csrf` first and return its token in `X-XSRF-TOKEN` for state-changing requests, including login/registration/refresh/logout. Send cookies with credentialed requests; protected APIs additionally require `Authorization: Bearer <accessToken>`. API failures are JSON, not login-page redirects.

| Method/path | Request | Success |
| --- | --- | --- |
| `GET /features` | None | `{googleEnabled,ssoEnabled}` |
| `GET /csrf` | None | `{token,headerName}` and CSRF cookie |
| `POST /register` | `{tenantType,loginType?,email,displayName,tenantCode?,organizationName?}` | 202 `{message}` |
| `POST /password/forgot` | `{email}` | Generic 202 `{message}` |
| `POST /password/setup` | `{token,password}` | 204 |
| `POST /password/reset` | `{token,password}` | 204 |
| `POST /login` | `{email,password,mfaCode?}` | Session response + refresh cookie |
| `POST /refresh` | No token body; refresh cookie | New session response + rotated cookie |
| `POST /logout` | No token body; refresh cookie | 204; revokes that session/family and clears cookie |
| `GET /me` | Access bearer | `{userId,tenantId,membershipId,email,displayName,tenantName}` |
| `GET /oidc/google` | Browser navigation | 302 to local OAuth initiation, when enabled/configured |
| `POST /oidc/sso` | `{tenantCode,enrollmentToken?}` | `{authorizationUrl}` containing only a trusted local `/oauth2/authorization/{code}` path |

`tenantType` is `INDIVIDUAL` or `ORGANIZATION`; organization `loginType` is `PASSWORD` or `SSO`. The first dropdown in the UI is Tenant Type; Individual hides Login Type. Provider buttons remain disabled with an unavailable explanation while their flags are off, and backend initiation enforces those flags.

A session response contains `accessToken`, `tokenType` (`Bearer`), `accessExpiresAt`, `refreshExpiresAt`, nullable `absoluteExpiresAt`, `serverTime`, `sessionId` and `user`. It never contains the refresh secret. The refresh cookie is `BOOK_REFRESH`, HttpOnly, Secure by default, `SameSite=Lax`, scoped to `/api/user/auth`. Cookie Max-Age uses whole-second rounding; the database deadline is authoritative.

Authentication errors use HTTP 401, insufficient permissions/CSRF failures use 403, throttling uses 429 and unavailable configuration uses 503. Invalid input/credential links use 400; registration conflicts use 409. Structured errors include `status` and `detail`; authentication/security errors also provide `code`, such as `MFA_REQUIRED`, `MFA_INVALID` or `AUTH_NOT_CONFIGURED`. Do not log bodies, passwords, factor codes, credential fragments, refresh cookies or token-bearing OIDC callbacks.

## Session and authorization rules

- Access JWT `iat`/`nbf` are integral UTC NumericDate seconds and `exp = iat + 300`. Validation pins HS256, signature, issuer, audience, time claims and identifiers. JWT claims include user/tenant/membership/session IDs, security/authorization versions and the authorization fingerprint, not sensitive profile data.
- Database session/refresh times use UTC microsecond precision. Login creates a random opaque refresh secret expiring 600 seconds after issuance. A successful refresh before its current deadline consumes it atomically, creates a new 300-second access JWT and a new refresh secret/deadline 600 seconds from that rotation. Equality with the deadline is expired. Nullable absolute expiry is enforced when set; no extra absolute cap is imposed.
- Example: login at 12:00 gives access expiry 12:05 and refresh deadline 12:10; refresh at 12:04 gives access expiry 12:09 and refresh deadline 12:14. Access expiry alone does not end a still-refreshable session. Normal requests/last-seen updates never slide the refresh deadline.
- Refresh lineage and unique digest/parent/live-token constraints prevent two live children. Transactions lock the user before session/token rows. Reuse of a consumed refresh token commits family revocation even while returning an authentication error. Logout revokes the session/family immediately; an otherwise valid old access JWT cannot continue authorizing requests.
- Every protected request checks authoritative user, tenant, membership, session, provider and version state. Authorization is **uncached**. Role/permission fingerprints invalidate stale sessions when grant/status/version/prerequisite state changes. Endpoint requirements are resolved deterministically by service, method and path; absent/ambiguous protected policies fail closed. Multiple requirements use **ALL**, and action permissions require the corresponding active same-component VIEW grant. `/me` also follows its catalog policy.
- Angular keeps access tokens in memory. The shared interceptor queues concurrent foreground protected requests behind one refresh and retries once. Web Locks serialize cookie mutations across tabs; unsupported browsers fail closed, and BroadcastChannel carries ephemeral access/expiry/logout messages. Automatic upload polling and completion reloads use only a valid existing access token, pause when renewal is needed, and cannot rotate cookies. Explicit Resume is foreground usage. Session clearing cancels upload/download browser work and clears receipt, filename and notification state. Deadline checks clear the client on expiry/tab resume, and server 401/revocation remains authoritative. See the sibling UI README for browser support/verification details.

## Enable trusted Google or company OIDC

[application-oidc.example.properties](config/application-oidc.example.properties) is an external-configuration example, not loaded by default. Copy/adapt it outside source control and add it with `--spring.config.additional-location=file:C:/secure/application-oidc.properties`. Standard application properties and environment values remain in use. Keep the adapted file restricted and store actual provider secrets in the named environment/secret-store keys.

The `app.user.providers.<code>` allowlist must agree with the ACTIVE `identity_providers` row on issuer, client ID, secret reference and optional external tenant ID. Login input cannot provide an issuer/discovery URL. Issuers must be HTTPS. Callback URIs are exact `/login/oauth2/code/<code>` paths on a trusted backend/proxied origin, with no query, fragment or wildcard; HTTP is allowed only for localhost/127.0.0.1 development callbacks. Provider authorization uses standard Spring Authorization Code flow, explicit PKCE, state, nonce and validated ID tokens. Configure a PKCE-capable provider; do not disable these checks to make an incompatible provider work. See [Spring's PKCE customizer](https://docs.spring.io/spring-security/reference/api/java/org/springframework/security/oauth2/client/web/OAuth2AuthorizationRequestCustomizers.html).

### Google

1. Create an OIDC web client in your Google project and register the exact redirect URI used by this deployment, for example `https://books.example.invalid/login/oauth2/code/google` through the backend proxy, or `http://localhost:9090/login/oauth2/code/google` in local development. The registration code must be `google` and issuer `https://accounts.google.com`. Google's redirect URI must exactly match its registered value; see [Google's OIDC guide](https://developers.google.com/identity/openid-connect/openid-connect).
2. Supply the client ID and the environment key named by `secret-ref`; the sample uses `GOOGLE_CLIENT_SECRET`. The database stores the name `GOOGLE_CLIENT_SECRET`, never its value.
3. Set `AUTH_GOOGLE_ENABLED=true`. Run the operator setup once with your normal application command plus:

   ```text
   --spring.config.additional-location=file:C:/secure/application-oidc.properties
   --app.user.provider-setup.code=google
   ```

   This creates/configures the shared Google provider row. Remove the one-shot `provider-setup` argument on subsequent starts; retain the allowlist and enabled flag.

Every Google login requires a valid email and `email_verified=true`. Automatic creation for a **new subject** additionally requires either a normalized `@gmail.com` address or a nonempty `hd` claim from the validated Google ID token. This eligibility policy follows [Google's guidance on authoritative email claims](https://developers.google.com/identity/sign-in/web/backend-auth): verified third-party addresses without a hosted-domain claim can have stale ownership. Such new identities are rejected with email/password-registration guidance, without creating account or membership records; no extra linking/verification flow is invented.

An eligible new subject atomically receives an Individual tenant, owner/ADMIN membership and external identity, with no local credential. A hosted-domain claim does **not** join the user to a company tenant. Repeated logins use `(identity_provider_id, subject)` and still require a valid verified email, while the new-account Gmail/hosted-domain restriction is not reapplied to an already linked subject. Provider email changes never automatically update the internal email or link another account. An email match with an existing account is rejected for implicit linking; it neither merges accounts nor grants another current membership. There is no public account-linking shortcut in this implementation.

### Company SSO and first administrator enrollment

1. Register a new Organization with `loginType: "SSO"`. It remains `PENDING_SETUP`, has a pending `ORG_OIDC` provider code `org-{tenantId}`, and receives no Set Password email/local credential. Registration is allowed while SSO login is disabled so an operator can complete setup later.
2. An authorized operator obtains the exact pending tenant/provider/user IDs from the database, validates the intended company's provider, and adds that exact provider code to external trusted configuration. Supply its issuer, client, secret reference, exact callback and, when needed, `external-tenant-id` (validated against the ID token's `tid` claim).
3. Set `AUTH_SSO_ENABLED=true` and configure SMTP. Start once with the adapted allowlist and:

   ```text
   --spring.config.additional-location=file:C:/secure/application-oidc.properties
   --app.user.provider-setup.code=org-YOUR_TENANT_ID
   --app.user.provider-setup.enroll-user-id=YOUR_PENDING_OWNER_USER_ID
   ```

   Replace both placeholders with the actual pending records. This is an operator-only startup command, not a public API. It activates the trusted provider and sends a distinct provider-bound `SSO_ENROLLMENT` link to the pending owner's registered email. Remove both one-shot arguments afterward.
4. The recipient follows `/user/enroll#token=...&tenantCode=...` and authenticates at that trusted provider. Successful enrollment requires the high-entropy unexpired proof, intended email, unchanged security version, owner/current membership and intended provider/tenant. **Matching email alone cannot link the privileged account.** The proof verifies email ownership; a trusted provider subject becomes the permanent identity key. It activates the account/workspace without creating a password.
5. Later company sign-ins use tenant code plus the existing provider subject. Unknown subjects are rejected; there is no automatic company join. A provider with linked identities cannot be repointed to another issuer/client/external tenant. Reconfigure trust through a separately reviewed provider change rather than reusing its identity key.

OIDC callback success writes the same application refresh cookie and redirects to `/user/callback` without application tokens in the URL. The frontend obtains its in-memory access token by refreshing once. Failure redirects to `/user/provider-error`. The temporary HttpOnly/Secure/Lax servlet cookie holds only the OIDC handshake; its session is cleared afterward and cannot authorize application APIs.

## Optional MFA and current limits

There is no automatic or mandatory MFA enrollment. Existing ACTIVE, confirmed `local_mfa_factors` are honored for password login before issuing a full session. Current support is TOTP with `secret_ref=env:YOUR_SECRET_KEY`, 30-second steps with a one-step tolerance and replay protection, plus one-use adaptively hashed recovery codes. Actual secrets remain outside the database. No public factor-enrollment/administration UI or endpoint is introduced.

Password/factor failures share the persistent credential lockout; entering a correct password does not reset failed second-factor attempts. Only a successfully completed factor clears them. The user's observed security version is rechecked under lock before consuming a TOTP/recovery code and again before session issuance.

**Current OIDC/MFA limitation:** a Google/SSO user with a configured active local factor is denied a full session; this implementation has no OIDC-to-local-factor challenge handoff. It fails closed. Do not provision a local factor for an OIDC-only user expecting an unsupported handoff. Existing-app inspection found no previously configured MFA integration. Pending Password users recover expired setup links through the existing forgot-password form; no additional public resend/join endpoint is introduced. Do not bypass provider/MFA limits by issuing sessions or linking privileged accounts from email matches.

## Verification status and files

Final full backend verification passed: **565 tests run, 556 passed, 9 skipped, 0 failures and 0 errors**, followed by successful executable-JAR packaging in the same run (`target/auth-complete-verify.log`). All authentication tests ran; the skips are eight existing optional source-PDF fixture checks and one optional live-AI check. The final suite includes 19 account-flow tests, queued recovery delivery and cleanup-failure containment, refresh concurrency/replay commit checks, tenant isolation, and 7 full-chain HTTP security tests including unmatched OIDC callback auditing. The Angular suite passed **172 tests across 34 files**, including **39 auth tests across 8 files**, strict app/spec TypeScript checks, and a warning-free production build (**448.04 kB raw / 96.42 kB estimated transfer**). Frontend checks include unattended polling through the five/ten-minute boundaries, explicit Resume renewal, late upload-completion reloads, and cancellation/clearing of prior-account browser work.

The actual MySQL migration and its idempotent rerun passed in an isolated database (18 new tables plus four catalog tables, five permissions and ten endpoint policies), followed by 16 constraint checks whose synthetic transaction was rolled back. The migration was then applied to the configured application database after a backup and zero-active-upload check. Its **5 books, 39 topics and 9 receipts** were preserved; historical book/receipt ownership remains null, and no real users were created. Both local servers were restarted with the updated code/proxy configuration. The backend starts successfully with Hibernate schema validation, provider flags off and no provider/SMTP secrets.

After restarting the final executable JAR, **17 live HTTP checks** passed through the Angular proxy (`target/auth-release-smoke.log`): features and CSRF contracts; JSON 401 for unauthenticated catalog/read/download/receipt/metadata/upload requests; 503 for disabled providers; invalid-login and missing-refresh-cookie 401; logout cookie clearing; SMTP-disabled registration and recovery; untrusted-origin rejection; and a safe unmatched-provider callback redirect. The real database still has zero users/sessions/refresh rows, zero active uploads, all 5 books/39 topics/9 receipts preserved, and all historical ownership unassigned. Successful registration/setup/login/refresh/logout, expiry, concurrency and tenant authorization are covered with the real MVC/security chain and isolated transactional databases, with only outbound mail mocked.

Real Google/company-provider logins, SMTP delivery and browser/mobile authentication interaction remain unperformed. The browser access host was unavailable. The current OIDC/local-MFA handoff limitation above remains explicit.

Relevant checks can be run from this backend directory:

```powershell
mvn '-Dmaven.repo.local=target/maven-repository' -o verify '-DargLine=-Dpdfbox.fontcache=target -Djava.io.tmpdir=C:/workspace/backend/Book_Search/Book-Search-Download/target/test-tmp'
```

The workspace's prepared offline Maven cache can additionally use `'-Dmaven.repo.local=target/maven-repository' -o`; use offline mode only after dependencies are available. New tests use isolated H2 schemas from the production DDL with documented MySQL storage-syntax adaptations; MySQL migration validation is a separate check. Frontend commands remain `npm test -- --watch=false` and `npm run build` in the sibling UI directory. Final command results belong in the root READMEs/release report.

The final full `verify` command above ran with `TEMP` and `TMP` also directed to the workspace's `target/test-tmp`, completed in 5 minutes 30 seconds, and packaged the JAR without skipping tests. Current local startup uses the ignored development configuration without printing its key:

```powershell
java -jar target/Book-Search-Download-0.0.1-SNAPSHOT.jar --spring.config.additional-location=file:target/local-user-runtime.properties
```

Use that file only for this local development workspace. Production should provide its own external database/signing/SMTP settings and keep Secure cookies enabled. The UI is at `http://localhost:4200/user/login`; the backend is on port 9090. A live log check confirmed the development signing key and submitted verification password were absent, and no generated default security password was logged.

Changed-file groups for this feature are:

- `src/main/java/com/book/user/controller`, `dto`, `exception`, `repository`, `service`, `security`, `config`, `provider` and `util`: account flows, sessions/JWTs, security chain, policies, tenant context and provider integration.
- `src/main/resources/db/manual/20261125_user_auth.sql`: versioned schema, catalog ownership and permission/endpoint seeds; `src/main/resources/application.properties` and `pom.xml`: external configuration and existing-version Spring Security/OIDC/mail dependencies.
- `src/main/resources/templates/user/credential-link.txt`: actual setup/reset/enrollment email template.
- Existing search/metadata/upload/download entry points, entity/repository queries and background-work context: tenant-scoped authorization/storage access.
- `src/test/java/com/book/user/*Tests.java`, shared schema helper and updated existing tenant-aware tests: authentication, concurrency, policy and cross-tenant regressions.
- Sibling UI `src/app/features/user/`, routing/header integration and proxy configuration: auth container/forms, guards/interceptor, refresh coordination, callback and expiry UX. Existing book API/upload/Home/download services and shared notifications also carry the background-request policy and auth-boundary cleanup.
- This guide, [auth.env.example](config/auth.env.example) and [application-oidc.example.properties](config/application-oidc.example.properties): setup references only; no live provider/email operations.
