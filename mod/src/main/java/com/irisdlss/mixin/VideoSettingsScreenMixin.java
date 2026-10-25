package com.irisdlss.mixin;

import com.irisdlss.DlssSettingsScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** "DLSS..." button in Options > Video Settings, so the settings work without Mod Menu too. */
@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsScreenMixin extends Screen {

    protected VideoSettingsScreenMixin(Component title) {
        super(title);
    }

    // addTitle runs once per init(), after the widget list was cleared.
    @Inject(method = "addTitle", at = @At("TAIL"))
    private void irisdlss$addButton(CallbackInfo ci) {
        this.addRenderableWidget(Button.builder(Component.literal("DLSS..."),
                button -> this.minecraft.gui.setScreen(new DlssSettingsScreen(this)))
            .bounds(this.width - 85, 5, 80, 20).build());
    }
}
