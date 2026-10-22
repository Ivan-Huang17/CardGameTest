package org.cocos2dx.cpp.highlight;

import java.util.ArrayList;
import java.util.List;

/**
 * 从 {@link PacketRingBuffer} 截取出的一段高光片段。
 *
 * <p>{@code startUs} 是请求的起点，{@code actualStartUs} 是向前对齐到 GOP 边界（IDR 帧）
 * 之后的真实起点——截取窗口必须以关键帧开头，否则这段视频解码器无法独立播放。
 */
public final class HighlightClip {

    public final long requestedStartUs;
    public final long actualStartUs;
    public final long endUs;
    public final List<EncodedPacket> videoPackets = new ArrayList<>();
    public final List<EncodedPacket> audioPackets = new ArrayList<>();

    public HighlightClip(long requestedStartUs, long actualStartUs, long endUs) {
        this.requestedStartUs = requestedStartUs;
        this.actualStartUs = actualStartUs;
        this.endUs = endUs;
    }

    public boolean isEmpty() {
        return videoPackets.isEmpty();
    }
}
