#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>

#include "llama.h"

#define TAG "LlamaBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

// invoke_callback is kept for future use but NOT called in the generation loop.
// The loop caches jmethodID once and passes only the new piece per token.

// ---------------------------------------------------------------------------
// Context handle — bundles model + context (one Long covers both)
// ---------------------------------------------------------------------------
struct LlamaContext {
    llama_model*   model   = nullptr;
    llama_context* context = nullptr;
};

// ---------------------------------------------------------------------------
// nativeInitTextModel
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jlong JNICALL
Java_com_oceanguard_ai_inference_LlamaCppBridge_nativeInitTextModel(
        JNIEnv* env, jobject /* thiz */,
        jstring modelPath, jint nCtx, jint nThreads, jint nThreadsBatch) {

    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("Loading model: %s (nCtx=%d, nThreads=%d, nThreadsBatch=%d)",
         path, nCtx, nThreads, nThreadsBatch);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0; // CPU-only

    llama_model* model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(modelPath, path);

    if (!model) {
        LOGE("Failed to load model");
        return 0L;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx         = static_cast<uint32_t>(nCtx);
    cparams.n_threads     = static_cast<uint32_t>(nThreads);
    cparams.n_threads_batch = static_cast<uint32_t>(nThreadsBatch);

    llama_context* ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        LOGE("Failed to create llama_context");
        llama_model_free(model);
        return 0L;
    }

    LOGI("Model loaded OK");
    auto* handle = new LlamaContext{model, ctx};
    return reinterpret_cast<jlong>(handle);
}

// ---------------------------------------------------------------------------
// nativeGenerateText — streaming via TokenStreamCallback
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jstring JNICALL
Java_com_oceanguard_ai_inference_LlamaCppBridge_nativeGenerateText(
        JNIEnv* env, jobject /* thiz */,
        jlong contextHandle, jstring prompt,
        jint maxTokens, jfloat temperature, jint topK,
        jobject callback) {

    auto* handle = reinterpret_cast<LlamaContext*>(contextHandle);
    if (!handle || !handle->context) {
        LOGE("nativeGenerateText: null handle");
        return env->NewStringUTF("");
    }

    const char* promptStr = env->GetStringUTFChars(prompt, nullptr);
    std::string promptText(promptStr);
    env->ReleaseStringUTFChars(prompt, promptStr);

    const struct llama_vocab* vocab = llama_model_get_vocab(handle->model);

    // Tokenize — two-pass: first pass to get size, second to fill
    const int n_prompt = -llama_tokenize(
        vocab, promptText.c_str(), static_cast<int32_t>(promptText.size()),
        nullptr, 0, /*add_special=*/true, /*parse_special=*/true);
    std::vector<llama_token> tokens(n_prompt);
    llama_tokenize(
        vocab, promptText.c_str(), static_cast<int32_t>(promptText.size()),
        tokens.data(), static_cast<int32_t>(tokens.size()),
        /*add_special=*/true, /*parse_special=*/true);

    // Clear KV cache and eval prompt
    llama_memory_clear(llama_get_memory(handle->context), true);
    llama_batch batch = llama_batch_get_one(tokens.data(), static_cast<int32_t>(tokens.size()));
    if (llama_decode(handle->context, batch) != 0) {
        LOGE("llama_decode failed on prompt");
        return env->NewStringUTF("");
    }

    // Sampling
    llama_sampler* sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(static_cast<int32_t>(topK)));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    jobject globalCb = env->NewGlobalRef(callback);

    // Cache jmethodID once — avoids GetObjectClass+GetMethodID on every token.
    jclass cbCls = env->GetObjectClass(globalCb);
    jmethodID mid = env->GetMethodID(cbCls, "onToken", "(Ljava/lang/String;)V");
    env->DeleteLocalRef(cbCls);
    if (!mid) {
        LOGE("onToken method not found");
        llama_sampler_free(sampler);
        env->DeleteGlobalRef(globalCb);
        return env->NewStringUTF("");
    }

    std::string result;
    result.reserve(8192);
    char piece_buf[256];

    const llama_token eos = llama_vocab_eos(vocab);

    for (int i = 0; i < maxTokens; ++i) {
        llama_token token_id = llama_sampler_sample(sampler, handle->context, -1);
        if (token_id == eos) break;

        int n = llama_token_to_piece(
            vocab, token_id, piece_buf, sizeof(piece_buf) - 1, 0, true);
        if (n < 0) break;
        piece_buf[n] = '\0';
        result.append(piece_buf, n);

        // Send only the new piece — O(1) alloc per token vs O(n) for full accumulated text.
        jstring jpiece = env->NewStringUTF(piece_buf);
        env->CallVoidMethod(globalCb, mid, jpiece);
        env->DeleteLocalRef(jpiece);

        llama_batch next = llama_batch_get_one(&token_id, 1);
        if (llama_decode(handle->context, next) != 0) {
            LOGE("llama_decode failed at token %d", i);
            break;
        }
    }

    llama_sampler_free(sampler);
    env->DeleteGlobalRef(globalCb);
    llama_memory_clear(llama_get_memory(handle->context), true);

    return env->NewStringUTF(result.c_str());
}

// ---------------------------------------------------------------------------
// nativeWarmUp — short inference to prime CPU caches
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT void JNICALL
Java_com_oceanguard_ai_inference_LlamaCppBridge_nativeWarmUp(
        JNIEnv* /* env */, jobject /* thiz */, jlong contextHandle) {

    auto* handle = reinterpret_cast<LlamaContext*>(contextHandle);
    if (!handle || !handle->context) return;

    const struct llama_vocab* vocab = llama_model_get_vocab(handle->model);
    const char* warmup_text = "<|im_start|>user\nhi<|im_end|>\n<|im_start|>assistant\n";
    const int text_len = static_cast<int>(strlen(warmup_text));

    const int n = -llama_tokenize(vocab, warmup_text, text_len, nullptr, 0, true, true);
    if (n <= 0) return;
    std::vector<llama_token> tokens(n);
    llama_tokenize(vocab, warmup_text, text_len, tokens.data(), n, true, true);

    llama_memory_clear(llama_get_memory(handle->context), true);
    llama_batch batch = llama_batch_get_one(tokens.data(), static_cast<int32_t>(n));
    llama_decode(handle->context, batch);
    llama_memory_clear(llama_get_memory(handle->context), true);
    LOGD("Warm-up complete");
}

// ---------------------------------------------------------------------------
// nativeReleaseModel
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT void JNICALL
Java_com_oceanguard_ai_inference_LlamaCppBridge_nativeReleaseModel(
        JNIEnv* /* env */, jobject /* thiz */, jlong contextHandle) {

    auto* handle = reinterpret_cast<LlamaContext*>(contextHandle);
    if (!handle) return;
    if (handle->context) llama_free(handle->context);
    if (handle->model)   llama_model_free(handle->model);
    delete handle;
    LOGI("Model released");
}
