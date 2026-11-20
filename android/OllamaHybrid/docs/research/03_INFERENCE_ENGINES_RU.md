# Исследование 3 — движки инференса на Android

Срез: **2026-11-21**. Источники — исходники и документация движков (коммиты
указаны), официальная документация Android. Производительность здесь не
сравнивается: на одних и тех же телефонах ничего не мерилось, а чужие цифры
из README без условий замера ничего не доказывают.

## Кратко

| Движок | Лицензия | Форматы | CPU | GPU | NPU | Чем силён | В NEXUS |
|---|---|---|---|---|---|---|---|
| **llama.cpp** | MIT | GGUF (любые архитектуры из списка ggml) | ARMv8.0…v9.2, варианты под dotprod / fp16 / i8mm / SVE2 / SME, KleidiAI | OpenCL (Adreno 750/810/830/840 проверены авторами; Adreno 6xx в телефонах — «скорее всего нет»), Vulkan | Hexagon — отдельная сборка Qualcomm-тулчейном в Docker | Самый широкий выбор моделей и квантований; файл можно запускать на месте | **Используется**: `v1.0.0`, свой JNI, CPU-варианты |
| **LiteRT-LM** | Apache-2.0 | `.litertlm` (готовые сборки Google и сообщества) | да | да (файлы `*-gpu.litertlm`) | заявлено в README («GPU and NPU accelerators»), для конкретных моделей не проверено | Официальные Gemma 4 E2B/E4B, мультимодальность, Kotlin API | **Используется**: Maven `0.16.1` |
| **MNN** (Alibaba) | Apache-2.0 | `.mnn` (конвертированные: Qwen, DeepSeek, Gemma, …) | да | OpenCL / Vulkan (опции сборки `MNN_OPENCL`, `MNN_VULKAN`) | Qualcomm QNN (опция `MNN_QNN`); работа на конкретных телефонах не проверена | Мультимодальное приложение MNN Chat (текст, изображение, аудио, диффузия); используется в Operit и ServLlama | Кандидат |
| **ONNX Runtime** | MIT (файл LICENSE в этом срезе не перепроверен: клон репозитория недоступен из среды) | ONNX | да | через Execution Providers | QNN EP (Qualcomm) | Речь (Kokoro, Piper), эмбеддинги, OCR | Кандидат для речи |
| **sherpa-onnx** | Apache-2.0 (файл LICENSE `k2-fsa/sherpa-onnx`) | ONNX | да | — | — | Готовые STT/TTS/VAD для Android; используется в ServLlama, Local AI, OpenCyvis | Кандидат для офлайн-голоса |
| **whisper.cpp** | MIT (файл LICENSE `ggml-org/whisper.cpp`) | GGML (`ggml-*.bin`) | да | как ggml | — | Распознавание речи; PocketPal (whisper.rn) | Кандидат для голоса |
| **ExecuTorch** | BSD (файл LICENSE `pytorch/executorch`) | `.pte` | XNNPACK | Vulkan | Qualcomm QNN, MediaTek | Экспорт из PyTorch | Не нужен сейчас: в исследованных приложениях не найден |
| **MediaPipe LLM Inference** | Apache-2.0 | `.task` | да | да | — | Более ранний путь Google для Gemma; Gallery и OpenDroid сейчас собраны на LiteRT-LM | Не нужен: в NEXUS уже LiteRT-LM |
| **llama-server** (часть llama.cpp) | MIT | GGUF | как llama.cpp | как llama.cpp | как llama.cpp | Отдельный процесс + OpenAI-совместимый HTTP API; так сделаны AiPaca и ServLlama | Идея для локального API |
| **NNAPI** | — | — | — | — | — | **Устарел**: deprecated начиная с Android 15 ([NNAPI Migration Guide](https://developer.android.com/ndk/guides/neuralnetworks/migration-guide)) | Не использовать |

## llama.cpp

- Исходник в NEXUS: `FetchContent` тега `v1.0.0` (коммит `b1b770d…`,
  2026-11-13), файл `app/src/main/cpp/CMakeLists.txt`.
- **CPU.** Начиная со среза 52 NEXUS собирает CPU-бэкенд ggml отдельными
  библиотеками под разные процессоры (`GGML_BACKEND_DL` +
  `GGML_CPU_ALL_VARIANTS`): `android_armv8.0_1`, `armv8.2_1` (dotprod),
  `armv8.2_2` (+fp16), `armv8.6_1` (+i8mm), `armv9.0_1` (SVE2),
  `armv9.2_1/2` (SME). Лучшая выбирается при запуске функцией
  `ggml_backend_load_all_from_path` — так же устроен официальный пример
  `examples/llama.android` в репозитории llama.cpp. KleidiAI (оптимизированные
  ядра Arm) включён.
- **Сборка.** Движок теперь всегда оптимизирован (`-O3`), в том числе внутри
  debug-APK. До этого debug-APK содержал `-O0`-сборку: на эмуляторе CI
  Qwen3.5 0.8B отвечал одним предложением **371 с** (снимок
  `24_found_answer.png` прогона `44819203755`); после исправления та же
  задача — **13.4 с** (прогон `44831772256`) и 17.9 с (прогон `44837929289`).
  На эмуляторе выбран вариант «CPU: SSE3 SSSE3 AVX F16C REPACK» — видно в
  профиле узла Mesh.
- **GPU (OpenCL).** По `docs/backend/OPENCL.md` (v1.0.0): поддерживаются
  Q4_0, Q4_1, Q5_0, Q5_1, Q8_0, Q4_K, Q5_K, Q6_K, MXFP4, IQ4_NL; проверены
  Adreno 750 (8 Gen 3), 810 (7s Gen 3), 830 (8 Elite), 840 (8 Elite Gen 5);
  «A6x GPUs in phones are likely not supported». Требует OpenCL SDK при
  сборке. В NEXUS **не включено** — следующий кандидат (см. дорожную карту).
- **NPU (Hexagon).** `docs/backend/snapdragon/`: сборка Docker-образами
  Qualcomm (`ghcr.io/snapdragon-toolchain/arm64-android`). AiPaca и PocketPal
  заявляют этот путь. В NEXUS не включено: тяжёлая сборка и выигрыш, который
  нужно мерить на конкретных Snapdragon.
- **Форматы.** Любой GGUF из списка поддерживаемых архитектур; NEXUS читает
  заголовок GGUF сам (`GgufHeader.kt`) и запускает найденные на телефоне
  файлы на месте.

## LiteRT-LM

- Репозиторий `google-ai-edge/LiteRT-LM` (`4defab4`, 2026-11-20), релиз
  v0.19.1 (2026-11-05). В NEXUS — Maven-артефакт
  `com.google.ai.edge.litertlm:litertlm-android:0.16.1`; Google AI Edge
  Gallery использует 0.18.1, OpenDroid — 0.14.0.
- README: Android, iOS, Web, Desktop; «Peak performance via GPU and NPU
  accelerators»; vision и audio входы; Kotlin API — «Stable».
- Найденная в CI несовместимость: LiteRT-LM 0.16.1 вызывает
  `SendChannel.close$default`, которого нет в kotlinx-coroutines 1.10.x —
  NEXUS обновлён до 1.11.0 (срез 47). После этого Gemma 4 E2B ответила на
  эмуляторе по-русски за 21.1 с (снимок `19_gemma_answer.png`, прогон
  `44814838761`).
- Обновление до 0.18/0.19 — отдельная задача с проверкой на эмуляторе.

## MNN

- `alibaba/MNN` (`da16b74`, 2026-11-20), релиз 3.6.2. Приложение MNN Chat
  (`apps/Android/MnnLlmChat`) распространяется APK-ссылками на alicdn и в
  Google Play (README).
- Плюсы: готовые MNN-сборки моделей (пример: `taobao-mnn/Qwen3-4B-MNN`,
  `llm.mnn.weight` 2.46 ГБ), мультимодальность в одном приложении.
- Минусы для NEXUS: третий формат моделей, конвертация вне телефона, своя
  JNI-обвязка. Добавлять имеет смысл, только если нужен NPU/GPU-путь, которого
  нет в llama.cpp и LiteRT-LM для нужной модели.

## Речь

| Задача | Варианты | Рекомендация |
|---|---|---|
| Распознавание офлайн | whisper.cpp (MIT; base q5_1 — 60 МБ, small q5_1 — 190 МБ), sherpa-onnx | whisper.cpp: тот же ggml, что уже собран в NEXUS |
| Синтез офлайн по-русски | Piper (голоса ru_RU: denis, dmitri, irina, ruslan) через sherpa-onnx или ONNX Runtime | Piper: у Kokoro русских голосов нет |
| Сейчас в NEXUS | системные `SpeechRecognizer` и `TextToSpeech` | оставить как путь по умолчанию, офлайн-движки — опция |

## Вывод

1. **Оставить два движка:** llama.cpp (GGUF, максимум моделей, найденные
   файлы, CPU-варианты) и LiteRT-LM (Gemma 4 от Google, GPU).
2. **Следующий шаг по скорости:** OpenCL-бэкенд llama.cpp для Adreno 7xx/8xx
   с автоматическим откатом на CPU — по образцу AiPaca (профили ускорителей
   и откат при ошибке старта).
3. **Голос офлайн:** whisper.cpp (на том же ggml) + Piper ru_RU.
4. **MNN, ExecuTorch, MediaPipe** — не добавлять без конкретной модели, ради
   которой это нужно. NNAPI не использовать (устарел).
