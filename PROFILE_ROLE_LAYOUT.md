# Profile, permissions, cleanup and role layout update

This update supersedes conflicting UI and current-tenant-only invitation statements in [MANAGEMENT_PAGES.md](MANAGEMENT_PAGES.md). Existing authentication, role delegation ceilings, protected system roles and recoverable deletion policies remain in force.

## User Management

The status selector has exactly **Active**, **Inactive**, and **All Users**. The soft-delete action and distinct `deleted_at` marker remain. All Users includes soft-deleted records; Inactive retains its existing non-deleted-inactive semantics. Legacy `status=DELETED` page links are normalized to All Users instead of reintroducing a fourth selector option.

**Review Recent Cleanup** opens a formatted modal table containing all fields returned by the existing job API: job, tenant and target user IDs, scope, actual status, database-purge flag, attempts, error and update time. Refresh Cleanup Status refreshes the rows without leaving the modal. A selected job older than the latest 100 is fetched separately rather than disappearing. Retry errors are shown inside the popup. Close, Escape and outside dismissal reveal the existing underlying page; focus is trapped and restored to the opener.

Delete results appear prominently at the top: green tick for completed soft deletion or fully completed hard cleanup, red cross for a failure, and an explicitly labeled amber pending state while hard cleanup is incomplete. An accepted 202 is never reported as completed deletion. Return and X go back to the preceding known user-list/details route with its table context. Other result-content navigation is absent; the cleanup button only opens its modal. The existing preview, exact confirmation phrase and destructive-scope validation remain unchanged.

Both management page headings keep their titles on the left and a visible Back to Library button in the right action area. Role Management places it beside New Role.

## Roles

View Existing Roles displays the tenant selector and selected tenant's roles. Existing SUPER_USER authority can select other tenants. Ordinary administrators/role managers have their own tenant automatically selected with a disabled dropdown; backend scope checks still reject foreign IDs even when a request is forged. Existing permissions remain separate: Create User does not grant role creation.

SUPER_USER entries show database-maintenance information without an Edit Super User or Copy control. No database maintenance of these entries was performed. Edit Administrator is retained with its existing authorization checks.

Copy Role opens the ordinary new-role form, preselects the source permissions, and requires a new name. Additional grantable permissions can be added. Save Role uses the existing create API, generating a separate role; it does not update the source or copy protected role identity. Sources with unavailable/ungrantable permissions cannot be copied by that actor. The server validates tenant scope, ROLE_CREATE, the grant ceiling and prerequisites on every save. Cancel restores the prior selected tenant's role listing. Adjacent controls have consistent spacing.

## Home and sidebar

Home uses equal search/collection grid columns when both panels are open. Closing search removes that column and lets the collection use the full available width; opening it restores the equal layout. The grid fills the middle shell's available area, retains the stationary header/footer and adapts to narrow screens. Search controls use container queries so a half-width pane can wrap appropriately even on a wide desktop. Empty/loading book tables drop forced column widths/header layout; populated tables retain horizontal scrolling so overflowing content remains reachable.

The profile sidebar starts minimized, including on initial Home load. Explicit toggle controls open it. Username, Name, Account Type and Access Roles use compact static blue boxes with white text, not inputs or buttons. View Your Detail opens `/user/profile`. Add User visibility depends on the authenticated Create User capability and has no Individual-account exclusion. Logout remains connected to the existing session-clearing behavior.

## Own information and permission popup

`/user/profile` is a separate authenticated self-information page with Name, Username, Tenant Name and Role rows. X and Back to Library return Home. Back to User Profile returns Home with `profile=open`, which explicitly opens the sidebar. No client user ID is used to load either own endpoint.

View Permissions opens a modal with the authenticated username above its table. The table uses actual effective permission names, descriptions, codes, component/kind, role sources, service codes, URL patterns and HTTP methods. Read/Write/Delete labels are derived from those methods; missing descriptions/routes are labeled rather than invented. Closing/Escape/outside dismissal restores the underlying information page and opener focus. No credential fields, hashes, tokens or MFA data are returned.

New APIs:

| Method and path | Contract |
| --- | --- |
| GET `/api/user/profile/details` | Current authenticated profile fields plus trusted tenant ID; validates the active current session. |
| GET `/api/user/profile/permissions` | Current username and currently effective permission metadata/routes/sources; accepts no target ID. |
| GET `/api/user/users/create-tenants` | USER_CREATE; active, non-deleting authorized tenants. SUPER_USER can select across tenants; other actors receive only their current tenant. |
| GET `/api/user/users/create-options?tenantId=` | Selected tenant's persisted name/type/login policy; foreign selection rejected for non-super actors. |
| POST `/api/user/users` | Strict `{email,displayName,tenantId?}`; non-super actors cannot override their own tenant. |

The self endpoints are narrow authenticated entry policies, not administrative USER_VIEW requirements. APIs still validate the current user/session and do not let query parameters select someone else.

## Add User authentication and ownership

Super Admin can select an authorized tenant. Other authorized creators have their own tenant selected and disabled. There is no Authentication Type or Google Sign-up control; the backend loads the selected tenant's existing login policy and ignores no forged field: unsupported authentication/credential/role fields are rejected.

Password tenants send the existing single-use password setup link. SSO tenants create no local password or local credential and send no password setup message. They retain the existing secure **SSO enrollment invitation**, with a provider/user/email/security-version-bound proof, followed by that tenant's configured provider flow. This is an enrollment link, not a password link; verification, subject/provider binding and replay checks are preserved. Existing owners and role grants are not copied or changed. Failed invitation delivery still rolls back creation.

The latest requirement explicitly permits additional users in an Individual tenant when permission allows. Its persisted classification, owner and directory remain unchanged; members share the selected tenant rather than receiving a fabricated workspace. Existing destructive ownership guards continue blocking a standalone whole-root purge when the tenant is no longer exclusively owned by the selected sole member. No ambiguous shared content is guessed or deleted.

## Migration and checks

Apply prior migrations, then `src/main/resources/db/manual/20261126_profile_permissions_add_user.sql`. It is catalog-only and adds three GET policies/mappings, with no account or role-grant changes. The configured local MySQL database passed the migration and idempotent rerun with zero active uploads/deletion jobs and unchanged account/role/grant/library fingerprints. Backup: `.local/db-backups/20261126_profile_permissions_d9e01267/permission-catalog-before.sql`; result is in the adjacent `migration-result.log`, also logged in `target/profile-permissions-migration.log`. No live user creation/deletion, credential changes or emails were performed.

Actual verification:

- `mvn package`: **698 total, 689 passed, 9 optional skipped, zero failures/errors**, followed by executable JAR packaging. Log: `target/profile-role-updates-package.log`. New coverage includes self-only access and actual metadata, empty effective grants, SUPER_USER tenant selection, non-super forged selection, permission-based Individual membership, safe catalog reruns and independent role-copy persistence. Existing deletion/storage/session regressions remain covered by disposable H2/temp fixtures.
- `npm test -- --watch=false`: **231 passed across 40 files**. Log: `tmp/profile-role-updates-tests.log` in the UI project. Covers copy/edit/cancel/tenant controls, modal refresh/close/errors, truthful deletion results and return navigation, self information/permissions, sidebar defaults/profile reopening, and Home search/empty/overflow states.
- `npm run build`: **passed**. Log: `tmp/profile-role-updates-build.log` in the UI project.
- Actual local MySQL catalog migration and safe rerun: **passed**, without account/grant/library mutations or mail delivery.

The nine skips remain the eight opt-in source PDF fixtures and one live AI test. Browser geometry/visual checks are not implied by DOM/CSS tests; the earlier isolated browser host was unavailable and shared Edge-window capture was restricted. No live invitation/deletion journey is claimed. Restart the applications to load the new controllers and UI.
