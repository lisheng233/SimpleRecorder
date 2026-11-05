# SimpleRecorder

一个 **Minecraft 1.12.2 / Forge 客户端模组**：按 **F8** 开始或结束录屏，默认导出 **动画 AVIF**，并以 **GIF** 作为兼容性兜底。默认 **30 FPS**、画面长宽各缩到 **50%**。

- Minecraft：**1.12.2**
- Forge：构建用 **14.23.5.2855**（产物在 2855 / 2860 客户端都能加载）
- 环境：**仅客户端**，不需要装到服务器
- 构建：ForgeGradle 3.0.197 / Java 8 / Gradle 4.9
- MODID：`simplerecorder`（Forge 的 modId 只允许小写，显示名是 `SimpleRecorder`）

---

## 功能

- **F8 一键开始 / 结束**，按键可以在「选项 → 控制 → 按键绑定」里改。
- **F9 打开游戏内设置**；「模组列表 → SimpleRecorder → Config」按钮打开的也是同一个界面。
- **F10 暂停 / 继续录制**：暂停期间完全不抓帧，恢复后视频从暂停处接着播（暂停时长不会留在文件里）；**暂停状态下按 F8 直接结束并导出**。
- 默认 **AVIF** 动画，**30 FPS**、**50% 宽度 × 50% 高度**、无限循环。
- AVIF 不可用时自动回退 **GIF**，并在聊天栏说明原因。
- 配置支持 **热重载**：直接编辑 `.cfg` 文件后，下一次录制立即使用新值，不用重启。
- 录制时右上角显示红点 + `REC mm:ss`，这个指示器**不会**出现在输出文件里。
- **Jade/WTHIT 等 HUD 信息**：抓帧在 `RenderGameOverlayEvent.Post`（`ElementType.ALL`）的 `LOWEST` 优先级执行，先等 Jade 等 NORMAL 优先级 overlay 画完；录像会包含方块/实体信息。
- **高负载设置警告**：估算像素吞吐过高时在聊天栏提示可能掉帧。
- **异步 PBO 抓帧**：3 个 PBO 轮转 + `GL32` fence，渲染线程不做同步回读、不创建 `NativeImage`；缩放和编码在后台线程。
- **RGB→YUV 默认 OpenGL 着色器转换**（GLSL 120 + 立即模式全屏四边形，兼容 1.12.2 的 OpenGL 2.1 下限）；不可用时回退多线程 CPU 转换（默认 4 线程，上限本机逻辑 CPU 数）。
- 首次启动自动下载官方 `avifenc`（最长 1 分钟），之后缓存复用；不把第三方二进制打进 jar。
- 编码队列满时丢新帧，不会卡住游戏。
- AVIF 使用配置帧率作为时间基；掉帧时重复上一帧，保持真实时长。
- 窗口尺寸变化、退出世界时自动停止并导出。
- 输出完成后聊天栏显示可点击的文件路径。

---

## 安装

1. 安装 **Minecraft 1.12.2 + Forge 14.23.5.2855 或更高**（2860 也可以）。
2. 把 `simplerecorder-1.12.2-0.1.0.jar` 放进客户端的 `mods/` 文件夹。
3. 启动游戏。**服务器不需要安装**。

> 这是纯客户端模组，不添加方块、物品、网络包，也不会影响服务器注册表。`@Mod(clientSideOnly = true)` 会让专用服务器直接跳过它。

### AVIF 编码器与架构

模组**不把 `avifenc` 打进 jar**，而是在客户端启动时检查本机是否已有可用编码器；没有就异步下载官方 libavif 发行版，最长等待 **1 分钟**。下载成功后会缓存到：

```
config/simplerecorder/avif/<platform>/avifenc[.exe]
```

之后启动直接复用，不再联网。

| 平台 | 官方构建 | 处理方式 |
|---|---|---|
| Windows x64 | 有 | 启动时自动下载 |
| Linux x64 | 有 | 启动时自动下载 |
| macOS arm64 | 有 | 启动时自动下载 |
| Linux arm64 / Android | 无 | 没有默认可用的社区构建；设置 `avif.encoderPath` 或 `avif.downloadUrl`，找不到时提示自行获取 |
| macOS x64 | 无 | 同上 |

- 下载优先走 `github.com` 官方 release，失败后自动尝试 `gh-proxy.com` 镜像；总超时 1 分钟。
- 已经有本地缓存、PATH 中的 `avifenc`，或配置了 `encoderPath` 时不会重复下载。
- 下载失败、平台没有官方构建、或文件不可执行时，录制自动回退 **GIF**，聊天栏会显示原因。

---

## 使用

1. 进入世界。
2. 按 **F8** 开始录制，右上角出现 `● REC 00:00`。
3. 再按 **F8** 停止。聊天栏提示正在导出，完成后显示路径。
4. 文件在游戏目录下的 **`screenshots/`**：
   - `simplerecorder-2026-11-01_00-00-00.avif`
   - 回退时：`simplerecorder-2026-11-01_00-00-00.gif`

F8 在世界内、物品栏/暂停菜单等界面打开时都可以用；只有「控制 → 按键绑定」界面里不会触发（避免和改键冲突）。
暂停时按 **F8** 结束录制并导出（不会续录）；按 **F10** 才会继续录制。

---

## 设置

### 游戏内

按 **F9**（或模组列表的 Config 按钮）打开设置界面，可调：

- 输出格式：AVIF / GIF
- 帧率：5–60
- 缩放：10%–100%
- AVIF 质量：20–100
- AVIF 速度：5–10（越高越快、文件越大）
- YUV 转换：OPENGL（默认）/ MULTITHREAD
- CPU 线程数：1–本机逻辑 CPU 数（仅 MULTITHREAD 生效）
- 循环：开 / 关

点「完成」写入配置文件并关闭。

### 配置文件

首次启动生成 `config/simplerecorder.cfg`（Forge `Configuration` 的 INI 格式）：

```ini
output {
    S:format=AVIF          # AVIF / GIF
    I:fps=30               # 5-60
    D:scale=0.5            # 0.5 = 长宽各 50%
    B:loop=true
    B:warnHighSettings=true
}

avif {
    I:quality=60           # 20-100
    I:speed=8              # 5-10
    S:conversion=OPENGL    # OPENGL（默认）或 MULTITHREAD
    I:cpuThreads=4         # 1-本机逻辑 CPU 数
    S:encoderPath=         # 自定义 avifenc 可执行文件路径
    S:downloadUrl=         # 可选：含 avifenc 的 zip 地址
}
```

**热重载**：模组每秒检查一次文件 mtime/size，保存后新的值会在下一次录制时生效；游戏内设置界面打开时，外部编辑也会同步刷新界面。用模组列表的 Config 按钮关闭界面时也会立即重载。

`cpuThreads` 默认 4，上限是本机逻辑 CPU 数；单核机器会被自动限制为 1。GPU 转换固定为 YUV444；CPU 兜底在偶数尺寸使用 4:2:0、奇数尺寸使用 4:4:4。

> 修改只影响下一次录制，正在进行的录制不会中途切换参数。

---

## 画面范围与技术说明

- 正常情况下在 **HUD 渲染完成后**抓取主渲染目标，所以输出里有准星、快捷栏、生命值等 HUD。
- 打开物品栏、暂停菜单、聊天等界面时，会等 **GuiScreen 绘制完成后**再抓取。
- 录制指示器在抓取之后才画，因此不会进入输出文件。
- HUD 层的 toast、Jade 提示等只要在 NORMAL 优先级内绘制，都会被包含。
- 默认把 1920×1080 抓成 960×540；缩放使用 2×2 盒式平均。
- **异步 PBO 抓帧**：渲染线程只把 framebuffer `glReadPixels` 到 3 个轮转的 PBO，并插入 `GL32` fence；fence 完成后在当前 GL 上下文映射 PBO，把映射内存交给后台缩放线程。后台线程完成翻转和缩放后，渲染线程再解除映射并把 `ScaledFrame` 交给编码器。
- 不创建 `NativeImage`、不每帧分配 8 MB 像素数组，也没有同步 `getPixelsRGBA` 回读。缩放输出使用 `FramePool` 复用。
- **OpenGL YUV**：1.12.2 走 GLSL 120（兼容上下文、立即模式提交全屏四边形），以 2×2 盒式滤波采样主 framebuffer，做 BT.601 limited-range RGB→YUV，并把 YUV444 打包进 RGBA8 目标；PBO 直接读取 YUV，CPU 不再逐像素转换。
- **CPU 兜底**：`MULTITHREAD` 模式按行分带，用守护线程池并行转换；默认 4 线程，可在 1–本机逻辑 CPU 数之间调整，单核自动限制为 1。
- **AVIF**：把每帧转成 Y4M（GPU 为 4:4:4；CPU 偶数尺寸 4:2:0、奇数尺寸 4:4:4），流式写入 `avifenc --stdin`；不落盘中间帧。
- **GIF**：每帧做 5-bit RGB 直方图 + 加权中位切分，生成最多 256 色的自适应调色板，再交给 ImageIO 做 LZW。
- 每帧带捕获时间戳。GIF 的延迟写在累计的 1/100 秒网格上；AVIF 用配置帧率作为时间基并在掉帧时补重复帧，所以输出时长与现实一致。
- 按 **F1** 隐藏 HUD 且没有打开界面时，会在 `RenderWorldLastEvent` 抓取，因此只有世界（没有 HUD，也没有右上角指示器）。

### 文件大小

- AVIF 通常比 GIF 小一个数量级；1280×747、30 FPS 的游戏画面常见为 **每秒 1 MB 以内**，具体取决于质量和场景复杂度。
- GIF 没有现代视频的帧间压缩，1280×747 / 30 FPS 大约 **6–7 MB/s**，录 1 分钟可能到 400 MB。需要长时间录制请优先用 AVIF。

### 性能与高负载警告

抓帧使用 PBO 异步回读，缩放和编码都在后台线程。估算像素吞吐超过约 4000 万像素/秒，或 60 FPS + 75% 以上缩放时，开始录制会在聊天栏提示高负载。PBO 只保存 3 个帧缓冲；CPU 缩放线程单线程，避免和游戏抢满所有 CPU。如果编码队列满，会丢新帧而不是阻塞游戏。

---

## 从源码构建

需要 **JDK 8**（Gradle 4.9 只能跑在 JDK 8–10 上；Java 11+ 会直接失败）。先设好 `JAVA_HOME`：

```bat
set JAVA_HOME=D:\jdk8
gradlew.bat build
```

产物：

```
build/libs/simplerecorder-1.12.2-0.1.0.jar
```

只跑单元测试：

```bat
gradlew.bat test
```

首次构建要下载 MCP 映射并反编译 Minecraft，可能需要 30 分钟以上，属于正常现象。

> **Forge 版本说明**：`build.gradle` 钉的是 **14.23.5.2855**。在本机开发环境里，**14.23.5.2860 会在模组构造阶段崩溃**（`NetworkRegistry.newChannel` 空指针，属于 ForgeGradle 3 dev 启动链路上的问题，连空白脚手架也一样崩），换 2855 后 `runClient` 正常。产物只依赖 `[1.12.2]`，放进 2855 或 2860 的正式客户端都能加载。

测试覆盖 GIF 帧延迟/循环/同帧合并/调色板/端到端颜色、PBO 底部行翻转、50% 缩放、CPU YUV 分带转换，以及在本机已有 `avifenc` 时实际编码动画 AVIF。没有编码器的环境会跳过 AVIF 用例。

---

## 项目结构

```
src/main/java/com/lisheng/simplerecorder/
  SimpleRecorder.java              @Mod 入口，preInit 读配置并初始化客户端
  client/
    ClientSetup.java               客户端事件 / 按键注册 / 编码器预下载
    ClientEvents.java              F8、F9、HUD/界面抓帧、录制指示器、热重载
    RecordingController.java       状态机、格式选择、输出路径、聊天反馈
    RecorderConfigScreen.java      游戏内设置界面（GuiScreen）
    RecorderGuiFactory.java        模组列表的 Config 按钮
    PboCapture.java                3×PBO 异步回读、fence、映射内存交接
    FrameScaler.java               PBO/原生 RGBA→ARGB 与盒式缩放
    ScaledFrame.java / FramePool.java / YuvPool.java
  encode/
    RecordingEncoder.java          编码器接口
    RecordingResult.java
  avif/
    AvifSupport.java               平台检测、缓存、1 分钟异步下载
    GpuYuvConverter.java           GLSL 120 RGB→YUV444 + 缩放
    RgbToYuvConverter.java         多线程 CPU 兜底
    AvifEncoder.java               Y4M 流式 AVIF 编码
  gif/
    GifEncoder.java                后台编码线程 + 有界队列
    GifSequenceWriter.java         GIF89a 流式写入、延迟、循环、同帧合并
    PaletteQuantizer.java          自适应 256 色调色板
    GifResult.java
  config/
    RecorderConfig.java            Forge Configuration 包装 + 热重载
    OutputFormat.java / YuvMode.java
```

`avifenc` 不在 jar 内；首次启动下载到 `config/simplerecorder/avif/<platform>/`。

---
