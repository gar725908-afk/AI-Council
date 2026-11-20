# Исследование: Android AI-приложения, модели и движки для NEXUS

Выполнено по MASTER RESEARCH PROMPT. Срез данных — **2026-11-21**.

| № | Документ | Содержание |
|---|---|---|
| 1 | [Android AI Universe](01_ANDROID_AI_UNIVERSE_RU.md) | 24 проекта: лицензии, стек, коммиты, релизы, где исполняются; сравнительная матрица и рейтинг |
| 2 | [AI Models Catalog](02_AI_MODELS_CATALOG_RU.md) | 66 моделей с реальными размерами файлов, ревизиями и лицензиями из API Hugging Face; группы по размеру; речь |
| 3 | [Inference Engines](03_INFERENCE_ENGINES_RU.md) | llama.cpp, LiteRT-LM, MNN, ONNX Runtime, sherpa-onnx, whisper.cpp, ExecuTorch, MediaPipe, NNAPI; выбор |
| 4 | [Android Agents](04_ANDROID_AGENTS_RU.md) | Механизмы управления Android, агенты на телефоне и на ПК, MCP |
| 5 | [NEXUS Architecture](05_NEXUS_ARCHITECTURE_RU.md) | Целевая архитектура и происхождение идей |
| 6 | [Integration Matrix](06_INTEGRATION_MATRIX_RU.md) | Что брать, адаптировать, писать; ответы по каждому проекту |
| 7 | [Development Roadmap](07_DEVELOPMENT_ROADMAP_RU.md) | Шаги с зависимостями и критериями готовности |
| 8 | [Risk & License Audit](08_RISK_LICENSE_AUDIT_RU.md) | Лицензии кода и моделей, риски Android и безопасности |

## Метод

- 22 репозитория склонированы (`git clone --depth 1`); лицензии — по файлам
  `LICENSE`, движки — по файлам сборки, возможности — по README и коду.
- Релизы — через GitHub Releases (тег, дата, признак пререлиза).
- Модели — API Hugging Face: размер каждого файла, ревизия, лицензия, gated.
- Ничего не устанавливалось на телефон; производительность не сравнивалась.
  Где данных нет — так и написано («не проверено»).

## Главные выводы

1. Двух движков NEXUS (llama.cpp + LiteRT-LM) достаточно; ускорение —
   через CPU-варианты (сделано) и OpenCL для Adreno (следующий шаг).
2. В ходе исследования найдена и исправлена реальная проблема NEXUS:
   llama.cpp в debug-APK собирался без оптимизации (`-O0`).
3. Самые ценные идеи для переноса: виртуальный дисплей агента (Mika,
   OpenCyvis, Phone-MCP), устойчивость агентного цикла (DroidClaw), профили
   ускорителей и бенчмарк (AiPaca, PocketPal), локальный RAG (Local AI,
   Nyral), офлайн-голос (whisper.cpp + Piper ru_RU).
4. Код под GPL/AGPL (OpenMinis, Aether, AiPaca, ChatterUI, ServLlama,
   Local AI, OmniBot) и без лицензии (DroidClaw) — только как источник идей.
