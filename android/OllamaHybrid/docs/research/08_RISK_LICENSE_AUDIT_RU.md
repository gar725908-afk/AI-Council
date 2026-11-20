# Исследование 8 — риски и лицензии

Это инженерный обзор условий, а не юридическая консультация. Использование
«только для себя» не отменяет лицензий авторов: обязанности возникают, как
только APK или исходники передаются кому-то ещё (а у AGPL — и при доступе
по сети).

## 1. Лицензия самого NEXUS

В репозитории `gar725908-afk/AI-Council` нет файла `LICENSE` — по умолчанию
все права у владельца. Поэтому:

- подключать можно библиотеки под MIT, Apache-2.0, BSD, ISC, OFL — с
  сохранением уведомлений;
- код под GPL-3.0 / AGPL-3.0 **не копировать**: распространение такого
  APK потребует открыть весь NEXUS под той же лицензией. Решение о смене
  лицензии NEXUS — только за владельцем;
- LGPL-3.0 (Operit): допускает использование как отдельной заменяемой
  библиотеки; копирование кода в NEXUS делает эту часть LGPL.

## 2. Зависимости NEXUS сейчас

Лицензии взяты из опубликованных Maven POM (кэш Gradle) или файлов
`third_party_licenses/`.

| Компонент | Лицензия | Обязанности |
|---|---|---|
| AndroidX, Jetpack Compose, kotlinx-coroutines 1.11.0, kotlinx-serialization 1.8.1 | Apache-2.0 | уведомление, текст лицензии |
| OkHttp 4.12.0 | Apache-2.0 | то же |
| PDFBox-Android 2.0.27.0 | Apache-2.0 | то же |
| LiteRT-LM 0.16.1 | Apache-2.0 | то же |
| ZXing core 3.5.3 | Apache-2.0 (родительский POM) | то же |
| Shizuku API / provider 13.1.5 | MIT (POM) | уведомление |
| llama.cpp `v1.0.0` | MIT | уведомление |
| ML Kit Text Recognition 16.0.1, Code Scanner 16.1.0 | ML Kit Terms of Service (закрытые, Google) | условия Google; нужны Google Play services |
| Шрифты Exo 2, Inter, JetBrains Mono, Noto Sans JP (подмножество) | SIL OFL 1.1 | текст OFL рядом со шрифтом (есть в `third_party_licenses/`) |
| Иконки Lucide | ISC | уведомление (есть) |
| Conscrypt 2.5.2 (только тесты) | Apache-2.0 | — (не попадает в APK) |

**Пробел:** в приложении нет экрана «Лицензии» со всеми уведомлениями
Apache/MIT — его нужно добавить до того, как APK будет кому-то передан.

## 3. Исследованные проекты

| Лицензия | Проекты | Что можно |
|---|---|---|
| Apache-2.0 | AI Edge Gallery, Mika, MNN, OpenDroid, OpenCyvis, Mobile MCP, LiteRT-LM, Open-AutoGLM (код) | брать код и идеи с уведомлением и файлом NOTICE |
| MIT | PocketPal, Maid, Nyral, Zafiro, Phone-MCP, llama.cpp, whisper.cpp | брать с уведомлением |
| LGPL-3.0 | Operit | идеи; код — только как отдельная заменяемая библиотека |
| GPL-3.0 | OpenMinis, Aether, AiPaca | только идеи |
| AGPL-3.0 | ChatterUI, ServLlama, Local AI (производная RikkaHub) | только идеи |
| AGPL-3.0 + коммерческая | OmniBot (AGPL для некоммерческого/личного, иначе договор) | только идеи |
| Нет файла лицензии | DroidClaw (в README — «mit», файла нет) | только идеи: без файла лицензии права на код не подтверждены |

## 4. Лицензии моделей

| Условия | Модели (из каталога исследования) | На что обратить внимание |
|---|---|---|
| Apache-2.0 | Qwen2 / 2.5 (кроме 3B), Qwen3, Qwen3.5, Qwen2.5-VL 3B, Vikhr, QVikhr, T-lite 2.1, SmolLM2/3, SmolVLM, TinyLlama, Granite 3.3, InternLM 2.5, Mistral 7B v0.3, MiniCPM-V 4.5, Gemma 4 (сборки LiteRT-LM в `litert-community`) | свободно |
| MIT | DeepSeek-R1 Distill, Phi-3 / Phi-4, GLM-4 9B 0414, AutoGLM-Phone 9B | свободно |
| Gemma Terms of Use | Gemma 2, Gemma 3, Gemma 3n, EmbeddingGemma | запрещённые сценарии по Prohibited Use Policy; часть оригиналов — по запросу доступа |
| Llama 3.1 / 3.2 Community License | Llama 3.1 8B, Llama 3.2 1B / 3B | указание «Built with Llama», Acceptable Use Policy |
| Qwen Research License | Qwen2.5 3B, Qwen2.5-Omni 3B | некоммерческие исследования |
| Mistral Research License | Ministral 8B 2410 | исследовательское, не продакшн |
| LFM Open License 1.0 | LFM2, LFM2-VL | ограничения по выручке организации |
| Yi License, Falcon LLM License, Baichuan2 Community License | Yi 1.5, Falcon3 / Falcon-H1, Baichuan2 | прочитать условия перед распространением |
| FAIR Noncommercial Research | MobileLLM-R1 | только некоммерческое исследование, доступ по запросу |
| OpenMDW-1.1 | Nemotron 3.5 Bolt E4B (в каталоге NEXUS) | лицензия указана в каталоге и показывается перед загрузкой |
| YandexGPT-5-Lite-8B License | YandexGPT 5 Lite 8B (в каталоге NEXUS) | условия Яндекса |

Перед загрузкой NEXUS показывает лицензию модели («Скачивая, ты принимаешь лицензию …»).

## 5. Технические риски Android

| Риск | Подтверждение | Что делает NEXUS |
|---|---|---|
| Фоновые службы останавливаются прошивками производителей | статьи Nyral о «живучести» агентов; опыт Mika с беспроводной отладкой | Mesh — foreground service с уведомлением; честное «Прервано» в истории |
| Страницы 16 КБ (Android 15+) | 4 КБ-выравнивание не грузится на таких устройствах | с среза 52 нативные библиотеки выровнены под 16 КБ |
| Разрешение локальной сети (Android 17, targetSdk 37+) | [developer.android.com](https://developer.android.com/privacy-and-security/local-network-permission) | учесть при повышении targetSdk (сейчас 35) |
| NNAPI устарел (Android 15) | [NNAPI Migration Guide](https://developer.android.com/ndk/guides/neuralnetworks/migration-guide) | не используется |
| MediaProjection на Android 14+ — согласие на каждую сессию | изменения Android 14 | снимок экрана — одноразовое согласие |
| Accessibility в Google Play — отдельная политика | правила Google Play | APK распространяется вне Play; при публикации потребуется декларация |
| Нехватка памяти при загрузке модели | — | оценка «потянет ли телефон» до загрузки; Resource Governor |
| Перегрев и разряд | — | Resource Governor (нагрев, заряд) для чата и для Mesh |

## 6. Риски безопасности

| Риск | Что делает NEXUS / что нужно |
|---|---|
| Файлы моделей из неизвестных источников (парсер GGUF — поверхность атаки) | Каталог: закреплённые ревизии + SHA-256. Найденный файл: только чтение заголовка и проверка типа; запуск — по решению владельца. Риск остаётся — экран найденных файлов прямо предупреждает, что файлы не из каталога не сверены с эталонным SHA-256 |
| Инъекции через веб-страницы, документы, ответы MCP-серверов | Выводы участников совета и внешние данные помечаются как недоверенный ввод (`SynthesisInputTest`); MCP-серверы подключает только владелец |
| Ключи API | Android Keystore (AES-GCM); не отправляются в Mesh, модели, логи и отчёты |
| Root / Shizuku | только фиксированные команды, без свободной оболочки |
| Сеть своих телефонов | взаимный TLS, сопряжение с кодом на обоих экранах, только частные адреса — [модель безопасности Mesh](../NEXUS_MESH_SECURITY_MODEL_RU.md) |
| llama.cpp RPC | по README проекта «fragile and insecure» — не выставлять в сеть; план — только через закрытый канал Mesh |

## 7. Неопределённости этого исследования

- Ни одно стороннее приложение не устанавливалось; возможности — по README
  и коду.
- Производительность моделей и движков не сравнивалась на одинаковых
  телефонах.
- Качество русского языка моделей не измерялось.
- Лицензия ONNX Runtime в этом срезе не перепроверена по файлу (клон
  недоступен из среды исследования).
