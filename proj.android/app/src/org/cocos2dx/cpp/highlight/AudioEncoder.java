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
import java.util.concurrent.TimeUnit;

/**
 * 音频编码器：麦克风 PCM → AAC-LC → {@link SampleSink}。
 *
 * <p>阶段一简化版：只支持麦克风音源，去掉回采、救援、RecordingConfig 等依赖。
 * 后续阶段再按需扩展音源。
 *
 * <h3>关键：-2 与 -1 不是一回事</h3>
 * {@code dequeueOutputBuffer} 的 {@code INFO_OUTPUT_FORMAT_CHANGED(-2)} 必须单独处理，
 * 它携带 AAC 的 AudioSpecificConfig(csd-0)，封装器只有拿到它才能 addTrack(AUDIO)。
 * 如果像 {@code if (index < 0) break} 那样统一忽略，音轨就永远建立不起来。
 *
 * <h3>音画同步</h3>
 * 用 {@code AudioRecord.getTimestamp} 反推第 0 个采样帧对应的系统单调时间，
 * 使音频 PTS 与视频 PTS（来自 eglPresentationTimeANDROID）落在同一时基上。
 */
public final class AudioEncoder {

    private static final String TAG = "AudioEncoder";
    private static final long DEQUEUE_TIMEOUT_US = 10_000L;

    private final int sampleRate;
    private final int channels;
    private final int bitrate;
    private final SampleSink sink;

    private MediaCodec codec;
    private AudioRecord record;
    private Thread thread;
    private final CountDownLatch eosLatch = new CountDownLatch(1);
    private volatile boolean running;
    private volatile boolean prepared;

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
     * 准备编码器与麦克风采集器。
     *
     * @throws IllegalStateException 设备不支持 AAC 编码或麦克风不可用
     */
    public void prepare() {
        prepareEncoder();
        prepareAudioRecord();
        pcmBuffer = new byte[PCM_BUFFER_SAMPLES * channels * 2]; // 16bit = 2字节/样本
        prepared = true;
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
        // 取两倍最小缓冲与 200ms 帧量的较大值，避免高采样率下频繁短读
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
        record.startRecording();
        if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            Log.w(TAG, "AudioRecord 未进入录音态: " + record.getRecordingState());
        }
        running = true;
        thread = new Thread(this::loop, "hl-audio-encode");
        thread.start();
    }

    /**
     * 停止采集与编码。
     * 顺序：停采集 → 排空编码器尾部 → stop/release 编码器。
     */
    public void stop() {
        running = false;
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
    // 采集 + 编码循环
    // ------------------------------------------------------------------

    private void loop() {
        MediaCodec.BufferInfo outInfo = new MediaCodec.BufferInfo();
        long totalFrames = 0L;
        long frameZeroPtsUs = Long.MIN_VALUE;

        try {
            while (running) {
                // ── 1) PCM → 编码器输入 ──────────────────────────────────
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

                // ── 2) 编码器输出 → Sink ─────────────────────────────────
                drainOnce(outInfo);
            }
        } catch (Throwable t) {
            Log.e(TAG, "音频编码线程异常", t);
            sink.onEncoderError(Track.AUDIO, t);
        } finally {
            eosLatch.countDown();
        }
    }

    /** 把编码器当前所有可取的输出包排空一次。 */
    private void drainOnce(MediaCodec.BufferInfo info) {
        while (true) {
            int index = codec.dequeueOutputBuffer(info, 0);
            // ★ -2 与 -1 必须分开处理，见类注释
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                sink.onTrackFormat(Track.AUDIO, codec.getOutputFormat());
                continue;
            }
            if (index < 0) break; // INFO_TRY_AGAIN_LATER(-1) 或 INFO_OUTPUT_BUFFERS_CHANGED(-3)

            ByteBuffer buf = codec.getOutputBuffer(index);
            if (buf != null) {
                if (BufferUtils.isCodecConfig(info)) {
                    info.size = 0; // csd-0 已随 outputFormat 给 Muxer，不能再写一遍
                }
                if (info.size > 0) {
                    sink.onSample(Track.AUDIO, buf, info);
                }
            }
            boolean eos = BufferUtils.isEndOfStream(info);
            codec.releaseOutputBuffer(index, false);
            if (eos) {
                Log.i(TAG, "收到音频 EOS");
                break;
            }
        }
    }

    /** 停止采集后把编码器里残留的 AAC 包全部抽出。 */
    private void drainTail() {
        MediaCodec c = codec;
        if (c == null) return;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        // 发 EOS
        try {
            int idx = c.dequeueInputBuffer(200_000);
            if (idx >= 0) {
                c.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            }
        } catch (Throwable t) { Log.w(TAG, "发音频 EOS 失败", t); }
        // 排空
        int idle = 0;
        while (idle < 100) {
            int outIdx;
            try { outIdx = c.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US); }
            catch (Throwable t) { Log.w(TAG, "收尾排空失败", t); break; }

            if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) { idle++; continue; }
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // 兜住「主循环被异常打断」时 csd-0 尚未传给 Muxer 的情况
                sink.onTrackFormat(Track.AUDIO, c.getOutputFormat());
                continue;
            }
            if (outIdx < 0) continue;

            ByteBuffer buf = c.getOutputBuffer(outIdx);
            if (buf != null) {
                if (BufferUtils.isCodecConfig(info)) info.size = 0;
                if (info.size > 0) sink.onSample(Track.AUDIO, buf, info);
            }
            boolean eos = BufferUtils.isEndOfStream(info);
            c.releaseOutputBuffer(outIdx, false);
            if (eos) { Log.i(TAG, "收到音频收尾 EOS"); break; }
        }
    }

    // ------------------------------------------------------------------
    // 时基计算
    // ------------------------------------------------------------------

    /**
     * 反推第 0 个采样帧对应的系统单调时间（微秒）。
     * 使音频 PTS 与视频 PTS（来自 System.nanoTime）落在同一时基上。
     */
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
        // 降级：用当前时刻减去已采集时长
        return System.nanoTime() / 1000 - framesConsumed * 1_000_000L / sampleRate;
    }
}
