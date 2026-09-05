# OpenList design language

OpenList puts the user's files, current account, and task results first. The same rules apply to
connection, directory browsing, search, transfers, settings, and media presentation.

## Product grammar

- **Context:** the file header identifies the active account/server and directory. Account
  management is reachable directly and returns to the destination that opened it.
- **Actions:** opening a file and choosing a file action are distinct. The action sheet names
  the available verbs; rename and delete enter their own focused forms.
- **Search:** search replaces directory chrome and states its recursive scope. A result carries
  its actual parent directory; pagination is based on the server's reported total.
- **Continuity:** file, transfer, and settings are stable navigation destinations. Active transfer
  and playback summaries reserve their own space above navigation, including navigation-rail
  layouts. A summary never floats over the last file row.
- **Recovery:** loading, empty, unavailable, locked, waiting, and failed states have different
  messages. A control only promises an action supported by the current backend and permissions.

## Foundations

The implementation is in `ui/theme` and `ui/designsystem`. Screens consume MaterialTheme roles
instead of redefining colors, fonts, or component shapes.

| Foundation | Production rule |
| --- | --- |
| Brand | Neutral blue: light primary `#245FA6`, dark primary `#AACBFA`. Blue identifies actions and selected destinations; files sit on neutral surfaces. |
| Surface | Light background `#F8FAFC`, dark background `#11161C`. Use continuous lists, spacing, and dividers before adding contained surfaces. |
| Dynamic color | The user's existing dynamic-color preference is honored on supported Android versions; layout and typography preserve product identity independently of hue. |
| Typography | System sans-serif; `bodyLarge` and `titleMedium` use 16sp/24sp, `bodyMedium` uses 14sp/20sp, page headings use 24sp/32sp. Small captions are secondary only. |
| Spacing | `OpenListSpacing`: 4, 8, 12, 16, 24, 32dp. `OpenListLayout`: page inset 16dp, ordinary content maximum 720dp, pane gap 24dp. |
| Shape | Material shape roles use 4, 8, 12, 20, 24dp corners. Larger radii distinguish panels from continuous list content. |
| Media | Audio presentation and all playlists inherit the app's light, dark, and dynamic colors. The image viewer keeps neutral dark surfaces; video overlays use paired `OpenListMediaColors` scrims/foregrounds for legibility over video. |
| Motion | Use Compose/Material transitions to express navigation or expanding content. Animation does not determine request or background-task lifetimes. |

The pure `OpenListTheme(darkTheme, dynamicColor, content)` is the production implementation used by
previews and screenshot tests. The SessionStore overload adapts persisted appearance preferences.
`OpenListMediaTheme` is used by the image viewer. Theme text foreground/container pairs have explicit
contrast coverage; dynamic colors and arbitrary content still require runtime checks.

## Components and state ownership

`OpenListSectionHeader`, `OpenListEmptyState`, and `OpenListErrorState` provide shared grouping and
feedback. File items, action/detail panels, transfer items, the shared endpoint editor, and
`NowPlayingBar` express product-specific behavior. Standard Material buttons and fields stay direct
dependencies; there is no universal wrapper layer around every control.

Directory, search, protected-directory, details, and mutation state live in lifecycle-aware browser
state holders. A session owner retains the current child state through Activity recreation and
clears it when identity changes, even with another tab visible. Gallery preparation and selection
have the same ownership. Small navigation inputs are saveable; credentials, listings, and signed
media URLs are not serialized into navigation state.

The app shell owns navigation and persistent summaries. WorkManager owns transfer execution and
document-grant cleanup. MediaSession owns audio/video playback. The UI calls those owners and
renders their state; changing a presentation must not create a second transfer queue or player.

## Layout and accessibility contracts

- Interactive targets are at least 48dp; text containers use minimum heights or natural height.
- Large text can wrap, and alternatives such as vertical theme choices replace crowded rows.
- Headings and specific action labels appear in semantics. Selection and failure have textual
  meaning in addition to color. Live error messages are announced politely.
- Window width selects navigation and pane layout. Destination content keeps a stable composition
  location when the navigation rail appears.
- Long file names, Chinese/Latin mixed names, empty results, errors, and waiting tasks are included
  in production-component screenshot fixtures.
- A rendered screenshot is visual evidence only. It cannot prove TalkBack order, a server request,
  a background task, process recovery, or playback behavior.

## Connection and transfer details

Login, adding an account, and editing an account share an endpoint editor. Full URL input remains
editable while focused; connection options reveal protocol, port, and reverse-proxy path. Saved
connection identity is preserved when an edit only changes metadata or equivalent URL spelling;
an actual identity change still invalidates authentication.

The login screen uses the existing OpenList mark in a compact brand bar, a short heading, and one
contained form with 12dp input/button corners. On phones the form is centered with a 440dp maximum
width; wide windows place the introduction beside it. Large text returns to one column, and the
whole content scrolls within the available IME-safe area. Login's compact endpoint presentation
shows the complete URL after focus leaves the field, avoiding a second copy beneath the form;
advanced options remain accessible beside its short hint. Account editing keeps its full editor.
OTP has a focused challenge form with the target identity; errors use an inline themed panel.

The password input keeps its full width. A flat “记住密码” checkbox sits immediately below its
trailing edge; the checkbox and label share one 48dp-minimum touch row with native selection
semantics. It has no separate filled container, and checked means a saving preference, not a
completed save. This follows the [Android labeled-checkbox pattern](https://developer.android.com/develop/ui/compose/accessibility/api-defaults).
New identities default to unchecked.
After successful authentication (including OTP), a checked password is stored locally using
Android Keystore encryption bound to the account and credential purpose. The same server/username
can restore it; edits made while loading are never overwritten by the delayed restore. Unchecking
removes the saved password and renewal hash, including before a failed login attempt. Logout or
automatic token invalidation preserves an explicitly remembered password for the next login;
deleting the account or changing its identity removes it. Directory-unlock passwords remain
process-local. Legacy saved hashes can renew sessions but cannot reconstruct an original password.

Transfers use the same pinned top app bar, heading size, page insets, safe drawing area, and theme
background as settings. Wide layouts constrain task content to 720dp; empty and recovery states
stay centered and scroll when text exceeds the viewport.

Transfer presentation observes both upload and download work. New requests attach a one-way session
tag and safe filename metadata. Terminal bytes come from output data because WorkManager clears
progress at completion. Cancellation calls the existing grant-aware worker APIs. Recovery tells
the user when they must select a file or output location again; there is no unsupported universal
pause/resume promise.

Recent results are subject to WorkManager retention. Tasks created by an older build without
identity metadata are not shown in the new session-scoped list: their ownership cannot be proven
through WorkInfo. They still run under their existing worker rules. Permanent history and a
verified migration of legacy tasks would require an additional durable task-record contract.

Cache settings show the measured current usage and a focused cleanup action; advanced limits are
expandable. Cleanup reports completion only after the asynchronous operation returns, while
content protected by an active lease may be removed later.

## File visibility rules

“设置 → 文件显示 → 自定义筛选规则” and the directory/search filter icon open the same editor.
Rules are ordered local preferences shared across accounts. Each rule selects Hide or Show and
Files, Directories, or All; it can be added, removed, or moved. Saving persists the draft atomically.

Patterns match the entire name, case-insensitively: `*` matches any sequence and `?` one Unicode
code point. Backslash escapes the next character; literal spaces are preserved. All items are
visible by default, and the last matching rule wins. Hidden directories also hide their descendants;
a child exception needs its parent directories to be visible. For example, hide `*.tmp` then show
`important.tmp`, both targeting Files. To show only JPEG files, hide `*` then show `*.jpg`, targeting
Files so directories remain navigable.

Rules affect directory entries, recursive search results, and newly built media/image sequences.
Search pagination still uses the complete server result count; a fully hidden page retains both
“调整规则” and “加载更多结果”. Existing playback queues keep their current sequence. Hidden subtitle
sidecars remain available to automatic subtitle discovery. These are presentation preferences:
they do not delete server content or change server access permissions.

## Validation workflow

1. Run the meaningful unit regressions with `./gradlew testDebugUnitTest`.
2. Compile device tests and build with `./gradlew compileDebugAndroidTestKotlin assembleDebug`.
3. Render native Compose references with `./gradlew updateDebugScreenshotTest`; inspect the actual
   images before accepting or updating a reference. They live under `app/src/screenshotTestDebug/reference`.
4. Use `./gradlew validateDebugScreenshotTest` to compare future changes against the accepted set.
5. Run `./gradlew connectedDebugAndroidTest` on a disposable test device. On a personal device
   with an existing installation, build `assembleDebug assembleDebugAndroidTest`, verify matching
   signing certificates, and use `adb install -r` for the matching ABI APK and test APK. Run
   `adb shell am instrument -w -r org.openlist.mobile.test/androidx.test.runner.AndroidJUnitRunner`.
   This avoids the connected-test task's installation cleanup; do not clear the app's data.
6. Run `./gradlew lintDebug` and perform the remaining real-task checks described in `UI_REBUILD.md`.

The screenshot plugin is Google's experimental Compose Preview Screenshot Testing tool; it runs
on the host with the actual Compose components. Device tests still require an authorized Android
device with installation permitted by its system settings.

Relevant primary guidance: [custom Compose design systems](https://developer.android.com/develop/ui/compose/designsystems/custom),
[UI state holders](https://developer.android.com/topic/architecture/ui-layer/stateholders),
[accessibility defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults),
[native preview screenshots](https://developer.android.com/studio/preview/compose-screenshot-testing),
[build-managed devices](https://developer.android.com/studio/test/managed-devices).
