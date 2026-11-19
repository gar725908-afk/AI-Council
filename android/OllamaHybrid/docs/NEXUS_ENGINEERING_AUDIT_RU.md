# NEXUS — инженерный аудит Android-каркаса
## Проверка исходников Bridge и воспроизводимости текущего Git snapshot

**Дата проверки:** 19 ноября 2026 года  
**Проверенная база:** GitHub `main`, Android-каталог `android/OllamaHybrid/`  
**Назначение:** зафиксировать факты и ограничения до дальнейшей реализации.

---

## 1. Результат проверки Kotlin-каркаса

Файлы `BridgeContracts.kt`, `RootAccessProbe.kt` и `BridgeContractSmokeTest.kt` проверены отдельно от Android-приложения.

Использован локальный Kotlin/JVM compiler 1.9.0 на JRE 21.0.12.1. Команда:

```bash
kotlinc BridgeContracts.kt RootAccessProbe.kt BridgeContractSmokeTest.kt \
  -include-runtime -d bridge-smoke.jar
java -jar bridge-smoke.jar
```

**Результат: PASS** — исходники компилируются как отдельный Kotlin/JVM набор, smoke test завершился успешно.

Проверенные assertions:
- регистрация инструмента и отклонение дублирующего ID;
- выбор транспорта с максимальным приоритетом среди доступных;
- отказ, если подходящий транспорт недоступен;
- разрешение read-only операции при наличии capability;
- отказ destructive операции без подтверждения;
- отказ при approval, привязанном к другому request ID;
- принятие approval, привязанного к текущим request ID и tool ID.

Это ограниченная проверка Bridge-контрактов. Она **не является** Gradle-сборкой Android-приложения, проверкой APK или device test.

## 2. Что пока не проверено

- Полная Gradle-сборка проекта.
- AndroidManifest и запуск Activity.
- Установка и запуск на физическом Android-устройстве.
- Root Manager prompt и подтверждение фактического UID 0 на устройстве.
- Shizuku/Sui Binder lifecycle.
- Выполнение Android Bridge tools через настоящий executor.
- Вызовы инструментов из AI Council.
- Производительность, энергопотребление и поведение после process death.

Текущий Git tree не содержит необходимых корневых Gradle-файлов/Wrapper, AndroidManifest и полноценной точки входа приложения. Поэтому Gradle build на основании текущей копии репозитория не подтверждён.

## 3. Замечания к Bridge-контрактам перед production

Это не утверждение о наличии уязвимости в работающем приложении: в репозитории пока представлен каркас контрактов, а не полный execution pipeline. Следующие проверки должны быть добавлены при интеграции:

1. **Privilege ↔ transport enforcement.** Policy должна проверять не только capability, но и то, что выбранный transport действительно разрешён текущим privilege snapshot.
2. **Typed argument schema.** Сейчас аргументы представлены как `Map<String, String>`; до production нужны схемы типов, диапазонов, обязательности, нормализации и валидации.
3. **Approval lifecycle.** Approval должен иметь срок действия, одноразовое использование и привязку к точным параметрам действия или их digest, а не только к request ID и tool ID.
4. **Snapshot freshness.** Необходимо проверять возраст CapabilitySnapshot и перечитывать разрешения перед чувствительной операцией.
5. **Central executor.** Нужен единый pipeline: validate → policy → approval → route → execute → verify → audit.
6. **Root probe threading.** `RootAccessProbe.check()` блокирующий; вызывать его следует вне UI thread, например через IO dispatcher или управляемый executor.
7. **Result verification.** Возврат exit code или текстовый ответ инструмента не всегда доказывает, что целевое состояние действительно достигнуто.

Положительный момент текущего каркаса: Root probe использует фиксированную команду `su -c "id -u"`, проверяет UID 0 и не принимает произвольную shell-строку от модели. Это правильная отправная точка, но не полноценный Root service.

## 4. Статус проверки validator

Скрипт `tools/validate_android_bridge.py` требует наличие `app/src/main/AndroidManifest.xml` наряду с Bridge-файлами и спецификациями. В проверенном Git tree Manifest отсутствует. Поэтому текущая опубликованная копия не удовлетворяет всем предусловиям этого validator; его успешное завершение для текущего Git snapshot не заявляется.

## 5. Следующие инженерные действия

1. Найти и синхронизировать полный Android source tree, упомянутый в прежнем README как отдельный архив.
2. Сверить его с уже опубликованным Bridge scaffold и всеми архитектурными документами.
3. Восстановить воспроизводимую Gradle-сборку.
4. Добавить тесты на privilege/transport, approval expiry/replay, schema validation, stale capabilities и отказ доступа.
5. Только после этого связывать Bridge с Council и интерфейсом NEXUS.

---

## 6. Принцип статуса

**Compiled Kotlin scaffold ≠ Android app build ≠ installed APK ≠ verified device integration.**

Каждый из этих уровней фиксируется отдельно. В документации NEXUS нельзя повышать статус компонента без соответствующей проверки.
