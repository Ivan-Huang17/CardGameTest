#pragma once

#include <string>

/**
 * HighlightService：C++ 侧对外的唯一接口。
 *
 * 调用顺序：
 *   AppActivity.onCreate 里调用 Java HighlightRecorder.prepare()
 *   → C++ HighlightService::onSurfaceReady(surface) 接收 InputSurface
 *   → HighlightService::start() 启动录制
 *   → HighlightService::triggerHighlight("event") 触发片段截取（阶段四实现）
 *   → HighlightService::stop() 停止录制
 *
 * 音频路径（引擎补丁点 PcmAudioService::enqueue 调用）：
 *   HighlightService::onAudioFrame(buf, size) 每次 mixOneFrame 后调用，
 *   内部攒批约 100ms 后通过一次 JNI 调用传给 Java 侧 AudioEncoder。
 */
class HighlightService {
public:
    static void onSurfaceReady(void* env, void* surface, int encW, int encH);
    static void start();
    static void triggerHighlight(const std::string& eventName = "");
    static void stop();

    /**
     * 引擎音频补丁点：每次 mixOneFrame 产出一帧 PCM 后调用。
     * 内部攒批，攒够约 100ms 后一次性通过 JNI 传给 Java。
     *
     * @param buf        混音后的 PCM 数据（S16 interleaved）
     * @param size       字节数
     * @param sampleRate 采样率（Hz），首次调用时记录，后续不变
     * @param channels   声道数
     */
    static void onAudioFrame(const void* buf, size_t size,
                             int sampleRate, int channels);
};
