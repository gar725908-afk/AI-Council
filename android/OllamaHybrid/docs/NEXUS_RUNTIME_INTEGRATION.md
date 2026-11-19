# NEXUS Runtime Integration

Status: source integration added on `nexus/genesis-foundation`. Compilation, APK assembly, device execution, model loading, and performance tests remain intentionally deferred.

## Runtime registry

`NexusEngineRegistry` owns one gateway instance per provider kind for the lifetime of the AI Studio/Council screen. It centralizes gateway creation and cleanup. Local gateways resolve the active model from `NexusModelStorage` at request time, so Model Center activation is reflected without recreating the screen.

Runtime mapping:

- `LITERT_LM` → `ON_DEVICE_LITERT`
- `LLAMA_CPP` → `ON_DEVICE_LLAMA_CPP`
- `OLLAMA_LAN` → network-only; never accepted as an on-device Manager runtime

The registry rejects use after close and closes all managed gateways when the screen is disposed.

Model artifacts use unique immutable filenames, so a re-download cannot overwrite a file that may still be mapped by an active inference engine. Imports are staged to a temporary file and promoted only after copy completion; registry entries are restricted to files inside the app-private model directory, and activation rechecks size and SHA-256. A failed import/download removes its temporary or newly promoted artifact.



## Local-first Council

`NexusLocalCouncilManager` implements the Manager control plane using the currently active on-device model. The model returns a constrained JSON decision:

- `delegate` — one to eight assignments to known, enabled participants
- `ask_user` — request missing information
- `finish` — return the final summary
- `yield_control` — pause execution

The application validates every participant ID and task before dispatch. Model output cannot invoke tools, shell commands, Android APIs, or arbitrary code. The provider SSE transport applies bounded backpressure to the OkHttp callback thread rather than silently dropping generated fragments when a consumer is briefly slower. A terminal delivery failure is surfaced as a stream error.

SSE input lines are capped at 1 MiB and provider error bodies at 2 KiB; model-catalog JSON responses are capped at 1 MiB. Local Manager and executor generation use the shared bounded collector, which cancels the upstream stream once its output budget is exceeded.

OpenAI-compatible, Anthropic, and Gemini gateways now share one bounded SSE transport and the same endpoint security policy. Private HTTP host validation strictly parses IPv4 octets and IPv6 address bytes, preventing hostname strings that merely begin with `fc`/`fd` or contain four numeric fragments from being misclassified as private addresses. The remote Council also caps mission goals at 32,000 characters, individual executor output at 12,000 characters, synthesis context at 110,000 characters, and synthesis output at 16,000 characters; exceeding an output cap cancels the upstream stream and marks the returned text as truncated.

The decision parser enforces an action-specific JSON schema: required fields must be present, unknown fields are rejected, and action text, participant IDs, and task strings must have the expected JSON types and size limits. Each assignment must contain exactly `participantId` and `task`.

`NexusLocalModelParticipantAdapter` provides eight bounded executor roles. In the initial local configuration, the Manager and all eight executors share the active local model. Execution is serialized (`maxParallelism = 1`) to avoid concurrent access to a single native inference context and to limit memory pressure.

AI Studio now selects this nine-agent local execution path when an on-device provider is selected. Remote-provider Council execution remains available through the existing provider routing path.

Manager outcomes are preserved as mission lifecycle states: a request for clarification is stored as `AWAITING_INPUT`, an explicit pause or turn-limit is stored as `INTERRUPTED`, and only an actual execution failure is stored as `FAILED`. This prevents a valid Manager request for more information from being misreported as a model failure.

AI Studio exposes a **Continue with clarification** action for `AWAITING_INPUT` missions. It pre-fills a follow-up prompt with the original task and the Manager's question; the user supplies the missing information and explicitly starts a new mission. The prior mission is retained as history. Interrupted missions are not replayed automatically.

## Evolution routing policy

Approved `ROUTING_POLICY` payloads are parsed by the strict `NexusEvolutionRoutingPolicy` schema and applied by the remote-provider Council path before per-agent saved routes. Supported payload shape:

```json
{
  "version": 1,
  "routes": {
    "research": "OPENAI",
    "analysis": "ON_DEVICE_LITERT"
  },
  "modelOverrides": {
    "research": "gpt-model-id"
  }
}
```

Only the eight built-in Council role IDs and exact `ProviderKind` enum values are accepted. The schema version must be explicitly present as a numeric `1`; route values and model overrides must be JSON strings, and `modelOverrides`, when present, must be an object. Unknown root fields, unknown roles, invalid providers, blank/oversized model IDs, wrong JSON value types, and malformed JSON are rejected both when a proposal is created and again immediately before activation. Endpoints and credentials cannot be stored in a routing policy; they remain in provider settings and the credential vault. New credential records authenticate their secret identifier as AES-GCM additional authenticated data; legacy records are read and migrated in place when possible. Evolution model overrides take precedence over saved per-agent model overrides. When no Evolution route exists for an agent, the saved per-agent route and then the selected default provider are used. Model Center includes a v1 template to reduce manual schema errors.

Evolution storage fails closed: the active-version pointer must be a valid non-negative integer JSON value; malformed pointers are not silently reset to version zero. Before resolving active configuration or rolling back, the executor verifies the full version ancestry, active proposal records, parent links, and SHA-256 checksums for archived payloads. The immutable version record is persisted before the active pointer is moved, preserving the prior active version if the pointer update fails.

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
