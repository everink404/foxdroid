# Primary A0 validation — 2026-10-04

Repository: `C:\Users\Ever_\Documents\foxdroid`
Branch: `codex/android-handoff-20261004`
Base commit: `1b1c180cbabe546f32318ce0429a3f28043cc5db`

## Local checks

Temurin JDK 17.0.20.1 and Gradle 8.11.1 were installed in ignored `.tools/`.
The Gradle archive SHA-256 matches the official distribution checksum.
Android SDK 35 was already installed; the build installed Build Tools 35.0.0
using the existing accepted SDK license. AGP remains 8.9.2, Kotlin 2.1.20,
minimum API 29, target/compile API 35.

Executed from `android/`, using the downloaded Gradle distribution:

```text
gradle --no-daemon :content-model:test :game-core:test :app:assembleDebug :app:lintDebug
BUILD SUCCESSFUL in 3m 39s
54 actionable tasks: 54 executed
```

- Content contract: 2 test methods passed; response fields match six shared API
  schemas, cache keys isolate sources, server defaults to disabled.
- Game core: 1 test method passed, checking all 5 shared chart-note cases.
- APK: `android/app/build/outputs/apk/debug/app-debug.apk` (2,441,373 bytes).
- Lint: 0 errors, 20 warnings. Remaining warnings concern target API 35,
  ChromeOS ABI support, backup extraction rules, missing launcher icon and
  hardcoded diagnostic text. They are recorded, not suppressed.
- Reports: `android/content-model/build/reports/tests/test/`,
  `android/game-core/build/reports/tests/test/`,
  `android/app/build/reports/lint-results-debug.html`.
- Gradle wrapper generation passed separately. CI now invokes the pinned wrapper
  and includes content-model tests. Remote CI has not been run in this session.

## Limits and next work

`adb devices` reported no connected device. Installation, empty-library startup,
60 Hz/high-refresh device coverage, four-point touch, audio timestamps, underrun,
latency, lifecycle recovery and offline full-song acceptance are unverified.
A0 is partially complete; it has not met its two-device exit criteria.

The existing diagnostic shell has no importer or native audio runtime. Chart
vectors passing do not imply judgment-sequence tests pass: the judgment engine
and those tests remain A2 work. No WebView runtime or server dependency was added.
Content mapping/cache/migration boundaries are documented in
`android/CONTENT_CONTRACT.md`; persistent storage is not implemented yet.

No user song packs were added. Changes have not been committed, pushed, merged
or released. Next: obtain device evidence for A0 and proceed to A1 local import.

## Emulator acceptance and downloadable APK (2026-10-04)

The user authorized emulator acceptance and uploading the APK to GitHub.
AVD: JellyXR_API35, Pixel 7 configuration, Android 15/API 35, x86_64,
1080×2400, approximately 60 Hz. Started with WHPX, software GPU and no audio.
Initial installation was attempted before package services were ready and was
rejected; installation succeeded after `sys.boot_completed=1`.

The UI checks exposed missing system-bar insets and system Back exiting from
diagnostics. MainActivity now handles insets, returns to library on Back, and
saves/restores the diagnostic page across Activity recreation.

Final APK checks:

| Check | Result |
|---|---|
| Install/update | adb reported Success |
| Cold start | Status ok; reported TotalTime 1881 ms (emulator only) |
| Empty library | Title, empty state, server-disabled message visible |
| Device diagnostics | Device/API/refresh/sample-rate/buffer/output fields visible |
| Return button | Returned to local library |
| System Back | Returned from diagnostics to library after repair |
| Home then reopen | Diagnostics remained visible |
| Portrait→landscape→portrait | Diagnostic state restored, visible content readable |
| System bars | Final screenshots show content inside safe area |
| Crash log | No AndroidRuntime error entries returned |
| Network permissions | aapt reported no requested permissions |

Rebuild after UI changes passed in 1m 19s (54 tasks; 13 executed,
41 up-to-date); lint still reports 0 errors and 20 warnings. Existing core test
results remain passing. Evidence screenshots are in `docs/android-evidence/`.
An `am kill` attempt while backgrounded did not establish actual process death;
process-death recovery is not claimed as verified. Lock-screen and audio-focus
scenarios remain unverified.

Downloadable copy: `artifacts/android/foxdroid-a0-20261004-debug.apk`
Size: 2,451,785 bytes.
SHA-256: `a3b0c38d78039a6712b40d195627a0f21cc3917bbc5bae202d235c9822d3c48b`.
This is a debug-signed A0 diagnostic shell, not a playable game. User device
acceptance remains pending. No audio latency, four-point touch or complete-game
acceptance is inferred from emulator results.
