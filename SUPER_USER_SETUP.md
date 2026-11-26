# Super User provisioning

The current [role management and storage increment](ROLE_MANAGEMENT_STORAGE.md) supersedes earlier automatic ADMIN registration and cross-tenant file access: registrations get ordinary OWNER; protected ADMIN is super-user-managed; file/search ownership remains tenant-bound even for super users. Explicit cross-organization role management remains available to super users.

`SUPER_USER` is a reserved application-wide **system role**, assigned through the existing `roles`, `user_roles` and `role_permissions` tables. This change creates no tables or columns. Ordinary registration still creates only a tenant-scoped `ADMIN`; supplying a role, tenant ID or matching email in a public request never grants Super User access.

An active, verified account with an ACTIVE tenant, current membership and assigned ACTIVE system `SUPER_USER` receives every active catalog capability, including newly introduced capabilities, subject to active same-component VIEW prerequisites. Endpoint policies must still be mapped, active and unambiguous. Authentication, MFA where configured, CSRF, session deadlines and logout/revocation remain enforced.

The role can search, read, download and edit **assigned** books and inspect assigned upload receipts across tenants. Historical rows with null ownership remain inaccessible, as explicitly requested. New uploads belong to the actor's current tenant; background upload workers receive that tenant without inheriting application-wide privilege. Editing a foreign book does not change its ownership, and duplicate-name checks use the book's owning tenant.

The catalog includes `BOOKS.VIEW`, `BOOKS.UPLOAD`, `BOOKS.DOWNLOAD`, `BOOKS.EDIT`, `BOOKS.DELETE` and `ACCOUNT.VIEW`. The existing application has no book Delete endpoint or UI control. `BOOKS.DELETE` provides the permission for an implemented deletion operation; this role change does not introduce deletion behavior or remove files/books.

## Apply the additive permission migration

Back up the application database and apply [20261125_super_user_permissions.sql](src/main/resources/db/manual/20261125_super_user_permissions.sql) after the existing authentication migration. The script adds the deletion permission and fills active system-superuser grants, is safe to rerun, and preserves explicitly disabled catalog entries. It changes no catalog ownership or book metadata. No disposable database is required.

## Run the explicit operator command once

Run the normal backend startup with the additional target argument:

```powershell
java -jar target/Book-Search-Download-0.0.1-SNAPSHOT.jar --spring.config.additional-location=file:target/local-user-runtime.properties --app.user.super-user.email=kumar.dpk03@gmail.com
```

The runtime-properties file above is this workspace's ignored development configuration. Other deployments should use their normal external configuration. `--app.user.super-user.display-name="Your Name"` is optional; a new account's display name defaults to its email. Email is the login username, so no separate username column is added.

Remove `--app.user.super-user.email` on subsequent starts. It is deliberately absent from normal configuration and defaults to no action. The command is idempotent, but retaining an operator provisioning argument could restore a deliberately revoked system role on a later restart. No email address is hardcoded as an automatic privileged allowlist.

For an existing active, verified account, authentication method, provider links, password and verification state are preserved. A suspended/left membership, inactive account/tenant, ambiguous association or collision with a non-system role using the reserved code fails closed. An existing configured system role can be explicitly reactivated by the operator command.

For a new account, the command creates a **PENDING Individual PASSWORD** user and tenant, an ACTIVE current membership, the normal tenant ADMIN and reserved Super User assignment. It creates no password, email, credential token or authenticated session. The account remains unable to log in until normal email/password setup completes. Pending company SSO setup is never converted into password authentication.

## Complete password setup

Configure SMTP using [AUTH_SETUP.md](AUTH_SETUP.md) and enable `AUTH_MAIL_ENABLED=true`. Open the Angular Login page, choose **Forgot password**, and enter `kumar.dpk03@gmail.com`. For this pending Password account the existing recovery flow sends a one-time **Set Password** link to that address. Using that link verifies the intended email and activates the account; then sign in using the email and chosen password. Super User provisioning itself never marks an unverified email as verified.

If SMTP is disabled, the account and role can be provisioned, but password setup and login remain pending. No default password or alternate verification bypass is provided.

## Revocation and audit

Granting or changing the reserved role advances authorization versions and revokes the target user's existing session/refresh families. An unchanged rerun does not churn versions, audits or sessions. A redacted `SUPER_USER_GRANTED` audit identifies the target user and tenant, with a null actor for the external operator command.

Authoritative request checks include role code, system flag, grant/status/version state and the entire Super User permission catalog in the session fingerprint. Removing the assignment, suspending the role or changing its reserved/system classification invalidates old access and refresh credentials. The role never bypasses disabled identities or expired/revoked sessions. Operator changes should be made transactionally and audited; there is no public HTTP privilege-grant endpoint.

## Verification

The final full backend command passed **615 tests run, 606 passed, 9 existing optional checks skipped, 0 failures and 0 errors**, and packaged the executable JAR. All new role tests ran, including **20 provisioning cases, 15 real MVC/security cases**, reserved-role/capability policies, actual JPA cross-tenant/NULL-ownership isolation, foreign PDF reading/editing and worker-scope regression checks. The skips remain eight optional source-PDF fixtures and one optional live-AI check. The log is `target/super-user-verify.log` (8 minutes 3 seconds).

```powershell
mvn '-Dmaven.repo.local=target/maven-repository' -o verify '-DargLine=-Dpdfbox.fontcache=target -Djava.io.tmpdir=C:/workspace/backend/Book_Search/Book-Search-Download/target/test-tmp'
```

`TEMP` and `TMP` were also directed to `target/test-tmp`. MySQL validation applied the new permission migration twice in a transaction and rolled it back, then applied it with an idempotent rerun to the application database after a verified 22-table backup. The resulting catalog has six permissions and the database still has 22 tables. The five books, 39 topics, nine receipts and their extended data checksums were preserved. The backup is `target/db-backups/20261125_super_user/before-super-user.sql`; migration results are in `target/super-user-migration.log`.

No frontend source change was needed, so Angular checks were not repeated for this increment. Real email delivery and an interactive login for the new account require SMTP/password setup. Existing browser/mobile and live-provider limitations remain documented in `AUTH_SETUP.md`.

The explicit local command provisioned `kumar.dpk03@gmail.com` (email and username), user ID 1 / tenant ID 1 / membership ID 1, with ADMIN and ACTIVE system SUPER_USER and all six explicit catalog grants. Read-only MySQL verification confirmed one account, one grant audit, no local credential, credential token, external identity or authenticated session, and preserved unassigned historical ownership. The account and tenant are PENDING until normal email/password setup; SMTP is disabled and no sender credentials are configured in this workspace. Details are in ignored `target/super-user-provisioning-check.log`. The backend is subsequently started normally without the one-shot provisioning argument.

After the normal restart, the same MySQL assertions passed again and **20 live HTTP checks** passed through the Angular proxy (`target/super-user-live-smoke.log`). They cover existing protected APIs, feature flags, CSRF/CORS, refresh/logout, disabled providers, safe provider-error redirects, the pending Super User's login denial, mail-disabled setup recovery, and rejection of forged role headers. The normal startup did not run provisioning again; log checks found no signing key, probe password or generated default password. The local backend remains on 9090 and the existing UI on 4200. These checks verify pending provisioning and API enforcement; successful real-account email delivery/login remains unperformed until SMTP and password setup are completed.

Changed files for this increment include the two new provisioning service/runner classes, `AuthorizationService`, `UserAuthenticationFilter`, `TenantContext`, scoped search/receipt/metadata queries, the additive SQL above, and focused provisioning, policy, HTTP, tenant-isolation and worker tests. Frontend routes and response contracts are unchanged.
