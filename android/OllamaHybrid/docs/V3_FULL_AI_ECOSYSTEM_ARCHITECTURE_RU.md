# OllamaHybrid V3 — Full AI Ecosystem Architecture
Версия: 3.0  
Философия: полноценная AI-экосистема  
Hybrid Mesh: отложен; только архитектурная совместимость, без реализации сетевого mesh  
Статус: целевая архитектура и план реализации, не заявление о готовности функций.

## 0. Принятые продуктовые решения

1. OllamaHybrid — самостоятельная Android AI-экосистема, а не только чат-клиент.
2. Телефон — основная платформа и среда исполнения. Android Bridge управляет только этим Android-устройством через разрешённые Android API, app adapters, Shizuku/Root, Accessibility и Vision.
3. Все модели — сменяемые исполнители. Ни одна модель не владеет задачами, историей, памятью, браузером или инструментами.
4. Local-first: локальные модели и приватная обработка — приоритет. Cloud providers подключаются опционально и только с прозрачной передачей контекста. GigaChat — возможный дополнительный provider, не обязательная зависимость.
5. AI Council, Material Library, Watchdog, Android Bridge, Recovery Core и идеи desktop AI-Council переносятся в Android-экосистему с адаптацией к Android. Не переносить управление мышью/Windows: Android Bridge управляет только телефоном.
6. Базовый Council сохраняет знакомую схему Manager Agent + executor agents (исходная концепция — 1 manager и 8 исполнителей), но V3 должна поддерживать конфигурируемые роли/число агентов и ограничивать параллелизм по ресурсам телефона.
7. Universal Internet Access — общий сервис приложения для всех моделей и агентов. Смена модели не сбрасывает browser engine, tabs, cookies, history, downloads или сессию.
8. Hybrid Mesh не входит в V3 implementation scope. Не создавать peer discovery, mesh routing, remote execution, distributed storage или фоновые сетевые агенты сейчас. Оставить чистые интерфейсы provider/worker/storage, чтобы будущая распределённая версия не требовала переписывать domain layer.
9. GitHub хранит исходники и спецификации. APK — только GitHub Releases после настоящей сборки, установки, QA, подписи и SHA-256.
10. Нельзя объявлять функции реализованными до их фактической реализации и тестирования. Текущий Android tree в репозитории неполон; синхронизация полного проекта и воспроизводимая сборка — ранний обязательный этап.

## 1. Архитектурная идея

**OllamaHybrid = Personal AI Operating Environment.**

Единый цикл:
- пользователь создаёт задачу/миссию;
- Task Runtime сохраняет её независимо от модели;
- Cognitive Core определяет стратегию, модели, инструменты и бюджет;
- Council Orchestrator запускает одного или нескольких исполнителей;
- Tools Gateway предоставляет безопасные инструменты: Browser, Material Library, Android Bridge, files, local inference;
- Memory Service получает и выдаёт разрешённый контекст;
- Verification Core проверяет результат и доказательства;
- Result Composer создаёт ответ/артефакт;
- Event Journal и Recovery Core сохраняют ход выполнения и восстанавливаемое состояние.

Главный архитектурный инвариант: **модель предлагает; policy решает, что разрешено; tool исполняет; verifier проверяет; runtime сохраняет.** Ответ модели не является доказательством выполнения действия.

## 2. Слои системы

### L0 — Presentation / Hybrid Universe UI
Compose screens: Command Deck, AI Studio, Council, Cyber Browser, Model Lab, Device Core, Knowledge Vault, Activity Vault, Permission Center, Settings. UI читает immutable state из ViewModel/StateFlow и посылает typed intents. UI не содержит сетевых клиентов, shell, SQL и browser cookie logic.

### L1 — Application / Use Cases
Сценарии: CreateMission, PlanMission, RunMission, PauseMission, ResumeMission, CancelMission, RetryStep, SwitchModel, ImportMaterial, SearchMemory, OpenBrowserSession, ExecuteTool, RequestApproval, RecoverInterruptedWork, ExportArtifact.

### L2 — Domain Core
Независимые модели и правила:
- Mission, MissionStep, Run, AgentProfile, AgentAssignment;
- Provider, ModelDescriptor, ModelCapability, ModelHealth;
- ToolDescriptor, ToolCall, ToolResult, ActionRisk, ApprovalGrant;
- MemoryRecord, ProjectSpace, Material, Citation/Evidence;
- BrowserProfile, BrowserSession, BrowserTab, Download;
- DeviceCapability, PermissionState, AuditEvent;
- Artifact, Checkpoint, RecoveryRecord.

Domain не зависит от Android UI, конкретной LLM SDK, WebView/GeckoView, Shizuku library или базы данных.

### L3 — Runtime Services
- Task Runtime / durable orchestration;
- Cognitive Core / policy-based model routing;
- Council Orchestrator;
- Provider Runtime / local and cloud model adapters;
- Tool Gateway / registry, validation, permission, execution;
- Memory & Retrieval;
- Universal Web Access;
- Material Processing;
- Verification & Evidence;
- Watchdog & Recovery;
- Notifications / voice / scheduled work where Android permits.

### L4 — Adapters
Android native, Room/SQLite, DataStore, SAF, WorkManager, Android Keystore, llama.cpp JNI/NDK, OpenAI-compatible HTTP, optional GigaChat adapter, browser engine adapter, Shizuku/Sui, Root, Accessibility, MediaProjection, OCR and document parsers.

### L5 — Device / OS
Android sandbox, hardware accelerators, storage, network, OS permission flows, OEM background constraints. Root/Shizuku availability is dynamic and never assumed.

## 3. Главные сервисы

### 3.1 Task Runtime — долговечный жизненный цикл миссий

Runtime — владелец миссий, их шагов, статусов, checkpoints, retry policy и итоговых артефактов. Модель не владеет task state.

Mission state machine:
- Draft → Planned → AwaitingApproval → Queued → Running
- Running ↔ WaitingForTool / WaitingForUser / Paused
- Running → Verifying → Completed
- Any active state → Failed / Cancelled / Interrupted
- Interrupted → Recovering → Queued or NeedsUserDecision

Каждый шаг имеет стабильные IDs, dependencies, assigned agent, model/provider selection, input references, timeout, retry budget, idempotency key, output reference and evidence refs.

Runtime обязан:
- сохранять состояние до внешнего side effect;
- делать checkpoint после подтверждённого шага;
- различать timeout, provider error, tool error, user cancellation и permission denial;
- восстанавливать interrupted missions после process death;
- не повторять необратимое действие автоматически, если неизвестно, произошло ли оно;
- поддерживать pause/cancel между шагами и кооперативную отмену активного запроса;
- показывать точное состояние, не рисовать фиктивный процент прогресса.

Для Android длительные операции исполняются с учётом WorkManager/foreground-service правил и ограничений фонового выполнения. Foreground service используется только когда задача действительно требует длительной видимой работы и удовлетворяет требованиям Android.

### 3.2 Cognitive Core — выбор стратегии и модели

Вход: пользовательская цель, контекст проекта, политика приватности, доступные модели, состояние устройства, требуемые инструменты, лимиты бюджета/времени.

Выход: MissionPlan с шагами, зависимостями, ролями, исполнителями и проверками.

Режимы:
- Auto: система предлагает план и маршрутизацию;
- Manual: пользователь выбирает модель/агентов;
- Local Only: запрещена отправка содержимого облачным провайдерам;
- Cloud Allowed: облако доступно с явным disclosure;
- Council: параллельные независимые исполнители;
- Single Model: один исполнитель.

Routing не должен основываться только на названии модели. Учитывать фактические capabilities, доступность, контекстное окно (если известно), tool calling, мультимодальность, локальность, память устройства, latency и пользовательские ограничения.

Fallback:
1. проверить endpoint/model health;
2. повторить только безопасный запрос с backoff;
3. выбрать совместимую модель по policy;
4. сохранить тот же mission/step ID и передать контекст через ссылки на данные;
5. если семантика/контекст несовместимы — запросить решение пользователя, не сбрасывать диалог.

Не обещать точность маршрутизации или «самую умную модель» без тестов. Результат модели — недоверенный ввод, проходящий schema validation.

### 3.3 Provider Runtime — единый слой моделей

Интерфейс ModelProvider:
- listModels / inspectModel;
- healthCheck;
- generate / stream;
- cancel;
- supportedCapabilities;
- token/usage telemetry, если provider её предоставляет;
- structured tool-call parsing.

Адаптеры:
- LocalRuntimeAdapter (цель: llama.cpp Android NDK/JNI, приоритет arm64-v8a);
- OpenAICompatibleAdapter;
- provider-specific adapters (GigaChat optional и другие по мере необходимости).

Model Registry — источник правды для установленных моделей, конфигурации endpoints, capabilities и health. Разделять состояния:
- provider configured;
- endpoint reachable;
- model installed/listed;
- inference smoke test passed.

Model Catalog / Model Download Manager:
- каталог моделей с фильтрами по размеру, quantization, architecture, context и локальному запуску;
- загрузка с resume, pause, cancellation, checksum verification и дисковым резервом;
- проверка свободной памяти/хранилища до загрузки;
- явная лицензия, происхождение и формат;
- импорт GGUF из SAF;
- запрет автоматического запуска неизвестного файла;
- модель можно выгрузить из RAM, не удаляя её и не теряя чаты.
Не обещать запуск модели только по размеру файла: учитывать RAM, runtime, context/KV cache, GPU backend и Android OEM.

### 3.4 Council Orchestrator — коллективная работа

Сохранить модель Manager + executor agents, изначально 8 исполнителей, но сделать роли и количество конфигурируемыми. На телефоне действует лимит параллельности, основанный на памяти, нагреве, батарее и возможностях runtime. Для локальных тяжёлых моделей очередь может быть последовательной.

Типовые роли:
- Planner / Manager;
- Researcher;
- Analyst;
- Critic / Verifier;
- Browser Agent;
- Document Agent;
- Android Operator;
- Synthesizer.

Роль — шаблон поведения и разрешений, не отдельная модель. Разные агенты могут использовать один provider, а один агент — менять модель с записью события.

Council lifecycle:
Plan → Assign → Execute → Collect → Cross-check → Resolve conflicts → Synthesize → Verify.

Каждый agent result включает status, content reference, tool calls, evidence refs, elapsed time (если измерен), error and confidence metadata (если поддерживается). Confidence от модели не считается объективной вероятностью.

Watchdog:
- heartbeat/timeout;
- duplicate request prevention;
- bounded retries/backoff;
- stalled-agent detection;
- cancellation propagation;
- recovery from app process death;
- журнал всех переходов.
Не перезапускать шаг с необратимым side effect без idempotency check или участия пользователя.

### 3.5 Tool Gateway — единая безопасная точка инструментов

Все AI-агенты используют один Gateway. Запрещено подключать модель напрямую к shell, browser internals, файловой системе или Android APIs.

Tool call pipeline:
1. Parse and validate schema.
2. Resolve tool ID/version.
3. Check capability and current permission.
4. Apply privacy and risk policy.
5. Request approval if required.
6. Create durable execution record.
7. Route to adapter/transport.
8. Execute with timeout, bounded output and cancellation.
9. Verify actual outcome.
10. Persist audit event and return typed result.

Каждый tool описывает:
- stable ID and version;
- typed input/output schema;
- required capabilities;
- risk class: ReadOnly / Reversible / Sensitive / Destructive;
- data classification;
- idempotency behavior;
- timeout and output limits;
- whether human approval is required;
- supported adapter/transport.

ApprovalGrant создаётся приложением, привязывается к одному request ID + tool ID + конкретным параметрам/summary, имеет короткий срок действия и одноразовое использование. Модель не может выдать себе grant или изменить параметры после подтверждения.

### 3.6 Universal Web Access — общая сетевая среда

BrowserSessionStore и WebAccess API принадлежат приложению, не модели. Provider switch не очищает вкладки, cookies, history, bookmarks, downloads или page state.

Модули:
- BrowserEngineAdapter (WebView/GeckoView decision after technical/license review);
- BrowserSessionStore;
- Tab/History/Bookmark/Download repositories;
- Page Interaction API (DOM/semantic actions supported by engine);
- Browser Agent tools;
- Human Handoff;
- Approval Gate;
- Web privacy boundary.

Обычные публичные HTTP/HTTPS сайты не ограничивать внутренним allowlist. Отдельно защищать localhost/private network/system URIs. CAPTCHA, OTP, authentication and anti-bot checks передаются пользователю; не обходить защиту. Платежи, покупки, публикации, сообщения, удаления и иные side effects требуют явного подтверждения. Cloud provider disclosure показывается до отправки содержимого страницы/документа наружу.

### 3.7 Memory & Knowledge — единая память и библиотека

Разделить память на слои:
1. Session memory — текущая беседа/миссия.
2. Project memory — контекст конкретного проекта.
3. User-approved long-term memory — устойчивые факты и предпочтения.
4. Episodic memory — история выполненных миссий и решений.
5. Knowledge Library — документы и материалы с источниками.
6. Operational state — состояние вкладок, загрузок, моделей и незавершённых задач (не смешивать с семантической памятью).

Каждая запись имеет provenance/source, timestamp, scope, sensitivity, retention, confidence/provenance status and delete policy. Система должна отличать подтверждённый факт, вывод модели, пользовательскую гипотезу и устаревшие сведения.

Retrieval pipeline:
- query understanding;
- scope/permission filtering;
- lexical search;
- optional embeddings/vector retrieval;
- reranking;
- context budget selection;
- citation/source references.
Не добавлять в prompt все найденные документы без ограничения. Пользователь может просмотреть, исправить, экспортировать и удалить данные в рамках политики приложения.

Material Library:
- originals in app-private storage;
- SAF import;
- metadata/checksum;
- parser pipeline;
- indexed text/chunks;
- citations to source file/page/section;
- lexical search first; embeddings later.
Текущая база форматов TXT/MD/JSON/CSV/XML/HTML/Office Open XML. PDF extraction, OCR, audio transcription and video frame sampling — отдельные реализации и acceptance tests, не считать существующими.

### 3.8 Verification Core — проверка результата

Verification не должен быть ещё одним словесным ответом той же модели без доказательств.

Типы проверок:
- schema and completeness;
- arithmetic / deterministic calculations;
- source URL and citation checks;
- cross-agent contradiction detection;
- file integrity and output openability;
- tool postcondition (действие реально выполнено);
- user acceptance for subjective deliverables.

Evidence model:
- EvidenceItem: source type, URI/file reference, page/line/DOM location, capture time, excerpt/hash;
- Claim: text, evidence refs, status (supported / contradicted / unverified);
- VerificationRun: checks, outcomes, errors.

Если доказательств нет, пометить claim как unverified. Не придумывать источники и не маскировать ошибки уверенным языком.

### 3.9 Android Command Layer — управление телефоном

Android Bridge остаётся отдельной подсистемой. Приоритеты транспорта: Native Android API → Intent/app adapter → Shizuku/Root where permitted → Shizuku ADB → Accessibility → Vision/Gesture, с фактической capability check для каждого инструмента.

Поддерживать Standard без Root. Shizuku/Root — дополнительные профили, требующие явных системных подтверждений. Root не отменяет SELinux, encryption, OEM и platform restrictions. LLM вызывает только typed tools. Никаких произвольных shell-команд, скрытых действий, обхода защиты или управления чужими устройствами.

Действия телефона ограничены текущим устройством: запуск приложений, доступные файлы, настройки и UI actions в рамках реально предоставленных разрешений. Accessibility/Vision не гарантируют контроль secure windows или приватных данных других приложений.

### 3.10 Resilience / Watchdog / Recovery

Durable state хранится в локальной БД. Event journal — append-oriented журнал переходов миссии и tool execution. Checkpoints фиксируют безопасные границы выполнения.

После аварийного завершения:
- восстановить DB и проверить целостность;
- найти незавершённые миссии;
- сверить статус внешних операций, где возможно;
- безопасные идемпотентные шаги поставить в очередь;
- неопределённые side effects пометить NeedsUserDecision;
- сохранить черновики, результаты, ссылки на браузерные сессии и материалы.

Recovery Core остаётся защищённым от AI-editable workspace. Обновление приложения и саморедактирование не должны иметь права изменять recovery/keystore/signing components. Автоматический rollback не должен обещать восстановление установочного APK без отдельного проверенного update/installer workflow.

## 4. Данные и persistence

Рекомендуемая локальная persistence strategy:
- Room/SQLite: missions, steps, runs, agents, tool executions, approvals, events, materials metadata, memory metadata, bookmarks/history metadata, downloads, model registry.
- App-private files: imported originals, model files, generated artifacts, thumbnails, checkpoints.
- Android Keystore: encryption keys and protected secrets.
- DataStore: preferences and lightweight configuration.
- WorkManager: deferrable/retryable background work where Android policy allows.
- Encrypted storage for API credentials and sensitive records; never log keys/cookies.

Каждая долгоживущая сущность имеет UUID, createdAt, updatedAt, schemaVersion. Миграции БД обязательны. Large binary data не хранить как blobs в Room; хранить URI/path reference + integrity metadata. File deletion and retention policies должны учитывать references from missions and library.

## 5. Основные domain contracts

Примерно следующие интерфейсы (названия концептуальные, точные пакеты уточнить при реализации):

- MissionRepository: create/get/list/updateState/appendEvent/checkpoint.
- MissionPlanner: plan(goal, context, policy) → MissionPlan.
- ModelRouter: select(requirements, policy, deviceState) → ModelSelection.
- ModelProvider: health/list/generate/stream/cancel.
- CouncilRuntime: execute(plan)/pause/resume/cancel.
- ToolRegistry: resolve(toolId, version).
- ToolExecutor: execute(validatedRequest, approvalContext) → ToolResult.
- ApprovalManager: request/approve/deny/expire/consume.
- MemoryRepository + RetrievalService.
- MaterialRepository + ParserRegistry + Indexer.
- WebAccessService + BrowserSessionRepository.
- AndroidBridge + CapabilityRegistry.
- VerificationService.
- RecoveryService + EventJournal.
- ArtifactRepository.

Интерфейсы не должны возвращать Android View, raw WebView, arbitrary shell strings or provider-specific response types в Domain.

## 6. Поток выполнения миссии

1. Command Deck получает цель и создаёт Draft.
2. Task Runtime сохраняет запрос и генерирует mission ID.
3. Cognitive Core определяет privacy scope, сложность, tools и нужные capabilities.
4. Planner создаёт MissionPlan с DAG шагов и лимитами.
5. UI показывает план; рискованные действия до старта требуют согласия.
6. Runtime назначает роли/модели через ModelRouter.
7. Council выполняет шаги, Tool Gateway обрабатывает tool calls.
8. Memory retrieval добавляет только разрешённый контекст и ссылки на источники.
9. Watchdog следит за timeout, retries и process interruptions.
10. Verification проверяет claims, файлы и side effects.
11. Result Composer формирует ответ/файлы/таблицы с источниками.
12. Runtime сохраняет Completed или NeedsUserDecision, артефакты и event trail.

## 7. Безопасность и privacy model

- Local Only — жёсткая policy: никакой передачи текста/файлов cloud provider.
- Cloud mode — явный provider label и предупреждение о том, какие данные покинут устройство.
- Data minimization: отправлять только необходимые фрагменты, не всю библиотеку или историю.
- Prompt injection: содержимое страниц/файлов считается недоверенными данными, а не инструкциями policy/system.
- Tool isolation: модели не получают произвольный доступ к файловой системе, shell, cookies или Android APIs.
- Approval binding: согласие относится к конкретному действию и параметрам.
- Audit: инициатор, mission/step/tool IDs, transport, approval, timestamp, outcome; исключать пароли, токены, cookies и чувствительные payloads.
- Secrets via Android Keystore; redaction logs.
- Private/local network access — отдельная policy и consent.
- Import/export/delete controls для пользовательских данных.
- App integrity and signed release checks.
- Least privilege; отсутствие Root не ломает основной сценарий.

## 8. Наблюдаемость и UI

System Observatory показывает только измеренное:
- provider/model health;
- текущие миссии и шаги;
- активные агенты;
- tool executions and approvals;
- memory/storage footprint;
- model load/unload;
- battery/thermal/CPU/GPU metrics only where APIs provide them;
- error and recovery events.

У каждого показателя — source and last updated timestamp. Неизвестные значения показываются как Unknown/Not available.

V3 UI spaces:
- Command Deck: создать/контролировать миссии, системная сводка;
- AI Studio: прямой диалог и контекст;
- Council: граф агентов, события, инспектор;
- Cyber Browser: общий browser workspace;
- Model Lab: каталог, загрузки, installed models, providers;
- Knowledge Vault: документы, память, поиск;
- Device Core: bridge, capabilities, diagnostics;
- Activity Vault: миссии, история, downloads, artifacts;
- Trust Console: approvals, permissions, audit;
- Settings: privacy, policies, providers, limits, appearance.

## 9. Hybrid Mesh — позже, не V3 scope

Сейчас не реализовывать:
- peer discovery;
- device pairing and mesh identity;
- remote tool execution;
- distributed inference;
- distributed storage;
- background relay/forwarding;
- cross-device task scheduler.

Чтобы будущее расширение было возможным, доменные интерфейсы не должны предполагать, что любой executor физически находится в Android-процессе. Но в V3 допустим только LocalDeviceExecution и configured CloudProviderExecution. Не создавать пустые сетевые службы и не добавлять лишние permissions заранее.

## 10. Рекомендуемая структура модулей

- app/
- core/domain/
- core/data/
- core/database/
- core/security/
- core/telemetry/
- core/navigation/
- core/runtime/
- core/recovery/
- core/verification/
- core/artifacts/
- feature/commanddeck/
- feature/aistudio/
- feature/council/
- feature/browser/
- feature/modellab/
- feature/knowledge/
- feature/devicecore/
- feature/activity/
- feature/trust/
- feature/settings/
- integrations/provider-api/
- integrations/provider-local-llamacpp/
- integrations/provider-gigachat/ (optional)
- integrations/browser-engine/
- integrations/android-native/
- integrations/android-shizuku/
- integrations/android-root/
- integrations/android-accessibility/
- integrations/material-parsers/

Не создавать все Gradle modules одномоментно до синхронизации текущего проекта. Начать с package boundaries внутри существующего приложения; выделять Gradle modules по фактическим зависимостям и build time.

## 11. Этапы реализации и приёмка

### V3.0 — Reproducible Foundation (обязательный старт)
- Синхронизировать полный Android source tree в GitHub.
- Добавить Gradle wrapper, проверить SDK/NDK/toolchain and reproducible build.
- Создать stable tag/backup baseline до крупных изменений.
- Собрать, установить и запустить текущий app; зафиксировать реальный статус.
- Ввести architecture decision records и versioned schema migrations.
Acceptance: чистый checkout собирается на документированной среде; APK устанавливается; baseline tests проходят.

### V3.1 — Durable Task Runtime
- Room schema для missions/steps/events/checkpoints.
- State machine, process-death recovery, cancellation, retry policy.
- Command Deck mission list and mission detail.
Acceptance: задача восстанавливается после force-stop/relaunch; side effects не повторяются слепо.

### V3.2 — Provider Runtime + Model Lab
- ModelProvider contract and health states.
- Local llama.cpp NDK/JNI runtime and supported ABI checks.
- OpenAI-compatible adapter; optional GigaChat provider.
- Model catalog/download manager, resume/checksum/storage checks.
Acceptance: локальная модель реально загружается/выгружается и генерирует; cloud adapter separately tested; provider switch does not lose mission/chat.

### V3.3 — Cognitive Core + Council
- Policy and model routing.
- Manager + configurable executor roles.
- bounded concurrency, watchdog, durable retries.
- tool call events and synthesis.
Acceptance: one multi-step task survives model failure; agent statuses derive from backend events.

### V3.4 — Tool Gateway + Verification + Security
- Unified registry, typed schemas, risk policy, approval binding, audit.
- Verification of tool postconditions and source evidence.
- Prompt injection isolation and local-only policy.
Acceptance: model cannot invoke unregistered tools or forge approval; destructive actions require per-operation confirmation.

### V3.5 — Knowledge & Memory
- Material metadata, parser registry, lexical retrieval and citations.
- project/session memory and user-managed long-term memory;
- embeddings/vector retrieval after baseline correctness and performance.
Acceptance: answers cite actual imported material; scopes and deletion rules work.

### V3.6 — Universal Web Access
- Browser engine adapter, persistent sessions, tabs, history, downloads.
- common WebAccess tools for all providers and Council.
- human handoff and risk gates.
Acceptance: switch models without losing session; browser agent and human share same tab state; sensitive actions require confirmation.

### V3.7 — Android Bridge & Trust Console
- Native adapters, Shizuku/Sui, Direct Root, Accessibility and Vision in stages.
- Capability registry and Permission Center.
- audit and approval UI.
Acceptance: Standard mode works without Root; unavailable capability returns explicit reason; no arbitrary shell from LLM.

### V3.8 — Resilience, Observatory, release hardening
- crash recovery, integrity manifests, backups, export/delete;
- measured telemetry, privacy controls, battery/thermal handling;
- UI performance, accessibility, device matrix.
Acceptance: mission recovery, DB migrations, security, UI, provider and device tests pass; APK installed on physical target device; SHA-256 generated; release notes and GitHub Release published.

Hybrid Mesh is a later V4+ project, after V3 reliability and security acceptance.

## 12. Test strategy

- Unit: state machines, routing policy, schema validation, retry/idempotency, memory scope, permission policy.
- Contract: every provider adapter and tool adapter against stable interface tests.
- Integration: Room migrations, WorkManager constraints, local runtime lifecycle, browser session persistence, Council recovery.
- Security: forged approvals, prompt injection, path traversal, secret leakage, private network access, tool argument tampering, log redaction.
- UI: Compose semantics, TalkBack, large text, Static Mode, narrow phone/tablet, offline/error/empty/permission states.
- Device: low RAM, thermal throttling, low storage, battery saver, OEM background restrictions, process death, reboot, permission revocation, Root/Shizuku absent and present.
- Release: clean checkout build, install, smoke test, signing verification, SHA-256, rollback plan.

## 13. Текущий фактический baseline и ограничения

На момент подготовки архитектуры:
- Android scaffold v0.5.0; Kotlin/Compose, minSdk 26, compileSdk 36, Java 17.
- В исходной реализации есть базовый Council orchestrator/shared transcript/watchdog scaffold, OpenAI-compatible HTTP adapter, Material Library import/indexing for text and Office Open XML, Accessibility service and recovery snapshots.
- Начальные Bridge contracts/tool registry/risk gate/Root UID probe добавлены.
- llama.cpp JNI/local inference runtime отсутствует; 127.0.0.1:8080 — только конфигурационный endpoint, пока runtime не подключён.
- Full Shizuku/Sui and Direct Root service, Permission Center UI, complete Android tool calling, robust task persistence, production model downloads, PDF/OCR/audio/video pipelines, complete Browser workspace and V3 UI are not confirmed implemented.
- GitHub Android folder is not yet a full standalone buildable project mirror. Gradle wrapper/SDK build environment and physical-device APK verification are not confirmed.
- Статические проверки и isolated Kotlin/JVM smoke tests для раннего Bridge foundation не заменяют Gradle build/device QA.

Эта спецификация описывает целевое состояние и последовательность реализации. Каждый этап должен обновлять статус: Planned → In Progress → Implemented → Tested on Device → Released. Только последний проверенный статус показывается пользователю как готовая функция.

## 14. Evolution Core — саморазвитие и оптимизация

V3 включает отдельный cross-cutting Evolution Core. Подробная спецификация: [Evolution Core — архитектура саморазвития и самооптимизации](EVOLUTION_CORE_ARCHITECTURE_RU.md).

Evolution Core связывает три контура:
1. **Intelligence Evolution** — улучшение стратегий Cognitive Core и Council: выбор минимально достаточной модели, числа агентов, последовательности шагов, инструментов, кэша и retrieval.
2. **Runtime Evolution** — оптимизация CPU/RAM, задержек, сетевого трафика, I/O, тепловой нагрузки и доступных оценок энергопотребления.
3. **Software Evolution** — анализ исходников, создание patch в изолированной рабочей копии, сборка, тесты, benchmark, review и контролируемый rollout.

Обязательный цикл: Observe → Analyze → Propose → Experiment → Verify → Approve/Publish → Monitor → Rollback if needed.

**Новый архитектурный инвариант:** минимально необходимые ресурсы для наилучшего проверенного результата. Качество, безопасность, приватность и надёжность — жёсткие ограничения, а не метрики, которыми можно пожертвовать ради скорости.

Local AI и AI Council используют общий Tool Gateway. MCP является поддерживаемым протоколом интеграции tools/resources, но не обходит внутреннюю валидацию, permissions, risk policy и approvals. Playwright применяется только в совместимой browser environment; он не заменяет Android Bridge. Android BrowserEngineAdapter выбирает WebView/GeckoView после технической проверки.

Evolution Core начинает работу в режиме Observe. Затем включаются Recommend и ограниченный Controlled Auto только для заранее разрешённых низкорисковых стратегий. Автономное изменение исходного кода всегда выполняется в sandbox/ветке; Recovery Core, ApprovalManager, audit trust boundary, signing keys и update verification защищены от AI-editable workspace.

Изменение весов моделей, бесконтрольное переписывание приложения и автоматическая публикация APK не входят в V3. Любая оптимизация принимается только по воспроизводимым benchmark и проверкам, с версионированием доказательств и rollback. Hybrid Mesh остаётся V4+.
