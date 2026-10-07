---
description: Fix a failing Gradle build or unit-test compile of Jarvis Android with minimal changes.
---

Use the **build-error-resolver** agent: run `./gradlew :app:compileDebugKotlin` (then `:app:testDebugUnitTest` and `:app:assembleDebug`), fix the errors one cause at a time with the smallest change, rebuild after each fix, and stop when everything passes or when a fix would need a design change (then explain and ask). Finish with what was fixed and what remains.

<!-- Adapted from everything-claude-code (MIT, Affaan Mustafa), see .claude/THIRD_PARTY.md -->
