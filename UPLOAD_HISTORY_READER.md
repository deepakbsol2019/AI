# Upload history, UTC events, Full Page reading and Home layout

This increment implements the latest upload/reader/Home request and supersedes conflicting layout and timestamp descriptions in earlier guides. It keeps PDF processing, extraction, tenant file confinement, reading and downloads. No additional document format or missing-AI-metadata popup was introduced.

## Upload Document and durable private history

The header action is now **Upload Document**. PDF is the supported format. The browser performs size checks; the backend validates actual PDF bytes/structure rather than trusting a MIME header or filename extension. There is no file-type selection control.

Accepted uploads retain their independent receipt before background dispatch. The trusted authenticated tenant **and uploader user ID** are captured before crossing threads and restored/cleared within worker scope. Each new receipt stores original filename, eventual book name, numeric uploader ID, server-generated UTC creation/update times, current state and existing failure message. PENDING/PROCESSING/COMPLETED/FAILED are explicit states; the existing `inprogress`/`Success`/`failed` status field remains for API compatibility. Completed state still commits with the successful book transaction; failures survive rollback.

The acceptance notification includes Upload ID and **View Upload Status**, with a 15-second auto-hide. It explains that background processing is still pending; acceptance is not completion. Dismissal/auto-hide only removes the notification. Worker processing and receipt history do not depend on the page remaining open. Existing session rules remain: automatic status checks never renew authentication; explicit foreground navigation/Refresh can authenticate normally.

**My Uploads** in the sidebar opens `/user/uploads`. A notification links to `/user/uploads?uploadId=<id>`. The page retrieves persistent server history, shows File/Book Name, Upload ID, Uploaded By, upload/update times, actual state and failure details, and provides manual Refresh Status and pagination. A selected older ID can be looked up independently of the current history page. Invalid IDs fail visibly without an HTTP lookup. Client state clears at logout/identity change, and these private routes redirect on session expiry.

| API | Authorization and result |
| --- | --- |
| POST `/api/upload-file` | Existing authenticated upload capability/CSRF; multipart `file`, PDF-content validation. Uploader/tenant are never taken from form fields. |
| GET `/api/upload-status/{id}` | Existing capability plus **both** current tenant and current user matching the receipt; missing/foreign/peer IDs return unavailable. |
| GET `/api/my-uploads?page=0&size=20` | Same tenant-and-user ownership predicate before pagination/counts. Returns `{content,totalElements,totalPages,number,size}`; size limited to 100. |

Receipt fields include `book_upload_id`, legacy `status`/`message`, `fileName`, `bookName`, `uploadedBy`, `createdAt`, `updatedAt`, `state` and `failureReason`. The history/status ownership rule has no SUPER_USER bypass: another user in the same tenant still cannot inspect that receipt. Book reading/search permissions remain tenant-scoped under their existing rules; receipt privacy does not transfer or privatize shared tenant book content.

Legacy receipts have no reliable authenticated uploader or original event metadata. Their new provenance/date fields remain NULL and they are excluded from authenticated private history/status, rather than assigned by guessing. New history persists across page closure and later logins for the same user. Numeric uploader provenance intentionally has no user FK: delegated account-only hard deletion must retain tenant content/receipts rather than cascade-delete them. The existing background executor is preserved; this increment does not introduce a new durable resubmission queue for interrupted server processes.

## Tenant/name uniqueness

The existing book name remains the name field. Different tenants may use the same name. Existing tenant/raw-name uniqueness is retained, any obsolete single-column global name index is removed, and a generated `canonical_book_name=LOWER(TRIM(book_name))` with unique `(tenant_id,canonical_book_name)` closes the concurrent validation race for existing comparison rules. Application duplicate validation and constraint-error handling use the same tenant/name semantics. The displayed name is unchanged.

The migration stops before changes when existing non-null tenant/name comparison collisions are found. It neither deletes nor renames those rows. Historical null-tenant books remain quarantined and unclaimed. On this MySQL host, existing name/table collation matched `utf8mb4_0900_ai_ci`; temporary-table checks confirmed another tenant's equal name succeeds and a same-tenant case/space variant is rejected.

## UTC storage and viewer-local display

Upload events and authentication events use server-generated `Instant` values. Audited book/author/topic events now use explicit UTC wall-clock values for existing DATETIME columns, with JDBC 4.2 LocalDateTime binding to avoid applying the JVM offset a second time through Timestamp/Calendar conversion. Search and metadata-update DTOs expose `Instant` timestamps with a UTC `Z`; direct audited snapshots also serialize explicit UTC offsets. The database connection and Hibernate UTC settings remain in force. Existing stored event values are not bulk-shifted or assigned a guessed historical timezone.

The shared `viewerDate` pipe formats instants with `Intl.DateTimeFormat` and the current browser/device's named IANA zone, including daylight saving rules. Management details, cleanup dates and upload history use it. The displayed zone is labeled. Tests cover the same event in India and London, London winter/summer changes, equivalent offset/Z inputs, missing/invalid values and no double conversion. Offset-free API date-time strings are rejected as ambiguous rather than silently interpreted in the wrong zone.

There is no existing user-editable local event-date/time input: receipt creation is server-generated and profile/metadata editing does not allow event timestamps. No new naive local date-time input was added. Existing legacy metadata without reliable timezone provenance is preserved for ownership/timezone-reviewed migration rather than blindly rewritten.

## Full Page book/topic reader

The shared View Book/View Topic reader has a **Full Page** option plus Single/Double Page selection before entry and inside it. Small screens default to Single Page, large screens to Double Page; explicit user choice survives subsequent layout changes. Full Page occupies the viewport above the application chrome, keeping the header/footer/sidebar out of the reading view. Exit returns to the still-mounted normal reader, preserving PDF page/mode and, when no full-page navigation occurred, the preview's pixel scroll position. Back from that reader still returns to the original topic popup/results. Enter/Exit do not refetch the PDF.

A decorative single front cover displays the book name; a plain back cover and opening/closing/page-turn effects provide the book presentation. Covers are separate presentation states, not replacements for PDF pages. Navigating from the front starts at the first actual page; every page remains reachable, including an odd final page. Entering on a later preview page and immediately exiting restores that original page. Facing spreads navigate together, Single/Double switching retains the focused physical page, and invalid/failed navigation does not advance beyond boundaries. Reduced-motion styling disables the decorative animation.

Exactly five controls are arranged at the bottom: Sound and Exit on the left, the Single/Double toggle in the center, Previous and Next on the right. Page position is text, not another navigation control. Content sizing reserves space for the controls. Sound starts OFF/grey, becomes blue when enabled, and synthesizes a quiet paper noise only after successful forward/backward PDF page movement. Enabling sound, disabled navigation, failed rendering and cover-only transitions do not play it. Audio is initialized/resumed under the user's Sound gesture, uses no network sound asset, and releases active sources/context with the reader. PDF render handles, canvases and subscriptions are cleaned up.

## Home layout

Home's collection/table surface is solid white with readable text and controls. The full-height middle workspace recalculates through grid/flex constraints when profile or search changes. Search plus collection use equal columns when both are open; closing search leaves one full-width collection. A single collection table viewport owns actual horizontal overflow. It fills remaining height so its native scrollbar is immediately above the pinned bottom pagination strip and footer, even with only a few rows. Empty/loading layouts remove forced data-column widths; populated tables remain scrollable instead of clipping data. The inner book-table overflow wrapper no longer produces a duplicate scrollbar. Pagination remains functional at the bottom, outside table headings and separate from the scrollbar/footer.

## Migration and rollout

After prior migrations, apply `src/main/resources/db/manual/20261126_upload_history.sql` with a backup and quiesced uploads. It adds six nullable receipt fields and an actor/date history index, the generated canonical-name column/index, and the GET history policy. Back up before applying; MySQL DDL is not transactional. The migration is rerunnable and performs no legacy uploader/date backfill.

On the configured local MySQL database, preflight reported zero active uploads/deletion jobs and zero tenant/name collisions. The schema/data/catalog backup is `.local/db-backups/20261126_upload_history_9d84bdb6/schema-upload-catalog-before.sql`, with adjacent `migration-result.log`. Migration and idempotent rerun passed; a disposable temporary MySQL table verified comparison behavior. Existing account, tenant, role/grant and original library/upload columns were fingerprint-verified unchanged. Generated canonical values and NULL legacy metadata are the intended additions. No live uploads, account mutations, emails or tenant-file operations were performed. Restart the backend and frontend after deployment.

## Validation

- Frontend `npm test -- --watch=false`: **258 tests passed across 44 files**. Log: `tmp/uploads-reader-full-tests.log` in the UI project. Covers upload notices/history/privacy client boundaries, notification dismissal, invalid IDs, viewer timezones/DST, mode/spread boundaries, cover states, successful-navigation sound gating, reader-origin position/refetch behavior, Home visibility and auth/background renewal regressions.
- Frontend `npm run build`: **passed**, with a reader component CSS warning (6.29 kB versus the 4 kB warning budget; the error limit remains unchanged). Log: `tmp/uploads-reader-build.log`.
- Backend `mvn package`: **passed**, with **698 passed, 9 skipped (707 total)** and zero failures/errors. The executable application JAR was repackaged successfully. Log: `target/uploads-reader-package.log`. This includes tenant/name concurrency checks, tenant-plus-user upload history/status isolation, trusted background actor capture, job state/failure persistence, actual UTC database-column round trips and explicit UTC API serialization, plus the existing PDF/extraction/authentication regressions. The legacy duplicate-title search fixture temporarily removes and restores both unique constraints only in its isolated test database; production protection remains enabled.
- Actual MySQL migration/rerun, existing-data preservation and isolated tenant-name comparison checks: **passed**. Log: `target/upload-history-migration.log`.

Destructive, upload and extraction tests use isolated H2/temp PDF fixtures and controlled model responses. No live AI extraction, live user-owned upload or real browser/audible playback journey is claimed. Browser geometry and actual device sound remain manual-verification limitations in this environment.
