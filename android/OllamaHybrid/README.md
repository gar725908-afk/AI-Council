# NEXUS — Android personal AI ecosystem

**NEXUS** — персональная Android AI-экосистема, оформленная в художественной вселенной SIRIUS. Внеземное происхождение — художественный канон, не утверждение о реальном источнике ПО.

## Документы
- [NEXUS CODEX](docs/NEXUS_CODEX_RU.md) — история, канон, имена и визуальный язык.
- [Техническая дорожная карта](docs/NEXUS_TECHNICAL_ROADMAP_RU.md).
- [Инженерный аудит](docs/NEXUS_ENGINEERING_AUDIT_RU.md).
- [V3 Full AI Ecosystem](docs/V3_FULL_AI_ECOSYSTEM_ARCHITECTURE_RU.md).
- [Evolution Core](docs/EVOLUTION_CORE_ARCHITECTURE_RU.md).
- [Local-first Council и Android execution](docs/LOCAL_FIRST_COUNCIL_AND_ANDROID_EXECUTION_RU.md) — локальная модель-Manager, веб-сессии ИИ, ручной режим, будущий Desktop Link и NEXUS FORGE.
- [Ollama LAN Node](docs/NEXUS_LAN_OLLAMA_NODE_RU.md) — использование локальной модели на домашнем ПК через Ollama API по Wi-Fi/LAN, с маршрутизацией, потоковой передачей, отказоустойчивостью и безопасным pairing.

## Сборка
В каталоге проекта находятся корневые Gradle-конфигурации, Android Manifest и Jetpack Compose entry point. CI настроен на Gradle 8.13 и JDK 17: debug APK и unit tests.

Локальная сборка из Android Studio:
1. Откройте каталог android/OllamaHybrid.
2. Установите Android SDK Platform 36 и JDK 17.
3. Синхронизируйте Gradle-проект.
4. Запустите задачу :app:assembleDebug.

**Важно:** Gradle Wrapper пока не включён. Пока не подтверждены чистая CI-сборка, unit tests и установка на реальное Android-устройство, приложение не считается проверенным APK. Не считать текущий scaffold готовым приложением.

## Реализовано в Genesis runtime

- **AI Studio:** Compose-экран настройки провайдера, endpoint, модели и потокового запроса.
- **OpenAI-compatible gateway:** Chat Completions SSE через OkHttp; подходит для endpoint, которые действительно поддерживают этот протокол, включая OpenAI-compatible сервисы и локальные Ollama/LM Studio/vLLM серверы.
- **Anthropic gateway:** нативный Messages API, потоковые content_block_delta события.
- **Gemini gateway:** нативный streamGenerateContent с SSE.
- **Provider registry:** отдельные профили OpenAI, DeepSeek, OpenRouter, Groq, Mistral, Together AI, Fireworks AI, xAI Grok, Cerebras, NVIDIA API Catalog, Ollama local и Custom OpenAI-compatible, а также нативные Anthropic Claude и Google Gemini. Endpoint, model и ключи раздельны для каждого профиля.
- **Model discovery:** проверка соединения и загрузка списка моделей через /models, /v1/models, Anthropic /v1/models или Gemini /v1beta/models; найденную модель можно выбрать в UI.
- **Credential vault:** API-ключи шифруются AES-GCM ключом Android Keystore; в обычных настройках хранятся только endpoint и имя модели.
- **Legacy NEXUS COUNCIL runtime:** восемь преднастроенных ролей; одновременно работают максимум три агента; для каждой роли можно назначить отдельный провайдер и собственный model ID override. В текущем HTTP-потоке Manager пока синтезирует ответы через основной профиль AI Studio.
- **Local-first Council control plane foundation:** добавлены режимы LOCAL_AUTONOMOUS / LOCAL_ASSISTED / MANUAL, типизированные решения локального Manager, интерфейсы participant adapters, проверка opt-in для API/Desktop и цикл повторных делегирований. Это пока не подключено к экрану Council и не является production-реализацией локальной модели.
- **Mission Journal:** локальный SQLite-журнал миссий, результатов агентов и событий; фиксируются состояния и ошибки. Цели миссий, ответы, ошибки и детали событий шифруются AES-GCM через Android Keystore; старые plaintext-записи остаются читаемыми.
- **NEXUS CHAT:** локальные многоходовые диалоги, сохранение сообщений, восстановление последнего разговора, создание нового диалога; последние сообщения передаются выбранной модели как контекст. Для Council передаётся ограниченный транскрипт последних реплик.
- **Recovery marker:** при запуске незавершённые RUNNING/VERIFYING миссии помечаются INTERRUPTED; они не повторяются автоматически.
- **Failure handling:** ограничение времени на агента и синтез, кооперативная отмена через coroutine lifecycle, сохранение частичных результатов; тайм-аут одного агента не прерывает остальных; кнопка остановки миссии отменяет активные HTTP-вызовы.

## Ограничения текущей реализации

Это **первый runtime slice**, а не законченная экосистема:
- Новый Local-first Council control plane — foundation контрактов и исполнения поручений через адаптеры. On-device inference engine, production WebView session adapters, UI/DI integration и durable binding к MissionJournal пока не подключены.
- Текущий legacy Council использует HTTP gateway; синтезатор Manager работает через основной выбранный профиль AI Studio. Переход на локальную модель Manager ещё впереди.
- Для каждой роли Council поддерживается выбор провайдера. Конфигурация endpoint/model хранится на уровне профиля провайдера, поэтому индивидуальный выбор разных моделей одного и того же провайдера для каждой роли ещё не реализован.
- OpenAI-compatible сервер должен поддерживать Chat Completions и SSE. Совместимость по одному лишь названию провайдера не гарантируется.
- Локальный движок llama.cpp не встроен. Для текущего локального inference подключается уже запущенный сетевой endpoint; это не означает, что модель выполняется непосредственно на телефоне. Локальные HTTP endpoint разрешены только в loopback/private LAN; трафик по HTTP не шифруется.
- Бесплатные ИИ через WebView — целевой транспорт. У каждого сервиса отдельная сессия, пользователь самостоятельно проходит вход. Доступность автоматизации зависит от сайта и его правил; CAPTCHA/OTP и защитные проверки не обходятся. До production реализации используется assisted/manual сценарий.
- Desktop Link пока только архитектурное направление. Текущий Android runtime не управляет ПК и не требует купленного сервера. Работа над desktop AI Consilium не меняется этой Android-веткой.
- NEXUS FORGE пока архитектурный этап: встроенная среда разработки и сборка APK на Android ещё не реализованы.
- Mission Journal и NEXUS CHAT шифруют содержимое миссий, результаты агентов, заголовки и тексты сообщений на уровне полей с помощью AES-GCM/Android Keystore. Идентификаторы, статусы, временные метки, endpoint и названия моделей остаются метаданными SQLite. Вложения, полнотекстовый индекс, семантический поиск и поиск по архиву ещё не реализованы.
- Recovery фиксирует прерванное состояние; автоматический безопасный resume и WorkManager orchestration ещё не реализованы. Тайм-ауты агентов и синтезатора покрыты unit-тестами. SQLite-операции экрана выполняются вне главного UI-потока.
- Tool Gateway, MCP client, production browser automation, Material Library processing, Voice, Android Bridge executor, Shizuku/Root и Evolution executor ещё не подключены как production-функции.
- Каталог моделей запрашивает первую страницу ответа провайдера; постраничная загрузка и расширенная диагностика лимитов/прав доступа пока не реализованы.
- Unit tests добавлены, но прохождение тестов и Android APK build должны быть подтверждены GitHub Actions.
- UI не сообщает об успешном действии Android Bridge, пока bridge не реализован.

## Подключение AI Studio (существующий дополнительный механизм)

1. Откройте **AI Studio** или **Council**.
2. Выберите профиль: OpenAI, DeepSeek, OpenRouter, Groq, Mistral, Together AI, Fireworks AI, xAI Grok, Cerebras, NVIDIA API Catalog, Ollama, Custom OpenAI-compatible, Anthropic или Gemini.
3. Введите базовый endpoint и точное имя модели.
4. API-ключ требуется только для выбранного облачного API-провайдера. Ключ хранится через Android Keystore.
5. Для локального OpenAI-compatible сервера без авторизации ключ можно оставить пустым.

Эта функция остаётся дополнительным вариантом. Целевая базовая схема NEXUS — on-device Local Manager + сменяемые участники Council, а не обязательные платные API.

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
