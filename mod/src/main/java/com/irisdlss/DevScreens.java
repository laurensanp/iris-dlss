package com.irisdlss;

import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Dev option -Diris-dlss.debugScreen=mod|sodium|sodium2: opens the settings (Mod Menu screen, or the
 * first / second DLSS page of Sodium's video settings) a few seconds after joining a world, so a test
 * screenshot shows them.
 */
public final class DevScreens {

    private static final String SCREEN = System.getProperty("iris-dlss.debugScreen");
    private static int framesInWorld;

    private DevScreens() {
    }

    /** Minecraft.runTick, every frame. */
    public static void frame() {
        Minecraft minecraft = Minecraft.getInstance();
        if (SCREEN == null || minecraft.level == null || minecraft.player == null || ++framesInWorld != 60) {
            return;
        }
        try {
            minecraft.gui.setScreen(switch (SCREEN) {
                case "sodium" -> sodiumPage(0);
                case "sodium2" -> sodiumPage(1);
                default -> new DlssSettingsScreen(null);
            });
        } catch (ReflectiveOperationException | RuntimeException e) {
            IrisDlss.LOGGER.warn("Iris DLSS: could not open the {} settings screen", SCREEN, e);
        }
    }

    /** Sodium's video settings on one of our pages (Sodium internals, dev only). */
    private static Screen sodiumPage(int index) throws ReflectiveOperationException {
        Class<?> manager = Class.forName("net.caffeinemc.mods.sodium.client.config.ConfigManager");
        Object config = manager.getField("CONFIG").get(null);
        List<?> mods = (List<?>) config.getClass().getMethod("getModOptions").invoke(config);
        for (Object mod : mods) {
            if (!"iris-dlss".equals(mod.getClass().getMethod("configId").invoke(mod))) {
                continue;
            }
            List<?> pages = (List<?>) mod.getClass().getMethod("pages").invoke(mod);
            Object page = pages.get(index);
            Class<?> screen = Class.forName("net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen");
            Class<?> optionPage = Class.forName("net.caffeinemc.mods.sodium.client.config.structure.OptionPage");
            Method create = screen.getMethod("createScreen", Screen.class, optionPage);
            return (Screen) create.invoke(null, null, page);
        }
        throw new IllegalStateException("no iris-dlss options registered with Sodium");
    }
}
