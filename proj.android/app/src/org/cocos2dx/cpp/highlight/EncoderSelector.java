package org.cocos2dx.cpp.highlight;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 编码器选择与分辨率校正。
 *
 * <h3>为什么不能直接 configure</h3>
 * AVC/HEVC 编码器对分辨率有「对齐 + 上下限」约束，且不同 SoC 差异很大
 * （高通多数只要求 2 对齐，部分 MTK / 海思要求 16 对齐）。尺寸非法时
 * {@code MediaCodec.configure()} 会直接抛 {@code CodecException}，而不是自动缩放。
 * 所以流程必须是：
 * <ol>
 *   <li>按对齐要求向上取整（优先 16，不支持时退回编码器声明的对齐值）；</li>
 *   <li>用 {@link MediaCodecInfo.VideoCapabilities#isSizeSupported(int, int)} 校验；</li>
 *   <li>不通过就按比例逐级缩小，直到被接受。</li>
 * </ol>
 *
 * <h3>为什么要挑硬件编码器</h3>
 * 软件编码器（{@code c2.android.avc.encoder}）在 1080p30 下会吃掉一两个大核，
 * 而游戏本身正在抢 CPU，结果就是游戏掉帧 + 录制掉帧双向恶化。
 * 因此硬编优先（API 29+ 用官方 {@code isHardwareAccelerated}，
 * 更低版本用名字启发式判断）。
 */
public final class EncoderSelector {

    private static final String TAG = "EncoderSelector";

    private EncoderSelector() {
    }

    /** 选中的编码器及其接受的实际分辨率。 */
    public static final class VideoChoice {
        public final String codecName;
        public final MediaCodecInfo info;
        public final int width;
        public final int height;
        public final boolean hardware;

        VideoChoice(String codecName, MediaCodecInfo info, int width, int height, boolean hardware) {
            this.codecName = codecName;
            this.info = info;
            this.width = width;
            this.height = height;
            this.hardware = hardware;
        }

        @Override
        public String toString() {
            return codecName + " (" + width + "x" + height + (hardware ? ", HW)" : ", SW)");
        }
    }

    /**
     * 选择可用的视频编码器，并返回它真正接受的分辨率。
     *
     * @throws IllegalStateException 没有任何编码器能处理该格式/尺寸
     */
    public static VideoChoice selectVideoEncoder(String mime, int wantWidth, int wantHeight) {
        List<MediaCodecInfo> candidates = new ArrayList<>();
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!info.isEncoder()) {
                continue;
            }
            for (String type : info.getSupportedTypes()) {
                if (type.equalsIgnoreCase(mime)) {
                    candidates.add(info);
                    break;
                }
            }
        }
        if (candidates.isEmpty()) {
            throw new IllegalStateException("设备没有支持 " + mime + " 的编码器");
        }

        // 硬编优先；同档内保持系统给出的顺序（通常是优选顺序）
        Collections.sort(candidates, new Comparator<MediaCodecInfo>() {
            @Override
            public int compare(MediaCodecInfo a, MediaCodecInfo b) {
                return Boolean.compare(isHardware(a), isHardware(b)) * -1;
            }
        });

        String lastError = null;
        for (MediaCodecInfo info : candidates) {
            MediaCodecInfo.CodecCapabilities caps;
            try {
                caps = info.getCapabilitiesForType(mime);
            } catch (Throwable t) {
                lastError = info.getName() + ": " + t.getMessage();
                continue;
            }
            if (caps == null || caps.getVideoCapabilities() == null) {
                continue;
            }
            MediaCodecInfo.VideoCapabilities vc = caps.getVideoCapabilities();
            int[] fitted = fitSize(vc, wantWidth, wantHeight);
            if (fitted != null) {
                return new VideoChoice(info.getName(), info, fitted[0], fitted[1], isHardware(info));
            }
            lastError = info.getName() + ": 不支持 " + wantWidth + "x" + wantHeight
                    + "，支持范围 " + vc.getSupportedWidths() + " / " + vc.getSupportedHeights();
        }
        throw new IllegalStateException(
                "找不到能编码 " + wantWidth + "x" + wantHeight + " 的 " + mime + " 编码器（" + lastError + "）");
    }

    public static boolean isHardware(MediaCodecInfo info) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return info.isHardwareAccelerated();
        }
        // API 29 之前没有官方标志：AOSP 软编固定以 omx.google. / c2.android. 开头
        String n = info.getName().toLowerCase();
        return !n.startsWith("omx.google.") && !n.startsWith("c2.android.");
    }

    /**
     * 把目标尺寸校正为编码器接受的尺寸。
     *
     * @return {@code {width, height}}，或 null 表示该编码器完全不可用
     */
    private static int[] fitSize(MediaCodecInfo.VideoCapabilities vc, int wantWidth, int wantHeight) {
        if (wantWidth <= 0 || wantHeight <= 0) {
            return null;
        }
        int widthAlignment = Math.max(2, vc.getWidthAlignment());
        int heightAlignment = Math.max(2, vc.getHeightAlignment());
        int alignment = Math.max(widthAlignment, heightAlignment);

        // 优先 16 对齐：绝大多数硬编的宏块/步长要求，可规避画面右侧错位
        int[] alignCandidates = distinct(16, alignment, 2);
        for (int align : alignCandidates) {
            int w = alignTo(wantWidth, align);
            int h = alignTo(wantHeight, align);
            if (supports(vc, w, h, widthAlignment, heightAlignment)) {
                return new int[]{w, h};
            }
        }

        // 逐级缩小（每次 10%，最多 10 级），保持长宽比
        int w = alignTo(wantWidth, 16);
        int h = alignTo(wantHeight, 16);
        for (int i = 0; i < 10; i++) {
            w = alignTo((int) (w * 0.9f), 16);
            h = alignTo((int) (h * 0.9f), 16);
            if (w <= 0 || h <= 0) {
                return null;
            }
            if (supports(vc, w, h, widthAlignment, heightAlignment)) {
                return new int[]{w, h};
            }
        }
        return null;
    }

    private static boolean supports(MediaCodecInfo.VideoCapabilities vc, int w, int h,
                                    int widthAlignment, int heightAlignment) {
        if (w < widthAlignment || h < heightAlignment) {
            return false;
        }
        try {
            return vc.isSizeSupported(w, h);
        } catch (Throwable t) {
            return false;
        }
    }

    private static int[] distinct(int... values) {
        List<Integer> list = new ArrayList<>(values.length);
        for (int v : values) {
            if (v > 1 && !list.contains(v)) {
                list.add(v);
            }
        }
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i);
        }
        return out;
    }

    private static int alignTo(int value, int align) {
        int a = Math.max(2, align);
        return (value + a - 1) / a * a;
    }
}
