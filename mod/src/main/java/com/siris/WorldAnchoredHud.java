package com.siris;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.world.phys.Vec3;

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
    /** World positions the separate state's elements belong to (waypoint blocks), this frame. */
    private static final List<Vec3> ANCHORS = new ArrayList<>();

    private WorldAnchoredHud() {
    }

    /** Whether world-anchored HUD should go to the separate state this frame. */
    public static boolean active() {
        return DlssUpscaler.isActive() && DlssUpscaler.hasResources() && DlssConfig.frameGeneration
            && VulkanPresenter.isRunning();
    }

    /** World positions the elements extracted this frame stand on (for their motion vectors). */
    public static void addAnchor(Vec3 position) {
        ANCHORS.add(position);
    }

    /** An extractor drawing into the separate state; the caller extracts its elements with it. */
    public static GuiGraphicsExtractor extractor() {
        filled = true;
        return new GuiGraphicsExtractor(Minecraft.getInstance(), STATE, -1, -1);
    }

    /** GameRenderer.render, right before the GUI: draw the separate state, then refresh the hudless image. */
    public static void renderBeforeGui() {
        if (!filled) {
            ANCHORS.clear();
            return;
        }
        filled = false;
        // Before rendering: the renderer resets the state. Rectangles in output pixels.
        double scale = Minecraft.getInstance().getWindow().getGuiScale();
        List<float[]> rects = new ArrayList<>();
        Consumer<ScreenRectangle> add = bounds -> {
            if (bounds != null) {
                rects.add(new float[] {(float) (bounds.left() * scale), (float) (bounds.top() * scale),
                    (float) (bounds.width() * scale), (float) (bounds.height() * scale)});
            }
        };
        STATE.forEachElement(element -> add.accept(element.bounds()), GuiRenderState.TraverseRange.ALL);
        STATE.forEachText(text -> add.accept(text.bounds()));
        STATE.forEachItem(item -> add.accept(item.bounds()));
        if (renderer == null) {
            renderer = new GuiRenderer(STATE, Minecraft.getInstance().gameRenderer.featureRenderDispatcher(), List.of());
        }
        renderer.render();
        renderer.endFrame();
        DlssUpscaler.refreshHudless();
        DlssUpscaler.patchIconMotion(rects, ANCHORS);
        ANCHORS.clear();
    }
}
