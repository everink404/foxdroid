# Android handoff to Primary (B)

Date: 2026-10-04
Repository: https://github.com/everink404/foxdroid.git
Branch: codex/android-handoff-20261004
Owner: Primary (B), after successful transfer. A must not edit this task concurrently.

## Current state

- Docker content moved from server/ to docker/server/; Compose is docker/docker-compose.yml.
- Android skeleton exists: app, content-model, game-core, and Android CI workflow.
- app has an empty local library screen and a device diagnostics screen.
- game-core includes chart parsing and shared chart-vector tests.
- Android planning/status documents still say the Android project has not started; inspect actual sources before updating them.
- A prior local validation of the Docker reorganization reported 32 server/Web tests and static checks passing. Container startup was not validated on A.
- This handoff does not claim an Android APK build, Android tests, device verification, or A0 acceptance has passed.

## First task on B

1. Confirm this repository, branch, and latest handoff commit. Preserve any existing B changes; do not reset or overwrite them.
2. Read docs/ANDROID_DEVELOPMENT_PLAN.md, android/README.md, shared/, and this handoff.
3. Check JDK 17, Gradle 8.11.1, and Android SDK 35. Follow existing build configuration.
4. From android/, run: gradle --no-daemon :game-core:test :app:assembleDebug :app:lintDebug
5. Fix build/test blockers, record results, and update project status to match the actual skeleton.
6. If these checks pass, continue the next incomplete A0 item. Keep A1/A2 work separate; do not claim device tests without evidence.

## Delivery and return

Commit and push changes to this same task branch. Record commit, checks, remaining work, and APK/log locations here. Do not merge to main or release an image as part of this handoff. Transfer back only after synchronizing code and task context.