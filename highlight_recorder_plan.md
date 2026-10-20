# 高光录制模块 · 实现路线文档

> 用途：开发过程中的总纲。每个阶段完成并验证后，在「进度记录」里打勾并记下结论。
> 开发约定：**一个阶段验证通过前，不进入下一阶段。**

---

## 0. 背景与目标

在 BrilliantSortGame（Cocos2d-x + C++）中加入高光录制：

- 游戏运行时持续在后台编码画面与声音，只在内存中保留最近 N 秒
- 游戏逻辑触发「高光事件」时，截取事件前 `pre` 秒 + 事件后 `post` 秒
- 输出 MP4，保存到系统相册
- 录制对游戏帧率的影响尽量小

首个目标平台：Android。iOS 后续复用 C++ 层，平台层另行实现。

---

## 1. 可复用的资产

| 来源 | 文件 | 用法 |
|---|---|---|
| AndroidGameRecorder | `VideoEncoder.java` | MediaCodec 硬编，Surface 输入，排空线程。基本原样复用 |
| AndroidGameRecorder | `AudioEncoder.java` | AAC 编码。去掉回采/救援逻辑，简化音源 |
| AndroidGameRecorder | `Mp4Muxer.java` | 等轨道就绪再 start、PTS 归零、音轨降级。原样复用 |
| AndroidGameRecorder | `EncoderSelector.java` | 硬编优先 + 分辨率对齐。原样复用 |
| AndroidGameRecorder | `MediaStoreSaver.java` | 两阶段落盘到相册。原样复用 |
| AndroidGameRecorder | `BufferUtils` / `SampleSink` / `Track` | 工具类。原样复用 |
| AndroidGameRecorder | `EglRenderer.java` | 只作参考：`EGL_RECORDABLE_ANDROID`、`eglPresentationTimeANDROID`、着色器 |
| highlight-recorder | `capture_defs.h` | `EncodedPacket` / `HighlightClip` 等公共结构。复用 |
| highlight-recorder | `packet_ring_buffer.*` | 按 GOP 管理的环形缓冲。基本原样复用 |
| highlight-recorder | `highlight_recorder.*` | 事件状态机框架。复用框架，去掉 CaptureSource 和 FFmpeg 编码器 |
| highlight-recorder | `clip_composer.*` | PTS 重排逻辑复用，封装后端改成 Java `Mp4Muxer` |

不复用：AndroidGameRecorder 的 UI、`RecordingService`、悬浮窗、音频诊断；highlight-recorder 的 FFmpeg 编码器、播放器、`CaptureSource`。

---

## 2. 目标架构

> **2026-10 架构调整**：原方案环形缓冲在 C++，NAL 包需两次跨 JNI（Java→C++ 存入，C++→Java 取出）。
> 调整为环形缓冲在 Java 侧实现，省去两次 JNI 数据传输。
> C++ 仍然完全控制触发时机，通过一次无数据的 JNI 调用通知 Java 执行 snapshot。

```
┌──────────────── C++（Classes/Highlight/）───────────────────────────────┐
│                                                                         │
│  游戏逻辑 ──triggerHighlight()──▶ HighlightService（极简接口）           │
│                                        │ JNI（仅传事件名+时间戳，无包数据）│
│  Cocos EVENT_AFTER_DRAW ──▶ GameFrameCapture                            │
│      glBlitFramebuffer（GPU内拷屏缩放）  │                               │
│      eglMakeCurrent → drawFullscreenQuad│                               │
│      eglPresentationTimeANDROID         │                               │
│      eglSwapBuffers(encodeSurface) ─────┼──→ BufferQueue                │
│                                         │         ↓（GPU内，无JNI）      │
└─────────────────────────────────────────┼─────────────────────────────--┘
                                          │ JNI（仅一次，启动时传Surface）
┌──────────────── Java（highlight/）───────┼─────────────────────────────--┐
│                                         ↓                               │
│  VideoEncoder（MediaCodec消费BufferQueue）                               │
│       ↓ NAL包（不跨JNI，直接在Java内流转）                               │
│  PacketRingBuffer（Java实现，GOP环形缓冲）                               │
│       ↓ triggerHighlight JNI到达时 snapshot()                           │
│  HighlightMp4Writer（PTS重排 + Mp4Muxer封装）                           │
│       ↓                                                                 │
│  MediaStoreSaver → 相册                                                 │
└─────────────────────────────────────────────────────────────────────────┘
```

数据流原则：

1. **像素不跨 JNI**：游戏画面 GPU → GPU 进入 MediaCodec，CPU 不碰原始帧
2. **NAL 包不跨 JNI**：编码后的包在 Java 内部流转，存入环形缓冲、取出封装全在 Java 侧
3. **JNI 只有两处**：启动时 Java→C++ 传 Surface 句柄；触发时 C++→Java 传事件名，无数据传输
4. **循环缓冲存编码后数据**：720p / 4Mbps，40 秒约 20MB
5. **高光合成不重编码**：只做 PTS 重排 + 封装

职责划分：

| 层 | 职责 |
|---|---|
| C++ | 帧采集（GL/EGL）、事件判定、触发时机控制 |
| Java | 硬件编码、GOP环形缓冲、片段截取、PTS重排、MP4封装、相册落盘 |

---

## 3. 已知问题（来自现有代码审查，开发时必须处理）

### 3.1 highlight-recorder 中的问题

| # | 位置 | 问题 | 处理阶段 |
|---|---|---|---|
| H1 | `HighlightRecorder::triggerHighlight` | 占位片段已经 push 进 `clips_`，`clip_index = clips_.size() + pending_events_.size()` 会重复计数，索引错位 | 阶段四 |
| H2 | `checkPendingClips` / `triggerHighlight` | `pending_events_` 在编码回调线程和游戏线程被同时访问，没有加锁 | 阶段四 |
| H3 | `nowUs()` | 事件时间用 `steady_clock` 相对 `session_start_`，而包的 PTS 来自 MediaCodec（`System.nanoTime` 时基），两者不在同一时基，截取窗口会错位 | 阶段三统一时基 |
| H4 | `clip_composer.cpp` | `MP4Muxer::open` 的 extradata 传 `nullptr`，无 SPS/PPS 文件无法播放 | 阶段四改用 Java `MediaFormat` |
| H5 | `packet_ring_buffer.cpp` | 依赖 `utils/logger.hpp`（FFmpeg 工程的日志） | 阶段三替换为 `__android_log_print` |
| H6 | `ClipComposer` 交织排序 | 按 `dts_us` 排序；MediaCodec 输出的包没有 DTS，需要在 JNI 入口填 `dts = pts`（编码器关闭 B 帧时成立） | 阶段三/四 |

### 3.2 游戏项目中的风险点

| # | 问题 | 影响 | 处理阶段 |
|---|---|---|---|
| G1 | `AppActivity.onCreateView` 的 EGL 配置是 `5,6,5,0,16,8`（RGB565） | 编码 Surface 需要带 `EGL_RECORDABLE_ANDROID` 的配置，可能与游戏 Context 不兼容，不能直接在游戏 Context 上挂编码 Surface | 阶段二验证 |
| G2 | Cocos2d-x 3.x 默认直接画到默认帧缓冲（屏幕），**没有现成的「游戏 FBO 纹理」** | 需要自己把画面拷到纹理：`glCopyTexSubImage2D`，或 GLES3 的 `glBlitFramebuffer`，或改为先渲染到 RenderTexture | 阶段二验证 |
| G3 | 游戏 `minSdkVersion 23`，`AudioRecord.getTimestamp` 需要 API 24 | 低版本需降级时基计算 | 阶段一 |
| G4 | 游戏音频走 Cocos/RedMusic 播放，`AudioPlaybackCapture` 需要 API 29 + MediaProjection 授权 | 回采同进程声音仍需授权弹窗，与「无感录制」矛盾 | 第 6 节待确认 |
| G5 | `Cocos2dxActivity.onDestroy` 直接 `killProcess` | 退出时来不及 finalize，未合成的高光会丢 | 阶段六 |

---

## 4. 实现阶段

### 阶段一：Java 编码链路接入

**目标**：证明编码 → 封装 → 落盘在游戏工程里能跑通。与游戏渲染无关。

**做什么**

1. 新建包 `proj.android-studio/app/src/org/cocos2dx/cpp/highlight/`
2. 从 AndroidGameRecorder 搬入：`VideoEncoder`、`AudioEncoder`、`Mp4Muxer`、`EncoderSelector`、`MediaStoreSaver`、`BufferUtils`、`SampleSink`、`Track`，改包名
3. 清理依赖：
   - 去掉 `androidx.annotation.Nullable` 或在 Gradle 加 `androidx.annotation`
   - `AudioEncoder` 去掉 `MediaProjection` / 回采 / 麦克风救援，暂时只保留麦克风音源
   - `getTimestamp` 加 API 24 判断（G3）
   - 源工程是 Java 17，游戏是 Java 8，检查语法兼容
4. 新建测试入口 `HighlightSelfTest.java`：
   - 创建 `VideoEncoder`（720p / 30fps / 4Mbps / I 帧间隔 1 秒），拿到 InputSurface
   - 用 `Surface.lockCanvas` 每 33ms 画一帧不同颜色 + 帧序号
   - 录 5 秒后停止，按文档 §10 的顺序收尾
   - `MediaStoreSaver` 落盘
5. 通过 ImguiDebug 或临时调试入口触发

**验证**

- [ ] 编译通过，无新增 lint 错误
- [ ] 相册出现约 5 秒的 MP4
- [ ] `ffprobe` 显示 2 条流（video + audio）
- [ ] 画面是变化的色块和帧号，时长误差 < 0.5 秒
- [ ] logcat 中 `EncoderSelector` 选中的是硬编

**产出结论**：目标机型的编码器名称、实际分辨率、编码是否正常。

---

### 阶段二：从 Cocos 渲染管线采帧

**目标**：把测试色块换成真实游戏画面。先求正确，不求性能。

**技术验证结论（已完成，2026-10）**

| 验证项 | 结果 | 结论 |
|---|---|---|
| GL 版本 | OpenGL ES 3.2，Adreno 506 | ✅ 可用 `glBlitFramebuffer` |
| 帧缓冲绑定 | FBO=0 | ✅ Cocos 直接渲染到默认帧缓冲 |
| glCopyTexSubImage2D | OK | ✅ 备选方案也可用 |
| EGL 颜色格式 | R8G8B8A8 depth=24 | ✅ 非 RGB565，颜色精度足够 |
| EGL_RECORDABLE_ANDROID | 1 | ✅ **走方案A** |

**选定路线：方案A（同线程同 Context）**

在 Cocos GL 线程上，用游戏的 EGLContext 再建一个编码 EGLSurface。每帧渲染后：
`glBlitFramebuffer`（拷屏+缩放）→ `eglMakeCurrent` 切到编码 Surface → 画纹理 → `eglPresentationTimeANDROID` → `eglSwapBuffers` → 切回游戏 Surface。

**做什么**

1. Java 侧新建 `HighlightRecorder.java`：
   - `prepare()`：创建 `VideoEncoder`，返回 `InputSurface`
   - `start()` / `stop()`：控制编码器生命周期
   - 此阶段 `Mp4Muxer` 直写模式，先录出完整视频验证画面正确性

2. C++ 侧新建 `Classes/Highlight/GameFrameCapture.h/.cpp`：
   - `init(jobject surface)`：接收 Java 传来的 InputSurface，初始化编码用的 EGLSurface
   - 挂 `Director::EVENT_AFTER_DRAW` 钩子
   - 每帧：`glBlitFramebuffer` → `eglMakeCurrent` → 全屏四边形着色器 → `eglPresentationTimeANDROID` → `eglSwapBuffers` → 切回

3. C++ 侧新建 `Classes/Highlight/HighlightService.h/.cpp`：
   - `start()` / `stop()`：通过 JNI 驱动 Java 侧 `HighlightRecorder`
   - `triggerHighlight(eventName)`：预留接口，阶段三实现

4. 着色器：`sampler2D`（不是 `samplerExternalOES`），处理 Y 轴翻转（GL 坐标原点在左下，视频在左上）

5. 时间戳用 `clock_gettime(CLOCK_MONOTONIC)`，与 Java 的 `System.nanoTime()` 同源

**验证**

- [ ] 录出的视频是游戏画面，方向正确、颜色不偏
- [ ] 录制期间屏幕显示正常，无黑帧、闪烁、撕裂
- [ ] 记录录制前后帧率（此阶段允许有下降）
- [ ] 连续录 5 分钟无崩溃、无 GL 错误日志

**产出结论**：画面采集是否正确；Y 轴方向；帧率损失。

---

### 阶段三：C++ 环形缓冲接入

**目标**：编码产物回到 C++ 的 `PacketRingBuffer`，持续保持最近 N 秒。此阶段不截取高光。

**做什么**

1. 搬入 `Classes/Highlight/`：`capture_defs.h`、`packet_ring_buffer.*`、`highlight_recorder.*`
2. 替换日志依赖（H5）
3. `HighlightRecorder` 去掉 `CaptureSource`、FFmpeg 编码器、编码队列和编码线程，新增：
   - `pushVideoPacket(pkt)` / `pushAudioPacket(pkt)`，转发到 ring buffer
4. 新建 `Classes/Highlight/HighlightJniBridge.cpp`：
   - `nativeOnVideoPacket(ByteBuffer, offset, size, ptsUs, flags)`
   - `nativeOnVideoFormat(...)` / `nativeOnAudioFormat(...)`：保存 csd 数据，供阶段四使用
   - `nativeOnAudioPacket(ByteBuffer, offset, size, ptsUs)`
   - 用 `GetDirectBufferAddress` 直接读编码器输出缓冲，只做一次 memcpy 到 `EncodedPacket`
   - 填 `dts = pts`（H6）、`is_key_frame = flags & BUFFER_FLAG_KEY_FRAME`
5. 新建 Java `NativePacketSink implements SampleSink`，把编码器样本转给 JNI，替代 `Mp4Muxer` 作为编码器出口
6. **统一时基（H3）**：所有时间统一为 `CLOCK_MONOTONIC` 微秒。`HighlightRecorder::nowUs()` 改为读 `clock_gettime(CLOCK_MONOTONIC)`，不再相对 `session_start_`
7. 调试面板（ImguiDebug）每秒显示：GOP 数、最早/最新 PTS、视频包数、音频包数、缓冲总字节

**验证**

- [ ] GOP 数增长到 `max_gop_count` 后保持稳定（淘汰生效）
- [ ] `最新 PTS − 最早 PTS` ≈ 设定的回看时长
- [ ] 音频最早 PTS 与视频最早 PTS 相差 < 1 个 GOP（联动淘汰生效、时基一致）
- [ ] 运行 10 分钟，缓冲字节数稳定，进程内存不持续增长
- [ ] 帧率与阶段二相比无明显变化

**产出结论**：缓冲区实际内存占用；JNI 调用开销。

---

### 阶段四：高光截取与 MP4 合成

**目标**：`triggerHighlight` → 等待 post 时长 → 截取 → 输出可播放的 MP4。

**做什么**

1. 修复 H1：`clip_index` 直接取占位 push 前的 `clips_.size()`
2. 修复 H2：`pending_events_` 与 `clips_` 统一用一把锁保护
3. 搬入 `clip_composer.*`，后端替换：
   - 去掉 FFmpeg `MP4Muxer`
   - 新建 Java `HighlightMp4Writer`：`open(path)`、`addTrack(video/audio MediaFormat)`、`writeSample(track, data, ptsUs, flags)`、`close()`
   - 内部复用 `Mp4Muxer`
4. 修复 H4：`MediaFormat` 用阶段三保存的 csd（视频 `csd-0/csd-1` 即 SPS/PPS，音频 `csd-0`）重建
5. 合成放在独立线程，不阻塞游戏线程和编码线程
6. 合成完成后 `MediaStoreSaver` 落盘，结果通过 JNI 回调 C++
7. 新增 `nativeTriggerHighlight(eventName)`，调试面板加「触发高光」按钮

**片段策略（先按单片段做）**

- 每次触发独立输出一个 MP4（最直观、最易验证）
- 多片段合并成一个 MP4 作为扩展，使用 `ClipComposer` 的 rebase 能力，在验证单片段后再做

**验证**

- [ ] 触发后约 `post` 秒，相册出现新 MP4
- [ ] 时长 ≈ `pre + post`，误差 ≤ 1 个 GOP（起点向前对齐到 IDR）
- [ ] `ffprobe`：2 条流，首帧为关键帧，PTS 从 0 开始
- [ ] 开头无花屏，拖动进度条正常
- [ ] 内容确实是触发时刻前后的画面（触发时在屏幕上做个明显动作对照）
- [ ] 连续快速触发两次，两个片段不重叠、不崩溃
- [ ] 合成期间游戏不卡顿

**产出结论**：截取精度；合成耗时；多片段策略最终取舍。

---

### 阶段五：性能优化

**目标**：录制对游戏帧率的影响降到可忽略。

**做什么**（按阶段二的测量结果，按需选做）

1. 编码分辨率与帧率解耦：游戏 60fps 时只每隔一帧送编码器
2. 拷屏优化：GLES3 设备用 `glBlitFramebuffer` 直接缩放；或改为游戏先渲染到 RenderTexture，避免回读默认帧缓冲
3. 若阶段二用的是方案 A 且游戏线程开销明显，切换到方案 B
4. JNI 包传递：复用缓冲，避免每帧分配
5. 低端机降级：480p / 24fps / 2Mbps

**验证**

- [ ] 中端机录制开启/关闭帧率差 ≤ 3fps
- [ ] 低端机降级参数下帧率差 ≤ 5fps
- [ ] 30 分钟连续运行无发热降频导致的明显掉帧（与不录制对比）

---

### 阶段六：游戏集成与生命周期

**目标**：真实游戏事件触发高光，生命周期各环节不出错。

**做什么**

1. 定义 C++ 对外接口 `HighlightService`：`start()`、`pause()`、`resume()`、`trigger(event)`、`finalize(callback)`
2. 游戏逻辑接入触发点（关卡完成、连击等，具体事件待产品确认）
3. 生命周期：
   - `AppActivity.onPause` → 暂停采集，已触发未完成的片段立即截断合成
   - `onResume` → 恢复；EGL Surface 重建后重新挂编码 Surface
   - 退出前主动 finalize，规避 G5
4. 编码器异常（被其他应用占用、机型不支持）时整个模块静默关闭，不影响游戏
5. 开关：远程配置或设置项可关闭录制

**验证**

- [ ] 真实通关后产生高光，内容正确
- [ ] 切后台、锁屏、来电后返回，录制恢复，无崩溃
- [ ] 横竖屏、分辨率不同的机型都能正常录
- [ ] 关闭开关后完全不创建编码器
- [ ] 编码器创建失败时游戏正常运行

---

## 5. 关键参数（初始值，按验证结果调整）

| 参数 | 值 | 说明 |
|---|---|---|
| 编码分辨率 | 720×1280（竖屏） | 游戏固定竖屏 |
| 帧率 | 30fps | |
| 视频码率 | 4 Mbps | H.264 |
| I 帧间隔 | 1 秒 | 决定截取起点精度，越小越准、体积越大 |
| B 帧 | 关闭 | 保证 dts = pts |
| 音频 | AAC-LC 44.1kHz 立体声 128kbps | |
| pre / post | 10 秒 / 5 秒 | |
| max_gop_count | 20 | I 帧间隔 1 秒时约 20 秒回看，需 ≥ pre + 余量 |

---

## 6. 待确认问题

| # | 问题 | 影响 | 状态 |
|---|---|---|---|
| Q1 | 音源：游戏内音效还是麦克风？同进程回采仍需 MediaProjection 授权弹窗（G4）。备选：在游戏音频引擎层直接取 PCM 混音 | 阶段一音频方案、是否需要授权 | 待确认 |
| Q2 | Cocos2d-x 具体版本、GLES 版本 | 阶段二拷屏方式 | 阶段二开头自查 |
| Q3 | 录制是游戏启动即开始，还是用户开启 | 阶段六开关设计 | 待确认 |
| Q4 | 多个高光：每个一个文件，还是一局合并成一个 | 阶段四片段策略 | 待确认（先做单片段） |
| Q5 | 哪些游戏事件触发高光 | 阶段六 | 待产品确认 |
| Q6 | 高光是否需要裁剪 UI（如广告、调试面板） | 阶段二拷屏时机 | 待确认 |

---

## 7. 进度记录

| 阶段 | 状态 | 日期 | 结论 / 备注 |
|---|---|---|---|
| 一 Java 编码链路 | ✅ 完成 | 2026-10 | 相册出现 5s 色块视频，硬编生效，链路正常 |
| 二 Cocos 采帧 | 进行中 | 2026-10 | 技术验证完成，方案A，开始实现 |
| 三 Java 环形缓冲 | 未开始 | | 原 C++ 环形缓冲改为 Java 实现，省去两次 JNI |
| 四 高光截取合成 | 未开始 | | |
| 五 性能优化 | 未开始 | | |
| 六 游戏集成 | 未开始 | | |

---

## 8. 相关文件索引

| 文件 | 说明 |
|---|---|
| `AndroidGameRecorder/AndroidGameRecorder技术文档.md` | 录屏项目文档，编码/封装/停止顺序的坑都在这里 |
| `AndroidGameRecorder/AndroidGameRecorder/app/src/main/java/com/stu/gamerecorder/record/` | Java 编码内核源码 |
| `highlight-recorder/info2.txt` | 高光模块状态说明 |
| `highlight-recorder/src/recorder/` | 环形缓冲与事件状态机 |
| `highlight-recorder/src/muxer/clip_composer.*` | 片段 PTS 重排 |
| `proj.android-studio/app/src/org/cocos2dx/cpp/AppActivity.java` | 游戏 Activity、EGL 配置 |
| `proj.android-studio/app/jni/hellocpp/main.cpp` | 现有 JNI 入口 |
| `Classes/AppDelegate.cpp` | 游戏启动与生命周期 |
