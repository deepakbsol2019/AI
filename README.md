# Book Search Download backend

Spring Boot 4.2 / Java 25 backend with the existing Ollama, MySQL, PDFBox, upload-status, and local PDF storage flows.

The current [document table names, PDF reset and upload-directory guide](DOCUMENT_TABLE_LAYOUT.md) records the requested canonical names, verified row cleanup/backup, safe duplicate handling and `tenant/type/user/book` folder rule. It supersedes older physical names and folder examples below.

The latest [CSV / Excel upload, preview and download guide](SPREADSHEETS.md) documents the three-table migration, fixed 500-row ingestion, exact originals and standalone sheets, PDF / CSV / XLSX tabs, tenant isolation, service-only JSON authentication and actual verification results.

The current [viewer, profile and role update](VIEWER_PROFILE_ROLE_UPDATES.md) covers custom reading colors, stable facing-page rendering, fullscreen zoom, notification-only Home uploads, tenant-specific Add User role selection and the header profile icon, with verification results and runtime limits.

The latest [upload history, UTC events and Full Page reader guide](UPLOAD_HISTORY_READER.md) covers private tenant-plus-user upload history, Upload Document notices, tenant-name uniqueness, local timezone display and Home scrolling/pagination. It supersedes conflicting earlier upload/layout descriptions and records migration/verification details.

Latest changes are in [profile, permissions and role layout](PROFILE_ROLE_LAYOUT.md): cleanup/permission modals, truthful delete results, Copy Role, minimized profile sidebar, half/full-width library layout and tenant-authorized Add User (including Individual accounts). This supersedes conflicting earlier layout/invitation descriptions.

The current [management pages and sidebar guide](MANAGEMENT_PAGES.md) covers full-page user/details/create/confirmation routes, shared fixed shell, permission-based current-tenant invitations and distinct soft deletion. It supersedes earlier details-popup/header-profile layout descriptions while retaining the confirmed SUPER_USER policies.

Current [User Management](USER_MANAGEMENT.md) implements `/user/users`, scoped details/editing, dedicated activation and role assignment, durable deletion jobs, the authenticated header profile, and standalone registration directories. Its deletion matrix and provisioning rules supersede earlier account-deletion exclusions and lazy individual-directory descriptions.

Current organization roles and tenant-directory rules, APIs, migrations and verification are in [ROLE_MANAGEMENT_STORAGE.md](ROLE_MANAGEMENT_STORAGE.md). They supersede earlier ADMIN registration and application-wide file-access statements below.

Password recovery API, password rules, migration and deployment configuration are documented in [PASSWORD_RESET.md](PASSWORD_RESET.md).

## Authentication and tenant authorization

Application-wide `SUPER_USER` provisioning is documented in [SUPER_USER_SETUP.md](SUPER_USER_SETUP.md). It uses the existing role tables, retains authentication/session checks and keeps unassigned historical books inaccessible. Ordinary tenant ADMIN registration remains unchanged.

The application now requires an authenticated, active tenant session for the existing book APIs. Login, registration, password setup/reset and provider callbacks use the new `com.book.user` feature; the sibling Angular app opens its User login container before the guarded Home route. Google and company SSO are implemented but disabled by default. Password registration requires a configured SMTP sender; it never collects or emails a registration password.

See [AUTH_SETUP.md](AUTH_SETUP.md) for API contracts, local/deployment configuration, rotating sessions, SMTP templates, optional MFA, trusted provider activation/enrollment and safe examples. [AUTH_SCHEMA.md](AUTH_SCHEMA.md) inventories both supplied schema references, all 18 tables, MySQL adaptations, explicit additions and permission policies. New Java auth code stays under `com.book.user`; existing catalog changes enforce tenant ownership and propagate it explicitly to upload workers.

The versioned manual migration [20261125_user_auth.sql](src/main/resources/db/manual/20261125_user_auth.sql) was validated twice on isolated MySQL, including 16 rolled-back constraint checks, then applied to the configured application database after a backup and zero-active-upload check. Its 5 books, 39 topics and 9 receipts were preserved. All historical book/receipt tenant IDs remain null and inaccessible, as requested; no users were created in the real catalog. Hibernate now validates the schema rather than updating it. Apply the migration on other deployments before starting this code.

Database/signing/SMTP secrets are external. For this workspace only, an ignored `target/local-user-runtime.properties` contains the development signing key and the previous local database credential; it is not a configuration example or production secret store. The safe tracked examples are [config/auth.env.example](config/auth.env.example) and [config/application-oidc.example.properties](config/application-oidc.example.properties). The earlier literal database credential and credential-like SMTP comment were removed from application configuration; replace any previously used exposed secret externally.

Final validation passed: **565 backend tests run, 556 passed, 9 existing optional checks skipped, no failures/errors**, followed by successful executable-JAR packaging in the same full `verify` run. All auth tests ran, including queued recovery-email delivery, cleanup failure containment and OIDC failure auditing. The frontend passed **172 tests across 34 files**, strict app/spec TypeScript checks and a warning-free production build (**448.04 kB raw / 96.42 kB estimated transfer**). Automatic upload checks and completion reloads cannot renew an unattended session; sign-out clears prior-account upload/download/notification state. After restarting the final JAR, **17 live HTTP checks** passed through the Angular proxy. The migrated backend starts with schema validation and both provider flags disabled; no real users/sessions were created by the live checks. Details and exact commands are in [AUTH_SETUP.md](AUTH_SETUP.md). Historical counts in older feature sections describe their earlier changes. Real provider logins, real SMTP delivery and browser/mobile interaction remain unperformed; the browser access host was unavailable. The optional OIDC-to-local-MFA handoff limit is documented in the setup guide.

## Homepage search listing

`GET /api/search/books` now defaults to zero-based `page=0` and **`size=5`** when size is omitted. Explicit larger sizes remain supported; the existing valid API range is still 1–100. This default is applied only at the REST listing controller. The shared `searchBooks` AI tool retains its existing omitted-size default of 20; unrelated endpoint defaults are unchanged.

`bookName`, `authorName`, and `topicName` now use case-insensitive literal **contains** matching in the database: `Java` matches `Effective Java` and `Java Programming`. The broad `query` remains an OR across book name, author name, topic name, book description and topic description, with substring matching in each field. Separate supplied filters remain ANDed; an explicit topic name and topic-description filter must match the same topic. Surrounding whitespace is removed, empty optional filters are ignored, and nullable fields safely contribute no match.

All substring patterns use parameter binding and explicit `!` escaping so `%`, `_`, `!` and backslash input stay literal. Existing distinct-book counting and ID-first database pagination are preserved: multiple matching topics yield one book, correct filtered totals and its complete topic list. No match returns an empty result with zero totals. No browser-side substitute search, storage change, or schema migration was introduced. The topic popup's separate local filter is unchanged.

Focused verification ran **69 tests: 68 passed, 0 failures, 0 errors, 1 skipped** across `BookSearchControllerTests`, `SearchServiceTests`, `BookSearchQueryRepositoryTests`, `BookSearchToolTests`, `BookMetadataPersistenceTests` and `BookTypePersistenceTests`. The skip is the optional live-model test (`book.ai.live` was unset). These checks cover the actual H2 query and HTTP/controller flow, name/query substrings, literal wildcard characters, same-topic AND conditions, distinct totals, five-row default pages, unchanged tool default 20, and existing metadata persistence.

```powershell
mvn '-Dmaven.repo.local=target/maven-repository' -o test '-Dtest=BookSearchControllerTests,SearchServiceTests,BookSearchQueryRepositoryTests,BookSearchToolTests,BookMetadataPersistenceTests,BookTypePersistenceTests' '-DargLine=-Dpdfbox.fontcache=target -Djava.io.tmpdir=C:/workspace/backend/Book_Search/Book-Search-Download/target/test-tmp'
```

The owned backend on port 9090 was restarted after checking that no upload was in progress. Eleven live GET smoke checks through the Angular proxy passed: omitted-size listing returned five rows and size 5; partial/case-insensitive book, author, topic and broad queries matched the catalog; repeated topic matches stayed unique with complete topics; literal percent/underscore filters, blank/no-match input, ordered page 1 at size 2 and explicit size 20 retained correct results/counts. The five-book catalog was unchanged. These read-only live checks complement the seven-matching-book pagination scenario in H2. Full backend tests and live AI/upload processing were not rerun for this increment.

Changed files for this search increment are `search/repository/BookSearchQueryRepository.java` (name and broad-query database predicates), `search/controller/impl/SearchControllerImpl.java` (REST-only default), `dto/BookSearchCriteria.java`, `search/tool/BookSearchToolMetadata.java`, the controller/tool/repository test classes above, and this README. The search service, entity schema, metadata-update and PDF flows are unchanged in this increment.

## Independent metadata field updates

`PATCH /api/books/{bookId}/metadata` accepts exactly one provided key: `bookName`, `bookType`, `authorName`, or `bookDetails`. `PATCH /api/books/{bookId}/topics/{topicId}/metadata` accepts exactly one `topicName` or `topicDetails`. Both return the complete confirmed `BookSearchResponse.BookResult`, including all topics; existing search and download contracts stay unchanged. Example bodies are `{"bookType":"Physics"}` and `{"topicDetails":"First paragraph.\n\nSecond paragraph."}`. No database migration or new column is required.

The request DTO tracks property presence: omitted fields remain untouched, while explicit null or empty descriptions clear their values. Empty objects, multiple fields, duplicate JSON keys, unknown/read-only fields, and values other than text or null are rejected with 400. Required book/topic names are trimmed and limited to their existing 255-character columns. A book with an exact reserved `_offset` row also needs room for that existing suffix (248 characters for the book name). Book types are trimmed and limited to 120 characters before domain normalization; null/blank becomes `Unknown`. Author names are nullable, trimmed and limited to their existing 200-character column. Description strings preserve their complete paragraphs and whitespace in the existing LONGTEXT columns; no invented short-text limit or silent truncation is applied. Validation failures return 422, missing/foreign records 404, and conflicts 409 with useful `application/problem+json` detail.

Each save locks and refreshes the parent book before applying only the requested field. This preserves unrelated concurrent field updates without adding a version column. Shared legacy authors use copy-on-write under an author-row lock, so editing one book's credited name does not change another book. Metadata cannot be edited while its linked upload receipt is `inprogress`; retry after processing finishes. Legacy books without a linked receipt remain editable.

Technical PDF storage rows remain visible but their topic names/descriptions are read-only: names ending in `_offset`, plus a legacy sole whole-book row whose name/description match its book, whose physical range is 1 through the total pages, and whose printed-page fields are null. Attempts to edit those topic fields return 409. Ordinary topics cannot be renamed to the reserved `_offset` suffix. Book renames atomically update the exact canonical offset name and its standard technical detail, or the legacy whole-book derived name. Changing a legacy whole-book's book description keeps its technical copy synchronized. These are internal derived values, not unrelated semantic field updates. Existing PDF paths, files, page boundaries, IDs, and AI content are never regenerated or moved.

Focused checks are `BookMetadataControllerTests` and `BookMetadataPersistenceTests`; they cover exact-field JSON presence, explicit clearing, validation, full response/refetch, shared authors, technical-row protection, original-PDF readability after renames, upload-in-progress rejection, rollback, and concurrent independent field saves. The latest targeted suite passed **115 tests with no failures, errors or skips**, including these checks plus search, reading, downloads and application startup:

```powershell
mvn '-Dmaven.repo.local=target/maven-repository' -o test '-Dtest=BookMetadataControllerTests,BookMetadataPersistenceTests,BookSearchControllerTests,SearchServiceTests,BookReaderControllerTests,BookReaderServiceTests,DownloadControllerTests,DownloadServiceTests,DownloadPipelineTests,BookSearchDownloadApplicationTests' '-DargLine=-Dpdfbox.fontcache=target -Djava.io.tmpdir=C:/workspace/backend/Book_Search/Book-Search-Download/target/test-tmp'
```

A live no-op book-type PATCH through the Angular proxy returned 200 and refetched unchanged metadata/audit time from MySQL. A 121-character type returned 422 without changing the stored value. Changed-value persistence, concurrent updates and rollback use isolated H2 tests rather than altering the real catalog. The full backend suite, live AI extraction and live upload processing were not rerun for this incremental change. No new migration was applied or required.

Changed backend files for this increment are the three new `metadata/controller/BookMetadataController.java`, `metadata/dto/MetadataPatchRequest.java`, `metadata/service/BookMetadataService.java` files, `repository/BookDetailsRepository.java`, the two focused test classes and this README. Existing entities, extraction, downloads and migration scripts are unchanged in this increment.

## Inline PDF reading

The Angular reader uses new `GET /api/read/book?bookId={id}` and `GET /api/read/topics?bookId={id}&topicIds={id}...` routes. They return real PDF bytes with `Content-Disposition: inline` and `Cache-Control: no-store`. The existing attachment-download endpoints and their output format remain unchanged. No database migration or new file-path column is required.

Whole-book reading reuses the existing complete-book assembler. Single-topic reading reuses its stored topic PDF. For multiple topics, `BookReaderService` validates unique positive IDs and book ownership, requires authoritative persisted physical page boundaries, then orders the existing topic files by physical start/end (reserved offset tie-break, then ID). This places front matter before later chapters even when its row has a larger ID. Missing/invalid multi-topic boundaries return 409 with an explanation rather than guessed ranges. Existing PDF assembly, generated topic cover/contents, storage confinement and file-error handling remain in use.

The targeted reader/download/startup checks passed **68 tests, no failures or errors**: `BookReaderControllerTests`, `BookReaderServiceTests`, `DownloadControllerTests`, `DownloadPipelineTests`, `DownloadServiceTests` and `BookSearchDownloadApplicationTests`. Live inline requests via the UI proxy verified all three scopes, 372-page and 14-page complete books, and exact selected-source pages in physical order. Frontend visual/PDF dark-mode limitations and its full changed-file list are documented in the sibling UI README.

```powershell
mvn '-Dmaven.repo.local=target/maven-repository' -o test '-Dtest=BookReaderControllerTests,BookReaderServiceTests,DownloadControllerTests,DownloadPipelineTests,DownloadServiceTests,BookSearchDownloadApplicationTests' '-DargLine=-Dpdfbox.fontcache=target -Djava.io.tmpdir=C:/workspace/backend/Book_Search/Book-Search-Download/target/test-tmp'
```

## Combined book metadata generation

The existing metadata request generates the author, `bookDetails.bookName`, `bookDetails.bookType`, `bookDetails.bookDetails` (book description), and `topicDetails[].details` (topic descriptions/subtopic details) together in the same JSON response. It uses the same initial five PDF pages, including available introduction/preface text after a contents boundary. The source contents parser still receives only contents text, so prose headings are not turned into topics. Later contents requests generate topic details in their existing topics-only schema.

Types are concise evidence-based labels, for example **Computer Programming, Story, Poetry, Physics, Chemistry, or Math**. These are examples rather than a closed enum; another supported subject or genre can be retained as freeform text. Missing or insufficient evidence yields **Unknown**; the prompt forbids guessing a type from the filename. Entity normalization also defaults missing/blank or overlong types to Unknown.

The value is parsed through `PdfBatchExtraction.BookData`, retained by `ExtractionAccumulator`, persisted on `BookDetails.bookType`, and returned as `bookType` in search results. Existing records remain Unknown until a future upload supplies supported metadata; this change does not infer categories for old books automatically.

There is no separate description service, post-extraction model request, or per-topic enrichment call. The combined prompt asks for a concise grounded book description even when no contents/topics exist. When only a type is supported, it permits a general description of that type without invented characters, events, chapters, editions, or audiences. Topic descriptions use only their names and related supplied text, retaining the existing observed-subtopic and continuation flow. Source text remains untrusted data, never model instructions.

Previously accumulated non-empty book metadata is retained. Missing descriptions or Unknown types in an otherwise valid response do not cause another model request or invalidate successfully extracted metadata. If evidence remains insufficient, descriptions can remain null and the type remains Unknown. Existing calls for additional contents-page batches and retries for malformed extraction JSON keep their original behavior; description-only calls are not made.

Extraction does not invent semantic chapters. When no usable chapter metadata exists, the complete PDF uses the existing reserved technical topic row: `<bookName>_offset`, details `<bookName>_offset detail`, physical range **1 through the total PDF pages**, and null printed-page values. Its file location is stored in the existing **`topic_details.pdf_path`** column and consumed by the existing upload, download, transaction, and file-cleanup flows. Book descriptions and classifications are requested independently of whether semantic chapters were detected. Historical records are not automatically sent for inference by this migration.

## Database migration

The project uses **manual MySQL scripts** under `src/main/resources/db/manual/`, alongside Hibernate's existing `ddl-auto=update`. No new migration framework was introduced.

Run `20261124_book_type.sql` against the configured database before deploying this change:

```powershell
mysql --host=localhost --port=3306 --user=root -p search_book_download --execute="source C:/workspace/backend/Book_Search/Book-Search-Download/src/main/resources/db/manual/20261124_book_type.sql"
```

The idempotent book-type migration adds only this new `book_details` column:

| Table | Column | Definition | Purpose |
| --- | --- | --- | --- |
| book_details | book_type | VARCHAR(120) NOT NULL DEFAULT 'Unknown' | Persist grounded model classification; normalize legacy missing values |

If an earlier iteration created the extra `book_details.source_pdf_path` column, also run the corrective migration **`20261124_remove_book_source_path.sql`**:

```powershell
mysql --host=localhost --port=3306 --user=root -p search_book_download --execute="source C:/workspace/backend/Book_Search/Book-Search-Download/src/main/resources/db/manual/20261124_remove_book_source_path.sql"
```

The corrective script retains populated original-PDF paths on reserved `_offset` rows in `topic_details.pdf_path` before dropping the earlier book-level column. The application uses the existing topic-file storage contract. Downloads continue to enforce storage-root and real-path confinement checks. The migration does not rewrite existing semantic chapter descriptions or PDF contents.

## Checks

Normal development checks:

```powershell
mvn test
```

The managed environment used for implementation requires a writable Maven cache and temporary directory. Dependencies were copied from the existing local cache into ignored `target/maven-repository`; no dependency versions changed. The full-suite command is:

```powershell
mvn '-Dmaven.repo.local=target/maven-repository' -o test '-DargLine=-Dpdfbox.fontcache=target -Djava.io.tmpdir=C:/workspace/backend/Book_Search/Book-Search-Download/target/test-tmp'
```

The combined-request implementation passed the full backend suite: **413 tests, 0 failures, 0 errors, 9 skipped**. Coverage includes combined metadata generation and native request schema, one model call per valid batch, no calls triggered solely by missing descriptions, freeform type parsing, H2 persistence and search serialization, complete/topic PDF downloads, transactions, rollback cleanup, asynchronous upload status, reserved technical offset rows, and existing name limits. The optional local-model test is disabled in the regular suite and can be run separately as shown below.

The local database was verified to have no `book_details.source_pdf_path` column; `book_type` is the only added book column. The earlier uploaded 14-page PDF was verified on disk and reconnected through its reserved `_offset` topic row before the final smoke check. Full-book download returned HTTP 200 and all 14 pages; the original file's SHA-256 stayed unchanged. The corrective SQL also passed isolated MySQL tests for copying a populated path, rerunning safely, and rejecting a conflicting path without dropping its data.

`BookTypePersistenceTests` includes an optional local-model smoke check (H2 with transaction rollback; it creates no records in the configured MySQL database):

```powershell
mvn test '-Dtest=BookTypePersistenceTests' '-Dbook.ai.live=true'
```

It defaults to `http://localhost:11434` and `qwen2.5-coder:7b`; override test properties `book.ai.url` / `book.ai.model` if necessary. Regular deterministic tests use controlled model responses. The optional smoke check verifies the actual local model produces an evidence-based Programming classification and a nonblank book description, with both persisted in H2 and returned by search alongside the one reserved technical offset row. **This live test also passed separately with the combined-request implementation (1 test, no failures or errors).** No semantic chapters are invented. The test transaction rolls back without adding records to the real MySQL catalog. For CPU inference it uses a 150-second HTTP timeout; production transport settings remain unchanged.

The Angular UI displays Book Type immediately after Book Name, falls back to Unknown for legacy missing JSON values, and retains the existing book SVG. See the sibling UI README for startup and UI validation. No bookTheme, type-based SVG selection, generated SVG, or icon-storage field was added.

## Changed implementation files

- Metadata contract and accumulation: `dto/PdfBatchExtraction.java`, `dto/PdfTopicBatchExtraction.java`, `extraction/ExtractionAccumulator.java`, `extraction/BookMetadataExtractionService.java`, `prompts/book-extraction-system.st`.
- Combined generation uses only the existing `extraction/BookPageAnalyzer.java`; the separate description-generation service and its obsolete tests were removed.
- Persistence/API: `entity/BookDetails.java`, `repository/BookDetailsRepository.java`, `dto/BookSearchResponse.java`, `search/service/impl/SearchServiceImpl.java`, and the two migration scripts above.
- Existing reserved offset-topic storage: `upload/service/WholeBookFallbackService.java`, `upload/topic/TopicPdfUploadService.java`, `download/pdf/TopicPdfSource.java`, and `download/service/impl/DownloadServiceImpl.java`. Full-book fallback keeps its original PDF path in `topic_details.pdf_path` rather than adding another book field or semantic chapter.
- Tests: extraction/schema/accumulator/fallback tests, `repository/BookTypePersistenceTests.java`, search controller/service tests, technical-offset/download tests, and upload/rollback regressions.

The sibling frontend changes are its `Book` model, book-table template/styles/tests, shared/Home/modal surface styles, and affected typed test fixtures. Reference-image comparison and actual browser/mobile visual QA were not available; CSS/DOM checks verify shared opaque white backgrounds, no blur, the second-column order, and unchanged icon/image hashes.
