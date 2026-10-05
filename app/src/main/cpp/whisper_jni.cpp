// JNI bridge between com.lecturenotes.whisper.WhisperNative and whisper.cpp.

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <sched.h>
#include <cstring>
#include <ctime>
#include <string>

#include "ggml-backend.h"
#include "whisper.h"

#define TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Handle {
    whisper_context *ctx = nullptr;
    std::atomic<bool> abort{false};
    // Set when the OS moved us to fewer CPUs than we have threads mid-run.
    std::atomic<bool> cpu_shrunk{false};
    int n_threads = 0;
    int64_t last_cpu_check_ms = 0;
};

int64_t now_ms() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

int allowed_cpu_count() {
    cpu_set_t set;
    CPU_ZERO(&set);
    if (sched_getaffinity(0, sizeof(set), &set) != 0) return -1;
    return CPU_COUNT(&set);
}

struct ProgressState {
    JNIEnv *env;
    jobject listener;
    jmethodID onProgress;
    int last = -1;
};

void on_progress(whisper_context *, whisper_state *, int progress, void *user_data) {
    auto *st = static_cast<ProgressState *>(user_data);
    if (st->listener == nullptr || progress == st->last) return;
    st->last = progress;
    st->env->CallVoidMethod(st->listener, st->onProgress, progress);
    if (st->env->ExceptionCheck()) st->env->ExceptionClear();
}

// Also stops the run if the usable CPUs drop below our thread count: ggml's threads wait on
// each other every step, so oversubscribing is catastrophic (measured ~14x slower on-device).
// The caller then retries the chunk with fewer threads.
bool should_abort(void *user_data) {
    auto *h = static_cast<Handle *>(user_data);
    if (h->abort.load()) return true;
    int64_t now = now_ms();
    if (now - h->last_cpu_check_ms >= 500) {
        h->last_cpu_check_ms = now;
        int cpus = allowed_cpu_count();
        if (cpus > 0 && cpus < h->n_threads) {
            LOGI("usable CPUs dropped to %d (< %d threads); stopping to retry", cpus, h->n_threads);
            h->cpu_shrunk.store(true);
            return true;
        }
    }
    return false;
}

void log_to_android(ggml_log_level level, const char *text, void *) {
    if (level >= GGML_LOG_LEVEL_WARN) __android_log_print(ANDROID_LOG_WARN, TAG, "%s", text);
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_lecturenotes_whisper_WhisperNative_nativeLoadBackends(JNIEnv *env, jobject, jstring jdir) {
    whisper_log_set(log_to_android, nullptr);
    const char *dir = env->GetStringUTFChars(jdir, nullptr);
    ggml_backend_load_all_from_path(dir);
    env->ReleaseStringUTFChars(jdir, dir);

    std::string info;
    for (size_t i = 0; i < ggml_backend_reg_count(); i++) {
        if (!info.empty()) info += ", ";
        info += ggml_backend_reg_name(ggml_backend_reg_get(i));
    }
    LOGI("backends: %s", info.c_str());
    return env->NewStringUTF(info.c_str());
}

// CPUs this thread may run on right now. Some phones confine background work
// (even foreground services) to a subset of cores, e.g. vivo's "l-foreground" cpuset.
JNIEXPORT jintArray JNICALL
Java_com_lecturenotes_whisper_WhisperNative_nativeAllowedCpus(JNIEnv *env, jobject) {
    cpu_set_t set;
    CPU_ZERO(&set);
    int cpus[CPU_SETSIZE];
    int n = 0;
    if (sched_getaffinity(0, sizeof(set), &set) == 0) {
        for (int i = 0; i < CPU_SETSIZE; i++) if (CPU_ISSET(i, &set)) cpus[n++] = i;
    }
    jintArray out = env->NewIntArray(n);
    env->SetIntArrayRegion(out, 0, n, cpus);
    return out;
}

JNIEXPORT jstring JNICALL
Java_com_lecturenotes_whisper_WhisperNative_nativeSystemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(whisper_print_system_info());
}

JNIEXPORT jlong JNICALL
Java_com_lecturenotes_whisper_WhisperNative_nativeInit(JNIEnv *env, jobject, jstring jpath) {
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(jpath, path);
    if (ctx == nullptr) {
        LOGE("failed to load model");
        return 0;
    }
    auto *h = new Handle();
    h->ctx = ctx;
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT void JNICALL
Java_com_lecturenotes_whisper_WhisperNative_nativeFree(JNIEnv *, jobject, jlong jhandle) {
    auto *h = reinterpret_cast<Handle *>(jhandle);
    if (h == nullptr) return;
    whisper_free(h->ctx);
    delete h;
}

// [encode_ms, decode_ms] since the last reset; decode includes sampling and batched decoding.
JNIEXPORT jfloatArray JNICALL
Java_com_lecturenotes_whisper_WhisperNative_nativeTimings(JNIEnv *env, jobject, jlong jhandle) {
    auto *h = reinterpret_cast<Handle *>(jhandle);
    jfloatArray out = env->NewFloatArray(2);
    if (h == nullptr) return out;
    whisper_timings *t = whisper_get_timings(h->ctx);
    if (t == nullptr) return out;
    jfloat v[2] = {t->encode_ms, t->decode_ms + t->batchd_ms + t->sample_ms + t->prompt_ms};
    env->SetFloatArrayRegion(out, 0, 2, v);
    return out;
}

JNIEXPORT void JNICALL
Java_com_lecturenotes_whisper_WhisperNative_nativeResetTimings(JNIEnv *, jobject, jlong jhandle) {
    auto *h = reinterpret_cast<Handle *>(jhandle);
    if (h != nullptr) whisper_reset_timings(h->ctx);
}

JNIEXPORT jboolean JNICALL
Java_com_lecturenotes_whisper_WhisperNative_nativeCpuShrunk(JNIEnv *, jobject, jlong jhandle) {
    auto *h = reinterpret_cast<Handle *>(jhandle);
    return h != nullptr && h->cpu_shrunk.load() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_lecturenotes_whisper_WhisperNative_nativeAbort(JNIEnv *, jobject, jlong jhandle) {
    auto *h = reinterpret_cast<Handle *>(jhandle);
    if (h != nullptr) h->abort.store(true);
}

// Returns NativeSegment[] with times relative to the start of `samples`,
// or null if decoding failed or was aborted.
JNIEXPORT jobjectArray JNICALL
Java_com_lecturenotes_whisper_WhisperNative_nativeTranscribe(
        JNIEnv *env, jobject, jlong jhandle, jfloatArray jsamples, jint n_threads,
        jstring jprompt, jobject listener) {
    auto *h = reinterpret_cast<Handle *>(jhandle);
    if (h == nullptr) return nullptr;
    h->abort.store(false);
    h->cpu_shrunk.store(false);
    h->n_threads = n_threads;
    h->last_cpu_check_ms = now_ms();

    std::string prompt;
    if (jprompt != nullptr) {
        const char *p = env->GetStringUTFChars(jprompt, nullptr);
        prompt = p;
        env->ReleaseStringUTFChars(jprompt, p);
    }

    ProgressState progress{env, listener, nullptr};
    if (listener != nullptr) {
        jclass cls = env->GetObjectClass(listener);
        progress.onProgress = env->GetMethodID(cls, "onProgress", "(I)V");
    }

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = n_threads;
    params.language = "en";
    params.translate = false;
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.suppress_blank = true;
    params.suppress_nst = true;
    params.initial_prompt = prompt.empty() ? nullptr : prompt.c_str();
    params.progress_callback = on_progress;
    params.progress_callback_user_data = &progress;
    params.abort_callback = should_abort;
    params.abort_callback_user_data = h;

    jsize n = env->GetArrayLength(jsamples);
    jfloat *samples = env->GetFloatArrayElements(jsamples, nullptr);
    int rc = whisper_full(h->ctx, params, samples, n);
    env->ReleaseFloatArrayElements(jsamples, samples, JNI_ABORT);

    if (rc != 0 || h->abort.load() || h->cpu_shrunk.load()) {
        LOGI("whisper_full stopped: rc=%d abort=%d cpu_shrunk=%d", rc, (int) h->abort.load(), (int) h->cpu_shrunk.load());
        return nullptr;
    }

    jclass segCls = env->FindClass("com/lecturenotes/whisper/NativeSegment");
    jmethodID ctor = env->GetMethodID(segCls, "<init>", "(JJ[BF)V");
    int count = whisper_full_n_segments(h->ctx);
    jobjectArray out = env->NewObjectArray(count, segCls, nullptr);
    for (int i = 0; i < count; i++) {
        // whisper timestamps are in centiseconds.
        jlong t0 = whisper_full_get_segment_t0(h->ctx, i) * 10;
        jlong t1 = whisper_full_get_segment_t1(h->ctx, i) * 10;
        // Pass raw bytes: a token boundary can split a UTF-8 sequence, which NewStringUTF rejects.
        const char *text = whisper_full_get_segment_text(h->ctx, i);
        jsize len = (jsize) strlen(text);
        jbyteArray bytes = env->NewByteArray(len);
        env->SetByteArrayRegion(bytes, 0, len, reinterpret_cast<const jbyte *>(text));
        jfloat noSpeech = whisper_full_get_segment_no_speech_prob(h->ctx, i);
        jobject seg = env->NewObject(segCls, ctor, t0, t1, bytes, noSpeech);
        env->SetObjectArrayElement(out, i, seg);
        env->DeleteLocalRef(seg);
        env->DeleteLocalRef(bytes);
    }
    return out;
}

}  // extern "C"
