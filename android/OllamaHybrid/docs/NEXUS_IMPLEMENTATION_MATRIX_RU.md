# NEXUS — матрица реализации

Единый источник правды «требование → код → статус → проверка», как требует
handoff-документ (разделы 3.4, 13, 16.7). Обновляется при каждом существенном
изменении ветки.

- Ветка: `nexus/genesis-foundation`
- Базовый HEAD передачи: `ff35fbca9a331e832b81378e25b4c45499d0a92c`
- Обновлено: 2026-11-21
- Юнит-тестов: 445 (включая JVM-рендер экранов, сквозные сценарии оболочки, галерею скриншотов, живой MCP-сервер, Ollama /api/pull на MockWebServer и узлы NEXUS Mesh на настоящих TLS-сокетах), локальный прогон зелёный
- CI: `nexus-android-debug.yml` (тесты + debug APK), `android-build.yml` (валидация), `nexus-android-emulator.yml` (приложение на Android-эмуляторе + Ollama на раннере: настройка ядра, реальный ответ, совет, все вкладки, голос, модели на устройстве, сопряжение NEXUS Mesh со вторым узлом на раннере и задача по сети, жизненный цикл движка llama.cpp под CheckJNI; скриншоты, видео, logcat), `nexus-android-asan.yml` (движок llama.cpp с AddressSanitizer на эмуляторе: загрузка/ответ/отмена/освобождение, контрольный use-after-free обязан быть пойман)

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
| Чат NEX: стриминг, история, озвучка ответа (4.2, 4.8) | `ChatController.kt`, `ChatScreen.kt` | Unit Tested | `ShellFlowTest` (ответ через совместимый сервер), эмулятор |
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

Четвёртая редакция по решению владельца: изучены ~110 картинок по запросам
«приложение ИИ будущего» и «… киберпанк». Итог — тёмный HUD-интерфейс с
неоном, голограмма ИИ на проекторе и неоновый город; всё упрощено до чата и
нижней панели.

| Требование | Код | Статус | Тесты |
|---|---|---|---|
| Дизайн-система CYBER: палитра, Exo 2 / Inter / JetBrains Mono, HUD-панели со срезанными углами и подсветкой | `ui/Cyber.kt`, `ui/NexusTheme.kt`, `ui/NexusType.kt`, `ui/NexusComponents.kt` | CI Build | галерея `d03` |
| NEX-голограмма: 3D-голова из линий (кольца, меридианы, черты лица), глаза, рот при речи, проектор, частицы, сканлайны; цвет = реальное состояние | `ui/Hologram.kt` | Unit Tested | `CoreSetupLogicTest` (геометрия), галерея `d01`, `d02` |
| Неоновый город, перспективная сетка, сканлайны (сцены CITY/DIM/PLAIN) | `ui/CyberBackdrop.kt` | CI Build | галерея |
| Оболочка: 4 вкладки (Чат, Совет, Модули, Настройки) + голосовое ядро в центре | `MainActivity.kt`, `CyberShell.kt` | Unit Tested | `ShellFlowTest`, эмулятор |
| Чат: приветствие с голограммой, быстрый старт, режим NEX/Совет, HUD-сообщения, ошибка с «Повторить» | `ChatScreen.kt`, `ChatController.kt` | Unit Tested | `ShellFlowTest`, галерея `01`–`05` |
| Разговор голосом: слушает → думает → отвечает вслух (рот голограммы по реальному синтезу) | `CyberShell.kt` (`VoiceOverlay`), `nexus/VoiceIO.kt` | CI Build | галерея `06`, эмулятор |
| Мастер «Подключение ядра»: телефон (загрузка + SHA-256), ПК (Ollama, автопоиск в Wi-Fi), облако (13 сервисов, проверка ключа) | `CoreSetupScreen.kt`, `nexus/LanScanner.kt` | Unit Tested | `ShellFlowTest` (Ollama), `CoreSetupLogicTest` (8), эмулятор |
| Мастер открывается сам при первом запуске, если ядро не подключено | `MainActivity.kt`, `NexusProfile` | Unit Tested | `ShellFlowTest` |
| Ответы NEX с разметкой; чистый текст для озвучки и копирования | `ui/Markdown.kt` | Unit Tested | `MarkdownParserTest` (4) |
| Совет прямо в ленте: живое созвездие по событиям оркестратора | `ChatScreen.kt`, `ui/CouncilConstellation.kt` | Unit Tested | `ConstellationModelTest` (5) |
| Маршруты агентов — во вкладке «Совет» | `CouncilTab.kt` | CI Build | галерея `11` |
| Режимы эффектов (по умолчанию «Ярко»); системное «без анимаций» и энергосбережение побеждают | `MotionPolicy`, Настройки | Unit Tested | `MotionPolicyTest` (6) |
| NEX — персона (8 состояний) | `ui/NexMind.kt` | Unit Tested | `NexMindTest` (12) |
| Шрифты Exo 2 / Inter / JetBrains Mono и иконки Lucide в APK | `res/font`, `res/drawable/lu_*.xml` | CI Build | лицензии в `third_party_licenses/` |

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
| NEXUS LINK v2, телефонная сторона (10.4): права поверх сопряжения Mesh, PIN-схема v1 удалена | `nexus/LinkProtocol.kt` | Unit Tested | `LinkProtocolTest` (15) |
| Движок llama.cpp: безопасный жизненный цикл JNI (id вместо указателей, ожидание вызовов перед освобождением) | `cpp/LlamaCppJni.cpp`, `nexus/NexusOnDeviceEngines.kt` | CI Build | `NativeLifecycleTest` (10) на эмуляторе под CheckJNI и под ASan |
| Журнал ошибок, перехват падений, отчёт без секретов | `nexus/ErrorJournal.kt`, `NexusApplication.kt` | Unit Tested | `ErrorJournalTest` (5), `ErrorReportTest` (7) |
| Исправления: исходник с GitHub → Council → ветка + PR (main не трогается) | `androidbridge/GitHubTools.kt`, `nexus/GitHubClient.kt`, `RepairPanel.kt` | Unit Tested | `GitHubClientTest` (6), `GitHubPathsTest` (5) |

## NEXUS Mesh — сеть своих телефонов

Подробно: [README Mesh](NEXUS_MESH_README_RU.md), [протокол](NEXUS_MESH_PROTOCOL_RU.md),
[безопасность](NEXUS_MESH_SECURITY_MODEL_RU.md), [архитектура](NEXUS_MESH_ARCHITECTURE_RU.md),
[план тестирования](NEXUS_MESH_TEST_PLAN_RU.md).

| Требование | Код | Статус | Тесты |
|---|---|---|---|
| Ключ устройства в Android Keystore, ID из отпечатка | `mesh/MeshIdentity.kt` | CI Build | `MeshProtocolTest`, эмулятор (прогон `44837929289`) |
| Сопряжение (протокол v2): ссылка/QR, HMAC-доказательство, код с commit–reveal, одно сопряжение на приглашение, подтверждение на обоих | `mesh/MeshProtocol.kt`, `mesh/MeshNode.kt`, `MeshCenterScreen.kt` | CI Build | `MeshNodeTest`, `MeshProtocolTest`, эмулятор ↔ узел CI |
| Взаимный TLS с закреплением ключей, только частные адреса | `mesh/MeshNode.kt` | Unit Tested | `MeshNodeTest` |
| Отзыв доверия | `mesh/MeshTrust.kt`, `mesh/MeshNode.kt` | Unit Tested | `revokedPhoneCanNoLongerRunTasks` |
| Типизированные задачи: состояние, ответ модели, замер | `mesh/MeshNode.kt`, `mesh/MeshRuntime.kt` | CI Build | `MeshNodeTest`, `MeshHostPeerE2E` (эмулятор ↔ узел CI) |
| Resource Governor + запрет облачного ядра по умолчанию | `mesh/MeshGovernor.kt` | Unit Tested | `MeshNodeTest` |
| Сохранённая история задач | `mesh/MeshTrust.kt` | Unit Tested | `taskHistorySurvives…`, `storedHistory…` |
| mDNS в Wi-Fi, фоновая служба | `mesh/MeshRuntime.kt`, `mesh/MeshService.kt` | CI Build | нужна проверка на двух телефонах |
| Разные сети (Tailscale) | частные адреса 100.64/10 | Experimental | — |
| WebRTC, планировщик 3+ узлов, Council на телефонах, llama.cpp RPC | — | Planned | — |

## Не реализовано (честный список)

- **Desktop Agent для NEXUS LINK (10.4).** Протокол v2 и телефонная сторона
  реализованы (`docs/NEXUS_LINK_PROTOCOL_RU.md`, `LinkProtocolTest`);
  программа на ПК, которая сопрягается через NEXUS Mesh и отвечает по этому
  протоколу, ещё не написана.
- **NEXUS FORGE (12)** — сборка и бенчмарк на устройстве.
- **NEXUS Mesh, фазы 3–6** — WebRTC-транспорт, автоматический планировщик для
  трёх и более узлов, Council на нескольких телефонах, распределение одной
  модели через llama.cpp RPC. Фазы 1–2 (сопряжение, защищённый канал, задачи
  в одной сети) реализованы, на физических телефонах не проверены.
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
36. NEXUS LINK v1: спецификация и ядро на телефоне (в срезе 54 заменён на v2)
37. Журнал ошибок и перехват падений
38. Исправления: Council читает исходники с GitHub и предлагает PR
39. Стабильный порядок строк журналов, изоляция тестов БД
40. Визуальный слой: дизайн-система, NEX, Core Orbital, новый Command Deck, Neural Constellation, режимы эффектов, галерея скриншотов
41. Редизайн по отзыву владельца: шрифты, иконки, стекло, аврора, NEX-питомец, плавающая навигация, все экраны в одном стиле
42. «Как ChatGPT из 3055 года»: один экран-чат, боковое меню, космос с горизонтом, дуга действий, голос на весь экран, файлы и совет прямо в чате
43. CYBER по референсам владельца: голограмма NEX, неоновый город, HUD; 4 вкладки + голосовое ядро; мастер подключения ядра (телефон / ПК с автопоиском / облако); исправлен адрес Fireworks (`gofireworks.org` → `api.fireworks.ai/inference/v1`); сквозной тест на Android-эмуляторе с Ollama
44. Каталог моделей: 12 моделей для телефона в трёх уровнях (≤9B, закреплённые ревизии HF + SHA-256: Qwen3/3.5, Vikhr, QVikhr, Gemma 4 E2B/E4B, Nemotron 3.5 Bolt E4B, YandexGPT 5 Lite, T-lite 2.1), оценка «потянет ли телефон» по ОЗУ; «Скачать на ПК» через Ollama `/api/pull`
45. «Скачивать не надо»: поиск уже скачанных `.gguf`/`.litertlm` (общая память с доступом ко всем файлам + папка приложения), разбор GGUF-заголовка, сверка с каталогом по размеру и SHA-256, запуск файла на месте (путь или дескриптор SAF через `FILE*`), «Забыть» никогда не удаляет файл владельца
46. Движок llama.cpp: текст через JNI только UTF-8 массивами (без `NewStringUTF`), стили промпта (Qwen без размышлений, ChatML, Gemma 4, шаблон из GGUF), фильтр think-блоков и служебных токенов, обрезка истории, повторное использование KV-кэша между репликами, прогрев модели; SHA-256 пересчитывается только если файл изменился
47. Исправление по эмулятору: kotlinx-coroutines 1.11.0 — LiteRT-LM 0.16.1 вызывал `SendChannel.close$default`, которого нет в 1.10.x, и приложение падало в конце ответа Gemma
48. Темы «Неон-город», «Космос», «Аниме» (палитра как snapshot-состояние, процедурные сцены, японские подписи шрифтом Noto Sans JP-подмножеством, тест покрытия глифов), кольцо вокруг фронтальной камеры по состоянию NEX, полосы «опасность» на ошибках, ядро-реактор в доке
49. Живой питомец NEX: уровень и опыт только за реальные события, настроение из последних ответов, заряда и времени простоя, сон и реакции на касание, профиль с 13 достижениями
50. Идеи JARVIS: команды без ИИ (время, дата, заряд, точный калькулятор BigDecimal, будильник, таймер, приложения), голосовые будильники — только после «Подтвердить» (60 с), честное «Передано Android»; отрицания/вопросы/цитаты/составные запросы не исполняются; выбор голоса (офлайн/через интернет), скорость речи, озвучка по предложениям; возврат неотправленного текста при ошибке
51. NEXUS Mesh, фазы 1–2: ключ устройства в Android Keystore, сопряжение по ссылке/QR с HMAC-доказательством и 6-значным кодом на обоих телефонах, взаимный TLS 1.3 с закреплёнными ключами, только частные сети, три типизированные операции, Resource Governor (4 профиля, лимит ответа, облачное ядро только с разрешения), отзыв, история задач, mDNS, фоновая служба, экран Mesh; сквозной тест «эмулятор ↔ второй узел на CI»
52. Скорость моделей на телефоне: llama.cpp всегда собирается с оптимизацией даже в debug-APK (на эмуляторе сборка `-O0` давала 6 минут на одно предложение); CPU-варианты ggml (dotprod / fp16 / i8mm / SVE2 / SME) с выбором под процессор при запуске; выравнивание 16 КБ; ответ модели заменяет «передачу…» в одном снимке состояния (CI поймал кадр с двумя одинаковыми ответами); кнопки поиска моделей больше не обрезаются
53. Сквозной тест NEXUS Mesh на эмуляторе зелёный: Android Keystore ↔ второй узел на CI, TLS 1.3, задачи «состояние», «замер», «ответ модели» (прогон `44837929289`). Найдено и исправлено по пути: запись в сокет с главного потока Android (кнопки «Код совпадает», «Проверить связь», «Выполнить там») — теперь у каждой сессии свой поток записи; ожидание ссылки в скрипте; таймер приглашения не держит UI-тест

54. Ответ на технический аудит ([разбор по пунктам](NEXUS_AUDIT_RESPONSE_RU.md)): PIN-сопряжение LINK v1 (SHA-256 от 6 цифр, офлайн-перебор ≈2,5 с) удалено — LINK v2 стал правами поверх сопряжения Mesh; Mesh-протокол v2 — код сопряжения с commit–reveal и одно сопряжение на приглашение (код v1 подгонялся перебором ключей при утёкшей ссылке); CVE-2026-70640 к NEXUS неприменима (v1.0.0 = `b1b770d` содержит исправление `5c0d188`, пример llama.android не собирается), а своя JNI-обёртка стала безопасной по построению; 8 находок гигиены JNI; тесты жизненного цикла движка под CheckJNI и отдельный прогон с AddressSanitizer; перезапуск фоновой службы Mesh на Android 12+ больше не может уронить приложение; дубль `org.json` убран

55. Сквозной тест нашёл ошибку, которую прежние прогоны не видели: чат через Ollama падал с `NetworkOnMainThreadException` — шлюз Ollama делал HTTP-запрос в главном потоке, где чат собирает ответ. Исправлено в шлюзе и в чате, контракт `ModelGateway` требует main-safe, JVM-тест воспроизводит ошибку. Результаты прогонов аудита: ASan — `44884844833` (чисто, канарейка поймана), эмулятор — `44884844749` (жизненный цикл движка под CheckJNI, Mesh v2 со вторым узлом)

## Расхождения документации

- `PROJECT_MASTER_SPEC_RU.md` и `NEXUS_ENGINEERING_AUDIT_RU.md` описывают
  состояние до этой серии: утверждения «Устройство — заглушка», «Council
  переиспользует AI Studio», «нет докачки», «Ollama через OpenAI-shim», «нет
  Accessibility», «нет коннекторов» устарели — актуальное состояние здесь.
- Handoff-документ, п. 13.2 — исправлено; п. 13.3 — см. «Не реализовано».
