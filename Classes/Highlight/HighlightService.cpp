#include "HighlightService.hpp"
#include "cocos2d.h"

#if (CC_TARGET_PLATFORM == CC_PLATFORM_ANDROID)

#include "GameFrameCapture.hpp"
#include <jni.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <memory>

#define TAG "HighlightService"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static std::unique_ptr<GameFrameCapture> gCapture;

// ── JNI 工具：在 GL 线程执行任务 ─────────────────────────────────────────
// Cocos GL 线程通过 Cocos2dxGLSurfaceView.queueEvent 投递，
// C++ 侧用 Director::getInstance()->getScheduler()->performFunctionInCocosThread
static void runOnGLThread(std::function<void()> fn) {
    cocos2d::Director::getInstance()
        ->getScheduler()
        ->performFunctionInCocosThread(fn);
}

// ── HighlightService 实现 ─────────────────────────────────────────────────

void HighlightService::onSurfaceReady(void* env, void* surface, int encW, int encH) {
    JNIEnv* jenv = static_cast<JNIEnv*>(env);
    jobject jsurface = static_cast<jobject>(surface);

    // ANativeWindow 从 Java Surface 对象获取，必须在有效 JNIEnv 上调用
    ANativeWindow* win = ANativeWindow_fromSurface(jenv, jsurface);
    if (!win) {
        LOGE("onSurfaceReady: ANativeWindow_fromSurface 失败");
        return;
    }
    LOGI("onSurfaceReady: ANativeWindow=%p encW=%d encH=%d", win, encW, encH);

    // GameFrameCapture 的 init 必须在 GL 线程执行（EGL 是线程绑定的）
    runOnGLThread([win, encW, encH]() {
        gCapture.reset(new GameFrameCapture());
        if (!gCapture->init(win, encW, encH)) {
            LOGE("GameFrameCapture::init 失败");
            gCapture.reset();
        } else {
            LOGI("GameFrameCapture 初始化成功");
        }
        // ANativeWindow 引用计数 -1，EGLSurface 已持有它，这里可以释放
        ANativeWindow_release(win);
    });
}

void HighlightService::start() {
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
    // 阶段三实现：通知 Java 侧 PacketRingBuffer.snapshot()
    LOGI("triggerHighlight: [%s]（阶段三实现）", eventName.c_str());
}

void HighlightService::stop() {
    runOnGLThread([]() {
        if (gCapture) {
            gCapture->stopCapture();
            gCapture->release();
            gCapture.reset();
        }
    });
}

// ── JNI 入口：从 Java AppActivity 调用 C++ ───────────────────────────────
// Java 侧调用顺序：
//   nativeOnSurfaceReady(surface, encW, encH)  → HighlightService::onSurfaceReady
//   nativeStart()                              → HighlightService::start
//   nativeStop()                               → HighlightService::stop

extern "C" {

JNIEXPORT void JNICALL
Java_org_cocos2dx_cpp_AppActivity_nativeOnSurfaceReady(
        JNIEnv* env, jobject /*thiz*/, jobject surface, jint encW, jint encH) {
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

// 非 Android 平台：空实现
void HighlightService::onSurfaceReady(void*, void*, int, int) {}
void HighlightService::start() {}
void HighlightService::triggerHighlight(const std::string&) {}
void HighlightService::stop() {}

#endif // CC_PLATFORM_ANDROID
