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

### 抓帧时机

`Minecraft#renderFrame` 在最后 `submit()` + `present()` 之后，主渲染目标里是一帧完整的画面（含 HUD）。模组用 Mixin 在这个点抓帧，之后才画自己的录制指示器，所以：

- 抓到的画面包含世界、HUD、其他模组 overlay；
- 录制中右上角的 `● REC mm:ss` **不会**出现在输出文件里；
- 同一份代码在 Vulkan 上不会出现「只有 GUI 没世界」或「抓上一帧」的问题。

---

## 功能

- **F8 一键开始 / 结束**，按键可以在「选项 → 控制 → 按键绑定 → SimpleRecorder」里改。
- **F9 打开游戏内设置**。
- **F10 暂停 / 继续录制**：暂停期间完全不抓帧，恢复后视频从暂停处接着播（暂停时长不会留在文件里）；暂停状态下按 F8 直接结束并导出。
- 默认 **AVIF** 动画，**30 FPS**、**50% 宽度 × 50% 高度**、无限循环。
- AVIF 不可用时自动回退 **GIF**，并在聊天栏说明原因。
- 配置支持热重载：直接编辑 `config/simplerecorder.properties`，下一次录制立即生效。
- 录制时右上角显示红点 + `REC mm:ss`，这个指示器不会出现在输出文件里。
- 高负载设置警告：估算像素吞吐过高时在聊天栏提示可能掉帧。
- 异步 GPU 回读：3 个 GPU buffer 轮转，渲染线程只负责提交拷贝，缩放和编码在后台线程。
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

按 **F9** 打开设置界面，每个按钮点一下切换到下一个取值。也可以直接编辑 `config/simplerecorder.properties`（改完下一次录制生效）：

```properties
output.format=AVIF            # AVIF 或 GIF
output.fps=30                 # 5-60
output.scale=0.5              # 0.1-1.0
output.loop=true
output.warnHighSettings=true
avif.quality=60               # 20-100
avif.speed=8                  # 5-10
avif.conversion=MULTITHREAD   # 26.3 只支持 CPU 转换，见下
avif.cpuThreads=4             # 1-本机逻辑 CPU 数
avif.encoderPath=             # 手动指定 avifenc
avif.downloadUrl=             # 自定义下载地址
capture.backend=auto          # auto / opengl / vulkan，仅用于核对与提示
```

- `avif.conversion=OPENGL` 是 1.21.x 时代「用 GLSL 着色器做 RGB→YUV」的开关。26.3 的渲染后端可能是 Vulkan，那条路径已经不存在，设为 `OPENGL` 会自动降级为多线程 CPU 转换，并在聊天栏提示一次。
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
