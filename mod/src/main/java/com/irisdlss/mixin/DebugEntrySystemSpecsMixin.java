package com.irisdlss.mixin;

import com.irisdlss.DlssUpscaler;
import com.irisdlss.VulkanPresenter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.client.gui.components.debug.DebugEntrySystemSpecs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Adds the DLSS/Vulkan line under the "OpenGL ..." line on F3. */
@Mixin(DebugEntrySystemSpecs.class)
public class DebugEntrySystemSpecsMixin {

    @ModifyArg(method = "display", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/components/debug/DebugScreenDisplayer;addToGroup(Lnet/minecraft/resources/Identifier;Ljava/util/Collection;)V"),
        index = 1)
    private Collection<String> irisdlss$addDlssLine(Collection<String> lines) {
        List<String> extended = new ArrayList<>(lines);
        extended.add(DlssUpscaler.statusLine());
        extended.add(VulkanPresenter.statusLine());
        return extended;
    }
}
