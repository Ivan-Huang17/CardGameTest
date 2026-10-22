package org.cocos2dx.cpp.highlight;

import android.util.Log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 按 GOP（关键帧分组）管理的视频环形缓冲区，持续保留最近 N 秒的编码数据。
 *
 * <p>设计思路参考 highlight-recorder 项目的 {@code PacketRingBuffer}（C++ 实现），
 * 在本项目中用 Java 重新实现，直接运行在 {@link VideoEncoder} 的排空线程里，
 * 不需要跨 JNI 传输编码后的包数据（详见 {@code highlight_recorder_plan.md} 阶段三）。
 *
 * <h3>核心规则</h3>
 * <ul>
 *   <li>一个 IDR（关键）帧开启一个新 GOP 组，后续非关键帧追加到当前 GOP；</li>
 *   <li>容量上限为 {@code maxGopCount} 个 GOP，超出时淘汰最旧的整组；</li>
 *   <li>{@link #snapshot} 返回的片段永远从某个 GOP 的 IDR 帧开始
 *       （向前对齐），保证截取出的视频能独立解码播放；</li>
 *   <li>音频包按时间窗口独立截取，不受 GOP 对齐约束。</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * {@link #pushVideo} / {@link #pushAudio} 在编码器排空线程调用；
 * {@link #snapshot} 在触发高光时可能由另一线程调用。所有读写方法都加锁。
 */
public final class PacketRingBuffer {

    private static final String TAG = "PacketRingBuffer";

    /** 一个 GOP 组：一个 IDR 帧 + 其后续的非关键帧。 */
    private static final class GopGroup {
        long startPtsUs;
        long endPtsUs;
        final List<EncodedPacket> packets = new ArrayList<>();
    }

    private final int maxGopCount;
    private final Object lock = new Object();

    // 视频：双端队列，每个元素是一个 GOP 组，front 最旧，back 最新
    private final Deque<GopGroup> videoGops = new ArrayDeque<>();

    // 音频：按 pts 升序排列的全量队列
    private final Deque<EncodedPacket> audioPackets = new ArrayDeque<>();

    /**
     * @param maxGopCount 最多保留的 GOP 数量，决定「回看时长」。
     *                    经验值：I 帧间隔 1 秒时，保留 20 个 GOP ≈ 20 秒回看缓冲。
     */
    public PacketRingBuffer(int maxGopCount) {
        this.maxGopCount = maxGopCount;
        Log.i(TAG, "created, maxGopCount=" + maxGopCount);
    }

    // ------------------------------------------------------------------
    // 写入（编码器排空线程调用）
    // ------------------------------------------------------------------

    public void pushVideo(EncodedPacket pkt) {
        if (pkt == null) return;

        synchronized (lock) {
            if (pkt.isKeyFrame) {
                // IDR 帧：关闭上一个 GOP，开启新 GOP
                if (!videoGops.isEmpty()) {
                    videoGops.peekLast().endPtsUs = pkt.ptsUs;
                }
                GopGroup gop = new GopGroup();
                gop.startPtsUs = pkt.ptsUs;
                gop.endPtsUs = pkt.ptsUs;
                gop.packets.add(pkt);
                videoGops.addLast(gop);
            } else {
                // 非 IDR：追加到当前 GOP
                if (videoGops.isEmpty()) {
                    // 还未收到第一个 IDR，丢弃（保证片段必须从 IDR 开头）
                    Log.w(TAG, "丢弃首个 IDR 之前的非关键帧 pts=" + pkt.ptsUs);
                    return;
                }
                GopGroup last = videoGops.peekLast();
                last.endPtsUs = pkt.ptsUs;
                last.packets.add(pkt);
            }
            evictIfNeeded();
        }
    }

    public void pushAudio(EncodedPacket pkt) {
        if (pkt == null) return;
        synchronized (lock) {
            audioPackets.addLast(pkt);
        }
    }

    // ------------------------------------------------------------------
    // 读取（触发高光时调用）
    // ------------------------------------------------------------------

    /**
     * 截取 [startUs, endUs] 时间段的片段。
     * 视频向前对齐到覆盖 startUs 的 GOP 的 IDR 帧起点；音频按实际时间窗口截取。
     */
    public HighlightClip snapshot(long startUs, long endUs) {
        synchronized (lock) {
            if (videoGops.isEmpty()) {
                Log.w(TAG, "snapshot: 缓冲区为空");
                return new HighlightClip(startUs, startUs, endUs);
            }

            List<GopGroup> gopList = new ArrayList<>(videoGops);

            // 找最后一个 startPtsUs <= startUs 的 GOP（向前对齐）
            int firstGopIdx = -1;
            for (int i = gopList.size() - 1; i >= 0; i--) {
                if (gopList.get(i).startPtsUs <= startUs) {
                    firstGopIdx = i;
                    break;
                }
            }
            if (firstGopIdx < 0) {
                // startUs 早于缓冲区最早数据，从头开始
                firstGopIdx = 0;
            }

            long actualStartUs = gopList.get(firstGopIdx).startPtsUs;
            HighlightClip clip = new HighlightClip(startUs, actualStartUs, endUs);

            for (int i = firstGopIdx; i < gopList.size(); i++) {
                GopGroup gop = gopList.get(i);
                if (gop.startPtsUs > endUs) break;
                clip.videoPackets.addAll(gop.packets);
            }

            for (EncodedPacket pkt : audioPackets) {
                if (pkt.ptsUs < actualStartUs) continue;
                if (pkt.ptsUs > endUs) break;
                clip.audioPackets.add(pkt);
            }

            Log.i(TAG, "snapshot: 请求[" + startUs + "," + endUs + "]us"
                    + " 实际起点=" + actualStartUs + "us"
                    + " video=" + clip.videoPackets.size()
                    + " audio=" + clip.audioPackets.size());
            return clip;
        }
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public long earliestVideoPts() {
        synchronized (lock) {
            return videoGops.isEmpty() ? -1 : videoGops.peekFirst().startPtsUs;
        }
    }

    public long latestVideoPts() {
        synchronized (lock) {
            return videoGops.isEmpty() ? -1 : videoGops.peekLast().endPtsUs;
        }
    }

    public int gopCount() {
        synchronized (lock) {
            return videoGops.size();
        }
    }

    /** 当前缓冲区总字节数（视频+音频），用于内存占用监控。 */
    public long totalBytes() {
        synchronized (lock) {
            long total = 0;
            for (GopGroup gop : videoGops) {
                for (EncodedPacket pkt : gop.packets) {
                    total += pkt.data.length;
                }
            }
            for (EncodedPacket pkt : audioPackets) {
                total += pkt.data.length;
            }
            return total;
        }
    }

    public void clear() {
        synchronized (lock) {
            videoGops.clear();
            audioPackets.clear();
        }
        Log.i(TAG, "cleared");
    }

    // ------------------------------------------------------------------
    // 淘汰（已持锁时调用）
    // ------------------------------------------------------------------

    private void evictIfNeeded() {
        while (videoGops.size() > maxGopCount) {
            GopGroup oldest = videoGops.pollFirst();
            if (oldest == null) break;

            // 同步清理早于新缓冲区起点的音频包
            long newEarliest = videoGops.isEmpty()
                    ? Long.MAX_VALUE
                    : videoGops.peekFirst().startPtsUs;
            while (!audioPackets.isEmpty()
                    && audioPackets.peekFirst().ptsUs < newEarliest) {
                audioPackets.pollFirst();
            }
        }
    }
}
