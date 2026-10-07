---
description: Check the current state of Jarvis Android the way CI does (compile, unit tests, debug APK, Android lint) plus the version/README bookkeeping, and report a pass/fail summary.
argument-hint: "[quick]"
---

Verify the working tree. With `quick`, stop after step 2.

1. **Compile**: `./gradlew :app:compileDebugKotlin`. If it fails, report the errors with `file:line` and stop (suggest `/build-fix`).
2. **Unit tests**: `./gradlew :app:testDebugUnitTest`. Report passed/failed counts and each failing test with its message (reports in `app/build/reports/tests/testDebugUnitTest/`).
3. **Debug APK**: `./gradlew :app:assembleDebug`.
4. **Lint**: `./gradlew :app:lintDebug`, and report only new errors in files changed on this branch (`git diff --name-only origin/dev...HEAD`).
5. **Bookkeeping** for a user-visible change: `versionCode` and `versionName` bumped in `app/build.gradle.kts` versus `origin/dev`, README status line and feature section updated.
6. **Leftovers**: `git status`, debug `Log.d`/`println` added in the diff, `TODO` without context, secrets in the diff.

Report:

```
VERIFY: PASS | FAIL
Compile:  OK | n errors
Tests:    x passed, y failed
APK:      OK | failed
Lint:     OK | n new issues
Version:  0.9.xx (bumped | NOT bumped)
README:   updated | NOT updated
Ready for PR: yes | no (why)
```

<!-- Adapted from everything-claude-code (MIT, Affaan Mustafa), see .claude/THIRD_PARTY.md -->
