#pragma once

#if (CC_TARGET_PLATFORM == CC_PLATFORM_ANDROID)

#include "cocos2d.h"
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include "platform/CCGL.h"

/**
 * GameFrameCapture：在 Cocos GL 线程上，每帧把游戏画面送进编码器。
 *
 * 方案A（同线程同 Context）流程：
 *   1. glBlitFramebuffer(FBO=0 → texFBO)    拷屏+缩放到中间纹理
 *   2. eglMakeCurrent(encodeSurface)         切换渲染目标
 *   3. drawFullscreenQuad(tex)               把纹理画到编码 Surface（处理Y轴翻转）
 *   4. eglPresentationTimeANDROID(timestamp) 打时间戳
 *   5. eglSwapBuffers(encodeSurface)         帧推入 MediaCodec
 *   6. eglMakeCurrent(gameSurface)           切回游戏
 */
class GameFrameCapture {
public:
    GameFrameCapture() = default;
    ~GameFrameCapture() { release(); }

    /**
     * 初始化：接收 Java InputSurface 的 ANativeWindow，建编码 EGLSurface 和中间纹理。
     * 必须在 Cocos GL 线程上调用（EGL/GL 是线程绑定的）。
     *
     * @param nativeWindow  由 ANativeWindow_fromSurface(env, surface) 获得
     * @param encW          编码器宽度（可能因对齐与游戏分辨率不同）
     * @param encH          编码器高度
     * @return 初始化成功返回 true
     */
    bool init(void* nativeWindow, int encW, int encH);

    /**
     * 挂 Director::EVENT_AFTER_DRAW 钩子，开始每帧采集。
     * 必须在 init() 之后调用，且在 GL 线程。
     */
    void startCapture();

    /**
     * 移除钩子，停止采集。
     */
    void stopCapture();

    /** 释放所有 EGL/GL 资源。 */
    void release();

    bool isCapturing() const { return capturing_; }

private:
    // 每帧由 EVENT_AFTER_DRAW 回调执行
    void onAfterDraw();

    // 初始化 GLSL 着色器程序（全屏四边形 + Y轴翻转）
    bool initShader();

    // 初始化中间 FBO 和纹理（用于 glBlitFramebuffer 的目标）
    bool initTextureFBO(int w, int h);

    // EGL 资源
    EGLDisplay eglDisplay_    = EGL_NO_DISPLAY;
    EGLSurface encodeSurface_ = EGL_NO_SURFACE;
    EGLSurface gameSurface_   = EGL_NO_SURFACE;  // 保存游戏原来的 Surface
    EGLContext eglContext_     = EGL_NO_CONTEXT;  // 共享的游戏 Context

    // 中间纹理 FBO（glBlitFramebuffer 目标）
    GLuint texFBO_     = 0;
    GLuint texColor_   = 0;

    // 全屏四边形着色器
    GLuint shaderProgram_ = 0;
    GLuint vao_           = 0;
    GLuint vbo_           = 0;

    int encW_ = 0;
    int encH_ = 0;
    int gameW_ = 0;
    int gameH_ = 0;

    bool capturing_ = false;

    // EVENT_AFTER_DRAW listener 引用，用于精确 remove
    cocos2d::EventListenerCustom* afterDrawListener_ = nullptr;

    // PFNEGLPRESENTATIONTIMEANDROIDPROC 函数指针
    // eglext.h 不一定定义，手动声明
    typedef EGLBoolean (*EGLPresentationTimeANDROID)(EGLDisplay, EGLSurface, EGLnsecsANDROID);
    EGLPresentationTimeANDROID eglPresentationTime_ = nullptr;
};

#endif // CC_PLATFORM_ANDROID
