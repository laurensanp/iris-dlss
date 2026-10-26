package com.irisdlss.voxyvulkan;

import java.util.List;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Voxy wraps the creation of the game's graphics device and throws for the Vulkan backend, so the game
 * falls back to OpenGL. After Voxy's mixin is merged into Minecraft (ours has a higher priority, so it is
 * applied later), its {@code instanceof VulkanBackend} test is replaced by false: the device is created
 * normally on Vulkan. Nothing else of Voxy's code changes.
 */
public class VoxyVulkanMixinPlugin implements IMixinConfigPlugin {

    private static final String VULKAN_BACKEND = "com/mojang/renderpearl/backend/vulkan/VulkanBackend";

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return FabricLoader.getInstance().isModLoaded("voxy");
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        if (!targetClassName.equals("net.minecraft.client.Minecraft")) {
            return;
        }
        int patched = 0;
        for (MethodNode method : targetClass.methods) {
            if (!method.name.contains("voxy$forceOpenglDevice")) {
                continue;
            }
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.INSTANCEOF
                    && type.desc.equals(VULKAN_BACKEND)) {
                    InsnList replacement = new InsnList();
                    replacement.add(new InsnNode(Opcodes.POP));
                    replacement.add(new InsnNode(Opcodes.ICONST_0));
                    method.instructions.insert(insn, replacement);
                    method.instructions.remove(insn);
                    patched++;
                }
            }
        }
        if (patched > 0) {
            VoxyVulkan.LOGGER.info("Voxy Vulkan: Voxy's Vulkan device check turned off");
        } else {
            VoxyVulkan.LOGGER.warn("Voxy Vulkan: Voxy's Vulkan device check not found; the game may start on OpenGL");
        }
    }
}
