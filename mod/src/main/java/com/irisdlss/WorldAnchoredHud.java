package com.irisdlss;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.state.gui.GuiRenderState;

/**
 * HUD elements that sit on world positions (waypoint icons and labels). Frame generation treats the
 * whole GUI as HUD and only shows it as drawn on rendered frames, so such icons hold still in the
 * generated frames and look like they lag behind the world. While frame generation runs, they are
 * extracted into a GUI state of their own, drawn onto the upscaled world before the rest of the GUI,
 * and the "hudless" image is taken after them: frame generation then moves them with the world.
 */
public final class WorldAnchoredHud {

    private static final GuiRenderState STATE = new GuiRenderState();
    private static GuiRenderer renderer;
    private static boolean filled;

    private WorldAnchoredHud() {
    }

    /** Whether world-anchored HUD should go to the separate state this frame. */
    public static boolean active() {
        return DlssUpscaler.isActive() && DlssUpscaler.hasResources() && DlssConfig.frameGeneration
            && VulkanPresenter.isRunning();
    }

    /** An extractor drawing into the separate state; the caller extracts its elements with it. */
    public static GuiGraphicsExtractor extractor() {
        filled = true;
        return new GuiGraphicsExtractor(Minecraft.getInstance(), STATE, -1, -1);
    }

    /** GameRenderer.render, right before the GUI: draw the separate state, then refresh the hudless image. */
    public static void renderBeforeGui() {
        if (!filled) {
            return;
        }
        filled = false;
        if (renderer == null) {
            renderer = new GuiRenderer(STATE, Minecraft.getInstance().gameRenderer.featureRenderDispatcher(), List.of());
        }
        renderer.render();
        renderer.endFrame();
        DlssUpscaler.refreshHudless();
    }
}
