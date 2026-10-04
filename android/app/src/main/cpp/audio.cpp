#include <jni.h>
#include <aaudio/AAudio.h>
#include <vector>
#include <atomic>
#include <algorithm>
#include <ctime>
#include <cmath>
#include <new>
#include <thread>
#include <chrono>
#include <cstdio>

struct Player {
    std::vector<float> pcm;
    FILE* file = nullptr;
    std::thread producer;
    std::atomic<bool> stopping{false};
    std::atomic<int64_t> written{0}, consumed{0};
    std::atomic<int> starved{0};
    double clockBase = NAN;
    int64_t clockStarted = 0;
    ~Player() { stopping.store(true); if (producer.joinable()) producer.join(); if (file) fclose(file); }
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
        const auto consumed = p->consumed.load(std::memory_order_relaxed);
        if (consumed < p->written.load(std::memory_order_acquire)) {
            const auto index = (consumed % (p->pcm.size()/2)) * 2;
            out[i*2] = p->pcm[index]; out[i*2+1] = p->pcm[index+1];
            p->consumed.store(consumed+1, std::memory_order_release);
        } else { out[i*2] = out[i*2+1] = 0.f; p->starved.fetch_add(1); p->error.store(-10000); }
    }
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}
static void fail(AAudioStream*, void* data, aaudio_result_t error) {
    static_cast<Player*>(data)->error.store(error);
}
extern "C" JNIEXPORT jlong JNICALL Java_dev_foxdroid_app_NativeAudio_open(JNIEnv* env, jobject, jstring path, jint rate, jlong offset) {
    auto* p = new (std::nothrow) Player();
    if (!p) return 0;
    p->rate = rate; p->offset = offset; p->cursor = offset;
    try { p->pcm.resize(rate * 4); } catch (const std::bad_alloc&) { delete p; return 0; }
    const auto* filename = env->GetStringUTFChars(path, nullptr);
    if (!filename) { delete p; return 0; }
    p->file = fopen(filename,"rb"); env->ReleaseStringUTFChars(path,filename);
    if (!p->file || offset < 0 || fseek(p->file,offset*8,SEEK_SET) != 0) { delete p; return 0; }
    const auto capacity = static_cast<int64_t>(p->pcm.size()/2);
    const auto initial = fread(p->pcm.data(),sizeof(float)*2,capacity,p->file);
    std::fill(p->pcm.begin()+initial*2,p->pcm.end(),0.f); p->written.store(capacity);
    p->producer = std::thread([p,capacity] {
        while (!p->stopping.load()) {
            const auto written = p->written.load(std::memory_order_relaxed);
            const auto available = capacity - (written-p->consumed.load(std::memory_order_acquire));
            if (available < 1024) { std::this_thread::sleep_for(std::chrono::milliseconds(2)); continue; }
            const auto index = written % capacity;
            const auto count = std::min<int64_t>({available,capacity-index,4096});
            const auto read = fread(p->pcm.data()+index*2,sizeof(float)*2,count,p->file);
            if (ferror(p->file)) { p->error.store(-10001); return; }
            std::fill(p->pcm.begin()+(index+read)*2,p->pcm.begin()+(index+count)*2,0.f);
            p->written.store(written+count,std::memory_order_release);
        }
    });
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
    timespec now{}; clock_gettime(CLOCK_MONOTONIC, &now);
    const auto current = static_cast<int64_t>(now.tv_sec)*1000000000 + now.tv_nsec;
    if (time <= 0 || std::abs(current-time) > 1000000000LL) return NAN;
    if (!std::isfinite(p->clockBase)) {
        p->clockBase = frames + (current-time)*(p->rate/1e9);
        p->clockStarted = current;
    }
    const auto elapsed = (frames-p->clockBase)/p->rate + (nanos-time)/1e9;
    if (elapsed > (current-p->clockStarted)/1e9 + 0.5 || elapsed < -1.0) return NAN;
    return (p->offset + frames-p->clockBase + (nanos-time) * (p->rate/1e9)) / p->rate - 2.0;
}
extern "C" JNIEXPORT jintArray JNICALL Java_dev_foxdroid_app_NativeAudio_stats(JNIEnv* env, jobject, jlong ptr) {
    auto* p = reinterpret_cast<Player*>(ptr);
    jint values[] = {AAudioStream_getSampleRate(p->stream), AAudioStream_getFramesPerBurst(p->stream),
        AAudioStream_getBufferSizeInFrames(p->stream), AAudioStream_getXRunCount(p->stream), p->error.load(),
        AAudioStream_getPerformanceMode(p->stream), p->starved.load()};
    auto result = env->NewIntArray(7); env->SetIntArrayRegion(result, 0, 7, values); return result;
}
extern "C" JNIEXPORT void JNICALL Java_dev_foxdroid_app_NativeAudio_close(JNIEnv*, jobject, jlong ptr) {
    auto* p = reinterpret_cast<Player*>(ptr);
    if (p) { AAudioStream_close(p->stream); delete p; }
}
