# NEXUS Runtime Integration

Status: source integration added on `nexus/genesis-foundation`. Compilation, APK assembly, device execution, model loading, and performance tests remain intentionally deferred.

## Runtime registry

`NexusEngineRegistry` owns one gateway instance per provider kind for the lifetime of the AI Studio/Council screen. It centralizes gateway creation and cleanup. Local gateways resolve the active model from `NexusModelStorage` at request time, so Model Center activation is reflected without recreating the screen.

Runtime mapping:

- `LITERT_LM` → `ON_DEVICE_LITERT`
- `LLAMA_CPP` → `ON_DEVICE_LLAMA_CPP`
- `OLLAMA_LAN` → network-only; never accepted as an on-device Manager runtime

The registry rejects use after close and closes all managed gateways when the screen is disposed.

## Local-first Council

`NexusLocalCouncilManager` implements the Manager control plane using the currently active on-device model. The model returns a constrained JSON decision:

- `delegate` — one to eight assignments to known, enabled participants
- `ask_user` — request missing information
- `finish` — return the final summary
- `yield_control` — pause execution

The application validates every participant ID and task before dispatch. Model output cannot invoke tools, shell commands, Android APIs, or arbitrary code.

`NexusLocalModelParticipantAdapter` provides eight bounded executor roles. In the initial local configuration, the Manager and all eight executors share the active local model. Execution is serialized (`maxParallelism = 1`) to avoid concurrent access to a single native inference context and to limit memory pressure.

AI Studio now selects this nine-agent local execution path when an on-device provider is selected. Remote-provider Council execution remains available through the existing provider routing path.

## Evolution routing policy

Approved `ROUTING_POLICY` payloads are now read by the remote-provider Council path before per-agent saved routes. Supported payload shape:

```json
{
  "routes": {
    "research": "OPENAI",
    "analysis": "ON_DEVICE_LITERT"
  }
}
```

Provider values must match the `ProviderKind` enum. Unknown values and malformed policies fail closed instead of silently changing routes. When no Evolution route exists for an agent, the saved per-agent route and then the selected default provider are used.

Prompt profiles and skill packs continue to be injected into AI Studio and Council context. Tool-description and model-configuration proposals remain versioned Evolution data; they are not yet executable runtime controls.

## Dependency compatibility

LiteRT-LM remains pinned to `0.16.1` while the project uses Kotlin `2.2.x`. Later LiteRT-LM releases in the `0.17.x` and `0.18.x` lines publish Kotlin 2.4 metadata and require a newer Kotlin compiler. Do not upgrade the runtime independently of the Kotlin toolchain.

llama.cpp remains pinned to upstream tag `v1.0.0`, with the Android native target limited to `arm64-v8a` and CPU inference. The JNI source symbols were statically checked against the public header in that tag. This is not a substitute for an NDK build.

## Deferred verification

Do not claim the implementation is build-verified or device-verified until the separate validation stage is authorized and completed. Deferred items include:

1. Clean Gradle debug APK assembly with the configured Android SDK/NDK.
2. ABI and native-link verification on arm64-v8a.
3. LiteRT-LM and llama.cpp model loading on a physical Android device.
4. Cancellation, model switching, low-storage, and interrupted-download checks.
5. Memory, latency, thermal, battery, and long-generation measurements.
6. Local Manager decision validation, Council dispatch, and Evolution rollback integration checks.

Model weights remain outside Git and the APK. The desktop Python/PySide6 application and `main` branch remain unchanged.
