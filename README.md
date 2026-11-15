# SimpleRecorder

一个 **Minecraft 26.3 / Fabric 客户端模组**：按 **F8** 开始或结束录屏，默认导出 **动画 AVIF**，并以 **GIF** 作为兼容性兜底。默认 **30 FPS**、画面长宽各缩到 **50%**。

- Minecraft：**26.3**（26.x 线，2026-09-15 发布）
- Fabric Loader：**0.19.5**（`>=0.19.5`）
- Fabric API：**0.161.0+26.3**（`>=0.160.5`）
- 环境：**仅客户端**，不需要装到服务器
- 构建：Fabric Loom **1.17.21** / Java **25** / Gradle **9.5.1**
- MODID：`simplerecorder`

---

## 26.3 的渲染后端：OpenGL 还是 Vulkan

这是 26.x 相比 1.21.x 最大的差别，也是这个移植版的重点。

26.3 不再只有 OpenGL：游戏通过 `com.mojang.renderpearl` 这一层 GPU 抽象与显卡打交道，下面同时带 **OpenGL** 和 **Vulkan** 两套实现：

| 项目 | 说明 |
|---|---|
| 默认后端 | **OpenGL**（`options.txt` 里 `preferredGraphicsBackend:"default"` 时先试 OpenGL） |
| 切换方式（游戏内） | 选项 → 视频设置 → 图形 API（`preferredGraphicsBackend`） |
| 切换方式（启动参数） | `--graphicsApi vulkan` / `--graphicsApi opengl`，强制后游戏内设置不再生效 |
| 驱动信息 | 启动日志里 `Using graphics backend OpenGL|Vulkan, using drivers: ...` |
| 模组内查看 | 第一次开始录制时聊天栏会打出 `GPU backend: Vulkan (显卡型号)` |

**本模组不依赖具体后端**：抓帧全部走 `GpuDevice` / `CommandEncoder`（`copyTextureToBuffer` + `GpuBuffer` 映射），与官方 F2 截图用的是同一条路径，所以 OpenGL 和 Vulkan 上跑的是同一份代码。

这里没有直接调用 `org.lwjgl.opengl.*`。裸 GL 调用只在 OpenGL 后端存在，在 Vulkan 后端上会直接崩，因此 1.21.x 版本里那套 `glReadPixels` + PBO + fence 的抓帧方式在本移植版被整体替换。

实测（2026-11）：RTX 5070 Ti Laptop，OpenGL 3.3 与 Vulkan 1.4.357 两个后端都能正常录制，导出 GIF 与动画 AVIF。

### 抓帧：时机与开销

`Minecraft#renderFrame` 在最后 `submit()` + `present()` 之后，主渲染目标里是一帧完整的画面。模组用 Mixin 在这个点抓帧，**不阻塞渲染线程**：

- 每个槽位的 `GpuBuffer` 在分配时**一次性持久映射**（`USAGE_MAP_READ | COPY_DST | HINT_CLIENT_STORAGE`），之后每帧不再调用 `map()`；
- 渲染线程每帧只做三件事：`createCommandEncoder`（约 1 µs）、`copyTextureToBuffer`（约 70 µs）、`submit`（约 150 µs）；
- 拷贝完成回调置位后，槽位交给 Worker 线程，**直接从映射内存里做翻转 + 缩放**，渲染线程既不 memcpy 也不等 GPU；
- 3 个槽位轮转，全忙就丢这一帧（计入 dropped），**永不阻塞**。

实测（854×480 窗口，Vulkan，30 FPS 录制，14 秒）：

```
render-thread capture avg 216 us/call (encoder 1, record 70, submit 143)
  over 450 calls, 450 frames copied, 0 skipped
recording: 59.7 FPS
```

上一版实现是每帧 `map()` + 在渲染线程上 memcpy 整帧，实测约 **1.9 ms/帧**；现在是 **约 0.2 ms/帧**，并且渲染线程完全不碰像素数据。

### 录制指示器为什么默认只在暂停时显示

26.3 把世界渲染、后处理、GUI 全部画进**同一个目标**，而这个目标就是我们读回的那张纹理；模组没有「在这帧 GUI 画完之前插入一次快照」的入口（OpenGL 后端还能插入即时命令，Vulkan 后端不行）。所以只要在录制中把 `REC` 角标画进屏幕，它就一定会出现在输出文件里。

默认行为：

- **录制中不画角标**（状态靠聊天栏提示）；
- **暂停时画 `PAUSED mm:ss`**——暂停期间不抓帧，画什么都安全；
- 想恢复常驻角标可以设 `output.indicatorInRecording=true`，代价是角标会进画面。

录制内容本身仍然包含完整 HUD（血条、物品栏、Jade/WTHIT 等），因为抓的就是合成后的帧。

---

## 功能

- **F8 一键开始 / 结束**，按键可以在「选项 → 控制 → 按键绑定 → SimpleRecorder」里改。
- **F9 打开游戏内设置**。
- **F10 暂停 / 继续录制**：暂停期间完全不抓帧，恢复后视频从暂停处接着播（暂停时长不会留在文件里）；暂停状态下按 F8 直接结束并导出。
- 默认 **AVIF** 动画，**30 FPS**、**50% 宽度 × 50% 高度**、无限循环。
- AVIF 不可用时自动回退 **GIF**，并在聊天栏说明原因。
- 配置支持热重载：直接编辑 `config/simplerecorder.properties`，下一次录制立即生效。
- 录制中不画角标（状态靠聊天栏提示），暂停时显示 `PAUSED mm:ss`；可用 `output.indicatorInRecording` 换回常驻角标（会进画面）。
- 高负载设置警告：估算像素吞吐过高时在聊天栏提示可能掉帧。
- 异步 GPU 回读：3 个持久映射的 GPU buffer 轮转，渲染线程每帧只花约 0.2 ms 提交拷贝，缩放和编码在后台线程。
- 首次启动自动下载官方 `avifenc`（最长 1 分钟），之后缓存复用；不把第三方二进制打进 jar。
- 编码队列满时丢新帧，不会卡住游戏。
- 窗口尺寸变化、退出世界时自动停止并导出。
- 输出完成后聊天栏显示可点击的文件路径。

---

## 安装

1. 安装 **Minecraft 26.3 + Fabric Loader 0.19.5 或更高**。
2. 把 `simplerecorder-26.3-1.0.0.jar` 和 **Fabric API** 放进客户端的 `mods/` 文件夹。
3. 启动游戏。**服务器不需要安装**。

> 这是纯客户端模组，不添加方块、物品、网络包，也不会影响服务器注册表。装到服务器上也能加载，只是什么都不会做。

### AVIF 编码器

模组不把 `avifenc` 打进 jar，而是启动时检查本机是否已有可用编码器；没有就异步下载官方 libavif 发行版，最长等待 **1 分钟**。缓存位置：

```
config/simplerecorder/avif/<platform>/avifenc[.exe]
```

下载失败、平台没有官方构建，或第一次录制时还没下载完，录制会自动回退 **GIF**，聊天栏会说明原因。

---

## 使用

1. 进入世界。
2. 按 **F8** 开始录制，右上角出现 `● REC 00:00`。
3. 再按 **F8** 停止。聊天栏提示正在导出，完成后显示路径。
4. 文件在游戏目录下的 **`screenshots/`**：
   - `simplerecorder-2026-11-01_00-00-00.avif`
   - 回退时：`simplerecorder-2026-11-01_00-00-00.gif`

暂停时按 **F8** 结束录制并导出（不会续录）；按 **F10** 才会继续录制。

---

## 设置

按 **F9** 打开设置界面：帧率、缩放、AVIF 质量、AVIF 速度、CPU 线程数都是**滑块**（拖动即可），输出格式 / 循环 / 转换方式（CPU、GPU）/ 首选后端 / 角标是点击切换的按钮。也可以直接编辑 `config/simplerecorder.properties`（改完下一次录制生效）：

```properties
output.format=AVIF            # AVIF 或 GIF
output.fps=30                 # 5-60
output.scale=0.5              # 0.1-1.0
output.loop=true
output.warnHighSettings=true
avif.quality=60               # 20-100
avif.speed=8                  # 5-10
avif.convert=CPU              # CPU 或 GPU，见下
avif.cpuThreads=4             # 1-本机逻辑 CPU 数
avif.encoderPath=             # 手动指定 avifenc
avif.downloadUrl=             # 自定义下载地址
capture.backend=auto          # auto / opengl / vulkan，仅用于核对与提示
output.indicatorInRecording=false  # true 时录制中也画 REC 角标（会进画面）
```

- `avif.convert` 只有两个取值：`CPU`（多线程转换，默认）和 `GPU`。选 `GPU` 时具体用哪个图形 API 由**运行中的渲染后端**决定（也就是设置界面里「首选渲染后端」那一项显示的值），模组不再单独提供 OPENGL 这种写法。
  当前 26.3 版本**尚未实现** GPU 转换路径（需要自建 `RenderPipeline` + 着色器，且要在 OpenGL 与 Vulkan 两个后端上分别验证），选 `GPU` 会在聊天栏提示一次并回退 CPU。
- 旧配置里的 `avif.conversion`（`MULTITHREAD` / `OPENGL`）会自动迁移到 `avif.convert=CPU`。
- `capture.backend` **不会**在运行时切换后端（后端在启动时由游戏决定），填了和实际不符时只会提示一次；抓帧本身对两种后端都适用。

### 已知限制

- 26.3 上没有 GPU 着色器 YUV 转换，YUV 一律在 CPU 多线程完成；默认 4 线程，1080p 30 FPS 下 CPU 占用可控。
- 只支持 26.3（`fabric.mod.json` 里 `minecraft: "~26.3"`），26.2 及更早需要另外的分支。
- 26.1 起 Minecraft 不再混淆，本工程直接用官方类名，没有 yarn 映射。

---

## 从源码构建

需要 **JDK 25**（26.3 的最低 Java 版本）。

```bash
./gradlew build
# 产物：build/libs/simplerecorder-26.3-1.0.0.jar
```

本地测试：

```bash
./gradlew runClient
```

想在开发环境里强制试 Vulkan：

```bash
./gradlew runClient --args="--graphicsApi vulkan"
```

或者改 `run/options.txt` 里的 `preferredGraphicsBackend:"vulkan"`。

---

## 目录结构

```
src/main/java/com/lisheng/simplerecorder/
├── SimpleRecorder.java            纯客户端模组，公共入口只打日志
├── client/
│   ├── SimpleRecorderClient.java  键位、HUD 指示器、每帧钩子
│   ├── RecordingController.java   录制状态机（含暂停、导出、聊天反馈）
│   ├── FrameCapture.java          GPU 回读（后端无关，GpuBuffer 环）
│   ├── GpuCapabilities.java       后端探测与诊断
│   ├── RecorderConfigScreen.java  F9 设置界面
│   ├── FrameScaler.java           缩放
│   └── ScaledFrame / FramePool / YuvPool
├── mixin/
│   └── MinecraftFrameCaptureMixin.java  在 renderFrame 结束后抓帧
├── avif/                          AVIF 编码与 avifenc 管理
├── gif/                           GIF 编码
├── encode/                        编码器接口
└── config/                        配置文件读写
```

---

## 许可

GPL-3.0。第三方组件见 `THIRD_PARTY_NOTICES.md`。
