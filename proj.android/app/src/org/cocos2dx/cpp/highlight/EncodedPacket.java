package org.cocos2dx.cpp.highlight;

/**
 * 编码后的一个样本（视频一帧 / 一段 AAC）的不可变快照。
 *
 * <p>{@link VideoEncoder} / {@link AudioEncoder} 产出的 {@code ByteBuffer} 只在编码线程回调期间
 * 有效，一旦 {@code releaseOutputBuffer} 被调用就可能失效，所以进入 {@link PacketRingBuffer} 之前
 * 必须深拷贝成独立内存——这正是本类存在的意义，对应参考资料 highlight-recorder 项目里
 * {@code EncodedPacket} 的设计思路（同样是“编码后、不依赖编码器生命周期”的样本快照）。
 */
public final class EncodedPacket {

    public final Track track;
    public final byte[] data;
    public final long ptsUs;
    public final boolean isKeyFrame;

    public EncodedPacket(Track track, byte[] data, long ptsUs, boolean isKeyFrame) {
        this.track = track;
        this.data = data;
        this.ptsUs = ptsUs;
        this.isKeyFrame = isKeyFrame;
    }
}
