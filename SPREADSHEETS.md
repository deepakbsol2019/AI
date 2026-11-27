# CSV / Excel uploads, previews and downloads

The later [document table and directory update](DOCUMENT_TABLE_LAYOUT.md) supersedes this guide's original physical table names and storage examples. Current tables are `spreadsheet_file_details`, `spreadsheet_sheet_details`, `spreadsheet_sheet_rows`, and the shared receipt table is `pdf_file_status`. New files use `<root>/<tenant>/<type>/<user>/<book-base>/<stored-file>`; legacy locators remain readable. Apply the terminal naming migration after the migrations described here.

This implements the latest specification (`82730435-e211-4669-bf3a-2702d012e7dd`). It replaces the earlier proposed multiple-sheet merge/download request. The user confirmed **PDF** as Home's initial tab. There is no outstanding initial-tab choice.

## Behavior

Upload Document still posts multipart `file` to `/api/upload-file`. Existing PDF processing/downloads and persisted PDF paths remain unchanged. CSV, XLSX and XLS route to a separate spreadsheet coordinator after actual content validation. The existing generic receipt remains the only lifecycle: acceptance is PENDING/background work; COMPLETED requires the original object, every sheet artifact and every acknowledged row batch to succeed. Browser receipt polling and My Uploads retain their existing tenant-plus-uploader privacy checks.

The spreadsheet library is shared within the authenticated tenant under existing library VIEW/DOWNLOAD permissions. File/sheet IDs never authorize another tenant. Server context supplies tenant and uploader; multipart identity parameters are ignored. The new file ID is independently generated and linked to the existing `book_upload_id` through `upload_id`.

Home has **PDF** and **CSV / XLSX** tabs. Selected is gray and remains enabled; unselected is blue. `INITIAL_DOCUMENT_TYPE` in UI `features/books/models/document-type.config.ts` is the single initial setting. Initial load requests only PDF data; switching requests the matching backend, preserves the common search text, resets pagination and cancels obsolete responses. PDF headings/actions remain unchanged. Spreadsheet filename search runs against spreadsheet metadata, not PDF records or row payloads.

Spreadsheet columns are File Name, Uploaded By (resolved display name), Uploaded At (existing viewer-local timezone formatting) and Actions. The three top-level actions are **Download File**, **View File**, and **View Sheet**. View File opens a table viewer, initially on the first ordered sheet, with a sheet selector for workbooks. CSV has no unnecessary selector. View Sheet opens a topic-style ordered sheet list, each with View and Download. Previews fetch at most 100 rows at a time; they do not use the bulk service JSON endpoint or attempt to render Excel bytes through PDF.js.

## Persistence and batching

The additive MySQL migration creates exactly three business tables:

- `upload_file_excel_sheet_csv`: generated Long `file_id`, existing `upload_id`, tenant, original filename/locator, sheet count, UTC `uploaded_at` and authenticated `created_by`.
- `upload_sheet_csv_xlsx`: generated Long `sheet_id`, parent file, 1-based sheet index, name, source header row 1, JSON header, counts, standalone locator and UTC/uploader audit values.
- `upload_sheet_rows_csv_xlsx`: generated payload ID, matching file/sheet parent, original source `row_number` and one JSON cell map per data row. Composite foreign keys prevent mismatched file/sheet ownership; `(sheet_id,row_number)` is unique.

All required columns are NOT NULL. Empty locator strings are technical PROCESSING reservations only; publication requires populated locators. `canonical_name` and `original_format` are technical file columns. `original_upload_path` is necessary because the existing generic receipt has no original-file locator. `created_by` intentionally has no account FK so the established account-only deletion flow can preserve tenant content. Tenant purge deletes these children before their generic receipts.

Header and each payload are ordered `Map<String,String>` objects with `column_1`, `column_2`, etc. Duplicate/empty labels remain header values and cannot overwrite cells. Extra data columns receive empty header labels. MySQL JSON property iteration is normalized back to numeric column order before returning maps. Blank cells are `""`; every cell value is a string. Source positions are separate from generated payload IDs.

Logical batches are fixed at **500 retained data rows per sheet**, with an unpadded remainder. The detached DTO holds `List<Map<String,String>>` plus immutable source positions. One parser thread owns the workbook; workers receive only detached values and explicit tenant/uploader/file/sheet IDs. JDBC `batchUpdate` performs real batch INSERTs despite generated IDs. Each worker calls a separate transactional bean; parent metadata commits before dispatch, and no outer transaction waits for workers.

Defaults: two row workers, queue capacity four, at most six pending batch handles per upload, with caller-runs backpressure that rejects shutdown submissions. Existing upload coordinators use their bounded executor. Properties `app.spreadsheet.batch-pool-size` (1–4) and `app.spreadsheet.batch-queue-capacity` (1–32) can be configured alongside the deployment's DB pool. No threshold switches or one-thread-per-row behavior is used.

On failure, queued work is stopped and running transactions quiesce before compensation. Only tracked objects/rows from that attempt are removed; the generic FAILED receipt remains. Successful cleanup releases the name reservation for retry. Failed object cleanup retains quarantined metadata/reservation and logs upload/file/object identifiers for administrator cleanup; it never publishes partial data as completed. The existing executor is not a new durable restart/resubmission queue.

## Explicit defaults and format limits

- **Duplicates:** the project already defines tenant-scoped case/trim-insensitive file/book names. Spreadsheet reservations apply that policy to the original filename's base name, backed by a unique tenant/canonical-name index. A same-name CSV/XLSX conflicts within one tenant; another tenant may upload it independently. This is not a new SHA-256/content-dedup policy. Failed compensated attempts release their reservation.
- **CSV:** strict UTF-8, optional UTF-8 BOM, RFC4180 comma delimiter, quoted fields and embedded newlines. Source row numbers count logical records. Decoded cell text is preserved. Whitespace-only data rows are skipped while retained rows keep their source positions. Empty/blank-only or malformed CSV is rejected; header-only CSV is valid. CSV has one logical sheet named `Sheet1`.
- **Excel:** every sheet, including hidden sheets, is retained in original order. Empty sheets have header `{}`, zero columns and zero rows; header-only sheets retain their header. Formatted text uses POI DataFormatter with `Locale.US`, saved cell formats and cached formula results; no formula evaluator, macro or external-link execution is used. Leading zeros present in text/number formats survive; zeros already absent from a numeric source cannot be invented.
- **Standalone Excel:** both XLS and XLSX inputs produce **XLSX** sheet artifacts, with truthful filenames/media types. Source cell types, common styles/fonts/colors, widths, merged regions, hidden rows/columns and date windowing are preserved. Unqualified local formulas are retained when supported, with saved caches. Qualified references (including qualified self-references), external references and workbook-name formulas become their explicit cached literals so the standalone file does not have broken dependencies. Missing saved XLSX formula results are rejected. Whole originals preserve their exact bytes, including features not copied to standalone sheets.
- Standalone extraction does not promise perfect drawings/charts, macros, workbook names, rich-text runs or every advanced Excel feature. Original-file download retains these original bytes. Table previews show saved cell/header values, not a full Excel editing/chart-rendering application.
- Uploads are bounded at 100 MiB. Inputs are temp-file-backed; one POI workbook model remains in memory, with XLSX expansion capped at 256 MiB total, 64 MiB per entry and 10,000 ZIP parts. SXSSF's 100-row output window is a writer-memory setting, not the required 500-row ingestion batch size. No all-workbook JSON list or all-sheet byte-array collection is accumulated. Binary endpoints retain the existing byte-array response convention and its final allocation, with a 100 MiB object-read bound.

## Storage

The configured existing upload root and TenantStoragePaths confinement are reused. New spreadsheet objects use generated identity segments:

```text
<existingRoot>/<tenantId>/spreadsheets/<fileId>/original/<safeOriginalFilename>
<existingRoot>/<tenantId>/spreadsheets/<fileId>/<sheetId>/<originalBase>_<sheetName>.xlsx
```

Each returned locator is persisted verbatim. Exclusive object creation and a technical leaf suffix prevent overwrites; display/source names remain in metadata. CSV's logical sheet reuses its original object, and cleanup deduplicates that locator. No legacy PDF object is moved or reconstructed with a new path rule. Whole-file download reads the preserved original bytes. Individual sheet download reads the persisted standalone locator and never reopens/re-extracts the original workbook.

## API examples

Browser APIs use the existing bearer/session authorization and library permissions:

| Method / path | Result |
| --- | --- |
| POST `/api/upload-file` (multipart `file`) | Existing upload receipt plus `fileId`/`sheetCount` for spreadsheets; `Location: /api/upload-status/<uploadId>`. |
| GET `/api/spreadsheets?search=Report&page=0&size=20` | Completed tenant files, resolved uploader names, counts and pagination. |
| GET `/api/spreadsheets/12` | File metadata and ordered sheets/header/count metadata, without every row payload. |
| GET `/api/spreadsheets/12/sheets/35/rows?page=0&size=100` | Bounded preview `{rows,rowNumbers,number,size,totalElements,totalPages}`. |
| GET `/api/spreadsheets/12/download` | Exact original bytes and safe original attachment filename/media type. |
| GET `/api/spreadsheets/12/sheets/35/download` | Stored standalone sheet bytes and derived filename/media type. |

No multiple-sheet selection/merge API or JSON frontend button was added under the final scope.

Service API: GET `/api/internal/spreadsheets/12/sheets/35/data` streams one JSON response from ordered database rows in 500-row cursor reads, including all retained rows:

```json
{"fileId":12,"fileName":"Report.xlsx","sheetCount":2,"sheetId":35,"sheetIndex":1,"sheetName":"January","columnCount":1,"dataRowCount":1,"uploadedAt":"2026-11-26T12:00:00Z","createdBy":11,"header":{"column_1":"Employee ID"},"rows":[{"rowNumber":2,"payload":{"column_1":"0012"}}]}
```

Internal paths, tenant IDs and signed URLs are not exposed. IDs/counts/source positions are numeric metadata; cell/header maps contain only strings. A missing legacy original locator returns an explicit conflict; missing/inaccessible stored objects use the existing not-found conventions.

### Service authentication configuration

The application previously had only browser-session-bound access tokens. The internal route now has an independent stateless RS256 JWT chain, using the existing Spring Security resource-server dependency. It validates signature, issuer, audience, expiry/not-before/issued-at, `spreadsheets.read` scope and a configured service-client-to-tenant grant. An optional token `tid` must match that grant; request/body tenant values do not grant access. Browser session IDs/cookies/CSRF are not required. Tenant status/deletion locks are checked. Configuration is intentionally fail-closed until the actual service issuer/client grant is supplied:

```properties
# Configure in application.properties or deployment configuration; example values only.
app.spreadsheet-service.issuer=https://identity.example/issuer
app.spreadsheet-service.jwk-set-uri=https://identity.example/issuer/jwks
app.spreadsheet-service.audience=book-spreadsheets-api
app.spreadsheet-service.client-tenants=reporting-service=42,another-service=77
```

The token must contain the configured `client_id` (or `azp`) and scope. HTTPS is required for issuer/JWKS except local loopback development. No service credential or client tenant grant was invented for the user's deployment. Until configured, the internal endpoint returns 503 rather than accepting unauthenticated/browser credentials. This follows [Spring Security JWT resource-server validation](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html).

## Migration and validation

Apply `20261126_spreadsheets.sql` then `20261126_spreadsheet_endpoints.sql` after existing migrations, with a backup and no active uploads/deletion jobs. MySQL DDL is not transactional. Both scripts are rerunnable; no existing objects, accounts, grants or library content are renamed or backfilled.

The configured local MySQL migration and idempotent rerun passed. Temporary-table checks verified same-tenant name conflicts and independent cross-tenant names. Existing account/role/library fingerprints were unchanged. Private backup: `.local/db-backups/20261126_spreadsheets_7e522306/schema-catalog-before.sql`; adjacent `migration-result.log`. Log: backend `target/spreadsheet-migration.log`. No live accounts, emails or uploads were created.

- Final backend `mvn package`: **757 tests run, 748 passed, 9 skipped; zero failures/errors; executable JAR built.** Log: `target/spreadsheet-package.log`.
- Final UI full suite: **287 tests passed across 48 files** (`tmp/spreadsheet-ui-full.log`). Final viewer checks: **5 passed** (`tmp/spreadsheet-view-final.log`).
- Final UI production build: **passed** (`tmp/spreadsheet-ui-build.log`). The pre-existing reader stylesheet warning remains 7.04 kB versus the 4 kB warning budget, below the unchanged 8 kB error threshold.
- Coverage includes 0/1/499/500/501/1,200/10,001-row parsing, remainder/source positions, immutable string maps, CSV BOM/quotes/newlines/blanks, wider and duplicate headers, hidden/empty/header-only sheets, cached formulas/XLS normalization, actual JDBC batches/transactions, concurrent uniqueness, foreign-key/tenant isolation, worker/object failures and cleanup, exact originals, individual stored-sheet downloads, full 1,200-row DB JSON export, real RSA signatures, service auth without browser sessions, tab requests/colors/search/stale responses, previews and action labels.
- Browser visual verification was attempted but Windows UI automation returned `GetCursorPos failed: Access is denied (0x80070005)`. No live browser/device layout journey is claimed. Actual external identity-provider configuration is still deployment work, as described above.

Restart the backend and frontend after deployment.

## Changed files in this increment

Backend: `pom.xml`; `entity/BookUploadStatus.java`; `storage/TenantStoragePaths.java` and new `storage/DocumentFileStore.java`; `upload/controller/impl/UploadFileControllerImpl.java`; `user/service/UserDeletionService.java`; new `spreadsheet/parse/*`, `spreadsheet/persistence/SpreadsheetRepository.java`, `spreadsheet/upload/*`, `spreadsheet/api/*`, and `spreadsheet/security/*`; the two spreadsheet migrations above; corresponding parser/persistence/coordinator/API/security/storage tests plus `UploadFileControllerTests` and `AuthTestDatabase` endpoint seeding.

Frontend: new `models/document-type.config.ts`, `models/spreadsheet.models.ts`, `data-access/spreadsheet-api.service.ts`, `components/spreadsheet-table/*`, `components/spreadsheet-view/*` and tests; updated Home/search components, `book-download.service.ts`, upload-service/header supported-format labels; new Home document-type routing tests. Both READMEs link this guide. Existing PDF reader implementation and unrelated user-management changes were preserved.
