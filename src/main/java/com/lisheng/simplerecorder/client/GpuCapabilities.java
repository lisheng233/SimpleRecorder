package com.lisheng.simplerecorder.client;

import com.lisheng.simplerecorder.config.RecorderConfig;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.GpuDevice;
import java.util.Locale;
import org.slf4j.Logger;

/**
 * Which GPU backend Minecraft 26.3 is actually running on.
 *
 * <p>26.3 no longer has a single OpenGL renderer: the game talks to a backend
 * through {@code com.mojang.renderpearl} and ships both an OpenGL and a Vulkan
 * implementation. Everything a mod does with the GPU therefore has to go
 * through that layer; raw {@code org.lwjgl.opengl} calls only work on the
 * OpenGL backend and would be a hard crash on Vulkan.</p>
 *
 * <p>This mod never issues raw GL commands. The frame readback is done with
 * {@code GpuDevice}/{@code CommandEncoder} only, so the same code path runs on
 * both backends. This class exists so the choice is <em>visible</em>: it logs
 * the backend once, reports it in chat on the first recording, and warns when
 * the configured preference does not match what the game actually created
 * (the backend is picked at launch, a mod cannot switch it at runtime).</p>
 */
public final class GpuCapabilities {
    private static final Logger LOGGER = LogUtils.getLogger();

    public enum Backend {
        OPENGL,
        VULKAN,
        UNKNOWN
    }

    private static Backend backend = Backend.UNKNOWN;
    private static String backendName = "unknown";
    private static String deviceName = "unknown";
    private static boolean reported;
    private static boolean preferenceWarned;

    private GpuCapabilities() {
    }

    /** Refreshes the cached backend info. Safe to call once per frame. */
    public static void refresh() {
        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) {
            return;
        }
        DeviceInfo info = device.getDeviceInfo();
        if (info == null) {
            return;
        }
        String name = info.backendName() == null ? "unknown" : info.backendName();
        String normalized = name.toLowerCase(Locale.ROOT);
        Backend detected = normalized.contains("vulkan")
                ? Backend.VULKAN
                : normalized.contains("opengl") || normalized.contains("gl") ? Backend.OPENGL : Backend.UNKNOWN;
        if (detected != backend || !name.equals(backendName)) {
            backend = detected;
            backendName = name;
            deviceName = info.name() == null ? "unknown" : info.name();
            LOGGER.info("SimpleRecorder: GPU backend is {} ({}, driver {})", backendName, deviceName,
                    info.driverInfo());
        }
    }

    public static Backend backend() {
        return backend;
    }

    public static String backendName() {
        return backendName;
    }

    public static String deviceName() {
        return deviceName;
    }

    /**
     * @return a one-shot diagnostic line for the chat, or {@code null} when the
     *         user already saw it (or a preference mismatch was already reported)
     */
    public static String takeReport() {
        refresh();
        String preference = RecorderConfig.captureBackend();
        String mismatch = null;
        if (!preferenceWarned && !"auto".equals(preference)) {
            preferenceWarned = true;
            boolean wantsVulkan = "vulkan".equals(preference);
            boolean wantsOpenGl = "opengl".equals(preference) || "gl".equals(preference);
            if ((wantsVulkan && backend == Backend.OPENGL) || (wantsOpenGl && backend == Backend.VULKAN)) {
                mismatch = "capture.backend=" + preference + " was requested but Minecraft started with "
                        + backendName + "; the backend is chosen at launch, so " + backendName + " is used.";
            }
        }
        if (!reported) {
            reported = true;
            String message = "GPU backend: " + backendName + " (" + deviceName + ")";
            return mismatch == null ? message : message + " — " + mismatch;
        }
        return mismatch;
    }

    public static void resetReport() {
        reported = false;
    }
}
