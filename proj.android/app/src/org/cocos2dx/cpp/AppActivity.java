/****************************************************************************
Copyright (c) 2015-2016 Chukong Technologies Inc.
Copyright (c) 2017-2018 Xiamen Yaji Software Co., Ltd.

http://www.cocos2d-x.org

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
****************************************************************************/
package org.cocos2dx.cpp;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;

import org.cocos2dx.cpp.highlight.HighlightRecorder;
import org.cocos2dx.lib.Cocos2dxActivity;

import java.util.ArrayList;
import java.util.List;

public class AppActivity extends Cocos2dxActivity {

    private static final String TAG = "AppActivity";
    private static final int REQ_PERMISSIONS = 100;

    private HighlightRecorder highlightRecorder;
    private Handler statsHandler;
    private Runnable statsRunnable;

    // JNI 方法声明：C++ HighlightService 实现这些
    private native void nativeOnSurfaceReady(Surface surface, int encW, int encH);
    private native void nativeStart();
    private native void nativeStop();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.setEnableVirtualButton(false);
        super.onCreate(savedInstanceState);

        if (!isTaskRoot()) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }

        requestRecordPermissions();
    }

    // ------------------------------------------------------------------
    // 录制启动流程
    // 权限就绪 → prepare（Java，返回 Surface）→ nativeOnSurfaceReady（C++ EGL初始化）
    // → nativeStart（C++ 挂钩子）→ Java start（编码器开始接收帧）
    // ------------------------------------------------------------------

    private void startHighlightRecording() {
        highlightRecorder = HighlightRecorder.getInstance(this);

        // 1. Java 侧准备编码器，拿到 InputSurface
        Surface inputSurface = highlightRecorder.prepare();
        if (inputSurface == null) {
            Log.e(TAG, "HighlightRecorder.prepare() 失败");
            return;
        }

        int encW = highlightRecorder.getEncoderWidth();
        int encH = highlightRecorder.getEncoderHeight();

        // 2. 把 Surface 传给 C++，C++ 在 GL 线程初始化 EGLSurface
        nativeOnSurfaceReady(inputSurface, encW, encH);

        // 3. 等 GL 线程完成 EGL 初始化（异步投递，留一帧时间）
        //    再启动编码器开始接收帧
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
        // 顺序很重要：
        // 1. 先 start Java 编码器，激活 InputSurface 的 BufferQueue
        // 2. 再挂 C++ 钩子，确保第一帧送到时编码器已经 ready
        highlightRecorder.start();   // Java：codec.start()，激活 Surface
        nativeStart();               // C++：挂 EVENT_AFTER_DRAW 钩子，开始送帧
        Log.i(TAG, "高光录制已启动");

            startBufferStatsLogging();

            // 阶段三验证：录 30 秒后自动停止（超过 maxGopCount=20s，观察淘汰是否生效）
            new Handler(Looper.getMainLooper()).postDelayed(
                    this::stopHighlightRecording, 30_000);
        }, 500);
    }

    /** 每秒打印一次环形缓冲区状态，用于阶段三验证 GOP 淘汰和内存占用。 */
    private void startBufferStatsLogging() {
        statsHandler = new Handler(Looper.getMainLooper());
        statsRunnable = new Runnable() {
            @Override
            public void run() {
                if (highlightRecorder != null) {
                    highlightRecorder.logBufferStats();
                }
                statsHandler.postDelayed(this, 1000);
            }
        };
        statsHandler.post(statsRunnable);
    }

    private void stopBufferStatsLogging() {
        if (statsHandler != null && statsRunnable != null) {
            statsHandler.removeCallbacks(statsRunnable);
        }
    }

    private void stopHighlightRecording() {
        Log.i(TAG, "停止录制...");
        stopBufferStatsLogging();
        nativeStop();                    // C++：先移除钩子，停止送帧
        if (highlightRecorder != null) {
            highlightRecorder.stop();    // Java：停止编码，并把环形缓冲内容封装落盘到相册
        }
    }

    // ------------------------------------------------------------------
    // 权限申请
    // ------------------------------------------------------------------

    private void requestRecordPermissions() {
        List<String> needed = new ArrayList<>();

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }

        if (needed.isEmpty()) {
            new Handler(Looper.getMainLooper()).postDelayed(
                    this::startHighlightRecording, 2000);
        } else {
            requestPermissions(needed.toArray(new String[0]), REQ_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMISSIONS) return;

        new Handler(Looper.getMainLooper()).postDelayed(
                this::startHighlightRecording, 2000);
    }
}
