# Password recovery

Extends the existing `com.book.user` backend and Angular `features/user` routes coordinated by UserComponent. The existing login page links to `/user/password/forgot`; email opens `/user/password/reset#token=...`. Opening a link never changes a password. After reset the UI clears its authentication state and returns to login with a confirmation. Google and company SSO users should use their provider sign-in; recovery never creates a local credential for an active provider-only account.

## API contract

Both endpoints are public but require the existing CSRF cookie/header (`GET /api/user/auth/csrf` first) and retain exact-origin credentialed CORS. The interceptor excludes public authentication endpoints from bearer injection/refresh retries.

| POST endpoint | JSON request | Success |
| --- | --- | --- |
| `/api/user/auth/password/forgot` | `{"email":"reader@example.com"}` | 202 `{"message":"If an eligible account exists, a password reset email will be sent."}` |
| `/api/user/auth/password/reset` | `{"token":"<email secret>","password":"abcde","confirmation":"abcde"}` | 204, no automatic login |

Email is the username. It is normalized for account lookup only; delivery uses the persisted registered email. Known, unknown and provider-only accounts receive the same public confirmation. Pending password registrations retain the existing verified setup-link resend behavior. Missing/invalid email is a 400 input error. Password values must match exactly and be 5 through 25 UTF-16 code units inclusive, matching Java `String.length()` and JavaScript `String.length`. An emoji outside the BMP counts as two units; combining marks count separately. Whitespace, case and normalization are preserved. There are no composition rules, including no mandatory `@`. Setup retains its separate existing 12–128 policy.

Invalid requests return structured ProblemDetail JSON with `status`, `detail`, and `code`. Expired, consumed, revoked, wrong-purpose, stale-version, wrong-email and unknown tokens return 400 with a safe message offering another link. Recovery request ingress is limited to five per IP per 15 minutes; token submissions to 20 per IP per minute. The existing persistent account limit permits at most five issued setup/reset links in 15 minutes. IP limits are process-local: use shared trusted-gateway throttling for multiple instances.

## Storage and deployment

The existing schema already has all required fields and relationships in users, local_credentials, credential_tokens, auth_sessions, refresh_tokens and audit_events. No new table or column is needed. Apply `src/main/resources/db/manual/20261125_password_reset_purpose.sql` after the existing authentication migration while deploying this update. It revokes outstanding legacy `RESET_PASSWORD` links; users can request new `PASSWORD_RESET` links. Existing history is preserved. The script is idempotent and has not been applied to the application database by this change.

Secrets are 32 random bytes encoded base64url. Only SHA-256 digests persist. The transaction locks the user then the credential token, checks all bindings, atomically consumes it, updates the adaptive hash/password timestamp/lockout state, increments security_version and revokes outstanding credentials, sessions and refresh tokens. Existing session validation rejects old access JWTs immediately. Access JWTs remain five minutes and rotating refresh tokens remain ten minutes.

## Configuration

For local development, recovery email is enabled by default in `application.properties` and uses that file's existing SMTP settings. No private SMTP file is required. Restart the backend after editing these settings. `AUTH_MAIL_ENABLED=false` remains an explicit override to disable delivery; environment and external configuration retain Spring's normal precedence. Enabling the feature does not verify SMTP authentication or delivery.

Supply SMTP credentials externally; `config/auth.env.example` contains empty placeholders and is not automatically loaded. Set `AUTH_MAIL_ENABLED=true`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_FROM` and the existing `spring.mail.host`/`spring.mail.port` settings for your sender. Set `AUTH_PUBLIC_BASE_URL=https://books.example.com` and `AUTH_TRUSTED_ORIGINS=https://books.example.com`. Only configured HTTPS bases are accepted, with HTTP localhost allowed for development. Host headers and redirect parameters are never used for email links.

Reset-link lifetime defaults to 15 minutes; override with `PASSWORD_RESET_TTL` (ISO-8601 duration, e.g. `PT15M`). It does not alter session lifetimes. A bounded background executor delivers the existing plain-text Thymeleaf template. Disabled/unavailable email configuration returns a generic operational 503 for every account; SMTP/queue failure revokes that exact token and audits failure without revealing eligibility. The 202 response promises no completed delivery. There is no durable outbox: process crashes can lose queued jobs; users may request another link. Monitor `PASSWORD_RESET_DELIVERY` failures and the fixed redacted cleanup-failure log.

The Angular reset secret is captured only in memory and immediately removed from the URL fragment. The page uses `no-referrer`, no analytics, and never stores the secret in localStorage or attaches it to unrelated requests. Request/failure audit records omit recipient, secret and password; successful reset audit occurs in the password transaction.

## Verification

Backend tests use real transactions and isolated H2 schemas derived from the production manual migration, with mocked outbound mail. They cover exact password/Unicode boundaries, confirmation, registered recipient, provider restrictions, invalid token states, concurrency, lockout clearing, session/refresh revocation, and actual HTTP rejection of old JWTs. Angular tests cover the actual recovery/login routes and HTTP contract with HttpTestingController, plus length/Unicode/mismatch, duplicate submission, safe link failure and secret removal. These are in-process journey tests, not a live SMTP/browser deployment test. Test execution outcomes are recorded below after checks finish.

Executed on November 25, 2026:

- Backend: `mvn '-Dmaven.repo.local=target/maven-repository' -o '-Dtest=com.book.user.*Tests' test` — **147 passed**, zero failures/errors/skips. TEMP/TMP pointed to `target/test-tmp`. Final execution used native Windows access after restricted Java entropy generation stalled. Log: `target/password-reset-tests-final.log`.
- Frontend: `npm test -- --watch=false` — **184 passed across 34 files**. Log: `tmp/password-reset-tests.log`. An intermediate run had one existing reader timing-test failure; the final full rerun passed.
- Frontend: `npm run build` — **passed**. Log: `tmp/password-reset-build.log`.
- Not run: full unrelated backend suite, application-database migration, live SMTP delivery, or a deployed browser journey. The application database and running deployment were not modified by verification.

Changed areas: backend `PasswordService`, `CredentialMailService`, `AccountController`, reset-purpose SQL, reset lifetime property and environment example; Angular `PasswordComponent`, `LoginComponent`, `AuthApiService` and `index.html` referrer policy; backend `AccountFlowsTests`, `CredentialMailTests`, `UserSecurityIntegrationTests`, Angular page/route journey tests, and recovery/setup/README documentation. Existing repository changes were preserved.
