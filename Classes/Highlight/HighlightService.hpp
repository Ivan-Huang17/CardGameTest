#pragma once

#include <string>

/**
 * HighlightService：C++ 侧对外的唯一接口。
 *
 * 游戏逻辑层只与这个类交互，不感知 JNI、EGL、编码器的存在。
 *
 * 调用顺序：
 *   AppActivity.onCreate 里调用 Java HighlightRecorder.prepare()
 *   → C++ HighlightService::onSurfaceReady(surface) 接收 InputSurface
 *   → HighlightService::start() 启动录制
 *   → HighlightService::triggerHighlight("event") 触发片段截取（阶段三实现）
 *   → HighlightService::stop() 停止录制
 */
class HighlightService {
public:
    /**
     * Java 传来 InputSurface 后调用。
     * 内部初始化 EGLSurface 和 GameFrameCapture，挂 EVENT_AFTER_DRAW 钩子。
     * @param env      JNIEnv*
     * @param surface  Java android.view.Surface 对象（VideoEncoder.getInputSurface()）
     * @param encW     编码器实际宽度
     * @param encH     编码器实际高度
     */
    static void onSurfaceReady(void* env, void* surface, int encW, int encH);

    /**
     * 通知 Java 侧开始录制（调 HighlightRecorder.start()）。
     * 必须在 onSurfaceReady 之后调用。
     */
    static void start();

    /**
     * 触发高光截取（阶段三实现具体截取逻辑，此处预留）。
     */
    static void triggerHighlight(const std::string& eventName = "");

    /**
     * 停止录制：移除 EVENT_AFTER_DRAW 钩子，通知 Java 侧 stop()，封装 MP4。
     */
    static void stop();
};
