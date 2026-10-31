package com.siris.mixin;

import com.siris.EntityMotion;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Positions of the entities drawn this frame, for per-entity motion vectors. */
@Mixin(LevelExtractor.class)
public class LevelExtractorMixin {

    @WrapOperation(method = "extractVisibleEntities", require = 0, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/extract/LevelExtractor;extractEntity(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;"))
    private EntityRenderState siris$trackEntity(LevelExtractor instance, Entity entity, float partialTicks,
        Operation<EntityRenderState> original) {
        EntityRenderState state = original.call(instance, entity, partialTicks);
        EntityMotion.extracted(entity, state);
        return state;
    }
}
