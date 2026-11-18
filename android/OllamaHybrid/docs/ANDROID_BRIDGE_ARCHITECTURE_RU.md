# Hybrid Android Bridge — архитектура и требования

Статус: согласованная целевая архитектура, реализация поэтапная. Дата: 19.11.2026.

## 1. Цель и принцип
Создать мост между AI Council и Android. Мост выбирает прямой публичный Android API, Intent/deep link, app-specific adapter, Shizuku/Root, Accessibility либо Vision/gestures. Не имитировать касание, если действие доступно через программный интерфейс.

## 2. Профили полномочий
- Standard: app sandbox, публичные Android API, runtime permissions, SAF, Intent, фоновые задачи по правилам Android.
- Shizuku/ADB: операции shell UID на конкретной версии Android; возможности проверяются динамически. ADB не равен Root.
- Shizuku/Sui Root: Binder/service работает с UID 0 и пользователь выдал доступ.
- Direct Root: su через установленный Root Manager (например, Magisk); подтверждение и проверка UID 0.
- Accessibility/Vision: отдельные UI-интерфейсы, не являются Root и не обходят secure windows.

Root — максимальный профиль, но не blanket consent.

## 3. Запрос разрешений
Нельзя автоматически получить все системные разрешения при установке APK. Root выдаёт Root Manager по отдельному запросу. Accessibility, Notification Listener, MediaProjection, SAF и специальные доступы требуют соответствующих системных экранов/диалогов. Доступность зависит от Android API, OEM, прошивки и политики приложений.
Hybrid Permission Center должен: диагностировать устройство; объяснять разрешения; запускать официальный системный flow; после возврата проверять фактический grant; показывать недоступные возможности; не блокировать Standard при отсутствии Root.

## 4. Модули
androidbridge/core: BridgeRouter, ToolRegistry, CapabilityRegistry, BridgeSession.
native: Intent, PackageManager, SAF/ContentResolver, notifications, settings.
privileged: Shizuku/Sui Binder, identity and permission checks.
root: su probe, Root session, typed operations, Root audit.
accessibility: node tree, semantic actions, gestures.
vision: MediaProjection, OCR/vision, element grounding.
apps: per-app adapter registry.
security: PermissionManager, ActionPolicy, ApprovalManager, AuditLog.
tools: typed tools exposed to AI Council.

## 5. Маршрутизация
Приоритет: Native API → app/Intent adapter → Shizuku Root → Direct Root → Shizuku ADB → Accessibility → Vision/Gestures. Реальный порядок может зависеть от инструмента и capability. Router выбирает только реально доступный transport, поддерживаемый инструментом; иначе возвращает отказ, а не фиктивный успех.

## 6. Root Mode
- su probe только после явного действия пользователя.
- подтверждать UID 0, не наличие файла su.
- различать denial, missing manager, timeout и success.
- не подделывать и не кешировать согласие Root Manager как постоянное.
- запрещён универсальный su -c с текстом от LLM.
- Root операции — typed tools с allowlist, аргументами, timeout, output cap и audit.
- удаление, package install/uninstall, системные настройки и запись в системные разделы требуют отдельного подтверждения.
- Recovery Core, signing keys и защитные журналы исключены из AI-editable операций.

## 7. Контракт и безопасность AI
AI вызывает зарегистрированный tool. Валидатор проверяет schema/capability, policy проверяет risk, approval manager запрашивает согласие, router выбирает transport, executor выполняет, verifier проверяет результат, audit фиксирует request/tool/transport/result/approval/time, Council получает результат.
ApprovalGrant должен создаваться приложением, привязываться к request ID и tool ID и никогда не приниматься из JSON модели.

## 8. Возможности
Запуск приложений; package metadata; разрешённые файлы; device diagnostics; доступные уведомления; разрешённые настройки; accessibility tree; click по node; ввод; Back/Home; screen capture с consent; OCR/vision; типизированные Root package/filesystem/process tools; per-app adapters.
Нельзя обещать доступ к приватным данным всех приложений. Root не отменяет шифрование, SELinux, аппаратные ограничения и защиту аккаунтов.

## 9. Этапы
1. Foundation: contracts, capabilities, registry, policy, audit.
2. Native adapters.
3. Shizuku/Sui API, Provider, Binder lifecycle, permission flow, UID checks.
4. Direct Root session + typed executor.
5. Accessibility adapter.
6. MediaProjection + OCR/Vision.
7. App adapters.
8. Council tool calling, approval, verifier.
9. Device/OEM/reboot/revocation testing.
10. APK build, signing, device QA and GitHub Release.

## 10. Фактический статус
Есть старый AccessibilityService/PhoneToolExecutor, Council bus/orchestrator/watchdog, начальные Bridge contracts/tool registry/risk gate и Root UID probe. Полная Shizuku integration, Root command service, Permission Center UI, Root tools и Council tool calling ещё не реализованы. llama.cpp JNI, PDF/OCR/video pipelines также pending. APK не считается готовым без реальной Gradle-сборки и установки.
