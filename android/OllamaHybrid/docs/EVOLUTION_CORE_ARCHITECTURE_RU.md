# OllamaHybrid — Evolution Core: архитектура саморазвития и самооптимизации

Версия: 1.0  
Целевая линия: V3 Full AI Ecosystem  
Статус: архитектурная спецификация; не означает, что перечисленные сервисы уже реализованы.  
Принцип: **лучший проверенный результат при минимально необходимом расходе ресурсов**.

## 0. Назначение

Evolution Core — надсистема оптимизации OllamaHybrid. Она улучшает не только ответы моделей, но и способы выполнения задач, работу runtime и, в отдельном защищённом контуре, исходный код приложения.

Система должна стремиться к минимально необходимым затратам времени, энергии, памяти, вычислений, сетевого трафика и денежных средств при сохранении или повышении качества, надёжности, приватности и безопасности результата.

Evolution Core не является ещё одной LLM и не заменяет Cognitive Core, Council или Task Runtime. Это управляющий контур, который:
1. наблюдает за реальными выполнениями;
2. собирает измеримые показатели и подтверждённые исходы;
3. находит повторяющиеся потери и узкие места;
4. формирует гипотезы оптимизации;
5. проверяет их в безопасной среде;
6. принимает улучшения только при доказанном выигрыше;
7. сохраняет версию стратегии, доказательства и возможность отката.

**Ключевой инвариант:** система не объявляет себя улучшенной по собственному мнению модели. Улучшение существует только после измерения и проверки.

## 1. Три контура эволюции

### 1.1 Intelligence Evolution — развитие стратегий решения задач

Оптимизирует поведение Cognitive Core и Council:
- выбор минимально достаточной модели;
- выбор количества и ролей агентов;
- выбор последовательного или параллельного выполнения;
- разбиение задачи на шаги;
- сокращение лишних tool calls;
- повторное использование уже полученных результатов и контекста;
- выбор retrieval-стратегии и размера контекста;
- адаптация retry/backoff;
- выбор локального или облачного исполнителя в соответствии с политикой приватности;
- применение проверенных шаблонов для повторяющихся классов задач.

Обучение в V3 — прежде всего накопление проверенных стратегий и статистики, а не неконтролируемое изменение весов LLM. Fine-tuning/online weight training не входит в базовый scope: это отдельный исследовательский этап с датасетами, лицензиями, оценкой и возможностью отката.

### 1.2 Runtime Evolution — развитие производительности приложения

Оптимизирует само выполнение:
- CPU/GPU/NPU time, если доступна телеметрия;
- RAM, heap, native memory и пиковые аллокации;
- энергопотребление и расход батареи по доступным системным оценкам;
- тепловое состояние и throttling;
- сетевые запросы, байты и повторные загрузки;
- дисковый ввод-вывод;
- задержки UI и фоновых задач;
- кэширование, batching, lazy loading и concurrency;
- работу с базой данных и индексами;
- управление жизненным циклом локальных моделей.

Нельзя выдавать приблизительную оценку за точное измерение джоулей. Каждая метрика хранит источник, метод, точность и ограничения. Если Android/OEM не предоставляет достоверную детализацию на процесс, использовать прокси-метрики и явно помечать их.

### 1.3 Software Evolution — контролируемое развитие исходного кода

Отдельный контур разработки:
- анализ исходников и зависимостей;
- выявление дефектов, узких мест и архитектурного долга;
- генерация patch/diff в отдельной рабочей копии;
- сборка и тестирование;
- benchmark до/после;
- проверка безопасности, лицензий и миграций;
- подготовка релизного кандидата;
- контролируемое применение и rollback.

Модель не имеет прямого права переписывать установленное приложение, signing keys, Recovery Core, Approval Manager, audit journal или собственные ограничения безопасности. Software Evolution может только предложить patch для защищённого review pipeline.

## 2. Место в общей архитектуре

Evolution Core располагается над Cognitive Core и использует общие сервисы, но не получает обходных полномочий.

```
                    USER / MISSION
                          |
                    Task Runtime
                          |
                 Cognitive Core / Planner
                          |
        +-----------------+------------------+
        |                 |                  |
     Local AI         AI Council        Cloud Providers
        |                 |                  |
        +-----------------+------------------+
                          |
                 Unified Tool Gateway
             / MCP / Browser / Android / Data
                          |
                 Verification Core
                          |
                 Mission Outcome
                          |
                 Evolution Core
          /               |                 \
 Intelligence         Runtime            Software
 Evolution            Evolution          Evolution
          \               |                 /
                  Evidence & Benchmarks
                          |
                 Strategy Registry
                          |
             Cognitive Core (policy-gated)
```

Evolution Core не исполняет действия вместо Task Runtime. Он анализирует завершённые/достаточно наблюдаемые миссии и публикует проверенные рекомендации в Strategy Registry. Cognitive Core применяет их только при совпадении контекста, версии, ограничений и confidence threshold.

## 3. Взаимодействие Local AI и AI Council

### 3.1 Local-first маршрутизация

Локальная модель — первый кандидат для задач, которые она способна решить с требуемым качеством и в пределах ресурсов устройства. Но Local-first не означает Local-only и не означает запуск локальной модели любой ценой.

Cognitive Core оценивает:
- требуемые capabilities;
- фактический health и smoke-test статус модели;
- контекстное окно и формат tool calling;
- RAM/VRAM/shared memory и свободное хранилище;
- заряд, thermal state и режим энергосбережения;
- приватность и разрешённость cloud;
- deadline и бюджет;
- историю проверенного качества на данном классе задач.

Если локальный исполнитель не проходит пороги, система может:
1. разбить задачу на меньшие локальные шаги;
2. выбрать более лёгкую локальную модель;
3. вызвать Council;
4. запросить облачный provider, если Cloud Allowed и пользовательская политика это разрешает;
5. остановиться и объяснить ограничение, если безопасной стратегии нет.

### 3.2 Council как коллективный оптимизатор

Council не обязан запускать Manager + 8 исполнителей на каждую миссию. Это верхняя конфигурация/наследуемая концепция, а не фиксированная нагрузка.

Рекомендуемые роли:
- Planner/Manager — план и распределение;
- Researcher — поиск и источники;
- Analyst — структурный анализ;
- Critic/Verifier — независимая проверка;
- Browser Operator — веб-инструменты;
- Document Agent — документы и артефакты;
- Android Operator — типизированные действия телефона;
- Synthesizer — итог.

Evolution Core анализирует, какие роли реально добавили полезный вклад. Если в классе задач два агента стабильно дают эквивалентное или лучшее качество, стратегия может предпочесть двух агентов. Критик/верификатор не исключается там, где он является обязательным контролем риска или качества.

### 3.3 Разделение полномочий

- Model: предлагает план, текст и typed tool call.
- Cognitive Core: выбирает стратегию и исполнителей.
- Council: координирует агентов и разрешает разногласия.
- Tool Gateway: валидирует разрешения, риск и схему.
- MCP adapter: предоставляет совместимый протокол инструментов/ресурсов.
- Browser adapter: выполняет web actions через поддерживаемый движок.
- Android Bridge: выполняет только разрешённые действия на текущем Android-устройстве.
- Verification Core: проверяет постусловия и доказательства.
- Evolution Core: анализирует метрики и предлагает/публикует проверенные оптимизации.

Ни одна модель не может напрямую вызывать shell, обходить Gateway или самостоятельно создавать ApprovalGrant.

## 4. Unified Tool Gateway, MCP и Browser Automation

### 4.1 MCP — интерфейс интеграции, не корневой диспетчер безопасности

MCP поддерживается как один из протоколов подключения внешних tools/resources/prompts. Внутренний Tool Gateway остаётся владельцем:
- tool registry и версий;
- schema validation;
- capability checks;
- privacy/data classification;
- ActionRisk;
- ApprovalManager;
- execution journal;
- timeout/output limits/cancellation;
- audit и postcondition verification.

MCP server/client adapters не получают права обходить эту политику. Внешний MCP server считается недоверенной интеграцией до регистрации, проверки происхождения, разрешений и пользователя.

### 4.2 Playwright и Android browser

Playwright — средство browser automation, а не замена MCP и не универсальный Android device bridge. Использовать его там, где поддерживаемая среда реально предоставляет совместимый browser/runtime. Для встроенного Android browser определить BrowserEngineAdapter с WebView или GeckoView после отдельной проверки возможностей, ограничений и лицензий.

Общий Browser Agent API должен предоставлять моделям одни и те же типизированные операции независимо от provider:
- open/navigate;
- list/read tabs;
- inspect visible page and supported DOM/semantic tree;
- click/fill/select/scroll;
- wait for navigation/state;
- download and manage downloads;
- bookmarks/history;
- handoff to human.

Cookie/session store принадлежит приложению и не должен сбрасываться при смене модели. CAPTCHA, OTP, login challenges и anti-bot проверки передаются пользователю. Платежи, покупки, публикации, отправка сообщений, удаления и другие необратимые side effects требуют подтверждения.

### 4.3 Универсальность инструментов для моделей

Local AI и облачные модели используют общий Tool Gateway. Адаптер provider преобразует tool-call формат конкретной модели в общий ToolCall envelope. Capability mismatch обнаруживается до выполнения. Модель не должна получать секреты, cookies, токены или произвольные внутренние данные напрямую.

## 5. Цикл самооптимизации

### 5.1 Наблюдение

Для каждой миссии сохраняются:
- mission/step/run IDs;
- класс задачи и признаки сложности;
- выбранная стратегия и версии компонентов;
- provider/model и фактический health;
- tool IDs и количество вызовов;
- latency по этапам;
- tokens/usage, если provider сообщает;
- CPU/RAM/thermal/battery/network proxies, если доступны;
- retry, timeout, failures;
- результат Verification Core;
- пользовательская оценка/коррекция, если предоставлена;
- ссылки на evidence, без дублирования чувствительного содержимого.

Сырые логи не должны без необходимости содержать секреты, персональные данные, полные страницы или содержимое документов. Хранить минимально необходимые агрегаты и ссылки с политикой retention.

### 5.2 Анализ и гипотеза

Performance Observatory ищет:
- повторные одинаковые обращения;
- избыточные шаги;
- ожидания без полезной работы;
- слишком тяжёлую модель для простой задачи;
- неоправданную параллельность;
- частые fallback/retry;
- неэффективные retrieval/context budgets;
- медленные инструменты;
- регрессии по версиям приложения/провайдера.

Гипотеза оформляется как OptimizationProposal:
- target component/strategy;
- observed problem;
- proposed change;
- expected benefit;
- risk and privacy impact;
- applicability conditions;
- benchmark plan;
- rollback plan;
- evidence references.

### 5.3 Эксперимент

Новая стратегия сначала исполняется в одном из режимов:
1. Offline replay на обезличенных/синтетических задачах.
2. Shadow mode: новая стратегия рассчитывает план, но не исполняет side effects.
3. Canary: ограниченная доля низкорисковых задач.
4. A/B comparison: только при сопоставимых входных условиях и допустимой политике.
5. Manual approval: для изменения runtime/code/security-sensitive behavior.

Нельзя экспериментировать на платежах, публикациях, удалениях, системных настройках и иных чувствительных действиях без отдельного подтверждения. Нельзя намеренно ухудшать пользовательский результат ради эксперимента.

### 5.4 Критерий принятия

Использовать constrained multi-objective optimization, а не одну метрику.

Обязательные ограничения:
- качество не ниже заданного порога;
- reliability не хуже допустимого порога;
- безопасность и приватность не ухудшены;
- обязательные проверки и approvals сохранены;
- нет новых критических дефектов;
- статистическая/повторная уверенность достаточна для данного риска.

Среди допустимых стратегий выбирать компромисс по времени, энергии, памяти, сети и стоимости. Не складывать несопоставимые единицы в непрозрачный «магический score». Хранить исходные метрики, нормализацию, веса, версию objective policy и причину выбора.

Для обычной задачи пользователь может задать приоритет: Balanced, Fast, Energy Saver, Local/Private, Quality First. Safety/permission constraints не могут быть понижены этими режимами.

### 5.5 Публикация стратегии

Принятая оптимизация сохраняется как версионированная StrategyRecord:
- strategy_id/version;
- task class and applicability predicate;
- required capabilities;
- provider/model constraints;
- policy/privacy constraints;
- expected metric ranges;
- benchmark evidence;
- sample size and confidence;
- created/approved timestamp;
- status: Candidate, Shadow, Canary, Active, Deprecated, RolledBack.

Стратегии не должны бесконтрольно переноситься между несопоставимыми устройствами, моделями, Android версиями или наборами данных.

## 6. Resource Governor — бюджет ресурсов

Перед запуском Cognitive Core получает ResourceSnapshot:
- available RAM / memory pressure;
- model memory estimate and loaded state;
- battery level and charging state;
- thermal status;
- CPU availability/load proxy;
- GPU/NPU capability if discoverable;
- free storage;
- network type/connectivity/metered status;
- foreground/background state;
- user-selected energy/performance mode.

Resource Governor выдаёт ExecutionBudget:
- max concurrent agents;
- max local model residency / model switching policy;
- context/token budget;
- tool-call budget;
- time/deadline budget;
- download/network budget;
- background eligibility;
- thermal pause/defer policy.

Показатели устройства являются моментальным снимком и могут устареть. Runtime должен перепроверять их перед тяжёлым действием. Не гарантировать доступ к GPU/NPU только по наличию аппаратного блока: нужен runtime capability probe и smoke test.

## 7. Память саморазвития

Learning Memory отделена от обычной беседы и от пользовательской долгосрочной памяти.

Типы записей:
- Strategy outcome — какая стратегия сработала для какого класса задач;
- Failure pattern — повторяющаяся причина ошибки;
- Tool performance — latency/reliability по tool/version;
- Model performance — фактические результаты на benchmark suite;
- Optimization evidence — доказательства принятого изменения;
- User correction — явная поправка пользователя;
- Device profile — только необходимые технические параметры устройства.

Каждая запись имеет provenance, timestamp, scope, device/app/provider versions, confidence, retention, sensitivity и возможность удаления. Система не превращает неподтверждённый вывод модели в факт. Не использовать приватные пользовательские документы для обучения/оптимизации без явного разрешения и понятной политики.

## 8. Software Evolution Lab — безопасное изменение кода

### 8.1 Границы доверия

AI-editable workspace:
- обычные feature modules;
- тесты;
- документация;
- профилирование и benchmark harness;
- адаптеры, если они не принадлежат trust boundary.

Protected core — не редактируется автономным AI patcher:
- Recovery Core и rollback механизм;
- ApprovalManager и policy enforcement;
- signing keys/keystore;
- immutable audit anchor;
- update verifier;
- permission boundary;
- механизм проверки целостности и доверенной загрузки.

Изменения protected core требуют отдельного ручного инженерного процесса и внешнего review; в автономный pipeline они не входят.

### 8.2 Patch pipeline

1. Snapshot: зафиксировать базовый commit, tree hash, зависимости и baseline metrics.
2. Analyze: статический анализ, тесты, профилировщик, dependency/license audit.
3. Propose: сформировать patch с ограниченным scope и обоснованием.
4. Isolate: применить patch в отдельной ветке/worktree/sandbox.
5. Validate: форматирование, compile, unit/contract/integration/security tests.
6. Benchmark: сравнить на одном и том же benchmark suite.
7. Review: независимые Council reviewers; AI review не заменяет обязательный human approval для рискованных изменений.
8. Stage: создать release candidate, подписать доверенным release process.
9. Rollout: пользователь подтверждает установку/обновление по политике приложения.
10. Verify/Rollback: проверить старт, миграции и health; при неуспехе вернуть предыдущую версию/состояние, насколько это поддерживает Android update model.

### 8.3 Ограничения Android

Установленное Android-приложение не может штатно заменить собственный APK и обойти систему подписи/установки. Software Evolution работает с исходной копией/CI или разрешённой build environment, а установка новой сборки проходит через Android package installer/поддерживаемый update flow и подтверждения пользователя. Не хранить release signing key в доступном модели или app sandbox виде.

### 8.4 Воспроизводимость

Каждый candidate должен иметь:
- исходный commit;
- diff;
- toolchain/dependency lock;
- build logs;
- test reports;
- benchmark results;
- SBOM/license report;
- artifact checksum;
- signature verification result;
- release notes;
- rollback reference.

Никакая новая APK не публикуется как стабильный релиз только потому, что модель сообщила «сборка успешна». Нужны фактические build/test/install результаты.

## 9. Контракты домена

Ниже — концептуальные Kotlin-like contracts; это спецификация, не готовый production API.

```kotlin
data class ResourceSnapshot(
    val capturedAt: Instant,
    val availableRamBytes: Long?,
    val freeStorageBytes: Long?,
    val batteryPercent: Int?,
    val isCharging: Boolean?,
    val thermalStatus: ThermalStatus?,
    val network: NetworkSnapshot?,
    val foreground: Boolean
)

data class ExecutionBudget(
    val maxConcurrentAgents: Int,
    val maxToolCalls: Int,
    val maxContextTokens: Int?,
    val deadline: Instant?,
    val allowCloud: Boolean,
    val allowMeteredNetwork: Boolean,
    val energyMode: EnergyMode
)

data class MissionOutcome(
    val missionId: UUID,
    val strategyId: String?,
    val verifiedQuality: QualityEvidence,
    val reliability: ReliabilityEvidence,
    val resourceUsage: ResourceUsageEvidence,
    val provenance: List<EvidenceRef>
)

data class OptimizationProposal(
    val proposalId: UUID,
    val target: OptimizationTarget,
    val hypothesis: String,
    val patchOrStrategyRef: String,
    val constraints: List<AcceptanceConstraint>,
    val benchmarkPlan: BenchmarkPlan,
    val rollbackRef: String
)

interface EvolutionEngine {
    suspend fun observe(outcome: MissionOutcome)
    suspend fun analyze(scope: EvolutionScope): List<OptimizationProposal>
    suspend fun evaluate(proposalId: UUID): EvaluationReport
    suspend fun publish(proposalId: UUID, approval: EvolutionApproval): StrategyRecord
    suspend fun rollback(strategyId: String, reason: String): RollbackResult
}
```

Реальные контракты должны использовать текущие типы проекта, kotlinx.coroutines/Flow, сериализацию и Room entities; не копировать этот псевдокод механически.

## 10. Данные и хранение

Room/SQLite сущности (предварительный набор):
- mission_metric_events;
- resource_snapshots;
- strategy_records;
- strategy_applicability;
- optimization_proposals;
- benchmark_runs;
- benchmark_samples;
- evaluation_reports;
- tool_performance_stats;
- model_performance_stats;
- software_change_candidates;
- rollout_records;
- rollback_records.

Большие артефакты, логи сборок и benchmark outputs хранить в app-private files/artifact store с checksum; в Room — метаданные и ссылки. Секреты — Android Keystore. Для каждого набора данных определить retention, export и delete behavior. Не хранить полный prompt/документ в метриках по умолчанию.

## 11. Набор benchmark

### Intelligence
- task success / verified correctness;
- factuality and citation validity;
- completeness;
- tool-call count;
- agent count and useful contribution;
- retries and conflict resolution;
- human correction rate.

### Runtime
- wall-clock latency (p50/p95);
- CPU time where available;
- peak/average memory;
- battery/energy proxy;
- thermal throttling;
- network bytes and request count;
- database I/O and UI jank;
- model load/unload time.

### Software
- build success;
- unit/contract/integration/security test outcomes;
- crash-free startup;
- cold/warm start;
- memory/CPU/battery regression;
- APK size and dependency footprint;
- migration/rollback success.

Benchmark suite must be versioned. Compare equivalent inputs, device conditions and provider/model versions where possible. Record confounders and confidence intervals; do not claim causality from a single run.

## 12. Security, privacy and governance

- Default mode: Observe + Recommend. Automatic application of code changes is disabled.
- Strategy adaptation can be automated only within explicitly configured low-risk boundaries.
- Cloud use requires Cloud Allowed and disclosure before transmitting private page/document context.
- Tool actions continue to use the central risk policy and one-shot approvals.
- External MCP servers and browser content are untrusted inputs; defend against prompt injection and tool-output instruction attacks.
- No model-controlled arbitrary shell, hidden background surveillance, permission escalation or silent data export.
- No changing approval requirements, policy thresholds, audit retention or protected modules by an AI-generated patch.
- Keep an append-only execution/evolution journal with tamper-evident hashes where feasible; do not overstate it as cryptographic immutability unless keys and storage are protected.
- User controls: pause evolution, inspect proposals, compare metrics, approve/reject, pin stable version, export evidence, delete learning records.
- Emergency kill switch disables all autonomous optimization and software evolution without disabling normal app use.

## 13. UI — Evolution Observatory

Новая зона Hybrid Universe UI: **Evolution Observatory**.

Основные панели:
1. Core Pulse — фактический runtime health, модели, нагрузка, thermal/battery.
2. Mission Efficiency — время, tool calls, agents, качество, повторы.
3. Strategy Atlas — активные стратегии, область применимости и доказательства.
4. Optimization Queue — предложения, риск, ожидаемый выигрыш, тесты, статус.
5. Benchmark Lab — сравнение baseline/candidate.
6. Software Evolution — ветки, diffs, build/test, approval и rollback.
7. Learning Memory — какие закономерности сохранены и откуда они получены.
8. Governance — режим Observe/Recommend/Controlled Auto, лимиты и kill switch.

UI показывает реальные значения и timestamp. Не использовать декоративные «проценты интеллекта», фиктивные neural activity, fake energy savings или выдуманный progress.

## 14. Режимы работы

- Observe: только измерение, без рекомендаций и изменений.
- Recommend: анализ и предложения; ничего не применяется автоматически.
- Controlled Auto: разрешено применять только заранее одобренные низкорисковые стратегии в заданных пределах.
- Lab: эксперименты в изолированной среде, без влияния на стабильный runtime.
- Freeze: остановить обучение/оптимизацию; сбор обязательной эксплуатационной телеметрии регулируется отдельно.
- Emergency Stop: прекратить автономные изменения и rollout; сохранить диагностические данные.

Рекомендуемый стартовый режим после первой реализации — **Observe**, затем Recommend. Controlled Auto открывать только после тестирования и настройки лимитов.

## 15. Этапы реализации

### E0 — Contracts & Observability Foundation
- доменные контракты Evolution;
- unified event schema;
- correlation IDs mission/step/tool/model;
- ResourceSnapshot;
- telemetry privacy filters;
- базовые метрики и локальное хранение;
- UI read-only status.

Критерий выхода: реальные метрики собираются на устройстве, источники и ограничения указаны, тесты проходят.

### E1 — Strategy Memory & Efficiency Baseline
- хранение outcome/evidence;
- классификация повторяющихся задач;
- измерение baseline;
- ручной Strategy Registry;
- отчёт «почему выбрана эта стратегия».

Критерий выхода: можно воспроизвести, почему стратегия выбрана, и сравнить её с альтернативой.

### E2 — Adaptive Cognitive Routing
- Resource Governor;
- local-first model routing;
- динамический размер Council;
- лимиты concurrency/context/tool calls;
- fallback без потери mission state;
- safe caching/batching.

Критерий выхода: сравнимые задачи показывают измеримое снижение ресурсов без ухудшения качества ниже заданного порога.

### E3 — Controlled Experiments
- offline replay;
- shadow mode;
- canary для низкорисковых миссий;
- acceptance constraints;
- StrategyRecord lifecycle;
- automatic rollback стратегии.

Критерий выхода: эксперимент не меняет действующие side effects и имеет воспроизводимые отчёты.

### E4 — Runtime Self-Optimization
- профилирование узких мест;
- рекомендации по DB, memory, I/O, UI, network;
- feature-flag rollout;
- device/OEM compatibility matrix.

Критерий выхода: каждая оптимизация имеет benchmark, регрессионные тесты и rollback.

### E5 — Software Evolution Lab
- isolated source workspace;
- patch generation;
- reproducible build pipeline;
- security/license scanning;
- test/benchmark gate;
- human review and release candidate;
- protected-core enforcement.

Критерий выхода: приложение может подготовить и проверить candidate, но не может самовольно заменить stable APK или доверенные механизмы.

### E6 — Continuous Improvement Governance
- настраиваемые режимы;
- audit dashboard;
- policy review;
- learning data controls;
- долгосрочная статистика и drift detection.

## 16. Что не входит в первую реализацию

- автономное изменение весов моделей на телефоне;
- бесконтрольное переписывание приложения;
- самовыдача root/permissions/approval;
- автоматические платежи, публикации и удаления;
- постоянный запуск всех агентов;
- гарантированное измерение точных джоулей на любом Android/OEM;
- автоматическая публикация APK без независимого QA;
- Hybrid Mesh / peer discovery / distributed execution (отложено до V4+).

## 17. Принятые архитектурные решения

1. Evolution Core — отдельный cross-cutting domain service, связанный с Cognitive Core, Task Runtime, Council, Verification, Resource Governor и Recovery.
2. Local AI и Council работают через общий Tool Gateway; MCP — протокол интеграции, а не обход policy.
3. Playwright используется только в реально поддерживаемых browser environments; Android BrowserEngineAdapter остаётся абстракцией до выбора WebView/GeckoView.
4. Стратегии оптимизируются по нескольким метрикам при жёстких ограничениях качества, безопасности и приватности.
5. Самообучение V3 — evidence-based strategy learning; обучение весов моделей отложено.
6. Software Evolution работает в sandbox/ветке и не имеет доступа к protected core или signing secrets.
7. Первый релиз Evolution начинается в Observe mode; сначала доказательная телеметрия, затем рекомендации, затем ограниченная автоматизация.
8. Hybrid Mesh остаётся V4+.

---

**Итоговая формула:** OllamaHybrid должна не просто использовать интеллект, а измеримо улучшать способ его применения — и улучшать собственную реализацию только тогда, когда проверка подтверждает реальный выигрыш.
