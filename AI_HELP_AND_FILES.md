# AI Help, imports and file deletion

> Historical implementation record: the chat and deletion contracts below describe the earlier request. The current persistent chat, role-based deletion and viewer contracts are in [PERSISTENT_CHATS_AND_VIEWERS.md](PERSISTENT_CHATS_AND_VIEWERS.md). The CSV/XLSX validation rules remain current.

Apply the additive manual catalog scripts `src/main/resources/db/manual/20261128_ai_help.sql` and `20261128_file_delete_endpoints.sql` before using the new routes. They register endpoint policies; they do not grant roles new permissions. These scripts have not been applied to the application's configured database by this change.

## Request contracts

All routes below use the existing authenticated bearer session, tenant context and CSRF protection for mutations.

| Endpoint | Request | Success | Errors |
| --- | --- | --- | --- |
| `POST /api/ai-help/chat` | Raw query string, `Content-Type: text/plain` | `200 text/plain`: `Query received. This is an acknowledgment only.` | `400` blank/missing body, `415` other content type; existing `401`/`403` authentication/policy/CSRF errors |
| `DELETE /api/books/{bookId}` | Positive unique book ID, no body | `204`, after storage removal and database commit | `404` absent/foreign/not uploader, `409` active upload or ambiguous/shared storage, `503` incomplete cleanup |
| `DELETE /api/spreadsheets/{fileId}` | Positive unique spreadsheet file ID, no body | `204`, including original, worksheet artifacts and imported rows | Same deletion errors |
| `DELETE /api/uploads/{uploadId}` | Positive unique upload receipt ID, no body | `204`, including associated file, or a failed receipt with no remaining file | Same deletion errors |

AI Help prints the received query through `System.out.println(query)`. The header panel retains local messages across close/reopen, disables duplicate pending sends, permits retry after errors, and clears on an authentication boundary reset. It labels the result as an acknowledgment. No support queries are persisted and no AI model, support workflow, tool registry or automatic tool execution is connected to this endpoint.

Deletion requires existing `BOOKS.DELETE` permission and uploader ownership. Names are display labels; persisted IDs and storage locators select the objects. The UI refreshes the relevant table only after a successful response. A storage or database failure leaves metadata and exact storage references available for a retry of the same DELETE. Already missing physical files are tolerated. Some bytes may have been removed before a failure; they are not restored by a database rollback. Retry finishes cleanup, rather than undoing it. No automatic background file-deletion worker is introduced. Shared legacy storage locators are rejected to protect other files.

## Import validation

The first record/worksheet row defines the headers. Every header position requires a nonblank label; duplicate labels remain supported using the existing positional `column_1`, `column_2`, etc. keys. Excel width ends at the last header value/formula, excluding formatting-only trailing cells. Each worksheet needs headers.

CSV uses Commons CSV RFC4180 parsing, including quoted commas, multiline fields and trailing empty fields. Every subsequent parsed record may have at most the header width. Extra fields fail at the first offending record, with its logical record number and expected/actual field counts. Missing and empty values map to null without shifting later columns. Empty records are data rows too.

Excel uses cell indexes: extra values or formulas fail with worksheet, row and offending column information; styled empty extra cells do not expand the header or fail a data row. Existing saved-result requirements for in-range formulas remain in place. Empty cells and missing rows/cells become positional nulls.

Every source record is read to validate the entire file, independently of preview pagination. Validation and source parsing happen once before database reservation or persistent storage. Validated rows are spooled sparsely to temporary disk files, then read in bounded batches by the existing background persistence flow. Invalid inputs and rejected execution clean up temporary sources/spools. Background compensation waits for workers, discards imported rows, and removes written objects. A storage cleanup outage retains only failed file/sheet manifests for recovery through deletion; a simultaneous database outage can require retrying that cleanup too.

Tracked spreadsheet writes allocate an exclusive UUID object and commit its locator before opening/copying the source. A failure to register the locator cannot leave uploaded source bytes behind. If locator registration and placeholder cleanup both fail, at most an empty reserved object can remain; its path is included in the failure log for operator cleanup.

CSV is streamed. XLS/XLSX input still uses the existing POI workbook model to preserve style-aware standalone worksheet downloads, bounded by the existing upload and expanded ZIP limits. Workbook memory is therefore not constant. Worksheet artifact generation reads that in-memory workbook; it does not reparse/revalidate the source file.

## Future AI extension point

See the complete service audit and tool list in [README.md](README.md#service-tools-for-a-future-ai-integration). Select annotated methods from Spring-managed beans in your future integration. Preserve server-established authentication/tenant contexts, session validation and endpoint authorization. Credentials and actor identities must not be supplied through model arguments. Existing provider dependencies and PDF extraction behavior remain unchanged.

## Main changed files

- Chat: `help/AiHelpController.java`, header component, frontend `features/ai-help/ai-help.component.ts` and tests.
- Imports: `spreadsheet/parse/{SpreadsheetParser,ParsedSpreadsheet,SpreadsheetRowBatch}.java`, `SpreadsheetUploadService.java`, `SpreadsheetRepository.java`, `storage/DocumentFileStore.java`, frontend nullable spreadsheet row model and parser/upload/storage tests.
- Deletion: `UploadedFileDeletionService.java`, `UploadedFileDeletionController.java`, shared frontend `delete-upload` component, book/spreadsheet/history tables, home refresh wiring and deletion tests.
- Tool exposure: `tools/{ToolCaller,ServiceToolAccess}.java`, guarded methods in the services listed in README, and guard/schema tests.
- Policy: `AuthorizationService.java`, the two new catalog scripts and isolated authentication test fixture updates.

## Verification

- Full backend Maven suite: **798 tests run, 0 failures, 0 errors, 9 skipped**, including application startup. The skipped cases retain their existing optional/disabled conditions.
- Focused backend HTTP, tool and deletion checks: **31 passed**.
- Full Angular suite (`npm test -- --watch=false`): **294 passed** across 50 files.
- Angular production build (`npm run build`): passed; existing reader CSS warning remains (7.04 kB against a 4 kB warning budget, below the 8 kB error budget).
- Both repositories' `git diff --check`: passed.

Backend checks used cached dependencies in ignored `target/maven-repository`, isolated H2 databases and temporary storage. The managed environment cannot read the developer's protected local signing file, so the full command selected an absent optional config location and supplied a disposable signing key through a test system property:

```powershell
mvn '-Dmaven.repo.local=target/maven-repository' -o test `
  '-Dspring.config.location=optional:classpath:isolated-test-no-local.properties' `
  '-Dapp.user.jwt.secret=aXNvbGF0ZWQtZGlzcG9zYWJsZS10ZXN0LXNpZ25pbmcta2V5LTMyaXRlbQ==' `
  '-DargLine=-Dpdfbox.fontcache=target -Djava.io.tmpdir=C:/workspace/backend/Book_Search/Book-Search-Download/target/test-tmp'
```

This config override is for isolated tests, not deployment configuration. No live model, SMTP delivery, production data deletion, migration application or browser visual session was exercised for this change. The chat contract was verified through Angular HTTP tests and the real backend bearer/policy/CSRF chain with captured controller output.
