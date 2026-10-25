package com.irisdlss;

import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** In-game settings; every change applies immediately (DLSS is recreated on the next frame). */
public class DlssSettingsScreen extends Screen {

    private static final int WIDTH = 150;
    private static final int HEIGHT = 20;
    private static final int GAP = 10;

    @Nullable
    private final Screen parent;

    public DlssSettingsScreen(@Nullable Screen parent) {
        super(Component.literal("Iris DLSS Bridge"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int left = this.width / 2 - WIDTH - GAP / 2;
        int right = this.width / 2 + GAP / 2;
        int y = this.height / 4;

        this.addRenderableWidget(CycleButton.onOffBuilder(DlssConfig.enabled)
            .create(left, y, WIDTH, HEIGHT, Component.literal("DLSS"), (button, value) -> {
                DlssConfig.enabled = value;
                VulkanPresenter.retry();
                DlssConfig.save();
            }));
        this.addRenderableWidget(CycleButton.onOffBuilder(DlssConfig.frameGeneration)
            .create(right, y, WIDTH, HEIGHT, Component.literal("Frame Generation"), (button, value) -> {
                DlssConfig.frameGeneration = value;
                if (value) {
                    DlssConfig.vulkanPresent = true;
                    VulkanPresenter.retry();
                }
                DlssConfig.save();
                this.rebuildWidgets();
            }));
        y += 24;
        this.addRenderableWidget(CycleButton.<DlssConfig.Quality>builder(q -> Component.literal(q.label), DlssConfig.quality)
            .withValues(List.of(DlssConfig.Quality.values()))
            .create(left, y, WIDTH, HEIGHT, Component.literal("Quality"), (button, value) -> {
                DlssConfig.quality = value;
                DlssConfig.save();
            }));
        this.addRenderableWidget(CycleButton.onOffBuilder(DlssConfig.vulkanPresent)
            .create(right, y, WIDTH, HEIGHT, Component.literal("Vulkan output"), (button, value) -> {
                DlssConfig.vulkanPresent = value;
                if (!value) {
                    DlssConfig.frameGeneration = false;
                }
                VulkanPresenter.retry();
                DlssConfig.save();
                this.rebuildWidgets();
            }));
        y += 24;
        this.addRenderableWidget(CycleButton.<DlssConfig.Preset>builder(p -> Component.literal(p.label), DlssConfig.preset)
            .withValues(List.of(DlssConfig.Preset.values()))
            .create(left, y, WIDTH, HEIGHT, Component.literal("Model"), (button, value) -> {
                DlssConfig.preset = value;
                DlssConfig.save();
            }));
        this.addRenderableWidget(new SharpnessSlider(right, y, WIDTH, HEIGHT));
        y += 24;
        this.addRenderableWidget(CycleButton.onOffBuilder(DlssConfig.textureLodBias)
            .create(left, y, WIDTH, HEIGHT, Component.literal("Texture LOD bias"), (button, value) -> {
                DlssConfig.textureLodBias = value;
                DlssConfig.save();
            }));
        y += 36;
        this.addRenderableWidget(Button.builder(Component.literal("Done"), button -> this.onClose())
            .bounds(this.width / 2 - 100, y, 200, HEIGHT).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.centeredText(this.font, this.title, this.width / 2, this.height / 4 - 40, 0xFFFFFFFF);
        graphics.centeredText(this.font, DlssUpscaler.statusLine(), this.width / 2, this.height / 4 - 28, 0xFFA0A0A0);
        graphics.centeredText(this.font, VulkanPresenter.statusLine(), this.width / 2, this.height / 4 - 16,
            0xFFA0A0A0);
        graphics.centeredText(this.font, "Sharpest: Quality DLAA. The shader pack's own TAA is switched off at start.",
            this.width / 2, this.height / 4 + 4 * 24 + 4, 0xFF808080);
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }

    private static final class SharpnessSlider extends AbstractSliderButton {

        SharpnessSlider(int x, int y, int width, int height) {
            super(x, y, width, height, Component.empty(), DlssConfig.sharpness);
            this.updateMessage();
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Component.literal("Sharpening: " + (this.value < 0.005 ? "off"
                : Math.round(this.value * 100) + "%")));
        }

        @Override
        protected void applyValue() {
            DlssConfig.sharpness = (float) this.value;
            DlssConfig.save();
        }
    }
}
