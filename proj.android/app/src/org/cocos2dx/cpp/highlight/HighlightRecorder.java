package org.cocos2dx.cpp.highlight;

import android.media.MediaFormat;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
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
 *   stop()      → 停止编码，并把已暂存的高光片段（storedClips）合并封装成一个 MP4 保存到相册
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

    // 高光截取窗口：触发时刻前后各 1 秒，共 2 秒
    private static final long PRE_ROLL_US  = 1_000_000L;
    private static final long POST_ROLL_US = 1_000_000L;
    // 等触发后 1 秒的数据入库后再截取。额外留 200ms 余量：
    // 音频批次最多攒 ~100ms 才送到 Java，加上 AAC 编码延迟，否则窗口末尾的音频可能还没进缓冲
    private static final long POST_ROLL_WAIT_MS = POST_ROLL_US / 1000 + 200;
    // 暂存片段数量上限。每个片段约 1MB（4Mbps × ~2s），且持有的 packet 不会随环形缓冲淘汰而释放，
    // 不设上限的话频繁点击会持续占用内存
    private static final int MAX_STORED_CLIPS = 20;

    private VideoEncoder videoEncoder;
    private AudioEncoder audioEncoder;
    private RingBufferSink ringBufferSink;
    private PacketRingBuffer ringBuffer;

    // 触发后延迟截取用的后台线程，不能占用游戏线程和 JNI 调用线程
    private volatile HandlerThread triggerThread;
    private volatile Handler triggerHandler;
    // 已截取并暂存的高光片段（最旧的在前）
    private final List<HighlightClip> storedClips = new ArrayList<>();

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

        // 启动高光截取线程（重复 prepare 时复用）
        if (triggerThread == null) {
            HandlerThread t = new HandlerThread("hl-trigger");
            t.start();
            triggerThread = t;
            triggerHandler = new Handler(t.getLooper());
        }

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
     * 停止录制：停止视频/音频编码器，再把已暂存的高光片段（storedClips）
     * 合并封装成一个 MP4 保存到相册（{@link #dumpStoredClipsToMp4()}）。
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

        // 把已暂存的高光片段（storedClips）合并封装成一个 MP4 落盘
        try {
            dumpStoredClipsToMp4();
        } catch (Throwable t) {
            Log.e(TAG, "dumpStoredClipsToMp4 异常", t);
        }

        // 停止高光截取线程，未执行的延迟截取随之取消
        HandlerThread captureThread = triggerThread;
        if (captureThread != null) {
            captureThread.quitSafely();
            triggerThread = null;
            triggerHandler = null;
        }
    }

    /**
     * 把已暂存的高光片段（{@link #storedClips}）按触发先后顺序合并写入同一个 MP4，保存到相册。
     * 两次点击间隔很短时片段内容会重叠，重叠部分按原样重复保留，不做去重。
     */
    private void dumpStoredClipsToMp4() {
        List<HighlightClip> clips = getStoredClips();
        if (clips.isEmpty()) {
            Log.w(TAG, "dumpStoredClipsToMp4: 没有暂存的高光片段，跳过");
            return;
        }

        MediaFormat videoFmt = ringBufferSink.getVideoFormat();
        MediaFormat audioFmt = ringBufferSink.getAudioFormat();
        Log.i(TAG, "dumpStoredClipsToMp4: 共 " + clips.size() + " 个片段"
                + " videoFmt=" + (videoFmt != null) + " audioFmt=" + (audioFmt != null));
        if (videoFmt == null) {
            Log.w(TAG, "dumpStoredClipsToMp4: 没有视频格式，跳过");
            return;
        }

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        writeClipsToMp4(clips, videoFmt, audioFmt, "HL_highlights_" + timestamp);
    }

    /** 片段之间的时间间隔（约 1 帧），保证相邻片段衔接处同一条轨道的时间戳严格递增。 */
    private static final long CLIP_GAP_US = 33_333L;

    private static void writeSample(Mp4Muxer muxer, android.media.MediaCodec.BufferInfo info,
                                    Track track, EncodedPacket pkt, long ptsUs) {
        int flags = (track == Track.VIDEO && pkt.isKeyFrame)
                ? android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
        info.set(0, pkt.data.length, ptsUs, flags);
        muxer.onSample(track, java.nio.ByteBuffer.wrap(pkt.data), info);
    }

    /**
     * 把多个片段依次写入同一个 MP4 并发布到相册。
     *
     * <p>每个片段的时间戳以自身视频起点（已对齐到关键帧）归零，再整体平移到上一个片段之后，
     * 音频和视频使用同一个平移量，片段内部的音画关系保持不变。
     * 每个片段都以关键帧开头，首尾相接后仍可正常解码。
     */
    private void writeClipsToMp4(List<HighlightClip> clips, MediaFormat videoFmt,
                                 MediaFormat audioFmt, String fileName) {
        boolean hasAudio = false;
        if (audioFmt != null) {
            for (HighlightClip clip : clips) {
                if (!clip.audioPackets.isEmpty()) {
                    hasAudio = true;
                    break;
                }
            }
        }

        File tempFile = MediaStoreSaver.newTempFile(context, fileName);
        Mp4Muxer muxer;
        try {
            muxer = new Mp4Muxer(tempFile.getAbsolutePath(), hasAudio, 0);
        } catch (Exception e) {
            Log.e(TAG, "writeClipsToMp4: Mp4Muxer 创建失败 " + fileName, e);
            return;
        }

        // 不调用 setTimeBase：时间戳已经在下面自己平移好，Mp4Muxer 的归零基准保持为 0
        muxer.onTrackFormat(Track.VIDEO, videoFmt);
        if (hasAudio) {
            muxer.onTrackFormat(Track.AUDIO, audioFmt);
        }

        android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
        long offsetUs = 0;
        int usedClips = 0;
        for (HighlightClip clip : clips) {
            if (clip.isEmpty()) continue;

            // 片段内时间戳 = 原时间戳 - 片段视频起点，再加上本片段在合并文件里的起始偏移
            long startUs = clip.actualStartUs;
            long lastUs = 0;
            for (EncodedPacket pkt : clip.videoPackets) {
                writeSample(muxer, info, Track.VIDEO, pkt, offsetUs + pkt.ptsUs - startUs);
                lastUs = Math.max(lastUs, pkt.ptsUs - startUs);
            }
            if (hasAudio) {
                for (EncodedPacket pkt : clip.audioPackets) {
                    writeSample(muxer, info, Track.AUDIO, pkt, offsetUs + pkt.ptsUs - startUs);
                    lastUs = Math.max(lastUs, pkt.ptsUs - startUs);
                }
            }

            offsetUs += lastUs + CLIP_GAP_US;
            usedClips++;
        }

        Mp4Muxer.Stats stats = muxer.stop();
        Log.i(TAG, "writeClipsToMp4: 封装完成 " + fileName + " 片段=" + usedClips + " " + stats);

        if (tempFile.length() <= 0 || stats.videoFrames <= 0) {
            Log.e(TAG, "writeClipsToMp4: 输出文件为空 " + fileName);
            MediaStoreSaver.deleteQuietly(tempFile);
            return;
        }

        Uri uri = MediaStoreSaver.publish(context, tempFile, tempFile.getName());
        if (uri != null) {
            Log.i(TAG, "=== 高光视频已保存到相册：" + uri
                    + " 片段=" + usedClips
                    + " video=" + stats.videoFrames + "帧"
                    + " audio=" + stats.audioFrames + "包"
                    + " 时长≈" + stats.durationMs() + "ms ===");
            MediaStoreSaver.deleteQuietly(tempFile);
        } else {
            Log.w(TAG, "writeClipsToMp4: 落盘失败，临时文件：" + tempFile.getAbsolutePath());
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

    // ------------------------------------------------------------------
    // 高光触发（C++ HighlightService::triggerHighlight 经 JNI 调用）
    // ------------------------------------------------------------------

    /**
     * C++ 在游戏点击时调用。立即返回，不阻塞游戏线程：
     * 只记录触发时刻并安排一次延迟截取。
     *
     * @param eventName 事件名，仅用于日志
     * @param triggerUs 触发时刻，CLOCK_MONOTONIC 微秒，与视频/音频 packet 的 PTS 同一时基
     */
    public static void onTriggerHighlight(String eventName, long triggerUs) {
        HighlightRecorder rec = instance;
        if (rec == null) return;
        rec.scheduleCapture(eventName, triggerUs);
    }

    private void scheduleCapture(final String eventName, final long triggerUs) {
        Handler h = triggerHandler;
        if (h == null || ringBuffer == null) {
            Log.w(TAG, "triggerHighlight 被忽略：录制尚未准备好 event=" + eventName);
            return;
        }
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                captureClip(eventName, triggerUs);
            }
        }, POST_ROLL_WAIT_MS);
    }

    /**
     * 从环形缓冲取出 [触发前 1 秒, 触发后 1 秒] 的 packet 并暂存。
     * 视频起点会向前对齐到 GOP 的关键帧，所以视频可能比 2 秒多出至多 1 个 GOP，
     * 这是保证片段可独立解码所必需的；音频则严格按窗口截取。
     */
    private void captureClip(String eventName, long triggerUs) {
        PacketRingBuffer rb = ringBuffer;
        if (rb == null) return;

        HighlightClip clip = rb.snapshot(triggerUs - PRE_ROLL_US, triggerUs + POST_ROLL_US);
        if (clip.isEmpty()) {
            Log.w(TAG, "高光片段为空，丢弃 event=" + eventName);
            return;
        }

        int stored;
        synchronized (storedClips) {
            storedClips.add(clip);
            while (storedClips.size() > MAX_STORED_CLIPS) {
                storedClips.remove(0);
            }
            stored = storedClips.size();
        }
        Log.i(TAG, "高光片段已暂存 event=" + eventName
                + " video=" + clip.videoPackets.size()
                + " audio=" + clip.audioPackets.size()
                + " 窗口=[" + (triggerUs - PRE_ROLL_US) + "," + (triggerUs + POST_ROLL_US) + "]us"
                + " 实际视频起点=" + clip.actualStartUs
                + " 当前暂存=" + stored);
    }

    /** 已暂存的高光片段（副本，最旧的在前）。 */
    public List<HighlightClip> getStoredClips() {
        synchronized (storedClips) {
            return new ArrayList<>(storedClips);
        }
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
