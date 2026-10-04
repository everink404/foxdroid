#include <jni.h>
#include <aaudio/AAudio.h>
#include <vector>
#include <atomic>
#include <algorithm>
#include <ctime>
#include <cmath>
#include <new>

struct Player {
    std::vector<float> pcm;
    AAudioStream* stream = nullptr;
    int rate = 48000;
    int64_t offset = 0;
    int64_t cursor = 0;
    std::atomic<int> error{0};
};
static aaudio_data_callback_result_t render(AAudioStream*, void* data, void* output, int32_t frames) {
    auto* p = static_cast<Player*>(data);
    auto* out = static_cast<float*>(output);
    for (int i = 0; i < frames; ++i, ++p->cursor) {
        const auto index = p->cursor * 2;
        out[i*2] = index < static_cast<int64_t>(p->pcm.size()) ? p->pcm[index] : 0.f;
        out[i*2+1] = index+1 < static_cast<int64_t>(p->pcm.size()) ? p->pcm[index+1] : 0.f;
    }
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}
static void fail(AAudioStream*, void* data, aaudio_result_t error) {
    static_cast<Player*>(data)->error.store(error);
}
extern "C" JNIEXPORT jlong JNICALL Java_dev_foxdroid_app_NativeAudio_open(JNIEnv* env, jobject, jfloatArray pcm, jint rate, jlong offset) {
    auto* p = new (std::nothrow) Player();
    if (!p) return 0;
    p->rate = rate; p->offset = offset; p->cursor = offset;
    try { p->pcm.resize(env->GetArrayLength(pcm)); } catch (const std::bad_alloc&) { delete p; return 0; }
    env->GetFloatArrayRegion(pcm, 0, p->pcm.size(), p->pcm.data());
    if (env->ExceptionCheck()) { delete p; return 0; }
    AAudioStreamBuilder* builder = nullptr;
    if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK) { delete p; return 0; }
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setChannelCount(builder, 2);
    AAudioStreamBuilder_setSampleRate(builder, rate);
    AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_GAME);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setDataCallback(builder, render, p);
    AAudioStreamBuilder_setErrorCallback(builder, fail, p);
    const auto result = AAudioStreamBuilder_openStream(builder, &p->stream);
    AAudioStreamBuilder_delete(builder);
    if (result != AAUDIO_OK) { delete p; return 0; }
    if (AAudioStream_getSampleRate(p->stream) != rate || AAudioStream_getChannelCount(p->stream) != 2 ||
        AAudioStream_getFormat(p->stream) != AAUDIO_FORMAT_PCM_FLOAT || AAudioStream_requestStart(p->stream) != AAUDIO_OK) {
        AAudioStream_close(p->stream); delete p; return 0;
    }
    return reinterpret_cast<jlong>(p);
}
extern "C" JNIEXPORT jdouble JNICALL Java_dev_foxdroid_app_NativeAudio_position(JNIEnv*, jobject, jlong ptr, jlong nanos) {
    auto* p = reinterpret_cast<Player*>(ptr);
    if (!p || p->error.load() != 0) return NAN;
    int64_t frames = 0, time = 0;
    if (AAudioStream_getTimestamp(p->stream, CLOCK_MONOTONIC, &frames, &time) != AAUDIO_OK) return NAN;
    return (p->offset + frames + (nanos-time) * (p->rate/1e9)) / p->rate - 2.0;
}
extern "C" JNIEXPORT jintArray JNICALL Java_dev_foxdroid_app_NativeAudio_stats(JNIEnv* env, jobject, jlong ptr) {
    auto* p = reinterpret_cast<Player*>(ptr);
    jint values[] = {AAudioStream_getSampleRate(p->stream), AAudioStream_getFramesPerBurst(p->stream),
        AAudioStream_getBufferSizeInFrames(p->stream), AAudioStream_getXRunCount(p->stream), p->error.load(),
        AAudioStream_getPerformanceMode(p->stream)};
    auto result = env->NewIntArray(6); env->SetIntArrayRegion(result, 0, 6, values); return result;
}
extern "C" JNIEXPORT void JNICALL Java_dev_foxdroid_app_NativeAudio_close(JNIEnv*, jobject, jlong ptr) {
    auto* p = reinterpret_cast<Player*>(ptr);
    if (p) { AAudioStream_close(p->stream); delete p; }
}
