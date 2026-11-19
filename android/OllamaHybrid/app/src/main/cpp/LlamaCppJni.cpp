#include <jni.h>
#include <android/log.h>
#include <llama.h>

#include <atomic>
#include <algorithm>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

#define NEXUS_TAG "NexusLlama"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, NEXUS_TAG, __VA_ARGS__)

struct NexusLlamaState {
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    const llama_vocab * vocab = nullptr;
    std::mutex generation_mutex;
    std::atomic<bool> cancelled{false};
};

static void throw_java(JNIEnv * env, const char * message) {
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type != nullptr) env->ThrowNew(type, message);
}

static NexusLlamaState * state_from(jlong handle) {
    return reinterpret_cast<NexusLlamaState *>(static_cast<intptr_t>(handle));
}

static bool set_batch(
    llama_batch_ext * batch,
    const llama_token * tokens,
    int32_t count,
    llama_pos first_position
) {
    llama_batch_ext_clear(batch);
    for (int32_t i = 0; i < count; ++i) {
        const int32_t index = llama_batch_ext_add_token(batch, 0, tokens[i]);
        if (index < 0) return false;
        const llama_pos position = first_position + i;
        if (!llama_batch_ext_set_pos(batch, index, &position)) return false;
    }
    return count > 0 && llama_batch_ext_set_output_logits(batch, count - 1, true);
}

extern "C" JNIEXPORT jlong JNICALL
Java_ai_hybrid_commandcenter_nexus_NativeLlamaBridge_nativeCreate(
    JNIEnv * env, jobject, jstring model_path, jint context_size, jint threads
) {
    const char * path = env->GetStringUTFChars(model_path, nullptr);
    if (path == nullptr) return 0;

    llama_backend_init();
    auto state = new NexusLlamaState();
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    state->model = llama_model_load_from_file(path, model_params);
    env->ReleaseStringUTFChars(model_path, path);

    if (state->model == nullptr) {
        delete state;
        throw_java(env, "llama.cpp не смог загрузить GGUF-файл.");
        return 0;
    }

    state->vocab = llama_model_get_vocab(state->model);
    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = static_cast<uint32_t>(std::max(512, context_size));
    context_params.n_batch = 512;
    state->context = llama_init_from_model(state->model, context_params);
    if (state->context == nullptr) {
        llama_model_free(state->model);
        delete state;
        throw_java(env, "llama.cpp не смог создать контекст модели.");
        return 0;
    }
    llama_set_n_threads(state->context, std::max(1, static_cast<int>(threads)),
                        std::max(1, static_cast<int>(threads)));
    return static_cast<jlong>(reinterpret_cast<intptr_t>(state));
}

extern "C" JNIEXPORT void JNICALL
Java_ai_hybrid_commandcenter_nexus_NativeLlamaBridge_nativeGenerate(
    JNIEnv * env, jobject, jlong handle, jstring prompt_value,
    jint max_tokens, jfloat temperature, jobject sink
) {
    auto state = state_from(handle);
    if (state == nullptr) {
        throw_java(env, "llama.cpp engine не инициализирован.");
        return;
    }

    std::lock_guard<std::mutex> guard(state->generation_mutex);
    state->cancelled.store(false);
    const char * prompt_chars = env->GetStringUTFChars(prompt_value, nullptr);
    if (prompt_chars == nullptr) return;
    const std::string prompt(prompt_chars);
    env->ReleaseStringUTFChars(prompt_value, prompt_chars);

    jclass sink_class = env->GetObjectClass(sink);
    jmethodID on_token = sink_class == nullptr ? nullptr :
        env->GetMethodID(sink_class, "onToken", "(Ljava/lang/String;)V");
    if (on_token == nullptr) {
        throw_java(env, "Не найден callback для потока токенов.");
        return;
    }

    // Reset KV state between independent requests. The Android gateway supplies the full chat history.
    llama_memory_clear(llama_get_memory(state->context), false);

    const int32_t token_count = -llama_tokenize(
        state->vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
        nullptr, 0, true, true
    );
    if (token_count <= 0) {
        throw_java(env, "Не удалось токенизировать запрос.");
        return;
    }
    std::vector<llama_token> prompt_tokens(static_cast<size_t>(token_count));
    const int32_t tokenized = llama_tokenize(
        state->vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
        prompt_tokens.data(), token_count, true, true
    );
    if (tokenized < 0) {
        throw_java(env, "Не удалось подготовить токены запроса.");
        return;
    }
    prompt_tokens.resize(static_cast<size_t>(tokenized));

    if (prompt_tokens.empty() || prompt_tokens.size() + static_cast<size_t>(max_tokens) >= llama_n_ctx(state->context)) {
        throw_java(env, "Запрос слишком длинный для выбранного контекста модели.");
        return;
    }

    llama_batch_ext * batch = llama_batch_ext_init(state->context);
    if (batch == nullptr) {
        throw_java(env, "Не удалось создать batch для llama.cpp.");
        return;
    }

    auto sampler_params = llama_sampler_chain_default_params();
    llama_sampler * sampler = llama_sampler_chain_init(sampler_params);
    if (sampler == nullptr) {
        llama_batch_ext_free(batch);
        throw_java(env, "Не удалось создать sampler llama.cpp.");
        return;
    }
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(std::max(0.0f, temperature)));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    if (!set_batch(batch, prompt_tokens.data(), static_cast<int32_t>(prompt_tokens.size()), 0) ||
        llama_process(state->context, LLAMA_PROCESS_TYPE_DECODE, batch) != 0) {
        llama_sampler_free(sampler);
        llama_batch_ext_free(batch);
        throw_java(env, "llama.cpp завершил ошибкой обработку входного запроса.");
        return;
    }

    const int limit = std::clamp(static_cast<int>(max_tokens), 1, 1024);
    llama_pos position = static_cast<llama_pos>(prompt_tokens.size());
    for (int step = 0; step < limit && !state->cancelled.load(); ++step) {
        const llama_token token = llama_sampler_sample(sampler, state->context, -1);
        if (llama_vocab_is_eog(state->vocab, token)) break;

        char small_buffer[256];
        int32_t bytes = llama_token_to_piece(state->vocab, token, small_buffer,
                                              static_cast<int32_t>(sizeof(small_buffer)), 0, true);
        std::string piece;
        if (bytes < 0) {
            std::vector<char> large_buffer(static_cast<size_t>(-bytes));
            bytes = llama_token_to_piece(state->vocab, token, large_buffer.data(),
                                          static_cast<int32_t>(large_buffer.size()), 0, true);
            if (bytes > 0) piece.assign(large_buffer.data(), static_cast<size_t>(bytes));
        } else if (bytes > 0) {
            piece.assign(small_buffer, static_cast<size_t>(bytes));
        }

        if (!piece.empty()) {
            jstring value = env->NewStringUTF(piece.c_str());
            if (value != nullptr) {
                env->CallVoidMethod(sink, on_token, value);
                env->DeleteLocalRef(value);
            }
            if (env->ExceptionCheck()) break;
        }

        if (!set_batch(batch, &token, 1, position) ||
            llama_process(state->context, LLAMA_PROCESS_TYPE_DECODE, batch) != 0) {
            LOGE("llama_process failed while decoding");
            break;
        }
        ++position;
    }

    llama_sampler_free(sampler);
    llama_batch_ext_free(batch);
}

extern "C" JNIEXPORT void JNICALL
Java_ai_hybrid_commandcenter_nexus_NativeLlamaBridge_nativeCancel(
    JNIEnv *, jobject, jlong handle
) {
    auto state = state_from(handle);
    if (state != nullptr) state->cancelled.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_ai_hybrid_commandcenter_nexus_NativeLlamaBridge_nativeDestroy(
    JNIEnv *, jobject, jlong handle
) {
    auto state = state_from(handle);
    if (state == nullptr) return;
    state->cancelled.store(true);
    std::lock_guard<std::mutex> guard(state->generation_mutex);
    if (state->context != nullptr) llama_free(state->context);
    if (state->model != nullptr) llama_model_free(state->model);
    delete state;
}
