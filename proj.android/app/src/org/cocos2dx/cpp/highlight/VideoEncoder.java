package org.cocos2dx.cpp.highlight;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 视频编码器：Surface 输入 → H.264/H.265 码流 → {@link SampleSink}。
 *
 * <h3>为什么用 Surface 输入（{@code COLOR_FormatSurface}）</h3>
 * MediaCodec 支持三种输入方式：ByteBuffer 喂 YUV、ByteBuffer 喂原始帧、
 * 以及 Surface 输入。录屏场景必须用 Surface：
 * <ul>
 *   <li>MediaProjection 直接往 Surface 上合成画面，全程在 GPU 内完成，不需要 CPU 拷贝 YUV；</li>
 *   <li>不需要关心色彩空间/步长，由 GRALLOC/编码器自己协商；</li>
 *   <li>帧率自适应：有新的帧到达才会编码，天然支持可变帧率（静态画面不浪费码率）。</li>
 * </ul>
 *
 * <h3>EOS 的时序</h3>
 * 结束录制时必须调用 {@link #signalEndOfInputStream()}，编码器会把已积压的帧编码完，
 * 然后输出一个带 {@code BUFFER_FLAG_END_OF_STREAM} 的空样本。只有收到这个样本，
 * 才能保证文件里包含了最后一帧。因此停止流程是：
 * <pre>
 *   释放 VirtualDisplay（不再产生新帧）
 *     → signalEndOfInputStream()
 *     → awaitEndOfStream()          // 等排空线程收到 EOS
 *     → stop()                      // codec.stop() + release()
 * </pre>
 * 顺序颠倒会把最后的帧丢掉，严重时文件尾部缺少 I 帧导致无法拖动定位。
 */
public final class VideoEncoder {

    private static final String TAG = "VideoEncoder";
    private static final long DEQUEUE_TIMEOUT_US = 10_000L;

    private final String mime;
    private final String preferredCodecName; // null 表示由系统自动选择
    private final int width;
    private final int height;
    private final int bitrate;
    private final int frameRate;
    private final int iFrameIntervalSec;
    private final int bitrateModeOverride;
    private final SampleSink sink;

    private MediaCodec codec;
    private Surface inputSurface;
    private Thread drainThread;
    private final CountDownLatch eosLatch = new CountDownLatch(1);

    private volatile boolean running;

    public VideoEncoder(String mime,
                        String preferredCodecName,
                        int width,
                        int height,
                        int bitrate,
                        int frameRate,
                        int iFrameIntervalSec,
                        int bitrateModeOverride,
                        SampleSink sink) {
        this.mime = mime;
        this.preferredCodecName = preferredCodecName;
        this.width = width;
        this.height = height;
        this.bitrate = bitrate;
        this.frameRate = frameRate;
        this.iFrameIntervalSec = iFrameIntervalSec;
        this.bitrateModeOverride = bitrateModeOverride;
        this.sink = sink;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getFrameRate() {
        return frameRate;
    }

    public int getBitrate() {
        return bitrate;
    }

    /** 编码器输入 Surface，交给 MediaProjection / GL 渲染器作为绘制目标。 */
    public Surface getInputSurface() {
        return inputSurface;
    }

    public String getCodecName() {
        return codec != null ? codec.getName() : String.valueOf(preferredCodecName);
    }

    /** 创建并配置编码器，返回它的输入 Surface。 */
    public Surface prepare() {
        MediaFormat format = MediaFormat.createVideoFormat(mime, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameIntervalSec);

        // 码率控制模式不是所有编码器都支持，配置失败就退回默认（一般为 VBR）
        if (bitrateModeOverride >= 0) {
            try {
                format.setInteger(MediaFormat.KEY_BITRATE_MODE, bitrateModeOverride);
            } catch (Throwable t) {
                Log.w(TAG, "设置 KEY_BITRATE_MODE 失败，使用默认模式", t);
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                // 每个关键帧都带上 SPS/PPS：录屏文件被裁剪、拖动定位后仍能独立解码
                format.setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1);
            } catch (Throwable ignored) {
                // 部分编码器不认这个 key，忽略
            }
        }

        MediaCodec c = null;
        try {
            c = (preferredCodecName != null)
                    ? MediaCodec.createByCodecName(preferredCodecName)
                    : MediaCodec.createEncoderByType(mime);
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        } catch (Throwable t) {
            // createEncoderByType 抛 IOException，configure 抛 CodecException——都用 Throwable 一并接住。
            // 注意：configure 失败时已经创建出来的 codec 必须显式释放，否则每失败一次就漏一个编码器实例，
            // 而屏幕编码器是全局独占资源，泄漏几次之后后续录制会直接找不到可用编码器。
            if (c != null) {
                try {
                    c.release();
                } catch (Throwable ignored) {
                }
            }
            throw new IllegalStateException("视频编码器初始化失败(" + width + "x" + height + ")", t);
        }

        Surface surface = c.createInputSurface();
        this.codec = c;
        this.inputSurface = surface;
        Log.i(TAG, "编码器=" + c.getName() + " " + width + "x" + height
                + " @" + frameRate + "fps " + (bitrate / 1000) + "kbps "
                + "IFrame=" + iFrameIntervalSec + "s");
        return surface;
    }

    /** 启动编码器并开启排空线程。 */
    public void start() {
        final MediaCodec c = requireCodec();
        c.start();
        running = true;
        drainThread = new Thread(new Runnable() {
            @Override
            public void run() {
                drainLoop(c);
            }
        }, "video-encode-drain");
        drainThread.start();
    }

    /** 请求一个关键帧（例如用户点了「打点」）。 */
    public void requestSyncFrame() {
        if (codec == null) {
            return;
        }
        try {
            Bundle params = new Bundle();
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            codec.setParameters(params);
        } catch (Throwable t) {
            Log.w(TAG, "requestSyncFrame 失败", t);
        }
    }

    /** 告诉编码器后面没有新帧了（Surface 输入专用）。 */
    public void signalEndOfInputStream() {
        if (codec == null) {
            return;
        }
        try {
            codec.signalEndOfInputStream();
        } catch (Throwable t) {
            Log.w(TAG, "signalEndOfInputStream 失败", t);
        }
    }

    /** 等待排空线程收到 EOS。 */
    public boolean awaitEndOfStream(long timeoutMs) {
        try {
            return eosLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 停止并释放。必须在 EOS 处理完之后调用。 */
    public void stop() {
        running = false;
        if (drainThread != null) {
            try {
                drainThread.join(1_500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            drainThread = null;
        }
        if (codec != null) {
            try {
                codec.stop();
            } catch (Throwable t) {
                Log.w(TAG, "video codec.stop 失败", t);
            }
            try {
                codec.release();
            } catch (Throwable t) {
                Log.w(TAG, "video codec.release 失败", t);
            }
            codec = null;
        }
        if (inputSurface != null) {
            try {
                inputSurface.release();
            } catch (Throwable t) {
                Log.w(TAG, "inputSurface.release 失败", t);
            }
            inputSurface = null;
        }
    }

    private void drainLoop(MediaCodec c) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (running) {
                int index = c.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US);
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    continue;
                }
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    sink.onTrackFormat(Track.VIDEO, c.getOutputFormat());
                    continue;
                }
                if (index < 0) {
                    // INFO_OUTPUT_BUFFERS_CHANGED 等返回值，同步模式下可直接忽略
                    continue;
                }

                ByteBuffer buffer = c.getOutputBuffer(index);
                if (buffer != null) {
                    if (BufferUtils.isCodecConfig(info)) {
                        // SPS/PPS 已随 outputFormat 交给 Muxer，不能重复写入
                        info.size = 0;
                    }
                    if (info.size > 0) {
                        sink.onSample(Track.VIDEO, buffer, info);
                    }
                }
                boolean eos = BufferUtils.isEndOfStream(info);
                c.releaseOutputBuffer(index, false);

                if (eos) {
                    Log.i(TAG, "收到视频 EOS");
                    break;
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "视频排空线程异常", t);
            sink.onEncoderError(Track.VIDEO, t);
        } finally {
            eosLatch.countDown();
        }
    }

    private MediaCodec requireCodec() {
        if (codec == null) {
            throw new IllegalStateException("prepare() 尚未调用");
        }
        return codec;
    }
}
