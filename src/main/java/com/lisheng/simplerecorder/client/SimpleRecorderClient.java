package com.lisheng.simplerecorder.client;

import com.lisheng.simplerecorder.config.OutputFormat;
import com.lisheng.simplerecorder.config.RecorderConfig;
import com.lisheng.simplerecorder.config.ConvertMode;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;

/**
 * Client entrypoint: keys, the HUD indicator and the per-frame capture hook.
 *
 * <p>Everything GPU related goes through the game's own abstraction, so this
 * mod runs unchanged on both of the 26.3 renderers (OpenGL and Vulkan). See
 * {@link GpuCapabilities} and {@link FrameCapture} for the details.</p>
 */
public final class SimpleRecorderClient implements ClientModInitializer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Identifier OVERLAY_ID = Identifier.fromNamespaceAndPath("simplerecorder", "indicator");

    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("simplerecorder", "main"));

    public static final KeyMapping KEY_TOGGLE = new KeyMapping(
            "key.simplerecorder.record", InputConstants.Type.KEYBOARD, InputConstants.KEY_F8, CATEGORY);
    public static final KeyMapping KEY_PAUSE = new KeyMapping(
            "key.simplerecorder.pause", InputConstants.Type.KEYBOARD, InputConstants.KEY_F10, CATEGORY);
    public static final KeyMapping KEY_SETTINGS = new KeyMapping(
            "key.simplerecorder.config", InputConstants.Type.KEYBOARD, InputConstants.KEY_F9, CATEGORY);

    private static final RecordingController CONTROLLER = new RecordingController();

    @Override
    public void onInitializeClient() {
        KeyMappingHelper.registerKeyMapping(KEY_TOGGLE);
        KeyMappingHelper.registerKeyMapping(KEY_PAUSE);
        KeyMappingHelper.registerKeyMapping(KEY_SETTINGS);

        HudElementRegistry.addLast(OVERLAY_ID, (graphics, deltaTracker) ->
                CONTROLLER.renderOverlay(Minecraft.getInstance(), graphics));

        ClientTickEvents.END_CLIENT_TICK.register(SimpleRecorderClient::onClientTick);

        // Resolve the backend once the device exists; on both backends the
        // capture path is identical, this only feeds logs and diagnostics.
        GpuCapabilities.refresh();
        LOGGER.info("SimpleRecorder 26.3 initialised (GPU backend: {})", GpuCapabilities.backendName());
    }

    private static void onClientTick(Minecraft minecraft) {
        GpuCapabilities.refresh();
        while (KEY_TOGGLE.consumeClick()) {
            CONTROLLER.toggle(minecraft);
        }
        while (KEY_PAUSE.consumeClick()) {
            CONTROLLER.togglePause(minecraft);
        }
        boolean openSettings = false;
        while (KEY_SETTINGS.consumeClick()) {
            openSettings = true;
        }
        if (openSettings) {
            minecraft.setScreenAndShow(new RecorderConfigScreen(null));
        }
        CONTROLLER.tick(minecraft);
    }

    // --- frame timing / self test -------------------------------------------------

    private static long lastFrameNanos;
    private static long idleFrames;
    private static long idleNanos;
    private static long recordFrames;
    private static long recordNanos;
    private static long autoTestStart;
    private static int autoTestPhase;
    private static long autoTestPhaseAt;

    /** Called from {@code Minecraft#renderFrame} after the frame has been presented. */
    public static void onFrameRendered() {
        Minecraft minecraft = Minecraft.getInstance();
        long now = System.nanoTime();
        if (lastFrameNanos != 0L) {
            long delta = now - lastFrameNanos;
            if (CONTROLLER.isRecording()) {
                recordFrames++;
                recordNanos += delta;
            } else if (idleFrames < 400L) {
                idleFrames++;
                idleNanos += delta;
            }
        }
        lastFrameNanos = now;

        CONTROLLER.onFrameRendered(minecraft);
        maybeAutoTest(minecraft, now);
    }

    /**
     * Development aid: {@code -Dsimplerecorder.autotest=<seconds>} starts a
     * recording after three seconds and stops it after the given duration, then
     * logs the frame rate before and during the recording. Used to measure the
     * capture overhead without a human holding F8.
     */
    /** True when the self test is enabled ({@code -Dsimplerecorder.autotest} or env var). */
    public static boolean isAutoTest() {
        return System.getProperty("simplerecorder.autotest") != null
                || System.getenv("SIMPLERECORDER_AUTOTEST") != null;
    }

    private static void maybeAutoTest(Minecraft minecraft, long now) {
        String seconds = System.getProperty("simplerecorder.autotest");
        if (seconds == null) {
            seconds = System.getenv("SIMPLERECORDER_AUTOTEST");
        }
        if (seconds == null) {
            return;
        }
        long duration = Long.parseLong(seconds) * 1_000_000_000L;
        if (autoTestStart == 0L) {
            autoTestStart = now;
            autoTestPhaseAt = now;
            return;
        }
        long elapsed = now - autoTestStart;
        switch (autoTestPhase) {
            case 0 -> {
                if (elapsed > 3_000_000_000L) {
                    autoTestPhase = 1;
                    autoTestPhaseAt = now;
                    CONTROLLER.toggle(minecraft);
                }
            }
            case 1 -> {
                if (now - autoTestPhaseAt > duration) {
                    autoTestPhase = 2;
                    autoTestPhaseAt = now;
                    CONTROLLER.toggle(minecraft);
                    double idleFps = idleNanos == 0L ? 0.0D
                            : 1.0E9D / ((double) idleNanos / Math.max(1L, idleFrames));
                    double recordFps = recordNanos == 0L ? 0.0D
                            : 1.0E9D / ((double) recordNanos / Math.max(1L, recordFrames));
                    LOGGER.info("AUTOTEST idle={} frames avg {} fps | recording={} frames avg {} fps",
                            idleFrames, String.format(java.util.Locale.ROOT, "%.1f", idleFps),
                            recordFrames, String.format(java.util.Locale.ROOT, "%.1f", recordFps));
                }
            }
            case 2 -> {
                if (now - autoTestPhaseAt > 2_000_000_000L) {
                    autoTestPhase = 3;
                    autoTestPhaseAt = now;
                    minecraft.setScreenAndShow(new RecorderConfigScreen(null));
                    LOGGER.info("AUTOTEST opened the settings screen");
                }
            }
            case 3 -> {
                if (now - autoTestPhaseAt > 2_000_000_000L) {
                    autoTestPhase = 4;
                    minecraft.setScreenAndShow(null);
                    LOGGER.info("AUTOTEST closed the settings screen");
                }
            }
            default -> {
            }
        }
    }
    public static RecordingController controller() {
        return CONTROLLER;
    }

    /** Used by the settings screen to show what the current settings will do. */
    public static String describeState() {
        String backend = GpuCapabilities.backendName();
        ConvertMode mode = RecorderConfig.convertMode();
        String conversion = mode == ConvertMode.GPU
                ? "GPU (" + backend + ") -> CPU fallback, not implemented yet"
                : "CPU (" + RecorderConfig.avifCpuThreads() + " threads)";
        return "Backend: " + backend + "  |  Conversion: " + conversion
                + "  |  Format: " + RecorderConfig.format()
                + "  |  Next file: ." + (RecorderConfig.format() == OutputFormat.AVIF ? "avif" : "gif");
    }
}
