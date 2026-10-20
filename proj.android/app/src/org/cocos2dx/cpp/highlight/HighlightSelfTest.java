package org.cocos2dx.cpp.highlight;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executors;

/**
 * 阶段一自测：验证 VideoEncoder → Mp4Muxer → MediaStoreSaver 整条链路。
 *
 * <p>不依赖游戏渲染，用 Canvas 画变化的色块模拟帧输入，录 5 秒后落盘到相册。
 *
 * <h3>验证目标</h3>
 * <ul>
 *   <li>相册出现约 5 秒的 MP4（纯视频，无音轨）</li>
 *   <li>画面是每帧变色的色块 + 帧序号文字</li>
 *   <li>logcat 中 EncoderSelector 选中的是硬编（hardware=true）</li>
 *   <li>ffprobe / 系统播放器确认只有 1 条 video 流</li>
 * </ul>
 *
 * <h3>调用方式</h3>
 * <pre>
 *   // 在 AppActivity.onCreate 末尾，仅 debug 包：
 *   if (BuildConfig.DEBUG) {
 *       new Handler(Looper.getMainLooper()).postDelayed(
 *           () -> HighlightSelfTest.run(this), 2000);
 *   }
 * </pre>
 */
public final class HighlightSelfTest {

    private static final String TAG = "HLSelfTest";

    // 录制参数
    private static final int WIDTH          = 720;
    private static final int HEIGHT         = 1280;
    private static final int FPS            = 30;
    private static final int BITRATE        = 4_000_000;   // 4 Mbps
    private static final int I_FRAME_SEC    = 1;
    private static final int DURATION_SEC   = 5;

    private HighlightSelfTest() {}

    /**
     * 在后台线程异步执行自测，不阻塞调用方（主线程）。
     */
    public static void run(Activity activity) {
        Executors.newSingleThreadExecutor().execute(() -> runSync(activity));
    }

    private static void runSync(Activity activity) {
        Log.i(TAG, "=== 阶段一自测开始 ===");

        // ── 1. 选择编码器 ────────────────────────────────────────────────
        EncoderSelector.VideoChoice choice;
        try {
            choice = EncoderSelector.selectVideoEncoder(
                    android.media.MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT);
        } catch (Exception e) {
            Log.e(TAG, "EncoderSelector 失败：" + e.getMessage(), e);
            return;
        }
        Log.i(TAG, "选中编码器：" + choice);

        // ── 2. 准备输出路径 ──────────────────────────────────────────────
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String filename = "HLTest_" + timestamp;
        File tempFile = MediaStoreSaver.newTempFile(activity, filename);
        Log.i(TAG, "临时文件：" + tempFile.getAbsolutePath());

        // ── 3. 创建 Mp4Muxer（纯视频，expectAudio=false） ───────────────
        Mp4Muxer muxer;
        try {
            muxer = new Mp4Muxer(tempFile.getAbsolutePath(), false, 0);
        } catch (Exception e) {
            Log.e(TAG, "Mp4Muxer 创建失败：" + e.getMessage(), e);
            return;
        }
        // 录制起点时基：第一帧画好后再设，这里先用当前时刻占位
        muxer.setTimeBase(System.nanoTime() / 1000);

        // ── 4. 创建 VideoEncoder ─────────────────────────────────────────
        VideoEncoder encoder = new VideoEncoder(
                android.media.MediaFormat.MIMETYPE_VIDEO_AVC,
                choice.codecName,
                choice.width,
                choice.height,
                BITRATE,
                FPS,
                I_FRAME_SEC,
                -1,           // bitrateModeOverride=-1 表示不强制设置
                muxer);

        Surface inputSurface;
        try {
            inputSurface = encoder.prepare();
        } catch (Exception e) {
            Log.e(TAG, "VideoEncoder.prepare 失败：" + e.getMessage(), e);
            return;
        }

        encoder.start();
        Log.i(TAG, "编码器已启动，输入 Surface=" + inputSurface);

        // ── 5. 用 Canvas 向 InputSurface 推帧 ───────────────────────────
        // Surface 输入模式下 PTS 由 EGL eglPresentationTimeANDROID 控制；
        // 但 Canvas 推帧时系统会自动给一个时间戳（lockHardwareCanvas 不是精确的）。
        // 阶段一只验证「能录出来」，时间戳精度够用即可。
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(60);

        int[] colors = {
                Color.RED, Color.GREEN, Color.BLUE,
                Color.YELLOW, Color.CYAN, Color.MAGENTA,
                Color.WHITE, Color.DKGRAY
        };
        int totalFrames = FPS * DURATION_SEC;
        long frameIntervalMs = 1000L / FPS;

        for (int i = 0; i < totalFrames; i++) {
            Canvas canvas = null;
            try {
                canvas = inputSurface.lockCanvas(null);
                if (canvas == null) {
                    Log.w(TAG, "lockCanvas 返回 null，跳过帧 " + i);
                } else {
                    int bg = colors[i % colors.length];
                    canvas.drawColor(bg);
                    // 对比色文字
                    paint.setColor(isLight(bg) ? Color.BLACK : Color.WHITE);
                    canvas.drawText("Frame " + i + " / " + totalFrames, 60, 200, paint);
                    canvas.drawText(timestamp, 60, 300, paint);
                }
            } catch (Exception e) {
                Log.w(TAG, "绘制帧 " + i + " 失败", e);
            } finally {
                if (canvas != null) {
                    try {
                        inputSurface.unlockCanvasAndPost(canvas);
                    } catch (Exception e) {
                        Log.w(TAG, "unlockCanvasAndPost 失败", e);
                    }
                }
            }

            try {
                Thread.sleep(frameIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        Log.i(TAG, "推帧完成，共 " + totalFrames + " 帧");

        // ── 6. 按正确顺序停止 ────────────────────────────────────────────
        // 顺序：signalEOS → awaitEOS → stop encoder → stop muxer
        // 参考：AndroidGameRecorder 技术文档 §10 停止顺序
        encoder.signalEndOfInputStream();
        boolean eosOk = encoder.awaitEndOfStream(2_000);
        Log.i(TAG, "awaitEndOfStream=" + eosOk);

        encoder.stop();
        Mp4Muxer.Stats stats = muxer.stop();
        Log.i(TAG, "封装完成：" + stats);

        // ── 7. 验证文件有效性 ────────────────────────────────────────────
        if (tempFile.length() <= 0) {
            Log.e(TAG, "临时文件为空，录制可能失败");
            MediaStoreSaver.deleteQuietly(tempFile);
            return;
        }
        if (stats.videoFrames <= 0) {
            Log.e(TAG, "没有写入任何视频帧，录制失败");
            MediaStoreSaver.deleteQuietly(tempFile);
            return;
        }

        // ── 8. 发布到相册 ────────────────────────────────────────────────
        String displayName = filename + ".mp4";
        Uri uri = MediaStoreSaver.publish(activity, tempFile, displayName);
        if (uri != null) {
            Log.i(TAG, "=== 自测成功，已保存到相册：" + uri + " ===");
            Log.i(TAG, "验证：用 ffprobe 确认只有 1 条 video 流，时长约 " + DURATION_SEC + "s");
        } else {
            Log.w(TAG, "发布到相册失败，临时文件保留在：" + tempFile.getAbsolutePath());
        }

        // 发布成功后删除临时文件
        if (uri != null) {
            MediaStoreSaver.deleteQuietly(tempFile);
        }
    }

    /** 判断颜色是否偏亮，用于选对比色文字。 */
    private static boolean isLight(int color) {
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);
        // 感知亮度公式
        return (r * 299 + g * 587 + b * 114) / 1000 > 128;
    }
}
