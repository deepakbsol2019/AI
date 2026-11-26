# User Management and individual storage

The later [management pages/sidebar increment](MANAGEMENT_PAGES.md) replaces the details popup with a routed full page, moves own profile/logout to the shared sidebar, adds explicit current-tenant invitations and distinguishes soft deletion from inactivity. Its migration and verification supersede corresponding earlier descriptions below. Existing SUPER_USER global/deletion policies remain explicitly confirmed.

This increment adds the authenticated `/user/users` Angular page, its backend APIs, an own-profile header panel, and registration-time storage provisioning for standalone accounts. It extends [role management and storage](ROLE_MANAGEMENT_STORAGE.md). The existing trusted system role `SUPER_USER` supplies global user-management access. No client-created role name grants that identity.

## Access and profile fields

An active system ADMIN needs explicit `USER_VIEW` and the relevant action grants. Its list, search, status filter, total count, pagination and detail queries include only ordinary employees in its own organization. Other admins, super users, the tenant owner and the actor are excluded. Foreign/unauthorized target lookups return 404. Merely assigning USER permissions to an ordinary custom role does not confer ADMIN status. New registrations receive the existing ordinary OWNER role and only the explicit library/account permission allowlist; this migration grants no new user-management permissions to existing roles.

The details popup displays actual identity, tenant, assigned roles, effective permission sources, status and timestamps. Missing values are labeled. `display_name` is the model's only editable ordinary profile field. Username is the immutable primary email. The profile request allowlist accepts only `displayName` and the returned `version`; unsupported fields, including password, tenant and roles, fail. Role assignment and activation use separate APIs. Direct per-user grants are not supported by this model. Passwords, credential hashes, reset/session/access tokens and MFA material are absent from all management DTOs.

Save refreshes the row/details while retaining search, status and page. Cancel/close handles unsaved profile and role edits. Dialogs support keyboard focus, Escape, and outside dismissal. The header's top-right profile control is available to every authenticated user, loads only `GET /api/user/profile`, and shows their username, available profile information and assigned role names. It has no permissions list or editing. The endpoint accepts no target ID. The store clears on logout/identity change and rejects late responses for an earlier identity. Opening the panel reloads it; saving one's own supported profile details refreshes it too. Role changes revoke the affected account's sessions and require a new login.

## Deletion policies

| Actor and target | Soft deletion | Hard deletion |
| --- | --- | --- |
| Super user, ordinary employee | Only that account becomes INACTIVE; retain everything | Remove account and exclusive data only. See ownership restriction below. Never remove shared company root. |
| Super user, standalone individual | Only that account becomes INACTIVE; retain everything | Purge its individually owned tenant and exact directory only when its owner and sole current member match the selected account. |
| Super user, tenant ADMIN | Every current tenant user becomes INACTIVE; preserve company, metadata and every path | Explicitly purge the entire company, including other admins/users, tenant associations/content and its root. |
| Delegated tenant ADMIN, ordinary employee | Only that account becomes INACTIVE; retain everything | Physically remove account/authentication associations; retain all company content, files and directories. |

Highest-privilege accounts and the current actor cannot be deactivated, activated, assigned roles or deleted through these actions. A tenant containing a protected SUPER_USER assignment cannot be deactivated or purged wholesale. Non-credential display-name editing remains permitted to a globally authorized super user. Cross-tenant account/provider ownership conflicts block a purge for operator review.

**Ownership restriction:** books and upload receipts identify their tenant, but have no reliable individual uploader ownership. Therefore a super user's destructive employee cleanup is blocked when the company has any book metadata, receipt, file or child directory. Shared folders are never interpreted as exclusive employee ownership. Removing an employee from an otherwise empty company preserves its root. Delegated-admin account-only deletion remains allowed when the company contains data and preserves all of it. No uploader reference needs transfer because the existing content model has no user FK. Historical unassigned/shared content is never adopted or swept into a tenant purge.

Deletion preview is calculated by the server. It includes company name/ID, account count, scope, preservation behavior, current version and the exact required phrase (`DELETE TENANT <id>`, `DEACTIVATE TENANT <id>`, or the corresponding USER phrase). Execution rechecks authorization, target classification, version, scope and count under locks. Clients cannot submit file paths or their own ownership manifest.

## Durable hard-deletion workflow

1. Transactionally validate and snapshot ownership. For a tenant purge, persist the exact directory manifest (relative entries, type, size and SHA-256) before removing metadata. Mark affected accounts DELETING, revoke credentials/sessions and block new tenant activity. A storage marker also fences late upload workers.
2. Persist `user_deletion_jobs` with a unique active job per tenant. The API responds **202 PENDING**, not completed. Tenant/account and job row locks serialize related mutations. Registration into a new tenant is unaffected; existing role-management mutations reject pending tenant jobs.
3. The worker leases a job, revalidates current actor permissions, membership, protected accounts, content scope and active uploads, then removes applicable DB associations/content. Authentication dependencies are removed in FK order. Audit records survive with numeric actor/tenant references retained in their details. Search uses the existing tenant-filtered database queries; deleting catalog rows removes those search entries. There is no separate external search index or authorization cache to invalidate.
4. Commit `db_purged=true`, `CLEANUP_PENDING`. Validate the saved manifest against the configured root and every existing entry before filesystem deletion. Unexpected/changed files, unsafe paths and links fail closed. Delete only validated entries, deepest first; missing entries are safe on retry. The job row remains locked through cleanup to fence concurrent workers.
5. Report COMPLETE only after required filesystem cleanup. A crash resumes after lease expiry; an exception reports FAILED with the database-purge flag and a safe error. A super user reviews/corrects the cause and explicitly retries. Account-only jobs require no file deletion. Full-tenant deletion retains an external numeric storage marker to prevent late worker recreation of that removed root.

The page exposes recent job statuses and explicit refresh/retry, including after page reload. Failed jobs continue blocking new tenant activity. A hard-deleted account is never recreated by activation. Database purge and filesystem cleanup are distinct commits; a FAILED job may already have removed the account, which is why its `databasePurged` status is shown. Never edit manifests or remove storage locks to force completion without an ownership review.

## Activation and role assignment

Only the super user can activate an existing INACTIVE selected account. This does not activate other tenant users. If the tenant itself is INACTIVE/SUSPENDED, the dialog requires explicit `resumeTenant=true`; other tenant policy states remain blocked. Email verification, current membership, credentials and identity-provider checks still apply. Revoked access/refresh/reset credentials stay revoked; the selected user needs fresh authentication.

Only the super user can replace selected user-role assignments through this page. Available choices are active non-SUPER_USER roles in the target tenant, including its protected ADMIN role. Foreign role IDs and highest-account mutations fail. Assignment is version checked, recorded with old/new role IDs, and revokes stale sessions. Existing role-definition APIs retain delegation ceilings. Their member-assignment operation also rejects changes to ADMIN members by ordinary admins and rejects SUPER_USER members even when the requested role itself is ordinary.

## APIs

All endpoints use existing authentication, CSRF and method/path policy handling. Action permissions require active USER_VIEW. GET own profile is a narrowly scoped authenticated exception after its active mapped endpoint policy is resolved; no management grant or ACCOUNT.VIEW grant is needed to see oneself.

| Method and path | Permission / purpose |
| --- | --- |
| GET `/api/user/profile` | Own authenticated profile only |
| GET `/api/user/users?search=&status=ALL&page=0&size=20` | USER_VIEW; optional super-user `tenantId` filter; max size 100 |
| GET `/api/user/users/{id}` | USER_VIEW; scoped details and effective permission sources |
| PUT `/api/user/users/{id}` | USER_EDIT; `{displayName,version}` only |
| GET `/api/user/users/{id}/deletion-preview?hard=true` | Corresponding USER_HARD_DELETE or USER_SOFT_DELETE |
| POST `/api/user/users/{id}/soft-delete` | USER_SOFT_DELETE; `{version,scope,affectedUsers,confirmation}` |
| POST `/api/user/users/{id}/hard-delete` | USER_HARD_DELETE; same confirmation DTO; 202 job |
| POST `/api/user/users/{id}/activate` | USER_ACTIVATE plus super user; `{version,resumeTenant}` |
| GET `/api/user/users/{id}/assignable-roles` | USER_ROLE_ASSIGN plus super user |
| PUT `/api/user/users/{id}/roles` | USER_ROLE_ASSIGN plus super user; `{roleIds,version}` |
| GET `/api/user/users/jobs` | USER_VIEW; latest 100 authorized jobs |
| GET `/api/user/users/jobs/{id}` | USER_VIEW; super global, admin own-tenant account-preserving jobs only |
| POST `/api/user/users/jobs/{id}/retry` | USER_HARD_DELETE plus super user; FAILED jobs only |

USER_VIEW, USER_EDIT, USER_SOFT_DELETE and USER_HARD_DELETE are delegable to an explicitly authorized tenant ADMIN through the existing role workflow. USER_ACTIVATE and USER_ROLE_ASSIGN are not delegable. Existing inactive users remain visible with the INACTIVE filter; it includes non-ACTIVE lifecycle states, whose exact statuses are displayed. Activation is offered only for INACTIVE records.

## Standalone storage and configuration

Standalone registrations already receive their own persisted INDIVIDUAL tenant ID. Both organization and standalone registration now provision `C:\workspace\backend\Book_Search\upload-pdf\<tenantId>` through `TenantStoragePaths`; provisioning is idempotent and reports failures. A new employee reuses its company tenant/root. No username-derived namespace or fabricated ID is used. Existing authenticated tenant path confinement, strict tenant search/download scope, and symlink/junction checks remain enforced even for super users.

The Windows base was verified on this host with a disposable create/write/delete probe on November 26, 2026. The probe was removed. Application OS identities on other hosts must have the corresponding access.

| Environment setting | Default | Meaning |
| --- | --- | --- |
| `TENANT_STORAGE_ROOT` | Existing supplied Windows base | Central storage base |
| `USER_DELETION_ENABLED` | true | Enable scheduled durable deletion worker |
| `USER_DELETION_POLL_MS` | 5000 | Worker polling interval |
| `INDIVIDUAL_STORAGE_BACKFILL` | false | Explicit startup backfill for missing persisted individual roots |

Backfill is opt-in: set `INDIVIDUAL_STORAGE_BACKFILL=true` for an operator-controlled startup, review ready/failed tenant IDs, then unset it. It verifies persisted individual ownership/current membership and excludes deletion jobs. It creates directories only, never copies files, adopts legacy shared data, changes ownership, or creates company roots. Failure is explicit and safe to retry after correcting host permissions. No live backfill was executed during this task. Existing shared-path migration remains a separate ownership-reviewed operation described in the storage guide.

## Migration and rollout

Apply prior authentication, super-user and role-management migrations first, then `src/main/resources/db/manual/20261126_user_management.sql`. Back up, quiesce application writes, apply without `--force`, and restart both applications after deploying the code. MySQL DDL commits independently. This additive, rerunnable migration adds `users.management_version`, the durable job table, six permissions and thirteen method-specific endpoint mappings. It does not change existing role grants or user/tenant statuses. The job table intentionally has no account/tenant FKs so recovery information survives purges.

On this workspace's configured local MySQL database, the migration and its idempotent rerun passed. The affected schema/catalog/user backup is `target/db-backups/20261126_user_management_0434a4a4/schema-catalog-users-before.sql` (contains private user profile data; keep private). The adjacent `migration-result.log` records verification. Existing users, tenants, memberships, roles, assignments and library data were fingerprint/checksum verified unchanged; the new management-version default is the intended addition. Zero live deletion jobs existed before or after deployment. No live user deactivation, deletion, role/profile edit, tenant-folder deletion or storage backfill was performed. New code still needs the normal application restart/reload; deployed browser interaction was not claimed.

## Verification

All destructive tests use isolated H2 schemas and temporary fixture directories. Security tests cover every deletion-matrix row, list/count/pagination scope, direct HTTP/CSRF requests, forged IDs and immutable fields, detail grants, selected-account activation, old-session/refresh rejection, role isolation and protected targets, stale confirmations, worker-authority changes, failure after DB purge, changed-file manifest rejection and safe recovery, individual registration directories and ownership-safe backfill. Existing storage regressions cover shared-root reuse, retries, unsafe IDs/traversal, Windows junctions and concurrent provisioning. Angular tests cover table/modal states, Save/Cancel/unsaved handling and retained context, role/activation/deletion controls, cleanup statuses, and the own-profile panel without permissions/secrets.

Final checks on November 26, 2026:

- Backend: `mvn '-Dmaven.repo.local=target/maven-repository' -o test` with TEMP/TMP set to `target/test-tmp`: **669 total, 660 passed, 9 skipped, zero failures/errors**. Log: `target/user-management-final-tests.log`. This includes 15 new user-management service/storage tests, 5 new real MVC/security tests and the additional protected-member role regression.
- Angular: `npm test -- --watch=false`: **203 tests passed across 37 files**. Log: `tmp/user-management-tests.log` in the UI project. This includes nine user-page tests and three header/profile tests.
- Angular: `npm run build`: **passed**. Log: `tmp/user-management-build.log` in the UI project.
- Actual MySQL additive migration, idempotent rerun, thirteen endpoint mappings, six permissions, unchanged existing-data fingerprints/checksums and zero live deletion jobs: **passed**.
- Actual supplied Windows storage base create/write/delete probe: **passed**, probe removed.

Backend logs are in ignored `target`; Angular logs in ignored `tmp`. The nine optional backend skips require PDF fixture inputs (eight) or an explicitly enabled live AI model (one). No live AI test or deployed browser journey was run for this increment. All live-data verification was non-destructive; destructive behavior was exercised only against disposable fixtures.

### Windows Maven temporary-path correction

A subsequent normal Maven run exposed Windows `java.io.tmpdir` using `C:\Users\DEEPAK~1\AppData\Local\Temp`. The earlier explicit test temporary-directory setting hid this case. Comparing a path's original spelling directly with `toRealPath()` incorrectly classified ordinary Windows 8.3 aliases as redirects, causing provisioning and PDF storage tests to fail. Storage now compares the followed and no-follow canonical paths, checks canonical tenant containment, and returns canonical readable file paths. The upload operation uses the central ancestor validator instead of repeating the lexical comparison. Symlink/junction rejection remains covered. An added fixture regression exercises provisioning, reading and manifest cleanup with the normal temporary-directory spelling.

Final verification: ordinary `mvn package` with no temporary-directory, repository or test-skip overrides **passed** on November 26, 2026. **670 tests total, 661 passed, 9 optional skipped, zero failures/errors**. The executable Spring Boot JAR was produced at `target/Book-Search-Download-0.0.1-SNAPSHOT.jar`. Log: `target/maven-package-fixed.log`. This run includes the Windows junction rejection regression and uses the actual short-name Windows temporary path.

### MySQL profile and user-list timestamp correction

MySQL Connector/J returns DATETIME values in JDBC maps as `LocalDateTime`, whereas the isolated H2 fixtures originally returned `Timestamp`. The new management mapper incorrectly cast map values to Timestamp, causing profile and user-table requests to fail with ClassCastException. It now delegates to the existing `AuthRows.instant` conversion, supporting both representations, nulls, Instant and OffsetDateTime. LocalDateTime is interpreted as UTC, matching the application's existing UTC database connection/storage convention. The same correction covers details and deletion-job response dates; the typed last-login query remains unchanged.

Six regression cases exercise actual scoped SQL with both map representations across profile/list/details, nullable fields and job get/list. A read-only probe against the configured local MySQL database confirmed the actual LocalDateTime driver type, correct UTC conversion, and successful profile, global-list and details reads. It performed no writes or email/storage operations and printed no personal data. Log: `target/user-timestamp-mysql-probe.log`. No schema/data migration is required.

Final `mvn package` verification passed: **682 total tests, 673 passed, 9 optional skipped, zero failures/errors**, including all six date-mapping regressions. The executable JAR was repackaged successfully. Log: `target/user-timestamp-package.log`.
