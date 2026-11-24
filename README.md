# Bookhaven · Book Search Download UI

Standalone Angular UI connected to the sibling `Book-Search-Download` Spring backend. Home opens directly; unknown routes redirect to Home. No authentication, sidebar, mock mode, or silent mock fallback.

## Run

From `Book-Search-Download-UI`:

```powershell
npm ci --cache .npm-cache
npm start
```

Open **http://localhost:4200/**. Run the existing backend separately on **http://localhost:9090**, with its database and processing dependencies. The development proxy forwards `/api/**`, including uploads and binary downloads.

Verified installed versions on 24 November 2026: Node **24.16.0**, npm **11.13.0**, Angular/CLI **22.3.0**, TypeScript **6.0.3**, Bootstrap **5.3.9**. Both Angular npm registry lookups returned 22.3.0. The starting lockfile already resolved that version, so no framework migration was needed. Node satisfies Angular 22's `^24.15.0` requirement; see [official compatibility](https://angular.dev/reference/versions) and [release policy](https://angular.dev/reference/releases).

## Configuration / deployment

- `src/app/core/configuration/api.config.ts`: API prefix `/api`, 100 MiB upload validation limit, 2-second status interval, matching backend defaults.
- `proxy.conf.json`: development target `http://localhost:9090`; restart the dev server after changing it.
- `src/styles.css`: `--library-background-image` selects the included local 1280×960 CC0 photo. [Asset attribution](public/assets/README.md). No runtime hotlink or blur. Reduced-transparency/contrast preferences use opaque panels and a solid background.
- `src/bootstrap.scss`: selective Bootstrap layout, forms, tables, buttons, feedback, and utilities. No Bootstrap JavaScript or competing modal controllers.

Build with `npm run build`. Deploy **`dist/Book-Search-Download-UI/browser/`**, with history fallback to `index.html` and `/api/**` reverse-proxied to the backend. The development proxy is not included in the production bundle.

For a separately hosted API, set `baseUrl` to its complete API prefix, for example `https://books.example.com/api`. The existing backend declares no CORS policy. Allow the UI origin, GET/POST and necessary request headers, and expose **`Content-Disposition`** to preserve server filenames in downloads. Same-origin deployment needs no CORS changes. Backend files were left unchanged.

## Actual API integration

Contracts were read from backend controllers, DTOs, entities, and tests.

| Capability             | Request                                                                        |
| ---------------------- | ------------------------------------------------------------------------------ |
| Initial search / Reset | `GET /api/search/books`, no criteria or body                                   |
| Search / pagination    | Same GET with populated filters and necessary `page`/`size`                    |
| Upload                 | `POST /api/upload-file`, multipart field **`file`**; browser sets Content-Type |
| Processing status      | `GET /api/upload-status/{book_upload_id}`                                      |
| Complete book          | `GET /api/download/book-download?bookId={id}`                                  |
| Selected topic         | `GET /api/download/book-topic-download?bookId={id}&topicIds={selectedId}`      |

Six search controls match `query`, `bookName`, `authorName`, `topicName`, `bookDetailsContains`, and `topicDetailsContains`. Names use case-insensitive complete matches. Description filters use literal substrings, including literal `%` and `_`. Broad query combines complete names and description substrings with OR; explicit filters combine with AND. Both topic filters must match the same topic. The form explains these rules; no enum options are invented.

Pagination consumes server `books`, `page`, `size`, `totalElements`, `totalPages`. Defaults: page 0, size 20; permitted size 1–100. Every book includes its **complete topic list**, reused by the modal without an extra endpoint. Search uses `switchMap` to prevent stale responses overwriting newer results.

Upload needs no metadata, so selecting a valid file starts it directly without a separate dialog. Picker cancellation sends nothing. Empty, non-PDF and oversize selections are rejected locally; backend validation remains authoritative. The 202 receipt is `{book_upload_id,status,message}`. Polling follows exact states **`inprogress`**, **`Success`**, **`failed`**, avoids overlapping checks, and refreshes the current filters/page on success. Interrupted polling offers **Resume status check** using the same receipt without re-uploading. XHR supplies transfer progress; server processing is indeterminate. Receipts are retained in the current app session, not across a full reload.

Home and modal share the full-book download service. It prevents duplicate active downloads while allowing unrelated downloads, reads binary Blobs and MIME type, honors safe `filename*`/`filename`, revokes temporary object URLs, and decodes JSON errors returned as Blobs. Binary download activity is indeterminate. One-topic PDFs contain only the selected topic content plus the backend-generated cover/contents pages.

The native HTML dialog provides modal semantics, focus containment and Escape behavior. Visible header/footer close controls restore focus to the View Book trigger. Complete topics are already available; dynamic module loading, empty topics, and download failures have visible feedback. Closing preserves Home state. Search failures, topic-module failures, and upload status have independent feedback.

## Structure

```text
public/assets/                         local image and license
src/app/
  core/configuration/api.config.ts
  layout/
    header/                           PDF picker
    footer/
    application-layout/
  features/books/
    books.routes.ts
    pages/home/                       orchestration and dynamic modal import
    components/
      book-search/                    typed reactive form
      book-table/                     exactly five Home columns
      book-topic-description/         exactly three topic columns
    models/book.models.ts
    data-access/                      HTTP and shared download services
    services/book-upload.service.ts   transfer and processing lifecycle
  shared/
    ui/icon.component.ts
    utils/                            HTTP errors and safe filenames
src/bootstrap.scss
src/styles.css
```

## Production lazy-loading verification

Production build output and `browser-stats.json` show separate dynamic imports:

| Load time                                        | Chunk                                                  | Raw size, rounded |
| ------------------------------------------------ | ------------------------------------------------------ | ----------------: |
| Initial                                          | Angular/router/HTTP bootstrap JavaScript               |          279.5 kB |
| Initial                                          | Bootstrap subset and global CSS                        |          150.6 kB |
| Root route                                       | Books routes via `loadChildren`                        |             175 B |
| Root route                                       | Home via `loadComponent`, including its shell/services |            ~28 kB |
| After first Home render, `@defer (on immediate)` | Search and reactive forms                              |           43.8 kB |
| After first Home render, `@defer (on immediate)` | Books table                                            |            5.7 kB |
| Only after View Book click, dynamic `import()`   | Topics modal                                           |            7.2 kB |

Initial total: **~430.1 kB raw / 91.5 kB estimated transfer**, below the original 500 kB warning budget. Header/footer/layout/icons/services load **with their lazy Home parent**; they are not independently deferred. Search/table are referenced only inside defer boundaries. Home starts its one initial search immediately, independently of deferred rendering. No modal prefetch. Development HMR may consolidate deferred chunks; production output is the verified loading source.

## Checks and remaining verification

```powershell
npm run build -- --stats-json
npm test -- --watch=false
```

Production compilation passes with strict TypeScript/templates, no warnings or budget failures. **33 tests** cover exact API serialization, one initial unfiltered search, pagination, filters/Reset, stale-request cancellation, retry, upload-refresh state, picker cancellation/reselection, validation/progress/status success/failure/recovery, duplicates/concurrency, selected book/topic dispatch, filenames, Blob errors, URL cleanup, table states, dynamic modal selection/close/focus restoration, and unknown routes.

Direct checks against the running backend passed: three real books on default search; exact-name filtering, pagination, and no results. Both download endpoints returned HTTP 200, `application/pdf`, attachment filenames and `%PDF` signatures. The dev server and `/api` proxy returned HTTP 200 and the same real catalog.

No contracts remain missing. **Live upload processing was not exercised** against the backend; controlled HTTP tests cover that lifecycle without creating test records. **Browser visual/mobile/keyboard QA remains unperformed**: the browser connector was unavailable and computer-use access to installed Edge was denied. DOM tests emulate native dialog open/close; real Tab trapping, Escape and responsive rendering still require a browser smoke check.
