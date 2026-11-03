package com.lisheng.simplerecorder.client;

import com.lisheng.simplerecorder.avif.AvifSupport;
import com.lisheng.simplerecorder.config.RecorderConfig;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.loading.FMLPaths;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

/**
 * Client event handlers: keybind, capture hook, HUD indicator and one extra
 * key handler so F8 also works while a container/pause screen is open.
 */
public final class ClientEvents {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final KeyMapping RECORD_KEY = new KeyMapping(
            "key.simplerecorder.record",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F8,
            "key.categories.simplerecorder");

    public static final KeyMapping CONFIG_KEY = new KeyMapping(
            "key.simplerecorder.config",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F9,
            "key.categories.simplerecorder");

    private static final RecordingController CONTROLLER = new RecordingController();
    private static long lastConfigModified;
    private static long lastConfigSize;
    private static int configWatchTicks;

    private ClientEvents() {
    }

    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(RECORD_KEY);
        event.register(CONFIG_KEY);
    }

    public static void onConfigReloading(ModConfigEvent.Reloading event) {
        reloadConfig();
    }

    /**
     * Forge's watcher is normally sufficient, but some launchers/paths do not
     * deliver file events reliably. This one-second mtime/size check is a cheap
     * fallback and also makes edits applied through the in-game screen
     * deterministic.
     */
    private static void tickConfigWatcher(Minecraft minecraft) {
        if (++configWatchTicks < 20) {
            return;
        }
        configWatchTicks = 0;
        try {
            Path path = FMLPaths.CONFIGDIR.get().resolve("simplerecorder-client.toml");
            if (!Files.isRegularFile(path)) {
                return;
            }
            long modified = Files.getLastModifiedTime(path).toMillis();
            long size = Files.size(path);
            if (lastConfigModified == 0L) {
                lastConfigModified = modified;
                lastConfigSize = size;
                return;
            }
            if (modified != lastConfigModified || size != lastConfigSize) {
                lastConfigModified = modified;
                lastConfigSize = size;
                RecorderConfig.SPEC.afterReload();
                reloadConfig();
            }
        } catch (IOException | RuntimeException ignored) {
            // A missing/partially written file is retried on the next tick.
        }
    }

    private static void reloadConfig() {
        AvifSupport.reset();
        LOGGER.info("Config hot-reloaded: format={}, fps={}, scale={}%, quality={}, speed={}, loop={}, "
                        + "conversion={}, cpuThreads={}",
                RecorderConfig.FORMAT.get(), RecorderConfig.FPS.get(),
                Math.round(RecorderConfig.SCALE.get() * 100.0D), RecorderConfig.AVIF_QUALITY.get(),
                RecorderConfig.AVIF_SPEED.get(), RecorderConfig.LOOP.get(),
                RecorderConfig.AVIF_CONVERSION.get(), RecorderConfig.AVIF_CPU_THREADS.get());
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof RecorderConfigScreen screen) {
            minecraft.execute(screen::reloadFromConfig);
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        while (RECORD_KEY.consumeClick()) {
            CONTROLLER.toggle(minecraft);
        }
        while (CONFIG_KEY.consumeClick()) {
            minecraft.setScreen(new RecorderConfigScreen(minecraft.screen));
        }
        tickConfigWatcher(minecraft);
        CONTROLLER.tick(minecraft);
    }

    /**
     * Runs after normal-priority HUD overlays (Jade/WTHIT/etc.) have queued
     * their geometry. The flush plus LOWEST priority makes those tooltips part
     * of the capture. Our own REC indicator is drawn only after the readback
     * request, so it never enters the file.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRenderHudForCapture(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen == null) {
            CONTROLLER.onRenderFrame(minecraft, event.getGuiGraphics());
            CONTROLLER.renderOverlay(minecraft, event.getGuiGraphics());
        }
    }

    /**
     * HUD rendering happens before an open screen. When a screen is active, wait
     * until the screen has been drawn so the video contains what the player sees.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onScreenForCapture(ScreenEvent.Render.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        // Forge also renders background screen layers through drawScreen; only
        // the top-level screen event represents the final image.
        if (minecraft.screen != null && event.getScreen() == minecraft.screen) {
            CONTROLLER.onRenderFrame(minecraft, event.getGuiGraphics());
            CONTROLLER.renderOverlay(minecraft, event.getGuiGraphics());
        }
    }

    /**
     * With F1 (hidden HUD) and no screen open, vanilla never calls Gui.render,
     * so RenderGuiEvent.Post does not fire. Capture the finished level instead.
     */
    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen == null && minecraft.options.hideGui) {
            CONTROLLER.onRenderFrame(minecraft, null);
        }
    }

    /**
     * KeyMapping clicks are not processed while a screen is open. This fallback
     * keeps F8 usable in the inventory or pause menu, without stealing the key
     * from the controls screen where the user may be rebinding it.
     */
    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen == null || minecraft.screen instanceof KeyBindsScreen) {
            return;
        }
        if (event.getAction() != InputConstants.PRESS) {
            return;
        }
        if (event.getKey() == RECORD_KEY.getKey().getValue()) {
            CONTROLLER.toggle(minecraft);
        }
    }
}
