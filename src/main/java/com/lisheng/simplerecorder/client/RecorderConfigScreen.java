package com.lisheng.simplerecorder.client;

import com.lisheng.simplerecorder.avif.AvifSupport;
import com.lisheng.simplerecorder.config.OutputFormat;
import com.lisheng.simplerecorder.config.RecorderConfig;
import com.lisheng.simplerecorder.config.YuvMode;
import org.apache.logging.log4j.LogManager;
import java.util.Locale;
import java.util.function.Consumer;
import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.gui.AbstractGui;
import net.minecraft.client.gui.widget.AbstractSlider;
import net.minecraft.client.gui.widget.button.Button;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TranslationTextComponent;
import org.apache.logging.log4j.Logger;

/**
 * In-game editor for the client config. The file is also watched by Forge, so
 * editing it externally hot-reloads the same values.
 */
public final class RecorderConfigScreen extends Screen {
    private static final Logger LOGGER = LogManager.getLogger();

    private final Screen parent;

    private int fps;
    private double scalePercent;
    private OutputFormat format;
    private int quality;
    private int speed;
    private boolean loop;
    private YuvMode conversion;
    private int cpuThreads;

    private OptionSlider fpsSlider;
    private OptionSlider scaleSlider;
    private OptionSlider qualitySlider;
    private OptionSlider speedSlider;
    private OptionSlider cpuThreadsSlider;
    private Button formatButton;
    private Button loopButton;
    private Button conversionButton;

    public RecorderConfigScreen(Screen parent) {
        super(new TranslationTextComponent("screen.simplerecorder.title"));
        this.parent = parent;
        readValues();
    }

    @Override
    protected void init() {
        int center = this.width / 2;
        int x = center - 110;
        int y = 40;
        int row = 20;

        formatButton = new Button(x, y, 220, 20, formatText(), button -> {
            format = format.next();
            button.setMessage(formatText());
            updateFormatState();
        });
        addButton(formatButton);
        y += row;

        fpsSlider = new OptionSlider(new TranslationTextComponent("screen.simplerecorder.fps"),
                5, 60, 1, fps, " FPS", value -> fps = (int) Math.round(value));
        fpsSlider.setBounds(x, y, 220, 20);
        addButton(fpsSlider);
        y += row;

        scaleSlider = new OptionSlider(new TranslationTextComponent("screen.simplerecorder.scale"),
                10, 100, 1, scalePercent, "%", value -> scalePercent = value);
        scaleSlider.setBounds(x, y, 220, 20);
        addButton(scaleSlider);
        y += row;

        qualitySlider = new OptionSlider(new TranslationTextComponent("screen.simplerecorder.quality"),
                20, 100, 1, quality, "", value -> quality = (int) Math.round(value));
        qualitySlider.setBounds(x, y, 220, 20);
        addButton(qualitySlider);
        y += row;

        speedSlider = new OptionSlider(new TranslationTextComponent("screen.simplerecorder.speed"),
                5, 10, 1, speed, "", value -> speed = (int) Math.round(value));
        speedSlider.setBounds(x, y, 220, 20);
        addButton(speedSlider);
        y += row;

        loopButton = new Button(x, y, 220, 20, loopText(), button -> {
            loop = !loop;
            button.setMessage(loopText());
        });
        addButton(loopButton);
        y += row;

        conversionButton = new Button(x, y, 220, 20, conversionText(), button -> {
            conversion = conversion == YuvMode.OPENGL ? YuvMode.MULTITHREAD : YuvMode.OPENGL;
            button.setMessage(conversionText());
            updateFormatState();
        });
        addButton(conversionButton);
        y += row;

        cpuThreadsSlider = new OptionSlider(new TranslationTextComponent("screen.simplerecorder.cpu_threads"),
                1, Math.max(1, Runtime.getRuntime().availableProcessors()), 1, cpuThreads, "",
                value -> cpuThreads = (int) Math.round(value));
        cpuThreadsSlider.setBounds(x, y, 220, 20);
        addButton(cpuThreadsSlider);
        y += row;

        addButton(new Button(center - 110, y, 105, 20,
                new TranslationTextComponent("screen.simplerecorder.done"), button -> saveAndClose()));
        addButton(new Button(center + 5, y, 105, 20,
                new TranslationTextComponent("screen.simplerecorder.cancel"), button -> onClose()));
        y += row;

        addButton(new Button(center - 110, y, 220, 20,
                new TranslationTextComponent("screen.simplerecorder.reload"), button -> {
                    readValues();
                    this.init(this.minecraft, this.width, this.height);
                }));

        updateFormatState();
    }

    private void updateFormatState() {
        if (qualitySlider != null) {
            qualitySlider.active = format == OutputFormat.AVIF;
        }
        if (speedSlider != null) {
            speedSlider.active = format == OutputFormat.AVIF;
        }
        if (cpuThreadsSlider != null) {
            cpuThreadsSlider.active = conversion == YuvMode.MULTITHREAD;
        }
    }

    @Override
    public void render(MatrixStack poseStack, int mouseX, int mouseY, float partialTick) {
        renderBackground(poseStack);
        AbstractGui.drawCenteredString(poseStack, this.font, this.title, this.width / 2, 16, 0xFFFFFF);
        AbstractGui.drawCenteredString(poseStack, this.font, new TranslationTextComponent("screen.simplerecorder.hint"),
                this.width / 2, 30, 0xA0A0A0);
        super.render(poseStack, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    /**
     * Called when Forge's config file watcher reloads the TOML file while this
     * screen is open.
     */
    public void reloadFromConfig() {
        readValues();
        this.init(this.minecraft, this.width, this.height);
        LOGGER.info("Reloaded SimpleRecorder config from file: format={}, fps={}, scale={}%",
                format, fps, scalePercent);
    }

    private void saveAndClose() {
        RecorderConfig.FPS.set(fps);
        RecorderConfig.SCALE.set(scalePercent / 100.0D);
        RecorderConfig.FORMAT.set(format);
        RecorderConfig.LOOP.set(loop);
        RecorderConfig.AVIF_QUALITY.set(quality);
        RecorderConfig.AVIF_SPEED.set(speed);
        RecorderConfig.AVIF_CONVERSION.set(conversion);
        RecorderConfig.AVIF_CPU_THREADS.set(cpuThreads);
        try {
            RecorderConfig.SPEC.save();
            LOGGER.info("Saved SimpleRecorder config: format={}, fps={}, scale={}%, quality={}, speed={}, loop={}, "
                            + "conversion={}, cpuThreads={}",
                    format, fps, scalePercent, quality, speed, loop, conversion, cpuThreads);
        } catch (RuntimeException exception) {
            LOGGER.error("Could not save SimpleRecorder config", exception);
        }
        AvifSupport.reset();
        this.minecraft.setScreen(parent);
    }

    private void readValues() {
        fps = RecorderConfig.FPS.get();
        scalePercent = RecorderConfig.SCALE.get() * 100.0D;
        format = RecorderConfig.FORMAT.get();
        quality = RecorderConfig.AVIF_QUALITY.get();
        speed = RecorderConfig.AVIF_SPEED.get();
        loop = RecorderConfig.LOOP.get();
        conversion = RecorderConfig.AVIF_CONVERSION.get();
        cpuThreads = RecorderConfig.AVIF_CPU_THREADS.get();
    }

    private ITextComponent formatText() {
        return new TranslationTextComponent("screen.simplerecorder.format", format.name());
    }

    private ITextComponent loopText() {
        return new TranslationTextComponent("screen.simplerecorder.loop_value", loop ? "ON" : "OFF");
    }

    private ITextComponent conversionText() {
        return new TranslationTextComponent("screen.simplerecorder.conversion_value", conversion.name());
    }

    private static final class OptionSlider extends AbstractSlider {
        private final ITextComponent label;
        private final double min;
        private final double max;
        private final double step;
        private final String suffix;
        private final Consumer<Double> changed;

        OptionSlider(ITextComponent label, double min, double max, double step, double initial,
                     String suffix, Consumer<Double> changed) {
            super(0, 0, 220, 20, label, (initial - min) / (max - min));
            this.label = label;
            this.min = min;
            this.max = max;
            this.step = step;
            this.suffix = suffix;
            this.changed = changed;
            updateMessage();
        }

        void setBounds(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        @Override
        protected void updateMessage() {
            setMessage(new StringTextComponent(label.getString() + ": " + display()));
        }

        @Override
        protected void applyValue() {
            changed.accept(actual());
        }

        private double actual() {
            double raw = min + this.value * (max - min);
            if (step > 0) {
                raw = Math.round(raw / step) * step;
            }
            return Math.max(min, Math.min(max, raw));
        }

        private String display() {
            if (step >= 1.0D) {
                return String.format(Locale.ROOT, "%.0f%s", actual(), suffix);
            }
            return String.format(Locale.ROOT, "%.1f%s", actual(), suffix);
        }
    }
}
