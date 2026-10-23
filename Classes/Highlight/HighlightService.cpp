#include "HighlightService.hpp"
#include "cocos2d.h"

#if (CC_TARGET_PLATFORM == CC_PLATFORM_ANDROID)

#include "GameFrameCapture.hpp"
#include <jni.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <memory>
#include <vector>
#include <mutex>
#include <time.h>
#include "platform/android/jni/JniHelper.h"
#include "audio/android/PcmAudioService.h"

#define TAG "HighlightService"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static std::unique_ptr<GameFrameCapture> gCapture;

// ── 音频攒批状态 ──────────────────────────────────────────────────────────
// 攒够约 100ms 的 PCM 再传一次 JNI，降低调用频率（~43次/秒 → ~10次/秒）
static std::vector<uint8_t> gAudioAccumulator;
static int64_t   gFirstSampleTs  = -1;   // 本批第一个采样点的 CLOCK_MONOTONIC 时间戳（ns）
static int       gAudioSampleRate = 0;
static int       gAudioChannels   = 0;
static size_t    gAudioBatchThreshold = 0; // 攒批阈值（字节），init 后计算

// ── JNI 缓存 ──────────────────────────────────────────────────────────────
static jclass    gHighlightRecorderClass = nullptr;
static jmethodID gOnAudioBatchMethod     = nullptr;

// 保护 gAudioAccumulator 等攒批状态：
// onAudioFrame 在 OpenSL ES 音频线程调用，stop() 在主线程调用，两者会并发访问
static std::mutex gAudioMutex;

// 注意：音频回调运行在 OpenSL ES 创建的 native 线程上。native 线程上的 FindClass
// 只能用系统类加载器，找不到 APK 里的应用类。这里通过 Cocos 缓存的应用 ClassLoader
// （JniHelper::getStaticMethodInfo 内部使用）加载，并由 JniHelper::getEnv() 通过
// pthread key 负责线程退出时自动 Detach。
static bool initAudioJni() {
    if (gHighlightRecorderClass && gOnAudioBatchMethod) return true;

    // static void onAudioBatch(byte[] pcm, int sampleRate, int channels, long ptsNs)
    cocos2d::JniMethodInfo mi;
    if (!cocos2d::JniHelper::getStaticMethodInfo(
            mi, "org/cocos2dx/cpp/highlight/HighlightRecorder",
            "onAudioBatch", "([BIIJ)V")) {
        LOGE("initAudioJni: 找不到 HighlightRecorder.onAudioBatch");
        return false;
    }
    gHighlightRecorderClass = (jclass)mi.env->NewGlobalRef(mi.classID);
    gOnAudioBatchMethod = mi.methodID;
    mi.env->DeleteLocalRef(mi.classID);
    return true;
}

static int64_t audioNowNs() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

// 调用方必须已持有 gAudioMutex
static void flushAudioBatchLocked() {
    if (gAudioAccumulator.empty()) return;

    JNIEnv* env = cocos2d::JniHelper::getEnv();
    if (!env || !initAudioJni()) {
        gAudioAccumulator.clear();
        gFirstSampleTs = -1;
        return;
    }

    int size = (int)gAudioAccumulator.size();
    jbyteArray jArr = env->NewByteArray(size);
    if (jArr) {
        env->SetByteArrayRegion(jArr, 0, size, (jbyte*)gAudioAccumulator.data());
        env->CallStaticVoidMethod(
            gHighlightRecorderClass, gOnAudioBatchMethod,
            jArr, gAudioSampleRate, gAudioChannels, (jlong)gFirstSampleTs);
        env->DeleteLocalRef(jArr);
    }

    // 任何一步抛了 Java 异常都必须清掉，否则这个线程后续的 JNI 调用会被 CheckJNI 中止
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }

    gAudioAccumulator.clear();
    gFirstSampleTs = -1;
}

// ── JNI 工具：在 GL 线程执行任务 ─────────────────────────────────────────
static void runOnGLThread(std::function<void()> fn) {
    cocos2d::Director::getInstance()
        ->getScheduler()
        ->performFunctionInCocosThread(fn);
}

// ── HighlightService 实现 ─────────────────────────────────────────────────

void HighlightService::onAudioFrame(const void* buf, size_t size,
                                     int sampleRate, int channels) {
    if (!buf || size == 0) return;

    std::lock_guard<std::mutex> lock(gAudioMutex);

    // 首次调用时初始化采样率/声道数/阈值
    if (gAudioSampleRate == 0) {
        gAudioSampleRate = sampleRate;
        gAudioChannels   = channels;
        // 攒批阈值：100ms 的 PCM 字节数
        // bytes = sampleRate × channels × 2(S16) × 0.1s
        gAudioBatchThreshold = (size_t)(sampleRate * channels * 2 * 0.1);
        LOGI("音频攒批初始化: sampleRate=%d channels=%d threshold=%zu",
             sampleRate, channels, gAudioBatchThreshold);
    }

    // 记录本批第一个采样点的时间戳（与视频帧同一时钟源）
    if (gFirstSampleTs < 0) {
        gFirstSampleTs = audioNowNs();
    }

    const uint8_t* data = (const uint8_t*)buf;
    gAudioAccumulator.insert(gAudioAccumulator.end(), data, data + size);

    // 攒够阈值则传给 Java
    if (gAudioAccumulator.size() >= gAudioBatchThreshold) {
        flushAudioBatchLocked();
    }
}

void HighlightService::onSurfaceReady(void* env, void* surface, int encW, int encH) {
    JNIEnv* jenv = static_cast<JNIEnv*>(env);
    jobject jsurface = static_cast<jobject>(surface);

    ANativeWindow* win = ANativeWindow_fromSurface(jenv, jsurface);
    if (!win) {
        LOGE("onSurfaceReady: ANativeWindow_fromSurface 失败");
        return;
    }
    LOGI("onSurfaceReady: ANativeWindow=%p encW=%d encH=%d", win, encW, encH);

    runOnGLThread([win, encW, encH]() {
        gCapture.reset(new GameFrameCapture());
        if (!gCapture->init(win, encW, encH)) {
            LOGE("GameFrameCapture::init 失败");
            gCapture.reset();
        } else {
            LOGI("GameFrameCapture 初始化成功");
        }
        ANativeWindow_release(win);
    });
}

void HighlightService::start() {
    // 注入音频分流回调（录制开始时才开始采集，避免录制前的音频进入缓冲）
    cocos2d::experimental::PcmAudioService::setAudioCaptureCallback(
        [](const void* buf, size_t size, int sampleRate, int channels) {
            HighlightService::onAudioFrame(buf, size, sampleRate, channels);
        });

    runOnGLThread([]() {
        if (!gCapture) {
            LOGE("start: GameFrameCapture 未初始化");
            return;
        }
        gCapture->startCapture();
        LOGI("采集已启动");
    });
}

void HighlightService::triggerHighlight(const std::string& eventName) {
    // 触发时刻取 CLOCK_MONOTONIC（微秒），与视频帧、音频批次的 PTS 同一时钟源，
    // Java 侧据此在环形缓冲里定位 [触发前1秒, 触发后1秒] 的 packet
    const int64_t triggerUs = audioNowNs() / 1000;

    // 只传事件名和时间戳，不传任何包数据；点击回调频率低，每次直接取类即可，不做缓存。
    // 时间戳是 64 位，JniHelper::callStaticVoidMethod 的模板不支持，所以手写调用。
    cocos2d::JniMethodInfo mi;
    if (!cocos2d::JniHelper::getStaticMethodInfo(
            mi, "org/cocos2dx/cpp/highlight/HighlightRecorder",
            "onTriggerHighlight", "(Ljava/lang/String;J)V")) {
        LOGE("triggerHighlight: 找不到 HighlightRecorder.onTriggerHighlight");
        return;
    }

    jstring jName = mi.env->NewStringUTF(eventName.c_str());
    mi.env->CallStaticVoidMethod(mi.classID, mi.methodID, jName, (jlong)triggerUs);
    if (mi.env->ExceptionCheck()) {
        mi.env->ExceptionDescribe();
        mi.env->ExceptionClear();
    }
    if (jName) mi.env->DeleteLocalRef(jName);
    mi.env->DeleteLocalRef(mi.classID);

    LOGI("triggerHighlight: [%s] triggerUs=%lld", eventName.c_str(), (long long)triggerUs);
}

void HighlightService::stop() {
    // 先清除音频回调，停止分流
    cocos2d::experimental::PcmAudioService::setAudioCaptureCallback(nullptr);

    // 把剩余攒批数据刷出去，不丢最后一段音频
    {
        std::lock_guard<std::mutex> lock(gAudioMutex);
        flushAudioBatchLocked();
    }

    runOnGLThread([]() {
        if (gCapture) {
            gCapture->stopCapture();
            gCapture->release();
            gCapture.reset();
        }
    });
}

// ── JNI 入口 ──────────────────────────────────────────────────────────────

extern "C" {

JNIEXPORT void JNICALL
Java_org_cocos2dx_cpp_AppActivity_nativeOnSurfaceReady(
        JNIEnv* env, jobject, jobject surface, jint encW, jint encH) {
    HighlightService::onSurfaceReady(env, surface, encW, encH);
}

JNIEXPORT void JNICALL
Java_org_cocos2dx_cpp_AppActivity_nativeStart(JNIEnv*, jobject) {
    HighlightService::start();
}

JNIEXPORT void JNICALL
Java_org_cocos2dx_cpp_AppActivity_nativeStop(JNIEnv*, jobject) {
    HighlightService::stop();
}

} // extern "C"

#else

void HighlightService::onSurfaceReady(void*, void*, int, int) {}
void HighlightService::start() {}
void HighlightService::triggerHighlight(const std::string&) {}
void HighlightService::stop() {}
void HighlightService::onAudioFrame(const void*, size_t, int, int) {}

#endif // CC_PLATFORM_ANDROID
