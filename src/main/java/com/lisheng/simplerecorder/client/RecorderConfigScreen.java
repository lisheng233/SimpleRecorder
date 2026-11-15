package com.lisheng.simplerecorder.client;

import com.lisheng.simplerecorder.config.OutputFormat;
import com.lisheng.simplerecorder.config.RecorderConfig;
import com.lisheng.simplerecorder.config.ConvertMode;
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * In-game settings.
 *
 * <p>Numeric settings are sliders so they can be dragged and fine-tuned; the
 * enum-like settings are buttons that cycle through their values. Every change
 * is written straight to {@code config/simplerecorder.properties}, the same file
 * the recorder reads when a recording starts.</p>
 */
public final class RecorderConfigScreen extends Screen {
    private static final int ROW_HEIGHT = 20;
    private static final int GAP = 6;
    private static final int COLUMN_WIDTH = 200;

    private final Screen parent;

    public RecorderConfigScreen(Screen parent) {
        super(Component.translatable("screen.simplerecorder.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int left = this.width / 2 - COLUMN_WIDTH - 4;
        int right = this.width / 2 + 4;
        int top = 36;
        int step = ROW_HEIGHT + GAP;

        // Numeric values: sliders.
        addRenderableWidget(new IntSlider(left, top, COLUMN_WIDTH, ROW_HEIGHT,
                "screen.simplerecorder.fps", 5, 60,
                RecorderConfig::fps, value -> RecorderConfig.set(RecorderConfig.KEY_FPS, Integer.toString(value))));

        addRenderableWidget(new IntSlider(right, top, COLUMN_WIDTH, ROW_HEIGHT,
                "screen.simplerecorder.scale_percent", 10, 100,
                () -> (int) Math.round(RecorderConfig.scale() * 100.0D),
                value -> RecorderConfig.set(RecorderConfig.KEY_SCALE,
                        String.format(Locale.ROOT, "%.2f", value / 100.0D))));

        addRenderableWidget(new IntSlider(left, top + step, COLUMN_WIDTH, ROW_HEIGHT,
                "screen.simplerecorder.quality", 20, 100,
                RecorderConfig::avifQuality,
                value -> RecorderConfig.set(RecorderConfig.KEY_QUALITY, Integer.toString(value))));

        addRenderableWidget(new IntSlider(right, top + step, COLUMN_WIDTH, ROW_HEIGHT,
                "screen.simplerecorder.speed", 5, 10,
                RecorderConfig::avifSpeed,
                value -> RecorderConfig.set(RecorderConfig.KEY_SPEED, Integer.toString(value))));

        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        addRenderableWidget(new IntSlider(left, top + 2 * step, COLUMN_WIDTH, ROW_HEIGHT,
                "screen.simplerecorder.cpu_threads", 1, cores,
                RecorderConfig::avifCpuThreads,
                value -> RecorderConfig.set(RecorderConfig.KEY_CPU_THREADS, Integer.toString(value))));

        // Enumerations: cycling buttons.
        addRenderableWidget(cycle(right, top + 2 * step,
                () -> Component.translatable("screen.simplerecorder.format_arg", RecorderConfig.format().name()),
                () -> {
                    OutputFormat next = RecorderConfig.format() == OutputFormat.AVIF
                            ? OutputFormat.GIF : OutputFormat.AVIF;
                    RecorderConfig.set(RecorderConfig.KEY_FORMAT, next.name());
                }));

        addRenderableWidget(cycle(left, top + 3 * step,
                () -> Component.translatable("screen.simplerecorder.convert_arg",
                        RecorderConfig.convertMode().displayName()),
                () -> {
                    ConvertMode next = RecorderConfig.convertMode() == ConvertMode.GPU
                            ? ConvertMode.CPU : ConvertMode.GPU;
                    RecorderConfig.set(RecorderConfig.KEY_CONVERT, next.name());
                }));

        addRenderableWidget(cycle(right, top + 3 * step,
                () -> Component.translatable("screen.simplerecorder.loop_arg", loopLabel()),
                () -> RecorderConfig.set(RecorderConfig.KEY_LOOP, Boolean.toString(!RecorderConfig.loop()))));

        addRenderableWidget(cycle(left, top + 4 * step,
                () -> Component.translatable("screen.simplerecorder.backend_arg", RecorderConfig.captureBackend()),
                () -> {
                    String next = switch (RecorderConfig.captureBackend()) {
                        case "auto" -> "opengl";
                        case "opengl" -> "vulkan";
                        default -> "auto";
                    };
                    RecorderConfig.set(RecorderConfig.KEY_BACKEND, next);
                }));

        addRenderableWidget(cycle(right, top + 4 * step,
                () -> Component.translatable("screen.simplerecorder.indicator_arg",
                        Component.translatable(RecorderConfig.indicatorInRecording()
                                ? "options.on" : "options.off")),
                () -> RecorderConfig.set(RecorderConfig.KEY_INDICATOR,
                        Boolean.toString(!RecorderConfig.indicatorInRecording()))));

        addRenderableWidget(Button.builder(Component.translatable("screen.simplerecorder.done"),
                        b -> this.onClose())
                .bounds(right, top + 5 * step, COLUMN_WIDTH, ROW_HEIGHT)
                .build());
    }

    private Button cycle(int x, int y, java.util.function.Supplier<Component> label, Runnable action) {
        Button button = Button.builder(label.get(), b -> {
            action.run();
            this.rebuildWidgets();
        }).bounds(x, y, COLUMN_WIDTH, ROW_HEIGHT).build();
        return button;
    }

    private Component loopLabel() {
        return Component.translatable(RecorderConfig.loop() ? "options.on" : "options.off");
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

    /** Slider bound to one integer config value. */
    private static final class IntSlider extends AbstractSliderButton {
        private final String labelKey;
        private final int min;
        private final int max;
        private final IntSupplier getter;
        private final IntConsumer setter;

        IntSlider(int x, int y, int width, int height, String labelKey, int min, int max,
                  IntSupplier getter, IntConsumer setter) {
            super(x, y, width, height, Component.empty(), toFraction(getter.getAsInt(), min, max));
            this.labelKey = labelKey;
            this.min = min;
            this.max = max;
            this.getter = getter;
            this.setter = setter;
            updateMessage();
        }

        private static double toFraction(int value, int min, int max) {
            if (max <= min) {
                return 0.0D;
            }
            return (double) (Math.max(min, Math.min(max, value)) - min) / (double) (max - min);
        }

        private int currentValue() {
            return (int) Math.round(min + this.value * (max - min));
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable(labelKey + "_arg", currentValue()));
        }

        @Override
        protected void applyValue() {
            setter.accept(currentValue());
        }
    }
}
