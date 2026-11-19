# NEXUS — Android personal AI ecosystem

**NEXUS** — персональная Android AI-экосистема, оформленная в художественной вселенной SIRIUS. Внеземное происхождение — художественный канон, не утверждение о реальном источнике ПО.

## Документы
- [NEXUS CODEX](docs/NEXUS_CODEX_RU.md) — история, канон, имена и визуальный язык.
- [Техническая дорожная карта](docs/NEXUS_TECHNICAL_ROADMAP_RU.md).
- [Инженерный аудит](docs/NEXUS_ENGINEERING_AUDIT_RU.md).
- [V3 Full AI Ecosystem](docs/V3_FULL_AI_ECOSYSTEM_ARCHITECTURE_RU.md).
- [Evolution Core](docs/EVOLUTION_CORE_ARCHITECTURE_RU.md).

## Сборка
В каталоге проекта находятся корневые Gradle-конфигурации, Android Manifest и Jetpack Compose entry point. CI настроен на Gradle 8.13 и JDK 17: debug APK и unit tests.

Локальная сборка из Android Studio:
1. Откройте каталог android/OllamaHybrid.
2. Установите Android SDK Platform 36 и JDK 17.
3. Синхронизируйте Gradle-проект.
4. Запустите задачу :app:assembleDebug.

**Важно:** Gradle Wrapper пока не включён. Пока не подтверждены чистая CI-сборка, unit tests и установка на реальное Android-устройство, приложение не считается проверенным APK. Сейчас нет подтверждения успешной Android-сборки.

## Реализовано в Genesis runtime

- **AI Studio:** Compose-экран настройки провайдера, endpoint, модели и потокового запроса.
- **OpenAI-compatible gateway:** Chat Completions SSE через OkHttp; подходит для endpoint, которые действительно поддерживают этот протокол, включая OpenAI-compatible сервисы и локальные Ollama/LM Studio/vLLM серверы.
- **Anthropic gateway:** нативный Messages API, потоковые content_block_delta события.
- **Gemini gateway:** нативный streamGenerateContent с SSE.
- **Provider registry:** выбор OpenAI-compatible, Anthropic Claude или Google Gemini в интерфейсе; endpoint, model и ключи раздельны для каждого профиля.
- **Model discovery:** проверка соединения и загрузка списка моделей через /models, /v1/models или Gemini /v1beta/models; найденную модель можно выбрать в UI.
- **Credential vault:** API-ключи шифруются AES-GCM ключом Android Keystore; в обычных настройках хранятся только endpoint и имя модели.
- **NEXUS COUNCIL:** восемь преднастроенных ролей; одновременно работают максимум три агента; затем Manager выполняет отдельный синтез.
- **Mission Journal:** локальный SQLite-журнал миссий, результатов агентов и событий; фиксируются состояния и ошибки.
- **Recovery marker:** при запуске незавершённые RUNNING/VERIFYING миссии помечаются INTERRUPTED; они не повторяются автоматически.
- **Failure handling:** ограничение времени на агента и синтез, кооперативная отмена через coroutine lifecycle, сохранение частичных результатов.

## Ограничения текущей реализации

Это **первый runtime slice**, а не законченная экосистема:
- Все роли Council пока используют один выбранный провайдер и одну модель. Маршрутизация разных моделей по ролям ещё не реализована.
- OpenAI-compatible сервер должен поддерживать Chat Completions и SSE. Совместимость по одному лишь названию провайдера не гарантируется.
- Локальный движок llama.cpp не встроен. Для локального inference подключается уже запущенный сетевой сервер, доступный телефону. Локальные HTTP endpoint разрешены только в loopback/private LAN; трафик по HTTP не шифруется.
- Mission Journal сохраняет миссии и ответы агентов, но полноценная история чатов, вложения, полнотекстовый индекс и поиск по архиву ещё не реализованы. Содержимое миссий хранится в app-private SQLite; отдельное шифрование всей базы данных пока не добавлено.
- Recovery пока фиксирует прерванное состояние; автоматический безопасный resume и WorkManager orchestration ещё не реализованы.
- Tool Gateway, MCP client, browser automation, Material Library processing, Voice, Android Bridge executor, Shizuku/Root и Evolution executor ещё не подключены как production-функции.
- Каталог моделей запрашивает первую страницу ответа провайдера; постраничная загрузка и расширенная диагностика лимитов/прав доступа пока не реализованы.
- Unit tests добавлены, но прохождение тестов и Android APK build должны быть подтверждены GitHub Actions.
- UI не сообщает об успешном действии Android Bridge, пока bridge не реализован.

## Подключение AI Studio

1. Откройте **AI Studio** или **Council**.
2. Выберите тип API: OpenAI-compatible, Anthropic или Gemini.
3. Введите базовый endpoint и точное имя модели. Поле endpoint уже содержит пример для выбранного протокола.
4. Добавьте API-ключ выбранного облачного провайдера и нажмите **Сохранить**. Ключ хранится отдельно от endpoint/model через Android Keystore.
5. Для локального OpenAI-compatible сервера без авторизации ключ можно оставить пустым.
6. Введите задачу и запустите одиночный запрос или Council.

Для локального OpenAI-compatible сервера пример endpoint: http://192.168.1.10:11434/v1. Укажите реальный адрес сервера в своей сети и убедитесь, что он доступен с телефона. Не отправляйте облачные API-ключи через незашифрованный HTTP. Не публикуйте ключи в GitHub, логах или скриншотах.

## Сохранность проекта

- Разработка ведётся в nexus/genesis-foundation; ветка main остаётся без изменений.
- Desktop Python/PySide6 AI-Council не изменяется.
- Исторические namespace и applicationId не переименовываются автоматически.
- APK не коммитится в исходники и не публикуется до сборки, установки и QA.

## Архитектурные принципы
- Local-first; облачные провайдеры — opt-in.
- Модели сменяемы; состояние миссий и память принадлежат NEXUS runtime.
- Typed tools, allowlists, permission checks, approval gates и audit.
- Android Bridge работает только в пределах реально доступных Android API и разрешений.
- Recovery Core изолирован от AI-editable workspace.
- Hybrid Mesh не входит в текущий V3 scope.
