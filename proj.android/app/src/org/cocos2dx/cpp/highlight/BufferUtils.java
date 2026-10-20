package org.cocos2dx.cpp.highlight;

import android.media.MediaCodec;

import java.nio.ByteBuffer;

/** {@link MediaCodec.BufferInfo} 的常用判断与缓冲操作。 */
public final class BufferUtils {

    private BufferUtils() {
    }

    /**
     * 是否为编解码器配置数据（H.264 的 SPS/PPS、AAC 的 AudioSpecificConfig）。
     *
     * <p>这类数据已经通过 {@code MediaFormat} 的 csd-0/csd-1 交给封装器了，
     * 如果当成普通帧再写一次，MP4 里会出现非法 NALU，部分播放器直接黑屏。
     */
    public static boolean isCodecConfig(MediaCodec.BufferInfo info) {
        return (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
    }

    public static boolean isEndOfStream(MediaCodec.BufferInfo info) {
        return (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
    }

    public static boolean isKeyFrame(MediaCodec.BufferInfo info) {
        return (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
    }

    /**
     * 把 buffer 中 {@code info.offset} 起、长度 {@code info.size} 的数据复制成独立缓冲区。
     *
     * <p>使用场景：{@code MediaMuxer} 还没有 {@code start()}（音轨格式未就绪），
     * 此时样本必须先攒在内存里，绝不能持有编码器缓冲区的引用——一旦
     * {@code releaseOutputBuffer} 被调用，那块内存随时会被覆盖。
     *
     * <p>返回值已 flip，position=0、limit=剩余长度，可直接交给 MediaMuxer。
     */
    public static ByteBuffer deepCopy(ByteBuffer src, MediaCodec.BufferInfo info) {
        int start = Math.max(0, info.offset);
        int end = Math.min(src.capacity(), info.offset + info.size);
        int length = Math.max(0, end - start);

        ByteBuffer copy = ByteBuffer.allocateDirect(length);
        ByteBuffer view = src.duplicate();
        view.position(start);
        view.limit(end);
        copy.put(view);
        copy.flip();
        return copy;
    }
}
