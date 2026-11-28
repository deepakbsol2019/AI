# Bookhaven · Book Search Download UI

Standalone Angular UI connected to the sibling `Book-Search-Download` Spring backend. Unauthenticated visitors go to Login; authenticated visitors reach the existing Home. Production has no mock mode or silent mock fallback.

The current [persistent AI Help and viewer integration guide](../Book-Search-Download/PERSISTENT_CHATS_AND_VIEWERS.md) covers saved private chats, selected-chat PDF downloads, role-scoped confirmed file deletion, responsive layout and page viewers, plus migrations, tests and browser verification limits.

The latest [CSV / Excel upload, preview and download guide](../Book-Search-Download/SPREADSHEETS.md) covers the confirmed initial PDF tab, gray/blue document-type tabs, filename search, spreadsheet file/sheet previews and binary downloads, backend migration and service-only JSON API.

The current [viewer, profile and role update](../Book-Search-Download/VIEWER_PROFILE_ROLE_UPDATES.md) covers custom reading colors, stable fullscreen facing pages, 50–125% zoom, notification-only Home uploads, tenant-specific role selection and the header profile icon, including final verification and browser limits.

Latest upload/reading/Home behavior is in the [upload history and Full Page reader guide](../Book-Search-Download/UPLOAD_HISTORY_READER.md): Upload Document, persistent private My Uploads, viewer-local dates, full-page covers/spreads/sound and bottom-aligned overflow/pagination. It supersedes conflicting older reader and layout descriptions below.

The latest [profile, permission and role layout guide](../Book-Search-Download/PROFILE_ROLE_LAYOUT.md) covers cleanup/results, Copy Role, initially minimized profile boxes, own-information/permissions, tenant-aware Add User and Home half/full-width behavior. It supersedes conflicting descriptions below and records the actual backend/frontend checks.

The current [management pages and sidebar increment](../Book-Search-Download/MANAGEMENT_PAGES.md) supersedes the earlier no-sidebar/header-profile/details-popup descriptions: all routes share a stationary header/footer shell, authenticated users have a collapsible left profile/navigation/logout sidebar, and management/details/create/delete confirmation use full pages. The user table has the eight requested columns; creation requires its own server capability and trusted current tenant. Production has no mock mode; the ignored browser QA fixture is separate from the application.

The guarded `/user/users` page adds scoped User Management with searchable pagination, an editable details popup, separate activation/role assignment and explicit soft/hard deletion confirmations with durable cleanup status. All authenticated users get the top-right own-profile panel with profile and assigned roles only. See the [User Management API, migration, storage and verification guide](../Book-Search-Download/USER_MANAGEMENT.md).

The guarded `/user/roles` page implements organization-scoped Role Management, current grantable permissions and protected ADMIN/SUPER_USER identities. New registration creates ordinary OWNER rather than ADMIN; files/search remain tenant-bound even for super users. See [role/storage migration and API guide](../Book-Search-Download/ROLE_MANAGEMENT_STORAGE.md); it supersedes older role defaults described below.

Book listings show **Book Type immediately after Book Name**, using the backend's persisted `bookType`. Missing legacy values display **Unknown**. **View Book** now reads the complete PDF; the separate **View Topic** action opens the existing topics popup. The application background uses the supplied `Library knowledge streams into the laptop.png` image with responsive centered cover positioning. Existing downloads and the book SVG remain intact.

## Authentication and registration

The lazy `features/user/UserComponent` contains Login, Registration, forgot/setup/reset password, SSO enrollment and provider callback/error routes. Login uses **Email/Username** and Password; configured MFA challenges reveal an authenticator/recovery-code field. The header button is **Registration**. Google and SSO buttons remain visible, natively disabled and explained while their server feature flags are off; the server also enforces those flags.

Registration never asks for a password. **Individual** hides Login Type and sends PASSWORD; the backend creates the individual tenant and its tenant-scoped administrator membership. **Organization** requires a unique organization code, organization name, first administrator name/email and Password or SSO Login Type. Password registration stays pending until the emailed Set Password link verifies the email and sets a password. SSO registration stays pending for trusted provider configuration and verified enrollment; no password email or fallback password is created. Setup passwords keep their existing policy. Reset passwords require exactly matching values of 5–25 UTF-16 code units with no composition rules. Successful reset clears local authentication and returns to login with a confirmation. See [password recovery configuration and API contract](../Book-Search-Download/PASSWORD_RESET.md). Accounts and organization codes are still validated authoritatively by the backend.

| Route | Purpose |
| --- | --- |
| `/user/login`, `/user/register` | Local login and new-tenant registration |
| `/user/password/forgot` | Non-enumerating reset request |
| `/user/password/setup#token=…`, `/user/password/reset#token=…` | Email credential verification; fragment is read into memory and immediately removed with `history.replaceState` |
| `/user/enroll#token=…&tenantCode=…` | Verified first-administrator SSO enrollment; fragment is immediately removed |
| `/user/callback` | Backend OIDC callback has set the refresh cookie; obtain access through one coordinated refresh, then Home |
| `/user/error` (`/user/provider-error` alias) | Safe provider failure view |

Access tokens exist only in memory. Refresh secrets are never returned to Angular or written to local/session storage: the backend uses its HttpOnly cookie. Email setup/reset/enrollment secrets are distinct one-time credentials, never access/refresh tokens. They are posted in request bodies after their fragments are removed; do not place access/refresh tokens in links.

The functional interceptor attaches bearer credentials only to the configured API origin and path boundary, fetches a fresh CSRF header for unsafe protected requests, and excludes public auth operations from authentication retry. It preserves XHR upload progress. A protected 401 gets at most one refresh/retry; permission 403s do not trigger refresh or logout. Login and the guard obtain access state through one session service; a failed/revoked refresh clears state and routes to Login. Header **Logout** clears local state immediately and asks the server to revoke the session; an offline failure is reported.

The server issues **5-minute access tokens** and a rotating **10-minute refresh deadline**, with nullable earlier absolute expiry supported. Concurrent calls share one in-tab refresh. Origin-scoped **Web Locks** serialize cookie-changing login/refresh/logout across tabs; **BroadcastChannel** distributes only ephemeral access/expiry state and logout notifications. Delayed broadcasts retain their originating clock offset, and logged-out session broadcasts cannot restore old credentials. A different account/session in another tab clears the old Home view. Safe refresh requires a current browser with Web Locks on **HTTPS or localhost**; unsupported browsers fail closed with a visible explanation rather than racing refresh cookies. No credentials are persisted to coordinate tabs.

Refresh runs only for foreground protected navigation/requests or an explicit provider callback, never from a keepalive interval. The first status check following an upload and an explicit **Resume status check** are foreground use; later automatic checks use only an already-valid in-memory access token. Access expiry or a background 401 stops the polling stream and shows an inline Resume message without any automatic refresh/retry. The collection reload triggered by upload completion is also background work, including its page-correction request; user Search/Reset/Retry remain foreground. Thus a long unattended upload cannot renew its session through status timers or a late completion response.

The known refresh/absolute deadline has a clear-only timer, with equality expired and checks on visibility, focus, `pageshow` and protected use. Normal API responses do not extend it. Access expiry alone can refresh on the next explicit protected use while the refresh deadline remains valid. Logout, invalidation, deadline expiry or an account change cancels browser upload/poll/download work and clears private filenames, receipts, cached action feedback and notification items/timers before another account can see them. Async continuations cannot repopulate old-account feedback. This cancellation does not claim to undo server-side PDF processing. Successful login creates a fresh Home with Search minimized, Collection visible and size 5; same-session reader/popup Back and normal section controls continue to preserve their current Home state.

All auth APIs use `/api/user/auth`:

| Method/path | Body/result |
| --- | --- |
| `GET /features` | `{googleEnabled,ssoEnabled}` |
| `GET /csrf` | `{token,headerName:'X-XSRF-TOKEN'}` plus CSRF cookie |
| `POST /login` | `{email,password,mfaCode?}` → session response |
| `POST /refresh`, `POST /logout` | `{}`; cookie + CSRF; refresh → session response, logout → 204 |
| `GET /me` | Protected current principal |
| `POST /register` | `{tenantType,loginType,email,displayName,tenantCode?,organizationName?}` → 202 `{message}` |
| `POST /password/forgot` | `{email}` → 202 generic `{message}` |
| `POST /password/setup`, `POST /password/reset` | `{token,password}` → 204 |
| `GET /oidc/google` | Browser navigation to backend-controlled provider initiation |
| `POST /oidc/sso` | `{tenantCode,enrollmentToken?}` → `{authorizationUrl}`; client permits only own-origin auth/provider initiation routes |

Session responses contain `{accessToken,tokenType:'Bearer',accessExpiresAt,refreshExpiresAt,absoluteExpiresAt,serverTime,sessionId,user:{userId,tenantId,membershipId,email,displayName,tenantName}}`; timestamps are server UTC. Public errors use `{code,detail,status}`. No refresh secret is included. Registration/setup/reset mail, signing keys, trusted origins and provider flags/secrets are configured in the **backend**, as documented in its README. Google/SSO default off; enabling a provider requires trusted issuer/client/redirect configuration. A working mail configuration is needed for actual password onboarding; no development password or secret-bearing email log is provided.

## Homepage and topic popup

Home retains this order: header, search, book listing, main footer. A **fresh Home instance starts with Search minimized and Book Collection visible**, filling the available middle region without reserving space for Search. Successful login reaches a fresh Home; data refreshes, section controls and returning from the still-mounted reader/topics popup do not reinitialize section state.

The viewport shell uses `100vh` with `100dvh` support; header and footer stay outside the flexible middle region. In normal viewports the body and middle shell do not scroll: **only the book rows scroll**, with sticky column headers and the collection heading/pagination outside that scroller. Scrolling results does not move Search. Below **360 CSS pixels of viewport height**, the middle shell allows scrolling as a controlled fallback while header/footer remain outside it. Narrow tables scroll horizontally instead of compressing action buttons.

Each section has an accessible minimize button; the header always shows **Search** and **Book Collection** restore buttons. Restore is natively disabled/grey while its section is visible and enabled/blue while minimized. Persistent hidden wrappers remove all section layout space while preserving form instances, draft input, applied criteria, results and pagination. Restoring either section never reorders it or makes a request. Both minimized leaves the background visible.

Visibility is derived from `searchMinimized` and `collectionMinimized`; the single-section CSS hooks are applied only for their matching state:

| Search | Book Collection | Middle-region layout |
| --- | --- | --- |
| Visible | Visible | Existing shared layout; Search stays above Collection and uses bounded overflow only as needed. |
| Minimized | Visible | `.collection-only`: Collection fills the middle region; only its table rows scroll. This is the fresh default. |
| Visible | Minimized | `.search-only`: Search fills the middle region with no shared-layout percentage/max-height cap; controlled overflow remains available when the form genuinely exceeds the viewport. |
| Minimized | Minimized | Both wrappers are hidden with no layout space; the existing background and header restore controls remain available. |

Restoring the missing section removes the single-section sizing hook and returns to the same shared layout. Footer `min-height` is reduced from **65px to 48px**, with modestly reduced vertical padding and responsive gaps; its horizontal width and readable content are preserved. Header sizing is unchanged in this increment.

View Topic uses the topics already present in the current search result. Its header, filter, download/view controls, selected count and close controls remain outside the scrolling topic-table container. Native dialogs share a reference-counted document/Home scroll lock: the original overflow declarations, priorities and scroll positions return after the final dialog closes, even if Angular destroys nested views in a different order.

The popup's local filter updates while typing and when **Filter** is pressed. It trims the query and matches a case-insensitive substring in the topic name **or** description, safely handling null values. Filtering and clearing issue no HTTP requests and never alter the original topic list. This local filter remains unchanged by the separate server-side contains-search update described below.

Selection uses stable topic IDs and includes hidden rows in its count. With zero or one selected, individual View/Download buttons are enabled and selected View/Download buttons are disabled. With two or more, those states reverse. Shared action styles use solid blue/white when enabled and contrasting grey when disabled, including hover states. Native disabled properties prevent activation. Existing in-progress download guards remain independent of whole-book downloads. Explicitly closing the popup or changing books clears its filter/selection; reading and returning with Back preserves them.

Selected downloads retain the existing output: **one combined PDF**, with its cover/contents and chosen topic source PDFs in the existing supplied order. There is no unresolved output-format decision. Reader selections additionally use persisted physical source order, so a front-matter `_offset` row appears before later chapters even if inserted last in the database. Multi-topic reading returns a clear 409 limitation if saved physical boundaries are missing or invalid; it does not guess. Individual stored-topic reading remains available.

## Compact text previews and metadata editing

Book and topic table text uses a compact single-line ellipsis. The shared `TruncatedTextComponent` measures actual overflow (`scrollWidth > clientWidth`); a full-text preview is available only when the value is really clipped, including after input changes, resize and font loading. Hover or focus opens it; click/touch or Enter/Space keeps it open, and Escape or an outside press closes it. Moving into the preview keeps it open. Its white, wrapping surface preserves full paragraphs and has its own bounded scroll area. A native popover places it above table/dialog clipping, with a portal fallback into the active dialog or document body. Values use Angular text interpolation, with no HTML interpretation or preview API request.

Visible pencil controls edit one field at a time. Book name, book type, author name and topic name use an inline input with Save/Cancel; book/topic descriptions use a native dialog with a multiline textarea. Cancel sends nothing. Saving disables duplicate submission, retains the draft on failure, and shows confirmed success/error feedback. A successful PATCH returns the persisted book snapshot. Home merges only the field that request saved, including its managed derived metadata, so responses for independent fields do not overwrite newer values. It then reapplies the current server search/page. The still-mounted topics popup keeps its filter and stable selected IDs when the same book is updated; an edited value can legitimately stop matching the active filter.

The API accepts **exactly one** allowlisted string or null property per request. Book fields are `bookName`, `bookType`, `authorName`, `bookDetails`; topic fields are `topicName`, `topicDetails`. Extra, duplicate, unknown, non-text or multi-field properties are rejected. IDs, page boundaries, PDF/storage paths, upload state, timestamps and relationships cannot be edited through these endpoints. Actual persisted limits are:

| Field | Limit and behavior |
| --- | --- |
| Book name | Required; 255 characters, or **248** when a canonical `{bookName}_offset` row also needs its seven-character suffix. Duplicate canonical book names are rejected. |
| Topic name | Required; 255 characters; `_offset` suffix reserved. |
| Book type | 120 characters; blank/null normalizes to `Unknown`. |
| Author name | 200 characters; can be cleared. Editing a shared author creates a separate author association for this book instead of renaming other books' author. |
| Book/topic descriptions | Existing `LONGTEXT` columns; no invented short UI character limit. Paragraph content is retained; the UI sends null when cleared. |

Managed technical topic rows ending in `_offset`, and the supported legacy whole-book storage row, remain read-only in both UI and API. Book renames update the matching canonical/legacy storage-row metadata while retaining IDs, PDF paths and physical/printed pages; legacy whole-book descriptions follow book-description edits. No PDF is renamed or rewritten. Uploads still in progress reject metadata changes. These changes use existing metadata columns and require **no database migration**.

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

Open **http://localhost:4200/**. Run the existing backend separately on **http://localhost:9090**, with its database, auth migration, signing keys, mail and processing dependencies configured as described in the backend README. The development proxy forwards `/api/**`, `/oauth2/**` and `/login/oauth2/**`, including provider initiation/callbacks, uploads and binary downloads. Restart `ng serve` after proxy changes. Local HTTP cookie settings must be explicitly enabled in backend development configuration; deployed refresh cookies require HTTPS/Secure.

Verified installed versions on 24 November 2026: Node **24.16.0**, npm **11.13.0**, Angular/CLI **22.3.0**, TypeScript **6.0.3**, Bootstrap **5.3.9**. Both Angular npm registry lookups returned 22.3.0. The starting lockfile already resolved that version, so no framework migration was needed. Node satisfies Angular 22's `^24.15.0` requirement; see [official compatibility](https://angular.dev/reference/versions) and [release policy](https://angular.dev/reference/releases).

## Configuration / deployment

- `src/app/core/configuration/api.config.ts`: API prefix `/api`, 100 MiB upload validation limit, 2-second status interval, matching backend defaults.
- `proxy.conf.json`: development target `http://localhost:9090`; restart the dev server after changing it.
- `src/styles.css`: the exact supplied `public/assets/Flowering-Gurukul-by-the-River.png` remains the centered, unstretched `cover` background for Home and the topic-dialog backdrop. No image or backdrop blur is applied. [Asset notes](public/assets/README.md).
- `src/bootstrap.scss`: selective Bootstrap layout, forms, tables, buttons, feedback, and utilities. No Bootstrap JavaScript or competing modal controllers.
- `--panel-background` is shared white **alpha 0.96** (4% transparency), increased after the earlier highly transparent panels proved hard to read. Near-black text has no outlined text shadow, and form fields use solid white backgrounds. The supplied background remains clear around the panels. Reader surfaces are opaque white by default, optionally black in Dark mode. No parent opacity fades text/buttons, and no image/backdrop blur is applied. Reduced-transparency/contrast preferences retain their accessibility overrides.
- `pdfjs-dist` **6.5.438** is loaded only when reading. `angular.json` copies the matching worker, CMaps, standard fonts, WASM, ICC profiles and license to `assets/pdfjs/`. Restart `ng serve` after asset-configuration changes. These are local assets, with no CDN dependency.

Build with `npm run build`. Deploy **`dist/Book-Search-Download-UI/browser/`**, with history fallback to `index.html` and **`/api/**`, `/oauth2/**`, `/login/oauth2/**`** reverse-proxied to the backend before that fallback. Provider callback `/login/oauth2/code/{code}` is a backend route; `/user/callback` is the Angular completion route. The development proxy is not included in the production bundle.

Same-origin reverse proxy deployment is recommended. For a separately hosted API, set `baseUrl` to its complete API prefix and explicitly configure the backend trusted-origin/CORS and cookie policy, credentials, GET/POST/PATCH, `Authorization`/`X-XSRF-TOKEN` headers, and exposed **`Content-Disposition`**. Wildcard credentialed origins are not supported. The browser provider redirect allowlist is intentionally own-origin; a separate API topology needs a deliberately reviewed provider initiation/proxy arrangement. Reader controllers reuse existing PDF assembly, ownership checks and storage confinement; download behavior and search DTO shape are preserved. The earlier contains-search change required no migration; **new authentication does require its backend migration**.

## Actual API integration

Contracts were read from backend controllers, DTOs, entities, and tests.

| Capability             | Request                                                                        |
| ---------------------- | ------------------------------------------------------------------------------ |
| Initial listing        | `GET /api/search/books?size=5`, no user-entered criteria or request body        |
| Reset                  | Same GET with the current selected `size`; filters and page are cleared      |
| Search / pagination    | Same GET with populated filters and necessary `page`/`size`                    |
| Upload                 | `POST /api/upload-file`, multipart field **`file`**; browser sets Content-Type |
| Processing status      | `GET /api/upload-status/{book_upload_id}`                                      |
| Complete book          | `GET /api/download/book-download?bookId={id}`                                  |
| Selected topic         | `GET /api/download/book-topic-download?bookId={id}&topicIds={selectedId}`      |
| Multiple topics        | Same GET with repeated IDs: `?bookId={id}&topicIds={firstId}&topicIds={secondId}` |
| Read complete book     | `GET /api/read/book?bookId={id}` (`Content-Disposition: inline`) |
| Read one/many topics   | `GET /api/read/topics?bookId={id}&topicIds={id}...` (inline; authoritative source order for multiple IDs) |
| Edit one book field    | `PATCH /api/books/{bookId}/metadata`, e.g. `{"bookType":"History"}` |
| Edit one topic field   | `PATCH /api/books/{bookId}/topics/{topicId}/metadata`, e.g. `{"topicDetails":"First paragraph.\n\nSecond paragraph."}` |

Both PATCH endpoints use JSON, require exactly one supported field and return HTTP 200 with the confirmed full `Book` shape used by search. Missing books or topics outside the book return 404; invalid field/body shapes return 400, invalid names/lengths return 422, and duplicate names, managed storage rows or in-progress uploads return 409. Validation errors leave persisted metadata unchanged.

Six search controls match `query`, `bookName`, `authorName`, `topicName`, `bookDetailsContains`, and `topicDetailsContains`. **Book, author and topic names now use case-insensitive substring/contains matching**, just like descriptions: `Java` can match `Effective Java` and `Java Programming`. Matching occurs in the backend query across the complete catalog, before distinct pagination. A book with several matching topics is returned once. Broad `query` combines the name and description matches with **OR**; separately supplied filters combine with **AND**, and both topic filters must match the same topic. Leading/trailing input whitespace is trimmed, empty optional filters are omitted, and `%`, `_` and the escape character are literal text rather than wildcard instructions. No matches produces the normal empty result and zero count.

Pagination consumes server `books`, `page`, `size`, `totalElements`, `totalPages`. The fresh listing starts at **page 0, size 5**, and the backend search default agrees when size is omitted. Home explicitly includes the selected size on its initial request and subsequent searches/reloads. The existing dropdown offers **5, 10, 20, 50 and 100**; the backend still accepts sizes 1–100. Changing size returns to page 0 while preserving applied criteria and authoritative totals. User-selected larger sizes survive section minimize/restore, reader/popup Back, refresh and **Reset**; Reset clears only filters and the page index. Every book includes its **complete topic list**, reused by the modal without an extra endpoint. Search uses `switchMap` to prevent stale responses overwriting newer results; Angular does not fetch the whole catalog to emulate contains matching.

Upload needs no metadata, so selecting a valid file starts it directly without a separate dialog. Picker cancellation sends nothing. Empty, non-PDF and oversize selections are rejected locally; backend validation remains authoritative. The 202 receipt is `{book_upload_id,status,message}`. Polling follows exact states **`inprogress`**, **`Success`**, **`failed`**, avoids overlapping checks, and requests a background refresh of the current filters/page on success. Interrupted or access-expired polling offers **Resume status check** using the same receipt without re-uploading; that explicit action may refresh sign-in before the ten-minute session deadline. The automatic timer never refreshes authentication. XHR supplies transfer progress; server processing is indeterminate. Receipts are retained only within the same authenticated app session and cleared on its auth boundary, not retained across a full reload.

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
      book-table/                     seven fixed columns; independent row scrolling and field edits
      book-topic-description/         local filter, selection, compact previews and field edits
      metadata-field/                 shared inline inputs and description-editor dialog
      book-reader/                    opaque white by default; optional Dark mode; single-page canvas
    models/book.models.ts
    models/reader*.ts                 reader contract and verified source-order helper
    models/metadata-*.ts              editable field limits and field-specific response merging
    data-access/                      HTTP, download and lazy PDF-engine services
    services/book-upload.service.ts   transfer and processing lifecycle
  features/user/
    user.component.ts, user.routes.ts lazy authentication parent and child routes
    pages/                            login, registration, password and provider flows
    data-access/auth-api.service.ts   cookie/CSRF API contracts
    services/                         memory session, expiry and cross-tab coordination
    security/                         book route guard and functional HTTP interceptor
    components/                       header Logout control
  shared/
    ui/                               icons, notifications and measured full-text previews
    services/notification.service.ts  stable IDs and independent 15-second timers
    utils/                            HTTP errors, filenames, reference-counted scroll locking
src/bootstrap.scss
src/styles.css
```

## Previous reading baseline: production lazy-loading verification

The following sizes came from the **previous reader/readability build**, before the compact previews and metadata editors. They are retained as a loading-boundary reference, not current production-size claims. That build's output and `browser-stats.json` showed separate dynamic imports:

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

That previous initial total was **~437.8 kB raw / 94.0 kB estimated transfer**, below the original 500 kB warning budget. Header/footer/layout load with their lazy Home parent; Search/table retain defer boundaries. Home starts one initial search independently of rendering. Reader/PDF.js use dynamic imports. Development HMR can consolidate chunks; use a fresh production build to measure the current output.

## Checks and remaining verification

```powershell
npm run build -- --stats-json
npm test -- --watch=false
```

### Latest authentication verification

Strict app/spec TypeScript checks pass. The final full Angular run passed **172 tests across 34 files**, including **39 auth tests across 8 files** and the existing book suites. The final production build passed with **no warnings**, **448.04 kB raw / 96.42 kB estimated transfer**. Executed commands were `ng test --watch=false`, `ng build`, and `tsc --noEmit -p tsconfig.app.json` / `tsconfig.spec.json` using the installed local executables. New coverage exercises single-flight refresh, usage-only rotation, exact/resume/absolute deadline handling, logout cancellation and offline logout latching, stale HTTP/broadcast session rejection, cross-tab locks, origin-safe bearer attachment, fresh CSRF, one protected retry, 403 distinction, guarded routes, provider-disabled controls, MFA challenges/account changes, registration branching and one-time credential fragments. Actual-route HTTP tests verify an invalid cookie reaches Login without requesting the catalog, and successful login reaches fresh Home with size five/bearer before Logout returns to Login without a refresh loop. Background-upload tests verify no automatic refresh from five through ten minutes, expiry redirect, one foreground Resume refresh at six minutes, and no renewal through a late completion reload. Auth-boundary tests verify cancellation of prior-account upload/download work, receipt/filename/status clearing and suppression of late saves, errors and notifications. A failed server logout blocks automatic sign-in in the current tab until manual login; a new tab/reload can still retain an unrevoked HttpOnly cookie if server logout could not be confirmed, which the UI explicitly reports.

The final full backend verification also passed **565 tests run: 556 passed, 9 existing optional checks skipped**, and executable-JAR packaging. The migration was applied after isolated MySQL/idempotence/constraint checks; the historical 5 books, 39 topics and 9 receipts remain preserved and unassigned. After restarting the final JAR, **17 live HTTP checks** passed through the UI proxy without creating a user/session. See the sibling backend [AUTH_SETUP.md](../Book-Search-Download/AUTH_SETUP.md) for configuration and verification details.

Actual browser login/provider/cookie/native-focus and mobile checks have **not** been performed: the browser access host was unavailable. Provider feature flags remain off by default; real email delivery/provider configuration and live password onboarding require backend setup. Unit tests use HTTP, browser-lock/channel, dialog and clock doubles and are not a substitute for those deployment checks.

### Historical homepage layout, page-size and contains-search changes

The latest Angular suite passed **127 tests across 26 files**. The production build passed strict TypeScript/template checks with **no warnings or budget failures**: initial output **438.10 kB raw / 94.07 kB estimated transfer**. Frontend checks cover fresh Search-minimized/Collection-visible initialization, all four visibility states and restore-button semantics, retained form/result/page state without section-toggle requests, the consistent initial size-five request, larger sizes including Reset preservation, partial-name request values, literal wildcard text and the updated matching guidance. Existing editing, preview, popup and reader regressions remain in the same passing suite. Latest backend search test totals are recorded separately in the sibling backend documentation.

The latest backend run completed **69 tests: 68 passed and one optional live-AI test skipped**, with no failures or errors. Eleven read-only live API checks through the Angular proxy also passed after restarting the owned backend with no active uploads: default size 5, book/author/topic/general contains, distinct complete-topic results, literal percent/underscore, blank/no-match input, page ordering/counts and explicit larger size. No catalog data was changed. Backend query tests use seven matching books to verify a second size-five page beyond the live catalog's five books. Full backend tests and live AI/upload processing were not rerun for this increment.

**Browser layout/mobile checks for that pre-authentication increment were not performed.** Automatic approval review rejected browser-state access and required browser reinitialization. Earlier screenshots and rendering checks below are historical evidence for their stated changes, not verification of the new single-section sizing or fresh default. At that time no login flow existed; the new auth integration is documented and tested separately above.

### Historical compact-table and metadata-editing verification

For the preceding compact-table/metadata increment, the Angular suite passed **124 tests across 26 files** and the production build had no warnings or budget failures (**438.10 kB raw / 94.02 kB estimated transfer**). Its targeted backend suite passed **115 tests**, covering metadata validation/persistence alongside the then-current search, reading and download regressions. That coverage included actual-overflow previews, hover/focus/click/Escape behavior, safe full paragraphs, resize/placement/teardown, one-field PATCH bodies, real limits, inline/dialog Save/Cancel and failure feedback, managed technical rows, reordered independent-field response merging, and retained search/pagination/popup state. Persistence tests refetched real H2 records, verified rollback on invalid changes, protected shared authors and verified concurrent field saves and PDF readability after renaming. These counts are historical rather than the latest contains-search backend totals.

A live no-op metadata PATCH through the Angular proxy returned HTTP 200; a subsequent search refetched the same stored type and unchanged audit timestamp. A 121-character type was rejected with HTTP 422 and the stored value remained unchanged. These smoke requests intentionally kept the real catalog metadata unchanged; changed-value persistence is covered by the isolated H2 tests.

A desktop Edge screenshot confirmed compact ellipsis rows, visible per-field pencils/actions, constrained Search and Book Collection areas, accessible pagination and the preserved white panels/background. Browser input did not reliably activate the intended control, so interactive scrolling, actual hover/keyboard/touch preview placement, native editor focus and mobile layouts remain **unverified**. DOM tests use geometry/dialog mocks and do not establish browser layout. Existing supplied background/SVG hashes are unchanged; reader/download source and PDF content are unchanged and covered by regression tests. No migration is introduced.

### Previous reader/readability verification

The earlier reader/readability production compilation passed strict TypeScript/templates without warnings or budget failures, and its **95 Angular tests** passed across 21 files. Coverage included independent minimize/restore in both sequences, native disabled controls, preserved draft forms/results/criteria/pagination without requests, unchanged main search, local filter and hidden selections, View/Download transitions **0 → 1 → 2 → 1 → 0**, all reader scopes and Back-state preservation, source ordering, one-page rendering/navigation/cancellation/error/empty states, no automatic attachment saving on View, nested/out-of-order scroll-lock teardown, and independent 15000 ms notification lifetimes/manual close/teardown. Readability regressions verified original light rendering by default, no `pageColors` engine argument in any theme, and a local dark-mode switch without network requests, PDF redraw, downloads or page/zoom/scroll resets. DOM tests emulated native dialogs and the PDF engine.

That earlier targeted backend suite passed **68 tests**: reader controller/service, existing download controller/service/PDF pipeline and application startup. It verified inline disposition, exact PDF bytes, source-order sorting including later-ID front matter, invalid/missing boundaries, ownership/duplicate validation and unchanged download behavior. Live inline requests through the Angular proxy returned HTTP 200 and `application/pdf` for all three reading scopes. PDFBox verified the complete book at **372 pages**, the smaller whole book at **14 pages**, and the selected result as exactly **34 requested source pages plus two existing front-matter pages**. A reversed selected-read request (`3,1`) was delivered in physical source order (`1,3`) and matched the original topic PDFs page for page. The frontend main search method was compared with its original implementation and was unchanged. The exact supplied image and existing SVG hashes were unchanged. The local PDF worker was served with HTTP 200. No database migration was required.

Live regression requests to the original complete-book, single-topic and selected-topic download endpoints also returned HTTP 200, `application/pdf` and attachment disposition. The selected download still preserves its supplied order (`3,1`), verified page for page, while inline reading normalizes that same request to physical order. Existing download behavior was not changed.

For the readability fix, a desktop Edge screenshot confirmed the Search panel's clearer, near-white background and readable text with the unchanged image visible around it. Automated browser activation did not change the observed UI, so a complete interactive reader/mobile/keyboard/layout check remains unperformed. Separately, an actual PDF.js/Node canvas render of page 3 of the 20-page inline topic PDF produced **12971 dark text pixels** on white, and the same display filter produced **12971 white text pixels** on black. Both output images were visually inspected for readable full text (`tmp/reader-light-regression.png`, `tmp/reader-dark-regression.png`); this verifies the rendering/filter path outside the browser, not browser geometry or every scanned/color document. **Live upload processing was not exercised**; controlled status tests verify that success is announced only after a confirmed terminal API status.

## Latest files changed: authentication integration

- **User feature:** new `src/app/features/user/` parent/routes, models, API adapter, memory session/cross-tab coordinator, guards/interceptor, login/registration/password/provider pages, header session control and focused tests.
- **Application wiring:** `app.routes.ts`, `app.config.ts`; lazy public auth routes, guarded books and functional interceptor with existing XHR preserved.
- **Header integration:** header TS/HTML plus header/application-layout specs and authenticated `home-render.spec.ts` route fixture. Existing book business components and requests are unchanged.
- **Development/deployment:** `proxy.conf.json` adds provider initiation/callback forwarding; this README records API contracts, session behavior and remaining configuration. Backend migration/security/configuration files are documented in the sibling README.

## Historical files changed: homepage defaults, section sizing and search guidance

- **Home initialization, visibility and pagination:** `src/app/features/books/pages/home/home.component.ts`, `.html`, `.css`; Home orchestration, rendered, visibility and reader-return test files in that directory. Fresh state and size-five requests are explicit; Reset preserves the selected size.
- **Search presentation:** `src/app/features/books/components/book-search/book-search.component.html`, `.css`, `.spec.ts`; contains guidance/placeholders, expanded Search-only layout and focused form regressions. The existing request field names remain unchanged.
- **Footer:** `src/app/layout/footer/footer.component.ts`; lower minimum height and vertical padding. Header dimensions are unchanged.
- **HTTP contract regression:** `src/app/features/books/data-access/books-api.service.spec.ts`; the generic HTTP adapter remains unchanged while omitted-size response expectations agree with the backend listing default.
- **Documentation:** this README. Backend query/default changes and their tests are described in the sibling backend README; table editing, full-text previews, popup filtering, reader/download logic and transparency are outside this increment.

## Historical files changed for compact tables and metadata editing

Paths below are relative to `src/app/features/books/` unless otherwise stated.

- **Shared previews:** new `src/app/shared/ui/truncated-text.component.ts`, `.html`, `.css` and `.spec.ts`.
- **Field editors and requests:** new `components/metadata-field/metadata-field.component.*`, `data-access/metadata-api.service.ts` and its tests; `models/metadata-fields.ts`, `metadata-save.ts` and merge tests.
- **Table and popup integration:** `components/book-table/book-table.component.*`, `components/book-topic-description/book-topic-description.component.*` and their tests; compact fixed columns, stable action space and metadata-save outputs.
- **Home layout and state:** `pages/home/home.component.ts`, `.html`, `.css`, `home-render.spec.ts`, `home.component.spec.ts`, new `metadata-flow.integration.spec.ts`; `src/app/layout/application-layout/application-layout.component.ts` keeps the shell fixed with the under-360px fallback.
- **Backend metadata API:** sibling `Book-Search-Download/src/main/java/com/book/metadata/controller/BookMetadataController.java`, `metadata/dto/MetadataPatchRequest.java`, `metadata/service/BookMetadataService.java`; existing metadata entities/repositories support the real limits and locked updates. Tests include `src/test/java/com/book/search/BookMetadataControllerTests.java` and `repository/BookMetadataPersistenceTests.java`. No migration files are added.
- **Documentation:** this README and the sibling backend README.

## Previous files changed for sections, reading and notifications

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
