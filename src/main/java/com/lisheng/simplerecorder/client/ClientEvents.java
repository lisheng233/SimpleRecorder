package com.lisheng.simplerecorder.client;

import com.lisheng.simplerecorder.avif.AvifSupport;
import com.lisheng.simplerecorder.config.RecorderConfig;
import net.minecraft.client.util.InputMappings;
import org.apache.logging.log4j.LogManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.ControlsScreen;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.loading.FMLPaths;
import org.lwjgl.glfw.GLFW;
import org.apache.logging.log4j.Logger;

/**
 * Client event handlers: keybind, capture hook, HUD indicator and one extra
 * key handler so F8 also works while a container/pause screen is open.
 */
public final class ClientEvents {
    private static final Logger LOGGER = LogManager.getLogger();

    public static final KeyBinding RECORD_KEY = new KeyBinding(
            "key.simplerecorder.record",
            KeyConflictContext.IN_GAME,
            InputMappings.Type.KEYSYM,
            GLFW.GLFW_KEY_F8,
            "key.categories.simplerecorder");

    public static final KeyBinding CONFIG_KEY = new KeyBinding(
            "key.simplerecorder.config",
            KeyConflictContext.IN_GAME,
            InputMappings.Type.KEYSYM,
            GLFW.GLFW_KEY_F9,
            "key.categories.simplerecorder");

    private static final RecordingController CONTROLLER = new RecordingController();
    private static long lastConfigModified;
    private static long lastConfigSize;
    private static int configWatchTicks;

    private ClientEvents() {
    }

    /**
     * 1.18.2 has no RegisterKeyBindingsEvent; client key bindings are added
     * during FMLClientSetupEvent instead.
     */
    public static void onClientSetup() {
        ClientRegistry.registerKeyBinding(RECORD_KEY);
        ClientRegistry.registerKeyBinding(CONFIG_KEY);
    }

    public static void onConfigReloading(ModConfig.ModConfigEvent event) {
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
        if (minecraft.screen instanceof RecorderConfigScreen) {
            RecorderConfigScreen screen = (RecorderConfigScreen) minecraft.screen;
            minecraft.execute(screen::reloadFromConfig);
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
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
     * their geometry. The LOWEST priority makes those tooltips part of the
     * capture. Our own REC indicator is drawn only after the readback request,
     * so it never enters the file.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRenderHudForCapture(RenderGameOverlayEvent.Post event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.ALL) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen == null) {
            CONTROLLER.onRenderFrame(minecraft, event.getMatrixStack());
            CONTROLLER.renderOverlay(minecraft, event.getMatrixStack());
        }
    }

    /**
     * HUD rendering happens before an open screen. When a screen is active, wait
     * until the screen has been drawn so the video contains what the player sees.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onScreenForCapture(GuiScreenEvent.DrawScreenEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        // Forge also renders background screen layers through drawScreen; only
        // the top-level screen event represents the final image.
        if (minecraft.screen != null && event.getGui() == minecraft.screen) {
            CONTROLLER.onRenderFrame(minecraft, event.getMatrixStack());
            CONTROLLER.renderOverlay(minecraft, event.getMatrixStack());
        }
    }

    /**
     * With F1 (hidden HUD) and no screen open, vanilla never calls Gui.render,
     * so RenderGameOverlayEvent.Post does not fire. Capture the finished world
     * in RenderWorldLastEvent instead.
     */
    @SubscribeEvent
    public static void onRenderWorldLast(RenderWorldLastEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen == null && minecraft.options.hideGui) {
            CONTROLLER.onRenderFrame(minecraft, event.getMatrixStack());
        }
    }

    /**
     * KeyBinding clicks are not processed while a screen is open. This fallback
     * keeps F8 usable in the inventory or pause menu, without stealing the key
     * from the controls screen where the user may be rebinding it.
     */
    @SubscribeEvent
    public static void onKeyInput(InputEvent.KeyInputEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen == null || minecraft.screen instanceof ControlsScreen) {
            return;
        }
        if (event.getAction() != org.lwjgl.glfw.GLFW.GLFW_PRESS) {
            return;
        }
        if (event.getKey() == RECORD_KEY.getKey().getValue()) {
            CONTROLLER.toggle(minecraft);
        }
    }
}
