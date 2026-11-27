# Document table names, explicit PDF reset and upload directories

This increment applies the latest table/path request. It supersedes older physical table names and upload-folder examples in previous guides. Public API routes, JSON fields and existing ID column names remain compatible.

## Canonical tables

| Previous table | Current table |
| --- | --- |
| `book_details` | `pdf_file_details` |
| `topic_details` | `pdf_file_topic_details` |
| `book_upload_status` | `pdf_file_status` |
| `author_details` | `pdf_file_author_details` |
| `upload_file_excel_sheet_csv` | `spreadsheet_file_details` |
| `upload_sheet_csv_xlsx` | `spreadsheet_sheet_details` |
| `upload_sheet_rows_csv_xlsx` | `spreadsheet_sheet_rows` |

The three repeated spreadsheet names in the request were three distinct normalized tables (files, sheets and source rows), not multiple copies of one table. Actual MySQL inspection found one copy of each. They were renamed together, preserving their relationships and data. All seven previous physical names are now absent.

The requested `pdf_file_status` name is used for the existing shared receipt table. Spreadsheet receipts still reference it; a physical rename does not introduce a second lifecycle or discard spreadsheet history. API `book_upload_id` and existing `book_id`, `topic_id`, `aouthor_id` columns are retained to avoid changing contracts unnecessarily.

## Requested PDF data reset

The explicit one-time reset removed **64 PDF topic rows, 8 PDF file rows, 8 PDF author rows and 12 PDF receipts** from the configured local `search_book_download` database. Post-reset counts are zero in all four PDF tables. There were no active uploads or deletion jobs during the operation and no backend listener on port 9090.

The reset protected receipt IDs referenced by spreadsheets and unlinked failed spreadsheet receipts identified by their CSV/XLSX/XLS filenames. Spreadsheet tables had zero rows before this reset and remained unchanged. Account, tenant, permission, role, session and other unrelated table fingerprints were verified unchanged. Generated IDs were not reset or reused.

A private schema/data backup was written before changing names or deleting rows:

`.local/db-backups/20261126_pdf_reset_names_eb33d8a6/schema-document-data-before.sql`

Generated columns are excluded from backup INSERT statements; source paths and original data are retained in the backup for operator-reviewed restoration. The adjacent `migration-result.log` and `target/document-reset-migration.log` record counts and preservation checks. No existing physical files were bulk-deleted. The request's database-row reset is separate from filesystem cleanup, and existing persisted legacy locators remain readable.

## New upload directory rule

All new PDF, CSV, XLSX and XLS objects use the common builder:

```text
C:\workspace\backend\Book_Search\upload-pdf\{tenant_id}\{file_type}\{user_id}\{book_name}\{stored_file}
```

The existing configurable upload root is retained. Canonical file-type folders are `PDF`, `CSV`, `XLSX` and `XLS`. Tenant and user IDs come from trusted server identity, never multipart path parameters. PDF uses its existing sanitized book title. CSV/Excel uses the sanitized original filename base as book_name. No additional file/sheet ID directories are inserted.

Examples:

```text
upload-pdf/42/PDF/7/MyBook/MyBook.pdf
upload-pdf/42/CSV/7/Sales/Sales.csv
upload-pdf/42/XLS/7/Report/Report.xls
upload-pdf/42/XLSX/7/Report/Report_Employees.xlsx
```

XLS originals remain XLS; normalized standalone Excel sheets use the XLSX folder. CSV's logical sheet reuses its original object. Actual returned locators are persisted and used for retrieval; older objects are not relocated or reconstructed using the new pattern.

Missing upload-root, tenant, type, user and book directories are created recursively before writing. Names are sanitized and tenant confinement, symlink/junction checks and deletion locks remain enforced. Existing folders can be reused without overwriting old files: collisions get a technical leaf suffix. Cleanup removes only files tracked by the current attempt. PDF operation UUIDs ensure a late rollback callback cannot clean a newer attempt using the same book folder.

## Migration order and duplicate handling

Apply `src/main/resources/db/manual/20261126_document_table_names.sql` **after all earlier manual migrations**, with application workers stopped and a verified backup. Historical migrations remain unchanged as deployment history. Do not rerun their old CREATE TABLE scripts against the renamed schema; doing that can recreate obsolete names or conflict with existing foreign-key names.

The terminal naming migration contains **no row purge**. It checks all mappings before DDL, removes only empty/schema-compatible old aliases with no outside FK dependencies, and atomically renames old-only tables. Populated old/canonical collisions, mismatched schemas and unexpected dependent tables cause an explicit failure for review. FK checks are never disabled. Rerunning the terminal migration preserves existing canonical tables and rows.

The PDF reset was a separately authorized, backed-up operation performed once for this request. Running the naming migration later does not clear newly uploaded documents.

## Verification

- Actual MySQL scratch-schema checks passed: data/FK-preserving rename, idempotent rerun, child-first empty-duplicate removal, populated-collision rejection, unexpected-FK rejection and schema-mismatch rejection. The scratch database was deleted and confirmed absent. Log: `target/document-table-migration-scratch.log`.
- Live backup, naming migration/rerun, PDF reset, zero-count checks and unrelated-data preservation passed. Logs: `target/document-reset-migration.log`; read-only verification confirmed all seven canonical names and no old aliases.
- Focused storage/upload tests: **50 passed**, including missing directories for all supported types, the exact new hierarchy, trusted uploader identity, collision handling, legacy reads, rollback ownership, actual PDF bytes/ranges and CSV/Excel ingestion. Log: `target/document-storage-focused.log`.
- Final `mvn package`: **751 passed, 9 skipped (760 total), zero failures/errors**. The executable application JAR was built. Log: `target/document-table-layout-package.log`. The full run covers renamed-table SQL/JPA behavior, background uploads, fallback/transaction cleanup, tenant isolation and existing account/role flows; old path/metadata test fixtures were updated to assert the new contract.

Restart the backend with the updated build before using the renamed database. Frontend/API contract changes are not required for this increment.
