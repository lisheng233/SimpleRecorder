package com.lisheng.simplerecorder.client;

import com.lisheng.simplerecorder.avif.AvifSupport;
import com.lisheng.simplerecorder.config.OutputFormat;
import com.lisheng.simplerecorder.config.RecorderConfig;
import com.lisheng.simplerecorder.config.YuvMode;
import org.apache.logging.log4j.LogManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.text.TextComponentTranslation;
import org.apache.logging.log4j.Logger;

/**
 * In-game editor for the client config. The cfg file is also watched, so
 * editing it externally hot-reloads the same values.
 */
public final class RecorderConfigScreen extends GuiScreen {
    private static final Logger LOGGER = LogManager.getLogger();

    private static final int BUTTON_FORMAT = 1;
    private static final int BUTTON_LOOP = 2;
    private static final int BUTTON_CONVERSION = 3;
    private static final int BUTTON_DONE = 4;
    private static final int BUTTON_CANCEL = 5;
    private static final int BUTTON_RELOAD = 6;
    private static final int SLIDER_FPS = 11;
    private static final int SLIDER_SCALE = 12;
    private static final int SLIDER_QUALITY = 13;
    private static final int SLIDER_SPEED = 14;
    private static final int SLIDER_CPU_THREADS = 15;

    private final GuiScreen parent;

    private int fps;
    private double scalePercent;
    private OutputFormat format;
    private int quality;
    private int speed;
    private boolean loop;
    private YuvMode conversion;
    private int cpuThreads;

    private final List<OptionSlider> sliders = new ArrayList<OptionSlider>();
    private OptionSlider dragging;
    private GuiButton formatButton;
    private GuiButton loopButton;
    private GuiButton conversionButton;
    private OptionSlider qualitySlider;
    private OptionSlider speedSlider;
    private OptionSlider cpuThreadsSlider;

    public RecorderConfigScreen(GuiScreen parent) {
        this.parent = parent;
        readValues();
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        this.sliders.clear();
        this.dragging = null;

        int center = this.width / 2;
        int x = center - 110;
        int y = 40;
        int row = 20;

        formatButton = new GuiButton(BUTTON_FORMAT, x, y, 220, 20, formatText());
        this.buttonList.add(formatButton);
        y += row;

        addSlider(new OptionSlider(SLIDER_FPS, x, y, 220, 20,
                I18n.format("screen.simplerecorder.fps"), 5.0D, 60.0D, 1.0D, fps, " FPS"));
        y += row;

        addSlider(new OptionSlider(SLIDER_SCALE, x, y, 220, 20,
                I18n.format("screen.simplerecorder.scale"), 10.0D, 100.0D, 1.0D, scalePercent, "%"));
        y += row;

        qualitySlider = new OptionSlider(SLIDER_QUALITY, x, y, 220, 20,
                I18n.format("screen.simplerecorder.quality"), 20.0D, 100.0D, 1.0D, quality, "");
        addSlider(qualitySlider);
        y += row;

        speedSlider = new OptionSlider(SLIDER_SPEED, x, y, 220, 20,
                I18n.format("screen.simplerecorder.speed"), 5.0D, 10.0D, 1.0D, speed, "");
        addSlider(speedSlider);
        y += row;

        loopButton = new GuiButton(BUTTON_LOOP, x, y, 220, 20, loopText());
        this.buttonList.add(loopButton);
        y += row;

        conversionButton = new GuiButton(BUTTON_CONVERSION, x, y, 220, 20, conversionText());
        this.buttonList.add(conversionButton);
        y += row;

        cpuThreadsSlider = new OptionSlider(SLIDER_CPU_THREADS, x, y, 220, 20,
                I18n.format("screen.simplerecorder.cpu_threads"), 1.0D,
                Math.max(1, Runtime.getRuntime().availableProcessors()), 1.0D, cpuThreads, "");
        addSlider(cpuThreadsSlider);
        y += row;

        this.buttonList.add(new GuiButton(BUTTON_DONE, center - 110, y, 105, 20,
                I18n.format("screen.simplerecorder.done")));
        this.buttonList.add(new GuiButton(BUTTON_CANCEL, center + 5, y, 105, 20,
                I18n.format("screen.simplerecorder.cancel")));
        y += row;

        this.buttonList.add(new GuiButton(BUTTON_RELOAD, center - 110, y, 220, 20,
                I18n.format("screen.simplerecorder.reload")));

        updateFormatState();
    }

    private void addSlider(OptionSlider slider) {
        this.buttonList.add(slider);
        this.sliders.add(slider);
    }

    private void updateFormatState() {
        if (qualitySlider != null) {
            qualitySlider.enabled = format == OutputFormat.AVIF;
        }
        if (speedSlider != null) {
            speedSlider.enabled = format == OutputFormat.AVIF;
        }
        if (cpuThreadsSlider != null) {
            cpuThreadsSlider.enabled = conversion == YuvMode.MULTITHREAD;
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        this.drawCenteredString(this.fontRenderer, I18n.format("screen.simplerecorder.title"),
                this.width / 2, 16, 0xFFFFFF);
        this.drawCenteredString(this.fontRenderer, I18n.format("screen.simplerecorder.hint"),
                this.width / 2, 30, 0xA0A0A0);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws java.io.IOException {
        if (mouseButton == 0) {
            for (OptionSlider slider : sliders) {
                if (slider.enabled && slider.contains(mouseX, mouseY)) {
                    dragging = slider;
                    slider.dragTo(mouseX);
                    applySliderValues();
                    return;
                }
            }
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        if (dragging != null) {
            dragging.dragTo(mouseX);
            applySliderValues();
            return;
        }
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        dragging = null;
        super.mouseReleased(mouseX, mouseY, state);
    }

    @Override
    protected void actionPerformed(GuiButton button) throws java.io.IOException {
        switch (button.id) {
            case BUTTON_FORMAT:
                format = format.next();
                button.displayString = formatText();
                updateFormatState();
                return;
            case BUTTON_LOOP:
                loop = !loop;
                button.displayString = loopText();
                return;
            case BUTTON_CONVERSION:
                conversion = conversion == YuvMode.OPENGL ? YuvMode.MULTITHREAD : YuvMode.OPENGL;
                button.displayString = conversionText();
                updateFormatState();
                return;
            case BUTTON_DONE:
                saveAndClose();
                return;
            case BUTTON_CANCEL:
                this.mc.displayGuiScreen(parent);
                return;
            case BUTTON_RELOAD:
                readValues();
                this.initGui();
                return;
            default:
                super.actionPerformed(button);
        }
    }

    private void applySliderValues() {
        fps = (int) Math.round(sliderValue(SLIDER_FPS, fps));
        scalePercent = sliderValue(SLIDER_SCALE, scalePercent);
        quality = (int) Math.round(sliderValue(SLIDER_QUALITY, quality));
        speed = (int) Math.round(sliderValue(SLIDER_SPEED, speed));
        cpuThreads = (int) Math.round(sliderValue(SLIDER_CPU_THREADS, cpuThreads));
    }

    private double sliderValue(int id, double fallback) {
        for (OptionSlider slider : sliders) {
            if (slider.id == id) {
                return slider.value();
            }
        }
        return fallback;
    }

    /**
     * Called when the cfg file changes on disk while this screen is open.
     */
    public void reloadFromConfig() {
        readValues();
        this.initGui();
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
            RecorderConfig.save();
            LOGGER.info("Saved SimpleRecorder config: format={}, fps={}, scale={}%, quality={}, speed={}, "
                            + "loop={}, conversion={}, cpuThreads={}",
                    format, fps, scalePercent, quality, speed, loop, conversion, cpuThreads);
        } catch (RuntimeException exception) {
            LOGGER.error("Could not save the SimpleRecorder config", exception);
        }
        AvifSupport.reset();
        this.mc.displayGuiScreen(parent);
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

    private String formatText() {
        return I18n.format("screen.simplerecorder.format", format.name());
    }

    private String loopText() {
        return I18n.format("screen.simplerecorder.loop_value", loop ? "ON" : "OFF");
    }

    private String conversionText() {
        return I18n.format("screen.simplerecorder.conversion_value", conversion.name());
    }

    /**
     * 1.12.2 has no reusable {@code AbstractSlider}, so this draws a flat slider
     * and lets the parent screen drive dragging from mouse events.
     */
    private static final class OptionSlider extends GuiButton {
        private final String label;
        private final double min;
        private final double max;
        private final double step;
        private final String suffix;
        private double current;

        OptionSlider(int id, int x, int y, int width, int height, String label,
                     double min, double max, double step, double initial, String suffix) {
            super(id, x, y, width, height, "");
            this.label = label;
            this.min = min;
            this.max = max;
            this.step = step;
            this.suffix = suffix;
            this.current = snap(initial);
        }

        double value() {
            return current;
        }

        boolean contains(int mouseX, int mouseY) {
            return this.visible && mouseX >= this.x && mouseY >= this.y
                    && mouseX < this.x + this.width && mouseY < this.y + this.height;
        }

        void dragTo(int mouseX) {
            double span = this.width - 8;
            if (span <= 0.0D) {
                return;
            }
            double ratio = (mouseX - (this.x + 4)) / span;
            ratio = Math.max(0.0D, Math.min(1.0D, ratio));
            this.current = snap(min + ratio * (max - min));
        }

        private double snap(double raw) {
            double value = raw;
            if (step > 0.0D) {
                value = Math.round(value / step) * step;
            }
            return Math.max(min, Math.min(max, value));
        }

        private String display() {
            if (step >= 1.0D) {
                return String.format(Locale.ROOT, "%.0f", current);
            }
            return String.format(Locale.ROOT, "%.1f", current);
        }

        @Override
        public void drawButton(Minecraft mc, int mouseX, int mouseY, float partialTicks) {
            if (!this.visible) {
                return;
            }
            this.hovered = contains(mouseX, mouseY);
            int background = !this.enabled ? 0xFF2A2A2A : (this.hovered ? 0xFF6A6A6A : 0xFF4A4A4A);
            Gui.drawRect(this.x, this.y, this.x + this.width, this.y + this.height, background);

            double ratio = max > min ? (current - min) / (max - min) : 0.0D;
            int fill = (int) Math.round(ratio * (this.width - 2));
            Gui.drawRect(this.x + 1, this.y + 1, this.x + 1 + fill, this.y + this.height - 1,
                    this.enabled ? 0xFF3F7FBF : 0xFF33506A);

            String text = label + ": " + display() + suffix;
            mc.fontRenderer.drawStringWithShadow(text, this.x + 4,
                    this.y + (this.height - 8) / 2,
                    this.enabled ? 0xFFFFFF : 0x808080);
        }
    }
}
