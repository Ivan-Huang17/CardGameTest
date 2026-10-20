//
// Highlight/Phase2Verifier.cpp
//

#include "Phase2Verifier.hpp"
#include "cocos2d.h"

// 仅在 Android 上做真正的 GL/EGL 查询
#if (CC_TARGET_PLATFORM == CC_PLATFORM_ANDROID)

#include <GLES2/gl2.h>
#include <EGL/egl.h>
#include <android/log.h>
#include <cstring>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  "Phase2", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  "Phase2", __VA_ARGS__)

// EGL_RECORDABLE_ANDROID 不是所有 NDK 版本的 eglext.h 都定义了
#ifndef EGL_RECORDABLE_ANDROID
#define EGL_RECORDABLE_ANDROID 0x3142
#endif

void Phase2Verifier::run() {
    auto director   = cocos2d::Director::getInstance();
    auto dispatcher = director->getEventDispatcher();

    LOGI("Phase2Verifier::run() called, registering EVENT_AFTER_DRAW listener");
    cocos2d::log("[Phase2] run() called, dispatcher enabled=%d",
                 dispatcher->isEnabled() ? 1 : 0);

    dispatcher->addCustomEventListener(
        cocos2d::Director::EVENT_AFTER_DRAW,
        [dispatcher](cocos2d::EventCustom*) {

            dispatcher->removeCustomEventListeners(cocos2d::Director::EVENT_AFTER_DRAW);

            cocos2d::log("[Phase2] lambda fired!");
            LOGI("========== 阶段二技术验证 开始 ==========");

            // ── 1. GLES 版本 ─────────────────────────────────────────
            const char* glVer  = (const char*)glGetString(GL_VERSION);
            const char* glRend = (const char*)glGetString(GL_RENDERER);
            LOGI("[1] GL_VERSION  : %s", glVer  ? glVer  : "(null)");
            LOGI("[1] GL_RENDERER : %s", glRend ? glRend : "(null)");

            bool isGles3 = glVer && strstr(glVer, "OpenGL ES 3") != nullptr;
            LOGI("[1] GLES3 : %s", isGles3
                ? "YES → 后续可用 glBlitFramebuffer 直接缩放拷屏"
                : "NO  → 只能用 glCopyTexSubImage2D 拷屏");

            // ── 2. 当前帧缓冲绑定 ────────────────────────────────────
            GLint fbo = -1;
            glGetIntegerv(GL_FRAMEBUFFER_BINDING, &fbo);
            LOGI("[2] GL_FRAMEBUFFER_BINDING : %d  (%s)", fbo,
                fbo == 0 ? "默认帧缓冲（屏幕），符合预期" : "自定义FBO，需要额外处理");

            // ── 3. glCopyTexSubImage2D 可用性 ────────────────────────
            GLuint tex = 0;
            glGenTextures(1, &tex);
            glBindTexture(GL_TEXTURE_2D, tex);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, 4, 4, 0,
                         GL_RGB, GL_UNSIGNED_BYTE, nullptr);
            glGetError();
            glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 0, 0, 4, 4);
            GLenum copyErr = glGetError();
            glBindTexture(GL_TEXTURE_2D, 0);
            glDeleteTextures(1, &tex);
            LOGI("[3] glCopyTexSubImage2D : %s",
                copyErr == GL_NO_ERROR ? "OK" : "FAILED（GL error）");

            // ── 4. EGL 配置查询 ──────────────────────────────────────
            EGLDisplay dpy = eglGetCurrentDisplay();
            EGLContext ctx = eglGetCurrentContext();

            if (dpy == EGL_NO_DISPLAY || ctx == EGL_NO_CONTEXT) {
                LOGW("[4] EGL_NO_DISPLAY/CONTEXT，无法查询 EGL 属性");
            } else {
                EGLint configId = 0;
                eglQueryContext(dpy, ctx, EGL_CONFIG_ID, &configId);
                LOGI("[4] EGL_CONFIG_ID : %d", configId);

                EGLint selAttribs[] = { EGL_CONFIG_ID, configId, EGL_NONE };
                EGLConfig config = nullptr;
                EGLint numCfg = 0;
                eglChooseConfig(dpy, selAttribs, &config, 1, &numCfg);

                if (numCfg > 0 && config != nullptr) {
                    EGLint r = 0, g = 0, b = 0, a = 0, depth = 0;
                    eglGetConfigAttrib(dpy, config, EGL_RED_SIZE,   &r);
                    eglGetConfigAttrib(dpy, config, EGL_GREEN_SIZE, &g);
                    eglGetConfigAttrib(dpy, config, EGL_BLUE_SIZE,  &b);
                    eglGetConfigAttrib(dpy, config, EGL_ALPHA_SIZE, &a);
                    eglGetConfigAttrib(dpy, config, EGL_DEPTH_SIZE, &depth);
                    LOGI("[4] EGL 颜色格式 : R%d G%d B%d A%d depth=%d", r, g, b, a, depth);

                    EGLint recordable = 0;
                    EGLBoolean ok = eglGetConfigAttrib(
                        dpy, config, EGL_RECORDABLE_ANDROID, &recordable);
                    if (ok == EGL_TRUE) {
                        LOGI("[4] EGL_RECORDABLE_ANDROID : %d  →  %s", recordable,
                            recordable
                              ? "方案A可行（同EGLContext挂编码Surface，最简单）"
                              : "需要方案B（新建独立EGLContext，共享纹理）");
                    } else {
                        EGLint eglErr = eglGetError();
                        LOGW("[4] EGL_RECORDABLE_ANDROID 查询失败 eglError=0x%x → 默认走方案B",
                             eglErr);
                    }
                } else {
                    LOGW("[4] eglChooseConfig 未找到 config id=%d", configId);
                }
            }

            LOGI("========== 阶段二技术验证 结束 ==========");
        });

    cocos2d::log("[Phase2] listener registered via addCustomEventListener");
}

#else  // 非 Android 平台：空操作

void Phase2Verifier::run() {}

#endif
