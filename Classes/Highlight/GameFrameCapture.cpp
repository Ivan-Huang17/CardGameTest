#include "GameFrameCapture.hpp"

#if (CC_TARGET_PLATFORM == CC_PLATFORM_ANDROID)

#include "cocos2d.h"  // cocos2d.h 内部已 include CCGL.h，GL 函数全部可用
#include <android/log.h>
#include <android/native_window.h>
#include <time.h>
#include <cstring>

// GLES3 的 blit 常量
#ifndef GL_READ_FRAMEBUFFER
#define GL_READ_FRAMEBUFFER 0x8CA8
#endif
#ifndef GL_DRAW_FRAMEBUFFER
#define GL_DRAW_FRAMEBUFFER 0x8CA9
#endif

// glBlitFramebuffer 属于 GLES3，Cocos 的 CCGL.h 只引入 GLES2 头文件，
// 用函数指针在运行时取，彻底避免头文件冲突
typedef void (*PFNGLBLITFRAMEBUFFER)(
        GLint, GLint, GLint, GLint, GLint, GLint, GLint, GLint, GLbitfield, GLenum);

static PFNGLBLITFRAMEBUFFER sGlBlitFramebuffer = nullptr;

#define TAG "GameFrameCapture"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ── 着色器源码 ────────────────────────────────────────────────────────────
// 全屏四边形，UV 的 Y 轴翻转：GL 坐标原点在左下，视频/纹理期望左上，所以 1.0-v
static const char *VERT_SRC = R"(
    attribute vec2 aPos;
    attribute vec2 aUV;
    varying vec2 vUV;
    void main() {
        gl_Position = vec4(aPos, 0.0, 1.0);
        vUV = aUV;
    }
)";

static const char *FRAG_SRC = R"(
    precision mediump float;
    varying vec2 vUV;
    uniform sampler2D uTex;
    void main() {
        gl_FragColor = texture2D(uTex, vUV);
    }
)";

// 全屏四边形顶点（NDC坐标 + UV）
static const float QUAD[] = {
        // x      y     u     v
        -1.0f, -1.0f, 0.0f, 0.0f,
        1.0f, -1.0f, 1.0f, 0.0f,
        -1.0f, 1.0f, 0.0f, 1.0f,
        1.0f, 1.0f, 1.0f, 1.0f,
};

// ── 工具函数 ─────────────────────────────────────────────────────────────

static GLuint compileShader(GLenum type, const char *src) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, nullptr);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char buf[512];
        glGetShaderInfoLog(s, sizeof(buf), nullptr, buf);
        LOGE("着色器编译失败: %s", buf);
        glDeleteShader(s);
        return 0;
    }
    return s;
}

static int64_t nowNs() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t) ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

// ── GameFrameCapture 实现 ─────────────────────────────────────────────────

bool GameFrameCapture::init(void *nativeWindow, int encW, int encH) {
    encW_ = encW;
    encH_ = encH;

    // 取游戏当前的 EGL 状态
    eglDisplay_ = eglGetCurrentDisplay();
    eglContext_ = eglGetCurrentContext();
    gameSurface_ = eglGetCurrentSurface(EGL_DRAW);

    if (eglDisplay_ == EGL_NO_DISPLAY || eglContext_ == EGL_NO_CONTEXT) {
        LOGE("init: 无法获取游戏 EGL 状态，必须在 GL 线程调用");
        return false;
    }

    // 取游戏屏幕分辨率（用于 glBlitFramebuffer 的源区域）
    auto *director = cocos2d::Director::getInstance();
    auto frameSize = director->getOpenGLView()->getFrameSize();
    gameW_ = (int) frameSize.width;
    gameH_ = (int) frameSize.height;
    LOGI("游戏分辨率: %dx%d  编码分辨率: %dx%d", gameW_, gameH_, encW_, encH_);

    // 用游戏 Context 的 EGLConfig（已验证 EGL_RECORDABLE_ANDROID=1）
    EGLint configId = 0;
    eglQueryContext(eglDisplay_, eglContext_, EGL_CONFIG_ID, &configId);
    EGLint selAttribs[] = {EGL_CONFIG_ID, configId, EGL_NONE};
    EGLConfig config = nullptr;
    EGLint numCfg = 0;
    eglChooseConfig(eglDisplay_, selAttribs, &config, 1, &numCfg);
    if (numCfg <= 0) {
        LOGE("init: eglChooseConfig 失败");
        return false;
    }

    // 用 InputSurface（ANativeWindow）创建编码 EGLSurface
    encodeSurface_ = eglCreateWindowSurface(
            eglDisplay_, config, (EGLNativeWindowType) nativeWindow, nullptr);
    if (encodeSurface_ == EGL_NO_SURFACE) {
        LOGE("init: eglCreateWindowSurface 失败 error=0x%x", eglGetError());
        return false;
    }
    LOGI("编码 EGLSurface 创建成功");

    // 取 eglPresentationTimeANDROID 函数指针
    eglPresentationTime_ = (EGLPresentationTimeANDROID)
            eglGetProcAddress("eglPresentationTimeANDROID");
    if (!eglPresentationTime_) {
        LOGE("init: eglPresentationTimeANDROID 不可用");
        return false;
    }

    // 取 glBlitFramebuffer 函数指针（运行时取，不依赖 GLES3 头文件）
    sGlBlitFramebuffer = (PFNGLBLITFRAMEBUFFER)
            eglGetProcAddress("glBlitFramebuffer");
    if (!sGlBlitFramebuffer) {
        LOGE("init: glBlitFramebuffer 不可用（设备不支持 GLES3）");
        return false;
    }

    // 初始化着色器和中间 FBO（在当前 Context 上创建，两个 Surface 共享）
    if (!initShader()) return false;
    if (!initTextureFBO(encW_, encH_)) return false;

    LOGI("init() 成功");
    return true;
}

bool GameFrameCapture::initShader() {
    GLuint vert = compileShader(GL_VERTEX_SHADER, VERT_SRC);
    GLuint frag = compileShader(GL_FRAGMENT_SHADER, FRAG_SRC);
    if (!vert || !frag) return false;

    shaderProgram_ = glCreateProgram();
    glAttachShader(shaderProgram_, vert);
    glAttachShader(shaderProgram_, frag);
    glBindAttribLocation(shaderProgram_, 0, "aPos");
    glBindAttribLocation(shaderProgram_, 1, "aUV");
    glLinkProgram(shaderProgram_);
    glDeleteShader(vert);
    glDeleteShader(frag);

    GLint ok = 0;
    glGetProgramiv(shaderProgram_, GL_LINK_STATUS, &ok);
    if (!ok) {
        char buf[512];
        glGetProgramInfoLog(shaderProgram_, sizeof(buf), nullptr, buf);
        LOGE("着色器链接失败: %s", buf);
        return false;
    }

    // 上传顶点数据
    glGenBuffers(1, &vbo_);
    glBindBuffer(GL_ARRAY_BUFFER, vbo_);
    glBufferData(GL_ARRAY_BUFFER, sizeof(QUAD), QUAD, GL_STATIC_DRAW);
    glBindBuffer(GL_ARRAY_BUFFER, 0);

    LOGI("着色器初始化成功");
    return true;
}

bool GameFrameCapture::initTextureFBO(int w, int h) {
    // 创建颜色纹理
    glGenTextures(1, &texColor_);
    glBindTexture(GL_TEXTURE_2D, texColor_);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, w, h, 0,
                 GL_RGB, GL_UNSIGNED_BYTE, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glBindTexture(GL_TEXTURE_2D, 0);

    // 创建 FBO 并绑定纹理
    glGenFramebuffers(1, &texFBO_);
    glBindFramebuffer(GL_FRAMEBUFFER, texFBO_);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                           GL_TEXTURE_2D, texColor_, 0);

    GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);

    if (status != GL_FRAMEBUFFER_COMPLETE) {
        LOGE("中间 FBO 创建失败 status=0x%x", status);
        return false;
    }
    LOGI("中间 FBO 初始化成功 %dx%d", w, h);
    return true;
}

void GameFrameCapture::startCapture() {
    if (capturing_) return;
    capturing_ = true;

    auto *dispatcher = cocos2d::Director::getInstance()->getEventDispatcher();

    // 用 addEventListenerWithFixedPriority，不依赖 addCustomEventListener 的全局队列
    // Phase2Verifier 的 removeCustomEventListeners 不会影响这里注册的 listener
    auto *listener = cocos2d::EventListenerCustom::create(
            cocos2d::Director::EVENT_AFTER_DRAW,
            [this](cocos2d::EventCustom *) {
                if (capturing_) {
                    onAfterDraw();
                }
            });
    listener->setEnabled(true);
    dispatcher->addEventListenerWithFixedPriority(listener, -1);
    afterDrawListener_ = listener;

    LOGI("startCapture: 已挂 EVENT_AFTER_DRAW 钩子");
}

void GameFrameCapture::stopCapture() {
    capturing_ = false;
    auto *dispatcher = cocos2d::Director::getInstance()->getEventDispatcher();
    if (afterDrawListener_) {
        dispatcher->removeEventListener(afterDrawListener_);
        afterDrawListener_ = nullptr;
    }
    LOGI("stopCapture: 已移除钩子");
}

void GameFrameCapture::onAfterDraw() {
    static int logCount = 0;

    ++logCount;

    LOGI("TEST: frame=%d begin", logCount);

    // 1. 将游戏画面复制到 texFBO_
    glFinish();
    glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, texFBO_);

    if (sGlBlitFramebuffer == nullptr) {
        LOGE("TEST: sGlBlitFramebuffer is nullptr");
        glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
        return;
    }

    sGlBlitFramebuffer(
            0, 0, gameW_, gameH_,
            0, 0, encW_, encH_,
            GL_COLOR_BUFFER_BIT, GL_LINEAR
    );

    GLenum err = glGetError();
    LOGI("TEST: blit error=0x%x", err);

    glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);

    if (err != GL_NO_ERROR) {
        LOGE("TEST: blit failed, skip encoder draw");
        return;
    }

    // 2. 保存当前游戏 Surface
    gameSurface_ = eglGetCurrentSurface(EGL_DRAW);

    if (gameSurface_ == EGL_NO_SURFACE) {
        LOGE("TEST: gameSurface_ is EGL_NO_SURFACE");
        return;
    }

    // 3. 切换到编码 Surface
    EGLBoolean ok = eglMakeCurrent(
            eglDisplay_,
            encodeSurface_,
            encodeSurface_,
            eglContext_
    );

    if (ok != EGL_TRUE) {
        LOGE("TEST: switch to encode surface failed, EGL error=0x%x",
             eglGetError());
        return;
    }

    LOGI("TEST: switched to encode surface");

    // 4. 在编码 Surface 上绘制 texColor_
    glViewport(0, 0, encW_, encH_);
    glUseProgram(shaderProgram_);

    glBindBuffer(GL_ARRAY_BUFFER, vbo_);

    glEnableVertexAttribArray(0);
    glVertexAttribPointer(
            0, 2, GL_FLOAT, GL_FALSE,
            4 * sizeof(float), (void *) 0
    );

    glEnableVertexAttribArray(1);
    glVertexAttribPointer(
            1, 2, GL_FLOAT, GL_FALSE,
            4 * sizeof(float), (void *) (2 * sizeof(float))
    );

    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, texColor_);
    glUniform1i(glGetUniformLocation(shaderProgram_, "uTex"), 0);

    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

    err = glGetError();
    LOGI("TEST: encoder draw error=0x%x", err);

    // 5. 提交编码帧
    int64_t ts = nowNs();
    if (eglPresentationTime_ != nullptr) {
        eglPresentationTime_(eglDisplay_, encodeSurface_, ts);
    }

    ok = eglSwapBuffers(eglDisplay_, encodeSurface_);
    LOGI("TEST: eglSwapBuffers=%d EGL error=0x%x",
         ok, ok == EGL_TRUE ? EGL_SUCCESS : eglGetError());

    // 6. 切回游戏 Surface
    ok = eglMakeCurrent(
            eglDisplay_,
            gameSurface_,
            gameSurface_,
            eglContext_
    );

    LOGI("TEST: restore game surface=%d EGL error=0x%x",
         ok, ok == EGL_TRUE ? EGL_SUCCESS : eglGetError());

    if (ok != EGL_TRUE) {
        capturing_ = false;
        return;
    }

    LOGI("TEST: frame=%d end", logCount);
}


void GameFrameCapture::release() {
    stopCapture();

    if (eglDisplay_ != EGL_NO_DISPLAY) {
        // 确保切回游戏 Surface
        if (gameSurface_ != EGL_NO_SURFACE) {
            eglMakeCurrent(eglDisplay_, gameSurface_, gameSurface_, eglContext_);
        }
        if (encodeSurface_ != EGL_NO_SURFACE) {
            eglDestroySurface(eglDisplay_, encodeSurface_);
            encodeSurface_ = EGL_NO_SURFACE;
        }
    }

    if (texFBO_) {
        glDeleteFramebuffers(1, &texFBO_);
        texFBO_ = 0;
    }
    if (texColor_) {
        glDeleteTextures(1, &texColor_);
        texColor_ = 0;
    }
    if (shaderProgram_) {
        glDeleteProgram(shaderProgram_);
        shaderProgram_ = 0;
    }
    if (vbo_) {
        glDeleteBuffers(1, &vbo_);
        vbo_ = 0;
    }

    LOGI("release() 完成");
}

#endif // CC_PLATFORM_ANDROID
