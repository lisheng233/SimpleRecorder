package com.lisheng.simplerecorder.config;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

/**
 * Client settings stored in {@code config/simplerecorder.properties}.
 *
 * <p>26.3 has no config registry of its own on Fabric, so this file is the
 * source of truth. The file's mtime is checked on every read, which gives the
 * same hot-reload behaviour the Forge ports get from their TOML watcher: edit
 * the file, start the next recording, the new values apply without a restart.</p>
 */
public final class RecorderConfig {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String KEY_FORMAT = "output.format";
    public static final String KEY_FPS = "output.fps";
    public static final String KEY_SCALE = "output.scale";
    public static final String KEY_LOOP = "output.loop";
    public static final String KEY_WARN_HIGH = "output.warnHighSettings";
    public static final String KEY_QUALITY = "avif.quality";
    public static final String KEY_SPEED = "avif.speed";
    public static final String KEY_CONVERSION = "avif.conversion";
    public static final String KEY_CPU_THREADS = "avif.cpuThreads";
    public static final String KEY_ENCODER_PATH = "avif.encoderPath";
    public static final String KEY_DOWNLOAD_URL = "avif.downloadUrl";
    public static final String KEY_BACKEND = "capture.backend";
    public static final String KEY_INDICATOR = "output.indicatorInRecording";

    private static final Properties PROPS = new Properties();
    private static long loadedStamp = Long.MIN_VALUE;
    private static boolean loadedOnce;

    private RecorderConfig() {
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("simplerecorder.properties");
    }

    private static synchronized void ensureLoaded() {
        Path path = file();
        long stamp;
        try {
            stamp = Files.exists(path) ? Files.getLastModifiedTime(path).toMillis() : -1L;
            if (stamp == -1L) {
                writeDefaults(path);
                stamp = Files.getLastModifiedTime(path).toMillis();
            }
        } catch (IOException exception) {
            LOGGER.warn("Could not inspect {}", path, exception);
            stamp = loadedStamp;
        }
        if (loadedOnce && stamp == loadedStamp) {
            return;
        }
        try (InputStream in = Files.newInputStream(path)) {
            PROPS.clear();
            PROPS.load(in);
            loadedStamp = stamp;
            loadedOnce = true;
        } catch (IOException exception) {
            LOGGER.warn("Could not read {}", path, exception);
        }
    }

    private static void writeDefaults(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Properties defaults = new Properties();
        defaults.setProperty(KEY_FORMAT, "AVIF");
        defaults.setProperty(KEY_FPS, "30");
        defaults.setProperty(KEY_SCALE, "0.5");
        defaults.setProperty(KEY_LOOP, "true");
        defaults.setProperty(KEY_WARN_HIGH, "true");
        defaults.setProperty(KEY_QUALITY, "60");
        defaults.setProperty(KEY_SPEED, "8");
        defaults.setProperty(KEY_CONVERSION, "MULTITHREAD");
        defaults.setProperty(KEY_CPU_THREADS, String.valueOf(Math.min(4, Math.max(1,
                Runtime.getRuntime().availableProcessors()))));
        defaults.setProperty(KEY_ENCODER_PATH, "");
        defaults.setProperty(KEY_DOWNLOAD_URL, "");
        defaults.setProperty(KEY_BACKEND, "auto");
        defaults.setProperty(KEY_INDICATOR, "false");
        try (OutputStream out = Files.newOutputStream(path)) {
            defaults.store(out, "SimpleRecorder client settings. Edits apply to the next recording.");
        }
    }

    private static String raw(String key, String fallback) {
        ensureLoaded();
        String value = PROPS.getProperty(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int intValue(String key, int fallback, int min, int max) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(raw(key, Integer.toString(fallback)))));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static double doubleValue(String key, double fallback, double min, double max) {
        try {
            return Math.max(min, Math.min(max, Double.parseDouble(raw(key, Double.toString(fallback)))));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static boolean boolValue(String key, boolean fallback) {
        return Boolean.parseBoolean(raw(key, Boolean.toString(fallback)));
    }

    public static OutputFormat format() {
        String value = raw(KEY_FORMAT, OutputFormat.AVIF.name());
        try {
            return OutputFormat.valueOf(value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return OutputFormat.AVIF;
        }
    }

    public static int fps() {
        return intValue(KEY_FPS, 30, 5, 60);
    }

    public static double scale() {
        return doubleValue(KEY_SCALE, 0.5D, 0.1D, 1.0D);
    }

    public static boolean loop() {
        return boolValue(KEY_LOOP, true);
    }

    public static boolean warnHighSettings() {
        return boolValue(KEY_WARN_HIGH, true);
    }

    public static int avifQuality() {
        return intValue(KEY_QUALITY, 60, 20, 100);
    }

    public static int avifSpeed() {
        return intValue(KEY_SPEED, 8, 5, 10);
    }

    /**
     * {@code OPENGL} asks for shader-side RGB to YUV conversion. 26.3 runs on
     * either the OpenGL or the Vulkan backend, and the shader conversion was an
     * OpenGL-only path, so this port always converts on the CPU and reports the
     * downgrade once instead of silently doing the wrong thing.
     */
    public static YuvMode yuvMode() {
        String value = raw(KEY_CONVERSION, YuvMode.MULTITHREAD.name());
        try {
            return YuvMode.valueOf(value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return YuvMode.MULTITHREAD;
        }
    }

    public static int avifCpuThreads() {
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        return intValue(KEY_CPU_THREADS, Math.min(4, cores), 1, cores);
    }

    public static String avifEncoderPath() {
        return raw(KEY_ENCODER_PATH, "");
    }

    public static String avifDownloadUrl() {
        return raw(KEY_DOWNLOAD_URL, "");
    }

    /**
     * Draw the REC badge while recording.
     *
     * <p>26.3 composites the whole GUI pass into the target the recorder reads,
     * so a badge drawn during a recording is part of the exported frames. Off by
     * default; the paused badge is always drawn because paused frames are not
     * captured.</p>
     */
    public static boolean indicatorInRecording() {
        return boolValue(KEY_INDICATOR, false);
    }

    /** {@code auto}, {@code opengl} or {@code vulkan}: only used for diagnostics. */
    public static String captureBackend() {
        return raw(KEY_BACKEND, "auto").toLowerCase(java.util.Locale.ROOT);
    }

    public static synchronized void set(String key, String value) {
        ensureLoaded();
        PROPS.setProperty(key, value);
        save();
    }

    public static synchronized void save() {
        Path path = file();
        try (OutputStream out = Files.newOutputStream(path)) {
            PROPS.store(out, "SimpleRecorder client settings. Edits apply to the next recording.");
            loadedStamp = Files.getLastModifiedTime(path).toMillis();
        } catch (IOException exception) {
            LOGGER.warn("Could not write {}", path, exception);
        }
    }
}
