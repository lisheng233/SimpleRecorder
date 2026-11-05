package com.lisheng.simplerecorder.client;

import com.lisheng.simplerecorder.avif.AvifEncoder;
import com.lisheng.simplerecorder.avif.AvifSupport;
import com.lisheng.simplerecorder.config.OutputFormat;
import com.lisheng.simplerecorder.config.RecorderConfig;
import com.lisheng.simplerecorder.config.YuvMode;
import com.lisheng.simplerecorder.encode.RecordingEncoder;
import com.lisheng.simplerecorder.encode.RecordingResult;
import com.lisheng.simplerecorder.gif.GifEncoder;
import org.apache.logging.log4j.LogManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.CompletionException;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.gui.AbstractGui;
import net.minecraft.util.text.event.ClickEvent;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TranslationTextComponent;
import org.apache.logging.log4j.Logger;

/**
 * Owns the recording state machine and bridges the render thread, the encoder
 * thread and chat feedback.
 */
public final class RecordingController {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private final FramePool pool = new FramePool();
    private final YuvPool yuvPool = new YuvPool();

    private State state = State.IDLE;
    private RecordingEncoder encoder;
    private PboCapture pboCapture;
    private Path outputPath;
    private OutputFormat activeFormat = OutputFormat.AVIF;

    private int fps;
    private double scale;
    private int frameWidth;
    private int frameHeight;
    private int capturedFrames;
    private int droppedFrames;
    private boolean warnedHigh;
    private long startNanos;
    private long nextCaptureNanos;
    private long pausedNanos;
    private long pauseStartedNanos;

    public void toggle(Minecraft minecraft) {
        if (state == State.RECORDING || state == State.PAUSED) {
            stop(minecraft, new TranslationTextComponent("message.simplerecorder.exporting"));
        } else if (state == State.IDLE) {
            start(minecraft);
        } else {
            clientMessage(minecraft, new TranslationTextComponent("message.simplerecorder.busy"));
        }
    }

    public void tick(Minecraft minecraft) {
        if ((state == State.RECORDING || state == State.PAUSED)
                && (minecraft.level == null || minecraft.player == null)) {
            stop(minecraft, new TranslationTextComponent("message.simplerecorder.auto_stop"));
        }
    }

    /**
     * Called from the end of the HUD render pass, before our own indicator is
     * drawn. The GUI is flushed first so the captured texture contains the HUD.
     */
    public void onRenderFrame(Minecraft minecraft, MatrixStack poseStack) {
        if (state != State.RECORDING) {
            return;
        }

        PboCapture capture = pboCapture;
        if (capture == null) {
            return;
        }

        capture.poll();
        if (capture.hasError()) {
            stop(minecraft, new TranslationTextComponent("message.simplerecorder.capture_error", "PBO readback failed"));
            return;
        }
        if (capture.hasSizeChanged()) {
            stop(minecraft, new TranslationTextComponent("message.simplerecorder.resize"));
            return;
        }

        long now = System.nanoTime();
        if (now < nextCaptureNanos) {
            return;
        }

        try {
            // 1.18.2 GUI drawing ends its own batched buffer per draw call, so
            // there is nothing left to flush here before the readback.
            if (!capture.capture(minecraft.getMainRenderTarget(), now)
                    && !capture.hasSizeChanged() && !capture.hasError()) {
                droppedFrames++;
            }
        } catch (Throwable throwable) {
            LOGGER.error("Could not queue a frame readback", throwable);
            stop(minecraft, new TranslationTextComponent("message.simplerecorder.capture_error", messageOf(throwable)));
            return;
        }

        long interval = 1_000_000_000L / fps;
        nextCaptureNanos += interval;
        if (nextCaptureNanos < now) {
            // Do not try to catch up with a burst of frames after a stall.
            nextCaptureNanos = now + interval;
        }
    }

    /**
     * Called on the render thread by {@link PboCapture} when a scaled frame is
     * ready. Scaling happened on the PBO worker; only the handoff runs here.
     */
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

    public void renderOverlay(Minecraft minecraft, MatrixStack poseStack) {
        if (state != State.RECORDING && state != State.PAUSED) {
            return;
        }

        long seconds = elapsedSeconds();
        boolean paused = state == State.PAUSED;
        String text = String.format(Locale.ROOT, paused ? "PAUSED %02d:%02d" : "REC %02d:%02d",
                seconds / 60L, seconds % 60L);
        int boxWidth = minecraft.font.width(text) + 18;
        int x = minecraft.getWindow().getGuiScaledWidth() - boxWidth - 8;
        int y = 8;

        AbstractGui.fill(poseStack, x, y, x + boxWidth, y + 14, 0x90000000);
        AbstractGui.fill(poseStack, x + 5, y + 5, x + 9, y + 9, paused ? 0xFFFFB020 : 0xFFFF3B30);
        AbstractGui.drawString(poseStack, minecraft.font, text, x + 13, y + 3, 0xFFFFFFFF);
    }

    public boolean isRecording() {
        return state == State.RECORDING;
    }

    public long elapsedSeconds() {
        long now = System.nanoTime();
        long paused = pausedNanos + (state == State.PAUSED ? now - pauseStartedNanos : 0L);
        return Math.max(0L, (now - startNanos - paused) / 1_000_000_000L);
    }

    /**
     * Pause or resume the current recording. Paused time is cut out of the
     * output: capture stops while paused and the frame timestamps handed to the
     * encoder are shifted back by the paused duration on resume, so the video
     * continues right where it stopped.
     */
    public void togglePause(Minecraft minecraft) {
        if (state == State.RECORDING) {
            pauseStartedNanos = System.nanoTime();
            state = State.PAUSED;
            clientMessage(minecraft, new TranslationTextComponent("message.simplerecorder.paused"));
            LOGGER.info("Recording paused");
        } else if (state == State.PAUSED) {
            pausedNanos += System.nanoTime() - pauseStartedNanos;
            pauseStartedNanos = 0L;
            state = State.RECORDING;
            nextCaptureNanos = System.nanoTime();
            clientMessage(minecraft, new TranslationTextComponent("message.simplerecorder.resumed"));
            LOGGER.info("Recording resumed after {} ms paused", pausedNanos / 1_000_000L);
        } else if (state == State.EXPORTING) {
            clientMessage(minecraft, new TranslationTextComponent("message.simplerecorder.busy"));
        }
    }

    private void start(Minecraft minecraft) {        if (minecraft.level == null || minecraft.player == null) {
            clientMessage(minecraft, new TranslationTextComponent("message.simplerecorder.no_world"));
            return;
        }

        fps = RecorderConfig.FPS.get();
        scale = RecorderConfig.SCALE.get();
        boolean loop = RecorderConfig.LOOP.get();
        int quality = RecorderConfig.AVIF_QUALITY.get();
        int speed = RecorderConfig.AVIF_SPEED.get();

        OutputFormat requested = RecorderConfig.FORMAT.get();
        activeFormat = requested;
        if (requested == OutputFormat.AVIF && !AvifSupport.isAvailable()) {
            activeFormat = OutputFormat.GIF;
            String reason = AvifSupport.error();
            LOGGER.warn("AVIF encoder unavailable, falling back to GIF: {}", reason);
            clientMessage(minecraft, new TranslationTextComponent("message.simplerecorder.fallback", reason));
        }

        try {
            outputPath = newOutputPath(minecraft, activeFormat.extension());
        } catch (IOException exception) {
            LOGGER.error("Could not create the screenshots directory", exception);
            clientMessage(minecraft,
                    new TranslationTextComponent("message.simplerecorder.path_error", messageOf(exception)));
            return;
        }

        boolean gpuConvert = activeFormat == OutputFormat.AVIF
                && RecorderConfig.AVIF_CONVERSION.get() == YuvMode.OPENGL;
        if (activeFormat == OutputFormat.AVIF) {
            encoder = new AvifEncoder(outputPath, fps, loop, quality, speed, pool, yuvPool,
                    RecorderConfig.AVIF_CPU_THREADS.get());
        } else {
            encoder = new GifEncoder(outputPath, fps, loop, pool);
        }
        pboCapture = new PboCapture(scale, pool, yuvPool, this::onCapturedFrame, gpuConvert);
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

        LOGGER.info("Started {} recording: {} ({} FPS, {}% size)", activeFormat, outputPath, fps,
                Math.round(scale * 100.0D));
    }

    private void warnHighSettings(Minecraft minecraft) {
        if (warnedHigh || !RecorderConfig.WARN_HIGH_SETTINGS.get()) {
            return;
        }
        net.minecraft.client.shader.Framebuffer target = minecraft.getMainRenderTarget();
        if (target == null) {
            return;
        }
        int width = FrameScaler.scaledWidth(target.width, scale);
        int height = FrameScaler.scaledHeight(target.height, scale);
        long pixelsPerSecond = (long) width * height * fps;
        if (pixelsPerSecond >= 40_000_000L || (fps >= 60 && scale >= 0.75D)) {
            warnedHigh = true;
            clientMessage(minecraft, new TranslationTextComponent(
                    "message.simplerecorder.high_settings", width, height, fps));
        }
    }

    private void stop(Minecraft minecraft, ITextComponent reason) {
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
        PboCapture closingCapture = pboCapture;
        pboCapture = null;
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

        closingEncoder.finish().whenComplete((result, throwable) -> minecraft.execute(
                () -> completeExport(minecraft, format, closingPath, result, throwable, frames, durationSeconds)));
    }

    private void completeExport(Minecraft minecraft, OutputFormat format, Path path, RecordingResult result,
                                Throwable throwable, int captured, long durationSeconds) {
        state = State.IDLE;
        outputPath = null;
        pboCapture = null;
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
                    new TranslationTextComponent("message.simplerecorder.error", messageOf(cause)));
            return;
        }

        if (result == null || result.frameCount() == 0) {
            deleteQuietly(path);
            clientMessage(minecraft, new TranslationTextComponent("message.simplerecorder.empty"));
            return;
        }

        String relative = relativize(minecraft, result.path());
        ITextComponent file = new StringTextComponent(relative)
                .withStyle(TextFormatting.YELLOW)
                .withStyle(style -> style.withClickEvent(
                        new ClickEvent(ClickEvent.Action.OPEN_FILE, result.path().toAbsolutePath().toString())));
        ITextComponent message = new TranslationTextComponent("message.simplerecorder.saved",
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
        return message == null || message.trim().isEmpty() ? throwable.getClass().getSimpleName() : message;
    }

    private static void clientMessage(Minecraft minecraft, ITextComponent message) {
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(message, false);
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
