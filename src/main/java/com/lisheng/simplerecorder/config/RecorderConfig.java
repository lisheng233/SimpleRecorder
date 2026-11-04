package com.lisheng.simplerecorder.config;

import java.io.File;
import net.minecraftforge.common.config.Configuration;

/**
 * Client settings.
 *
 * <p>1.12.2 has no {@code ForgeConfigSpec}, so this wraps a Forge
 * {@link Configuration} file. The accessors keep the {@code get()}/{@code set()}
 * shape used by the rest of the mod, which means the recording pipeline reads
 * live values and an edit to {@code config/simplerecorder.cfg} applies to the
 * next recording without a restart.</p>
 */
public final class RecorderConfig {
    private static final String CATEGORY_OUTPUT = "output";
    private static final String CATEGORY_AVIF = "avif";

    public static final EnumValue<OutputFormat> FORMAT = new EnumValue<OutputFormat>(OutputFormat.AVIF);
    public static final IntValue FPS = new IntValue(30);
    public static final DoubleValue SCALE = new DoubleValue(0.5D);
    public static final BooleanValue LOOP = new BooleanValue(true);
    public static final BooleanValue WARN_HIGH_SETTINGS = new BooleanValue(true);
    public static final IntValue AVIF_QUALITY = new IntValue(60);
    public static final IntValue AVIF_SPEED = new IntValue(8);
    public static final EnumValue<YuvMode> AVIF_CONVERSION = new EnumValue<YuvMode>(YuvMode.OPENGL);
    public static final IntValue AVIF_CPU_THREADS = new IntValue(4);
    public static final StringValue AVIF_ENCODER_PATH = new StringValue("");
    public static final StringValue AVIF_DOWNLOAD_URL = new StringValue("");

    private static final String[] FORMAT_NAMES = {"AVIF", "GIF"};
    private static final String[] CONVERSION_NAMES = {"OPENGL", "MULTITHREAD"};

    private static volatile File configFile;
    private static volatile Configuration configuration;

    private RecorderConfig() {
    }

    public static File configFile() {
        return configFile;
    }

    /**
     * Directory that holds {@code simplerecorder.cfg}; used for the AVIF encoder
     * cache so it stays next to the config instead of inside the jar.
     */
    public static File configDirectory() {
        File file = configFile;
        if (file != null && file.getParentFile() != null) {
            return file.getParentFile();
        }
        return new File("config");
    }

    public static synchronized void load(File file) {
        configFile = file;
        Configuration config = new Configuration(file);
        config.load();
        read(config);
        configuration = config;
    }

    /**
     * Re-reads the file from disk. Safe to call on the client thread: the values
     * are plain volatile fields, so an in-flight recording keeps its snapshot.
     */
    public static synchronized void reload() {
        File file = configFile;
        if (file == null) {
            return;
        }
        Configuration config = new Configuration(file);
        config.load();
        read(config);
        configuration = config;
    }

    public static synchronized void save() {
        Configuration config = configuration;
        if (config == null) {
            return;
        }
        write(config);
        config.save();
    }

    private static void read(Configuration config) {
        FORMAT.set(readEnum(config, "format", CATEGORY_OUTPUT, OutputFormat.AVIF, FORMAT_NAMES));

        FPS.set(clamp(config.getInt("fps", CATEGORY_OUTPUT, 30, 5, 60,
                "Capture rate. 30 is the default. Range: 5-60."), 5, 60));

        SCALE.set(clamp(config.get("scale", CATEGORY_OUTPUT, 0.5D,
                "Output size multiplier. 0.5 means 50% width and 50% height. Range: 0.1-1.0.",
                0.1D, 1.0D).getDouble(), 0.1D, 1.0D));

        LOOP.set(config.getBoolean("loop", CATEGORY_OUTPUT, true,
                "Loop the exported animation forever."));

        WARN_HIGH_SETTINGS.set(config.getBoolean("warnHighSettings", CATEGORY_OUTPUT, true,
                "Warn in chat when the estimated capture load is very high."));

        AVIF_QUALITY.set(clamp(config.getInt("quality", CATEGORY_AVIF, 60, 20, 100,
                "AVIF quality (20-100). 60 is a good size/quality balance for gameplay."), 20, 100));

        AVIF_SPEED.set(clamp(config.getInt("speed", CATEGORY_AVIF, 8, 5, 10,
                "AVIF encoder speed (5-10). Higher is faster and larger. 8 is near real-time."), 5, 10));

        AVIF_CONVERSION.set(readEnum(config, "conversion", CATEGORY_AVIF, YuvMode.OPENGL, CONVERSION_NAMES));

        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        AVIF_CPU_THREADS.set(clamp(config.getInt("cpuThreads", CATEGORY_AVIF, Math.min(4, cores), 1, cores,
                "CPU conversion threads. Range: 1-" + cores + " (this machine's logical CPU count)."),
                1, cores));

        AVIF_ENCODER_PATH.set(config.getString("encoderPath", CATEGORY_AVIF, "",
                "Optional path to avifenc. Leave empty to use the cached/downloaded encoder, then PATH.").trim());

        AVIF_DOWNLOAD_URL.set(config.getString("downloadUrl", CATEGORY_AVIF, "",
                "Optional zip URL containing avifenc. Overrides the official libavif download for this platform.").trim());
    }

    private static void write(Configuration config) {
        config.get("format", CATEGORY_OUTPUT, OutputFormat.AVIF.name(),
                "Default container: AVIF (small, modern) or GIF (compatibility fallback).",
                FORMAT_NAMES).set(FORMAT.get().name());
        config.get("fps", CATEGORY_OUTPUT, 30,
                "Capture rate. 30 is the default. Range: 5-60.", 5, 60).set(FPS.get());
        config.get("scale", CATEGORY_OUTPUT, 0.5D,
                "Output size multiplier. 0.5 means 50% width and 50% height. Range: 0.1-1.0.", 0.1D, 1.0D)
                .set(SCALE.get());
        config.get("loop", CATEGORY_OUTPUT, true,
                "Loop the exported animation forever.").set(LOOP.get());
        config.get("warnHighSettings", CATEGORY_OUTPUT, true,
                "Warn in chat when the estimated capture load is very high.")
                .set(WARN_HIGH_SETTINGS.get());
        config.get("quality", CATEGORY_AVIF, 60,
                "AVIF quality (20-100). 60 is a good size/quality balance for gameplay.", 20, 100)
                .set(AVIF_QUALITY.get());
        config.get("speed", CATEGORY_AVIF, 8,
                "AVIF encoder speed (5-10). Higher is faster and larger. 8 is near real-time.", 5, 10)
                .set(AVIF_SPEED.get());
        config.get("conversion", CATEGORY_AVIF, YuvMode.OPENGL.name(),
                "RGB->YUV conversion: OPENGL (shader, default) or MULTITHREAD (CPU fallback).",
                CONVERSION_NAMES).set(AVIF_CONVERSION.get().name());

        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        config.get("cpuThreads", CATEGORY_AVIF, Math.min(4, cores),
                "CPU conversion threads. Range: 1-" + cores + " (this machine's logical CPU count).", 1, cores)
                .set(AVIF_CPU_THREADS.get());
        config.get("encoderPath", CATEGORY_AVIF, "",
                "Optional path to avifenc. Leave empty to use the cached/downloaded encoder, then PATH.")
                .set(AVIF_ENCODER_PATH.get());
        config.get("downloadUrl", CATEGORY_AVIF, "",
                "Optional zip URL containing avifenc. Overrides the official libavif download for this platform.")
                .set(AVIF_DOWNLOAD_URL.get());
    }

    private static <T extends Enum<T>> T readEnum(Configuration config, String name, String category,
                                                  T fallback, String[] validValues) {
        String raw = config.getString(name, category, fallback.name(), "", validValues).trim();
        for (String candidate : validValues) {
            if (candidate.equalsIgnoreCase(raw)) {
                return Enum.valueOf(fallback.getDeclaringClass(), candidate);
            }
        }
        return fallback;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Plain mutable int, mirroring the {@code ForgeConfigSpec.IntValue} shape. */
    public static final class IntValue {
        private volatile int value;

        IntValue(int value) {
            this.value = value;
        }

        public int get() {
            return value;
        }

        public void set(int next) {
            this.value = next;
        }
    }

    /** Plain mutable double. */
    public static final class DoubleValue {
        private volatile double value;

        DoubleValue(double value) {
            this.value = value;
        }

        public double get() {
            return value;
        }

        public void set(double next) {
            this.value = next;
        }
    }

    /** Plain mutable boolean. */
    public static final class BooleanValue {
        private volatile boolean value;

        BooleanValue(boolean value) {
            this.value = value;
        }

        public boolean get() {
            return value;
        }

        public void set(boolean next) {
            this.value = next;
        }
    }

    /** Plain mutable string. */
    public static final class StringValue {
        private volatile String value;

        StringValue(String value) {
            this.value = value;
        }

        public String get() {
            return value;
        }

        public void set(String next) {
            this.value = next == null ? "" : next;
        }
    }

    /** Plain mutable enum. */
    public static final class EnumValue<T extends Enum<T>> {
        private volatile T value;

        EnumValue(T value) {
            this.value = value;
        }

        public T get() {
            return value;
        }

        public void set(T next) {
            if (next != null) {
                this.value = next;
            }
        }
    }
}
