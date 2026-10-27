package com.irisdlss;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/**
 * Dev test run, with -Diris-dlss.testWorld=name (the world is joined by DevTestWorldMixin): runs
 * -Diris-dlss.testCommands (';'-separated) after one second, holds the camera at -Diris-dlss.testPitch,
 * saves screenshots/dlss-test.png after -Diris-dlss.testTicks client ticks (default 200) and quits.
 */
public final class DevTest {

    public static final String WORLD = System.getProperty("iris-dlss.testWorld");
    private static final String COMMANDS = System.getProperty("iris-dlss.testCommands");
    private static final String PITCH = System.getProperty("iris-dlss.testPitch");
    private static final int CAPTURE_TICKS = Integer.getInteger("iris-dlss.testTicks", 200);

    private static int ticks;

    private DevTest() {
    }

    /** End of every client tick. */
    public static void tick(Minecraft minecraft) {
        if (WORLD == null || minecraft.level == null || minecraft.player == null) {
            return;
        }
        ticks++;
        if (ticks == 20 && COMMANDS != null) {
            for (String command : COMMANDS.split(";")) {
                minecraft.player.connection.sendCommand(command.trim());
            }
        }
        if (PITCH != null) {
            minecraft.player.setXRot(Float.parseFloat(PITCH));
        }
        if (ticks == CAPTURE_TICKS) {
            Screenshot.grab(minecraft.gameDirectory, "dlss-test.png", minecraft.gameRenderer.mainRenderTarget(), 1,
                message -> IrisDlss.LOGGER.info("Iris DLSS test: {}", message.getString()));
        } else if (ticks == CAPTURE_TICKS + 40) {
            IrisDlss.LOGGER.info("Iris DLSS test: done, closing the game");
            minecraft.stop();
        }
    }
}
