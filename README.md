# Book Search Download backend

Spring Boot 4.2 / Java 25 backend with the existing Ollama, MySQL, PDFBox, upload-status, and local PDF storage flows.

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
