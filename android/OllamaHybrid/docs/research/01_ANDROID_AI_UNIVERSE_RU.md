# Исследование 1 — Android AI Universe

Каталог открытых Android AI-приложений, агентов и движков из MASTER RESEARCH
PROMPT. Дата среза: **2026-11-21**.

## Как собраны данные

- Исходники: неглубокий клон каждого репозитория (`git clone --depth 1`),
  в таблице указан коммит. Лицензия — по файлу `LICENSE` в корне.
- Стек и движки: по файлам сборки (`build.gradle(.kts)`, `libs.versions.toml`,
  `package.json`, `pubspec.yaml`, `CMakeLists.txt`) и по коду.
- Возможности: по README; ключевые — сверены с кодом (путь к файлу указан
  там, где это важно).
- Релизы: GitHub Releases (тег и дата последнего релиза). Есть ли в релизе
  APK, проверено только там, где на APK прямо ссылается README.
- Ничего не устанавливалось и не запускалось на телефоне.

Статусы в матрицах: **Реализовано** — описано в README и подтверждается
кодом (найдены соответствующие классы или зависимости; качество и глубина
запуском не проверялись), **Заявлено** — только README, **В коде** — найдено
в коде, в README не описано, **Через внешний сервис**, **Через ПК**, **Не
обнаружено**, **Не проверено**.

## A. Android AI-приложения

| # | Проект | Лицензия | Стек | Коммит (дата) | Последний релиз | Где работает |
|---|---|---|---|---|---|---|
| 1 | [Operit AI](https://github.com/AAswordman/Operit) | LGPL-3.0 | Kotlin (+TS/JS) | `7a39941` (2026-11-12) | v1.12.2 (2026-09-18) | На телефоне |
| 2 | [Google AI Edge Gallery](https://github.com/google-ai-edge/gallery) | Apache-2.0 | Kotlin | `2bf7956` (2026-11-20) | 1.0.23 (2026-11-04); APK в релизах и Google Play (README) | На телефоне |
| 3 | [PocketPal AI](https://github.com/a-ghorbani/pocketpal-ai) | MIT | TypeScript, React Native | `ed0b965` (2026-11-19) | v1.24.0 (2026-11-17); App Store / Google Play (README) | На телефоне |
| 4 | [Mika](https://github.com/wangzan101/Mika) | Apache-2.0 | Kotlin | `7101e99` (2026-11-21) | v0.1.50 (2026-11-20); APK в релизах (README) | На телефоне; модели — облако по ключу |
| 5 | [OpenMinis](https://github.com/OpenMinis/OpenMinis) | GPL-3.0 | Kotlin + Swift | `1a71df0` (2026-10-26) | 0-beta-build46 (пререлиз, 2026-11-20) | На телефоне; модели — облако |
| 6 | [MNN / MNN Chat](https://github.com/alibaba/MNN) | Apache-2.0 | C++ движок, Kotlin-приложение | `da16b74` (2026-11-20) | движок 3.6.2 (2026-10-29); APK MNN Chat по ссылкам в `apps/Android/MnnLlmChat/README.md` и Google Play | На телефоне |
| 7 | [OpenDroid](https://github.com/JMAN730/opendroid) | Apache-2.0 | Kotlin | `9b471bc` (2026-11-10) | v1.0.9 (2026-11-10) | На телефоне |
| 8 | [Maid](https://github.com/Mobile-Artificial-Intelligence/maid) | MIT | TypeScript (llama.rn) | `60206bd` (2026-10-29) | v3.0.0 (2026-03-10) | На телефоне |
| 9 | [OmniBot](https://github.com/omnimind-ai/OmniBot) | AGPL-3.0 для некоммерческого/личного использования, иначе коммерческая (двойная) | Kotlin + Flutter | `a11cdf3` (2026-11-21) | v0.7.0.6 (пререлиз, 2026-11-18) | На телефоне; модели — облако |
| 10 | [ChatterUI](https://github.com/Vali-98/ChatterUI) | AGPL-3.0 | TypeScript (cui-llama.rn) | `1753a4a` (2026-10-01) | v0.11.0-beta1 (пререлиз, 2026-10-30) | На телефоне |
| 11 | [ServLlama](https://github.com/ArkaneFans/servllama) | AGPL-3.0 | Dart / Flutter | `df0a0f3` (2026-11-19) | 2.0.0-alpha.4 (пререлиз, 2026-11-15); APK в релизах (README) | На телефоне |
| 12 | [Local AI](https://github.com/smartworldarafath/Local-AI) | AGPL-3.0 | Kotlin | `ac354f3` (2026-11-15) | v1.3.2-beta (2026-11-15); APK в релизах (README) | На телефоне |
| 13 | [AiPaca](https://github.com/dojekon/AIPaca) | GPL-3.0 | Kotlin | `b0bb7d3` (2026-11-02) | v0.1.0 (2026-11-02) | На телефоне (arm64, Android 10+) |
| 14 | [Nyral](https://github.com/aixtin/nyral) | MIT | Kotlin | `70cb256` (2026-11-20) | v2.11.1 (2026-11-20); APK в релизах (README) | На телефоне; модели — облако |
| 15 | [Zafiro](https://github.com/niki914/zafiro) | MIT | Kotlin | `ff8253c` (2026-11-21) | v20-2.0.0 (2026-11-18) | На телефоне; модели — облако (BYOK) |
| 16 | [Aether](https://github.com/Zhou-Shilin/Aether) | GPL-3.0 | Kotlin + TypeScript (Pi) | `b6681b8` (2026-11-07) | 2.1.7 (2026-10-11) | На телефоне; модели — облако |
| 17 | [OpenCyvis Phone](https://github.com/opencyvis/opencyvis-phone) | Apache-2.0 | Kotlin | `3acd0a5` (2026-09-19) | v2.0.1 (2026-06-03) | На телефоне (ADB по Wi-Fi или Shizuku) |
| 18 | [DroidClaw](https://github.com/unitedbyai/droidclaw) | файла LICENSE нет; README: «mit» | TypeScript (bun) + Kotlin | `c7c9919` (2026-02-28) | v0.5.3 (2026-02-25) | **Через ПК**: агент на компьютере с `adb` |

Замечания по коду:

- Local AI использует пакет `me.rerere.rikkahub` — это производная от
  RikkaHub (видно по путям тестов, например
  `app/src/test/java/me/rerere/rikkahub/data/ai/mcp/…`).
- В OmniBot есть `LegacyLocalModelDataCleanup.kt` — локальные модели убраны,
  текущий README описывает облачные модели (Gemini и др.).
- Упоминание llama в OpenMinis найдено только в `OpenAIProvider.kt`
  (облачный провайдер) — локального движка нет.

## B. Автономное управление Android

| # | Проект | Лицензия | Стек | Коммит (дата) | Релиз | Где работает |
|---|---|---|---|---|---|---|
| 19 | [Open-AutoGLM](https://github.com/zai-org/Open-AutoGLM) | Apache-2.0 (код) | Python | `86f5538` (2026-03-06) | релизов на GitHub нет | **Через ПК**: Python + ADB (Android) или HDC (HarmonyOS); модель AutoGLM-Phone-9B |
| 20 | [Mobile MCP](https://github.com/mobile-next/mobile-mcp) | Apache-2.0 | TypeScript (Node) | `5b54758` (2026-11-18) | 1.0.18 (2026-11-18) | **Через ПК**: MCP-сервер на компьютере, Android через `adb` |
| 21 | [Phone-MCP](https://github.com/Mirochill/Phone-MCP) | MIT | Java (Android) + JS (relay) | `7a74e71` (2026-10-25) | v0.19.0 (2026-10-25), APK и SHA256SUMS (README) | Телефон + свой relay (Cloudflare Workers или Node) для MCP-клиента |

## C. Движки

| # | Проект | Лицензия | Срез | Релиз |
|---|---|---|---|---|
| 22 | [llama.cpp](https://github.com/ggml-org/llama.cpp) | MIT | в NEXUS закреплён `v1.0.0` (`b1b770d`, 2026-11-13) | — |
| 23 | [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) | Apache-2.0 | `4defab4` (2026-11-20) | v0.19.1 (2026-11-05); в NEXUS — Maven `0.16.1` |
| 24 | [MNN](https://github.com/alibaba/MNN) | Apache-2.0 | `da16b74` (2026-11-20) | 3.6.2 (2026-10-29) |

Подробное сравнение — [Исследование 3](03_INFERENCE_ENGINES_RU.md).

## Сравнительная матрица

| Приложение | Local LLM | Model Manager | Agents / tools | MCP | Browser | Linux | Android Control | Voice | Vision |
|---|---|---|---|---|---|---|---|---|---|
| Operit | Реализовано: MNN + llama.cpp (`llm/llama/CMakeLists.txt`) | Реализовано | Реализовано | Реализовано (в релизе 1.12.2 — исправления MCP) | Реализовано: свой браузер + агент | Реализовано: Ubuntu 24.04 через PRoot | Реализовано: Accessibility, Shizuku, Root | Реализовано: профили TTS/STT | Заявлено |
| AI Edge Gallery | Реализовано: LiteRT-LM 0.18.1 | Реализовано: каталог + свои модели | Реализовано: Agent Skills | В коде: `RunMcpTool.kt`, `McpManagerViewModel.kt` | Не обнаружено | Не обнаружено | Частично: Mobile Actions (FunctionGemma 270M) | Реализовано: ASR и TTS на устройстве | Реализовано: Ask Image |
| PocketPal AI | Реализовано: llama.rn (llama.cpp); GPU OpenCL/Adreno, NPU Hexagon (README) | Реализовано: HF-поиск и загрузка | Реализовано: Talents (инструменты) | Не обнаружено | Частично: веб-поиск и чтение страниц | Не обнаружено | Не обнаружено | Реализовано: whisper.rn, Kokoro (ONNX) | Заявлено |
| Mika | Не обнаружено (облако по ключу) | — | Реализовано | Реализовано: Mika как сервер для ИИ на компьютере | Реализовано: настольный браузер | Реализовано: Linux-песочница (arm64) | Реализовано: фоновый виртуальный экран (`AgentDisplayTest.kt`) | Реализовано: системное распознавание (`SpeechRecognizer`) | Реализовано: отправка экрана модели |
| OpenMinis | Не обнаружено | — | Реализовано | Заявлено | Реализовано | Реализовано: Alpine (iSH / PRoot) | Частично: системные интеграции | Не проверено | Не проверено |
| MNN Chat | Реализовано: MNN | Реализовано | Не обнаружено | Не обнаружено | Не обнаружено | Не обнаружено | Не обнаружено | Реализовано: аудио-ввод (README) | Реализовано: image-to-text, text-to-image |
| OpenDroid | Реализовано: LiteRT-LM 0.14.0 | Реализовано: загрузка, SHA-256, импорт | Реализовано | В коде: `McpServer.kt`, `McpConnectionPool.kt` | Не проверено | Не обнаружено | Реализовано: Accessibility | Реализовано: wake word, STT, TTS | Заявлено |
| Maid | Реализовано: llama.rn | Реализовано: загрузки с HF | Не обнаружено | Не обнаружено | Не обнаружено | Не обнаружено | Не обнаружено | Через отдельное приложение Maise | Не проверено |
| OmniBot | Не обнаружено (локальные модели удалены) | — | Реализовано: Skills | Заявлено | Реализовано | Реализовано: Alpine | Реализовано: без root + опциональный root | Не проверено | Не проверено |
| ChatterUI | Реализовано: cui-llama.rn | Реализовано: импорт или внешний файл | Не обнаружено | Не обнаружено | Не обнаружено | Не обнаружено | Не обнаружено | Реализовано: системный TTS | Не проверено |
| ServLlama | Реализовано: llama-server + MNN (`mnn_engine`) | Реализовано: Discover | Реализовано: инструменты ассистентов | В коде | Не проверено | Не обнаружено | Не обнаружено | Реализовано: sherpa-onnx (STT/TTS) | Реализовано: mmproj |
| Local AI | Реализовано: LiteRT-LM | Реализовано | Реализовано | Реализовано: SSE и Streamable-HTTP | Не проверено | Реализовано: PRoot | Частично: уведомления и др. | Реализовано: sherpa-onnx | Заявлено |
| AiPaca | Реализовано: llama-server; CPU / OpenCL / Hexagon | Реализовано: HF, докачка, SHA-256 | Не обнаружено | Не обнаружено | Не обнаружено | Не обнаружено | Не обнаружено | Не обнаружено | Не проверено |
| Nyral | Не обнаружено (облако) | — | Реализовано: 32 инструмента | В коде: `McpClientManagerTest.kt` | Реализовано: свой агент-браузер | Частично: локальная оболочка, SSH | Реализовано: Accessibility | Реализовано: голосовой ввод (`AudioRecord`) | Заявлено |
| Zafiro | Не обнаружено (BYOK) | — | Реализовано | Реализовано: клиент и сервер | Не проверено | Реализовано: Shell, Python | Реализовано: a11y + скриншоты | Частично: перехват ассистента (root) | Реализовано: скриншоты |
| Aether | Не обнаружено | — | Реализовано: Pi, субагенты | Реализовано: Pi MCP Adapter | Через внешний сервис (поиск) | Реализовано: Alpine | Частично: Shizuku / Termux | Не проверено | Не проверено |
| OpenCyvis | Заявлено: «cloud or local» | Не проверено | Реализовано | Не обнаружено | Не обнаружено | Не обнаружено | Реализовано: виртуальный дисплей, ADB / Shizuku | Реализовано: sherpa-onnx | Реализовано: понимание экрана |
| DroidClaw | Через ПК (Ollama) | — | Реализовано (на ПК) | Не обнаружено | Не обнаружено | Не обнаружено | Через ПК: adb + accessibility tree | Не обнаружено | Реализовано: скриншот как запасной вариант |
| Open-AutoGLM | Через ПК: AutoGLM-Phone-9B | — | Реализовано (на ПК) | Через внешний сервис (Midscene.js) | Не обнаружено | Не обнаружено | Через ПК: ADB / HDC | Не обнаружено | Реализовано: мультимодальное понимание экрана |
| Mobile MCP | — | — | — | Реализовано: MCP-сервер | Не обнаружено | Не обнаружено | Через ПК: adb, accessibility snapshots | Не обнаружено | Частично: скриншоты |
| Phone-MCP | — | — | — | Реализовано: 66 инструментов | Не обнаружено | Не обнаружено | Реализовано: Shizuku (свой дисплей), Accessibility | Не обнаружено | Частично: PNG / MP4 приложения |
| **NEXUS** (для сравнения) | Реализовано: llama.cpp + LiteRT-LM | Реализовано: 12 моделей, SHA-256, найденные файлы | Реализовано: Council, Tool Gateway | Реализовано: клиент | Реализовано: свой браузер, без агента | Не обнаружено | Реализовано: Accessibility, Shizuku, Root (фиксированные команды) | Реализовано: системные STT/TTS | Не обнаружено |

## Рейтинг (на основании матрицы, без запуска на устройстве)

1. **Лучшее готовое приложение-агент:** Operit — единственный проект, где
   локальные модели (MNN + llama.cpp), MCP, браузер-агент, Linux, Accessibility,
   Shizuku и Root собраны вместе. Лицензия LGPL-3.0.
2. **Лучший локальный движок:** llama.cpp — шире всего по моделям (GGUF),
   CPU-варианты, OpenCL/Adreno, Hexagon. См. Исследование 3.
3. **Лучший Model Center:** AiPaca (докачка в 1–8 соединений, SHA-256,
   Wi-Fi-режим, профили ускорителей, бенчмарки) и Google AI Edge Gallery
   (каталог LiteRT + бенчмарк).
4. **Лучший автономный агент на самом телефоне:** Mika и OpenCyvis — работают
   на отдельном виртуальном экране, не мешая владельцу.
5. **Лучший Android Bridge:** Phone-MCP (сессии на отдельных дисплеях через
   Shizuku, явные «аренды», 66 инструментов) — но требует своего relay.
6. **Лучший MCP:** Phone-MCP как сервер; как клиент — Local AI (SSE +
   Streamable-HTTP) и Aether (Stdio, HTTP, SSE, Unix sockets).
7. **Лучший браузерный агент:** Operit, Nyral, Mika — собственный браузер с
   передачей капчи человеку.
8. **Лучшая память:** Local AI (локальный RAG с выбранной embedding-моделью),
   Nyral (bge-small ONNX + ключевые слова в SQLite), Operit (графовая память).
9. **Лучшая мультимодальность:** MNN Chat (текст, изображение, аудио,
   генерация изображений) и AI Edge Gallery (изображение, аудио, ASR/TTS).
10. **Лучший фундамент для NEXUS:** собственный код NEXUS + llama.cpp +
    LiteRT-LM, с идеями из Operit, AiPaca, Mika/OpenCyvis и Phone-MCP. Причины
    и что именно переносить — [Исследование 6](06_INTEGRATION_MATRIX_RU.md).
