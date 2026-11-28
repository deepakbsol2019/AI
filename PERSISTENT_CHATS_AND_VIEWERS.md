# Persistent chats, deletion permissions and viewers

This is the current contract for the two-prompt change request. It supersedes the earlier acknowledgment-only AI Help and uploader-only deletion descriptions in `AI_HELP_AND_FILES.md`.

## Deployment and migrations

Keep the existing authentication, document and spreadsheet migrations installed. Apply these additive scripts before using the new endpoints:

1. `src/main/resources/db/manual/20261128_file_delete_endpoints.sql` if the preceding file-deletion change has not been deployed.
2. `src/main/resources/db/manual/20261128_file_delete_capabilities.sql` registers scoped management and capability routes.
3. `src/main/resources/db/manual/20261128_persistent_chats.sql` creates `ai_chat_pages`, `ai_chat_messages`, and `ai_chat_preferences`, plus the nine private chat endpoint policies.
4. `src/main/resources/db/manual/20261128_super_user_file_ai_permissions.sql` adds the AI Help permission catalog, maps chat endpoints, and explicitly grants active reserved `SUPER_USER` roles `BOOKS.VIEW`, `BOOKS.DELETE`, `AI_HELP.VIEW` and `AI_HELP.CHAT`. It is repeatable and preserves disabled catalog entries.

These four scripts were applied to the configured local MySQL database on 28 November 2026 after saving a permission-catalog snapshot under `.local/db-backups/super-user-file-ai-20261128-100653/`. Verification found the active reserved `SUPER_USER` grants, all three chat tables and all nine active chat routes. Other deployments must apply the scripts separately. Existing sessions should sign out and sign in again to refresh their permission fingerprint.

The grants do not assign roles to users or expand an ordinary admin's scope. No model provider, AI tool registration or automatic model execution was added. The existing Spring Boot 4.2 / Java 25, JDBC/manual migration, authenticated bearer session and CSRF conventions are retained. The new PDF font is bundled with its OFL license under `src/main/resources/fonts/chat/`.

## File deletion and download

| Trusted current role | Permitted deletion scope |
| --- | --- |
| Active reserved system `SUPER_USER` | Any tenant-assigned upload in the application, regardless of uploader. “Super admin” refers to this existing role, not a new role constant. |
| Active reserved system `ADMIN` | Any upload in its own authenticated tenant, regardless of uploader. It has no cross-tenant deletion access. |
| Other roles with valid existing `BOOKS.DELETE` authorization | Only their own uploaded files in their authenticated tenant. Existing prerequisite permissions still apply. |

Role names/display labels supplied by the client do not grant privileges. A custom role named ADMIN or SUPER_USER without the reserved system identity does not gain the corresponding scope. Fresh session, database role and endpoint checks run for every delete operation, including annotated service methods.

The three existing `DELETE /api/books/{bookId}`, `/api/spreadsheets/{fileId}`, and `/api/uploads/{uploadId}` routes retain `204` success. Deletion is ID-scoped, transactional for database records and retryable for filesystem cleanup. Non-owned/out-of-scope records return `404`, active uploads/shared ambiguous objects return `409`, and incomplete storage/database cleanup returns `503`. Storage references remain available for retry after failure. Tenant cleanup locks are respected. A legacy upload without an assigned tenant cannot be safely mapped to tenant storage and returns `409` pending ownership migration; a missing legacy uploader is tolerated for admins/super users but never grants ordinary users access.

`GET /api/files/delete-capabilities?kind=books|spreadsheets|uploads&ids=1&ids=2` returns an ID-to-boolean map for 1–100 IDs. Missing/unauthorized/active entries are false. The frontend batches these hints, clears them on identity boundaries, disables unavailable delete controls and opens a native confirmation dialog before sending DELETE. Cancel sends no deletion request. Errors retain the item; confirmed success refreshes its table.

The existing **My Uploads** view retains its original private history. Its **Files I can delete** option (`/user/uploads?scope=managed`) uses `GET /api/files/manage?page=0&size=20` and `GET /api/files/manage/{uploadId}`. The management response follows the existing history/status JSON fields with an additional `tenantId`. Super users can discover cross-tenant deletion targets here; admins see their tenant; ordinary permitted users see their own uploads. This is a metadata/deletion view, with no new cross-tenant download route.

Download handlers retain the existing visibility, tenant and download-permission rules. They do not require uploader identity to match the caller and do not call the deletion ownership policy. PDF and spreadsheet regressions explicitly exercise a different uploader within a visible tenant.

## Private chat API

AI Help is available to every authenticated account, including the reserved `SUPER_USER` (superadmin). The new migration also records explicit `AI_HELP.VIEW` and `AI_HELP.CHAT` grants for active reserved super users, while preserving the existing authenticated-user chat policy. The header link and `/ai-help` route do not exclude privileged accounts. Active endpoint registrations and the chat-table migration are still required. Superadmin access is to that user's own private chats; it does not grant access to another user's conversations.

All paths below start with `/api/ai-help`. Every service operation derives owner user and tenant from the authenticated session and checks both, even for SUPER_USER. No chat operation accepts a client owner/tenant or privileged bypass. Errors follow the existing HTTP conventions with a readable problem detail: invalid input `400`, missing/foreign chat `404`, stale revision/duplicate name/empty PDF `409`, unsupported PDF glyph `422`, database failure `503`.

| Method and path | Request | Success response |
| --- | --- | --- |
| `GET /pages` | None | `{pages: ChatPage[], lastSelectedPageId: number|null}`; metadata only, no messages. |
| `POST /pages` | No body | `201 ChatPage`; creates and selects the next conflict-free `Chat N`. |
| `PUT /selection` | JSON `{pageId}` | Selected `ChatPage`, persisted per owner and tenant. |
| `PATCH /pages/{pageId}` | JSON `{name, description?}` | Updated `ChatPage`; ID remains stable. Name is nonblank, at most 150 characters; details at most 4,000. Duplicate owner/tenant names return `409`. |
| `GET /pages/{pageId}/messages?page=0&size=50` | Size 1–200 | `{pageId,revision,messages,number,size,totalElements,totalPages,hasOlder}`. Page zero is the newest window, returned in ascending conversation order. |
| `POST /chat?pageId=...&revision=...` | Raw UTF-8 string, `Content-Type: text/plain`; optional UUID `X-Message-Id` | `{pageId,message,acknowledgment,assistantStatus:"NOT_CONFIGURED",revision}`. Persists the USER message, then prints exactly `System.out.println(messageText)`. |
| `DELETE /pages/{pageId}/messages?revision=...` | No body | `ChatPage` with incremented revision; removes every message, retains the page ID/name/details. |
| `DELETE /pages/{pageId}?revision=...` | No body | `{lastSelectedPageId}` after deleting the page and all child messages, with an accessible remaining selection or null. |
| `GET /pages/{pageId}/pdf` | No body | `200 application/pdf` plus UTF-8 `Content-Disposition` filename from the current edited name. Exports the entire selected conversation, including older message windows. |

`ChatPage` is `{pageId,name,description,createdAt,updatedAt,revision}`. `ChatMessage` is `{messageId,pageId,senderRole,content,createdAt,messageOrder}`. Timestamps are UTC instants. User and actual assistant messages have independent unique IDs and a locked per-page ordering counter. Messages are nonblank and limited to 32,000 UTF-16 units. Retrying the same UUID/text on a page returns the saved message instead of inserting a duplicate; reusing the UUID for different text returns `409`.

There is no connected assistant generation. The acknowledgment is displayed as status, not an invented assistant answer or stored ASSISTANT record. `ChatService.appendAssistantReply(pageId,expectedRevision,reply)` is an internal future integration hook for an actual reply; it enforces current authenticated ownership and the conversation revision. It is not registered with a model or exposed as a client-controlled sender-role endpoint.

## Persistence, loading and consistency

The user/tenant preference stores the last selection and next default number. Closing the full-page `/ai-help` route or logging out clears private browser state and cancels requests, without Clean/Delete. Opening after login lists metadata and fetches only the selected page's newest window. Switching pages updates the preference and fetches only that page. Revision and view/identity guards prevent stale data from appearing after switches or identity changes.

Send clears only the submitted draft version after successful persistence, preserving a newer draft typed while the request was pending. Failure keeps the text and retry UUID. Enter sends; Shift+Enter inserts a newline; IME composition does not accidentally send. Separate red Clean/Delete warnings capture the selected stable ID and revision; Cancel is inert. Clean offers Download first and warns that stored messages will no longer be downloadable. Delete selects another page, or creates the next default chat if none remain.

Send, actual-reply append, Clean and Delete coordinate through transaction locks and revision checks. Cleaning increments the revision, so an old pending request/reply cannot reinsert the previous conversation. Page deletion cascades messages atomically. Failure rolls back page/message/selection changes and is never reported as success.

PDF export locks the selected page for a consistent current name and complete ordered conversation. It includes sender labels, multiline wrapping, readable page breaks/page numbers and embedded Latin/Devanagari fonts. Changing the selected UI page during export does not change the captured export ID or server filename. Empty/cleaned pages return `409`; deleted pages return `404`. Previously downloaded PDFs remain unchanged. Other Unicode scripts/glyphs not covered by the bundled fonts return explicit `422`, retaining all saved messages rather than dropping characters.

## Viewers and responsive layout

- The global solid-blue button rule now permits readable red destructive buttons. File confirmations use native top-layer dialogs, initial Cancel focus, keyboard trapping/Escape, focus restoration and safe cancellation on identity/target changes.
- Book table columns now reserve usable description and edit-control space separately from action columns. Text containers can shrink to ellipsis; bounded horizontal scrolling retains usable controls. The existing shared truncated-text component measures rendered width and exposes full text through hover, focus and tap. Existing 5/10/25 selectors are actual row-page sizes; they were not reinterpreted as character limits.
- Shared wrapping/min-width constraints protect long labels, metadata editing, dialogs, forms and toolbars. Existing user-management/profile/role/history tables already use contained scrolling and wrapping; those patterns are preserved.
- Both CSV and Excel viewers have a **Full Page** link to `/spreadsheets/{fileId}/view?sheet={sheetId}&row={rowPage}`. The page uses application space, retains the initial sheet/row selection, supports wide/long scrolling, and has Back/Close navigation. It does not invoke browser fullscreen.
- PDF Full Page removes its duplicated theme/color controls and inherits the selection from the normal reader outside Full Page. Full Page zoom controls are beside Single/Double Page in the lower toolbar, without duplicate zoom controls above.
- Single Next retains its existing animation. Single Previous brings the previous page into the clipped page box. Double Next turns the right leaf leftwards about the center spine; Previous turns the left leaf rightwards about that spine. Front/back canvas faces, fixed facing pages and odd-page blank slots retain final page order. Resize/zoom/mode/navigation changes cancel stale layers; reduced-motion skips the animation.

## Files changed for this request

- Backend chat: `help/{AiHelpController,ChatPage,ChatMessage,ChatService,ChatPdfExporter}.java`, new chat migration and `fonts/chat/*`.
- Backend files/security: `FileDeletionPolicy.java`, `FileDeletionCapabilitiesController.java`, `UploadedFileDeletionService.java`, `AuthorizationService.java`, `UserSecurityConfiguration.java`, new capabilities/management migration.
- Frontend AI Help: `features/ai-help/*`, `app.routes.ts`, header TS/HTML/CSS.
- Frontend file controls/management: `components/delete-upload/*`, upload-history component, Books API service, upload status model, related tests.
- Responsive/viewers: book-table and metadata-field CSS, shared `styles.css`, book-reader TS/HTML/CSS/tests, spreadsheet-view TS/HTML/CSS/tests, new spreadsheet-page component, `books.routes.ts`.
- Verification: new `ChatPersistenceTests`, `FileManagementAccessTests`, `FileDeletionPolicyTests`, `SuperUserFileAiPermissionTests`; updated HTTP chat/deletion, download, spreadsheet read, authentication schema/fixture, chat, viewer, capability, confirmation and management UI tests.

## Verification and limitations

The full backend suite passed **821 tests run, zero failures/errors, nine existing skips** after the explicit `SUPER_USER` permission migration. This includes repeatable grants, preserved disabled catalog entries and private chat access without pre-existing role grants. The full Angular suite previously passed **320 tests** and the production Angular build passed; this permission follow-up changed no frontend code. CSS warning budgets remain for AI Help (4.23 kB) and the reader (7.74 kB), both below the configured 8 kB error budget. Both repositories' whitespace checks pass. A final focused PDF/tool/management run passed **17 tests** after the final footer/description polish, including a check for a single footer per page and Windows reserved filenames. Processing receipts defer capability loading until ready, so a temporary active-upload denial cannot permanently disable Delete.

The backend tests use isolated H2 databases and temporary storage with real bearer/CSRF/policy HTTP checks. They cover the role matrix on all deletion paths, cross-tenant denial, downloads by another uploader, chat owner/tenant isolation, console `hi`, new-session restoration, selection/naming, pagination, rename, full selected PDF including Hindi, Clean/Delete scope, stale revisions/idempotency, and transaction rollback. Viewer tests cover spine geometry/front/back frames, first/last/odd spreads, cancellation, mode changes, zoom/resize and reduced motion. PDF first/last pages were rendered and visually inspected successfully.

Live browser animation/layout verification was attempted but blocked: the browser access host is unavailable; native Edge input reported missing coordinate geometry and then detected user activity. The window was left undisturbed. An ignored loopback preview server/fixtures under frontend `tmp/` exercised synthetic HTTP smoke checks only; this is not evidence of a live production integration. The additive migrations and reserved super-user grants were verified against the configured local MySQL database. Production deployment, actual assistant generation and production file deletion were not exercised. The existing Excel import workbook-memory and exceptional filesystem cleanup limits from the earlier change remain as documented.
