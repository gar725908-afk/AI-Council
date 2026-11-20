# NEXUS — матрица реализации

Единый источник правды «требование → код → статус → проверка», как требует
handoff-документ (разделы 3.4, 13, 16.7). Обновляется при каждом существенном
изменении ветки.

- Ветка: `nexus/genesis-foundation`
- Базовый HEAD передачи: `ff35fbca9a331e832b81378e25b4c45499d0a92c`
- Обновлено: 2026-11-20
- Юнит-тестов: 349 (включая JVM-рендер экранов, галерею скриншотов и живой MCP-сервер), локальный прогон и CI зелёные
- CI: `nexus-android-debug.yml` (тесты + debug APK), `android-build.yml` (валидация)

Шкала статусов (по разделу 14 ТЗ), показывается только реально достигнутый:
`Planned → In Progress → Implemented → Unit Tested → CI Build → Tested on Physical Device → Released`

**Внимание: ни один пункт ещё не имеет статуса «Tested on Physical Device».**
Проверка на физическом Android-телефоне не выполнялась (по решению владельца
проекта) и остаётся обязательным шагом приёмки. Вместо неё на каждом срезе
выполняется JVM-рендер всех основных экранов (Robolectric) — он ловит падения
при отрисовке, но не заменяет проверку на устройстве.

## Пространства приложения (раздел 4)

| Требование | Код | Статус | Тесты |
|---|---|---|---|
| Command Deck на реальных статусах (4.1, 5.1) | `CommandDeckScreen.kt`, `DeckModel.kt` | Unit Tested | `DeckModelTest` (12), галерея |
| Command Portal + голосовой ввод (4.1, 4.8) | `CommandDeckScreen.kt`, `nexus/VoiceIO.kt` | CI Build | `VoiceErrorsTest` |
| Health check endpoint с latency (4.1, 6.3) | `nexus/ProviderHealthCheck.kt` | Unit Tested | `ProviderHealthCheckTest` (11) |
| AI Studio: стриминг, история, озвучка ответа (4.2, 4.8) | `AiStudioScreen.kt` | CI Build | частично |
| Council — командный центр + запуск (4.3) | `CouncilScreen.kt` | CI Build | рендер-smoke |
| Лента реальных событий агентов (4.3) | `CouncilOrchestrator` events | Unit Tested | `CouncilAgentEventsTest` (4) |
| Neural Constellation: живая и по журналу (5.3) | `ui/CouncilConstellation.kt` | Unit Tested | `ConstellationModelTest` (5), галерея |
| Инспектор результатов агентов (4.3) | `CouncilScreen.kt` + `MissionJournal` | CI Build | журнал покрыт |
| Повтор миссии (6.1) | `CouncilScreen.kt` | CI Build | — |
| Cyber Browser: вкладки, история, загрузки (4.4) | `BrowserScreen.kt`, `nexus/BrowserTabs.kt`, `nexus/BrowserHistoryStore.kt` | Unit Tested | `BrowserTabsTest` (7) |
| Политика адресов браузера (4.4, 14.3) | `nexus/BrowserUrlPolicy.kt` | Unit Tested | `BrowserUrlPolicyTest` (7) |
| Agent Dock: страница → Studio/Council/Архив (4.4) | `BrowserScreen.kt` | CI Build | — |
| Archive: импорт, поиск с цитатами (4.7) | `ArchiveScreen.kt`, `nexus/MaterialLibrary.kt`, `nexus/LibrarySearch.kt` | Unit Tested | `MaterialLibraryTest`, `LibrarySearchTest` (16) |
| Парсеры: PDF, XLSX, DOCX, текст (4.7) | `nexus/PdfTextExtractor.kt`, `nexus/OfficeTextExtractor.kt`, `nexus/MaterialTextExtractor.kt` | Unit Tested | `PdfTextExtractorTest` (4), `OfficeTextExtractorTest` (6) |
| Просмотр материала и страниц PDF (4.7) | `ArchiveScreen.kt`, `nexus/PdfPageRenderer.kt` | CI Build | хранение оригиналов покрыто |
| Операции с PDF: извлечение, объединение (4.7) | `nexus/PdfOperations.kt` | Unit Tested | `PdfOperationsTest` (4) |
| Материал как цитируемый контекст (4.7, 6.4) | `nexus/MaterialContext.kt` | Unit Tested | `MaterialContextTest` (4) |
| NEXUS Voice: распознавание и синтез (4.8) | `nexus/VoiceIO.kt` | Unit Tested | `VoiceErrorsTest` (2) |
| Settings: данные, приватность, очистка, экспорт (4.9) | `SystemScreen.kt`, `nexus/MissionExport.kt` | Unit Tested | `MissionExportTest` (3) |
| About / build information (4.9) | `DeviceCoreScreen.kt`, `SystemScreen.kt` | CI Build | — |

## Визуальный слой и NEX (разделы 2, 5)

Третья редакция по решению владельца: «как ChatGPT, но будто приложение из
3055 года». Командная палуба и шесть вкладок заменены одним экраном-чатом;
инструменты открываются из бокового меню.

| Требование | Код | Статус | Тесты |
|---|---|---|---|
| Один главный экран — чат (как ChatGPT): меню, выбор режима NEX/Совет, новый чат | `ChatScreen.kt` | CI Build | галерея `01`–`06` |
| Ответы NEX без пузырей, с разметкой (заголовки, списки, цитаты, код) | `ui/Markdown.kt` | Unit Tested | `MarkdownParserTest` (4) |
| Космический фон «Deep Field»: звёзды, туманность, дуга горизонта планеты; цвет атмосферы = состояние NEX | `ui/SpaceBackground.kt` | CI Build | галерея |
| Кнопка «+»: дуга действий (голос, файл, сайт, совет) | `ChatScreen.kt` | CI Build | галерея `05` |
| Голос на весь экран; кольца следуют реальному уровню микрофона | `ChatScreen.kt`, `VoiceInput.Event.Level` | CI Build | галерея `06` |
| Файл в чат: текст извлекается и уходит как цитируемый контекст | `ChatScreen.kt`, `MaterialTextExtractor`, `MaterialContext` | CI Build | экстракторы покрыты |
| Совет прямо в ленте: живое созвездие по событиям оркестратора | `ChatScreen.kt`, `ui/CouncilConstellation.kt` | Unit Tested | `ConstellationModelTest` (5) |
| Модель и ключи, маршруты агентов — в выдвижных шторках | `ChatScreen.kt` | CI Build | — |
| Боковое меню: история чатов, Совет, Браузер, Архив, Состояние, Настройки | `ChatScreen.kt`, `MainActivity.kt` | CI Build | галерея `04` |
| NEX — персона и облик (8 состояний, мимика, моргание) | `ui/NexMind.kt`, `ui/NexVisuals.kt` | Unit Tested | `NexMindTest` (12), галерея `07` |
| Состояние системы (бывшая палуба) с Core Orbital | `CommandDeckScreen.kt`, `DeckModel.kt` | Unit Tested | `DeckModelTest` (12), галерея `13` |
| Шрифты Inter / Unbounded и иконки Lucide в APK | `res/font`, `res/drawable/lu_*.xml` | CI Build | лицензии в `third_party_licenses/` |
| Режимы эффектов; системное «без анимаций» и энергосбережение побеждают | `MotionPolicy`, Настройки | Unit Tested | `MotionPolicyTest` (6) |

Сравнение со старыми скриншотами/видео владельца не выполнялось: их нет в
репозитории.

## Runtime и архитектура (разделы 6, 10, 11)

| Требование | Код | Статус | Тесты |
|---|---|---|---|
| Runner миссий в runtime-слое (6, 6.1) | `nexus/CouncilMissionRunner.kt` | Unit Tested | `CouncilMissionRunnerTest` (6) |
| Выводы участников = недоверенный ввод (6.4, 14.2) | `buildSynthesisInput` + маркеры + нейтрализация | Unit Tested | `SynthesisInputTest` (5) |
| Tool Gateway: конвейер, одобрения, аудит (6.5) | `androidbridge/BridgeGateway.kt`, journal v3 `tool_audit` | Unit Tested | `BridgeGatewayTest` (15) |
| Верификатор постусловий (6.5) | `VerifiableBridgeTool` + реализации | Unit Tested | `BridgeGatewayTest`, тесты инструментов |
| MCP-коннекторы: любой сервер (6.5, 8) | `nexus/McpClient.kt`, `androidbridge/McpBridgeTool.kt`, `McpConnectorsPanel.kt` | Unit Tested | `McpProtocolTest`, `McpClientTest` (9, с MockWebServer) |
| Нативный Ollama: /api/chat, /api/tags (10.3) | `nexus/OllamaNativeGateway.kt` | Unit Tested | `OllamaApiTest` (9) |
| Локальный Manager/Council, журнал миссий (10.1) | `LocalFirstCouncil`, `MissionJournal` | CI Build | `LocalFirstCouncilTest`, `MissionStateMachineTest` |
| Model Center: каталог, загрузка, SHA-256 (9) | `nexus/NexusModelStorage.kt` | CI Build | `NexusModelCenterTest` |
| Докачка загрузок после обрыва (9) | `nexus/ModelResumePolicy.kt` | Unit Tested | `ModelResumePolicyTest` (16) |
| Resource Governor: нагрев, батарея, память (11.2) | `nexus/ResourceGovernor.kt` | Unit Tested | `ResourceGovernorTest` (6) |
| Evolution: предложения, активация, откат (11) | `nexus/NexusEvolutionExecutor.kt`, Model Center | CI Build | покрыто ранее |

## Android Bridge (раздел 7)

| Требование | Код | Статус | Тесты |
|---|---|---|---|
| Матрица возможностей с честными статусами | `androidbridge/DeviceCapabilityInspector.kt` | Unit Tested | `CapabilityMatrixTest` (11) |
| Device Core: измерения с таймстемпами (4.6) | `DeviceCoreScreen.kt` | CI Build | рендер-smoke |
| Root-проба только вручную, uid=0 | `androidbridge/RootAccessProbe.kt` | CI Build | политика покрыта |
| Direct Root executor, фиксированные команды | `androidbridge/RootShellExecutor.kt`, `PrivilegedTools.kt` | Unit Tested | `RootOutputParserTest`, `PrivilegedDiagnosticsToolTest` (11) |
| Accessibility: чтение дерева и жесты | `androidbridge/NexusAccessibilityService.kt`, `AccessibilityTools.kt` | Unit Tested | `UiTreeMatcherTest`, `AccessibilityToolsContractTest` (13) |
| Снимок экрана, одноразовое согласие | `androidbridge/ScreenCapture.kt` | Unit Tested | `ScreenCaptureTest` (5) |
| Permission Center | `androidbridge/PermissionCatalog.kt`, `DeviceCoreScreen.kt` | Unit Tested | `PermissionCatalogTest` (4, сверка с манифестом) |
| Наличие Shizuku ≠ привилегия | `CapabilityMatrix.privileges` | Unit Tested | `CapabilityMatrixTest` |
| Shizuku-транспорт: UserService + AIDL, фиксированные команды | `androidbridge/ShizukuTransport.kt`, `NexusPrivilegedService.kt` | Unit Tested | `ShizukuTransportTest` (10) |
| Системные настройки: чтение публичных значений, открытие экранов | `androidbridge/SystemSettingsTools.kt` | Unit Tested | `SystemSettingsCatalogTest`, `SystemSettingsToolsTest` (9) |

## Сопровождение и самовосстановление

| Требование | Код | Статус | Тесты |
|---|---|---|---|
| OCR сканов PDF на устройстве | `nexus/OcrEngine.kt`, `ArchiveScreen.kt` | Unit Tested | `OcrEngineTest` (5) |
| NEXUS LINK v1, телефонная сторона (10.4) | `nexus/LinkProtocol.kt` | Unit Tested | `LinkProtocolTest` (15) |
| Журнал ошибок, перехват падений, отчёт без секретов | `nexus/ErrorJournal.kt`, `NexusApplication.kt` | Unit Tested | `ErrorJournalTest` (5), `ErrorReportTest` (7) |
| Исправления: исходник с GitHub → Council → ветка + PR (main не трогается) | `androidbridge/GitHubTools.kt`, `nexus/GitHubClient.kt`, `RepairPanel.kt` | Unit Tested | `GitHubClientTest` (6), `GitHubPathsTest` (5) |

## Не реализовано (честный список)

- **Desktop Agent для NEXUS LINK (10.4).** Протокол v1 и телефонная сторона
  реализованы (`docs/NEXUS_LINK_PROTOCOL_V1_RU.md`, `LinkProtocolTest`);
  программа на ПК, которая отвечает по этому протоколу, ещё не написана.
- **NEXUS FORGE (12)** — сборка и бенчмарк на устройстве.
- **OCR кириллицы.** Распознавание сканов работает на ML Kit (латиница);
  кириллические сканы распознаются плохо — это ограничение модели, оно
  показывается пользователю.
- **OAuth-коллбеки коннекторов и их downstream-операции (8.2)** сверх MCP.
- **Физическая проверка на телефоне** — загрузка и генерация локальной модели,
  Root, Shizuku, Accessibility, снимок экрана, голос, анимации, производительность
  и расход батареи (13.3, 14). Это обязательный шаг приёмки, который нельзя
  выполнить из облачной среды.

## Журнал срезов (все: локальная сборка + тесты → CI зелёный)

1. Device Core вместо заглушки «Устройство» + CapabilityMatrix
2. Command Deck на реальных хранилищах
3. Council: отдельный командный центр (ЦЕНТР/ЗАПУСК)
4. Живой health check endpoint
5. CouncilMissionRunner в runtime-слое
6. Реальные события агентов из оркестратора
7. Command Composer: задача с главной в Studio/Council
8. Нативный Ollama + защита синтеза от инъекций
9. Докачка загрузок моделей (Range + пере-хэширование)
10. Карточка сборки + повтор миссии
11. Матрица реализации (этот документ)
12. Tool Gateway: конвейер, аудит, device.diagnostics
13. Robolectric: JVM-рендер основных экранов
14. Archive: импорт, поиск с цитатами
15. Материал как цитируемый контекст в Studio
16. Информационная архитектура + Settings
17. Cyber Browser: песочница, политика адресов, Agent Dock
18. Resource Governor
19. Permission Center
20. Материалы и страницы в Council; экспорт миссий
21. Иконка NEXUS, чистка lint
22. PDF: извлечение текста (PDFBox)
23. XLSX и DOCX без тяжёлых зависимостей
24. Хранение оригиналов, просмотр PDF и текста
25. Операции с PDF: извлечение страниц и объединение
26. Браузер: вкладки, постоянная история, системные загрузки
27. NEXUS Voice: распознавание и синтез речи
28. Верификатор постусловий в Gateway
29. MCP-коннекторы: любой сервер через Gateway
30. Direct Root executor с фиксированными командами
31. Accessibility: чтение экрана и нажатия
32. Снимок экрана с одноразовым согласием
33. Shizuku: транспорт через Binder/UserService — ADB-привилегии без root
34. Системные настройки как типизированные инструменты
35. OCR сканов PDF на устройстве (ML Kit, ограничения указаны)
36. NEXUS LINK v1: спецификация и ядро на телефоне
37. Журнал ошибок и перехват падений
38. Исправления: Council читает исходники с GitHub и предлагает PR
39. Стабильный порядок строк журналов, изоляция тестов БД
40. Визуальный слой: дизайн-система, NEX, Core Orbital, новый Command Deck, Neural Constellation, режимы эффектов, галерея скриншотов
41. Редизайн по отзыву владельца: шрифты, иконки, стекло, аврора, NEX-питомец, плавающая навигация, все экраны в одном стиле
42. «Как ChatGPT из 3055 года»: один экран-чат, боковое меню, космос с горизонтом, дуга действий, голос на весь экран, файлы и совет прямо в чате

## Расхождения документации

- `PROJECT_MASTER_SPEC_RU.md` и `NEXUS_ENGINEERING_AUDIT_RU.md` описывают
  состояние до этой серии: утверждения «Устройство — заглушка», «Council
  переиспользует AI Studio», «нет докачки», «Ollama через OpenAI-shim», «нет
  Accessibility», «нет коннекторов» устарели — актуальное состояние здесь.
- Handoff-документ, п. 13.2 — исправлено; п. 13.3 — см. «Не реализовано».
