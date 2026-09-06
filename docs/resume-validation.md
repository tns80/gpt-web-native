# Resume and navigation validation

This change keeps a single main WebView and normal HTTP cache behavior. It does
not store screenshots, chat text, OAuth URLs or a second copy of the website.
The private preferences contain only a sanitized conversation route and theme.
Android may still reclaim the process; cold starts still initialize the website.

## Device checks

1. Open a long conversation, scroll and type an unsent draft. Go Home, use another
   app briefly and return via the launcher. The same live page should remain,
   including the draft and position. There should be no new document-start event.
2. Close the keyboard and use Back through history. At the history root, Back
   backgrounds the task. Reopen and verify the page remains. Test gesture Back on
   Android 13+ as well as three-button navigation. Back with the keyboard open
   must dismiss the keyboard first.
3. Visit two conversations using the website sidebar. Inspect history/document
   events to distinguish client-side routing from full document navigation.
   The native wrapper must not construct a new WebView for this action.
4. Swipe the app away and relaunch. It should load the last supported conversation
   route, without restoring query strings or fragments. A cold start is expected
   to reload web content; an unsent draft is not guaranteed after process death.
5. Select a dark website theme, restart and check the app content/loading background
   and system icons. The system-owned launch splash before Activity creation may
   still follow the device theme. Test a light page on a dark device and vice versa.
6. Start offline. Verify the error offers retry; restore connectivity and tap it.
   A stalled initial load offers retry after 15 seconds. HTTP sign-in/challenge
   pages remain visible rather than being replaced by a generic error screen.
7. Recheck top spacing, keyboard insets, popup login, upload and voice. These
   existing paths must continue working.
8. On a test device terminate the WebView renderer. The app should offer explicit
   recovery rather than crashing or repeatedly reloading. If a popup shares the
   renderer, it must also be cleaned up. Retry should construct one new main view.
9. Sign out, then sign in with another test account. Check that auth routes and
   credentials are never persisted as the startup URL. Temporary-chat launches
   should resume at home. Unsupported routes fall back to the last supported route.

## Local diagnostics

Use `adb logcat -s GptWebLifecycle:I` on a connected test device.
Logs contain event names, monotonic time, durations and numeric error codes only.
They contain no URLs, conversation IDs, titles, cookies, page text or screenshots.
No analytics SDK, server upload, periodic polling or disk log is added.

- `activity_create_*`: new Activity (and main WebView).
- `activity_resume` without create/document start: warm return.
- `history_update` without document start: likely website client-side navigation.
- `main_document_start` / `main_document_finish_*ms`: document navigation.
- `renderer_crash` / `renderer_reclaimed`: renderer loss; explicit retry is needed.

Page commit is only the first safe document frame, not proof that ChatGPT has
finished fetching or rendering the conversation. Native progress tracks document
loading, not the website's internal conversation requests. Diagnose those before
adding site-specific DOM modifications.
