# Bookhaven · Book Search Download UI

Standalone Angular UI connected to the sibling `Book-Search-Download` Spring backend. Home opens directly; unknown routes redirect to Home. No authentication, sidebar, mock mode, or silent mock fallback.

Book listings show **Book Type immediately after Book Name**, using the backend's persisted `bookType`. Missing legacy values display **Unknown**. **View Book** now reads the complete PDF; the separate **View Topic** action opens the existing topics popup. Existing downloads, the book SVG and the supplied Gurukul background asset remain intact.

## Homepage and topic popup

Home retains this order: header, search, book listing, main footer. The viewport shell uses `100vh` with `100dvh` support; header and footer are outside its single flexible, scrollable middle region. The body does not scroll on Home. Each section has an accessible minimize button; the header always shows **Search** and **Book Collection** restore buttons. Restore is natively disabled/grey while its section is visible and enabled/blue while minimized. Persistent hidden wrappers remove all section layout space while preserving form instances, draft input, applied criteria, results and pagination. Restoring either section never reorders it or makes a request. Both minimized leaves the background visible.

View Topic uses the topics already present in the current search result. Its header, filter, download/view controls, selected count and close controls remain outside the scrolling topic-table container. Native dialogs share a reference-counted document/Home scroll lock: the original overflow declarations, priorities and scroll positions return after the final dialog closes, even if Angular destroys nested views in a different order.

The popup's local filter updates while typing and when **Filter** is pressed. It trims the query and matches a case-insensitive substring in the topic name **or** description, safely handling null values. Filtering and clearing issue no HTTP requests and never alter the original topic list. The main search API and its exact-name matching behavior are unchanged.

Selection uses stable topic IDs and includes hidden rows in its count. With zero or one selected, individual View/Download buttons are enabled and selected View/Download buttons are disabled. With two or more, those states reverse. Shared action styles use solid blue/white when enabled and contrasting grey when disabled, including hover states. Native disabled properties prevent activation. Existing in-progress download guards remain independent of whole-book downloads. Explicitly closing the popup or changing books clears its filter/selection; reading and returning with Back preserves them.

Selected downloads retain the existing output: **one combined PDF**, with its cover/contents and chosen topic source PDFs in the existing supplied order. There is no unresolved output-format decision. Reader selections additionally use persisted physical source order, so a front-matter `_offset` row appears before later chapters even if inserted last in the database. Multi-topic reading returns a clear 409 limitation if saved physical boundaries are missing or invalid; it does not guess. Individual stored-topic reading remains available.

## Reusable PDF reader and notifications

One dynamically loaded reader handles explicit `book`, `topic`, and `selected-topics` modes. Inline endpoints supply actual complete PDFs, preserving source formatting and authoritative topic files. View never calls the attachment-saving service or creates a download link. The reader provides loading/empty/error/retry states, page number/total, Previous/Next, fit width, zoom, Back and a Download action for the current scope. Only one page is rendered at a time; render tasks, PDF documents, workers, canvas buffers, resize handlers and HTTP subscriptions are released on replacement/close. Home and the originating topics popup stay mounted beneath it, preserving state and focus on Back.

The reader opens in **Light mode (original PDF)** with an opaque white background and original PDF colors. It fills the viewport (`100vh`/`100dvh`), with a compact heading/toolbar/footer and a flexible, independently scrolling document region. The dropdown also offers **Dark mode**, which changes only the reader surroundings and applies `invert(1) hue-rotate(180deg)` to its canvas. PDF.js always renders the original page onto white; its previous SVG-based high-contrast `pageColors` path is no longer used. Switching modes does not refetch or redraw the PDF, change the page/zoom/scroll position, or modify saved/downloaded files. A compact help disclosure explains that dark display also recolors scans/photos; Light mode preserves original colors.

Shared non-modal notifications appear below the active fixed header so restore/upload/Back controls remain accessible. Each stable notification ID has its own **15000 ms** lifetime and manual close action; previous timers cannot dismiss newer messages. The active native dialog hosts the outlet while open, without duplicate app-level overlays. Empty stacks leave no overlay. Success/error severity has appropriate live announcements and does not move focus. Confirmed upload completion/failure and download handoff/errors use notifications; upload progress, interrupted-status recovery, input validation and content errors requiring Retry remain inline. Timers and observers are cleaned up on teardown.

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
- `src/styles.css`: the exact supplied `public/assets/Flowering-Gurukul-by-the-River.png` remains the centered, unstretched `cover` background for Home and the topic-dialog backdrop. No image or backdrop blur is applied. [Asset notes](public/assets/README.md).
- `src/bootstrap.scss`: selective Bootstrap layout, forms, tables, buttons, feedback, and utilities. No Bootstrap JavaScript or competing modal controllers.
- `--panel-background` is shared white **alpha 0.96** (4% transparency), increased after the earlier highly transparent panels proved hard to read. Near-black text has no outlined text shadow, and form fields use solid white backgrounds. The supplied background remains clear around the panels. Reader surfaces are opaque white by default, optionally black in Dark mode. No parent opacity fades text/buttons, and no image/backdrop blur is applied. Reduced-transparency/contrast preferences retain their accessibility overrides.
- `pdfjs-dist` **6.5.438** is loaded only when reading. `angular.json` copies the matching worker, CMaps, standard fonts, WASM, ICC profiles and license to `assets/pdfjs/`. Restart `ng serve` after asset-configuration changes. These are local assets, with no CDN dependency.

Build with `npm run build`. Deploy **`dist/Book-Search-Download-UI/browser/`**, with history fallback to `index.html` and `/api/**` reverse-proxied to the backend. The development proxy is not included in the production bundle.

For a separately hosted API, set `baseUrl` to its complete API prefix, for example `https://books.example.com/api`. The existing backend declares no CORS policy. Allow the UI origin, GET/POST and necessary request headers, and expose **`Content-Disposition`** to preserve server filenames in downloads. Same-origin deployment needs no CORS changes. New reader controllers reuse the existing PDF assembly, ownership checks and storage confinement; existing download/search endpoints are unchanged. No database migration was required.

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
| Read complete book     | `GET /api/read/book?bookId={id}` (`Content-Disposition: inline`) |
| Read one/many topics   | `GET /api/read/topics?bookId={id}&topicIds={id}...` (inline; authoritative source order for multiple IDs) |

Six search controls match `query`, `bookName`, `authorName`, `topicName`, `bookDetailsContains`, and `topicDetailsContains`. Names use case-insensitive complete matches. Description filters use literal substrings, including literal `%` and `_`. Broad query combines complete names and description substrings with OR; explicit filters combine with AND. Both topic filters must match the same topic. The form explains these rules; no enum options are invented.

Pagination consumes server `books`, `page`, `size`, `totalElements`, `totalPages`. Defaults: page 0, size 20; permitted size 1–100. Every book includes its **complete topic list**, reused by the modal without an extra endpoint. Search uses `switchMap` to prevent stale responses overwriting newer results.

Upload needs no metadata, so selecting a valid file starts it directly without a separate dialog. Picker cancellation sends nothing. Empty, non-PDF and oversize selections are rejected locally; backend validation remains authoritative. The 202 receipt is `{book_upload_id,status,message}`. Polling follows exact states **`inprogress`**, **`Success`**, **`failed`**, avoids overlapping checks, and refreshes the current filters/page on success. Interrupted polling offers **Resume status check** using the same receipt without re-uploading. XHR supplies transfer progress; server processing is indeterminate. Receipts are retained in the current app session, not across a full reload.

Home and modal share the full-book download service. It prevents duplicate active downloads while allowing unrelated downloads, reads binary Blobs and MIME type, honors safe `filename*`/`filename`, revokes temporary object URLs, and decodes JSON errors returned as Blobs. Binary download activity is indeterminate. One-topic PDFs contain only the selected topic content plus the backend-generated cover/contents pages.

Native HTML dialogs provide modal semantics and default focus containment/Escape behavior. Popup close returns focus to View Topic; reader Back returns focus to its originating action without resetting the still-mounted popup or list. Browser keyboard behavior still requires manual verification.

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
      book-table/                     seven columns; Book Type second, View Book and View Topic
      book-topic-description/         local filter, selection, View/Download actions
      book-reader/                    reusable opaque dark, single-page PDF canvas
    models/book.models.ts
    models/reader*.ts                 reader contract and verified source-order helper
    data-access/                      HTTP, download and lazy PDF-engine services
    services/book-upload.service.ts   transfer and processing lifecycle
  shared/
    ui/                               existing icons and shared notification outlet
    services/notification.service.ts  stable IDs and independent 15-second timers
    utils/                            HTTP errors, filenames, reference-counted scroll locking
src/bootstrap.scss
src/styles.css
```

## Production lazy-loading verification

Production build output and `browser-stats.json` show separate dynamic imports:

| Load time                                        | Chunk                                                  | Raw size, rounded |
| ------------------------------------------------ | ------------------------------------------------------ | ----------------: |
| Initial                                          | Angular/router/HTTP/notification bootstrap JavaScript  |          285.7 kB |
| Initial                                          | Bootstrap subset and global CSS                        |          152.1 kB |
| Root route                                       | Books routes via `loadChildren`                        |             175 B |
| Root route                                       | Home via `loadComponent`, including its shell/services |           31.4 kB |
| After first Home render, `@defer (on immediate)` | Search and reactive forms                              |           44.4 kB |
| After first Home render, `@defer (on immediate)` | Books table                                            |            6.6 kB |
| Only after View Topic                            | Topics modal                                           |           14.8 kB |
| Only after a View/reader action                   | Reader component                                       |           19.3 kB |
| Only when loading an actual PDF                   | PDF.js engine (worker separate local asset)            |          449.6 kB |

Initial total: **~437.8 kB raw / 94.0 kB estimated transfer**, below the original 500 kB warning budget. Header/footer/layout load with their lazy Home parent. Search/table retain defer boundaries. Home starts one initial search independently of rendering. Reader/PDF.js are not eagerly included in the initial bundle. Development HMR can consolidate chunks; production output is the verified loading source.

## Checks and remaining verification

```powershell
npm run build -- --stats-json
npm test -- --watch=false
```

Production compilation passes with strict TypeScript/templates and **no warnings or budget failures**. **95 Angular tests pass** across 21 files. Coverage includes independent minimize/restore in both sequences, native disabled controls, preserved draft forms/results/criteria/pagination without requests, unchanged main search, local filter and hidden selections, View/Download transitions **0 → 1 → 2 → 1 → 0**, all reader scopes and Back-state preservation, source ordering, one-page rendering/navigation/cancellation/error/empty states, no automatic attachment saving on View, nested/out-of-order scroll-lock teardown, and independent 15000 ms notification lifetimes/manual close/teardown. Readability regressions verify original light rendering by default, no `pageColors` engine argument in any theme, and a local dark-mode switch without network requests, PDF redraw, downloads or page/zoom/scroll resets. DOM tests emulate native dialogs and the PDF engine.

The targeted backend suite passed **68 tests**: reader controller/service, existing download controller/service/PDF pipeline and application startup. It verifies inline disposition, exact PDF bytes, source-order sorting including later-ID front matter, invalid/missing boundaries, ownership/duplicate validation and unchanged download behavior. Live inline requests through the Angular proxy returned HTTP 200 and `application/pdf` for all three reading scopes. PDFBox verified the complete book at **372 pages**, the smaller whole book at **14 pages**, and the selected result as exactly **34 requested source pages plus two existing front-matter pages**. A reversed selected-read request (`3,1`) was delivered in physical source order (`1,3`) and matched the original topic PDFs page for page. The frontend main search method was compared with its original implementation and is unchanged. The exact supplied image and existing SVG hashes are unchanged. The local PDF worker is served with HTTP 200. No database migration was required.

Live regression requests to the original complete-book, single-topic and selected-topic download endpoints also returned HTTP 200, `application/pdf` and attachment disposition. The selected download still preserves its supplied order (`3,1`), verified page for page, while inline reading normalizes that same request to physical order. Existing download behavior was not changed.

For the readability fix, a desktop Edge screenshot confirmed the Search panel's clearer, near-white background and readable text with the unchanged image visible around it. Automated browser activation did not change the observed UI, so a complete interactive reader/mobile/keyboard/layout check remains unperformed. Separately, an actual PDF.js/Node canvas render of page 3 of the 20-page inline topic PDF produced **12971 dark text pixels** on white, and the same display filter produced **12971 white text pixels** on black. Both output images were visually inspected for readable full text (`tmp/reader-light-regression.png`, `tmp/reader-dark-regression.png`); this verifies the rendering/filter path outside the browser, not browser geometry or every scanned/color document. **Live upload processing was not exercised**; controlled status tests verify that success is announced only after a confirmed terminal API status.

## Files changed for sections, reading and notifications

- `src/app/app.ts` / `app.html`: global notification outlet.
- Header component TS/HTML/CSS/tests, application-layout TS/tests and footer component styles.
- Home TS/HTML/CSS, `home-render.spec.ts`, new `home-visibility.spec.ts` and `reader-flow.integration.spec.ts`.
- Book-search and book-table component TS/HTML/CSS/tests; topic-popup TS/HTML/CSS/tests.
- New `components/book-reader/book-reader.component.ts`, `.html`, `.css` and `.spec.ts`.
- New `models/reader.models.ts`, `reader-topic-order.ts` and ordering tests.
- New `data-access/pdf-reader.service.ts` and tests; existing `books-api.service.ts`, `book-download.service.ts` and tests.
- Upload service and tests: truthful transient notifications and separate inline validation state.
- New `shared/services/notification.service.ts`, `shared/ui/notification-outlet.component.ts` and their tests.
- `shared/utils/scroll-lock.ts` and tests; `src/styles.css`; `package.json`, lockfile and `angular.json`; this README.
- Backend: new `download/controller/BookReaderController.java`, `download/controller/impl/BookReaderControllerImpl.java`, `download/service/BookReaderService.java`, two reader test classes and backend README. Existing download/search services and database schema are unchanged.
