# OllamaHybrid — Android edition materials

This directory currently contains the Android architecture/specification and the first Bridge foundation files alongside the existing Python desktop AI-Council. It is **not yet a complete, independently buildable Android Studio project mirror**.

## Product and design specifications
- [Project master specification](docs/PROJECT_MASTER_SPEC_RU.md)
- [Cyberpunk UI design system and screen specification v1](docs/UI_DESIGN_SYSTEM_RU.md)
- [Hybrid Universe Experimental Edition — visual system and detailed screens v2](docs/HYBRID_UNIVERSE_EXPERIMENTAL_V2_RU.md)
- [Universal Internet Access and Agent Browser specification](docs/WEB_ACCESS_AND_AGENT_BROWSER_RU.md)
- [Android Bridge architecture](docs/ANDROID_BRIDGE_ARCHITECTURE_RU.md)
- [Android Bridge test plan](docs/ANDROID_BRIDGE_TEST_PLAN_RU.md)

## Committed in this directory
- Product master specification and accumulated decisions.
- UI design system v1 and Hybrid Universe Experimental Edition v2: visual language, design tokens, motion states, navigation, detailed screen layouts, accessibility, performance goals and implementation phases.
- Universal Internet Access / Agent Browser architecture specification.
- Hybrid Android Bridge architecture, including Standard / Shizuku Extended / Root profiles.
- Test plan and device matrix.
- Initial Kotlin Bridge contracts, risk policy, tool registry/router and Root UID probe.
- Android app Gradle configuration snapshot for version 0.5.0.

## Full working project
The full current Android working tree is maintained separately as the OllamaHybrid project archive supplied with this update. The remaining existing Android application sources, resources and root Gradle project files still need to be synchronized into this GitHub directory before it can be built directly from GitHub.

## Status
- Architecture, Bridge foundation and UI/Internet specifications committed.
- Static validation and isolated Kotlin/JVM smoke tests passed for the earlier Bridge foundation.
- Full Android Gradle build, APK install and Root/Shizuku device tests are **not** claimed.
- Full Shizuku/Sui integration, Direct Root command service, Permission Center UI, native adapters, local llama.cpp runtime and Council tool wiring remain future implementation work.
- UI and browser specifications are design requirements, not claims that those screens/features are already implemented.

APK binaries belong in GitHub Releases only after a real build, device verification and checksum generation.
