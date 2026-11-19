# NEXUS — инженерный аудит Android-каркаса

**Дата:** 19 ноября 2026 года  
**Ветка:** `nexus/genesis-foundation`

## 1. Подтверждённые проверки

### Bridge contracts
`BridgeContracts.kt`, `RootAccessProbe.kt` и `BridgeContractSmokeTest.kt` отдельно скомпилированы Kotlin/JVM compiler 1.9.0 и smoke test завершился PASS. Это проверяет только Kotlin-контракты и тестовые assertions, не Android APK.

### Genesis Android scaffold
В ветку добавлены:
- корневые `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`;
- Android Manifest;
- стартовый Jetpack Compose `MainActivity`;
- CI workflow для Gradle 8.13 / JDK 17;
- Android README с ограничениями и инструкциями.

Compose экран показывает только честные статусы незапущенных подсистем. AI runtime, Council, Browser и Bridge execution не имитируются.

## 2. Что ещё не подтверждено
- Успешная Android Gradle сборка и выпуск APK в этом окружении.
- Установка и запуск на физическом устройстве.
- Android Studio sync на чистой машине.
- Полная функциональность AI Studio, Council, Browser, provider registry и local inference.
- Room/DataStore persistence, WorkManager lifecycle и recovery.
- Реальная интеграция Shizuku/Sui, Root service и Android tool execution.
- Полный Material Library, voice и Evolution pipeline.

## 3. Ограничения сборки
- CI использует установленный Gradle 8.13; Gradle Wrapper scripts/JAR ещё не добавлены.
- Для локальной сборки нужен JDK 17 и Android SDK Platform 36.
- Текущий Compose экран — минимальный runnable target, не финальный дизайн.
- Namespace и applicationId остаются `ai.hybrid.commandcenter` для сохранения совместимости.

## 4. Следующие обязательные этапы
1. Добавить Gradle Wrapper и добиться успешного CI build.
2. Восстановить/сверить полный Android source tree, если он существует отдельно.
3. Ввести domain/data/feature слои и Room/DataStore.
4. Реализовать Provider API и рабочий AI Studio с безопасным хранением секретов.
5. Реализовать durable Mission Runtime и Council с лимитами параллелизма.
6. Подключить Browser и Material Library.
7. Реализовать единый Tool Gateway и Android Bridge с approval, audit и verifier.
8. Только затем — local inference JNI, Shizuku/Root и Evolution pipeline.

**Статус:** создана стартовая Android Gradle/Compose структура. Полноценная сборка и интеграция на устройстве пока не заявляются.
