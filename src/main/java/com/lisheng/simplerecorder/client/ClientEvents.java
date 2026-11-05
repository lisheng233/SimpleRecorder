package com.lisheng.simplerecorder.client;

import com.lisheng.simplerecorder.SimpleRecorder;
import com.lisheng.simplerecorder.avif.AvifSupport;
import com.lisheng.simplerecorder.config.RecorderConfig;
import org.apache.logging.log4j.LogManager;
import java.io.File;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiControls;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.apache.logging.log4j.Logger;
import org.lwjgl.input.Keyboard;

/**
 * Client event handlers: keybinds, capture hooks, HUD indicator and a light
 * config file watcher.
 *
 * <p>1.12.2 relies on LWJGL 2, so key presses are polled from the keyboard state
 * on the client tick instead of consumed from a GLFW callback. That also makes
 * F8/F9 work while a container or the pause menu is open.</p>
 */
public final class ClientEvents {
    private static final Logger LOGGER = LogManager.getLogger();

    public static final KeyBinding RECORD_KEY = new KeyBinding(
            "key.simplerecorder.record", Keyboard.KEY_F8, "key.categories.simplerecorder");

    public static final KeyBinding CONFIG_KEY = new KeyBinding(
            "key.simplerecorder.config", Keyboard.KEY_F9, "key.categories.simplerecorder");

    public static final KeyBinding PAUSE_KEY = new KeyBinding(
            "key.simplerecorder.pause", Keyboard.KEY_F10, "key.categories.simplerecorder");

    private static final RecordingController CONTROLLER = new RecordingController();

    private static boolean recordHeld;
    private static boolean configHeld;
    private static boolean pauseHeld;
    private static long lastConfigModified;
    private static long lastConfigSize;
    private static int configWatchTicks;

    private ClientEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        pollKeys(minecraft);
        tickConfigWatcher();
        CONTROLLER.tick(minecraft);
    }

    /**
     * Polls the raw keyboard state with edge detection. The bindings are still
     * real {@link KeyBinding}s, so the Controls screen rebinding works.
     */
    private static void pollKeys(Minecraft minecraft) {
        boolean recordDown = isDown(RECORD_KEY);
        boolean configDown = isDown(CONFIG_KEY);
        boolean pauseDown = isDown(PAUSE_KEY);

        boolean recordPressed = recordDown && !recordHeld;
        boolean configPressed = configDown && !configHeld;
        boolean pausePressed = pauseDown && !pauseHeld;
        recordHeld = recordDown;
        configHeld = configDown;
        pauseHeld = pauseDown;

        if (recordPressed && !(minecraft.currentScreen instanceof GuiControls)) {
            CONTROLLER.toggle(minecraft);
        }
        if (pausePressed) {
            CONTROLLER.togglePause(minecraft);
        }
        if (configPressed) {
            GuiScreen parent = minecraft.currentScreen;
            if (!(parent instanceof RecorderConfigScreen)) {
                minecraft.displayGuiScreen(new RecorderConfigScreen(parent));
            }
        }
    }

    private static boolean isDown(KeyBinding binding) {
        int code = binding.getKeyCode();
        return code > 0 && Keyboard.isKeyDown(code);
    }

    /**
     * Forge's own file watcher is not applied to {@code Configuration} values, so
     * a one-second mtime/size check makes external edits apply to the next
     * recording without a restart.
     */
    private static void tickConfigWatcher() {
        if (++configWatchTicks < 20) {
            return;
        }
        configWatchTicks = 0;
        File file = RecorderConfig.configFile();
        if (file == null || !file.isFile()) {
            return;
        }
        long modified = file.lastModified();
        long size = file.length();
        if (lastConfigModified == 0L) {
            lastConfigModified = modified;
            lastConfigSize = size;
            return;
        }
        if (modified != lastConfigModified || size != lastConfigSize) {
            lastConfigModified = modified;
            lastConfigSize = size;
            reloadConfig();
        }
    }

    /**
     * Called both by the file watcher and by Forge when the mod-list config GUI
     * closes.
     */
    public static void reloadConfig() {
        try {
            RecorderConfig.reload();
        } catch (RuntimeException exception) {
            LOGGER.warn("Could not reload the SimpleRecorder config", exception);
            return;
        }
        AvifSupport.reset();
        LOGGER.info("Config hot-reloaded: format={}, fps={}, scale={}%, quality={}, speed={}, loop={}, "
                        + "conversion={}, cpuThreads={}",
                RecorderConfig.FORMAT.get(), RecorderConfig.FPS.get(),
                Math.round(RecorderConfig.SCALE.get() * 100.0D), RecorderConfig.AVIF_QUALITY.get(),
                RecorderConfig.AVIF_SPEED.get(), RecorderConfig.LOOP.get(),
                RecorderConfig.AVIF_CONVERSION.get(), RecorderConfig.AVIF_CPU_THREADS.get());
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.currentScreen instanceof RecorderConfigScreen) {
            ((RecorderConfigScreen) minecraft.currentScreen).reloadFromConfig();
        }
    }

    @SubscribeEvent
    public static void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event) {
        if (SimpleRecorder.MODID.equals(event.getModID())) {
            reloadConfig();
        }
    }

    /**
     * Runs after normal-priority HUD overlays (Jade/WTHIT/etc.) have queued their
     * geometry, so those tooltips end up in the captured image. Our own REC
     * indicator is drawn only after the readback request, so it never enters the
     * file.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRenderHudForCapture(RenderGameOverlayEvent.Post event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.ALL) {
            return;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.currentScreen == null) {
            CONTROLLER.onRenderFrame(minecraft);
            CONTROLLER.renderOverlay(minecraft);
        }
    }

    /**
     * HUD rendering happens before an open screen. When a screen is active, wait
     * until the screen has been drawn so the video contains what the player sees.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onScreenForCapture(GuiScreenEvent.DrawScreenEvent.Post event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        // Forge also renders background screen layers through drawScreen; only the
        // top-level screen event represents the final image.
        if (minecraft.currentScreen != null && event.getGui() == minecraft.currentScreen) {
            CONTROLLER.onRenderFrame(minecraft);
            CONTROLLER.renderOverlay(minecraft);
        }
    }

    /**
     * With F1 (hidden HUD) and no screen open, vanilla never renders the overlay,
     * so RenderGameOverlayEvent.Post does not fire. Capture the finished world in
     * RenderWorldLastEvent instead.
     */
    @SubscribeEvent
    public static void onRenderWorldLast(RenderWorldLastEvent event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.currentScreen == null && minecraft.gameSettings.hideGUI) {
            CONTROLLER.onRenderFrame(minecraft);
        }
    }
}
