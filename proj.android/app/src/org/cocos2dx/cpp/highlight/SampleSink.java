package org.cocos2dx.cpp.highlight;

import android.media.MediaCodec;
import android.media.MediaFormat;

import java.nio.ByteBuffer;

/**
 * 编码器数据的出口（Sink）。
 *
 * <p>{@link VideoEncoder} / {@link AudioEncoder} 只负责「把画面/声音编成码流」，
 * 完全不关心码流最后落到哪里。真正的落地由 {@link Mp4Muxer} 实现本接口完成，
 * 于是：
 * <ul>
 *   <li>想做分段录制、边录边上传、写到网络流，只需换一个 Sink 实现；</li>
 *   <li>单元测试里可以塞一个内存 Sink，脱离文件系统验证编码逻辑。</li>
 * </ul>
 *
 * <p>所有回调都发生在编码线程中，实现方需要自行保证线程安全。
 */
public interface SampleSink {

    /**
     * 编码器输出格式就绪，对应 {@link MediaCodec#INFO_OUTPUT_FORMAT_CHANGED}。
     * 此时 format 里带有 csd-0/csd-1（H.264 的 SPS/PPS、AAC 的 AudioSpecificConfig），
     * 必须交给封装器登记轨道。
     */
    void onTrackFormat(Track track, MediaFormat format);

    /**
     * 一个编码后的样本（视频一帧 / 一段 AAC）。
     *
     * <p><b>注意：</b>{@code buffer} 只在回调执行期间有效，回调返回后编码器就可能复用这块内存；
     * 若需要延后处理，必须先做深拷贝（见 {@link BufferUtils#deepCopy}）。
     */
    void onSample(Track track, ByteBuffer buffer, MediaCodec.BufferInfo info);

    /** 编码线程内抛出的异常，上层据此决定是否中止整场录制。 */
    void onEncoderError(Track track, Throwable error);
}
