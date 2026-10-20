package org.cocos2dx.cpp.highlight;

import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 高光录制总控（阶段二版本）：编码器准备 + 直写 MP4 验证画面采集是否正确。
 *
 * <h3>阶段二目标</h3>
 * 验证 GameFrameCapture（C++ 侧）能把真实游戏画面送进编码器，录出的视频方向正确、颜色不偏。
 * 此阶段用 Mp4Muxer 直写模式，不做环形缓冲和片段截取（阶段三加入）。
 *
 * <h3>调用顺序</h3>
 * <pre>
 *   prepare()   → 创建编码器，返回 InputSurface 给 C++ 侧做 EGL 初始化
 *   start()     → 启动编码器，开始接收帧
 *   stop()      → 停止编码，封装 MP4，落盘到相册
 * </pre>
 *
 * <h3>线程说明</h3>
 * prepare/start/stop 由 C++ 通过 JNI 在主线程或 GL 线程调用。
 * VideoEncoder 的排空线程（video-encode-drain）在内部自管理。
 */
public final class HighlightRecorder {

    private static final String TAG = "HighlightRecorder";

    // 录制参数（与阶段一一致，竖屏 720p）
    private static final int WIDTH        = 720;
    private static final int HEIGHT       = 1280;
    private static final int FPS          = 30;
    private static final int BITRATE      = 4_000_000;  // 4 Mbps
    private static final int I_FRAME_SEC  = 1;

    private final android.content.Context context;

    private Mp4Muxer  muxer;
    private VideoEncoder videoEncoder;
    private File      tempFile;

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

        // 准备输出路径
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        tempFile = MediaStoreSaver.newTempFile(context, "HL_" + timestamp);

        // 创建 Muxer（纯视频，直写模式）
        try {
            muxer = new Mp4Muxer(tempFile.getAbsolutePath(), false, 0);
        } catch (Exception e) {
            Log.e(TAG, "Mp4Muxer 创建失败", e);
            return null;
        }

        // 创建 VideoEncoder
        videoEncoder = new VideoEncoder(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                choice.codecName,
                choice.width,
                choice.height,
                BITRATE,
                FPS,
                I_FRAME_SEC,
                -1,
                muxer);

        Surface inputSurface;
        try {
            inputSurface = videoEncoder.prepare();
        } catch (Exception e) {
            Log.e(TAG, "VideoEncoder.prepare 失败", e);
            return null;
        }

        Log.i(TAG, "prepare() 完成，InputSurface=" + inputSurface
                + " 分辨率=" + choice.width + "x" + choice.height);
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
        // 以当前时刻为录制时基，后续 PTS 从这里归零
        muxer.setTimeBase(System.nanoTime() / 1000);
        videoEncoder.start();
        Log.i(TAG, "录制已开始");
    }

    /**
     * 停止录制，封装 MP4，落盘到相册。
     * 按正确顺序：signalEOS → awaitEOS → stop encoder → stop muxer → publish。
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

        Mp4Muxer.Stats stats = muxer.stop();
        muxer = null;
        Log.i(TAG, "封装完成：" + stats);

        if (tempFile == null || tempFile.length() <= 0 || stats.videoFrames <= 0) {
            Log.e(TAG, "录制失败：文件为空或无视频帧");
            MediaStoreSaver.deleteQuietly(tempFile);
            return;
        }

        // 落盘到相册
        String displayName = tempFile.getName();
        android.net.Uri uri = MediaStoreSaver.publish(context, tempFile, displayName);
        if (uri != null) {
            Log.i(TAG, "=== 阶段二录制完成，已保存到相册：" + uri + " ===");
            Log.i(TAG, "验证：视频应为游戏画面，方向正确，时长约 " + stats.durationMs() / 1000 + "s");
            MediaStoreSaver.deleteQuietly(tempFile);
        } else {
            Log.w(TAG, "发布到相册失败，临时文件：" + tempFile.getAbsolutePath());
        }
    }

    /** 获取编码器接受的实际宽度（EncoderSelector 可能做了对齐调整）。 */
    public int getEncoderWidth() {
        return videoEncoder != null ? videoEncoder.getWidth() : WIDTH;
    }

    /** 获取编码器接受的实际高度。 */
    public int getEncoderHeight() {
        return videoEncoder != null ? videoEncoder.getHeight() : HEIGHT;
    }
}
