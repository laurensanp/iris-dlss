package com.siris;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/**
 * Dev test run, with -Dsiris.testWorld=name (the world is joined by DevTestWorldMixin): runs
 * -Dsiris.testCommands (';'-separated) after one second, -Dsiris.testRepeat every tick after that, holds the camera at -Dsiris.testPitch,
 * saves screenshots/dlss-test.png after -Dsiris.testTicks client ticks (default 200) and quits.
 */
public final class DevTest {

    public static final String WORLD = System.getProperty("siris.testWorld");
    private static final String COMMANDS = System.getProperty("siris.testCommands");
    private static final String REPEAT = System.getProperty("siris.testRepeat");
    private static final String PITCH = System.getProperty("siris.testPitch");
    private static final int CAPTURE_TICKS = Integer.getInteger("siris.testTicks", 200);
    /** -Dsiris.testShots=N: screenshots on N ticks in a row (dlss-test.png, dlss-test-1.png, ...). */
    private static final int SHOTS = Integer.getInteger("siris.testShots", 1);
    /** -Dsiris.captureTick=N: frame-generation capture (see VulkanPresenter) at client tick N. */
    private static final int CAPTURE_TICK = Integer.getInteger("siris.captureTick", -1);

    /** -Dsiris.testHideGui=true: F1 (no HUD, hand or overlay) for clean screenshots. */
    private static final boolean HIDE_GUI = Boolean.getBoolean("siris.testHideGui");

    private static int ticks;

    private DevTest() {
    }

    /** End of every client tick. */
    public static void tick(Minecraft minecraft) {
        if (WORLD == null || minecraft.level == null || minecraft.player == null) {
            return;
        }
        ticks++;
        if (ticks == 20 && HIDE_GUI && !minecraft.gui.hud.isHidden()) {
            minecraft.gui.hud.toggle();
        }
        if (ticks == 20 && COMMANDS != null) {
            for (String command : COMMANDS.split(";")) {
                minecraft.player.connection.sendCommand(command.trim());
            }
        }
        if (ticks > 20 && REPEAT != null) {
            for (String command : REPEAT.split(";")) {
                minecraft.player.connection.sendCommand(command.trim());
            }
        }
        if (ticks == CAPTURE_TICK) {
            VulkanPresenter.captureRequested = true;
        }
        if (PITCH != null) {
            minecraft.player.setXRot(Float.parseFloat(PITCH));
        }
        if (ticks >= CAPTURE_TICKS && ticks < CAPTURE_TICKS + SHOTS) {
            String name = ticks == CAPTURE_TICKS ? "dlss-test.png" : "dlss-test-" + (ticks - CAPTURE_TICKS) + ".png";
            Screenshot.grab(minecraft.gameDirectory, name, minecraft.gameRenderer.mainRenderTarget(), 1,
                message -> Siris.LOGGER.info("SIRIS test: {}", message.getString()));
        } else if (ticks == CAPTURE_TICKS + 40) {
            Siris.LOGGER.info("SIRIS test: done, closing the game");
            minecraft.stop();
        }
    }
}
