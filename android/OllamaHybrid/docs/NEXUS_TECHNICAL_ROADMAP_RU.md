# NEXUS — план технического перехода и реализации
## Базовая дорожная карта на основе Android OllamaHybrid

**Версия:** 1.0  
**Дата:** 19 ноября 2026 года  
**Статус:** рабочий план перехода; не заявление о завершённой реализации  
**Исходный репозиторий:** gar725908-afk/AI-Council  
**Android-каталог:** android/OllamaHybrid/

---

## 1. Цель

Перевести Android-экосистему OllamaHybrid под продуктовую идентичность NEXUS, сохранив полезные архитектурные решения, историю Git и существующее desktop-приложение AI-Council.

NEXUS — новое имя и продуктовая оболочка Android-системы. Это не основание массово переименовывать пакеты, классы и каталоги до получения полного исходного проекта и подтверждения сборки.

## 2. Правила сохранности репозитория

1. Desktop-приложение Python/PySide6 в корне репозитория остаётся без изменений.
2. Существующая ветка main не используется для экспериментальной миграции.
3. Разработка ведётся в отдельной ветке `nexus/genesis-foundation`.
4. Старые пути, package IDs, namespaces и классы не переименовываются вслепую.
5. Сначала создаётся инвентаризация и воспроизводимая сборка, затем — изменения UI и архитектуры.
6. Никакие пользовательские секреты, cookies, токены, документы, локальные журналы и ключи подписи не добавляются в Git.
7. APK не коммитится в историю исходников: для проверенных сборок используются GitHub Releases.
8. Любое изменение с риском потери данных сопровождается diff, тестами и планом rollback.

## 3. Аудит текущего содержимого GitHub

По состоянию на 19.11.2026 проверена ветка `main` и рекурсивное дерево `android/OllamaHybrid/`.

### 3.1 Что подтверждено в репозитории

- README Android edition.
- `app/build.gradle.kts` с namespace/applicationId `ai.hybrid.commandcenter`, minSdk 26, targetSdk 35, compileSdk 36, Java 17 и Compose dependencies.
- Два Kotlin-файла Bridge foundation:
  - `BridgeContracts.kt`;
  - `RootAccessProbe.kt`.
- Документы:
  - `PROJECT_MASTER_SPEC_RU.md`;
  - `V3_FULL_AI_ECOSYSTEM_ARCHITECTURE_RU.md`;
  - `UI_DESIGN_SYSTEM_RU.md`;
  - `HYBRID_UNIVERSE_EXPERIMENTAL_V2_RU.md`;
  - `EVOLUTION_CORE_ARCHITECTURE_RU.md`;
  - `ANDROID_BRIDGE_ARCHITECTURE_RU.md`;
  - `ANDROID_BRIDGE_TEST_PLAN_RU.md`.
- Вспомогательные проверки:
  - `BridgeContractSmokeTest.kt`;
  - `validate_android_bridge.py`.

### 3.2 Что отсутствует в проверенном Android tree

В рекурсивном дереве каталога нет корневых Gradle-файлов и Gradle Wrapper, AndroidManifest, Compose entry point, полноценных feature screens и ресурсов приложения. Зафиксированы только два Kotlin-файла в Bridge package. Это не полный независимо собираемый Android Studio project.

README указывает, что полный рабочий Android working tree поддерживается отдельно как архив и ещё не синхронизирован в GitHub. Поэтому нельзя считать текущее содержимое репозитория полной копией рабочей версии и нельзя начинать замену отсутствующих исходников догадками.

Отдельно: Android README ссылается на `WEB_ACCESS_AND_AGENT_BROWSER_RU.md`, но этот файл не присутствует в проверенном рекурсивном дереве. Ссылку и наличие документа нужно сверить при следующем обновлении документации.

### 3.3 Проверяемый статус компонентов

| Компонент | Статус по мастер-спецификации |
|---|---|
| Compose UI | Базовый каркас; полноценные экраны и production-потоки не подтверждены |
| AI Council | Shared bus / orchestrator / watchdog и HTTP adapter описаны как scaffold; retry, consensus и tool calling незавершены |
| Local AI Runtime | llama.cpp JNI/runtime не подключён |
| Material Library | Базовый импорт и lexical retrieval; PDF/OCR/audio/video/embeddings не завершены |
| Android Bridge | Contracts, registry/risk gate и Root UID probe; Shizuku/полный Root service/Permission Center и adapters pending |
| Evolution / Recovery | Workspace, snapshots, logs, pending marker и rollback scaffold; manifests, patcher и build/test runner pending |
| Browser | Архитектура описана; production browser/session/tool integration не подтверждена |
| APK | Сборка и установка не подтверждены |

Эта таблица является исходной базой аудита; статусы изменяются только после проверки конкретного кода и тестов.

## 4. Целевая архитектура NEXUS

Сохранять архитектурные границы V3:

1. **Presentation / NEXUS WORLD** — Compose UI, навигация, состояние экранов и доступность.
2. **Application / Mission Runtime** — сценарии создания, планирования, исполнения, приостановки и восстановления миссий.
3. **Domain / NEXUS CORE** — независимые модели задач, агентов, провайдеров, инструментов, памяти, разрешений и доказательств.
4. **Runtime Services** — Council, provider routing, memory, browser, materials, verification, watchdog и recovery.
5. **Adapters** — Android API, Room/DataStore, SAF, WorkManager, Keystore, llama.cpp JNI, HTTP providers, browser engine, Shizuku/Root и Accessibility.
6. **Device / OS** — реальные системные возможности, разрешения, аппаратные ограничения и OEM поведение.

Критический инвариант:

**Модель предлагает → Policy проверяет → Approval подтверждает → Tool исполняет → Verifier проверяет → Runtime сохраняет результат.**

LLM не является владельцем mission state и не может передать себе полномочия через текст или JSON ответа.

## 5. Продуктовая карта и сопоставление имён

| Продуктовый раздел NEXUS | Существующая/целевая подсистема |
|---|---|
| NEXUS CORE | Cognitive Core, Task Runtime, policy, routing |
| NEXUS COUNCIL | Council Orchestrator и Agent Runtime |
| NEXUS ARCHIVE | Memory, Material Library и retrieval |
| NEXUS VOICE | Speech input/output и voice interaction |
| NEXUS BRIDGE | Android Bridge и capability/permission gateway |
| NEXUS WORLD | Hybrid Universe UI и навигационная оболочка |
| NEXUS EVOLUTION | Evolution Core, isolated workspace и Recovery Core |
| NEX | Персона и UX-слой общения, не отдельный привилегированный исполнитель |

На период миграции технические пути `OllamaHybrid` и namespace `ai.hybrid.commandcenter` остаются допустимыми внутренними историческими именами. Изменение applicationId требует отдельной оценки миграции установки, данных и подписи.

## 6. Этапы работ и критерии завершения

### PHASE 0 — INVENTORY & SOURCE RECOVERY
- Получить полный рабочий source tree из указанного в README Android project archive или другого доступного GitHub-артефакта.
- Сопоставить архив с уже опубликованными Kotlin-файлами и спецификациями.
- Проверить отсутствие секретов и локальных пользовательских данных.
- Создать manifest файлов и отчёт о различиях.
- Зафиксировать base commit и не перезаписывать историю.

**Gate:** известен полный состав исходников и понятны расхождения с GitHub.

### PHASE 1 — GENESIS FOUNDATION
- Восстановить корневые Gradle-файлы, Wrapper, Manifest, source sets, ресурсы и точку входа из проверенного source tree.
- Зафиксировать версии Android Gradle Plugin/Kotlin/dependencies.
- Настроить reproducible debug build.
- Добавить базовые unit tests и проверку статического анализа.
- Проверить установку на тестовом Android-устройстве или эмуляторе.

**Gate:** clean checkout собирается и запускается; сборка не зависит от локальных секретов или неизвестных файлов.

### PHASE 2 — NEXUS WORLD & IDENTITY
- Перенести бренд NEXUS в app display name, launcher icon, splash, onboarding и About.
- Сохранить существующие технические identifiers, пока миграция не обоснована.
- Реализовать design tokens и общий Compose scaffold по UI specification.
- Включить Static/Reduced Motion режим.
- Внедрить NEX как persona layer с конфигурируемым голосом/стилем, не нарушая прозрачность и безопасность.
- Связать художественные названия с реальным состоянием сервисов.

**Gate:** интерфейс NEXUS работает, доступен и не показывает фиктивные статусы.

### PHASE 3 — CORE & MISSION LIFECYCLE
- Durable Mission/Step state machine.
- Provider registry, health checks и local/cloud policy.
- Persistent chat/session storage.
- Pause/cancel/retry с idempotency и timeout.
- Event Journal, recovery after process death и точные error states.

**Gate:** mission восстанавливается после остановки процесса; повторы не дублируют необратимые действия.

### PHASE 4 — COUNCIL
- Конфигурируемые роли и количество агентов, с начальным профилем Manager + executor agents.
- Общий transcript и единый mission context.
- Resource Governor для ограничения параллелизма на телефоне.
- Typed tool calling, policy, approval, verifier и audit.
- Таймауты, retries/backoff, cancellation и consensus/scoring только после проверки результата.

**Gate:** Council выполняет тестовые миссии с прослеживаемыми input/output/evidence и корректной отменой.

### PHASE 5 — BRIDGE & DEVICE CORE
- Capability detection и Permission Center.
- Native API/Intent adapters first.
- Shizuku/Sui Binder lifecycle и проверка UID/permission.
- Direct Root typed service с allowlist, timeout, output cap и approval.
- Accessibility adapter как отдельный транспорт.
- Audit и verifier для каждого device action.

**Gate:** Standard mode работает без Root; privileged features проверяют актуальные разрешения; опасные действия не исполняются без конкретного согласия.

### PHASE 6 — ARCHIVE, BROWSER & VOICE
- Устойчивый Material Library pipeline и честные форматы.
- Browser engine/session storage, cookies isolation, tabs, downloads и tool gateway.
- Voice input/output с контролируемыми разрешениями.
- PDF/OCR/video/audio/embeddings добавлять отдельно, с тестами качества и ограничений.

**Gate:** пользователь видит источник данных, состояние индексации, передачу контекста и возможность остановить процесс.

### PHASE 7 — EVOLUTION & RELEASE
- AI-editable workspace отдельно от Recovery Core.
- Diff review, approval, hash manifest, snapshots и atomic rollback.
- Изолированный build/test runner.
- Dependency/license/security review.
- Gradle build, installation, smoke/regression tests, signature verification и SHA-256.
- GitHub Release с APK и changelog только после device QA.

**Gate:** релиз воспроизводим, подписан, протестирован и имеет понятный rollback path.

## 7. Инженерные ограничения первой реализации

- Hybrid Mesh, peer discovery, distributed storage и удалённое выполнение не входят в V3 scope.
- Root не означает доступ ко всем данным и не отменяет системные защиты Android.
- Локальная модель выбирается с учётом RAM, storage, ABI, thermal limits и энергопотребления.
- Количество параллельных агентов динамически ограничивается ресурсами устройства.
- Cloud providers подключаются опционально; в Local Only режиме содержимое не отправляется облачному провайдеру.
- Никакие функции не объявляются готовыми только на основании UI, mock data или целевой спецификации.

## 8. Процесс изменения кода

Для каждой задачи:
1. Указать конкретную цель и файлы.
2. Проверить зависимости и обратную совместимость.
3. Сформировать узкий diff.
4. Добавить или обновить тесты.
5. Запустить доступные проверки.
6. Зафиксировать фактический результат и ограничения.
7. Только после этого обновить статус в документации.

Для рискованных и структурных изменений сначала создаётся branch/commit. Ветка main остаётся стабильной до review/merge.

## 9. Ближайшее действие

Первое техническое действие — не переименование приложения и не рисование всех экранов. Нужно получить/найти полный рабочий Android source tree, потому что текущий GitHub Android tree не содержит достаточного набора файлов для чистой сборки.

При этом концепция и архитектурный переход NEXUS уже могут быть документированы независимо от недостающего кода.
