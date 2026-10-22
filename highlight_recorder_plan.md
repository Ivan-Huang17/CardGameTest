# 高光录制模块 · 实现路线文档

> 用途：开发过程中的总纲。每个阶段完成并验证后，在「进度记录」里打勾并记下结论。
> 开发约定：**一个阶段验证通过前，不进入下一阶段。**

---

## 0. 背景与目标

在 CardGameTest（PokerGame，Cocos2d-x 3.17 + C++）中加入高光录制：

- 游戏运行时持续在后台编码画面，只在内存中保留最近 N 秒
- 游戏逻辑触发「高光事件」时，截取事件前 `pre` 秒 + 事件后 `post` 秒
- 输出 MP4，保存到系统相册
- 录制对游戏帧率的影响尽量小

测试机：Meizu M6 Note，Android 7.1.2（API 25）。
首个目标平台：Android。本项目结构简单（单场景、无复杂渲染管线），
实现只服务于验证高光录制这一目标，不追求通用框架。

---

## 1. 可复用的参考资产

以下资产来自另外两个独立项目，仅供参考代码思路，**不直接拷贝依赖**：

| 参考来源 | 参考内容 | 本项目的处理方式 |
|---|---|---|
| AndroidGameRecorder（参考项目） | MediaCodec 硬编码、Surface 输入、PTS 归零、Mp4Muxer 安全封装、编码器分辨率对齐 | 按思路用 Java 重新实现，已完成（阶段一） |
| AndroidGameRecorder（参考项目） | `EglRenderer`：`EGL_RECORDABLE_ANDROID`、`eglPresentationTimeANDROID`、着色器写法 | 按思路移植到 C++ 侧 `GameFrameCapture`（阶段二） |
| highlight-recorder（参考项目） | GOP 环形缓冲设计思路（按关键帧分组、淘汰策略）、事件前后截取的状态机思路 | 只参考设计思路，**不复用 C++/FFmpeg 代码**，改用 Java 实现（阶段三） |

不复用任何参考项目的 UI、Service、FFmpeg 编码器、CaptureSource 等重量级组件。
本项目的实现全部基于 Android 原生 `MediaCodec` / `MediaMuxer` API，不引入 FFmpeg。

---

## 2. 目标架构

C++ 侧只负责游戏画面采集和触发时机控制；Java 侧负责编码、环形缓冲、截取、封装、落盘。
环形缓冲放在 Java 实现（而不是 C++），JNI 只在两处发生，且都不传输像素或编码后的包数据：

```
┌──────────────── C++（Classes/Highlight/）───────────────────────────────┐
│                                                                         │
│  游戏逻辑 ──triggerHighlight()──▶ HighlightService（极简接口）           │
│                                        │ JNI（仅传事件名，无包数据）     │
│  Cocos EVENT_AFTER_DRAW ──▶ GameFrameCapture                           │
│      glFinish() 等待渲染完成             │                              │
│      glBlitFramebuffer（GPU内拷屏缩放）  │                               │
│      eglMakeCurrent → 画全屏四边形       │                               │
│      eglPresentationTimeANDROID         │                               │
│      eglSwapBuffers(encodeSurface) ─────┼──→ BufferQueue                │
│      eglMakeCurrent 切回游戏 Surface     │         ↓（GPU内，无JNI）      │
└─────────────────────────────────────────┼─────────────────────────────--┘
                                          │ JNI（仅一次，启动时传Surface）
┌──────────────── Java（highlight/）───────┼─────────────────────────────--┐
│                                         ↓                               │
│  VideoEncoder（MediaCodec消费BufferQueue）                               │
│       ↓ NAL包（不跨JNI，直接在Java内流转）                               │
│  PacketRingBuffer（Java实现，GOP环形缓冲，阶段三）                        │
│       ↓ triggerHighlight JNI到达时 snapshot()                           │
│  HighlightMp4Writer（PTS重排 + Mp4Muxer封装，阶段四）                    │
│       ↓                                                                 │
│  MediaStoreSaver → 相册                                                 │
└─────────────────────────────────────────────────────────────────────────┘
```

数据流原则：

1. **像素不跨 JNI**：游戏画面在 GPU 内部直接进入 MediaCodec 的 BufferQueue，CPU 不碰原始帧
2. **NAL 包不跨 JNI**：编码后的包在 Java 内部流转，存入环形缓冲、取出封装全在 Java 侧
3. **JNI 只有两处**：启动时 Java→C++ 传 Surface 句柄（一次）；触发高光时 C++→Java 传事件名（无数据传输）
4. **循环缓冲存编码后数据**：720×1280 / 4Mbps，预计 20 秒缓冲约 10MB
5. **高光合成不重编码**：只做 PTS 重排 + 封装，不过编码器

职责划分：

| 层 | 职责 |
|---|---|
| C++（`Classes/Highlight/`） | 帧采集（GL/EGL）、游戏事件判定、触发时机控制 |
| Java（`proj.android/app/src/org/cocos2dx/cpp/highlight/`） | 硬件编码、GOP环形缓冲、片段截取、PTS重排、MP4封装、相册落盘 |

---

## 3. 实现阶段

### 阶段一：Java 编码链路（已完成）

**目标**：证明编码 → 封装 → 落盘在本项目工程里能跑通。与游戏渲染无关。

**完成情况**

已在 `proj.android/app/src/org/cocos2dx/cpp/highlight/` 建好以下文件：

- `VideoEncoder.java`：MediaCodec 硬编码，Surface 输入，独立排空线程，EOS 正确处理
- `AudioEncoder.java`：麦克风 PCM → AAC（当前阶段未启用，预留）
- `Mp4Muxer.java`：轨道就绪才 start、PTS 归零、音轨降级、线程安全写入
- `EncoderSelector.java`：硬编优先、分辨率对齐校正
- `MediaStoreSaver.java`：两阶段落盘到相册（先写私有目录再发布）
- `BufferUtils` / `SampleSink` / `Track`：工具类
- `HighlightSelfTest.java`：自测入口，用 Canvas 画变化色块验证链路

**验证结果（已通过）**

- [x] 相册出现 5 秒色块 MP4
- [x] 画面是变化的色块和帧号
- [x] logcat 中 `EncoderSelector` 选中硬编（`OMX.qcom.video.encoder.avc`）

---

### 阶段二：从 Cocos 渲染管线采帧（进行中）

**目标**：把测试色块换成真实游戏画面。

**技术验证结论（已完成）**

在测试机（Meizu M6 Note，Adreno 506）上验证：

| 验证项 | 结果 | 结论 |
|---|---|---|
| GL 版本 | OpenGL ES 3.2 | ✅ 可用 `glBlitFramebuffer` |
| 帧缓冲绑定 | FBO=0 | ✅ Cocos 直接渲染到默认帧缓冲 |
| EGL 颜色格式 | R8G8B8A8 depth=24 | ✅ 非 RGB565，颜色精度足够 |
| EGL_RECORDABLE_ANDROID | 1 | ✅ 走方案A（同线程同 Context） |

**选定路线：方案A**

在 Cocos GL 线程上，用游戏的 EGLContext 再建一个编码 EGLSurface。每帧渲染后：

```
glFinish()（等待 GPU 渲染完成）
  → glBlitFramebuffer（默认FBO → 中间纹理FBO，拷屏+缩放）
  → eglMakeCurrent 切到编码 Surface
  → 画全屏四边形（采样中间纹理）
  → eglPresentationTimeANDROID 打时间戳
  → eglSwapBuffers(encodeSurface) 提交给 MediaCodec
  → eglMakeCurrent 切回游戏 Surface（每帧重新取，不用缓存值）
```

**已完成的实现**

1. Java `HighlightRecorder.java`：`prepare()` 创建 VideoEncoder 返回 InputSurface；
   `start()`/`stop()` 控制编码器生命周期；当前为直写 MP4 模式（验证画面用）
2. C++ `Classes/Highlight/GameFrameCapture.hpp/.cpp`：`init()` 接收 InputSurface 建 EGLSurface，
   `startCapture()`/`stopCapture()` 挂卸 `Director::EVENT_AFTER_DRAW` 钩子，
   `onAfterDraw()` 执行上述 GL/EGL 流程
3. C++ `Classes/Highlight/HighlightService.hpp/.cpp`：JNI 桥接层，
   `onSurfaceReady()` / `start()` / `stop()` 对接 Java 侧
4. 用函数指针 `eglGetProcAddress("glBlitFramebuffer")` 在运行时取函数地址，
   绕开 Cocos `CCGL.h` 与 GLES3 头文件的 OES 宏冲突问题
5. CMake 侧把 `CMAKE_CXX_STANDARD` 从 11 提到 17（原值由 cocos2d 引擎的
   `CocosConfigDefine.cmake` 强制设置，覆盖了项目侧配置）

**调试中发现并修复的问题**

| 问题 | 原因 | 修复 |
|---|---|---|
| `eglSwapBuffers` 报 `EGL_BAD_SURFACE (0x300D)` | Java 侧 `codec.start()` 还没执行，InputSurface 的 BufferQueue 未激活，C++ 侧过早开始送帧 | 调整启动顺序：先 `highlightRecorder.start()`（激活 Surface）再 `nativeStart()`（挂钩子送帧） |
| 游戏画面黑屏，录制内容也全黑 | `gameSurface_` 只在 `init()` 时取了一次并缓存；Cocos 内部的 EGLSurface 在运行中可能变化，切回时用了失效的旧值，导致游戏渲染目标错误 | 改为每帧在 `onAfterDraw()` 内重新调用 `eglGetCurrentSurface(EGL_DRAW)`，不缓存 |
| 视频画面上下颠倒 | 着色器里多做了一次 Y 轴翻转，与 `glBlitFramebuffer` 的坐标方向重复抵消成颠倒 | 去掉着色器里的 `1.0 - v`，保留原始 UV |

**验证**

- [ ] 录出的视频是游戏画面，方向正确、颜色不偏
- [ ] 录制期间屏幕显示正常，无黑屏
- [ ] 连续录制若干分钟无崩溃、无 GL 错误日志

**产出结论**：（录制联调通过后补充）

---

### 阶段三：Java 环形缓冲

**目标**：编码产物进入 Java 的 `PacketRingBuffer`，持续保持最近 N 秒。此阶段不截取高光。

**做什么**

1. 新建 `PacketRingBuffer.java`：
   - 按 GOP（关键帧分组）管理视频包，一个 IDR 帧开启一个新 GOP 组
   - 容量上限为 `maxGopCount`，超出淘汰最旧的 GOP
   - `pushVideo(ByteBuffer data, long ptsUs, boolean isKeyFrame)`
   - `snapshot(long startUs, long endUs)`：截取并向前对齐到 IDR 帧，返回 `HighlightClip`
2. `VideoEncoder` 的排空线程改为把样本喂给 `PacketRingBuffer`，而不是直接交给 `Mp4Muxer`
3. 调试：定时打印 GOP 数、最早/最新 PTS、缓冲总字节，确认淘汰策略生效

**验证**

- [ ] GOP 数增长到 `maxGopCount` 后保持稳定（淘汰生效）
- [ ] `最新 PTS − 最早 PTS` ≈ 设定的回看时长
- [ ] 运行若干分钟，缓冲字节数稳定，内存不持续增长

**产出结论**：缓冲区实际内存占用。

---

### 阶段四：高光截取与 MP4 合成

**目标**：`HighlightService::triggerHighlight()` → C++ 通过 JNI 通知 Java →
等待 post 时长 → `PacketRingBuffer.snapshot()` → 输出可播放的 MP4。

**做什么**

1. C++ `HighlightService::triggerHighlight(eventName)` 通过 JNI 调用 Java 方法，
   只传事件名和当前时间戳，不传任何包数据
2. Java 新建事件状态机（可以是 `HighlightRecorder` 内部的简单逻辑，不需要单独类）：
   - 记录触发时刻
   - 等待 `postSeconds` 后从 `PacketRingBuffer.snapshot(trigger-pre, trigger+post)` 截取片段
3. 新建 `HighlightMp4Writer.java`：
   - 内部持有独立的 `Mp4Muxer` 实例
   - 用编码时保存的 `MediaFormat`（含 csd-0/csd-1，即 SPS/PPS）新建轨道
   - 把 `HighlightClip` 里的包按 PTS 顺序写入，PTS 从片段起点归零
4. 合成放在独立线程，不阻塞游戏线程和编码线程
5. 合成完成后 `MediaStoreSaver` 落盘

**片段策略**：每次触发独立输出一个 MP4（先按最简单的方式验证，不做多片段合并）

**验证**

- [ ] 触发后约 `post` 秒，相册出现新 MP4
- [ ] 时长 ≈ `pre + post`，误差 ≤ 1 个 GOP
- [ ] 首帧为关键帧，播放流畅、拖动进度条正常
- [ ] 内容确实是触发时刻前后的画面
- [ ] 连续快速触发两次，两个片段不重叠、不崩溃

**产出结论**：截取精度；合成耗时。

---

### 阶段五：性能优化

**目标**：录制对游戏帧率的影响降到可忽略。

**做什么**（按阶段二的测量结果，按需选做）

1. 若编码帧率需要低于游戏帧率，在 `onAfterDraw` 里做帧率节流（跳帧）
2. JNI 调用开销评估（本方案 JNI 调用频率很低，预计不是瓶颈）
3. 低端机降级：更低分辨率/帧率/码率

**验证**

- [ ] 录制开启/关闭帧率差在可接受范围内
- [ ] 长时间连续运行无明显掉帧

---

### 阶段六：游戏集成与生命周期

**目标**：真实游戏事件触发高光，生命周期各环节不出错。

**做什么**

1. 游戏逻辑接入触发点（具体事件待产品确认，参考：牌局胜利、连续操作等）
2. 生命周期处理：
   - `AppActivity.onPause` → 暂停采集
   - `onResume` → 恢复，重新挂编码 Surface（Cocos Surface 可能已重建）
   - 退出前主动停止并 flush，避免未完成的高光丢失
3. 编码器异常或设备不支持时整个模块静默关闭，不影响游戏主流程
4. 开关：可配置关闭录制功能

**验证**

- [ ] 真实游戏事件触发后产生正确的高光片段
- [ ] 切后台、锁屏后返回，录制恢复，无崩溃
- [ ] 关闭开关后完全不创建编码器
- [ ] 编码器创建失败时游戏正常运行

---

## 4. 关键参数（初始值，按验证结果调整）

| 参数 | 值 | 说明 |
|---|---|---|
| 编码分辨率 | 720×1280（竖屏） | 游戏固定竖屏 |
| 帧率 | 30fps | |
| 视频码率 | 4 Mbps | H.264 |
| I 帧间隔 | 1 秒 | 决定截取起点精度，越小越准、体积越大 |
| pre / post | 待定（参考 10 秒 / 5 秒） | 阶段四确定 |
| maxGopCount | 待定（参考 20，约 20 秒回看） | 需 ≥ pre + 余量，阶段三确定 |

---

## 5. 待确认问题

| # | 问题 | 影响 | 状态 |
|---|---|---|---|
| Q1 | 是否需要音频（麦克风或游戏内音效） | 阶段一/四音频方案 | 待确认，当前阶段未启用音频 |
| Q3 | 录制是游戏启动即开始，还是用户手动开启 | 阶段六开关设计 | 待确认 |
| Q4 | 多个高光：每个一个文件，还是一局合并成一个 | 阶段四片段策略 | 待确认（先做单片段） |
| Q5 | 哪些游戏事件触发高光 | 阶段六 | 待产品确认 |

---

## 6. 进度记录

| 阶段 | 状态 | 日期 | 结论 / 备注 |
|---|---|---|---|
| 一 Java 编码链路 | ✅ 完成 | 2026-10 | 相册出现 5s 色块视频，硬编生效，链路正常 |
| 二 Cocos 采帧 | 调试中 | 2026-10 | 架构已搭好，正在排查黑屏/全黑录制问题 |
| 三 Java 环形缓冲 | 未开始 | | |
| 四 高光截取合成 | 未开始 | | |
| 五 性能优化 | 未开始 | | |
| 六 游戏集成 | 未开始 | | |

---

## 7. 相关文件索引（本项目内）

| 文件 | 说明 |
|---|---|
| `Classes/Highlight/GameFrameCapture.hpp/.cpp` | GL/EGL 采帧核心，方案A实现 |
| `Classes/Highlight/HighlightService.hpp/.cpp` | C++ 对外接口 + JNI 桥接 |
| `Classes/Highlight/Phase2Verifier.hpp/.cpp` | 阶段二技术验证工具（GLES版本/EGL配置查询），验证完成后可删除 |
| `proj.android/app/src/org/cocos2dx/cpp/highlight/` | Java 侧编码、封装、落盘实现 |
| `proj.android/app/src/org/cocos2dx/cpp/AppActivity.java` | 游戏 Activity，录制启停入口、权限申请 |
| `CMakeLists.txt` | 根目录构建配置，Highlight 模块源文件在此注册 |
