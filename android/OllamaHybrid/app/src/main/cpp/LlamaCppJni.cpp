#include <jni.h>
#include <android/log.h>
#include <llama.h>
#include <ggml-backend.h>

#include <atomic>
#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <memory>
#include <mutex>
#include <shared_mutex>
#include <string>
#include <unistd.h>
#include <unordered_map>
#include <vector>

#define NEXUS_TAG "NexusLlama"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, NEXUS_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, NEXUS_TAG, __VA_ARGS__)

// Text crosses the JNI boundary only as UTF-8 byte arrays. NewStringUTF expects
// "modified UTF-8" and aborts under CheckJNI on 4-byte characters (emoji) or on a
// multi-byte Cyrillic letter split between two tokens, so it is never used here.

// Lifetime rules. The bug class behind CVE-2026-70640 (llama.cpp's old Android
// example freed a context while another thread still used it) is ruled out by
// construction, not by the Kotlin caller being careful:
//  * Java holds an opaque id, never a pointer. Ids are never reused, so a stale id
//    finds nothing instead of freed memory.
//  * Every call that touches the model or the context holds `lifecycle` shared.
//    nativeDestroy unregisters the id, raises `closing`, then takes `lifecycle`
//    exclusively: it waits for the calls in flight and frees the memory before it
//    returns, so the next model never shares RAM with the old one.
//  * The state is reference-counted, so its mutexes outlive every thread waiting on them.
//  * JNIEnv is never stored and llama.cpp's worker threads never call into Java:
//    the token callback runs on the thread that called nativeGenerate.
struct NexusLlamaState {
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    const llama_vocab * vocab = nullptr;
    FILE * file = nullptr; // owned when the model was opened from a descriptor
    // Tokens whose keys/values are in the KV cache (sequence 0): the next turn
    // re-uses the longest common prefix instead of re-reading the whole chat.
    std::vector<llama_token> cached;
    std::shared_mutex lifecycle;      // shared: any use; exclusive: release
    std::mutex generation_mutex;      // one generation at a time: the KV cache is per context
    std::atomic<bool> cancelled{false};
    std::atomic<bool> closing{false};
    bool released = false;            // guarded by lifecycle

    // The caller holds `lifecycle` exclusively, or is the last owner.
    void release() {
        if (released) return;
        released = true;
        if (context != nullptr) llama_free(context);
        if (model != nullptr) llama_model_free(model);
        if (file != nullptr) fclose(file);
        context = nullptr;
        model = nullptr;
        vocab = nullptr;
        file = nullptr;
        cached.clear();
    }

    ~NexusLlamaState() { release(); }
};

using StatePtr = std::shared_ptr<NexusLlamaState>;

static std::mutex g_registry_mutex;
static std::unordered_map<jlong, StatePtr> g_registry;
static jlong g_next_handle = 1; // guarded by g_registry_mutex; 0 means "no engine" on the Kotlin side

static jlong register_state(StatePtr state) {
    std::lock_guard<std::mutex> lock(g_registry_mutex);
    const jlong id = g_next_handle++;
    g_registry.emplace(id, std::move(state));
    return id;
}

static StatePtr find_state(jlong handle) {
    std::lock_guard<std::mutex> lock(g_registry_mutex);
    auto it = g_registry.find(handle);
    return it == g_registry.end() ? nullptr : it->second;
}

static StatePtr unregister_state(jlong handle) {
    std::lock_guard<std::mutex> lock(g_registry_mutex);
    auto it = g_registry.find(handle);
    if (it == g_registry.end()) return nullptr;
    StatePtr state = std::move(it->second);
    g_registry.erase(it);
    return state;
}

// Keeps a state alive and blocks its release while one call works with it.
class StateUse {
public:
    explicit StateUse(jlong handle) : state_(find_state(handle)) {
        if (state_) lock_ = std::shared_lock<std::shared_mutex>(state_->lifecycle);
    }
    // False for unknown ids and for engines released while this call was waiting.
    bool ok() const { return state_ && !state_->released && state_->model != nullptr; }
    NexusLlamaState * operator->() const { return state_.get(); }

private:
    StatePtr state_;
    std::shared_lock<std::shared_mutex> lock_;
};

static void throw_java(JNIEnv * env, const char * message) {
    if (env->ExceptionCheck()) return; // an exception is already on its way to Kotlin
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type == nullptr) return; // NoClassDefFoundError is pending
    env->ThrowNew(type, message);
    env->DeleteLocalRef(type);
}

static std::string bytes_to_string(JNIEnv * env, jbyteArray value) {
    if (value == nullptr) return {};
    const jsize length = env->GetArrayLength(value);
    std::string out(static_cast<size_t>(length), '\0');
    if (length > 0) env->GetByteArrayRegion(value, 0, length, reinterpret_cast<jbyte *>(&out[0]));
    return out;
}

static jbyteArray string_to_bytes(JNIEnv * env, const std::string & value) {
    jbyteArray out = env->NewByteArray(static_cast<jsize>(value.size()));
    if (out != nullptr && !value.empty()) {
        env->SetByteArrayRegion(out, 0, static_cast<jsize>(value.size()), reinterpret_cast<const jbyte *>(value.data()));
    }
    return out;
}

// Length of the prefix of `s` that ends on a complete UTF-8 character.
static size_t complete_utf8_prefix(const std::string & s) {
    const size_t n = s.size();
    size_t i = n;
    int back = 0;
    while (i > 0 && back < 4) {
        --i;
        ++back;
        const unsigned char c = static_cast<unsigned char>(s[i]);
        if ((c & 0xC0) == 0x80) continue; // continuation byte
        int need = 1;
        if ((c & 0xE0) == 0xC0) need = 2;
        else if ((c & 0xF0) == 0xE0) need = 3;
        else if ((c & 0xF8) == 0xF0) need = 4;
        return back >= need ? n : i;
    }
    return n;
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

// Creates the context for a loaded model and hands Kotlin an id for it.
// On failure the state (and with it the model and the file) is freed by its owner.
static jlong finish_create(JNIEnv * env, StatePtr state, jint context_size, jint threads) {
    if (state->model == nullptr) {
        throw_java(env, "llama.cpp не смог загрузить GGUF-файл.");
        return 0;
    }
    state->vocab = llama_model_get_vocab(state->model);
    llama_context_params context_params = llama_context_default_params();
    int32_t n_ctx = std::max(512, static_cast<int>(context_size));
    const int32_t trained = llama_model_n_ctx_train(state->model);
    if (trained > 0) n_ctx = std::min(n_ctx, trained);
    context_params.n_ctx = static_cast<uint32_t>(n_ctx);
    context_params.n_batch = 512;
    state->context = llama_init_from_model(state->model, context_params);
    if (state->context == nullptr) {
        throw_java(env, "llama.cpp не смог создать контекст модели (не хватает памяти?).");
        return 0;
    }
    const int n_threads = std::max(1, static_cast<int>(threads));
    llama_set_n_threads(state->context, n_threads, n_threads);
    return register_state(std::move(state));
}

static std::once_flag g_backends_once;
static std::string g_backends;

// Loads the CPU backend variant that fits this processor (the libraries sit in the
// app's native library folder) and describes what was loaded, e.g. "CPU: NEON DOTPROD FP16".
extern "C" JNIEXPORT jstring JNICALL
Java_ai_hybrid_commandcenter_nexus_NativeLlamaBridge_nativeInitBackends(JNIEnv * env, jobject, jstring lib_dir) {
    std::string path;
    if (lib_dir != nullptr) {
        const char * dir = env->GetStringUTFChars(lib_dir, nullptr);
        if (dir == nullptr) return nullptr; // OutOfMemoryError is pending
        path = dir;
        env->ReleaseStringUTFChars(lib_dir, dir);
    }
    std::call_once(g_backends_once, [&path]() {
        if (!path.empty()) ggml_backend_load_all_from_path(path.c_str());
        llama_backend_init();
        std::string info;
        for (size_t i = 0; i < ggml_backend_reg_count(); ++i) {
            ggml_backend_reg_t reg = ggml_backend_reg_get(i);
            if (!info.empty()) info += "; ";
            info += ggml_backend_reg_name(reg);
            auto features = reinterpret_cast<ggml_backend_get_features_t>(
                ggml_backend_reg_get_proc_address(reg, "ggml_backend_get_features"));
            if (features == nullptr) continue;
            std::string flags;
            for (ggml_backend_feature * f = features(reg); f != nullptr && f->name != nullptr; ++f) {
                if (f->value != nullptr && std::strcmp(f->value, "1") == 0) flags += std::string(" ") + f->name;
            }
            if (!flags.empty()) info += ":" + flags;
        }
        g_backends = info;
    });
    // ASCII only (backend and feature names), safe for NewStringUTF.
    return env->NewStringUTF(g_backends.c_str());
}

static bool has_cpu_backend(JNIEnv * env) {
    if (ggml_backend_dev_count() > 0) return true;
    throw_java(env, "Движок llama.cpp не нашёл вычислительный модуль для этого процессора.");
    return false;
}

static llama_model_params cpu_model_params() {
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    return model_params;
}

// The path arrives as UTF-8 bytes: GetStringUTFChars would hand fopen "modified
// UTF-8", which spells characters outside the BMP (emoji in a folder name) wrongly.
extern "C" JNIEXPORT jlong JNICALL
Java_ai_hybrid_commandcenter_nexus_NativeLlamaBridge_nativeCreate(
    JNIEnv * env, jobject, jbyteArray model_path, jint context_size, jint threads
) {
    const std::string path = bytes_to_string(env, model_path);
    if (env->ExceptionCheck()) return 0;
    if (path.empty() || path.find('\0') != std::string::npos) {
        throw_java(env, "Неверный путь к файлу модели.");
        return 0;
    }
    llama_backend_init();
    if (!has_cpu_backend(env)) return 0;
    auto state = std::make_shared<NexusLlamaState>();
    state->model = llama_model_load_from_file(path.c_str(), cpu_model_params());
    return finish_create(env, std::move(state), context_size, threads);
}

// Opens a model the owner picked in the system file picker, without copying it:
// the descriptor is duplicated, wrapped in a FILE* and memory-mapped by llama.cpp.
extern "C" JNIEXPORT jlong JNICALL
Java_ai_hybrid_commandcenter_nexus_NativeLlamaBridge_nativeCreateFromFd(
    JNIEnv * env, jobject, jint fd, jint context_size, jint threads
) {
    const int own = dup(fd);
    if (own < 0) {
        throw_java(env, "Не удалось получить доступ к файлу модели.");
        return 0;
    }
    FILE * file = fdopen(own, "rb");
    if (file == nullptr) {
        close(own);
        throw_java(env, "Не удалось открыть файл модели.");
        return 0;
    }
    llama_backend_init();
    if (!has_cpu_backend(env)) {
        fclose(file);
        return 0;
    }
    auto state = std::make_shared<NexusLlamaState>();
    state->file = file;
    state->model = llama_model_load_from_file_ptr(file, cpu_model_params());
    return finish_create(env, std::move(state), context_size, threads);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_ai_hybrid_commandcenter_nexus_NativeLlamaBridge_nativeMetadata(
    JNIEnv * env, jobject, jlong handle, jstring key_value
) {
    if (key_value == nullptr) return nullptr;
    StateUse state(handle);
    if (!state.ok()) return nullptr;
    const char * key = env->GetStringUTFChars(key_value, nullptr);
    if (key == nullptr) return nullptr; // OutOfMemoryError is pending
    std::vector<char> buffer(256);
    int32_t length = llama_model_meta_val_str(state->model, key, buffer.data(), buffer.size());
    if (length >= static_cast<int32_t>(buffer.size())) {
        buffer.resize(static_cast<size_t>(length) + 1);
        length = llama_model_meta_val_str(state->model, key, buffer.data(), buffer.size());
    }
    env->ReleaseStringUTFChars(key_value, key);
    if (length < 0) return nullptr;
    return string_to_bytes(env, std::string(buffer.data(), static_cast<size_t>(length)));
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_ai_hybrid_commandcenter_nexus_NativeLlamaBridge_nativeChatTemplate(
    JNIEnv * env, jobject, jlong handle
) {
    StateUse state(handle);
    if (!state.ok()) return nullptr;
    const char * tmpl = llama_model_chat_template(state->model, nullptr);
    if (tmpl == nullptr) return nullptr;
    return string_to_bytes(env, std::string(tmpl));
}

// Formats a chat with the template stored in the GGUF. Returns null when
// llama.cpp does not recognise that template (the caller then uses ChatML).
extern "C" JNIEXPORT jbyteArray JNICALL
Java_ai_hybrid_commandcenter_nexus_NativeLlamaBridge_nativeFormatChat(
    JNIEnv * env, jobject, jlong handle, jobjectArray roles, jobjectArray contents
) {
    if (roles == nullptr || contents == nullptr) return nullptr;
    StateUse state(handle);
    if (!state.ok()) return nullptr;
    const char * tmpl = llama_model_chat_template(state->model, nullptr);
    if (tmpl == nullptr) return nullptr;
    const jsize count = std::min(env->GetArrayLength(roles), env->GetArrayLength(contents));
    std::vector<std::string> role_values;
    std::vector<std::string> content_values;
    role_values.reserve(static_cast<size_t>(count));
    content_values.reserve(static_cast<size_t>(count));
    for (jsize i = 0; i < count; ++i) {
        auto role = static_cast<jbyteArray>(env->GetObjectArrayElement(roles, i));
        auto content = static_cast<jbyteArray>(env->GetObjectArrayElement(contents, i));
        role_values.push_back(bytes_to_string(env, role));
        content_values.push_back(bytes_to_string(env, content));
        env->DeleteLocalRef(role);
        env->DeleteLocalRef(content);
        if (env->ExceptionCheck()) return nullptr;
    }
    std::vector<llama_chat_message> messages;
    messages.reserve(static_cast<size_t>(count));
    size_t total = 0;
    for (jsize i = 0; i < count; ++i) {
        messages.push_back({role_values[i].c_str(), content_values[i].c_str()});
        total += role_values[i].size() + content_values[i].size();
    }
    std::vector<char> buffer(total * 2 + 1024);
    int32_t length = llama_chat_apply_template(tmpl, messages.data(), messages.size(), true,
                                               buffer.data(), static_cast<int32_t>(buffer.size()));
    if (length < 0) return nullptr;
    if (length > static_cast<int32_t>(buffer.size())) {
        buffer.resize(static_cast<size_t>(length));
        length = llama_chat_apply_template(tmpl, messages.data(), messages.size(), true,
                                           buffer.data(), static_cast<int32_t>(buffer.size()));
        if (length < 0) return nullptr;
    }
    return string_to_bytes(env, std::string(buffer.data(), static_cast<size_t>(length)));
}

extern "C" JNIEXPORT void JNICALL
Java_ai_hybrid_commandcenter_nexus_NativeLlamaBridge_nativeGenerate(
    JNIEnv * env, jobject, jlong handle, jbyteArray prompt_value,
    jint max_tokens, jfloat temperature, jobject sink
) {
    if (sink == nullptr) {
        throw_java(env, "Не найден callback для потока токенов.");
        return;
    }
    StateUse state(handle);
    if (!state.ok()) {
        throw_java(env, "llama.cpp engine закрыт или не инициализирован.");
        return;
    }
    if (state->closing.load()) return;

    std::lock_guard<std::mutex> guard(state->generation_mutex);
    // A close that arrived while this call waited for the previous generation wins.
    if (state->closing.load()) return;
    state->cancelled.store(false);
    auto stop_requested = [&state]() { return state->cancelled.load() || state->closing.load(); };
    const std::string prompt = bytes_to_string(env, prompt_value);
    if (env->ExceptionCheck()) return;

    jclass sink_class = env->GetObjectClass(sink);
    const jmethodID on_token = env->GetMethodID(sink_class, "onToken", "([B)Z");
    env->DeleteLocalRef(sink_class);
    if (on_token == nullptr) return; // NoSuchMethodError is pending

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

    const int32_t n_ctx = static_cast<int32_t>(llama_n_ctx(state->context));
    const int32_t room = n_ctx - static_cast<int32_t>(prompt_tokens.size()) - 8;
    if (prompt_tokens.empty() || room < 64) {
        throw_java(env, "Запрос слишком длинный для контекста модели. Начни новый чат.");
        return;
    }
    const int limit = std::min(std::clamp(static_cast<int>(max_tokens), 1, 2048), static_cast<int>(room));

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
    llama_sampler_chain_add(sampler, llama_sampler_init_penalties(
        llama_vocab_n_tokens(state->vocab), 64, 1.08f, 0.0f, 0.0f));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_min_p(0.05f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(std::max(0.0f, temperature)));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    // Prefix cache: the gateway sends the whole chat every turn, but everything up
    // to the last answer is already in the KV cache. Keep the common prefix and
    // decode only the new tail. Recurrent/hybrid memories cannot drop a partial
    // sequence; then llama_memory_seq_rm refuses and the cache is rebuilt.
    llama_memory_t memory = llama_get_memory(state->context);
    size_t keep = 0;
    const size_t max_keep = prompt_tokens.size() - 1; // at least one token is decoded for fresh logits
    while (keep < state->cached.size() && keep < max_keep && state->cached[keep] == prompt_tokens[keep]) ++keep;
    if (keep == 0 || !llama_memory_seq_rm(memory, 0, static_cast<llama_pos>(keep), -1)) {
        llama_memory_clear(memory, false);
        keep = 0;
    }
    state->cached.assign(prompt_tokens.begin(), prompt_tokens.begin() + static_cast<long>(keep));

    // Long prompts are decoded in n_batch-sized slices.
    const int32_t n_batch = 512;
    bool prompt_ok = true;
    for (size_t offset = keep; offset < prompt_tokens.size() && prompt_ok; offset += n_batch) {
        const int32_t count = static_cast<int32_t>(std::min<size_t>(n_batch, prompt_tokens.size() - offset));
        prompt_ok = set_batch(batch, prompt_tokens.data() + offset, count, static_cast<llama_pos>(offset)) &&
            llama_process(state->context, LLAMA_PROCESS_TYPE_DECODE, batch) == 0;
        if (prompt_ok) state->cached.insert(state->cached.end(), prompt_tokens.begin() + static_cast<long>(offset),
                                            prompt_tokens.begin() + static_cast<long>(offset) + count);
        if (stop_requested()) break;
    }
    if (!prompt_ok || stop_requested()) {
        llama_memory_clear(memory, false);
        state->cached.clear();
        llama_sampler_free(sampler);
        llama_batch_ext_free(batch);
        if (!prompt_ok) throw_java(env, "llama.cpp завершил ошибкой обработку входного запроса.");
        return;
    }

    std::string pending;
    bool keep_going = true;
    auto emit = [&](bool flush_all) {
        const size_t ready = flush_all ? pending.size() : complete_utf8_prefix(pending);
        if (ready == 0) return;
        jbyteArray value = string_to_bytes(env, pending.substr(0, ready));
        pending.erase(0, ready);
        if (value == nullptr) { // OutOfMemoryError is pending: no more JNI calls
            keep_going = false;
            return;
        }
        const jboolean more = env->CallBooleanMethod(sink, on_token, value);
        env->DeleteLocalRef(value); // one local reference per token would overflow the table
        if (env->ExceptionCheck() || more == JNI_FALSE) keep_going = false;
    };

    llama_pos position = static_cast<llama_pos>(prompt_tokens.size());
    for (int step = 0; step < limit && keep_going && !stop_requested(); ++step) {
        const llama_token token = llama_sampler_sample(sampler, state->context, -1);
        if (llama_vocab_is_eog(state->vocab, token)) break;

        char small_buffer[256];
        int32_t bytes = llama_token_to_piece(state->vocab, token, small_buffer,
                                              static_cast<int32_t>(sizeof(small_buffer)), 0, true);
        if (bytes < 0) {
            std::vector<char> large_buffer(static_cast<size_t>(-bytes));
            bytes = llama_token_to_piece(state->vocab, token, large_buffer.data(),
                                          static_cast<int32_t>(large_buffer.size()), 0, true);
            if (bytes > 0) pending.append(large_buffer.data(), static_cast<size_t>(bytes));
        } else if (bytes > 0) {
            pending.append(small_buffer, static_cast<size_t>(bytes));
        }
        emit(false);
        if (!keep_going) break;

        if (!set_batch(batch, &token, 1, position) ||
            llama_process(state->context, LLAMA_PROCESS_TYPE_DECODE, batch) != 0) {
            LOGE("llama_process failed while decoding");
            llama_memory_clear(memory, false);
            state->cached.clear();
            break;
        }
        state->cached.push_back(token);
        ++position;
    }
    if (keep_going && !env->ExceptionCheck()) emit(true);

    llama_sampler_free(sampler);
    llama_batch_ext_free(batch);
}

// Asks a running generation to stop at the next token. Safe with any id, at any time.
extern "C" JNIEXPORT void JNICALL
Java_ai_hybrid_commandcenter_nexus_NativeLlamaBridge_nativeCancel(
    JNIEnv *, jobject, jlong handle
) {
    const StatePtr state = find_state(handle);
    if (state) state->cancelled.store(true);
}

// Stops and frees an engine. Returns only after calls in flight have left and the
// model and context are freed; a second call, or an unknown id, does nothing.
extern "C" JNIEXPORT void JNICALL
Java_ai_hybrid_commandcenter_nexus_NativeLlamaBridge_nativeDestroy(
    JNIEnv *, jobject, jlong handle
) {
    const StatePtr state = unregister_state(handle);
    if (!state) return;
    state->closing.store(true);
    state->cancelled.store(true);
    std::unique_lock<std::shared_mutex> lock(state->lifecycle);
    state->release();
}

#ifdef NEXUS_ASAN
// Sanitizer builds only (never in an APK given to people): a planted
// use-after-free that proves the CI sanitizer job really reports memory errors,
// so a clean lifecycle run means something.
extern "C" JNIEXPORT jint JNICALL
Java_ai_hybrid_commandcenter_nexus_NativeLlamaBridge_nativeAsanCanary(JNIEnv *, jobject) {
    auto * planted = new volatile int[8];
    planted[0] = 42;
    delete[] planted;
    return planted[0]; // heap-use-after-free on purpose
}
#endif
