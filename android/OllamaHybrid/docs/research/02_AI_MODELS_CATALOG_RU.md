# Исследование 2 — каталог моделей для Android

Срез: **2026-11-21**. Все размеры файлов, лицензии и ревизии получены из API
Hugging Face (`/api/models/<repo>` и `/api/models/<repo>/tree/main`) в этот
день; ссылка в таблице ведёт на конкретную ревизию.

## Как читать таблицы

- **Файл, ГБ** — реальный размер файла весов на Hugging Face (для разбитых на
  части GGUF — сумма частей).
- **ОЗУ, ГБ (расчёт)** — *оценка, не замер*: `файл × 1.15 + 0.45 ГБ` — так же
  считает NEXUS (`ModelFit` в `nexus/NexusModelCenter.kt`): веса в памяти,
  KV-кэш на 4 096 токенов и сам движок. Для vision-моделей прибавляется
  проектор (`mmproj`).
- **Vision / Audio / Tools / Reasoning** — по карточке модели и составу файлов
  (наличие `mmproj` = поддержка изображений в llama.cpp). «Не проверено» —
  данных недостаточно или поддержка в этом формате не подтверждена.
- **Русский** — «не проверено» для всех, кроме моделей, которые авторы
  заявляют как русскоязычные. Собственных тестов качества не проводилось.
- **CPU / GPU / NPU** — общие для формата: GGUF работает на CPU через
  llama.cpp; GPU через OpenCL поддерживает типы Q4_0, Q4_1, Q5_0, Q5_1, Q8_0,
  Q4_K, Q5_K, Q6_K, MXFP4, IQ4_NL и проверен на Adreno 750/810/830/840
  (`docs/backend/OPENCL.md` в llama.cpp `v1.0.0`); NPU (Hexagon) требует
  отдельной сборки Qualcomm-тулчейном. Для конкретных файлов ниже NPU **не
  подтверждён**. LiteRT-LM: CPU/GPU, файлы `*-gpu.litertlm` — для GPU.
- **Лицензия** — из карточки HF; если в зеркале GGUF её нет, указана лицензия
  базовой модели (с пометкой). «Доступ по запросу» — gated-репозиторий.

Отмечены **полужирным** в разделе «Что из этого в NEXUS» модели, которые уже
стоят в каталоге приложения.

## Модели по размеру файла

### ≤1 ГБ

| Модель | Семейство | Параметры | Тип | Формат | Квант. | Файл, ГБ | ОЗУ, ГБ (расчёт) | Vision | Audio | Tools | Reasoning | Русский | Лицензия | Источник (ревизия) и файл |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Qwen2 1.5B Instruct | Qwen | 1.5B | LLM | GGUF | Q4_K_M | 0.99 | ≈1.6 | — | — | да | — | не проверено | apache-2.0 | [Qwen/Qwen2-1.5B-Instruct-GGUF](https://huggingface.co/Qwen/Qwen2-1.5B-Instruct-GGUF/tree/c62434db64) `qwen2-1_5b-instruct-q4_k_m.gguf` |
| Qwen2.5 0.5B Instruct | Qwen | 0.5B | LLM | GGUF | Q4_K_M | 0.49 | ≈1.0 | — | — | да | — | не проверено | apache-2.0 | [Qwen/Qwen2.5-0.5B-Instruct-GGUF](https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/tree/9217f5db79) `qwen2.5-0.5b-instruct-q4_k_m.gguf` |
| Qwen3 0.6B | Qwen | 0.6B | LLM | GGUF | Q8_0 | 0.64 | ≈1.2 | — | — | да | да | не проверено | apache-2.0 | [Qwen/Qwen3-0.6B-GGUF](https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/tree/23749fefcc) `Qwen3-0.6B-Q8_0.gguf` |
| Qwen3.5 0.8B | Qwen | 0.8B | LLM + vision | GGUF | Q4_K_M | 0.53 | ≈1.1 | да (mmproj) | — | да | да | не проверено | apache-2.0 | [unsloth/Qwen3.5-0.8B-GGUF](https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/tree/6ab461498e) `Qwen3.5-0.8B-Q4_K_M.gguf` |
| ↳ проектор изображений | | | mmproj | GGUF | — | 0.20 | +0.2 | | | | | | | `mmproj-F16.gguf` |
| Qwen3-Embedding 0.6B | Qwen | 0.6B | embeddings | GGUF | Q8_0 | 0.64 | ≈1.2 | — | — | — | — | не проверено | apache-2.0 | [Qwen/Qwen3-Embedding-0.6B-GGUF](https://huggingface.co/Qwen/Qwen3-Embedding-0.6B-GGUF/tree/370f27d755) `Qwen3-Embedding-0.6B-Q8_0.gguf` |
| Gemma 3 270M it | Gemma | 0.27B | LLM | GGUF | Q8_0 | 0.29 | ≈0.8 | — | — | — | — | не проверено | gemma (по базовой google/gemma-3-270m-it) | [ggml-org/gemma-3-270m-it-GGUF](https://huggingface.co/ggml-org/gemma-3-270m-it-GGUF/tree/e7647be17a) `gemma-3-270m-it-Q8_0.gguf` |
| Gemma 3 1B it | Gemma | 1B | LLM | GGUF | Q4_K_M | 0.81 | ≈1.4 | — | — | — | — | не проверено | gemma | [ggml-org/gemma-3-1b-it-GGUF](https://huggingface.co/ggml-org/gemma-3-1b-it-GGUF/tree/f9c28bcd85) `gemma-3-1b-it-Q4_K_M.gguf` |
| Gemma 3 1B it (LiteRT) | Gemma | 1B | LiteRT-LM | LiteRT-LM | Q4 | 0.58 | ≈1.1 | — | — | — | — | не проверено | gemma (доступ по запросу) | [litert-community/Gemma3-1B-IT](https://huggingface.co/litert-community/Gemma3-1B-IT/tree/a9bd85643f) `Gemma3-1B-IT_multi-prefill-seq_q4_ekv4096.litertlm` |
| EmbeddingGemma 300M | Gemma | 0.3B | embeddings | GGUF | Q8_0 | 0.33 | ≈0.8 | — | — | — | — | не проверено | gemma (по базовой google/embeddinggemma-300m) | [ggml-org/embeddinggemma-300M-GGUF](https://huggingface.co/ggml-org/embeddinggemma-300M-GGUF/tree/0f741b5a65) `embeddinggemma-300M-Q8_0.gguf` |
| Llama 3.2 1B Instruct | Llama | 1B | LLM | GGUF | Q4_K_M | 0.81 | ≈1.4 | — | — | да | — | не проверено | llama3.2 | [bartowski/Llama-3.2-1B-Instruct-GGUF](https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/tree/067b946cf0) `Llama-3.2-1B-Instruct-Q4_K_M.gguf` |
| SmolLM2 360M | SmolLM | 0.36B | LLM | GGUF | Q8_0 | 0.39 | ≈0.9 | — | — | — | — | не проверено | apache-2.0 | [HuggingFaceTB/SmolLM2-360M-Instruct-GGUF](https://huggingface.co/HuggingFaceTB/SmolLM2-360M-Instruct-GGUF/tree/593b5a2e04) `smollm2-360m-instruct-q8_0.gguf` |
| SmolVLM 500M | SmolLM | 0.5B | VLM | GGUF | Q8_0 | 0.44 | ≈1.0 | да (mmproj) | — | — | — | не проверено | apache-2.0 | [ggml-org/SmolVLM-500M-Instruct-GGUF](https://huggingface.co/ggml-org/SmolVLM-500M-Instruct-GGUF/tree/72e986006e) `SmolVLM-500M-Instruct-Q8_0.gguf` |
| ↳ проектор изображений | | | mmproj | GGUF | — | 0.11 | +0.1 | | | | | | | `mmproj-SmolVLM-500M-Instruct-Q8_0.gguf` |
| TinyLlama 1.1B Chat | TinyLlama | 1.1B | LLM | GGUF | Q4_K_M | 0.67 | ≈1.2 | — | — | — | — | не проверено | apache-2.0 | [TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF](https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF/tree/52e7645ba7) `tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf` |
| LFM2 350M | LFM | 0.35B | LLM | GGUF | Q4_0 | 0.22 | ≈0.7 | — | — | не проверено | — | не проверено | other/lfm1.0 | [LiquidAI/LFM2-350M-GGUF](https://huggingface.co/LiquidAI/LFM2-350M-GGUF/tree/8fdc9d526b) `LFM2-350M-Q4_0.gguf` |
| LFM2 1.2B | LFM | 1.2B | LLM | GGUF | Q4_0 | 0.70 | ≈1.3 | — | — | не проверено | — | не проверено | other/lfm1.0 | [LiquidAI/LFM2-1.2B-GGUF](https://huggingface.co/LiquidAI/LFM2-1.2B-GGUF/tree/5399e76c64) `LFM2-1.2B-Q4_0.gguf` |
| LFM2-VL 1.6B | LFM | 1.6B | VLM | GGUF | Q4_0 | 0.70 | ≈1.3 | да (mmproj) | — | — | — | не проверено | other/lfm1.0 | [LiquidAI/LFM2-VL-1.6B-GGUF](https://huggingface.co/LiquidAI/LFM2-VL-1.6B-GGUF/tree/6121de2670) `LFM2-VL-1.6B-Q4_0.gguf` |
| ↳ проектор изображений | | | mmproj | GGUF | — | 0.56 | +0.6 | | | | | | | `mmproj-LFM2-VL-1.6B-Q8_0.gguf` |
| Falcon-H1 1.5B | Falcon | 1.5B | LLM (гибрид) | GGUF | Q4_K_M | 0.94 | ≈1.5 | — | — | — | — | не проверено | other/falcon-llm-license | [tiiuae/Falcon-H1-1.5B-Instruct-GGUF](https://huggingface.co/tiiuae/Falcon-H1-1.5B-Instruct-GGUF/tree/0d3a6cfe25) `Falcon-H1-1.5B-Instruct-Q4_K_M.gguf` |
| Vikhr-Qwen-2.5 1.5B | Vikhr (Qwen) | 1.5B | LLM | GGUF | Q4_K_M | 0.99 | ≈1.6 | — | — | — | — | русский (заявлено авторами) | apache-2.0 | [Vikhrmodels/Vikhr-Qwen-2.5-1.5B-Instruct-GGUF](https://huggingface.co/Vikhrmodels/Vikhr-Qwen-2.5-1.5B-Instruct-GGUF/tree/f3d0a36f09) `Vikhr-Qwen-2.5-1.5b-Instruct-Q4_K_M.gguf` |
| bge-reranker-v2-m3 | BGE | 0.57B | reranker | GGUF | Q4_K_M | 0.44 | ≈1.0 | — | — | — | — | не проверено | apache-2.0 | [gpustack/bge-reranker-v2-m3-GGUF](https://huggingface.co/gpustack/bge-reranker-v2-m3-GGUF/tree/3093af03b1) `bge-reranker-v2-m3-Q4_K_M.gguf` |

### 1–2 ГБ

| Модель | Семейство | Параметры | Тип | Формат | Квант. | Файл, ГБ | ОЗУ, ГБ (расчёт) | Vision | Audio | Tools | Reasoning | Русский | Лицензия | Источник (ревизия) и файл |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Qwen2.5 1.5B Instruct | Qwen | 1.5B | LLM | GGUF | Q4_K_M | 1.12 | ≈1.7 | — | — | да | — | не проверено | apache-2.0 | [Qwen/Qwen2.5-1.5B-Instruct-GGUF](https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/tree/91cad51170) `qwen2.5-1.5b-instruct-q4_k_m.gguf` |
| Qwen2.5 Coder 1.5B | Qwen | 1.5B | код | GGUF | Q4_K_M | 1.12 | ≈1.7 | — | — | да | — | не проверено | apache-2.0 | [Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF](https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/tree/f86cb2c1fa) `qwen2.5-coder-1.5b-instruct-q4_k_m.gguf` |
| Qwen3 1.7B | Qwen | 1.7B | LLM | GGUF | Q8_0 | 1.83 | ≈2.6 | — | — | да | да | не проверено | apache-2.0 | [Qwen/Qwen3-1.7B-GGUF](https://huggingface.co/Qwen/Qwen3-1.7B-GGUF/tree/90862c4b9d) `Qwen3-1.7B-Q8_0.gguf` |
| Qwen3.5 2B | Qwen | 2B | LLM + vision | GGUF | Q4_K_M | 1.28 | ≈1.9 | да (mmproj) | — | да | да | не проверено | apache-2.0 | [unsloth/Qwen3.5-2B-GGUF](https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/tree/f6d5376be1) `Qwen3.5-2B-Q4_K_M.gguf` |
| ↳ проектор изображений | | | mmproj | GGUF | — | 0.67 | +0.7 | | | | | | | `mmproj-F16.gguf` |
| Qwen2.5-VL 3B | Qwen | 3B | VLM / OCR | GGUF | Q4_K_M | 1.93 | ≈2.7 | да (mmproj) | — | — | — | не проверено | apache-2.0 | [ggml-org/Qwen2.5-VL-3B-Instruct-GGUF](https://huggingface.co/ggml-org/Qwen2.5-VL-3B-Instruct-GGUF/tree/5037fcf163) `Qwen2.5-VL-3B-Instruct-Q4_K_M.gguf` |
| ↳ проектор изображений | | | mmproj | GGUF | — | 0.84 | +0.9 | | | | | | | `mmproj-Qwen2.5-VL-3B-Instruct-Q8_0.gguf` |
| Gemma 2 2B it | Gemma | 2B | LLM | GGUF | Q4_K_M | 1.71 | ≈2.4 | — | — | — | — | не проверено | gemma | [bartowski/gemma-2-2b-it-GGUF](https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/tree/855f67caed) `gemma-2-2b-it-Q4_K_M.gguf` |
| R1-Distill-Qwen 1.5B | DeepSeek | 1.5B | reasoning | GGUF | Q4_K_M | 1.12 | ≈1.7 | — | — | — | да | не проверено | mit (по базовой deepseek-ai/…) | [bartowski/DeepSeek-R1-Distill-Qwen-1.5B-GGUF](https://huggingface.co/bartowski/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/tree/9cc28b17e8) `DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf` |
| SmolLM2 1.7B | SmolLM | 1.7B | LLM | GGUF | Q4_K_M | 1.06 | ≈1.7 | — | — | — | — | не проверено | apache-2.0 | [HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF](https://huggingface.co/HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF/tree/2d4a76a30b) `smollm2-1.7b-instruct-q4_k_m.gguf` |
| SmolLM3 3B | SmolLM | 3B | LLM | GGUF | Q4_K_M | 1.92 | ≈2.7 | — | — | да | да | не проверено | apache-2.0 | [ggml-org/SmolLM3-3B-GGUF](https://huggingface.co/ggml-org/SmolLM3-3B-GGUF/tree/4965cb60b1) `SmolLM3-Q4_K_M.gguf` |
| InternLM 2.5 1.8B | InternLM | 1.8B | LLM | GGUF | Q4_K_M | 1.17 | ≈1.8 | — | — | — | — | не проверено | apache-2.0 | [internlm/internlm2_5-1_8b-chat-gguf](https://huggingface.co/internlm/internlm2_5-1_8b-chat-gguf/tree/916410ad25) `internlm2_5-1_8b-chat-q4_k_m.gguf` |
| StableLM 2 Zephyr 1.6B | StableLM | 1.6B | LLM | GGUF | Q4_K_M | 1.03 | ≈1.6 | — | — | — | — | не проверено | other | [second-state/stablelm-2-zephyr-1.6b-GGUF](https://huggingface.co/second-state/stablelm-2-zephyr-1.6b-GGUF/tree/6da77f49f4) `stablelm-2-zephyr-1_6b-Q4_K_M.gguf` |
| Granite 3.3 2B | Granite | 2B | LLM | GGUF | Q4_K_M | 1.55 | ≈2.2 | — | — | да | не проверено | не проверено | apache-2.0 | [ibm-granite/granite-3.3-2b-instruct-GGUF](https://huggingface.co/ibm-granite/granite-3.3-2b-instruct-GGUF/tree/7cdf86ccd1) `granite-3.3-2b-instruct-Q4_K_M.gguf` |
| Falcon3 1B | Falcon | 1B | LLM | GGUF | Q4_K_M | 1.06 | ≈1.7 | — | — | — | — | не проверено | Falcon LLM License (по базовой tiiuae/Falcon3-1B-Instruct) | [tiiuae/Falcon3-1B-Instruct-GGUF](https://huggingface.co/tiiuae/Falcon3-1B-Instruct-GGUF/tree/fc404df7cf) `Falcon3-1B-Instruct-q4_k_m.gguf` |

### 2–4 ГБ

| Модель | Семейство | Параметры | Тип | Формат | Квант. | Файл, ГБ | ОЗУ, ГБ (расчёт) | Vision | Audio | Tools | Reasoning | Русский | Лицензия | Источник (ревизия) и файл |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Qwen2.5 3B Instruct | Qwen | 3B | LLM | GGUF | Q4_K_M | 2.10 | ≈2.9 | — | — | да | — | не проверено | other/qwen-research | [Qwen/Qwen2.5-3B-Instruct-GGUF](https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/tree/7dabda4d13) `qwen2.5-3b-instruct-q4_k_m.gguf` |
| Qwen3 4B | Qwen | 4B | LLM | GGUF | Q4_K_M | 2.50 | ≈3.3 | — | — | да | да | не проверено | apache-2.0 | [Qwen/Qwen3-4B-GGUF](https://huggingface.co/Qwen/Qwen3-4B-GGUF/tree/bc640142c6) `Qwen3-4B-Q4_K_M.gguf` |
| Qwen3.5 4B | Qwen | 4B | LLM + vision | GGUF | Q4_K_M | 2.74 | ≈3.6 | да (mmproj) | — | да | да | не проверено | apache-2.0 | [unsloth/Qwen3.5-4B-GGUF](https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/tree/e87f176479) `Qwen3.5-4B-Q4_K_M.gguf` |
| ↳ проектор изображений | | | mmproj | GGUF | — | 0.67 | +0.7 | | | | | | | `mmproj-F16.gguf` |
| Qwen2.5-Omni 3B | Qwen | 3B | омни | GGUF | Q4_K_M | 2.10 | ≈2.9 | да | да (вход) | — | — | не проверено | other/qwen-research | [ggml-org/Qwen2.5-Omni-3B-GGUF](https://huggingface.co/ggml-org/Qwen2.5-Omni-3B-GGUF/tree/75f1b73b65) `Qwen2.5-Omni-3B-Q4_K_M.gguf` |
| ↳ проектор изображений | | | mmproj | GGUF | — | 1.54 | +1.6 | | | | | | | `mmproj-Qwen2.5-Omni-3B-Q8_0.gguf` |
| Gemma 3 4B it | Gemma | 4B | LLM + vision | GGUF | Q4_K_M | 2.49 | ≈3.3 | да (mmproj) | — | — | — | не проверено | gemma | [ggml-org/gemma-3-4b-it-GGUF](https://huggingface.co/ggml-org/gemma-3-4b-it-GGUF/tree/d097622374) `gemma-3-4b-it-Q4_K_M.gguf` |
| ↳ проектор изображений | | | mmproj | GGUF | — | 0.85 | +0.9 | | | | | | | `mmproj-model-f16.gguf` |
| Gemma 3n E2B it | Gemma | E2B | LLM | GGUF | Q4_K_M | 3.03 | ≈3.9 | не проверено в GGUF | не проверено в GGUF | — | — | не проверено | gemma | [unsloth/gemma-3n-E2B-it-GGUF](https://huggingface.co/unsloth/gemma-3n-E2B-it-GGUF/tree/d7bd20d510) `gemma-3n-E2B-it-Q4_K_M.gguf` |
| Gemma 4 E2B it (LiteRT-LM) | Gemma | E2B | LiteRT-LM | LiteRT-LM | — | 2.59 | ≈3.4 | не проверено | не проверено | — | — | не проверено | apache-2.0 | [litert-community/gemma-4-E2B-it-litert-lm](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/tree/2e12bfd869) `gemma-4-E2B-it.litertlm` |
| Gemma 4 E4B it (LiteRT-LM) | Gemma | E4B | LiteRT-LM | LiteRT-LM | — | 3.66 | ≈4.7 | не проверено | не проверено | — | — | не проверено | apache-2.0 | [litert-community/gemma-4-E4B-it-litert-lm](https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/tree/f254b5ba6e) `gemma-4-E4B-it.litertlm` |
| Llama 3.2 3B Instruct | Llama | 3B | LLM | GGUF | Q4_K_M | 2.02 | ≈2.8 | — | — | да | — | не проверено | llama3.2 | [bartowski/Llama-3.2-3B-Instruct-GGUF](https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/tree/5ab33fa94d) `Llama-3.2-3B-Instruct-Q4_K_M.gguf` |
| Phi-3 mini 4k | Phi | 3.8B | LLM | GGUF | Q4 | 2.39 | ≈3.2 | — | — | — | — | не проверено | mit | [microsoft/Phi-3-mini-4k-instruct-gguf](https://huggingface.co/microsoft/Phi-3-mini-4k-instruct-gguf/tree/a64113399c) `Phi-3-mini-4k-instruct-q4.gguf` |
| Phi-4 mini | Phi | 3.8B | LLM | GGUF | Q4_K_M | 2.49 | ≈3.3 | — | — | да | — | не проверено | mit | [unsloth/Phi-4-mini-instruct-GGUF](https://huggingface.co/unsloth/Phi-4-mini-instruct-GGUF/tree/78eb92a46f) `Phi-4-mini-instruct-Q4_K_M.gguf` |
| Yi 1.5 6B Chat | Yi | 6B | LLM | GGUF | Q4_K_M | 3.67 | ≈4.7 | — | — | — | — | не проверено | other/yi-license | [second-state/Yi-1.5-6B-Chat-GGUF](https://huggingface.co/second-state/Yi-1.5-6B-Chat-GGUF/tree/aefae1805b) `Yi-1.5-6B-Chat-Q4_K_M.gguf` |
| QVikhr-3 4B | Vikhr (Qwen) | 4B | LLM | GGUF | Q4_K_M | 2.50 | ≈3.3 | — | — | — | — | русский (заявлено авторами) | apache-2.0 | [Vikhrmodels/QVikhr-3-4B-Instruction-GGUF](https://huggingface.co/Vikhrmodels/QVikhr-3-4B-Instruction-GGUF/tree/ab5a2e9090) `QVikhr-3-4B-Instruction-Q4_K_M.gguf` |
| MobileLLM-R1 950M | MobileLLM | 0.95B | reasoning | safetensors | BF16 | 3.80 | ≈4.8 | — | — | — | да | не проверено | other/fair-noncommercial-research (доступ по запросу) | [facebook/MobileLLM-R1-950M](https://huggingface.co/facebook/MobileLLM-R1-950M/tree/aa7d61df54) `model.safetensors` |
| Qwen3 4B (MNN) | Qwen (MNN) | 4B | MNN | MNN | — | 2.46 | ≈3.3 | — | — | да | да | не проверено | apache-2.0 | [taobao-mnn/Qwen3-4B-MNN](https://huggingface.co/taobao-mnn/Qwen3-4B-MNN/tree/45f4d179ae) `llm.mnn.weight` |

### 4–6 ГБ

| Модель | Семейство | Параметры | Тип | Формат | Квант. | Файл, ГБ | ОЗУ, ГБ (расчёт) | Vision | Audio | Tools | Reasoning | Русский | Лицензия | Источник (ревизия) и файл |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Qwen2.5 7B Instruct | Qwen | 7B | LLM (2 файла) | GGUF | — | 4.68 | ≈5.8 | — | — | да | — | не проверено | apache-2.0 | [Qwen/Qwen2.5-7B-Instruct-GGUF](https://huggingface.co/Qwen/Qwen2.5-7B-Instruct-GGUF/tree/bb5d59e06d) `2 файла(ов)` |
| Qwen2.5 Coder 7B | Qwen | 7B | код (2 файла) | GGUF | — | 4.68 | ≈5.8 | — | — | да | — | не проверено | apache-2.0 | [Qwen/Qwen2.5-Coder-7B-Instruct-GGUF](https://huggingface.co/Qwen/Qwen2.5-Coder-7B-Instruct-GGUF/tree/13fb94bfda) `2 файла(ов)` |
| Qwen3 8B | Qwen | 8B | LLM | GGUF | Q4_K_M | 5.03 | ≈6.2 | — | — | да | да | не проверено | apache-2.0 | [Qwen/Qwen3-8B-GGUF](https://huggingface.co/Qwen/Qwen3-8B-GGUF/tree/7c41481f57) `Qwen3-8B-Q4_K_M.gguf` |
| Qwen3.5 9B | Qwen | 9B | LLM + vision | GGUF | Q4_K_M | 5.68 | ≈7.0 | да (mmproj) | — | да | да | не проверено | apache-2.0 | [unsloth/Qwen3.5-9B-GGUF](https://huggingface.co/unsloth/Qwen3.5-9B-GGUF/tree/3885219b68) `Qwen3.5-9B-Q4_K_M.gguf` |
| ↳ проектор изображений | | | mmproj | GGUF | — | 0.92 | +1.0 | | | | | | | `mmproj-F16.gguf` |
| Gemma 3n E4B it | Gemma | E4B | LLM | GGUF | Q4_K_M | 4.54 | ≈5.7 | не проверено в GGUF | не проверено в GGUF | — | — | не проверено | gemma | [unsloth/gemma-3n-E4B-it-GGUF](https://huggingface.co/unsloth/gemma-3n-E4B-it-GGUF/tree/90fa8b0e43) `gemma-3n-E4B-it-Q4_K_M.gguf` |
| Llama 3.1 8B Instruct | Llama | 8B | LLM | GGUF | Q4_K_M | 4.92 | ≈6.1 | — | — | да | — | не проверено | llama3.1 | [bartowski/Meta-Llama-3.1-8B-Instruct-GGUF](https://huggingface.co/bartowski/Meta-Llama-3.1-8B-Instruct-GGUF/tree/bf5b95e96d) `Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf` |
| R1-Distill-Qwen 7B | DeepSeek | 7B | reasoning | GGUF | Q4_K_M | 4.68 | ≈5.8 | — | — | — | да | не проверено | mit (по базовой deepseek-ai/…) | [bartowski/DeepSeek-R1-Distill-Qwen-7B-GGUF](https://huggingface.co/bartowski/DeepSeek-R1-Distill-Qwen-7B-GGUF/tree/361004151d) `DeepSeek-R1-Distill-Qwen-7B-Q4_K_M.gguf` |
| R1-0528-Qwen3 8B | DeepSeek | 8B | reasoning | GGUF | Q4_K_M | 5.03 | ≈6.2 | — | — | — | да | не проверено | mit | [unsloth/DeepSeek-R1-0528-Qwen3-8B-GGUF](https://huggingface.co/unsloth/DeepSeek-R1-0528-Qwen3-8B-GGUF/tree/eb48357c17) `DeepSeek-R1-0528-Qwen3-8B-Q4_K_M.gguf` |
| Mistral 7B Instruct v0.3 | Mistral | 7B | LLM | GGUF | Q4_K_M | 4.37 | ≈5.5 | — | — | да | — | не проверено | apache-2.0 | [bartowski/Mistral-7B-Instruct-v0.3-GGUF](https://huggingface.co/bartowski/Mistral-7B-Instruct-v0.3-GGUF/tree/61fd4167ff) `Mistral-7B-Instruct-v0.3-Q4_K_M.gguf` |
| Ministral 8B 2410 | Mistral | 8B | LLM | GGUF | Q4_K_M | 4.91 | ≈6.1 | — | — | да | — | не проверено | other/mrl | [bartowski/Ministral-8B-Instruct-2410-GGUF](https://huggingface.co/bartowski/Ministral-8B-Instruct-2410-GGUF/tree/b4e2ea74eb) `Ministral-8B-Instruct-2410-Q4_K_M.gguf` |
| MiniCPM-V 2.6 | MiniCPM | 8B | VLM / OCR | GGUF | Q4_K_M | 4.68 | ≈5.8 | да (mmproj) | — | — | — | не проверено | MiniCPM Model License (в карточке HF не указана) | [openbmb/MiniCPM-V-2_6-gguf](https://huggingface.co/openbmb/MiniCPM-V-2_6-gguf/tree/48fe6436ab) `ggml-model-Q4_K_M.gguf` |
| ↳ проектор изображений | | | mmproj | GGUF | — | 1.04 | +1.1 | | | | | | | `mmproj-model-f16.gguf` |
| MiniCPM-V 4.5 | MiniCPM | 8B | VLM / OCR | GGUF | Q4_K_M | 5.03 | ≈6.2 | да (mmproj) | — | — | — | не проверено | apache-2.0 (по базовой openbmb/MiniCPM-V-4_5) | [openbmb/MiniCPM-V-4_5-gguf](https://huggingface.co/openbmb/MiniCPM-V-4_5-gguf/tree/6c477db4ba) `ggml-model-Q4_K_M.gguf` |
| ↳ проектор изображений | | | mmproj | GGUF | — | 1.10 | +1.1 | | | | | | | `mmproj-model-f16.gguf` |
| Nemotron 3.5 Bolt E4B | Nemotron | E4B | LLM + vision | GGUF | UD-Q3_K_XL | 4.22 | ≈5.3 | да (mmproj) | — | не проверено | не проверено | не проверено | other/openmdw-1.1 | [unsloth/NVIDIA-Nemotron-3.5-Bolt-E4B-GGUF](https://huggingface.co/unsloth/NVIDIA-Nemotron-3.5-Bolt-E4B-GGUF/tree/b738967d6d) `NVIDIA-Nemotron-3.5-Bolt-E4B-UD-Q3_K_XL.gguf` |
| ↳ проектор изображений | | | mmproj | GGUF | — | 0.56 | +0.6 | | | | | | | `mmproj-Q8_0.gguf` |
| YandexGPT 5 Lite 8B | YandexGPT | 8B | LLM | GGUF | Q4_K_M | 4.92 | ≈6.1 | — | — | — | — | русский (заявлено авторами) | other/yandexgpt-5-lite-8b | [yandex/YandexGPT-5-Lite-8B-instruct-GGUF](https://huggingface.co/yandex/YandexGPT-5-Lite-8B-instruct-GGUF/tree/9fe287d2f5) `YandexGPT-5-Lite-8B-instruct-Q4_K_M.gguf` |
| T-lite-it 2.1 | T-lite (Qwen) | 8B | LLM | GGUF | Q4_K_M | 5.03 | ≈6.2 | — | — | — | — | русский (заявлено авторами) | apache-2.0 | [t-tech/T-lite-it-2.1-GGUF](https://huggingface.co/t-tech/T-lite-it-2.1-GGUF/tree/fc10186e59) `T-lite-it-2.1-Q4_K_M.gguf` |

### 6–10 ГБ

| Модель | Семейство | Параметры | Тип | Формат | Квант. | Файл, ГБ | ОЗУ, ГБ (расчёт) | Vision | Audio | Tools | Reasoning | Русский | Лицензия | Источник (ревизия) и файл |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Phi-4 | Phi | 14B | LLM | GGUF | Q4_0 | 8.38 | ≈10.1 | — | — | — | — | не проверено | mit | [microsoft/phi-4-gguf](https://huggingface.co/microsoft/phi-4-gguf/tree/6edc2ef666) `phi-4-Q4_0.gguf` |
| GLM-4 9B 0414 | GLM | 9B | LLM | GGUF | Q4_K_M | 6.17 | ≈7.5 | — | — | да | — | не проверено | mit | [bartowski/THUDM_GLM-4-9B-0414-GGUF](https://huggingface.co/bartowski/THUDM_GLM-4-9B-0414-GGUF/tree/211924cd94) `THUDM_GLM-4-9B-0414-Q4_K_M.gguf` |

### >10 ГБ

| Модель | Семейство | Параметры | Тип | Формат | Квант. | Файл, ГБ | ОЗУ, ГБ (расчёт) | Vision | Audio | Tools | Reasoning | Русский | Лицензия | Источник (ревизия) и файл |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| AutoGLM-Phone 9B | GLM | 9B | phone-use агент | safetensors | BF16 | 20.59 | ≈24.1 | да | — | да (действия) | — | не проверено | mit | [zai-org/AutoGLM-Phone-9B](https://huggingface.co/zai-org/AutoGLM-Phone-9B/tree/66a46ea238) `5 файла(ов)` |
| Baichuan2 7B Chat | Baichuan | 7B | LLM | PyTorch .bin | — | 15.01 | ≈17.7 | — | — | — | — | не проверено | ?/baichuan2-community-license | [baichuan-inc/Baichuan2-7B-Chat](https://huggingface.co/baichuan-inc/Baichuan2-7B-Chat/tree/ea66ced177) `pytorch_model.bin` |

### Речь (STT / TTS)

| Модель | Семейство | Параметры | Назначение | Файл | Размер | Лицензия | Источник |
|---|---|---|---|---|---|---|---|
| whisper base (q5_1) | Whisper | 74M | STT | `ggml-base-q5_1.bin` | 60 МБ | mit | [ggerganov/whisper.cpp](https://huggingface.co/ggerganov/whisper.cpp/tree/5359861c73) |
| whisper small (q5_1) | Whisper | 244M | STT | `ggml-small-q5_1.bin` | 190 МБ | mit | [ggerganov/whisper.cpp](https://huggingface.co/ggerganov/whisper.cpp/tree/5359861c73) |
| whisper large-v3-turbo (q8_0) | Whisper | 809M | STT | `ggml-large-v3-turbo-q8_0.bin` | 874 МБ | mit | [ggerganov/whisper.cpp](https://huggingface.co/ggerganov/whisper.cpp/tree/5359861c73) |
| Piper ru_RU (denis / dmitri / irina / ruslan) | Piper | — | TTS, русский | `ru/ru_RU/*` | — | mit (репозиторий; у голосов свои карточки) | [rhasspy/piper-voices](https://huggingface.co/rhasspy/piper-voices/tree/e9c8a39444) |
| Kokoro 82M (ONNX, q8f16) | Kokoro | 82M | TTS (русских голосов нет: префиксы a,b,e,f,h,i,j,p,z) | `model_q8f16.onnx` | 86 МБ | apache-2.0 | [onnx-community/Kokoro-82M-v1.0-ONNX](https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX/tree/1939ad2a8e) |

## Что из этого в NEXUS

Каталог приложения (12 моделей, `NexusModelCatalog`) взят из этого списка по
трём правилам: ≤ 9B параметров, официальный или проверенный источник
(Qwen, Google, ggml-org, unsloth, Vikhrmodels, Yandex, T-Tech), закреплённая
ревизия + SHA-256. В каталоге: **Qwen3 0.6B, Qwen3.5 0.8B / 2B / 4B / 9B,
Vikhr-Qwen-2.5 1.5B, QVikhr-3 4B, Gemma 4 E2B / E4B (LiteRT-LM), Nemotron
3.5 Bolt E4B, YandexGPT 5 Lite 8B, T-lite-it 2.1**.

Кандидаты на добавление (по пользе для NEXUS, лицензия без ограничений на
личное использование):

| Зачем | Модель | Почему |
|---|---|---|
| Память / поиск по архиву | Qwen3-Embedding 0.6B или EmbeddingGemma 300M | Локальные эмбеддинги для RAG; EmbeddingGemma — лицензия Gemma |
| Переранжирование | bge-reranker-v2-m3 | Мультиязычный, 0.44 ГБ |
| Распознавание речи офлайн | whisper base / small (whisper.cpp) | MIT, 60–190 МБ |
| Синтез речи офлайн по-русски | Piper ru_RU (irina, dmitri, denis, ruslan) | Есть русские голоса; Kokoro русских голосов не имеет |
| Изображения и OCR | Qwen3.5 (уже есть, нужен `mmproj`), Qwen2.5-VL 3B, SmolVLM 500M | Работают через llama.cpp с проектором |
| Управление телефоном | AutoGLM-Phone 9B | 20.6 ГБ в BF16 — на телефоне только после квантования; лицензия MIT |

Не рекомендуются для каталога: Phi-4 14B и GLM-4 9B в Q8 (больше 10 ГБ ОЗУ),
MobileLLM-R1 (лицензия FAIR Noncommercial Research, доступ по запросу),
Baichuan2 7B (только PyTorch-веса 15 ГБ, отдельная лицензия), Ministral 8B
(Mistral Research License), Qwen2.5 3B и Qwen2.5-Omni 3B (Qwen Research
License). Подробности — [аудит рисков и лицензий](08_RISK_LICENSE_AUDIT_RU.md).
