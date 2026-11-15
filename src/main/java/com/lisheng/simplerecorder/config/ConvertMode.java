package com.lisheng.simplerecorder.config;

import java.util.Locale;

/**
 * Where the RGB to YUV conversion runs.
 *
 * <p>The old values were {@code MULTITHREAD} and {@code OPENGL}: the latter named
 * a specific graphics API, which stopped making sense once Minecraft could run on
 * Vulkan as well. The choice is now only <em>CPU</em> or <em>GPU</em>; when GPU
 * is selected the concrete API is whatever renderer the game is running on (the
 * preference shown next to it in the settings screen).</p>
 */
public enum ConvertMode {
    /** Multi-threaded conversion on the CPU. Works on every backend. */
    CPU,
    /** Conversion on the GPU, using the active renderer (OpenGL or Vulkan). */
    GPU;

    public static ConvertMode fromString(String value) {
        if (value == null || value.isBlank()) {
            return CPU;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        // Migrate the pre-1.0.1 names. Both old values ran on the CPU in this
        // build, so they both map to CPU: a config file that used to say OPENGL
        // must not silently flip to a GPU path the user never tested.
        if (normalized.equals("MULTITHREAD") || normalized.equals("OPENGL")) {
            return CPU;
        }
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return CPU;
        }
    }

    public String displayName() {
        return name();
    }
}
