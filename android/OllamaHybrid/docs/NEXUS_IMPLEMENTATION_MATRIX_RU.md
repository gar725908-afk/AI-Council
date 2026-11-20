# NEXUS — матрица реализации

Единый источник правды «требование → код → статус → проверка», как требует
handoff-документ (разделы 3.4, 13, 16.7). Обновляется при каждом существенном
изменении ветки.

- Ветка: `nexus/genesis-foundation`
- Базовый HEAD передачи: `ff35fbca9a331e832b81378e25b4c45499d0a92c`
- Обновлено: 2026-11-20, после серии срезов (см. журнал ниже)
- Юнит-тестов: 166 (включая JVM-рендер экранов), lint: 0 ошибок; локальный прогон и CI зелёные
- CI: `nexus-android-debug.yml` (тесты + debug APK), `android-build.yml` (валидация)

Шкала статусов (по разделу 14 ТЗ), показывается только реально достигнутый:
`Planned → In Progress → Implemented → Unit Tested → CI Build → Tested on Physical Device → Released`

**Внимание: ни один пункт ещё не имеет статуса «Tested on Physical Device».**
Проверка на физическом Android-телефоне не выполнялась в этой серии срезов и
остаётся обязательным шагом приёмки.

## Матрица

| Требование (раздел ТЗ) | Код | Статус | Тесты |
|---|---|---|---|
| Command Deck на реальных статусах (4.1) | `CommandDeckScreen.kt` | CI Build | косвенно (хранилища) |
| Command Composer: задача → Studio/Council (4.1) | `CommandDeckScreen.kt`, `MainActivity.kt` | CI Build | — |
| Health check endpoint с latency/временем (4.1, 6.3) | `nexus/ProviderHealthCheck.kt` | Unit Tested | `ProviderHealthCheckTest` (11) |
| AI Studio: стриминг, история, статусы (4.2) | `AiStudioScreen.kt` (до передачи) | CI Build | частично |
| Council — отдельный командный центр (4.3, 13.2) | `CouncilScreen.kt` | CI Build | — |
| Лента реальных событий агентов (4.3) | `CouncilOrchestrator` events | Unit Tested | `CouncilAgentEventsTest` (4) |
| Инспектор результатов агентов из журнала (4.3) | `CouncilScreen.kt` + `MissionJournal` | CI Build | журнал покрыт ранее |
| Повтор миссии (6.1: NeedsUserDecision) | `CouncilScreen.kt` | CI Build | — |
| Runner миссий в runtime-слое (6, 6.1) | `nexus/CouncilMissionRunner.kt` | Unit Tested | `CouncilMissionRunnerTest` (6) |
| Выводы участников = недоверенный ввод (6.4, 14.2) | `buildSynthesisInput` + маркеры + нейтрализация | Unit Tested | `SynthesisInputTest` (5) |
| Device Core: диагностика с таймстемпами (4.6) | `DeviceCoreScreen.kt`, `DeviceCapabilityInspector.kt` | CI Build | `CapabilityMatrixTest` (10, чистая логика) |
| Матрица возможностей honest-статусов (4.6) | `CapabilityMatrix` | Unit Tested | `CapabilityMatrixTest` |
| Root-проба только вручную, uid=0 (7) | `RootAccessProbe` + UI-кнопка | CI Build | политика покрыта ранее |
| Наличие Shizuku ≠ привилегия (7) | `CapabilityMatrix.privileges` | Unit Tested | `CapabilityMatrixTest` |
| Model Center: каталог/загрузка/SHA-256 (9) | `NexusModelStorage.kt` (до передачи) | CI Build | `NexusModelCenterTest` |
| Докачка загрузок после обрыва (9, known limitation) | `nexus/ModelResumePolicy.kt`, `download()` | Unit Tested | `ModelResumePolicyTest` (16) |
| Нативный Ollama: /api/chat JSONL (10.3) | `nexus/OllamaNativeGateway.kt` | Unit Tested | `OllamaApiTest` (9, парсеры) |
| Нативный Ollama: /api/tags discovery (10.3) | `ProviderModelCatalog.kt` | Unit Tested | `OllamaApiTest` |
| About / build information (4.9) | `DeviceCoreScreen.kt` (СБОРКА И СРЕДА) | CI Build | — |
| Локальный Manager/Council, журнал миссий (10.1) | до передачи: `LocalFirstCouncil`, `MissionJournal` | CI Build | `LocalFirstCouncilTest`, `MissionStateMachineTest` |
| Tool Gateway: конвейер, одобрения, аудит (6.5) | `androidbridge/BridgeGateway.kt`, journal v3 `tool_audit` | Unit Tested | `BridgeGatewayTest` (13) |
| Первый типизированный инструмент device.diagnostics (6.5, 7) | `DeviceDiagnosticsTool.kt` + карточка на Device Core | CI Build | через `BridgeGatewayTest` |
| JVM-рендер каждого основного экрана (14.2 UI regression) | `ScreenRenderSmokeTest.kt` (Robolectric offline) | Unit Tested | 8 рендер-тестов |
| Информационная архитектура по разделу 4 (Deck/Studio/Council/Browser/Archive/System) | `MainActivity.kt`, `SystemScreen.kt` | CI Build | рендер-тесты |
| Archive: импорт через SAF, честные парсеры, SHA-256 (4.7) | `MaterialLibrary.kt`, `MaterialTextExtractor.kt`, `ArchiveScreen.kt` | Unit Tested | `MaterialLibraryTest` (Robolectric), extractor (4) |
| Archive: поиск с точными цитатами и позицией (4.7) | `LibrarySearch.kt` | Unit Tested | `LibrarySearchTest` (6) |
| Материал/страница как источник в Studio и Council (4.7) | `MaterialContext.kt` | Unit Tested | `MaterialContextTest` (4) |
| Cyber Browser: WebView-песочница, политика адресов, Agent Dock (4.4) | `BrowserScreen.kt`, `BrowserUrlPolicy.kt` | Unit Tested | `BrowserUrlPolicyTest` (7) + рендер |
| Settings: данные/приватность/экспорт/диагностика/About (4.9) | `SystemScreen.kt` (`SettingsPanel`) | CI Build | рендер-тест |
| Экспорт миссий в JSON без секретов (4.7, 6.1) | `MissionExport.kt` | Unit Tested | `MissionExportTest` (3) |
| Resource Governor: нагрев/заряд/память (11.2) | `ResourceGovernor.kt` + Studio/Model Center | Unit Tested | `ResourceGovernorTest` (6) |
| Permission Center, каталог = манифест (7) | `PermissionCatalog.kt` + Device Core | Unit Tested | `PermissionCatalogTest` (4) |
| Evolution Observatory: предложения, approve/reject, rollback, аудит (11.1) | до передачи: `NexusEvolutionExecutor` + Model Center | CI Build | `NexusEvolutionExecutorTest` |
| Иконка приложения в визуальном языке NEXUS (5) | `res/mipmap-anydpi-v26`, `drawable/ic_launcher_foreground.xml` | CI Build | lint |

## Не реализовано (честный список, по 13.3 с поправками)

- Cyber Browser: вкладки, постоянная история, загрузки, полный Agent Dock
  (4.4) — сделан первый срез: песочница WebView, политика адресов, текст
  страницы в Studio/Council/Архив.
- MCP-адаптеры поверх Gateway и верификатор постусловий сверх типизированного
  результата (6.5): сам конвейер Gateway с одобрениями и аудитом уже реализован.
- Интегрированный Android Bridge: каталог типизированных инструментов,
  Shizuku Binder, Direct Root executor, Permission Center (7). Сейчас есть
  контракты, политика, Root-проба и честная матрица возможностей.
- Accessibility / MediaProjection / Vision (7).
- Archive: парсеры PDF/DOCX, семантический поиск (4.7) — импорт текстовых
  форматов, лексический поиск с цитатами и подача в Studio/Council сделаны.
- NEXUS Voice (4.8).
- Resource Governor: только гейт перед запуском (11.2); фоновый мониторинг
  во время длительных задач не реализован. Evolution Observatory — есть в
  Model Center.
- NEXUS LINK, NEXUS FORGE (10.4, 12).
- OAuth-коллбеки коннекторов и их downstream-операции (8.2).
- Settings: группы «Внешний вид» (режимы эффектов) и «Модели по умолчанию»
  (4.9) — появятся вместе с реальными эффектами и выбором; остальные группы
  сделаны.
- Пауза загрузки как отдельная кнопка UI (докачка работает через
  отмену + повторный запуск; выделенная кнопка «Пауза» — следующий шаг).
- **Физическая проверка на телефоне: загрузка/генерация локальной модели,
  Bridge/Root, производительность, батарея (13.3, 14).**

## Журнал срезов этой серии (все: локальная сборка + тесты → CI зелёный)

1. `01eeaad` Device Core вместо заглушки «Устройство» + CapabilityMatrix (+10 тестов)
2. `c38f7ce` Command Deck на реальных хранилищах
3. `a8fe48b` Council: отдельный командный центр (ЦЕНТР/ЗАПУСК)
4. `40ae683` Живой health check endpoint с Command Deck (+11)
5. `cb3636d` CouncilMissionRunner в runtime-слое (+6)
6. `cf6a930` Реальные события агентов из оркестратора (+4)
7. `280110c` Command Composer: задача с главной в Studio/Council
8. `2465dd2` Нативный Ollama (/api/chat, /api/tags) + защита синтеза от инъекций (+19)
9. `60e993b` Докачка загрузок моделей (Range + повторное хэширование) (+16)
10. `50de74f` Карточка сборки на Device Core + повтор миссии в Council
11. `5aed120` Матрица реализации (этот документ)
12. `ad9078f` Tool Gateway: конвейер исполнения, аудит в журнале v3, device.diagnostics (+13)
13. `2b030c2` Robolectric: JVM-рендер smoke-тесты всех основных экранов (+3)
14. `4212adb` Матрица обновлена
15. `73ad8ae` Archive / Material Library: импорт SAF, парсеры, поиск с цитатами (+16)
16. `19d1d1a` Материал Архива → AI Studio как недоверенный источник (+4)
17. `d423eea` Навигация по разделу 4 + Settings (+2)
18. `23ab75b` Cyber Browser: первый срез (+8)
19. `3549e51` Resource Governor (+6)
20. `12ac901` Permission Center (+4)
21. `2a277ba` Материалы/страницы → Council; экспорт миссий в JSON (+3)
22. `5dbb229` Иконка NEXUS, lint-гигиена

## Расхождения документации

- `PROJECT_MASTER_SPEC_RU.md` и `NEXUS_ENGINEERING_AUDIT_RU.md` описывают
  состояние до этой серии: фразы «Устройство — заглушка», «Council
  переиспользует AI Studio», «нет докачки», «Ollama через OpenAI-shim»
  устарели — актуальное состояние в этой матрице.
- Handoff-документ, п. 13.2 — исправлено в этой серии; п. 13.3 — см.
  «Не реализовано» выше.
