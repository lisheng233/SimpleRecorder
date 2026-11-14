package com.lisheng.simplerecorder.client;

import com.lisheng.simplerecorder.config.OutputFormat;
import com.lisheng.simplerecorder.config.RecorderConfig;
import com.lisheng.simplerecorder.config.YuvMode;
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

    /** Called from {@code Minecraft#renderFrame} after the frame has been presented. */
    public static void onFrameRendered() {
        CONTROLLER.onFrameRendered(Minecraft.getInstance());
    }

    public static RecordingController controller() {
        return CONTROLLER;
    }

    /** Used by the settings screen to show what the current settings will do. */
    public static String describeState() {
        String backend = GpuCapabilities.backendName();
        YuvMode mode = RecorderConfig.yuvMode();
        String conversion = mode == YuvMode.OPENGL
                ? "CPU (GPU shader conversion is not available on 26.3)"
                : "CPU (" + RecorderConfig.avifCpuThreads() + " threads)";
        return "Backend: " + backend + "  |  Conversion: " + conversion
                + "  |  Format: " + RecorderConfig.format()
                + "  |  Next file: ." + (RecorderConfig.format() == OutputFormat.AVIF ? "avif" : "gif");
    }
}
