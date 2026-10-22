package org.cocos2dx.cpp.highlight;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTimestamp;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 音频编码器：PCM → AAC-LC → {@link SampleSink}。
 *
 * <p>支持两种音频来源：
 * <ul>
 *   <li><b>引擎分流模式</b>（本项目使用）：外部调用 {@link #feedPcm} 喂 PCM，
 *       数据来自 PcmAudioService 的混音输出（通过 C++ 攒批 + JNI 传入）；
 *       此模式下 {@code AudioRecord} 不启动，不需要 RECORD_AUDIO 权限。</li>
 *   <li><b>麦克风模式</b>：内部 {@code AudioRecord} 循环读取（原始逻辑保留）。</li>
 * </ul>
 */
public final class AudioEncoder {

    private static final String TAG = "AudioEncoder";
    private static final long DEQUEUE_TIMEOUT_US = 10_000L;

    private final int sampleRate;
    private final int channels;
    private final int bitrate;
    private final SampleSink sink;

    private MediaCodec codec;
    private AudioRecord record;   // 麦克风模式时使用，引擎分流模式为 null
    private Thread thread;
    private final CountDownLatch eosLatch = new CountDownLatch(1);
    private volatile boolean running;
    private volatile boolean prepared;

    // 引擎分流模式：外部喂入的 PCM 批次队列
    // 每个元素是 { pcmData, ptsNs }
    private static final class PcmBatch {
        final byte[] data;
        final long ptsNs;
        PcmBatch(byte[] data, long ptsNs) { this.data = data; this.ptsNs = ptsNs; }
    }
    private final LinkedBlockingQueue<PcmBatch> pcmQueue = new LinkedBlockingQueue<>(64);
    private boolean engineMode = false;  // true = 引擎分流模式

    // 每次 read 的 PCM 缓冲：16bit 立体声，约 4096 样本帧
    private static final int PCM_BUFFER_SAMPLES = 4096;
    private byte[] pcmBuffer;

    public AudioEncoder(int sampleRate, int channels, int bitrate, SampleSink sink) {
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.bitrate = bitrate;
        this.sink = sink;
    }

    public int getSampleRate() { return sampleRate; }
    public int getChannels()   { return channels; }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /**
     * 准备编码器。
     * 引擎分流模式下不启动 AudioRecord，prepare 成功后等待 {@link #feedPcm} 喂数据。
     */
    public void prepare() {
        prepareEncoder();
        // 不创建 AudioRecord，改用引擎分流模式
        engineMode = true;
        pcmBuffer = new byte[PCM_BUFFER_SAMPLES * channels * 2];
        prepared = true;
        Log.i(TAG, "AudioEncoder 准备完成（引擎分流模式）");
    }

    private void prepareEncoder() {
        MediaFormat format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024);

        MediaCodec c = null;
        try {
            c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        } catch (Throwable t) {
            if (c != null) {
                try { c.release(); } catch (Throwable ignored) {}
            }
            throw new IllegalStateException("AAC 编码器初始化失败", t);
        }
        this.codec = c;
        Log.i(TAG, "AAC 编码器=" + c.getName()
                + " " + sampleRate + "Hz/" + channels + "ch " + (bitrate / 1000) + "kbps");
    }

    @SuppressLint("MissingPermission")
    private void prepareAudioRecord() {
        int channelMask = channels == 2
                ? AudioFormat.CHANNEL_IN_STEREO
                : AudioFormat.CHANNEL_IN_MONO;
        int minBuffer = AudioRecord.getMinBufferSize(
                sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT);
        int bufferBytes = Math.max(minBuffer * 2, sampleRate * channels * 2 / 5);

        this.record = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes);

        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            record.release();
            throw new IllegalStateException("AudioRecord 初始化失败（麦克风权限未授予或设备不支持）");
        }
        Log.i(TAG, "AudioRecord 就绪 " + sampleRate + "Hz/" + channels
                + "ch bufferBytes=" + bufferBytes);
    }

    public void start() {
        if (!prepared) throw new IllegalStateException("prepare() 尚未调用");
        codec.start();
        if (record != null) {
            record.startRecording();
        }
        running = true;
        thread = new Thread(engineMode ? this::loopEngineMode : this::loop,
                "hl-audio-encode");
        thread.start();
    }

    /**
     * 外部喂入一批 PCM 数据（引擎分流模式专用）。
     * 由 {@link HighlightRecorder#onAudioBatch} 在 JNI 调用时触发。
     *
     * @param pcm   PCM 字节数组（S16 interleaved，深拷贝后的数据）
     * @param ptsNs 本批第一个采样点的 CLOCK_MONOTONIC 时间戳（纳秒），来自 C++ 侧
     */
    public void feedPcm(byte[] pcm, long ptsNs) {
        if (!running || pcm == null || pcm.length == 0) return;
        // 队列满时丢弃本批（录制优先级低于游戏流畅）
        pcmQueue.offer(new PcmBatch(pcm, ptsNs));
    }

    public void stop() {
        running = false;
        // 唤醒可能阻塞在 queue.poll 的编码线程
        pcmQueue.offer(new PcmBatch(new byte[0], -1));
        if (thread != null) {
            try { thread.join(1_500); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
        if (record != null) {
            try { record.stop();    } catch (Throwable t) { Log.w(TAG, "record.stop 失败", t); }
            try { record.release(); } catch (Throwable t) { Log.w(TAG, "record.release 失败", t); }
            record = null;
        }
        drainTail();
        if (codec != null) {
            try { codec.stop();    } catch (Throwable t) { Log.w(TAG, "codec.stop 失败", t); }
            try { codec.release(); } catch (Throwable t) { Log.w(TAG, "codec.release 失败", t); }
            codec = null;
        }
        prepared = false;
    }

    public boolean awaitEndOfStream(long timeoutMs) {
        try {
            return eosLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 引擎分流模式编码循环
    // ------------------------------------------------------------------

    /**
     * 引擎分流模式：从 {@link #pcmQueue} 取批次数据喂给 MediaCodec。
     * PTS 直接用 C++ 传来的时间戳（CLOCK_MONOTONIC 纳秒转微秒），与视频帧同源。
     */
    private void loopEngineMode() {
        MediaCodec.BufferInfo outInfo = new MediaCodec.BufferInfo();
        try {
            while (running) {
                // 从队列取一批数据，最多等 100ms
                PcmBatch batch = pcmQueue.poll(2000, TimeUnit.MILLISECONDS);
                if (batch == null) continue;
                if (batch.data.length == 0) break; // stop() 发来的退出信号

                // 计算这批数据的 PTS（微秒），以 C++ 时间戳为基准
                long batchPtsUs = batch.ptsNs / 1000;
                int offset = 0;

                // 把这批 PCM 按编码器帧大小逐块送入
                while (offset < batch.data.length) {
                    int inIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
                    if (inIndex < 0) break;

                    ByteBuffer inBuf = codec.getInputBuffer(inIndex);
                    if (inBuf == null) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, 0);
                        break;
                    }
                    inBuf.clear();
                    int room = Math.min(inBuf.remaining(), batch.data.length - offset);
                    inBuf.put(batch.data, offset, room);

                    // 逐帧推算 PTS：基于采样数偏移，精确到采样级别
                    int samplesOffset = offset / (channels * 2);
                    long framePtsUs = batchPtsUs + samplesOffset * 1_000_000L / sampleRate;

                    codec.queueInputBuffer(inIndex, 0, room, framePtsUs, 0);
                    offset += room;
                }

                drainOnce(outInfo);
            }
        } catch (Throwable t) {
            Log.e(TAG, "音频编码线程（引擎分流模式）异常", t);
            sink.onEncoderError(Track.AUDIO, t);
        } finally {
            eosLatch.countDown();
        }
    }

    // ------------------------------------------------------------------
    // 麦克风模式编码循环（原始逻辑保留）
    // ------------------------------------------------------------------

    private void loop() {
        MediaCodec.BufferInfo outInfo = new MediaCodec.BufferInfo();
        long totalFrames = 0L;
        long frameZeroPtsUs = Long.MIN_VALUE;

        try {
            while (running) {
                int inIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
                if (inIndex >= 0) {
                    ByteBuffer inBuf = codec.getInputBuffer(inIndex);
                    if (inBuf == null) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, 0);
                    } else {
                        inBuf.clear();
                        int room = Math.min(inBuf.remaining(), pcmBuffer.length);
                        int read = record.read(pcmBuffer, 0, room);
                        if (read > 0) {
                            if (frameZeroPtsUs == Long.MIN_VALUE) {
                                frameZeroPtsUs = resolveFrameZeroPtsUs(totalFrames);
                                Log.i(TAG, "音频时基 frameZeroPtsUs=" + frameZeroPtsUs);
                            }
                            inBuf.put(pcmBuffer, 0, read);
                            long ptsUs = frameZeroPtsUs
                                    + totalFrames * 1_000_000L / sampleRate;
                            codec.queueInputBuffer(inIndex, 0, read, ptsUs, 0);
                            totalFrames += read / (channels * 2);
                        } else {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, 0);
                        }
                    }
                }
                drainOnce(outInfo);
            }
        } catch (Throwable t) {
            Log.e(TAG, "音频编码线程异常", t);
            sink.onEncoderError(Track.AUDIO, t);
        } finally {
            eosLatch.countDown();
        }
    }

    private void drainOnce(MediaCodec.BufferInfo info) {
        while (true) {
            int index = codec.dequeueOutputBuffer(info, 0);
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                sink.onTrackFormat(Track.AUDIO, codec.getOutputFormat());
                continue;
            }
            if (index < 0) break;

            ByteBuffer buf = codec.getOutputBuffer(index);
            if (buf != null) {
                if (BufferUtils.isCodecConfig(info)) info.size = 0;
                if (info.size > 0) sink.onSample(Track.AUDIO, buf, info);
            }
            boolean eos = BufferUtils.isEndOfStream(info);
            codec.releaseOutputBuffer(index, false);
            if (eos) { Log.i(TAG, "收到音频 EOS"); break; }
        }
    }

    private void drainTail() {
        MediaCodec c = codec;
        if (c == null) return;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            int idx = c.dequeueInputBuffer(200_000);
            if (idx >= 0) {
                c.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            }
        } catch (Throwable t) { Log.w(TAG, "发音频 EOS 失败", t); }
        int idle = 0;
        while (idle < 100) {
            MediaCodec.BufferInfo outInfo = new MediaCodec.BufferInfo();
            int outIdx;
            try { outIdx = c.dequeueOutputBuffer(outInfo, DEQUEUE_TIMEOUT_US); }
            catch (Throwable t) { Log.w(TAG, "收尾排空失败", t); break; }

            if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) { idle++; continue; }
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                sink.onTrackFormat(Track.AUDIO, c.getOutputFormat());
                continue;
            }
            if (outIdx < 0) continue;

            ByteBuffer buf = c.getOutputBuffer(outIdx);
            if (buf != null) {
                if (BufferUtils.isCodecConfig(outInfo)) outInfo.size = 0;
                if (outInfo.size > 0) sink.onSample(Track.AUDIO, buf, outInfo);
            }
            boolean eos = BufferUtils.isEndOfStream(outInfo);
            c.releaseOutputBuffer(outIdx, false);
            if (eos) { Log.i(TAG, "收到音频收尾 EOS"); break; }
        }
    }

    private long resolveFrameZeroPtsUs(long framesConsumed) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && record != null) {
            try {
                AudioTimestamp ts = new AudioTimestamp();
                if (record.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC)
                        == AudioRecord.SUCCESS && ts.framePosition > 0) {
                    return ts.nanoTime / 1000 - ts.framePosition * 1_000_000L / sampleRate;
                }
            } catch (Throwable t) {
                Log.w(TAG, "getTimestamp 失败，退化为估算", t);
            }
        }
        return System.nanoTime() / 1000 - framesConsumed * 1_000_000L / sampleRate;
    }
}
