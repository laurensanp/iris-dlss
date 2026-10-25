package com.irisdlss;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * In-game settings (Mod Menu), the same options as the Sodium page, in sections. Every change applies
 * immediately (DLSS is recreated on the next frame) and is saved.
 */
public class DlssSettingsScreen extends Screen {

    private static final int WIDTH = 150;
    private static final int HEIGHT = 20;
    private static final int GAP = 10;
    private static final int ROW = 22;
    private static final int HEADER = 14;

    @Nullable
    private final Screen parent;
    /** Section titles and their y, drawn in extractRenderState. */
    private final List<Header> headers = new ArrayList<>();
    private int left;
    private int right;
    private int y;
    private boolean column;

    private record Header(String title, int y) {
    }

    public DlssSettingsScreen(@Nullable Screen parent) {
        super(Component.literal("DLSS"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.headers.clear();
        this.left = this.width / 2 - WIDTH - GAP / 2;
        this.right = this.width / 2 + GAP / 2;
        this.y = 44;
        this.column = false;
        boolean dlss = DlssConfig.enabled;

        section("Upscaling");
        add(toggle("DLSS", DlssConfig.enabled, "Render the world at a lower resolution and upscale it with NVIDIA "
            + "DLSS. The game runs on Vulkan while this is on.", value -> {
                DlssConfig.enabled = value;
                VulkanPresenter.retry();
            }), true);
        add(cycle("Mode", DlssConfig.Quality.values(), DlssConfig.quality, q -> q.label,
            SodiumOptions::qualityTooltip, value -> DlssConfig.quality = value), dlss);
        add(slider("Render resolution", 33, 100, () -> DlssConfig.renderScale, v -> DlssConfig.renderScale = v,
            v -> v + " %", "Only for mode \"Custom\": the world's render resolution per axis."),
            dlss && DlssConfig.quality == DlssConfig.Quality.CUSTOM);
        add(cycle("Model", DlssConfig.Preset.values(), DlssConfig.preset, p -> p.label,
            SodiumOptions::presetTooltip, value -> DlssConfig.preset = value), dlss);
        add(slider("Sharpening", 0, 100, () -> Math.round(DlssConfig.sharpness * 100),
            v -> DlssConfig.sharpness = v / 100.0F, v -> v == 0 ? "off" : v + " %",
            "Contrast-adaptive sharpening after DLSS."), dlss);

        section("Frame Generation");
        add(toggle("Frame Generation", DlssConfig.frameGeneration, "One generated frame between two rendered ones "
            + "(RTX 40/50). Needs the DLSS presenter. Lunar Client: turn off its \"Multi-Threaded Rendering\".",
            value -> {
                DlssConfig.frameGeneration = value;
                VulkanPresenter.retry();
            }), dlss && DlssConfig.vulkanPresent);
        add(toggle("DLSS presenter", DlssConfig.vulkanPresent, "Show frames through the DLSS presenter's own "
            + "swapchain (needed for Frame Generation, paces frames for lower latency).", value -> {
                DlssConfig.vulkanPresent = value;
                VulkanPresenter.retry();
            }), dlss);

        section("Display and advanced");
        add(cycle("Overlay", DlssConfig.Overlay.values(), DlssConfig.overlay, o -> o.label,
            o -> Component.literal("Performance overlay in the top left corner of the HUD."),
            value -> DlssConfig.overlay = value), true);
        add(toggle("Texture LOD bias", DlssConfig.textureLodBias, "Sharper distant textures at lower render "
            + "resolutions. Applies after a restart.", value -> DlssConfig.textureLodBias = value), true);
        add(toggle("Pack TAA off", DlssConfig.disablePackTaa, "Switch the shader pack's own TAA off at the next "
            + "start (it blurs on top of DLSS).", value -> DlssConfig.disablePackTaa = value), true);
        add(slider("Hand distance", 0, 30, () -> Math.round(DlssConfig.handDistance * 10),
            v -> DlssConfig.handDistance = v / 10.0F, v -> String.format("%.1f blocks", v / 10.0),
            "Pixels closer than this count as the first-person hand (no motion)."), true);
        add(cycle("Jitter", DlssConfig.JitterDirection.values(), DlssConfig.JitterDirection.current(), j -> j.label,
            j -> Component.literal("Only if the image stays soft or wobbles: keep the sharpest."),
            DlssConfig.JitterDirection::apply), true);

        if (this.column) {
            this.y += ROW;
        }
        this.addRenderableWidget(Button.builder(Component.literal("Done"), button -> this.onClose())
            .bounds(this.width / 2 - 100, this.y + 6, 200, HEIGHT).build());
    }

    private void section(String title) {
        if (this.column) {
            this.y += ROW;
            this.column = false;
        }
        this.y += 4;
        this.headers.add(new Header(title, this.y));
        this.y += HEADER;
    }

    private void add(AbstractWidget widget, boolean active) {
        widget.setPosition(this.column ? this.right : this.left, this.y);
        widget.active = active;
        this.addRenderableWidget(widget);
        if (this.column) {
            this.y += ROW;
        }
        this.column = !this.column;
    }

    private AbstractWidget toggle(String name, boolean value, String tooltip, java.util.function.Consumer<Boolean> set) {
        CycleButton<Boolean> button = CycleButton.onOffBuilder(value)
            .create(0, 0, WIDTH, HEIGHT, Component.literal(name), (b, v) -> {
                set.accept(v);
                DlssConfig.save();
                this.rebuildWidgets();
            });
        button.setTooltip(Tooltip.create(Component.literal(tooltip)));
        return button;
    }

    private <E> AbstractWidget cycle(String name, E[] values, E value, java.util.function.Function<E, String> label,
        java.util.function.Function<E, Component> tooltip, java.util.function.Consumer<E> set) {
        return CycleButton.<E>builder(v -> Component.literal(label.apply(v)), value)
            .withValues(List.of(values))
            .withTooltip(v -> Tooltip.create(tooltip.apply(v)))
            .create(0, 0, WIDTH, HEIGHT, Component.literal(name), (b, v) -> {
                set.accept(v);
                DlssConfig.save();
                this.rebuildWidgets();
            });
    }

    private AbstractWidget slider(String name, int min, int max, IntSupplier get, IntConsumer set,
        IntFunction<String> format, String tooltip) {
        IntSlider slider = new IntSlider(name, min, max, get, set, format);
        slider.setTooltip(Tooltip.create(Component.literal(tooltip)));
        return slider;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.centeredText(this.font, this.title, this.width / 2, 8, 0xFFFFFFFF);
        graphics.centeredText(this.font, DlssUpscaler.statusLine(), this.width / 2, 20, 0xFFA0A0A0);
        graphics.centeredText(this.font, VulkanPresenter.compactStatus(), this.width / 2, 30, 0xFFA0A0A0);
        for (Header header : this.headers) {
            graphics.text(this.font, header.title(), this.left, header.y() + 2, 0xFF76B900, true);
            graphics.fill(this.left + this.font.width(header.title()) + 4, header.y() + 6, this.right + WIDTH,
                header.y() + 7, 0x40FFFFFF);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }

    /** Slider over whole numbers min..max, saved on release. */
    private static final class IntSlider extends AbstractSliderButton {

        private final String name;
        private final int min;
        private final int max;
        private final IntConsumer set;
        private final IntFunction<String> format;

        IntSlider(String name, int min, int max, IntSupplier get, IntConsumer set, IntFunction<String> format) {
            super(0, 0, WIDTH, HEIGHT, Component.empty(), (get.getAsInt() - min) / (double) (max - min));
            this.name = name;
            this.min = min;
            this.max = max;
            this.set = set;
            this.format = format;
            this.updateMessage();
        }

        private int current() {
            return this.min + (int) Math.round(this.value * (this.max - this.min));
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Component.literal(this.name + ": " + this.format.apply(this.current())));
        }

        @Override
        protected void applyValue() {
            this.set.accept(this.current());
            DlssConfig.save();
        }
    }
}
