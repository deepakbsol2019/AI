# Management pages, sidebar and current-tenant invitations

This increment supersedes the details-popup/header-profile layout in [USER_MANAGEMENT.md](USER_MANAGEMENT.md). The existing SUPER_USER global policies and confirmed whole-tenant deletion behavior remain in force, as explicitly confirmed for this update. Ordinary users cannot use foreign tenant/user/role IDs to escape their scope. No new cross-tenant administration bypass was added.

## Routes and shared layout

- `/user/users`: User Management listing.
- `/user/users/new`: Create User, gated by the explicit creation capability.
- `/user/users/:id`: User Details, including supported profile editing, separately authorized activation and role assignment.
- `/user/users/:id/delete?hard=false|true`: existing confirmation content and exact server-generated phrase on a dedicated confirmation page.
- `/user/roles`: existing role list and role/permission editor on a full content page.

These pages use the same viewport shell as the library and public authentication pages. Header/footer do not shrink or participate in scrolling. Only the middle main content scrolls vertically, with an independently bounded sidebar profile/navigation area. The former library search/results vertical scroll containers were removed; table overflow is horizontal within the table region. Sidebar collapse changes available main width on desktop. Below 768px it starts collapsed and opens within the middle section as a bounded overlay; the header control always reopens it. Logout stays at the bottom of the sidebar, outside its scrolling profile/navigation area.

The actual model has no uploaded-avatar field. The sidebar therefore uses a neutral inline illustrated SVG, with no invented image URL, image request or animation. There is no missing-image network failure or reduced-motion dependency. It displays the authenticated username/name, Individual or Organisation account type, and assigned role names. Profile and logout are no longer duplicated in the header. Own profile and navigation stores clear on logout/identity change, reject stale responses and remain independent of a client-selected target ID.

The user table has exactly eight columns, in order: **Username, Tenant Name, Tenant Type, Role, Status, View User Details, Soft Delete, Hard Delete**. Roles remain multiple where the model supports them. User details and creation do not open dialogs. List search/status/page are carried in query parameters, including back navigation and refresh. Details have explicit Save/Cancel and inline unsaved-change handling. Logout/session expiry can leave dirty forms rather than trapping private data behind a navigation guard. API failures preserve useful form input and do not announce success.

No delete-confirmation page existed in the inspected implementation: it used an in-component dialog. Its existing server preview, scope/count/version checks, warnings, exact confirmation phrases, separate soft/hard actions and durable cleanup behavior were retained when routing the confirmation content into a full page. No replacement confirmation popup was introduced. Hard deletion remains 202/PENDING until the existing cleanup job completes; FAILED cleanup is visible and explicitly retryable by a super user.

## Create User capability and invitation

`USER_CREATE`, displayed as **Create User**, is a delegable capability. No ADMIN or other role label alone grants it. It is not automatically granted to existing ADMIN/OWNER roles, and it grants no USER_VIEW, deletion, role creation or role assignment authority. The existing authorization model represents a prerequisite-free entry capability using `permission_kind=VIEW`; USER_CREATE has its own `USER_CREATION` component so it does not implicitly require/grant permission to view other users. Existing action/View prerequisites are unchanged.

Invitation is supported for an active **Organisation** current tenant. Individual tenants retain their existing single-owner model and cannot create additional members. The creator can be an admin or any ordinary current member explicitly granted USER_CREATE. Even a super user cannot pass another tenant into this new creation API. Existing global super-user management of already existing users/roles remains unchanged.

The full-page form accepts existing email/username and display name fields. Tenant details are server-derived and read-only. The strict backend DTO accepts only `{email,displayName}`; tenant IDs, roles, passwords and other forged fields are rejected. The server revalidates current status, security/authz versions, capability and pending deletion jobs under tenant/account locks. A new pending user and membership share the current tenant and its existing root. Public registration is not reused to create another tenant, and an existing email never joins or moves an account.

No roles are copied or implicitly assigned; role assignment retains its independent existing authority. Password tenants reuse the existing single-use Set Password invitation. Active, configured SSO tenants reuse a provider/user/email/security-version-bound single-use enrollment proof for an explicitly invited pending member; first-owner setup still requires ownership and PENDING_SETUP. Wrong provider/email or replay remains rejected. Delivery failure rolls back the new account, membership and credential token. Creation succeeds only after the configured invitation delivery path accepts the message. No live invitation was sent during implementation.

For an actor also allowed to list users, creation returns to the listing with All users selected so the new PENDING account is visible. A create-only actor gets success on the create page without gaining list access.

## Distinct soft-deleted state

`users.deleted_at` is the separate nullable soft-deletion marker; account status also becomes INACTIVE so normal login/refresh/session use stops. Existing inactive users remain `deleted_at=NULL` and are not retroactively labeled deleted. Default listing is Active. Inactive excludes soft-deleted records; Soft-deleted and All users filters make them available to authorized managers. Status displays Active/Inactive and a separate Soft-deleted annotation or underlying lifecycle state where relevant.

Soft deletion preserves the record and all content/files. The previously confirmed matrix remains: super-user soft deletion of a tenant ADMIN affects every current tenant user; ordinary employee soft deletion affects only that employee. Soft-deleted accounts cannot be edited, assigned roles, activated as merely inactive, or revived through password setup/reset/SSO. This increment does not introduce an undelete endpoint. Activation of a genuinely inactive, non-deleted account remains separately authorized.

Hard deletion continues to use the existing physical account/association purge and exact-manifest workflow. A delegated tenant admin's employee hard deletion preserves all tenant metadata/files/directories. The super-user employee ownership ambiguity block and explicit super-user ADMIN whole-company purge remain unchanged. Audit/recovery records survive. No live user or tenant folder was deleted for this work.

## Additional APIs and migration

| Method and path | Authority and result |
| --- | --- |
| GET `/api/user/users/capabilities` | Authenticated current identity only; booleans `canViewUsers`, `canCreateUsers`, `canManageRoles`, `superUser`, plus trusted tenant ID/name/type. No permission list or client tenant input. |
| GET `/api/user/users/create-options` | USER_CREATE; current Organisation tenant ID/name/type/login policy. |
| POST `/api/user/users` | USER_CREATE and existing CSRF rules; strict `{email,displayName}`; 201 created pending-user summary. |

Capabilities are UI hints and route guards; each backend operation still enforces its own permission and tenant scope. Role-management services retain their existing tenant checks and delegation ceilings. Create User does not authorize role creation; direct forged role/tenant requests remain covered by tests.

Apply prior migrations, then `src/main/resources/db/manual/20261126_user_creation_soft_delete.sql`. It adds one nullable column, one capability and three method-specific endpoint policies/mappings. It neither changes existing statuses nor grants permissions to any role. Back up and apply without `--force`; MySQL DDL commits independently. Restart the backend/frontend as usual and sign in again if the changed permission catalog invalidated an existing super-user authorization fingerprint.

The old management seeder now targets only its original method/path pairs, so rerunning it cannot attach a USER_VIEW prerequisite to Create User. The new migration also repairs that specific obsolete association on the three new entry policies, preserving unrelated routes and role grants. Regression tests cover both catalog reruns and repair. Capability guards explicitly await authentication bootstrap so direct management URL refreshes do not race a still-empty client identity.

The migration and its idempotent rerun passed on the configured local MySQL database with zero active uploads/deletion jobs. Existing account, tenant, role/assignment/grant and library rows were fingerprint-verified unchanged. Backup: `.local/db-backups/20261126_management_pages_5537d28a/schema-catalog-users-before.sql`, with adjacent `migration-result.log`. The ignored `.local` location survives Maven clean. The backup contains private profile data and should remain private. No live user creation/deletion/status change or invitation delivery was performed.

## Checks

- `mvn package`: **692 total tests, 683 passed, 9 optional skipped, zero failures/errors**, followed by successful executable JAR packaging. Log: `target/management-pages-package.log`. Six new invitation/soft-delete HTTP/security tests cover non-admin capability use, tenant and protected-field forgery, duplicate isolation, setup completion, mail-failure rollback, SSO proof binding/replay and deleted-versus-inactive access. Role tests verify Create User grants no role creation/foreign scope and deleted members cannot receive roles through the separate API. Two migration regressions cover catalog reruns and narrow repair. Destructive tests use isolated H2 schemas and temporary fixture directories only.
- `npm test -- --watch=false`: **217 passed across 39 files**. Log: `tmp/management-pages-tests.log` in the UI project. Includes 14 routed management tests, sidebar/navigation/capability/bootstrap-guard tests and updated real-HTTP login/logout regression checks.
- `npm run build`: **passed**. Log: `tmp/management-pages-build.log` in the UI project.
- Local MySQL migration/rerun, unchanged existing-data fingerprints and zero live account mutations/invitations: **passed**.

The nine backend skips are eight optional source-PDF fixtures and one opt-in live AI test. An isolated browser QA server with synthetic API data was prepared at `tmp/management-layout-fixture.cjs`; it never connects to the live backend, database, mail or tenant storage. Browser visual/scroll verification requires an available browser surface and is reported separately; automated DOM/CSS tests alone do not establish browser geometry.

Browser-check status at delivery: the isolated browser host was unavailable, and automatic approval rejected whole-window capture of the existing Edge window because it contains unrelated tabs, including after approval to use that shared window. The user was asked to open `http://127.0.0.1:4305/user/users` in a separate Edge window containing only the fixture. Desktop/narrow-screen geometry and actual scroll behavior are therefore pending that isolated window; they are not reported as verified. No live account or permission change was performed through a browser.
