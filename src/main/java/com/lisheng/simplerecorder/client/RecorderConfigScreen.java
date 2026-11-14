package com.lisheng.simplerecorder.client;

import com.lisheng.simplerecorder.config.OutputFormat;
import com.lisheng.simplerecorder.config.RecorderConfig;
import com.lisheng.simplerecorder.config.YuvMode;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * In-game settings.
 *
 * <p>Every row is a single button that cycles its value, which keeps the screen
 * usable on any GUI scale without depending on the slider widget internals of
 * this Minecraft version. Values are written to the properties file
 * immediately, so the screen and hand edits share one source of truth.</p>
 */
public final class RecorderConfigScreen extends Screen {
    private static final int[] FPS_CHOICES = {5, 10, 15, 20, 24, 30, 45, 60};
    private static final double[] SCALE_CHOICES = {0.1D, 0.25D, 0.5D, 0.75D, 1.0D};
    private static final int[] QUALITY_CHOICES = {20, 30, 40, 50, 60, 70, 80, 90, 100};
    private static final int[] SPEED_CHOICES = {5, 6, 7, 8, 9, 10};

    private final Screen parent;

    public RecorderConfigScreen(Screen parent) {
        super(Component.translatable("screen.simplerecorder.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int rowHeight = 22;
        int gap = 4;
        int columnWidth = 200;
        int left = this.width / 2 - columnWidth - 4;
        int right = this.width / 2 + 4;
        int top = 40;

        addRenderableWidget(button(Component.translatable("screen.simplerecorder.format_arg",
                        RecorderConfig.format().name()),
                left, top, columnWidth, () -> {
                    OutputFormat next = RecorderConfig.format() == OutputFormat.AVIF
                            ? OutputFormat.GIF : OutputFormat.AVIF;
                    RecorderConfig.set(RecorderConfig.KEY_FORMAT, next.name());
                }));

        addRenderableWidget(button(Component.translatable("screen.simplerecorder.fps_arg",
                        RecorderConfig.fps()),
                right, top, columnWidth, () -> {
                    int current = RecorderConfig.fps();
                    int next = FPS_CHOICES[0];
                    for (int i = 0; i < FPS_CHOICES.length; i++) {
                        if (FPS_CHOICES[i] == current) {
                            next = FPS_CHOICES[(i + 1) % FPS_CHOICES.length];
                            break;
                        }
                    }
                    RecorderConfig.set(RecorderConfig.KEY_FPS, Integer.toString(next));
                }));

        addRenderableWidget(button(Component.translatable("screen.simplerecorder.scale_arg",
                        percent(RecorderConfig.scale())),
                left, top + (rowHeight + gap), columnWidth, () -> {
                    double current = RecorderConfig.scale();
                    double next = SCALE_CHOICES[0];
                    for (int i = 0; i < SCALE_CHOICES.length; i++) {
                        if (Math.abs(SCALE_CHOICES[i] - current) < 1.0E-4D) {
                            next = SCALE_CHOICES[(i + 1) % SCALE_CHOICES.length];
                            break;
                        }
                    }
                    RecorderConfig.set(RecorderConfig.KEY_SCALE, String.format(Locale.ROOT, "%.2f", next));
                }));

        addRenderableWidget(button(Component.translatable("screen.simplerecorder.quality_arg",
                        RecorderConfig.avifQuality()),
                right, top + (rowHeight + gap), columnWidth, () -> {
                    int current = RecorderConfig.avifQuality();
                    int next = QUALITY_CHOICES[0];
                    for (int i = 0; i < QUALITY_CHOICES.length; i++) {
                        if (QUALITY_CHOICES[i] == current) {
                            next = QUALITY_CHOICES[(i + 1) % QUALITY_CHOICES.length];
                            break;
                        }
                    }
                    RecorderConfig.set(RecorderConfig.KEY_QUALITY, Integer.toString(next));
                }));

        addRenderableWidget(button(Component.translatable("screen.simplerecorder.speed_arg",
                        RecorderConfig.avifSpeed()),
                left, top + 2 * (rowHeight + gap), columnWidth, () -> {
                    int current = RecorderConfig.avifSpeed();
                    int next = SPEED_CHOICES[0];
                    for (int i = 0; i < SPEED_CHOICES.length; i++) {
                        if (SPEED_CHOICES[i] == current) {
                            next = SPEED_CHOICES[(i + 1) % SPEED_CHOICES.length];
                            break;
                        }
                    }
                    RecorderConfig.set(RecorderConfig.KEY_SPEED, Integer.toString(next));
                }));

        addRenderableWidget(button(Component.translatable("screen.simplerecorder.conversion_arg",
                        RecorderConfig.yuvMode().name()),
                right, top + 2 * (rowHeight + gap), columnWidth, () -> {
                    YuvMode next = RecorderConfig.yuvMode() == YuvMode.OPENGL
                            ? YuvMode.MULTITHREAD : YuvMode.OPENGL;
                    RecorderConfig.set(RecorderConfig.KEY_CONVERSION, next.name());
                }));

        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        addRenderableWidget(button(Component.translatable("screen.simplerecorder.cpu_threads_arg",
                        RecorderConfig.avifCpuThreads()),
                left, top + 3 * (rowHeight + gap), columnWidth, () -> {
                    int next = RecorderConfig.avifCpuThreads() % cores + 1;
                    RecorderConfig.set(RecorderConfig.KEY_CPU_THREADS, Integer.toString(next));
                }));

        addRenderableWidget(button(Component.translatable("screen.simplerecorder.loop_arg",
                        loopLabel()),
                right, top + 3 * (rowHeight + gap), columnWidth, () ->
                        RecorderConfig.set(RecorderConfig.KEY_LOOP, Boolean.toString(!RecorderConfig.loop()))));

        addRenderableWidget(button(Component.translatable("screen.simplerecorder.backend_arg",
                        RecorderConfig.captureBackend()),
                left, top + 4 * (rowHeight + gap), columnWidth, () -> {
                    String current = RecorderConfig.captureBackend();
                    String next = switch (current) {
                        case "auto" -> "opengl";
                        case "opengl" -> "vulkan";
                        default -> "auto";
                    };
                    RecorderConfig.set(RecorderConfig.KEY_BACKEND, next);
                }));

        addRenderableWidget(button(Component.translatable("screen.simplerecorder.done"),
                right, top + 5 * (rowHeight + gap), columnWidth, this::onClose));
    }

    private Button button(Component label, int x, int y, int width, Runnable action) {
        return Button.builder(label, b -> {
            action.run();
            // Rebuild so the labels show the new values immediately.
            this.rebuildWidgets();
        }).bounds(x, y, width, 20).build();
    }

    private Component loopLabel() {
        return Component.translatable(RecorderConfig.loop() ? "options.on" : "options.off");
    }

    private static String percent(double scale) {
        return Math.round(scale * 100.0D) + "%";
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(this.font, this.title, this.width / 2, 16, 0xFFFFFFFF);
        graphics.centeredText(this.font, Component.translatable("screen.simplerecorder.hint"),
                this.width / 2, this.height - 46, 0xFFA0A0A0);
        graphics.centeredText(this.font, Component.literal(SimpleRecorderClient.describeState()),
                this.width / 2, this.height - 32, 0xFFA0A0A0);
        graphics.centeredText(this.font, Component.translatable("screen.simplerecorder.backend_note"),
                this.width / 2, this.height - 18, 0xFF808080);
    }

    @Override
    public void onClose() {
        Minecraft minecraft = this.minecraft;
        if (minecraft != null) {
            minecraft.setScreenAndShow(this.parent);
        }
    }
}
