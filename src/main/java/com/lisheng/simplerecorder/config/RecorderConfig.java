package com.lisheng.simplerecorder.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client settings. Forge watches this TOML file and applies edits without a
 * restart; recording reads the live values when a recording starts.
 */
public final class RecorderConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.EnumValue<OutputFormat> FORMAT;
    public static final ModConfigSpec.IntValue FPS;
    public static final ModConfigSpec.DoubleValue SCALE;
    public static final ModConfigSpec.BooleanValue LOOP;
    public static final ModConfigSpec.BooleanValue WARN_HIGH_SETTINGS;
    public static final ModConfigSpec.IntValue AVIF_QUALITY;
    public static final ModConfigSpec.IntValue AVIF_SPEED;
    public static final ModConfigSpec.EnumValue<YuvMode> AVIF_CONVERSION;
    public static final ModConfigSpec.IntValue AVIF_CPU_THREADS;
    public static final ModConfigSpec.ConfigValue<String> AVIF_ENCODER_PATH;
    public static final ModConfigSpec.ConfigValue<String> AVIF_DOWNLOAD_URL;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment("SimpleRecorder client settings. The file is watched for changes; edits apply to the next recording.");
        builder.push("output");

        FORMAT = builder
                .comment("Default container: AVIF (small, modern) or GIF (compatibility fallback).")
                .defineEnum("format", OutputFormat.AVIF);

        FPS = builder
                .comment("Capture rate. 30 is the default. Range: 5-60.")
                .defineInRange("fps", 30, 5, 60);

        SCALE = builder
                .comment("Output size multiplier. 0.5 means 50% width and 50% height. Range: 0.1-1.0.")
                .defineInRange("scale", 0.5D, 0.1D, 1.0D);

        LOOP = builder
                .comment("Loop the exported animation forever.")
                .define("loop", true);

        WARN_HIGH_SETTINGS = builder
                .comment("Warn in chat when the estimated capture load is very high.")
                .define("warnHighSettings", true);

        builder.pop();

        builder.comment("AVIF encoder settings. Ignored when the format is GIF.").push("avif");

        AVIF_QUALITY = builder
                .comment("AVIF quality (20-100). 60 is a good size/quality balance for gameplay.")
                .defineInRange("quality", 60, 20, 100);

        AVIF_SPEED = builder
                .comment("AVIF encoder speed (5-10). Higher is faster and larger. 8 is near real-time.")
                .defineInRange("speed", 8, 5, 10);

        AVIF_CONVERSION = builder
                .comment("RGB->YUV conversion: OPENGL (shader, default) or MULTITHREAD (CPU fallback).")
                .defineEnum("conversion", YuvMode.OPENGL);

        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        AVIF_CPU_THREADS = builder
                .comment("CPU conversion threads. Range: 1-" + cores + " (this machine's logical CPU count).")
                .defineInRange("cpuThreads", Math.min(4, cores), 1, cores);

        AVIF_ENCODER_PATH = builder
                .comment("Optional path to avifenc. Leave empty to use the cached/downloaded encoder, then PATH.")
                .define("encoderPath", "");

        AVIF_DOWNLOAD_URL = builder
                .comment("Optional zip URL containing avifenc. Overrides the official libavif download for this platform.")
                .define("downloadUrl", "");

        builder.pop();

        SPEC = builder.build();
    }

    private RecorderConfig() {
    }
}
