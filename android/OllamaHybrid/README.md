# OllamaHybrid — Android edition materials

This directory currently contains the Android architecture/specification and the first Bridge foundation files alongside the existing Python desktop AI-Council. It is **not yet a complete, independently buildable Android Studio project mirror**.

## Committed in this directory
- Product master specification and accumulated decisions.
- Hybrid Android Bridge architecture, including Standard / Shizuku Extended / Root profiles.
- Test plan and device matrix.
- Initial Kotlin Bridge contracts, risk policy, tool registry/router and Root UID probe.
- Android app Gradle configuration snapshot for version 0.5.0.

## Full working project
The full current Android working tree is maintained separately as the OllamaHybrid project archive supplied with this update. The remaining existing Android application sources, resources and root Gradle project files still need to be synchronized into this GitHub directory before it can be built directly from GitHub.

## Status
- Architecture and Bridge foundation committed.
- Static validation and isolated Kotlin/JVM smoke tests passed.
- Full Android Gradle build, APK install and Root/Shizuku device tests are **not** claimed.
- Full Shizuku/Sui integration, Direct Root command service, Permission Center UI, native adapters, local llama.cpp runtime and Council tool wiring remain future implementation work.

APK binaries belong in GitHub Releases only after a real build, device verification and checksum generation.
