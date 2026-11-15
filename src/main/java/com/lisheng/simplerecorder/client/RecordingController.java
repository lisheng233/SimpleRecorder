package com.lisheng.simplerecorder.client;

import com.lisheng.simplerecorder.avif.AvifEncoder;
import com.lisheng.simplerecorder.avif.AvifSupport;
import com.lisheng.simplerecorder.config.OutputFormat;
import com.lisheng.simplerecorder.config.RecorderConfig;
import com.lisheng.simplerecorder.config.ConvertMode;
import com.lisheng.simplerecorder.encode.RecordingEncoder;
import com.lisheng.simplerecorder.encode.RecordingResult;
import com.lisheng.simplerecorder.gif.GifEncoder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.CompletionException;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

/**
 * Owns the recording state machine and bridges the render thread, the capture
 * ring, the encoder thread and chat feedback.
 */
public final class RecordingController {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private final FramePool pool = new FramePool();
    private final YuvPool yuvPool = new YuvPool();

    private State state = State.IDLE;
    private RecordingEncoder encoder;
    private FrameCapture capture;
    private Path outputPath;
    private OutputFormat activeFormat = OutputFormat.AVIF;

    private int fps;
    private double scale;
    private int frameWidth;
    private int frameHeight;
    private int capturedFrames;
    private int droppedFrames;
    private boolean warnedHigh;
    private boolean warnedGpuConversion;
    private long startNanos;
    private long nextCaptureNanos;
    private long pausedNanos;
    private long pauseStartedNanos;

    public void toggle(Minecraft minecraft) {
        switch (state) {
            case RECORDING, PAUSED -> stop(minecraft, Component.translatable("message.simplerecorder.exporting"));
            case IDLE -> start(minecraft);
            case EXPORTING -> clientMessage(minecraft, Component.translatable("message.simplerecorder.busy"));
        }
    }

    public void tick(Minecraft minecraft) {
        if (!SimpleRecorderClient.isAutoTest()
                && (state == State.RECORDING || state == State.PAUSED)
                && (minecraft.level == null || minecraft.player == null)) {
            stop(minecraft, Component.translatable("message.simplerecorder.auto_stop"));
        }
    }

    /**
     * Called once per frame, right after the frame has been submitted and
     * presented. The main render target still holds the finished image at this
     * point, so a copy issued now contains the world, the HUD and every overlay
     * except the one drawn after this hook.
     */
    public void onFrameRendered(Minecraft minecraft) {
        if (state != State.RECORDING) {
            return;
        }
        FrameCapture active = capture;
        if (active == null) {
            return;
        }

        active.poll();
        if (active.hasError()) {
            stop(minecraft, Component.translatable("message.simplerecorder.capture_error", "GPU readback failed"));
            return;
        }
        if (active.hasSizeChanged()) {
            stop(minecraft, Component.translatable("message.simplerecorder.resize"));
            return;
        }

        long now = System.nanoTime();
        if (now < nextCaptureNanos) {
            return;
        }

        RenderTarget target;
        try {
            target = minecraft.gameRenderer.mainRenderTarget();
        } catch (Throwable throwable) {
            LOGGER.error("Could not reach the main render target", throwable);
            stop(minecraft, Component.translatable("message.simplerecorder.capture_error", messageOf(throwable)));
            return;
        }

        try {
            if (!active.capture(target, now) && !active.hasSizeChanged() && !active.hasError()) {
                droppedFrames++;
            }
        } catch (Throwable throwable) {
            LOGGER.error("Could not queue a frame readback", throwable);
            stop(minecraft, Component.translatable("message.simplerecorder.capture_error", messageOf(throwable)));
            return;
        }

        long interval = 1_000_000_000L / fps;
        nextCaptureNanos += interval;
        if (nextCaptureNanos < now) {
            // Do not try to catch up with a burst of frames after a stall.
            nextCaptureNanos = now + interval;
        }
    }

    private void onCapturedFrame(ScaledFrame frame) {
        if ((state != State.RECORDING && state != State.PAUSED) || encoder == null) {
            releaseFrame(frame);
            return;
        }
        if (frameWidth == 0) {
            frameWidth = frame.width();
            frameHeight = frame.height();
        } else if (frameWidth != frame.width() || frameHeight != frame.height()) {
            releaseFrame(frame);
            return;
        }

        ScaledFrame offered = pausedNanos == 0L ? frame : new ScaledFrame(
                frame.pixels(), frame.width(), frame.height(),
                frame.captureNanos() - pausedNanos, frame.yuv());
        if (!encoder.offer(offered)) {
            droppedFrames++;
            releaseFrame(frame);
        }
        capturedFrames++;
    }

    private void releaseFrame(ScaledFrame frame) {
        if (frame.pixels() != null) {
            pool.release(frame.pixels());
        }
        if (frame.yuv() != null) {
            yuvPool.release(frame.yuv());
        }
    }

    /**
     * Draws the recording indicator.
     *
     * <p>26.3 renders the whole GUI pass into the same target the recorder reads
     * back, so any pixel drawn here ends up inside the exported file. The
     * indicator is therefore only drawn while the recording is <em>paused</em>
     * (no frames are captured then); during an active recording the state is
     * reported through chat instead.</p>
     */
    public void renderOverlay(Minecraft minecraft, GuiGraphicsExtractor graphics) {
        boolean paused = state == State.PAUSED;
        if (!paused && !(state == State.RECORDING && RecorderConfig.indicatorInRecording())) {
            return;
        }

        long seconds = elapsedSeconds();
        String text = String.format(Locale.ROOT, paused ? "PAUSED %02d:%02d" : "REC %02d:%02d",
                seconds / 60L, seconds % 60L);
        int boxWidth = minecraft.font.width(text) + 18;
        int x = minecraft.getWindow().getGuiScaledWidth() - boxWidth - 8;
        int y = 8;

        graphics.fill(x, y, x + boxWidth, y + 14, 0x90000000);
        graphics.fill(x + 5, y + 5, x + 9, y + 9, paused ? 0xFFFFB020 : 0xFFFF3B30);
        graphics.text(minecraft.font, text, x + 13, y + 3, 0xFFFFFFFF);
    }

    public boolean isRecording() {
        return state == State.RECORDING;
    }

    public boolean isBusy() {
        return state != State.IDLE;
    }

    public long elapsedSeconds() {
        long now = System.nanoTime();
        long paused = pausedNanos + (state == State.PAUSED ? now - pauseStartedNanos : 0L);
        return Math.max(0L, (now - startNanos - paused) / 1_000_000_000L);
    }

    public void togglePause(Minecraft minecraft) {
        if (state == State.RECORDING) {
            pauseStartedNanos = System.nanoTime();
            state = State.PAUSED;
            clientMessage(minecraft, Component.translatable("message.simplerecorder.paused"));
            LOGGER.info("Recording paused");
        } else if (state == State.PAUSED) {
            pausedNanos += System.nanoTime() - pauseStartedNanos;
            pauseStartedNanos = 0L;
            state = State.RECORDING;
            nextCaptureNanos = System.nanoTime();
            clientMessage(minecraft, Component.translatable("message.simplerecorder.resumed"));
            LOGGER.info("Recording resumed after {} ms paused", pausedNanos / 1_000_000L);
        } else if (state == State.EXPORTING) {
            clientMessage(minecraft, Component.translatable("message.simplerecorder.busy"));
        }
    }

    private void start(Minecraft minecraft) {
        if (!SimpleRecorderClient.isAutoTest()
                && (minecraft.level == null || minecraft.player == null)) {
            clientMessage(minecraft, Component.translatable("message.simplerecorder.no_world"));
            return;
        }

        fps = RecorderConfig.fps();
        scale = RecorderConfig.scale();
        boolean loop = RecorderConfig.loop();
        int quality = RecorderConfig.avifQuality();
        int speed = RecorderConfig.avifSpeed();

        OutputFormat requested = RecorderConfig.format();
        activeFormat = requested;
        if (requested == OutputFormat.AVIF && !AvifSupport.isAvailable()) {
            activeFormat = OutputFormat.GIF;
            String reason = AvifSupport.error();
            LOGGER.warn("AVIF encoder unavailable, falling back to GIF: {}", reason);
            clientMessage(minecraft, Component.translatable("message.simplerecorder.fallback", reason));
        }

        if (!warnedGpuConversion && RecorderConfig.convertMode() == ConvertMode.GPU) {
            warnedGpuConversion = true;
            // A real GPU pass needs a custom RenderPipeline + shader in 26.3; this
            // build has no such pipeline yet, so say so instead of pretending.
            clientMessage(minecraft, Component.translatable("message.simplerecorder.gpu_conversion_fallback",
                    GpuCapabilities.backendName()));
            LOGGER.warn("avif.convert=GPU is not implemented yet on 26.3 ({} backend); using CPU conversion",
                    GpuCapabilities.backendName());
        }

        try {
            outputPath = newOutputPath(minecraft, activeFormat.extension());
        } catch (IOException exception) {
            LOGGER.error("Could not create the screenshots directory", exception);
            clientMessage(minecraft,
                    Component.translatable("message.simplerecorder.path_error", messageOf(exception)));
            return;
        }

        switch (activeFormat) {
            case AVIF -> encoder = new AvifEncoder(outputPath, fps, loop, quality, speed, pool, yuvPool,
                    RecorderConfig.avifCpuThreads());
            case GIF -> encoder = new GifEncoder(outputPath, fps, loop, pool);
        }
        capture = new FrameCapture(scale, pool, yuvPool, this::onCapturedFrame);
        state = State.RECORDING;
        frameWidth = 0;
        frameHeight = 0;
        capturedFrames = 0;
        droppedFrames = 0;
        pausedNanos = 0L;
        pauseStartedNanos = 0L;
        startNanos = System.nanoTime();
        nextCaptureNanos = startNanos;
        warnHighSettings(minecraft);

        String report = GpuCapabilities.takeReport();
        if (report != null) {
            clientMessage(minecraft, Component.literal(report));
        }

        LOGGER.info("Started {} recording: {} ({} FPS, {}% size, {} backend)", activeFormat, outputPath, fps,
                Math.round(scale * 100.0D), GpuCapabilities.backendName());
    }

    private void warnHighSettings(Minecraft minecraft) {
        if (warnedHigh || !RecorderConfig.warnHighSettings()) {
            return;
        }
        RenderTarget target;
        try {
            target = minecraft.gameRenderer.mainRenderTarget();
        } catch (Throwable throwable) {
            return;
        }
        if (target == null) {
            return;
        }
        int width = FrameScaler.scaledWidth(target.width, scale);
        int height = FrameScaler.scaledHeight(target.height, scale);
        long pixelsPerSecond = (long) width * height * fps;
        if (pixelsPerSecond >= 40_000_000L || (fps >= 60 && scale >= 0.75D)) {
            warnedHigh = true;
            clientMessage(minecraft, Component.translatable(
                    "message.simplerecorder.high_settings", width, height, fps));
        }
    }

    private void stop(Minecraft minecraft, Component reason) {
        if (state != State.RECORDING && state != State.PAUSED) {
            return;
        }
        if (state == State.PAUSED) {
            pausedNanos += System.nanoTime() - pauseStartedNanos;
            pauseStartedNanos = 0L;
        }
        state = State.EXPORTING;

        RecordingEncoder closingEncoder = encoder;
        encoder = null;
        FrameCapture closingCapture = capture;
        capture = null;
        if (closingCapture != null) {
            closingCapture.close();
        }
        Path closingPath = outputPath;
        int frames = capturedFrames;
        int dropped = droppedFrames;
        long durationSeconds = elapsedSeconds();
        OutputFormat format = activeFormat;

        clientMessage(minecraft, reason);
        LOGGER.info("Stopping {} recording: {} frames, {} dropped", format, frames, dropped);
        if (closingCapture != null) {
            LOGGER.info("Capture stats: {}", closingCapture.stats());
        }

        closingEncoder.finish().whenComplete((result, throwable) -> minecraft.execute(
                () -> completeExport(minecraft, format, closingPath, result, throwable, frames, durationSeconds)));
    }

    private void completeExport(Minecraft minecraft, OutputFormat format, Path path, RecordingResult result,
                                Throwable throwable, int captured, long durationSeconds) {
        state = State.IDLE;
        outputPath = null;
        capture = null;
        frameWidth = 0;
        frameHeight = 0;
        capturedFrames = 0;
        droppedFrames = 0;
        pausedNanos = 0L;
        pauseStartedNanos = 0L;

        if (throwable != null) {
            Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
                    ? throwable.getCause() : throwable;
            LOGGER.error("{} export failed", format, cause);
            deleteQuietly(path);
            clientMessage(minecraft,
                    Component.translatable("message.simplerecorder.error", messageOf(cause)));
            return;
        }

        if (result == null || result.frameCount() == 0) {
            deleteQuietly(path);
            clientMessage(minecraft, Component.translatable("message.simplerecorder.empty"));
            return;
        }

        String relative = relativize(minecraft, result.path());
        Component file = Component.literal(relative)
                .withStyle(ChatFormatting.YELLOW)
                .withStyle(style -> style.withClickEvent(
                        new ClickEvent.OpenFile(result.path().toAbsolutePath())));
        Component message = Component.translatable("message.simplerecorder.saved",
                file, result.frameCount(), formatSize(result.bytes()));
        clientMessage(minecraft, message);

        LOGGER.info("{} saved: {} ({} frames, {} bytes, {}s wall clock, {} captured)",
                format, result.path(), result.frameCount(), result.bytes(), durationSeconds, captured);
    }

    private static Path newOutputPath(Minecraft minecraft, String extension) throws IOException {
        Path directory = minecraft.gameDirectory.toPath().resolve("screenshots");
        Files.createDirectories(directory);

        String stamp = FILE_STAMP.format(LocalDateTime.now());
        Path path = directory.resolve("simplerecorder-" + stamp + "." + extension);
        int suffix = 2;
        while (Files.exists(path)) {
            path = directory.resolve("simplerecorder-" + stamp + "-" + suffix++ + "." + extension);
        }
        return path;
    }

    private static String relativize(Minecraft minecraft, Path path) {
        try {
            Path gameDirectory = minecraft.gameDirectory.toPath().toAbsolutePath();
            return gameDirectory.relativize(path.toAbsolutePath()).toString().replace('\\', '/');
        } catch (RuntimeException ignored) {
            return path.toString();
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0D);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0D * 1024.0D));
    }

    private static String messageOf(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    private static void clientMessage(Minecraft minecraft, Component message) {
        if (minecraft.player != null) {
            minecraft.player.sendSystemMessage(message);
        } else {
            LOGGER.info(message.getString());
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best-effort cleanup.
        }
    }

    private enum State {
        IDLE,
        RECORDING,
        PAUSED,
        EXPORTING
    }
}
