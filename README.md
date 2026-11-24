# Bookhaven · Book Search Download UI

Standalone Angular UI connected to the sibling `Book-Search-Download` Spring backend. Home opens directly; unknown routes redirect to Home. No authentication, sidebar, mock mode, or silent mock fallback.

Book listings show **Book Type immediately after Book Name**, using the backend's persisted `bookType`. Missing, null, empty, or whitespace-only legacy values display **Unknown**. Remaining author, description, full-download, and View Book actions are preserved. The existing SVG/icon and background image are unchanged by the book-type feature; there are no type-based icons or theme fields.

## Homepage and topic popup

Home retains this order: header, search, book listing, main footer. The welcome hero and the narrow pre-footer tagline are removed. The viewport shell uses `100vh` with `100dvh` support; header and main footer are outside its single flexible, scrollable middle region. The body does not scroll on Home. The book table retains horizontal scrolling on narrow screens and leaves vertical scrolling to the middle region. Opaque white panels and the supplied Gurukul background remain unchanged.

View Book uses the topics already present in the current search result. Its header, filter, download controls, selected count and close controls remain outside the scrolling topic-table container. Opening the native dialog locks document/body and the Home scroll region; close, native close/Escape, and component destruction restore their previous overflow declarations, priorities and scroll positions.

The popup's local filter updates while typing and when **Filter** is pressed. It trims the query and matches a case-insensitive substring in the topic name **or** description, safely handling null values. Filtering and clearing issue no HTTP requests and never alter the original topic list. The main search API and its exact-name matching behavior are unchanged.

Selection uses stable topic IDs and survives filtering, including hidden rows. The count always includes hidden selections. With zero or one selected, individual topic downloads remain available; with two or more, they are disabled and **Download Selected Topic** becomes available. An active topic download prevents duplicate bulk submissions; a bulk download locks that book's topic download controls until success or failure. Whole-book downloads remain independent. Closing or changing books clears filter and selection state.

Selected topics use the existing backend output: **one combined PDF**, with its existing cover/contents and only the chosen topic source PDFs, ordered as listed in the book. There is no unresolved output-format decision or new backend endpoint.

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
- `src/styles.css`: `--library-background-image` in the shared `:root, dialog::backdrop` rule references the exact supplied `public/assets/Flowering-Gurukul-by-the-River.png`. The page and native pop-up backdrop share this local asset, with centered `cover` positioning and no stretching, warm tint, or image filter. A fixed viewport background layer keeps the central scene centered on long mobile pages. Neutral white overlays support readability; content and pop-up panels remain `#FFFFFF`, with the image outside the pop-up content. Reduced-transparency/contrast preferences use solid backgrounds. [Asset notes](public/assets/README.md).
- `src/bootstrap.scss`: selective Bootstrap layout, forms, tables, buttons, feedback, and utilities. No Bootstrap JavaScript or competing modal controllers.
- `--panel-background` in `src/styles.css` is opaque white (`#FFFFFF`) and shared by base Home/header/footer/modal/table surfaces. It changes background color only; text/buttons/icons do not inherit container opacity. No image or backdrop blur is applied. An attached visual reference was not present in the available files, so the explicit pure-white requirement is used as the target.

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
| Multiple topics        | Same GET with repeated IDs: `?bookId={id}&topicIds={firstId}&topicIds={secondId}` |

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
      book-table/                     six Home columns; Book Type second
      book-topic-description/         selection, name, description, download columns
    models/book.models.ts
    data-access/                      HTTP and shared download services
    services/book-upload.service.ts   transfer and processing lifecycle
  shared/
    ui/icon.component.ts
    utils/                            HTTP errors, safe filenames, scoped scroll locking
src/bootstrap.scss
src/styles.css
```

## Production lazy-loading verification

Production build output and `browser-stats.json` show separate dynamic imports:

| Load time                                        | Chunk                                                  | Raw size, rounded |
| ------------------------------------------------ | ------------------------------------------------------ | ----------------: |
| Initial                                          | Angular/router/HTTP bootstrap JavaScript               |          279.5 kB |
| Initial                                          | Bootstrap subset and global CSS                        |          151.3 kB |
| Root route                                       | Books routes via `loadChildren`                        |             175 B |
| Root route                                       | Home via `loadComponent`, including its shell/services |           27.2 kB |
| After first Home render, `@defer (on immediate)` | Search and reactive forms                              |           43.8 kB |
| After first Home render, `@defer (on immediate)` | Books table                                            |            6.1 kB |
| Only after View Book click, dynamic `import()`   | Topics modal                                           |           14.9 kB |

Initial total: **~430.7 kB raw / 91.6 kB estimated transfer**, below the original 500 kB warning budget. Header/footer/layout/icons/services load **with their lazy Home parent**; they are not independently deferred. Search/table are referenced only inside defer boundaries. Home starts its one initial search immediately, independently of deferred rendering. No modal prefetch. Development HMR may consolidate deferred chunks; production output is the verified loading source.

## Checks and remaining verification

```powershell
npm run build -- --stats-json
npm test -- --watch=false
```

Production compilation passes with strict TypeScript/templates, no warnings or budget failures. **50 Angular tests pass**. They cover section order/removal and shell projection; unchanged main search serialization; local filter input/apply/clear with zero HTTP requests; name-or-description and null/no-match cases; selection transitions **0 → 1 → 2 → 1 → 0**; hidden selections and original-order dispatch; selected-only HTTP IDs; duplicate suppression and success/error recovery; close/changed-book reset; scroll-lock restoration; and the retained upload, individual/whole-book download, filename, Blob-error, dynamic-modal/focus, Book Type and pagination checks. DOM tests emulate native dialog open/close and do not prove browser scroll geometry.

The existing backend download regression suite also passed **34 tests** (`DownloadControllerTests`, `DownloadPipelineTests`, `DownloadServiceTests`). Live requests through the Angular `/api` proxy returned HTTP 200, `application/pdf`, and attachment filenames for selected topics, an individual topic and a whole book. For Think Java topic IDs **1 and 3**, PDFBox verified all **34 selected source pages** against their original topic files, in the requested order, plus exactly two existing front-matter pages; there were no extra source pages. The whole-book check retained all 14 pages. Default search still returns the four existing books, and the frontend main search method was compared directly with its original implementation and is unchanged. No database migration or backend source change was needed for this request.

The computer-use skill was used to inspect the updated desktop homepage and topic popup in Edge. Both retained white panels and the clear Gurukul image; the popup's header/controls and table container fit within the desktop viewport. Further browser interaction was interrupted by active user input and a minimized window. **Actual scroll-motion/overlap/double-scrollbar checks, mobile/short-viewport rendering, and real keyboard Tab/Escape checks were not completed**. The final explicit book-table vertical-overflow rule was added after that inspection and has build/test coverage but no subsequent visual confirmation. **Live upload processing was not exercised**; this request makes no upload-flow changes.

## Files changed for the homepage/topic workflow

- `src/app/layout/application-layout/application-layout.component.ts` and its new `.spec.ts`.
- `src/app/features/books/pages/home/home.component.html`, `home.component.css`, and `home-render.spec.ts`.
- `src/app/features/books/components/book-table/book-table.component.css` (vertical scroll ownership only).
- `src/app/features/books/components/book-topic-description/book-topic-description.component.ts`, `.html`, `.css`, `.spec.ts`, and new `book-topic-download.integration.spec.ts`.
- `src/app/shared/utils/scroll-lock.ts` and new `scroll-lock.spec.ts`.
- `src/app/features/books/data-access/books-api.service.ts`, `book-download.service.ts`, and their `.spec.ts` files.
- `src/styles.css` and this README.
