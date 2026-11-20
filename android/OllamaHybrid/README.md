# NEXUS — Android personal AI ecosystem

**NEXUS** — персональная Android AI-экосистема, оформленная в художественной вселенной SIRIUS. Внеземное происхождение — художественный канон, не утверждение о реальном источнике ПО.

## Документы
- [Матрица реализации](docs/NEXUS_IMPLEMENTATION_MATRIX_RU.md) — **источник правды**: требование → код → статус → тесты, журнал срезов и честный список того, что не сделано.
- [NEXUS Mesh](docs/NEXUS_MESH_README_RU.md) — закрытая сеть своих телефонов: сопряжение, защищённый канал, задачи; [протокол](docs/NEXUS_MESH_PROTOCOL_RU.md), [безопасность](docs/NEXUS_MESH_SECURITY_MODEL_RU.md), [архитектура](docs/NEXUS_MESH_ARCHITECTURE_RU.md), [тесты](docs/NEXUS_MESH_TEST_PLAN_RU.md).
- [NEXUS CODEX](docs/NEXUS_CODEX_RU.md) — история, канон, имена и визуальный язык.
- [Техническая дорожная карта](docs/NEXUS_TECHNICAL_ROADMAP_RU.md).
- [Инженерный аудит](docs/NEXUS_ENGINEERING_AUDIT_RU.md) — исторический документ, часть выводов устарела (см. матрицу).
- [V3 Full AI Ecosystem](docs/V3_FULL_AI_ECOSYSTEM_ARCHITECTURE_RU.md).
- [Evolution Core](docs/EVOLUTION_CORE_ARCHITECTURE_RU.md).
- [Local-first Council и Android execution](docs/LOCAL_FIRST_COUNCIL_AND_ANDROID_EXECUTION_RU.md).
- [Ollama LAN Node](docs/NEXUS_LAN_OLLAMA_NODE_RU.md) — модель на домашнем ПК через Ollama по Wi-Fi/LAN.
- [NEXUS MODEL CENTER и EVOLUTION](docs/NEXUS_MODEL_CENTER_AND_EVOLUTION_RU.md) — LiteRT-LM и llama.cpp, загрузчик весов, Model Center.
- [NEXUS LINK v1](docs/NEXUS_LINK_PROTOCOL_V1_RU.md), [Extension API](docs/NEXUS_EXTENSION_API_V1_RU.md), [подпись релизов](docs/NEXUS_ANDROID_RELEASE_SIGNING_RU.md), [дизайн-система](docs/UI_DESIGN_SYSTEM_RU.md).

## Сборка

CI (GitHub Actions, Gradle 8.13, JDK 17):
- `nexus-android-debug.yml` — все unit-тесты (`:app:testDebugUnitTest`) и debug APK;
- `android-build.yml` — валидация сборки;
- `nexus-android-emulator.yml` — приложение на Android-эмуляторе с Ollama на раннере: настройка ядра, реальный ответ модели, совет, вкладки, модели на устройстве, сквозной тест NEXUS Mesh; скриншоты, видео, logcat;
- `nexus-android-release.yml` — подписанная сборка (см. документ о подписи).

Локально (Gradle Wrapper в репозитории нет — нужен установленный Gradle 8.13):
1. Android SDK Platform 36, NDK 27.0.12077973, CMake 3.22.1, JDK 17.
2. `cd android/OllamaHybrid`
3. `gradle :app:testDebugUnitTest :app:assembleDebug`

Нативная часть (llama.cpp, закреплённая версия) собирается через CMake/NDK
под arm64-v8a; для эмулятора — `-Pnexus.emulatorAbi=true` (x86_64).

## Что есть в приложении

Полный и проверяемый список — в [матрице реализации](docs/NEXUS_IMPLEMENTATION_MATRIX_RU.md). Коротко:

- **Чат NEX** с потоковым ответом, голосом (распознавание и синтез), командами без ИИ (время, дата, заряд, точный калькулятор, будильник и таймер с подтверждением), питомцем NEX и тремя темами оформления.
- **Ядро ИИ на выбор:** модель на телефоне (LiteRT-LM или llama.cpp, каталог из 12 моделей до 9B с закреплёнными ревизиями и SHA-256, поиск уже скачанных файлов), Ollama на ПК в локальной сети (с загрузкой моделей на ПК), облачные API (OpenAI-совместимые, Anthropic, Gemini). Ключи — в Android Keystore.
- **Council** — совет агентов с журналом миссий и защитой синтеза от инъекций.
- **Архив** (PDF, DOCX, XLSX, текст, OCR), **браузер** с политикой адресов, **MCP-коннекторы**, **Android Bridge** (Accessibility, снимок экрана, Shizuku, Root — только фиксированные команды и с подтверждением).
- **NEXUS Mesh** — сопряжение своих телефонов и задачи между ними по взаимному TLS.
- **Журнал ошибок** и предложение исправлений через ветку и PR (main не трогается).

## Ограничения

- **Ничего не проверено на физическом телефоне** — это обязательный шаг
  приёмки владельцем. Проверки сейчас: unit-тесты на JVM (Robolectric),
  JVM-рендер экранов и сквозные сценарии на Android-эмуляторе в CI.
- Скорость и расход батареи моделей на телефоне не измерялись на устройствах.
- Desktop Agent для NEXUS LINK и NEXUS FORGE не реализованы.
- NEXUS Mesh: фазы 3–6 (WebRTC, планировщик для 3+ узлов, Council на
  нескольких телефонах, распределение одной модели) не реализованы.
- OCR кириллицы ограничен возможностями ML Kit.

## Сохранность проекта

- Разработка ведётся в nexus/genesis-foundation; ветка main остаётся без изменений.
- Desktop Python/PySide6 AI-Council не изменяется.
- Исторические namespace и applicationId не переименовываются автоматически.
- APK не коммитится в исходники и не публикуется до сборки, установки и QA.

## Архитектурные принципы
- Local-first; облачные API — opt-in, бесплатные пользовательские WebView-сессии — отдельный адаптер.
- Локальная модель является интеллектуальным Manager; NEXUS CORE сохраняет mission state, policy, approvals и контроль исполнения.
- Typed tools, allowlists, permission checks, approval gates и audit.
- Android Bridge работает только в пределах реально доступных Android API и разрешений.
- Recovery Core изолирован от AI-editable workspace.
- Desktop Link не требует обязательного облачного сервера и не считается готовым до реализации pairing/crypto/scopes.
