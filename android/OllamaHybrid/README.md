# NEXUS — Android personal AI ecosystem

**NEXUS** — персональная Android AI-экосистема, оформленная в художественной вселенной SIRIUS. Внутренний канон и продуктовая терминология: [NEXUS CODEX](docs/NEXUS_CODEX_RU.md).

> SIRIUS и внеземное происхождение технологии — художественная часть проекта, а не утверждение о реальном источнике программного обеспечения.

## Документы NEXUS
- [NEXUS CODEX — история, канон, имена и визуальный язык](docs/NEXUS_CODEX_RU.md)
- [План технического перехода и реализации](docs/NEXUS_TECHNICAL_ROADMAP_RU.md)

## Базовая архитектура
NEXUS продолжает Android-архитектуру, ранее разработанную под рабочим названием **OllamaHybrid**. Каталог `android/OllamaHybrid/`, namespace `ai.hybrid.commandcenter` и текущие технические identifiers пока сохраняются для совместимости. Массовое переименование будет отдельным этапом после восстановления полного проекта и проверки сборки.

- [Master specification](docs/PROJECT_MASTER_SPEC_RU.md)
- [V3 full AI ecosystem architecture](docs/V3_FULL_AI_ECOSYSTEM_ARCHITECTURE_RU.md)
- [UI design system](docs/UI_DESIGN_SYSTEM_RU.md)
- [Hybrid Universe experimental UI specification](docs/HYBRID_UNIVERSE_EXPERIMENTAL_V2_RU.md)
- [Evolution Core](docs/EVOLUTION_CORE_ARCHITECTURE_RU.md)
- [Android Bridge architecture](docs/ANDROID_BRIDGE_ARCHITECTURE_RU.md)
- [Android Bridge test plan](docs/ANDROID_BRIDGE_TEST_PLAN_RU.md)

## Status — important
Этот каталог GitHub пока **не является полным независимо собираемым Android Studio project**. В проверенном дереве присутствуют спецификации, Gradle-конфигурация app-модуля и начальные Bridge-файлы, но отсутствуют корневые Gradle-файлы/Wrapper, AndroidManifest, Compose entry point, полноценные экраны и ресурсы.

Полный рабочий Android source tree должен быть найден и синхронизирован до массовых изменений кода. Целевые архитектурные документы не являются доказательством реализации функций.

### Принципы
- Local-first; cloud providers — opt-in.
- Сменяемые модели; постоянное состояние принадлежит NEXUS runtime.
- Typed tools, allowlists, permission checks, approval gates и audit.
- Android Bridge управляет только Android-устройством и работает в пределах доступных Android API/разрешений.
- Recovery Core изолирован от AI-editable workspace.
- Hybrid Mesh отложен за пределы V3.
- APK публикуется в GitHub Releases только после реальной сборки, установки и QA.

## Сохранение desktop AI-Council
Python/PySide6 desktop-приложение в корне репозитория остаётся отдельным продуктом и не переименовывается этим Android-переходом.
