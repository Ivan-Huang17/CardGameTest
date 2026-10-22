package org.cocos2dx.cpp.highlight;

import android.media.MediaFormat;
import android.net.Uri;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
/**
 * 高光录制总控（阶段三版本）：编码器产物进入 {@link PacketRingBuffer}，持续保留最近 N 秒。
 *
 * <h3>阶段三目标</h3>
 * 验证环形缓冲的淘汰策略和内存占用是否稳定。此阶段不做片段截取（阶段四加入），
 * 只持续编码并丢进缓冲区，通过 {@link #logBufferStats()} 定时观察 GOP 数和内存占用。
 *
 * <h3>调用顺序</h3>
 * <pre>
 *   prepare()   → 创建编码器，返回 InputSurface 给 C++ 侧做 EGL 初始化
 *   start()     → 启动编码器，开始接收帧，样本持续写入 PacketRingBuffer
 *   stop()      → 停止编码，并把环形缓冲的全部内容封装成 MP4 保存到相册（验证用）
 * </pre>
 *
 * <h3>线程说明</h3>
 * prepare/start/stop 由 C++ 通过 JNI 在主线程或 GL 线程调用。
 * VideoEncoder 的排空线程（video-encode-drain）在内部自管理，直接写入环形缓冲。
 */
public final class HighlightRecorder {

    private static final String TAG = "HighlightRecorder";

    // 录制参数（与阶段一/二一致，竖屏 720p）
    private static final int WIDTH        = 720;
    private static final int HEIGHT       = 1280;
    private static final int FPS          = 30;
    private static final int BITRATE      = 4_000_000;  // 4 Mbps
    private static final int I_FRAME_SEC  = 1;

    // I 帧间隔 1 秒，保留 20 个 GOP ≈ 20 秒回看缓冲
    private static final int MAX_GOP_COUNT = 20;

    private final android.content.Context context;

    private VideoEncoder videoEncoder;
    private AudioEncoder audioEncoder;
    private RingBufferSink ringBufferSink;
    private PacketRingBuffer ringBuffer;

    // 供 C++ 通过 JNI 调用的单例访问
    private static HighlightRecorder instance;

    public HighlightRecorder(android.content.Context context) {
        this.context = context.getApplicationContext();
    }

    // ------------------------------------------------------------------
    // JNI 入口（由 C++ HighlightService 调用）
    // ------------------------------------------------------------------

    /** C++ 调用：获取单例，供 nativePrepare 等方法使用。 */
    public static synchronized HighlightRecorder getInstance(android.content.Context ctx) {
        if (instance == null) {
            instance = new HighlightRecorder(ctx);
        }
        return instance;
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /**
     * 创建编码器，返回 InputSurface。
     * C++ 侧拿到这个 Surface 后用 ANativeWindow_fromSurface 转成 EGLSurface。
     *
     * @return VideoEncoder 的 InputSurface，C++ 用它初始化编码 EGLSurface
     */
    public Surface prepare() {
        // 选编码器
        EncoderSelector.VideoChoice choice;
        try {
            choice = EncoderSelector.selectVideoEncoder(
                    MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT);
        } catch (Exception e) {
            Log.e(TAG, "EncoderSelector 失败", e);
            return null;
        }
        Log.i(TAG, "选中编码器：" + choice);

        // 创建环形缓冲区和它的 Sink
        ringBuffer = new PacketRingBuffer(MAX_GOP_COUNT);
        ringBufferSink = new RingBufferSink(ringBuffer);

        // 创建 VideoEncoder，输出接到环形缓冲（不再直写 Mp4Muxer）
        videoEncoder = new VideoEncoder(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                choice.codecName,
                choice.width,
                choice.height,
                BITRATE,
                FPS,
                I_FRAME_SEC,
                -1,
                ringBufferSink);

        Surface inputSurface;
        try {
            inputSurface = videoEncoder.prepare();
        } catch (Exception e) {
            Log.e(TAG, "VideoEncoder.prepare 失败", e);
            return null;
        }

        Log.i(TAG, "prepare() 完成，InputSurface=" + inputSurface
                + " 分辨率=" + choice.width + "x" + choice.height
                + " maxGopCount=" + MAX_GOP_COUNT);

        // 创建 AudioEncoder，输出同样接到 ringBufferSink
        audioEncoder = new AudioEncoder(44100, 2, 128_000, ringBufferSink);
        try {
            audioEncoder.prepare();
            Log.i(TAG, "AudioEncoder 准备完成");
        } catch (Exception e) {
            Log.w(TAG, "AudioEncoder 初始化失败，将无音轨录制", e);
            audioEncoder = null;
        }

        return inputSurface;
    }

    /**
     * 启动编码器，开始接收 C++ 侧送来的帧。
     * 必须在 C++ 完成 EGL 初始化之后调用。
     */
    public void start() {
        if (videoEncoder == null) {
            Log.e(TAG, "start() 前必须先调用 prepare()");
            return;
        }
        // 环形缓冲不需要 PTS 归零（没有 Mp4Muxer 的 mvhd 起始时间问题），
        // 直接使用编码器原始的 System.nanoTime() 时基，snapshot 时按相对时间计算。
        videoEncoder.start();
        if (audioEncoder != null) {
            audioEncoder.start();
        }
        Log.i(TAG, "录制已开始，样本将持续写入环形缓冲区");
    }

    /**
     * 停止录制：停止视频/音频编码器，再把环形缓冲当前的全部内容
     * 封装成一个 MP4 保存到相册（{@link #dumpBufferToMp4()}）。
     * 按触发时刻截取片段的逻辑在阶段四实现。
     */
    public void stop() {
        if (videoEncoder == null) {
            return;
        }
        Log.i(TAG, "stop() 开始...");

        videoEncoder.signalEndOfInputStream();
        boolean eosOk = videoEncoder.awaitEndOfStream(2_000);
        Log.i(TAG, "awaitEndOfStream=" + eosOk);

        videoEncoder.stop();
        videoEncoder = null;
        Log.i(TAG, "stop(): 视频编码器已停止");

        // 音频编码器停止出异常不能影响后面的落盘
        if (audioEncoder != null) {
            try {
                audioEncoder.stop();
                Log.i(TAG, "stop(): 音频编码器已停止");
            } catch (Throwable t) {
                Log.e(TAG, "stop(): 音频编码器停止异常，继续落盘", t);
            }
            audioEncoder = null;
        }

        logBufferStats();

        // 把环形缓冲当前全量内容转储成 MP4 落盘，用于验证视频+音频是否正常
        try {
            dumpBufferToMp4();
        } catch (Throwable t) {
            Log.e(TAG, "dumpBufferToMp4 异常", t);
        }
    }

    /**
     * 把环形缓冲里的所有内容封装成一个 MP4 落盘到相册。
     * 用于阶段三/四验证：确认视频帧和音频帧都正确录入、时间戳正常。
     */
    private void dumpBufferToMp4() {
        if (ringBuffer == null || ringBuffer.gopCount() == 0) {
            Log.w(TAG, "dumpBufferToMp4: 环形缓冲为空，跳过");
            return;
        }

        // 取全量时间范围的 snapshot
        long startUs = ringBuffer.earliestVideoPts();
        long endUs   = ringBuffer.latestVideoPts();
        HighlightClip clip = ringBuffer.snapshot(startUs, endUs);

        if (clip.isEmpty()) {
            Log.w(TAG, "dumpBufferToMp4: snapshot 为空");
            return;
        }

        MediaFormat videoFmt = ringBufferSink.getVideoFormat();
        MediaFormat audioFmt = ringBufferSink.getAudioFormat();
        Log.i(TAG, "dumpBufferToMp4: clip video=" + clip.videoPackets.size()
                + " audio=" + clip.audioPackets.size()
                + " videoFmt=" + (videoFmt != null) + " audioFmt=" + (audioFmt != null));
        if (videoFmt == null) {
            Log.w(TAG, "dumpBufferToMp4: 没有视频格式，跳过");
            return;
        }

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File tempFile = MediaStoreSaver.newTempFile(context, "HL_dump_" + timestamp);

        boolean hasAudio = (audioFmt != null && !clip.audioPackets.isEmpty());
        Mp4Muxer muxer;
        try {
            muxer = new Mp4Muxer(tempFile.getAbsolutePath(), hasAudio, 0);
        } catch (Exception e) {
            Log.e(TAG, "dumpBufferToMp4: Mp4Muxer 创建失败", e);
            return;
        }

        // PTS 归零：以最早视频帧为基准
        long basePtsUs = clip.actualStartUs;
        muxer.setTimeBase(basePtsUs);

        // 通知 muxer 轨道格式
        muxer.onTrackFormat(Track.VIDEO, videoFmt);
        if (hasAudio) {
            muxer.onTrackFormat(Track.AUDIO, audioFmt);
        }

        // 写入视频包
        android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
        for (EncodedPacket pkt : clip.videoPackets) {
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(pkt.data);
            int flags = pkt.isKeyFrame ? android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
            info.set(0, pkt.data.length, pkt.ptsUs, flags);
            muxer.onSample(Track.VIDEO, buf, info);
        }

        // 写入音频包
        if (hasAudio) {
            for (EncodedPacket pkt : clip.audioPackets) {
                java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(pkt.data);
                info.set(0, pkt.data.length, pkt.ptsUs, 0);
                muxer.onSample(Track.AUDIO, buf, info);
            }
        }

        Mp4Muxer.Stats stats = muxer.stop();
        Log.i(TAG, "dumpBufferToMp4: 封装完成 " + stats);

        if (tempFile.length() <= 0 || stats.videoFrames <= 0) {
            Log.e(TAG, "dumpBufferToMp4: 输出文件为空");
            MediaStoreSaver.deleteQuietly(tempFile);
            return;
        }

        String displayName = tempFile.getName();
        Uri uri = MediaStoreSaver.publish(context, tempFile, displayName);
        if (uri != null) {
            Log.i(TAG, "=== dump 完成，已保存到相册：" + uri
                    + " video=" + stats.videoFrames + "帧"
                    + " audio=" + stats.audioFrames + "包"
                    + " 时长≈" + stats.durationMs() / 1000 + "s ===");
            MediaStoreSaver.deleteQuietly(tempFile);
        } else {
            Log.w(TAG, "dumpBufferToMp4: 落盘失败，临时文件：" + tempFile.getAbsolutePath());
        }
    }

    /**
     * 打印当前环形缓冲区状态，用于阶段三验证淘汰策略和内存占用是否稳定。
     * 建议在录制期间用定时器每秒调用一次（参考 §4.3 验证项）。
     */
    public void logBufferStats() {
        if (ringBuffer == null) {
            return;
        }
        long earliest = ringBuffer.earliestVideoPts();
        long latest = ringBuffer.latestVideoPts();
        int gopCount = ringBuffer.gopCount();
        long totalBytes = ringBuffer.totalBytes();
        double spanSec = (earliest >= 0 && latest >= 0)
                ? (latest - earliest) / 1_000_000.0
                : 0;

        Log.i(TAG, String.format(Locale.US,
                "缓冲区状态: GOP数=%d 跨度=%.1fs 总字节=%d(%.1fKB) earliest=%d latest=%d",
                gopCount, spanSec, totalBytes, totalBytes / 1024.0, earliest, latest));
    }

    /** 供阶段四使用：获取环形缓冲区实例（用于截取高光片段）。 */
    public PacketRingBuffer getRingBuffer() {
        return ringBuffer;
    }

    /** 供阶段四使用：获取保存的视频/音频格式（封装 MP4 时重建轨道需要）。 */
    public RingBufferSink getRingBufferSink() {
        return ringBufferSink;
    }

    /** 获取编码器接受的实际宽度（EncoderSelector 可能做了对齐调整）。 */
    public int getEncoderWidth() {
        return videoEncoder != null ? videoEncoder.getWidth() : WIDTH;
    }

    /** 获取编码器接受的实际高度。 */
    public int getEncoderHeight() {
        return videoEncoder != null ? videoEncoder.getHeight() : HEIGHT;
    }

    /**
     * C++ 侧攒批后通过 JNI 调用此方法，把一批 PCM 数据喂给 AudioEncoder。
     * 运行在 C++ 的 OpenSL ES 回调线程，AudioEncoder 内部队列接收后异步编码。
     *
     * @param pcm        PCM 数据（S16 interleaved）
     * @param sampleRate 采样率（Hz）
     * @param channels   声道数
     * @param ptsNs      本批第一个采样点的 CLOCK_MONOTONIC 时间戳（纳秒）
     */
    public static void onAudioBatch(byte[] pcm, int sampleRate, int channels, long ptsNs) {
        HighlightRecorder rec = instance;
        if (rec == null || rec.audioEncoder == null) return;
        rec.audioEncoder.feedPcm(pcm, ptsNs);
    }
}
