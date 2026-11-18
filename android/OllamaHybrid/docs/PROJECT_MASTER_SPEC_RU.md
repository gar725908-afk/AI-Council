# OllamaHybrid — единая мастер-спецификация

Версия спецификации 1.0. Дата фиксации: 19.11.2026.

## 1. Цель
OllamaHybrid — Android AI Command Center: локальные модели на телефоне, multi-agent AI Council, библиотека пользовательских материалов и управление самим Android-устройством через разрешённые системные интерфейсы.

Приложение должно работать без Root. При наличии Root включается расширенный Root Mode с максимально доступными полномочиями, но рискованные действия требуют отдельного согласия.

## 2. Принятые решения
1. Android — самостоятельная платформа; мост управляет телефоном, не Windows/ПК.
2. Android Bridge — отдельная подсистема и единый API между AI Council и Android.
3. Native-first: API/Intent/app adapter предпочтительнее имитации касаний.
4. Профили: Standard, Extended (Shizuku/ADB), Root (Shizuku/Sui Root или direct su).
5. Root не равен универсальному разрешению на всё и не снимает SELinux, шифрование, OEM и аппаратные ограничения.
6. AI вызывает зарегистрированные typed tools; произвольный shell от модели запрещён.
7. Чувствительные и необратимые действия требуют подтверждения, привязанного к конкретной операции.
8. Recovery Core изолирован от AI-editable workspace.
9. Git хранит исходники и документацию; APK публикуется в GitHub Releases после сборки и проверки.
10. Нельзя заявлять о готовности сборки или аппаратных тестов без фактического выполнения.

## 3. Подсистемы

### Local AI Runtime
Цель: llama.cpp через Android NDK/JNI или встроенный runtime; ABI device support (приоритет arm64-v8a); загрузка/выгрузка моделей; память/контекст/потоки; GPU/Vulkan при наличии; persistent model registry; прямые GGUF-файлы с проверкой размера, checksum, quantization и лицензии.
Статус: OpenAI-compatible provider adapter Council есть. llama.cpp JNI/runtime не подключён. Endpoint 127.0.0.1:8080 не отвечает, пока inference server не встроен/запущен.

### AI Council
Параллельные агенты, общий контекст/transcript, роли и модели, request/parent IDs, защита от дублей, watchdog (running/completed/failed/timeout), отмена, таймауты, retries/backoff, сбор/сравнение ответов, consensus/scoring и Android Bridge tool calling.
Статус: shared bus, параллельный orchestrator, watchdog journal и OpenAI-compatible HTTP adapter — v0.4 scaffold. Persistent agent profiles, local runtime, retry/consensus и Bridge tool calling не завершены.

### Material Library
SAF import, app-private originals, manifest/metadata/checksum, indexing TXT/MD/JSON/CSV/XML/HTML и Office Open XML, lexical retrieval, context injection. Далее: embeddings/vector search, PDF text extraction, OCR, video frame sampling и audio transcription.
Статус: базовый импорт и lexical retrieval есть; полноценные PDF/OCR/video/audio/embedding pipeline не реализованы.

### Android Bridge
Native API, Intent/deep links, app-specific adapters, Shizuku/Sui Binder, Direct Root, Accessibility tree, MediaProjection/Vision, permission manager, policy, approval, audit. Подробности — в ANDROID_BRIDGE_ARCHITECTURE_RU.md.
Статус: существующий AccessibilityService и PhoneToolExecutor есть; добавлены начальные contracts/tool registry/risk gate и Root UID probe. Полный Shizuku, Root command service, Permission Center UI, все adapters и Council tool integration pending.

### Self-Development / Recovery Core
AI-editable workspace отдельно от recovery code; snapshot до изменений; reviewed diff и approval; SHA-256 manifest; atomic backup/restore; retention; pending marker; startup health validation; изолированный build/test; rollback; signing и сохранение последнего рабочего APK.
Статус: workspace, snapshots, logs, pending marker и rollback реализованы. Не восстанавливает установленный APK, не защищён от Root/компрометации ОС/подмены APK. SHA-256 manifests, AI patcher и build/test runner pending. Android app не может незаметно переписать собственный installed APK.

### Studio / Models / Settings
Каталог моделей и verified GGUF downloads, persistent queue/model registry, memory/storage compatibility checks, документы/медиа, Council profiles, Bridge dashboard, Permission Center и action journal.
Статус: базовый Compose UI, seed catalog и импорт есть; production download manager и полноценные in-app редакторы документов/изображений/видео pending.

## 4. Permission Center и Root
Первый запуск диагностирует Android API, ABI, RAM/storage, Root Manager/UID 0, Shizuku Binder/permission/UID, Accessibility, Notification Listener, SAF grants, runtime permissions и фоновые ограничения. MediaProjection запрашивается в момент использования.
Android не позволяет APK безусловно получить все разрешения во время установки. Системные подтверждения Root Manager, Accessibility, Notification Listener, MediaProjection и специальных доступов должны быть явными. После возврата приложение повторно проверяет фактическое состояние. Отказ Root не блокирует Standard mode.

Root tools должны иметь allowlist, typed arguments, timeout, output cap, audit и approval. LLM не получает универсальный su -c.

## 5. Приватность
Не коммитить пароли, cookies, токены, личные переписки, пользовательские документы, локальные журналы и signing keys. Секреты хранить защищённо. Пользовательские материалы остаются локальными, если он явно не выбрал облачный endpoint.

## 6. GitHub и релизы
Репозиторий gar725908-afk/AI-Council содержит существующее desktop Python приложение. Android edition располагается в android/OllamaHybrid/, не ломая desktop-код. Для каждой версии: source + docs, static checks, Gradle build, установка на устройство, проверка подписи/SHA-256, GitHub Release с APK/changelog. APK не следует коммитить в историю Git при каждой сборке — использовать Release assets.

## 7. Ближайшие этапы
1. Проверка v0.4/v0.5 и настройка Gradle wrapper/Android build host.
2. Shizuku/Sui API, Binder lifecycle, ADB-vs-Root identity.
3. Direct Root typed command service и Permission Center.
4. Bridge Dashboard, capability detection, Native adapters.
5. Подключение Accessibility к общему router.
6. Council tool calling, approval, verifier и audit.
7. llama.cpp runtime и model registry.
8. Material multimodal pipelines.
9. Усиление Recovery Core и автоматических тестов.
10. APK build, device QA, GitHub Releases.

## 8. Проверяемый статус
Проект Kotlin/Compose, minSdk 26, compileSdk 36, Java target 17; source scaffold 0.5.0. Нет Gradle wrapper, Gradle executable и Android SDK в текущей среде. Реальный APK build/install не подтверждён. Статический validator и отдельная компиляция Bridge scaffold — отдельные ограниченные проверки.
