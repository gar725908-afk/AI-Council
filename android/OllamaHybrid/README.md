# OllamaHybrid — Android AI Command Center

Android Studio project (Kotlin + Jetpack Compose). This folder is the Android edition alongside the existing Python desktop AI-Council; desktop files are not replaced.

## Product decisions
- On-device local AI runtime and multi-agent AI Council.
- Shared persistent Council context, parallel agents, watchdog and material retrieval.
- Native-first Android Bridge: API/Intent/app adapters before Accessibility or screen gestures.
- Standard, Extended (Shizuku/ADB), and Root profiles. Root is the maximum optional privilege profile, not a blanket consent grant.
- AI agents call typed tools only; model-generated arbitrary shell commands are prohibited.
- Recovery Core remains outside the AI-editable workspace.
- Source code and documentation belong in Git; verified APK binaries belong in GitHub Releases.

## Current status
The project source scaffold includes Council, Material Library, Recovery Core, Accessibility phone controls and initial Android Bridge contracts/Root UID probe. Full Shizuku/Sui integration, Root command service, Permission Center UI, all native adapters, local llama.cpp runtime and APK build are still pending.

See:
- docs/PROJECT_MASTER_SPEC_RU.md
- docs/ANDROID_BRIDGE_ARCHITECTURE_RU.md
- docs/ANDROID_BRIDGE_TEST_PLAN_RU.md

## Build honesty
Do not claim an APK is ready until Gradle assemble succeeds, the APK installs and launches, and device tests pass. The current working environment has no Gradle wrapper/Gradle executable or Android SDK, so no APK build is claimed.
