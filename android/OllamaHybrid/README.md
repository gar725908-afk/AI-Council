# NEXUS — Android personal AI ecosystem

**NEXUS** — персональная Android AI-экосистема, оформленная в художественной вселенной SIRIUS. Внеземное происхождение — художественный канон, не утверждение о реальном источнике ПО.

## Документы
- [NEXUS CODEX](docs/NEXUS_CODEX_RU.md) — история, канон, имена и визуальный язык.
- [Техническая дорожная карта](docs/NEXUS_TECHNICAL_ROADMAP_RU.md).
- [Инженерный аудит](docs/NEXUS_ENGINEERING_AUDIT_RU.md).
- [V3 Full AI Ecosystem](docs/V3_FULL_AI_ECOSYSTEM_ARCHITECTURE_RU.md).
- [Evolution Core](docs/EVOLUTION_CORE_ARCHITECTURE_RU.md).

## Сборка
В каталоге проекта находятся корневые Gradle-конфигурации, Android Manifest и Jetpack Compose entry point. CI использует Gradle 8.13 и JDK 17, собирает debug APK и запускает unit tests.

Локальная сборка из Android Studio:
1. Откройте каталог `android/OllamaHybrid`.
2. Установите Android SDK Platform 36 и JDK 17.
3. Синхронизируйте Gradle-проект.
4. Запустите задачу `:app:assembleDebug`.

**Важно:** Gradle Wrapper пока не включён. До успешного CI clean build и установки на реальное Android-устройство приложение не считается проверенным APK.

## Реализовано в Genesis runtime

- **AI Studio:** Compose-экран настройки endpoint/model и потокового запроса.
- **Provider transport:** OpenAI Chat Completions-compatible SSE через OkHttp.
- **Credential vault:** API-ключ шифруется AES-GCM ключом Android Keystore; в обычные настройки попадают только endpoint и имя модели.
- **NEXUS COUNCIL:** восемь преднастроенных ролей; до трёх агентов выполняются одновременно; после сбора результатов выполняется отдельный синтез Manager.
- **Failure handling:** ограничение времени на агента и синтез, отмена через coroutine lifecycle, фиксация ошибок отдельных агентов.
- **Network policy в provider adapter:** публичные endpoint должны использовать HTTPS; HTTP допускается только для loopback/private LAN адресов для локального inference.

## Ограничения текущей реализации

Это **первый runtime slice**, а не законченная экосистема:
- Подключён один универсальный OpenAI-compatible транспорт. Отдельные официальные SDK/адаптеры провайдеров ещё не реализованы.
- Локальный движок llama.cpp не встроен. Для Ollama/другого OpenAI-compatible сервера укажите его сетевой endpoint; локальный сервер должен быть доступен телефону.
- Все агенты Council пока используют выбранные в настройках endpoint и модель. Отдельная маршрутизация моделей по ролям ещё не реализована.
- Tool Gateway, browser automation, persistent chat/mission database, WorkManager recovery, Material Library, Voice, Android Bridge, Shizuku/Root и Evolution executor не подключены как production-функции.
- Unit tests добавлены, но их прохождение и Android APK build должны быть подтверждены GitHub Actions.
- UI не сообщает об успешном действии Android Bridge, пока сам bridge не реализован.

## Подключение AI Studio

1. Откройте **AI Studio** или **Council**.
2. Введите OpenAI-compatible base URL, например `https://api.openai.com/v1`, имя модели и API-ключ.
3. Нажмите **Сохранить**. Ключ хранится отдельно от обычных настроек через Android Keystore.
4. Введите задачу и запустите одиночный запрос или Council.

Для локального сервера можно использовать адрес частной сети, например `http://192.168.1.10:11434/v1`, если сервер действительно доступен с телефона и поддерживает OpenAI-compatible Chat Completions streaming. Не публикуйте API-ключи в GitHub, логах или скриншотах. Не используйте незашифрованный HTTP через публичный интернет.

## Сохранность проекта

- Разработка ведётся в `nexus/genesis-foundation`; ветка `main` остаётся без изменений.
- Desktop Python/PySide6 AI-Council не изменяется.
- Исторические namespace и applicationId не переименовываются автоматически.
- APK не коммитится в исходники и не публикуется до сборки, установки и QA.

## Архитектурные принципы
- Local-first; cloud providers — opt-in.
- Модели сменяемые; состояние миссий и память принадлежат NEXUS runtime.
- Typed tools, allowlists, permission checks, approval gates и audit.
- Android Bridge работает только в пределах реально доступных Android API и разрешений.
- Recovery Core изолирован от AI-editable workspace.
- Hybrid Mesh не входит в текущий V3 scope.
