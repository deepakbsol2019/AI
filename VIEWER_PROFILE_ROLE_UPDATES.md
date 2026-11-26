# Viewer, upload, Add User and header update

This is the current scope. It supersedes earlier reader controls, Home upload-panel and Add User role-selection descriptions. Existing PDF upload/extraction/download APIs, upload history, tenant isolation and account-management policies remain in use.

## Reading controls and stable rendering

- Light and Dark modes remain. Colorful mode reveals two native color inputs, **Background Color** and **Text Color**. The selected colors immediately map the displayed PDF's paper and ink, including scanned content. Light mode restores original document colors. The existing PDF layout and source bytes are unchanged.
- Normal and Full Page viewers use the same appearance-control template and shared component state. Mode, custom colors, reading mode and zoom survive entry/exit. Zoom is limited to **50–125%**, shown numerically, with disabled buttons at the limits. Full Page now applies zoom to its fitted page size.
- Full Page keeps its navigation, sound and Exit controls below the reading area. Appearance controls occupy their own area above it. Large or unusually shaped pages preserve aspect ratio; content larger than the viewport scrolls from an accessible top/left edge.
- Page turns retain the previous page as a temporary sheet over the completed new spread and animate in the appropriate forward/backward direction for **580 ms**. Mode, zoom and color changes cancel any old turn sheet. Reduced-motion preferences suppress animation.

The flicker investigation found a reproducible redraw mechanism in the existing code: the ResizeObserver unconditionally requested another PDF render, PDF.js cleared the visible canvas bitmap on each render, and the in-flow rendering message became another flex item next to the fullscreen spread. Changes in overflow/scrollbars could request more redraws. The fix measures the viewport's outer box, ignores unchanged sizes/render keys, positions the message outside layout flow, and renders into detached canvases. Both facing pages and their layout commit together only after all rendering succeeds. The previous spread remains displayed while that work is pending. Superseded renders cannot commit or replace a newer result with an error.

No PDF-specific browser exception was found in the existing logs. Tests explicitly cover handled render failures and a superseded RenderingCancelledException without console errors or unhandled rejections. Actual browser visual inspection is a separate limit below; a historical backend connection error/lazy-chunk fetch error was not treated as the cause of PDF flicker.

## Home upload notification

Home no longer inserts upload validation, progress or status panels above the collection. The existing top notification, Upload ID/action and **15-second** dismissal remain. Validation/interrupted-status feedback uses notifications too. Closing a notification does not cancel the server job. My Uploads retains persistent status/Refresh Status access, including when automatic polling pauses. Interrupted polling no longer leaves Upload Document permanently disabled.

## Tenant-specific Select Role

The existing Add User form loads role options through `GET /api/user/users/create-options?tenantId=...`. Options now include `roles: [{roleId,name}]`; user creation accepts optional `roleId` alongside the existing fields. No new database columns or migration are required.

Changing tenant immediately clears the role selection and options, disables selection/submission while loading, and ignores stale responses. The form submits the selected role with the selected tenant. When assignable roles exist, the form requires a valid choice. Creators without role-assignment authority retain their existing ability to create an account without a role.

The backend reuses RoleManagementService's `ROLE_ASSIGN` checks, tenant boundary, active-role validation, system-role restrictions and delegated permission ceiling. SUPER_USER policy is preserved; its protected role cannot be assigned through this form. The new membership and selected role are saved in one transaction before the invitation is sent. Invalid/foreign/inactive/unauthorized role selections roll back account creation and do not send invitations.

## Header profile icon

The former navigation toggle is now a profile icon in the same top-left header position. Hover or keyboard focus shows only a compact username/name tooltip. Clicking toggles the existing full profile/sidebar with its current details, navigation and actions. Tooltip dismissal, Escape and focus restoration are covered. Reading modes are available in the reader, not in the profile.

## Verification

- Full frontend suite: **273 tests passed across 45 files**, including viewer stability, stale-render cancellation, custom color controls, zoom limits, chart aspect ratios, directional animation, upload notifications/no inserted Home panel, tenant-role loading/assignment and profile hover/click behavior. Log: UI `tmp/viewer-profile-role-tests.log`.
- Frontend production build: **passed**. Reader stylesheet warning: **7.04 kB** versus the existing 4 kB warning threshold, below the unchanged 8 kB error threshold. Log: UI `tmp/viewer-profile-role-build.log`.
- Backend `mvn package`: **passed**, **701 passed and 9 skipped (710 total)**, zero failures/errors; executable JAR built. Includes the existing PDF, upload, authentication, role-management and deletion regressions. Log: backend `target/viewer-profile-role-package.log`.
- Focused reader/runtime tests: **12 passed**, including huge-canvas bounds, pending spreads, repeated resizes, an interrupted render, no uncaught rejection/console error, and page-turn direction/duration. Log: UI `tmp/viewer-stability-tests.log`.
- Actual raster smoke: **15 cases passed** through the current PdfReaderService, Angular dependency injection, real PDF.js and Skia canvas. Synthetic vector-chart PDFs covered large landscape, tall and extremely wide pages at single/double fitting widths and both zoom limits, plus oversized-render stress cases. Pixel checks verified chart/text/edge markers, preserved aspect ratios and bounded bitmap dimensions; saved landscape/tall PNGs were also inspected. There were zero warnings/errors. Fixtures and the smoke script are isolated under ignored UI `tmp/`; no new dependency was added. Results: `tmp/vector-chart-smoke-results.json`, log: `tmp/vector-chart-smoke.log`. This is raster verification, not browser layout or GPU-filter verification.
- No live accounts, invitations/emails or uploads were created by these tests. No application database migration is needed for this increment.

The browser access host was unavailable, so actual browser geometry, GPU color-filter appearance and visible animation/flicker were not manually observed. Automated DOM/render-lifecycle checks do not replace that device-level visual check. Restart the backend and frontend to load the updated application.
