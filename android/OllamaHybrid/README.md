# NEXUS — Android personal AI ecosystem

**NEXUS** — персональная Android AI-экосистема, оформленная в художественной вселенной SIRIUS. Внеземное происхождение — художественный канон, не утверждение о реальном источнике ПО.

## Документы
- [NEXUS CODEX](docs/NEXUS_CODEX_RU.md) — история, канон, имена и визуальный язык.
- [Техническая дорожная карта](docs/NEXUS_TECHNICAL_ROADMAP_RU.md).
- [Инженерный аудит](docs/NEXUS_ENGINEERING_AUDIT_RU.md).

## Сборка
В каталоге проекта созданы корневые Gradle-конфигурации, Android Manifest и стартовый Jetpack Compose экран. CI устанавливает Gradle 8.13 и JDK 17, собирает debug APK и запускает unit tests.

Локальная сборка из Android Studio:
1. Откройте каталог `android/OllamaHybrid`.
2. Установите Android SDK Platform 36 и JDK 17.
3. Синхронизируйте Gradle-проект.
4. Запустите конфигурацию app или задачу `:app:assembleDebug`.

**Важно:** Gradle Wrapper пока не включён. CI использует установленный Gradle 8.13. Перед передачей проекта как автономного архива нужно добавить стандартные wrapper scripts и wrapper JAR.

## Статус реализации
Текущая версия — **NEXUS Android foundation / Genesis scaffold**, а не завершённый AI-продукт. Стартовый Compose экран и навигационные заглушки созданы. AI Studio, Council runtime, browser, provider registry, persistent memory, Android Bridge executor, Shizuku/Root integration, local inference и Evolution pipeline ещё не подключены к UI как законченные функции.

Ветка `nexus/genesis-foundation` сохраняет изменения отдельно от `main`. Desktop Python/PySide6 AI-Council не изменяется.

## Архитектурные принципы
- Local-first; cloud providers — opt-in.
- Модели сменяемы; состояние миссий и память принадлежат NEXUS runtime.
- Typed tools, allowlists, permission checks, approval gates и audit.
- Android Bridge работает только в пределах реально доступных Android API и разрешений.
- Recovery Core изолирован от AI-editable workspace.
- Hybrid Mesh не входит в текущий V3 scope.
- APK публикуется в Releases только после сборки, установки и QA.
