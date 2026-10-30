package com.irisdlss;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/**
 * Dev test run, with -Diris-dlss.testWorld=name (the world is joined by DevTestWorldMixin): runs
 * -Diris-dlss.testCommands (';'-separated) after one second, -Diris-dlss.testRepeat every tick after that, holds the camera at -Diris-dlss.testPitch,
 * saves screenshots/dlss-test.png after -Diris-dlss.testTicks client ticks (default 200) and quits.
 */
public final class DevTest {

    public static final String WORLD = System.getProperty("iris-dlss.testWorld");
    private static final String COMMANDS = System.getProperty("iris-dlss.testCommands");
    private static final String REPEAT = System.getProperty("iris-dlss.testRepeat");
    private static final String PITCH = System.getProperty("iris-dlss.testPitch");
    private static final int CAPTURE_TICKS = Integer.getInteger("iris-dlss.testTicks", 200);
    /** -Diris-dlss.testShots=N: screenshots on N ticks in a row (dlss-test.png, dlss-test-1.png, ...). */
    private static final int SHOTS = Integer.getInteger("iris-dlss.testShots", 1);
    /** -Diris-dlss.captureTick=N: frame-generation capture (see VulkanPresenter) at client tick N. */
    private static final int CAPTURE_TICK = Integer.getInteger("iris-dlss.captureTick", -1);

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
                message -> IrisDlss.LOGGER.info("Iris DLSS test: {}", message.getString()));
        } else if (ticks == CAPTURE_TICKS + 40) {
            IrisDlss.LOGGER.info("Iris DLSS test: done, closing the game");
            minecraft.stop();
        }
    }
}
