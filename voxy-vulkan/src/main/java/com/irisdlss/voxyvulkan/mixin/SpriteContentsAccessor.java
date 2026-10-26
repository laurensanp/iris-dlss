package com.irisdlss.voxyvulkan.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The sprite's pixels on the CPU, for the average colour of a block face. */
@Mixin(SpriteContents.class)
public interface SpriteContentsAccessor {

    @Accessor("originalImage")
    NativeImage voxyvulkan$getOriginalImage();
}
