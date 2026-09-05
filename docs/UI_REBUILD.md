# UI rebuild

The redesign makes OpenList a file-first Android client with visible account context,
direct file actions, traceable transfers, and continuous media playback. The target
is a coherent product experience across connection, browsing, search, transfers,
settings, images, audio, and video—not a theme-only change.

## Implementation plan

- [x] Inspect the current routes, themes, asynchronous state, and existing behavior tests.
- [x] Run the pre-change unit-test baseline (`testDebugUnitTest`, successful).
- [x] Establish production/preview theme parity, semantic colors, typography, layout tokens,
  and a small shared component vocabulary.
- [x] Rebuild file browsing, search, actions, and adaptive file details; extract lifecycle-aware
  asynchronous state and preserve account/request isolation.
- [x] Unify upload/download visibility and real supported task actions in a transfer destination.
- [x] Integrate file/transfer/settings navigation, direct account access, and persistent activity
  summaries without covering scrollable content.
- [x] Rebuild connection/account flows and settings around the user's decisions.
- [x] Apply the language to image/audio/video presentation while preserving playback contracts.
- [x] Render and inspect representative native UI, validate meaningful behavioral regressions,
  run lint/build checks, and document any remaining runtime verification gaps.

## Behavioral requirements

- The active server/account is visible before a file operation. Account switching clears old
  content and rejects stale asynchronous results.
- Search communicates its scope, uses the actual parent path, and restores browser context.
- Choosing a file action precedes entering its form; mutations cannot submit twice while busy.
- Transfer progress/results belong to the originating session. Controls only promise operations
  the worker and document permissions can actually perform.
- Media services, worker tasks, document grants, upload staging/checkpoints, and cache leases keep
  their existing ownership. UI disposal must not accidentally terminate unrelated background work.
- Navigation chrome, playback summary, and transfer summary reserve layout space. Window changes
  preserve destination state.
- Production, previews, and UI validation use the same design system. Long names, dark theme,
  large fonts, touch targets, and semantic actions are part of acceptance.

## Validation log

- 2026-09-05: clean starting tree at `176589d`; `./gradlew testDebugUnitTest` passed before edits.
- No connected device or installed AVD/system image was available at the initial environment check.
- The implementation's unit suite passed: 84 suites, 456 tests, no failures, errors, or skips.
- Final frozen-source gate passed:
  `./gradlew --no-daemon --max-workers=1 testDebugUnitTest assembleDebug assembleDebugAndroidTest validateDebugScreenshotTest lintDebug`.
  Lint reports 0 errors, 15 warnings, and 1 hint; remaining warnings are dependency updates,
  existing PiP guidance, application-context ownership, and style suggestions. An earlier lint
  analysis crashed while a test source was being edited; the final fresh-process run completed.
- Native Compose rendering produced 37 inspected reference images covering file lists/search/
  actions/rename/details, connection/account/OTP, transfers, settings, media, and the component
  catalog. Light/dark, large fonts, and expanded widths are represented. An initially blank
  modal-sheet preview was replaced by the production sheet's shared content, then inspected.
- The requirement audit found that entering search hid the current account label. The production
  search header now shows the same server/account context as the directory header. Its regular
  and 320dp / 2×-font renders were inspected; both preserve the account and recursive scope.
  After this fix, unit tests, both APK builds, reference rendering, and lint passed together;
  all 37 screenshot comparisons passed separately after inspecting the updated references. The updated
  ARM64 Debug package was installed and verified on the phone at 16:18 (Asia/Shanghai).
- A source-based sRGB contrast calculation checked 23 opaque text/container pairs in each
  light, dark, and media palette. The minimum ratios are 5.579:1, 5.472:1, and 5.472:1. This
  verifies the specified palette pairs, not device-provided dynamic colors or arbitrary imagery.
- Independent code review covered browser request ownership, account identity preservation,
  transfer-session filtering, and playback intent. It prompted fixes for hidden refresh errors
  in empty directories, equivalent endpoint edits losing authentication, and idle playback toggles.
- Full-screen long errors now scroll to retry. New device tests cover short-window large-text
  recovery, playback chrome reserving list space, asynchronous cache cleanup, and API24-compatible
  video-surface capture. Compilation is separate from actually running these tests.
- The user selected a physical device instead of installing an emulator image. ARM64 Debug
  `org.openlist.mobile` version 0.1.0 (1) was successfully installed with `adb install -r` on
  model 22081212C; its certificate matched the previously installed package. No uninstall or
  clear-data command was used.
- The independent test APK was rejected by the phone with
  `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`. Device-test execution is pending
  permission on the phone. No SDK license was accepted and no emulator image was installed.

## Follow-up changes: 2026-09-05

The user requested four concrete revisions after trying the redesigned application:

- Transfers now share the settings tab's pinned title bar, typography, spacing, safe area,
  theme background, and constrained wide-screen content.
- The server-login password has a compact adjacent “保存” checkbox. Successful checked logins
  store an account-bound encrypted password locally; unchecked attempts clear previous saved
  credentials before the request, even when login fails or OTP is cancelled. Automatic session
  invalidation preserves remembered input and cannot overwrite a later opt-out or login.
- Audio and video playlists inherit the application theme, including live dynamic/light/dark
  changes. Video controls retain explicit contrast over the video image.
- Ordered wildcard Hide/Show rules are editable from settings and directory/search headers.
  Rules persist locally, support file/folder targeting, and apply to new playback/gallery
  sequences. Fully hidden search pages still paginate; hidden results retain a recovery action.
  Complete sibling snapshots keep hidden subtitle sidecars available for video autodiscovery.

Independent review found and prompted fixes for OTP renewal deleting a remembered password,
failed unchecked logins retaining an older saved password, and filtering subtitle sidecars before
media preparation. Tests exercise the production account transitions and codec, wildcard
precedence/Unicode/parent visibility, media sequence filtering, and hidden subtitle discovery.
Device regression sources additionally cover the compact checkbox, editor actions, hidden-page
pagination/recovery, Android Keystore isolation, and playlist theme changes.

Follow-up validation completed on the final frozen sources:

- `testDebugUnitTest`: 87 suites, 485 tests; zero failures, errors, or skips.
- `assembleDebug` and `assembleDebugAndroidTest`: passed.
- `updateDebugScreenshotTest`: 54 references, with no stale or missing images. Updated transfer,
  login, playlist, filter-editor, and all-hidden recovery renders were visually inspected.
- `validateDebugScreenshotTest`: all 54 comparisons passed.
- `lintDebug`: zero errors, 16 warnings, and 3 hints. Remaining reports concern dependency
  updates, existing PiP/context handling, and Compose/KTX style suggestions.
- The final `app-arm64-v8a-debug.apk` was installed successfully with `adb install -r` on
  model 22081212C. Version 0.1.0 (1) and the original Android Debug certificate were verified.
  No uninstall or data-clearing action was used. The independent test APK remains absent on
  the phone, so compiled device regressions are still pending execution.

## Login redesign: 2026-09-05

The user requested a dedicated redesign of the login experience. The native screen now uses the
existing OpenList mark in a compact brand bar and brings the server, username, password, and primary
action into one contained form. The compact address editor displays the complete resolved URL after
focus leaves it; it keeps the original text during continuous input. Advanced connection options
remain visible in a short row. The adjacent saved-password checkbox has an integrated tonal surface,
and an explicit note explains local encrypted saving. The OTP form shows its account identity and
shares the same fields, primary button, and error treatment. Wide windows place introduction and
form side by side; large text uses one column and short windows remain scrollable.

The changes reuse the existing production palette, type, shapes, encryption, and login state
ownership. Existing UI regressions were extended for complete-URL focus transitions, the compact
save control in a 320×300dp viewport at 2× font scale, and invalid/valid OTP IME submissions followed
by repeated button taps. These device tests compile; they still require permitted test-package
installation before they can be counted as executed.

Login redesign validation passed on frozen sources: 485 unit tests, both Debug APK builds,
57 screenshot comparisons, and Lint with zero errors (15 warnings and 3 hints). Phone, dark, empty,
wide, large-font, OTP, and short-window error states were rendered and inspected. At 600dp height,
the error state needs scrolling to reach the primary button; it is not fixed outside the scroll
content. The new ARM64 Debug APK was successfully installed with `adb install -r` on 22081212C,
with the same 0.1.0 (1) version and matching original Debug certificate. No app uninstall or
clear-data operation was performed. Device interaction tests remain unexecuted because the phone
has not permitted installation of the independent test package.

## Remember-password refinement: 2026-09-05

After the user requested references from established Android products such as Netflix and YouTube,
the saved-password preference was changed to a flat, labeled native checkbox immediately below the
password field's trailing edge. The field now keeps its full width. The checkbox and “记住密码”
label share a minimum 48dp touch row; the filled tile and stacked label were removed. The repeated
footer explanation was removed, while the accessibility label identifies local encrypted saving.
Checked still expresses intent; successful authentication performs persistence.

The implementation follows Google's [accessible labeled-checkbox pattern](https://developer.android.com/develop/ui/compose/accessibility/api-defaults).
[Netflix's current mobile login guidance](https://help.netflix.com/en/node/311830241325668) describes
code/password authentication but does not document a matching remember-password checkbox.
[YouTube's Android Save action](https://support.google.com/youtube/answer/57792?co=GENIE.Platform%3DAndroid&hl=en)
adds videos to playlists; it is a content command rather than a credential-saving preference.
These sources informed the semantic choice, not a claim that the login control copies either app.

Refinement validation passed on frozen sources: 485 unit tests, both Debug APK builds,
57 screenshot comparisons, and Lint with zero errors (15 warnings and 3 hints). The updated
phone, dark, and narrow large-font renders were visually inspected. The ARM64 Debug APK was
installed with `adb install -r` on 22081212C and its update time verified as 17:14:53
(Asia/Shanghai), with version 0.1.0 (1) and the same original Debug certificate. Device interaction
tests remain unexecuted because the independent test package is still blocked on the phone.

## Upload session fix: 2026-09-05

Read-only WorkManager diagnostics on the physical phone showed that recent uploads failed on
their first attempt with “登录凭据已失效，请重新登录后选择文件”, before file staging or network upload.
`SessionStore.awaitLoaded()` returned the settings captured by the first completed load attempt,
even after an interactive login had published a new token. Upload and download workers could
therefore receive the process's original signed-out session.

The session-loading APIs now use the load attempt only to await successful initialization, then
return the current published settings. Task identity and request guards continue to reject
logged-out or replaced accounts. The Android constructor remains unchanged for callers; an
internal DataStore constructor allows regression tests against the real persistence path.

Four new real-DataStore regressions failed with the old implementation and pass with the fix:
login after cold start, token renewal, account switching, and logout followed by a new login.
They verify the upload request's current authorization and rejection of older login bindings.
The full unit suite passes with 489 tests across 88 suites and no failures, errors, or skips.
Both Debug APK builds and Lint passed (zero errors, 15 warnings, 3 hints). The same-certificate
ARM64 Debug package, version 0.1.0 (1), was installed with `adb install -r` on 22081212C and
verified at 17:26:01 (Asia/Shanghai). No uninstall or data-clearing operation was used. A new
upload to the user's real server has not been executed; the independent device-test package
remains blocked by the phone's installation restriction.

## Video brightness and volume gestures: 2026-09-05

The shared Compose video hit surface previously handled taps, speed-boost holds, and horizontal
seeking only. It now locks each drag's direction after touch slop and keeps the side selected by
the initial down event: left vertical drags adjust window brightness; right vertical drags adjust
media volume. Up increases and down decreases, with compact icon/percentage feedback. Seeking and
vertical adjustment cannot fire for the same drag, and vertical controls also work for streams
without a seekable duration. The video hit surface consumes its drag before the windowed player's
outer scrolling container. Buttons, the timeline, and playlist retain their own hit targets.

The device-control owner lives at the playback-overlay level, so switching between windowed and
fullscreen layouts preserves the brightness override. Leaving video playback or entering PiP
restores the previous window setting; player lock and PiP disable adjustment gestures. Brightness
is bounded to 5–100%. Media volume is read back after each requested change, including Android's
fixed-volume or safety restrictions, and remains at the user's selected level on exit.

The implementation uses the public [window brightness override](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#screenBrightness)
and [media-stream volume API](https://developer.android.com/reference/android/media/AudioManager#setStreamVolume(int,%20int,%20int)).
It adds the ordinary `MODIFY_AUDIO_SETTINGS` permission and does not write global brightness.
Regression sources cover level boundaries/restoration, actual volume feedback, gesture directions,
cross-centre drags, unavailable seeking, cancellation, disabling, and parent-scroll competition.

Validation passed on frozen sources: 496 unit tests across 89 suites, both Debug APK builds,
59 screenshot comparisons, and Lint with zero errors (15 warnings and 3 hints). Normal and
320dp / 2×-font adjustment feedback was rendered and visually inspected. Seven new Compose
interaction tests compile but remain unexecuted because the phone still blocks the independent
test package. The original-certificate ARM64 Debug APK, version 0.1.0 (1), was installed with
`adb install -r` on 22081212C and verified at 17:40:06 (Asia/Shanghai), without uninstalling or
clearing app data. Physical brightness/volume gestures still need on-device interaction validation.

## Remaining acceptance

- Permit the independent test APK on the phone and run the instrumented suite.
- Real server login/OTP, transfer grant selection, foreground/background video and audio,
  TalkBack traversal, and process restoration have not been manually verified by the redesign
  task. Unit ownership/restored-input tests do not prove a real process restoration.
- Existing tasks created before session metadata was introduced continue running but are not
  included in the new session-scoped transfer destination; see `DESIGN_SYSTEM.md`.

## Evidence by requirement

| Requirement | Verified evidence | Device evidence still required |
| --- | --- | --- |
| Account context and isolation | File/search-header renders; browser/session-owner unit regressions | Real account switching and subsequent file actions |
| Search scope and restoration | Search render; real-parent-path and restored-input unit regressions | Directory scroll position after leaving search; actual process restoration |
| Explicit actions and duplicate protection | Shared action-content/rename renders; repeated-submit unit regression | Menu → form → result interaction |
| Session-scoped transfers | Identity filtering, terminal progress, and recovery-policy unit regressions | Live WorkManager updates and document-provider grants |
| Background ownership | Playback disposal, grant lifecycle, staging/checkpoint, and cache unit regressions | Android foreground/background/service combinations |
| Navigation and reserved space | Production Scaffold structure; device regression sources compile | Run width-switch retention and last-file reachability tests |
| Shared design and accessibility | Production-theme renders and specified palette contrast | Run large-font touch/scroll tests; inspect TalkBack, IME, and dynamic color |

The device-test package is absent on the selected phone at the last read-only check. The
installation rejection is an external blocker; compiling a test does not change its evidence
status to passed.

This file tracks the whole redesign while implementation proceeds. A completed build alone does
not establish that the product and visual acceptance requirements have been met.
