# Authentication schema and tenant boundaries

The [management pages increment](MANAGEMENT_PAGES.md) adds nullable `users.deleted_at` (157 fields across the original 18 tables, plus the existing durable job table), a separate USER_CREATE capability and three endpoint policies. Existing INACTIVE rows remain non-deleted; recovery/authentication enforce the new marker.

The later [User Management increment](USER_MANAGEMENT.md) adds `users.management_version` and a durable deletion-job table (19 authentication/management tables; 156 fields across the original 18). It adds scoped management policies without granting them to existing roles, plus authenticated own-profile access. Individual registration now provisions its existing tenant-ID directory immediately. Follow that guide for the additive migration, activation, deletion/recovery matrix and current validation.

The later [organization roles and storage increment](ROLE_MANAGEMENT_STORAGE.md) adds `permissions.is_delegable` (default false), making 155 fields including prior additions. It reuses the existing tenant ownership and trusted `roles.is_system`/immutable role-code identity. Its OWNER registration and strict tenant file/search boundaries supersede the earlier ADMIN/global-file policy below.

The reference PDF and SVG in `Downloads/Organization_Table_Relationship_Chart` were both read and cross-referenced. They describe the same 18 tables and 149 original fields. The chart is a schema reference, not an authentication sequence. The existing application actually uses MySQL, so `src/main/resources/db/manual/20261125_user_auth.sql` follows its existing manual-migration convention instead of introducing PostgreSQL or replacing the database.

| Original chart table | Original fields | Enforced intent |
| --- | ---: | --- |
| tenants | 10 | Unique tenant code and nullable unique owner; owner references users |
| users | 8 | Unique required login email; security version |
| tenant_memberships | 8 | Tenant/user FKs; one ACTIVE or SUSPENDED membership per user; LEFT history |
| roles | 10 | Unique role code within its tenant; authorization version |
| user_roles | 5 | Composite tenant/member, tenant/role and tenant/granter FKs |
| permissions | 9 | Unique code; same-component prerequisite FK; effective children require a VIEW |
| role_permissions | 6 | Composite tenant/role FK; permission FK |
| api_endpoints | 8 | Unique service/method/pattern policy |
| endpoint_permissions | 3 | Endpoint/permission composite primary key |
| auth_sessions | 15 | Composite tenant/member/user FK; versions; nullable absolute deadline |
| refresh_tokens | 8 | Unique digest and parent; same-session parent lineage; one active token per session |
| audit_events | 11 | Nullable tenant and actor; redacted structured details |
| identity_providers | 11 | Trusted provider registry; one ACTIVE company provider per tenant |
| external_identities | 6 | Unique provider/subject, with case-sensitive subject |
| local_credentials | 7 | At most one adaptive password hash per user; attempts/lock deadline |
| credential_tokens | 10 | Unique digest; purpose/email/security version; expiry and consumption/revocation |
| local_mfa_factors | 8 | Optional local-factor metadata and external secret reference |
| recovery_codes | 6 | Optional one-use hashed recovery codes |

Every original field is retained with its original nullable/non-null intent except the explicitly necessary pending-provider configuration change below. MySQL converts `TIMESTAMPTZ` to UTC `DATETIME(6)`, `BYTEA` to `VARBINARY(32)` SHA-256 digests, `JSONB` to native `JSON`, and `INET` to a 45-character textual IPv4/IPv6 address. Application and JDBC clocks/connections must use UTC.

## Explicit deviations and additions

- Organization login policy is `PASSWORD` or `SSO`, overriding the chart's company `SSO_ONLY` annotation. Individual tenants always persist `PASSWORD`; a Google-created individual has no local credential unless a separately verified setup flow creates one.
- `identity_providers.issuer_url` and `client_id` may be null only while status is `PENDING`. This permits an honestly unconfigured SSO registration. Other statuses require nonempty configuration; application login additionally requires an ACTIVE, explicitly trusted provider. `provider_code` is globally unique to make trusted-provider routing unambiguous. Company providers require a tenant; the shared Google provider requires a null tenant.
- `credential_tokens.identity_provider_id` is an added nullable FK. SSO enrollment binds its single-use credential token to the intended trusted provider instead of linking a privileged pending administrator using email alone.
- `auth_sessions.authorization_fingerprint` is an added required SHA-256 hex fingerprint of the actual role/permission state. It supplements the chart's user, tenant and membership versions so direct role/grant/permission changes invalidate existing access and refresh sessions.
- Three generated nullable columns enforce partial uniqueness in MySQL: `tenant_memberships.current_user_id`, `refresh_tokens.active_session_id` and `identity_providers.active_company_tenant_id`. They add no independently mutable business data. Refresh parent linkage also has a composite same-session FK and a unique parent so two successor tokens cannot branch from one parent.
- Existing `book_details` and `book_upload_status` gain nullable `tenant_id` FKs. A composite tenant/receipt FK prevents a newly owned book from pointing to another tenant's receipt. The old global unique book-name index becomes `UNIQUE(tenant_id, book_name)` so different tenants can independently own the same title. Existing book names, descriptions, types, authors, topics, page boundaries, receipt state and stored PDF paths are preserved.

Historical books and upload receipts remain **unassigned and inaccessible**, as explicitly chosen by the user. Registration never claims historical data based on a name, email or domain. Any future ownership migration must explicitly identify the authorized owner and corresponding book/receipt rows; it is not an automatic registration side effect. There is no unrestricted join or account-link endpoint.

## Provisioning and authorization

The later [Super User increment](SUPER_USER_SETUP.md) reserves the existing system role code `SUPER_USER` for explicit operator assignment. It adds no tables or columns, extends trusted scope to assigned tenant records, and keeps null/unassigned ownership inaccessible. Normal `ADMIN` remains tenant-scoped. The additive permission migration adds `BOOKS.DELETE`; existing authentication migration catalog counts below describe the original five-permission baseline.

Provisioning creates the user first, then its owned tenant, ACTIVE current membership, tenant-scoped ADMIN role and assignment in one transaction. This avoids a circular owner insertion dependency. Pending local registrations have a PENDING user/tenant; SSO has a PENDING user and PENDING_SETUP tenant. Membership alone never authorizes a pending or suspended account. The ADMIN receives the actual active permission catalog, not global administration or grants in another tenant.

The initial catalog is `BOOKS.VIEW`, `BOOKS.UPLOAD`, `BOOKS.DOWNLOAD`, `BOOKS.EDIT` and `ACCOUNT.VIEW`. Every non-VIEW capability requires its active, actually granted same-component VIEW; a composite prerequisite FK also prevents cross-component references. `AuthorizationService` reads the database without a permission cache, rejects inactive roles/permissions and invalid prerequisites, resolves policies by service (`BOOK_SEARCH`), HTTP method and most-specific path, rejects equally specific ambiguity, and requires **ALL** mapped permissions. Unknown protected routes and protected policies with no requirements fail closed. Authentication public routes are separately pinned to the security configuration's explicit allowlist; arbitrary catalog rows do not expose new unauthenticated endpoints.

Search predicates/counts/pagination run within the trusted tenant. Downloads, reader requests, metadata changes and upload receipts reject foreign or unassigned records with the same 404 as missing records. Tenant ownership is never taken from request query/body input. A submission captures its trusted tenant as a scalar before queuing; the worker restores and finally clears that scope independently of thread reuse. The existing PDF generation and download assembly remain intact.

## Applying and verifying

Back up the database, stop writes and apply the manual SQL without `--force`; MySQL DDL commits independently. The migration is designed to be rerun after an interrupted application: table creation, catalog inserts and catalog-column/index/FK changes are conditional. It does not assign or rewrite historical content. Existing custom disabled catalog state is retained by insertion-only seeding. The normal application must use schema validation rather than automatic schema creation/update.

`AuthTestDatabase` derives isolated MySQL-mode H2 authentication tables and seeded policies from the actual production migration, adapting only dialect/storage syntax and omitting the existing-catalog alteration procedure. Tests cover transactional provisioning/rollback, generated uniqueness/composite FKs, permission prerequisites/ALL semantics, fingerprint changes, explicit tenant scopes, and actual JPA catalog isolation. H2 is not a substitute for MySQL migration validation; executed commands and actual results are recorded in the main README/task report.

An actual MySQL JDBC probe also passed **16 constraint checks with zero failures** in the separately created verification database. It validated the database-name marker against `^book_auth_verify_20261125_[a-f0-9]{8}$` and checked `SELECT DATABASE()` before inserting synthetic rows. It exercised owner/current-membership uniqueness, historical LEFT memberships, tenant-safe role/granter/session FKs, pending and single-active company providers, exact case/trailing-space OIDC subjects, unique provider subjects, and active/digest/parent/same-session refresh constraints. Every synthetic write was rolled back; no DDL, deletion, truncation, or application-catalog write was performed by this probe. Its nonsecret result log is `target/auth-reference/mysql-constraint-results.log`; the disposable Java source/class remain under ignored `target/auth-reference`.

The temporary `book_auth_verify_20261125_168b4adb` database was subsequently backed up and removed at the user's request to clean up duplicate tables. It held 22 disposable copies of the schema, no library/account data, and only 25 permission/endpoint seed rows. No runtime configuration, foreign keys or stored database objects depended on it. The application database `search_book_download` has exactly 22 distinct required tables (four library and 18 authentication tables); none were dropped. All application table definitions, exact row counts and extended data checksums matched before and after cleanup. Backup and verification records are under ignored `target/db-cleanup/20261125_duplicate-verification-tables/`. The old verification marker/probe are historical artifacts; recreating a disposable database is necessary before rerunning that MySQL constraint probe. Runtime schema validation remains enabled to avoid automatic duplicate-table creation.
