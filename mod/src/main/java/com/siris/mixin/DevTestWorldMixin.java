package com.siris.mixin;

import com.siris.DevTest;
import com.siris.Siris;
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
 * Dev option -Dsiris.testWorld=name: joins that singleplayer world from the title screen (first time: creates it,
 * creative, fixed seed). The rest of the test runs in {@link com.siris.DevTest}.
 */
@Mixin(TitleScreen.class)
public class DevTestWorldMixin {

    @Unique
    private static final String WORLD = DevTest.WORLD;
    @Unique
    private static boolean siris$opened;

    @Inject(method = "tick", at = @At("HEAD"))
    private void siris$openTestWorld(CallbackInfo ci) {
        if (WORLD == null || siris$opened) {
            return;
        }
        siris$opened = true;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getLevelSource().levelExists(WORLD)) {
            Siris.LOGGER.info("SIRIS test: joining world {}", WORLD);
            minecraft.createWorldOpenFlows().openWorld(WORLD, () -> minecraft.gui.setScreen(new TitleScreen()));
        } else {
            Siris.LOGGER.info("SIRIS test: creating world {}", WORLD);
            LevelSettings settings = new LevelSettings(WORLD, GameType.CREATIVE, LevelSettings.DifficultySettings.DEFAULT,
                true, WorldDataConfiguration.DEFAULT);
            minecraft.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(26_03L, true, false),
                WorldPresets::createNormalWorldDimensions, (TitleScreen) (Object) this);
        }
    }
}
