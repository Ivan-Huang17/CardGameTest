//
// Highlight/Phase2Verifier.hpp
//
// 阶段二技术验证：在第一帧渲染完成后，在 GL 线程上收集以下信息：
//
//   1. GLES 版本         → 决定能否用 glBlitFramebuffer（GLES3 才有）
//   2. 当前帧缓冲绑定    → 确认 Cocos 渲染到默认帧缓冲（FBO=0）
//   3. glCopyTexSubImage2D 可用性  → 最基础的拷屏方式能否用
//   4. EGL_RECORDABLE_ANDROID     → 方案A（同Context）还是方案B（独立Context）
//
// 调用方式：
//   在 GameController::init() 末尾调用 Phase2Verifier::run() 一次即可。
//   结果全部输出到 logcat，过滤 tag "Phase2"。
//
// 仅 Android 平台生效，其他平台编译为空操作。
//

#pragma once

class Phase2Verifier {
public:
    // 注册 EVENT_AFTER_DRAW 钩子，只在第一帧执行一次后自动注销
    static void run();
};
