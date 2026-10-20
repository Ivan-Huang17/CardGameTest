package org.cocos2dx.cpp.highlight;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * MP4 封装器：{@link MediaMuxer} 的安全封装。
 *
 * <h3>必须自己处理好的三件事</h3>
 *
 * <p><b>1) addTrack 必须先于 start()。</b>
 * 视频轨的格式几乎立刻返回（第一帧编码完就有），音频轨却可能晚几十毫秒才吐格式。
 * 如果视频线程一拿到格式就 {@code start()}，之后音轨就再也加不进去了
 * （{@code addTrack} 在 start 之后调用会抛 {@code IllegalStateException}）。
 * 本类的做法是：start() 之前到达的样本先深拷贝进 {@link #pending} 缓冲，
 * 等所有期望轨道都 addTrack 完成，按 PTS 排序一次性写入，随后切成直写模式——
 * 既不丢帧，也不会出现「只有画面没声音」。
 *
 * <p><b>2) 音轨可能永远不来。</b>
 * 设备不支持音频回采、游戏主动设置了 {@code allowAudioPlaybackCapture=false}、
 * 用户没有授予录音权限……都会让 AudioEncoder 连格式都出不来。
 * 只靠等待会导致视频样本一直堆在内存里直到 OOM。所以由上层调用
 * {@link #giveUpAudioTrack()} 把期望轨道数从 2 降到 1，降级为纯视频录制。
 *
 * <p><b>3) PTS 需要归零。</b>
 * 编码器给出的时间戳来自 {@code System.nanoTime()}（开机以来的单调时钟），
 * 典型值是数万亿微秒。直接写进 MP4 会让 mvhd 的起始时间异常巨大，
 * 部分播放器/剪辑软件会因此算错时长甚至拒绝打开。本类统一减去
 * {@link #setTimeBase(long)} 传入的录制起点，让文件从 0 开始。
 *
 * <h3>线程安全</h3>
 * 视频、音频两条编码线程会并发调用 {@link #onSample}，所有写入都在 {@link #lock}
 * 保护下完成；这同时保证了同一个轨道内的 PTS 单调递增（否则 MediaMuxer 会报错）。
 */
public final class Mp4Muxer implements SampleSink {

    private static final String TAG = "Mp4Muxer";

    /** 预写缓冲上限。超过说明音轨大概率不会来了，宁可丢起始若干帧也不能 OOM。 */
    private static final long MAX_PENDING_BYTES = 24L * 1024 * 1024;

    /** 录制统计结果，用于生成文件名、上报日志与「已保存」提示。 */
    public static final class Stats {
        public final long videoFrames;
        public final long audioFrames;
        public final long firstPtsUs;
        public final long lastPtsUs;
        public final long bytesWritten;
        public final int trackCount;
        public final String outputPath;

        Stats(long videoFrames, long audioFrames, long firstPtsUs, long lastPtsUs,
              long bytesWritten, int trackCount, String outputPath) {
            this.videoFrames = videoFrames;
            this.audioFrames = audioFrames;
            this.firstPtsUs = firstPtsUs;
            this.lastPtsUs = lastPtsUs;
            this.bytesWritten = bytesWritten;
            this.trackCount = trackCount;
            this.outputPath = outputPath;
        }

        /** 以首个样本到最后一个样本的间隔估算时长。 */
        public long durationMs() {
            return lastPtsUs > firstPtsUs ? (lastPtsUs - firstPtsUs) / 1000L : 0L;
        }

        @Override
        public String toString() {
            return "Stats{video=" + videoFrames + "帧, audio=" + audioFrames + "包, "
                    + "时长≈" + durationMs() + "ms, 写入=" + bytesWritten + "B}";
        }
    }

    private static final class Pending {
        final Track track;
        final ByteBuffer data;
        final long ptsUs;
        final int flags;
        final int size;

        Pending(Track track, ByteBuffer data, long ptsUs, int flags, int size) {
            this.track = track;
            this.data = data;
            this.ptsUs = ptsUs;
            this.flags = flags;
            this.size = size;
        }
    }

    private final Object lock = new Object();
    private final String outputPath;
    private final MediaMuxer muxer;
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private final MediaCodec.BufferInfo writeInfo = new MediaCodec.BufferInfo();

    private int videoTrackIndex = -1;
    private int audioTrackIndex = -1;
    private boolean audioExpected;
    private boolean started;
    private boolean released;
    private long pendingBytes;

    private long videoFrames;
    private long audioFrames;
    private long firstPtsUs = Long.MAX_VALUE;
    private long lastPtsUs;
    private long bytesWritten;
    private long basePtsUs;
    private boolean baseApplied;

    /** 所有轨道就位、封装真正开始时回调。 */
    private Runnable onStarted;
    /** 音轨被放弃时回调，用于把 UI/通知里的音频状态改成「无音轨」。 */
    private Runnable onAudioAbandoned;

    public Mp4Muxer(String outputPath, boolean expectAudio, int orientationHint) {
        this.outputPath = outputPath;
        this.audioExpected = expectAudio;

        // MediaMuxer 的构造函数抛受检 IOException（路径不可写、无权限、同名文件被占用等）。
        // 录制内核对外统一只抛 unchecked，调用方按 IllegalStateException 兜底，
        // 因此这里就地转换，不把 IOException 挂到构造函数签名上。
        MediaMuxer created;
        try {
            created = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        } catch (Throwable t) {
            throw new IllegalStateException("无法创建 MP4 封装器：" + outputPath, t);
        }
        this.muxer = created;

        if (orientationHint != 0) {
            try {
                muxer.setOrientationHint(orientationHint);
                Log.i(TAG, "setOrientationHint=" + orientationHint);
            } catch (Throwable t) {
                Log.w(TAG, "setOrientationHint 失败", t);
            }
        }
    }

    public void setOnStarted(Runnable r) {
        this.onStarted = r;
    }

    public void setOnAudioAbandoned(Runnable r) {
        this.onAudioAbandoned = r;
    }

    /** 设置录制起点（微秒，单调时钟）。后续所有 PTS 都会减去它，只生效一次。 */
    public void setTimeBase(long us) {
        synchronized (lock) {
            if (!baseApplied) {
                basePtsUs = us;
                baseApplied = true;
            }
        }
    }

    public boolean isStarted() {
        synchronized (lock) {
            return started;
        }
    }

    public boolean hasAudioTrack() {
        synchronized (lock) {
            return audioTrackIndex >= 0;
        }
    }

    public String getOutputPath() {
        return outputPath;
    }

    @Override
    public void onTrackFormat(Track track, MediaFormat format) {
        synchronized (lock) {
            if (released) {
                return;
            }
            try {
                if (track == Track.VIDEO) {
                    if (videoTrackIndex < 0) {
                        videoTrackIndex = muxer.addTrack(format);
                        Log.i(TAG, "video track=" + videoTrackIndex + " " + format);
                    }
                } else {
                    if (audioTrackIndex < 0) {
                        audioTrackIndex = muxer.addTrack(format);
                        Log.i(TAG, "audio track=" + audioTrackIndex + " " + format);
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "addTrack 失败", t);
                onEncoderError(track, t);
                return;
            }
            tryStartLocked();
        }
    }

    /** 放弃音轨：音频编码器始终没吐出格式时调用（详见类注释第 2 点）。 */
    public void giveUpAudioTrack() {
        synchronized (lock) {
            if (!audioExpected) {
                return;
            }
            audioExpected = false;
            tryStartLocked();
        }
        Log.w(TAG, "音轨未就绪，降级为纯视频录制");
        if (onAudioAbandoned != null) {
            onAudioAbandoned.run();
        }
    }

    private void tryStartLocked() {
        if (started || released) {
            return;
        }
        if (videoTrackIndex < 0) {
            return; // 视频轨是所有等待的锚点
        }
        if (audioExpected && audioTrackIndex < 0) {
            return;
        }
        muxer.start();
        started = true;
        int flushed = pending.size();
        flushPendingLocked();
        Log.i(TAG, "muxer started: video=" + videoTrackIndex + " audio=" + audioTrackIndex
                + " 预写样本=" + flushed);
        if (onStarted != null) {
            onStarted.run();
        }
    }

    /** 预写缓冲按 PTS 升序落盘；同一时间戳时视频在前，避免播放器首帧解析异常。 */
    private void flushPendingLocked() {
        List<Pending> sorted = new ArrayList<>(pending);
        Collections.sort(sorted, new Comparator<Pending>() {
            @Override
            public int compare(Pending a, Pending b) {
                int byPts = Long.compare(a.ptsUs, b.ptsUs);
                if (byPts != 0) {
                    return byPts;
                }
                return a.track == b.track ? 0 : (a.track == Track.VIDEO ? -1 : 1);
            }
        });
        pending.clear();
        pendingBytes = 0;
        for (Pending p : sorted) {
            writeLocked(p.track, p.data, 0, p.ptsUs, p.flags, p.size);
        }
    }

    @Override
    public void onSample(Track track, ByteBuffer buffer, MediaCodec.BufferInfo info) {
        if (BufferUtils.isCodecConfig(info) || info.size <= 0) {
            return;
        }
        synchronized (lock) {
            if (released) {
                return;
            }
            long pts = normalize(info.presentationTimeUs);
            if (started) {
                // MediaMuxer 内部同步拷贝数据，可以直接复用编码器缓冲，省一次 memcpy
                writeLocked(track, buffer, info.offset, pts, info.flags, info.size);
                return;
            }
            if (pendingBytes + info.size > MAX_PENDING_BYTES) {
                Log.w(TAG, "预写缓冲超限(" + pendingBytes + "B)，丢弃一帧 " + track);
                return;
            }
            ByteBuffer copy = BufferUtils.deepCopy(buffer, info);
            pending.add(new Pending(track, copy, pts, info.flags, copy.remaining()));
            pendingBytes += copy.remaining();
        }
    }

    private long normalize(long ptsUs) {
        long v = ptsUs - basePtsUs;
        return v < 0 ? 0L : v;
    }

    /**
     * @param dataOffset 样本数据在 {@code data} 中的起始偏移。MediaMuxer 的 native 实现用
     *                   {@code getDirectBufferAddress + offset + size} 定位数据，因此偏移必须如实传入
     */
    private void writeLocked(Track track, ByteBuffer data, int dataOffset,
                             long ptsUs, int flags, int size) {
        int index = (track == Track.VIDEO) ? videoTrackIndex : audioTrackIndex;
        if (index < 0 || size <= 0) {
            return;
        }
        try {
            writeInfo.set(dataOffset, size, ptsUs, flags);
            muxer.writeSampleData(index, data, writeInfo);
        } catch (Throwable t) {
            Log.e(TAG, "writeSampleData 失败 track=" + track + " pts=" + ptsUs, t);
            onEncoderError(track, t);
            return;
        }
        if (track == Track.VIDEO) {
            videoFrames++;
        } else {
            audioFrames++;
        }
        if (ptsUs < firstPtsUs) {
            firstPtsUs = ptsUs;
        }
        if (ptsUs > lastPtsUs) {
            lastPtsUs = ptsUs;
        }
        bytesWritten += size;
    }

    @Override
    public void onEncoderError(Track track, Throwable error) {
        Log.e(TAG, "编码器错误 " + track, error);
    }

    /**
     * 结束封装并释放资源。
     *
     * <p>必须在两个编码器都停止之后再调用，否则会往已经释放的 muxer 写数据。
     */
    public Stats stop() {
        synchronized (lock) {
            if (!released) {
                if (started) {
                    try {
                        muxer.stop();
                    } catch (Throwable t) {
                        // 一个样本都没写进去时 stop() 会抛 IllegalStateException，属于预期情况
                        Log.w(TAG, "muxer.stop 失败（可能没有任何样本）", t);
                    }
                }
                try {
                    muxer.release();
                } catch (Throwable t) {
                    Log.w(TAG, "muxer.release 失败", t);
                }
                released = true;
                started = false;
                pending.clear();
                pendingBytes = 0;
            }
            return new Stats(
                    videoFrames,
                    audioFrames,
                    firstPtsUs == Long.MAX_VALUE ? 0L : firstPtsUs,
                    lastPtsUs,
                    bytesWritten,
                    (videoTrackIndex >= 0 ? 1 : 0) + (audioTrackIndex >= 0 ? 1 : 0),
                    outputPath);
        }
    }
}
