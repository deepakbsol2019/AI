# Organization roles and tenant storage

The later [User Management increment](USER_MANAGEMENT.md) adds the separate user page, protected member-assignment checks, durable deletion matrix, header own-profile panel and immediate provisioning for standalone registrations. It supersedes earlier lazy individual-directory/deletion exclusions; existing role-definition ceilings and tenant-bound file/search access remain in force.

This increment supersedes the earlier ADMIN defaults and application-wide file-access policy. Role Management is at `/user/roles`, in the existing guarded Angular UserComponent feature, linked from the library. It lists authorized roles, disables protected/out-of-ceiling edits, shows grantable permission identities with HTTP method/path distinctions, and supports create/edit with loading, validation, conflict/error messages and duplicate-submission protection. Super users explicitly select an organization. Successful saves return to login because tenant authorization versions advance.

## Security model

Roles already have mandatory organization ownership, tenant-local unique `role_code`, `is_system`, and `authz_version`. Protected identity uses the trusted combination of `is_system` and immutable reserved code, never the display name. The API cannot change role codes/system flags. Ordinary codes are server-generated. ADMIN/Administrator/Super User case, punctuation and compatibility-name aliases are rejected for ordinary role names; renaming a protected role never removes its protection.

Only a currently authenticated SUPER_USER can create/edit ADMIN or alter its grants/assignments. SUPER_USER stays operator-managed: this component cannot create, edit or assign it. No group, inherited-role, per-user permission-override, role-delete or alternate permission-mapping endpoint exists. No assignment UI was introduced.

`ROLE_VIEW`, `ROLE_CREATE`, `ROLE_EDIT`, and `ROLE_ASSIGN` are independent catalog permissions under ROLES. Existing same-component View prerequisites apply. Creation does not imply editing or assignment. Management powers can come from any assigned custom role; no check assumes the actor is an admin. The migration adds `permissions.is_delegable`, default false. Reviewed library/account and ordinary role-management capabilities are explicitly enabled. Future unknown capabilities remain non-delegable. Protected identity management is enforced separately and cannot be delegated through a permission set.

Non-super actors may grant an equal set or any subset of their **current effective, delegable permission IDs**, with required View prerequisites. Editing also checks the existing role's entire permission set against the current ceiling; submitting a reduced set cannot take control of an overprivileged role. System identities remain protected even with no grants. Tenant IDs, role IDs and assignment membership IDs are verified server-side.

Management transactions lock actor/target tenant rows in tenant-ID order, recheck active identity/security/version/fingerprint/current grants, and atomically write role/grants and audit. Updates require the last observed `version`; conflicts return 409. Tenant serialization prevents concurrent stale privilege decisions and duplicate names through these APIs. Every successful mutation advances tenant authorization version; assignments also advance membership version. Existing uncached session/version/fingerprint validation rejects old access and refresh tokens on the next request. Affected tenant users must sign in again, including after role creation.

Audit records include actor, target organization/role, timestamp and old/new permission IDs. Distinct assignment events record old/new member IDs. Roles have explicit grants with no inheritance or creator-ceiling cascade: later management evaluates current authority without rewriting existing child roles automatically.

New password/Google/public registrations receive an ordinary `OWNER` role with reviewed library/account permissions, not protected ADMIN or management powers. Existing tenant-owned ADMIN/SUPER_USER records and assignments are preserved. The existing operator super-user provisioning command remains the bootstrap mechanism. A super user can then delegate via protected ADMIN editing/assignment or ordinary assigned roles. Pending organizations may be configured by a super user but cannot log in until existing activation/provider policy permits it.

## Database rollout

1. Back up the database and stop role/authentication writes during rollout. Apply existing user-auth/super-user migrations first if missing.
2. Apply `src/main/resources/db/manual/20261125_role_management.sql` with a MySQL client honoring DELIMITER, without `--force`. MySQL DDL commits independently. It adds one delegation-policy column and four permissions/eight method-specific endpoint policies. Reruns preserve operator-disabled catalog/delegation settings. No management rights are automatically granted to existing ADMIN roles.
3. Reserved non-system code collisions cause an explicit migration failure. Resolve them with a reviewed operator migration, never by automatically promoting a custom role. Role ownership is already FK-bound and mandatory; no organization is guessed from names, email or filesystem paths. Unowned legacy data is not exposed.
4. Deploy backend/frontend together and restart. Old sessions affected by authorization changes require login. Manual migrations are not automatically applied. The role migration was subsequently applied to the configured local application database during the November 26 missing-column remediation recorded below.

## API

All routes require bearer authentication and their catalog permission. Unsafe methods retain existing CSRF and exact-origin CORS. `tenantId` defaults to the actor's tenant; another tenant returns 404 unless the authenticated actor is super user. IDs are database identities, never user-supplied filesystem fragments or trusted role claims.

| Method/path | Purpose/input |
| --- | --- |
| GET `/api/user/roles/organizations` | Authorized organization choices, own tenant only unless super user |
| GET `/api/user/roles?tenantId=123` | List scoped roles |
| GET `/api/user/roles/catalog?tenantId=123` | Current grantable IDs, prerequisites, method/path mappings and abilities |
| GET `/api/user/roles/{id}?tenantId=123` | Scoped role detail |
| POST `/api/user/roles` | `{"tenantId":123,"name":"Reader","description":"Read books","permissionIds":[1]}`; 201 |
| PUT `/api/user/roles/{id}` | Same fields plus `"version":1`; updated role/version |
| PUT `/api/user/roles/{id}/members/{membershipId}?tenantId=123` | Assign role, requires ROLE_ASSIGN |
| DELETE `/api/user/roles/{id}/members/{membershipId}?tenantId=123` | Remove assignment, requires ROLE_ASSIGN |

For ADMIN creation only, a super user includes `"systemRole":"ADMIN"`. Edits cannot reclassify identity. ADMIN assignment/removal is super-user-only; no endpoint grants SUPER_USER. Obtain real permission IDs from the live catalog. Role responses contain `roleId`, `tenantId`, `name`, `description`, `systemRole`, `version`, `permissionIds`, and `editable`. Errors use the existing structured user exception handler.

## Tenant directories

`app.topic-pdf.storage-root=${TENANT_STORAGE_ROOT:C:/workspace/backend/Book_Search/upload-pdf}` uses the supplied Windows path by default. Other hosts must configure an absolute native path; a Windows path interpreted as relative on non-Windows is rejected. ID 123 owns exactly `C:\workspace\backend\Book_Search\upload-pdf\123\`. Tenant IDs are positive server-issued database longs, used directly as decimal directory names, never sanitized names or user IDs.

`TenantStoragePaths` centralizes directory provisioning, filename checks, real-path/ancestor validation and readable-file resolution. It rejects traversal, foreign tenant paths, symbolic-link/junction escapes, invalid platform paths, unsafe/reserved filenames and missing context. Provisioning is concurrent/idempotent and checks actual directory/read/write access with a temporary write probe. Company registration provisions after obtaining the tenant ID, inside the existing registration transaction. I/O failure returns 503 and rolls back database work. Filesystem creation is not transactional: a later database/SMTP failure can leave an empty allocated directory, which is preserved rather than risking another attempt's files. Provisioning the same valid ID reuses its directory.

No new public existing-tenant join API was introduced. Additional trusted memberships use the same tenant ID/resolver, without per-user directories. Individuals receive tenant storage lazily on upload. The existing background orchestrator captures/restores authenticated tenant context; extraction workers use the server-captured tenant/operation identity. No worker falls back to shared storage.

Uploads now save `<base>/<tenantId>/<existing book folder>/<existing topic filename>.pdf`. Ownership persists through `book_details.tenant_id`, topic-to-book FK, and tenant-owned upload receipts. Downloads/previews require both record ownership and a file in the same tenant folder. Search scopes the Criteria query before matching/counts/pagination and during the final graph fetch. Super-user permissions do not override file ownership. Receipt reads/updates are also tenant-filtered. No active vector index, search cache, file-list or deletion API exists to migrate; AI dependencies alone do not implement vector search. Future indexes/caches must include tenant ownership before ranking/counts or in their keys.

The supplied base was verified to exist and accept a create/delete write probe on this Windows workspace host. Different hosts/runtime identities need their own checks. Restrict directories/ancestors to application/operator OS identities: portable path validation does not replace filesystem ACLs against a malicious local process swapping directories concurrently.

## Preserve and migrate existing shared PDFs

No legacy file was moved, deleted or assigned. New readers fail closed for old shared paths, even for otherwise owned metadata. Inventory before rollout:

```sql
SELECT b.tenant_id,b.book_id,t.topic_id,t.pdf_path
FROM book_details b JOIN topic_details t ON t.book_id=b.book_id
ORDER BY b.tenant_id,b.book_id,t.topic_id;
```

For each **reliably owned** book, approve an exact source/destination manifest from its persisted tenant FK. Quiesce uploads/edits, verify regular readable sources under the old trusted base with no link escape, provision the exact tenant directory, and **copy without overwrite**. Verify hashes, PDF readability/page counts and destination real-path confinement. In a DB transaction lock book/topic rows, recheck unchanged tenant/old path, then update only that topic's pdf_path to the verified destination. Verify scoped read/download/search and counts. Keep originals/backups until separately approved cleanup. Duplicate targets and ambiguous/null ownership require manual resolution; never guess from folder, email or role. Rebuild external indexes only from approved owned rows with tenant filtering before ranking/counts. Current database search needs no reindex because the tenant FK already supplies ownership.

## Verification

November 26 runtime remediation: registration reported `Unknown column 'p.is_delegable'`. Read-only inspection confirmed the configured local database lacked the migration, with zero reserved-role collisions and zero active uploads. A schema/data backup of the three affected catalog tables was saved to `target/db-backups/20261126_role_management_34f79532/permission-catalog-before.sql`. The exact supplied migration was applied and rerun successfully against MySQL; all four management permissions and eight endpoint policies/mappings were verified. The previously failing registration grant SQL passed EXPLAIN without creating an account or sending mail. Library row counts/checksums and existing role-grant counts were preserved. Results: `target/db-backups/20261126_role_management_34f79532/migration-result.log`. This database-only correction is visible to the running application without a code restart; affected stale sessions may need a new login.

Backend tests cover identities/aliases, custom-role delegation, equal/subset ceilings, unavailable IDs, existing-role ceilings, stale permissions, tenant isolation, concurrent saves, audit grants, actual MVC/CSRF/bearer policies, and rejection of old access/refresh sessions. Storage tests cover company registration/rollback, exact directories, additional-member reuse, concurrency, missing context, filenames/traversal, preserved legacy files and Windows junction/symlink escapes. Existing pipeline tests now assert tenant paths/ownership even for super users. Angular tests cover protected actions, method/path distinctions, prerequisites, versioned saves, denial/conflicts and duplicate submission.

Final checks completed on November 26, 2026:

- Backend: `mvn '-Dmaven.repo.local=target/maven-repository' -o test` — **648 tests total, 639 passed, 9 skipped, zero failures/errors**. TEMP/TMP used `target/test-tmp`; the suite ran with native filesystem/cryptographic access. Eight skipped checks require explicitly configured source PDF fixtures; one requires a live AI-model opt-in. All six tenant-storage tests passed, including Windows junction escape protection. Log: `target/roles-storage-full-tests.log`.
- Frontend: `npm test -- --watch=false` — **190 tests passed across 35 files**, including six Role Management UI tests. Log: `tmp/roles-tests.log` in the UI project.
- Frontend: `npm run build` — **passed**. Log: `tmp/roles-build.log`.
- The actual Windows base existence/write probe passed; its temporary probe file was removed.
- Database unit/integration tests use isolated H2 schemas with documented dialect adaptations. The new MySQL migration was subsequently applied to the configured local application database on November 26, with a catalog backup, a successful idempotent rerun, unchanged library checksums/existing role grants, and successful EXPLAIN validation of the registration grant INSERT. No deployed browser journey, live AI run or legacy-file migration is claimed.

Changed file groups for this increment:

- New backend `user/controller/RoleManagementController.java`, `user/service/RoleManagementService.java`; updated `AuthorizationService`, `TenantProvisioningService`, `TenantContext`; new `db/manual/20261125_role_management.sql`.
- New `storage/TenantStoragePaths.java`; updated `TopicPdfFileStore`, `TopicPdfUploadService`, `TopicPdfSource`, `BookSearchQueryRepository` and `BookUploadStatusRepository`.
- New Angular `features/user/data-access/role-api.service.ts`, `pages/role-management.component.ts` and its tests; updated user routing, session control/navigation and login save confirmation.
- New backend role-service/HTTP/storage security tests; schema rerun-policy coverage and existing tenant-aware upload/download/search/provisioning fixtures updated to the new rules.
- Storage environment configuration/example and repository setup/README/schema documentation. Existing unrelated workspace changes were preserved.
