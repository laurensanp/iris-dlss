package com.irisdlss.mixin;

import com.irisdlss.DevTest;
import com.irisdlss.IrisDlss;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Dev option -Diris-dlss.testWorld=name (same as the Iris fork's test harness, for runs without it,
 * e.g. with Vitrail): joins that singleplayer world from the title screen (first time: creates it,
 * creative, fixed seed). The rest of the test runs in {@link com.irisdlss.DevTest}.
 */
@Mixin(TitleScreen.class)
public class DevTestWorldMixin {

    @Unique
    private static final String WORLD = DevTest.WORLD;
    @Unique
    private static boolean irisdlss$opened;

    @Inject(method = "tick", at = @At("HEAD"))
    private void irisdlss$openTestWorld(CallbackInfo ci) {
        if (WORLD == null || irisdlss$opened) {
            return;
        }
        irisdlss$opened = true;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getLevelSource().levelExists(WORLD)) {
            IrisDlss.LOGGER.info("Iris DLSS test: joining world {}", WORLD);
            minecraft.createWorldOpenFlows().openWorld(WORLD, () -> minecraft.gui.setScreen(new TitleScreen()));
        } else {
            IrisDlss.LOGGER.info("Iris DLSS test: creating world {}", WORLD);
            LevelSettings settings = new LevelSettings(WORLD, GameType.CREATIVE, LevelSettings.DifficultySettings.DEFAULT,
                true, WorldDataConfiguration.DEFAULT);
            minecraft.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(26_03L, true, false),
                WorldPresets::createNormalWorldDimensions, (TitleScreen) (Object) this);
        }
    }
}
