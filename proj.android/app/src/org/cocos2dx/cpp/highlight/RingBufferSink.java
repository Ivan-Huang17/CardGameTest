package org.cocos2dx.cpp.highlight;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;

import java.nio.ByteBuffer;

/**
 * {@link SampleSink} 实现：把编码器输出的样本深拷贝后存入 {@link PacketRingBuffer}。
 *
 * <p>与阶段二的直写模式（{@link Mp4Muxer}）不同，这里不立即封装 MP4，
 * 只是把编码后的包积累在环形缓冲区里，等 {@link HighlightRecorder#triggerHighlight}
 * 触发时再从缓冲区截取一段，交给 {@link HighlightMp4Writer} 封装。
 *
 * <h3>MediaFormat 的保存</h3>
 * {@code onTrackFormat} 收到的 {@link MediaFormat} 里带有 csd-0/csd-1（SPS/PPS），
 * 封装 MP4 时必须用它重建轨道，因此在这里保存一份供阶段四读取。
 */
public final class RingBufferSink implements SampleSink {

    private static final String TAG = "RingBufferSink";

    private final PacketRingBuffer ringBuffer;

    private volatile MediaFormat videoFormat;
    private volatile MediaFormat audioFormat;

    public RingBufferSink(PacketRingBuffer ringBuffer) {
        this.ringBuffer = ringBuffer;
    }

    public MediaFormat getVideoFormat() {
        return videoFormat;
    }

    public MediaFormat getAudioFormat() {
        return audioFormat;
    }

    @Override
    public void onTrackFormat(Track track, MediaFormat format) {
        if (track == Track.VIDEO) {
            videoFormat = format;
            Log.i(TAG, "视频格式就绪: " + format);
        } else {
            audioFormat = format;
            Log.i(TAG, "音频格式就绪: " + format);
        }
    }

    @Override
    public void onSample(Track track, ByteBuffer buffer, MediaCodec.BufferInfo info) {
        if (BufferUtils.isCodecConfig(info) || info.size <= 0) {
            // csd-0/csd-1 已经随 onTrackFormat 保存，不需要再存一份样本
            return;
        }

        byte[] data = new byte[info.size];
        ByteBuffer view = buffer.duplicate();
        view.position(info.offset);
        view.limit(info.offset + info.size);
        view.get(data);

        boolean isKeyFrame = BufferUtils.isKeyFrame(info) || track == Track.AUDIO;
        EncodedPacket pkt = new EncodedPacket(track, data, info.presentationTimeUs, isKeyFrame);

        if (track == Track.VIDEO) {
            ringBuffer.pushVideo(pkt);
        } else {
            ringBuffer.pushAudio(pkt);
        }
    }

    @Override
    public void onEncoderError(Track track, Throwable error) {
        Log.e(TAG, "编码器错误 " + track, error);
    }
}
