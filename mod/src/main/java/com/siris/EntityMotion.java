package com.siris;

import com.mojang.blaze3d.buffers.Std140Builder;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Per-entity motion for the motion vectors: every visible entity's rendered position (and body yaw
 * for living ones) is kept from frame to frame. The motion shader moves world pixels that lie inside
 * a moving entity's box with that entity (translation plus turn about its vertical axis) instead of
 * treating them as static. Limbs, heads and other animation stay camera-plus-entity only.
 */
public final class EntityMotion {

    /** Entities handed to the shader per frame (the nearest moving ones). */
    public static final int MAX = 32;
    /** vec4s per entity in the shader's array. */
    public static final int VEC4S = 3;

    private static final class Pose {
        double x;
        double y;
        double z;
        float yaw;
        double prevX;
        double prevY;
        double prevZ;
        float prevYaw;
        float width;
        float height;
        double distanceSq;
        int frame = -1;
        int prevFrame = -1;
    }

    private static final Int2ObjectOpenHashMap<Pose> POSES = new Int2ObjectOpenHashMap<>();
    private static final Pose[] candidates = new Pose[256];
    private static final Comparator<Pose> NEAREST = Comparator.comparingDouble(p -> p.distanceSq);
    private static int frame;

    private EntityMotion() {
    }

    /** Frame start: positions extracted from now on belong to the next frame. */
    public static void frameStart() {
        frame++;
        if ((frame & 63) == 0) {
            for (Iterator<Int2ObjectMap.Entry<Pose>> it = POSES.int2ObjectEntrySet().fastIterator(); it.hasNext(); ) {
                if (frame - it.next().getValue().frame > 4) {
                    it.remove();
                }
            }
        }
    }

    /** LevelExtractor, each visible entity: the state holds the position it is drawn at this frame. */
    public static void extracted(Entity entity, EntityRenderState state) {
        Pose pose = POSES.get(entity.getId());
        if (pose == null) {
            pose = new Pose();
            POSES.put(entity.getId(), pose);
        }
        if (pose.frame != frame) {
            // First extraction this frame (a shadow pass may extract the same entity again).
            pose.prevX = pose.x;
            pose.prevY = pose.y;
            pose.prevZ = pose.z;
            pose.prevYaw = pose.yaw;
            pose.prevFrame = pose.frame;
            pose.frame = frame;
        }
        pose.x = state.x;
        pose.y = state.y;
        pose.z = state.z;
        pose.yaw = state instanceof LivingEntityRenderState living ? living.bodyRot : Float.NaN;
        pose.width = state.boundingBoxWidth;
        pose.height = state.boundingBoxHeight;
        pose.distanceSq = state.distanceToCameraSq;
    }

    /**
     * Writes the entity count and the entities' vec4 array (MAX * VEC4S, padded with zeros), camera-relative
     * to cameraPos: center + yaw change (radians, current minus previous), box half size, world movement.
     */
    public static Std140Builder write(Std140Builder data, Vec3 cameraPos) {
        int count = 0;
        for (Pose pose : POSES.values()) {
            if (pose.frame != frame || pose.prevFrame != frame - 1 || count == candidates.length) {
                continue;
            }
            double dx = pose.x - pose.prevX;
            double dy = pose.y - pose.prevY;
            double dz = pose.z - pose.prevZ;
            double moved = dx * dx + dy * dy + dz * dz;
            float turned = yawDelta(pose);
            // Standing still, or teleported (no connection to last frame's picture).
            if (moved < 1e-8 && Math.abs(turned) < 1e-4F || moved > 8.0 * 8.0) {
                continue;
            }
            candidates[count++] = pose;
        }
        int total = count;
        if (count > MAX) {
            Arrays.sort(candidates, 0, count, NEAREST);
            count = MAX;
        }
        data.putInt(count);
        for (int i = 0; i < MAX; i++) {
            if (i >= count) {
                data.putVec4(0.0F, 0.0F, 0.0F, 0.0F).putVec4(0.0F, 0.0F, 0.0F, 0.0F).putVec4(0.0F, 0.0F, 0.0F, 0.0F);
                continue;
            }
            Pose pose = candidates[i];
            // Box a bit wider than the hitbox (arms, tails, held items); its bottom just above the
            // feet so the ground the entity stands on keeps the camera's motion.
            float half = pose.width * 0.5F + 0.3F;
            double bottom = pose.y + 0.03;
            double top = pose.y + pose.height + 0.15;
            data.putVec4((float) (pose.x - cameraPos.x), (float) ((bottom + top) * 0.5 - cameraPos.y),
                (float) (pose.z - cameraPos.z), yawDelta(pose));
            data.putVec4(half, (float) ((top - bottom) * 0.5), half, 0.0F);
            data.putVec4((float) (pose.x - pose.prevX), (float) (pose.y - pose.prevY), (float) (pose.z - pose.prevZ),
                0.0F);
        }
        Arrays.fill(candidates, 0, total, null);
        return data;
    }

    /** Body turn since last frame in radians; renderers rotate living models by 180 - bodyRot about +Y. */
    private static float yawDelta(Pose pose) {
        if (Float.isNaN(pose.yaw) || Float.isNaN(pose.prevYaw)) {
            return 0.0F;
        }
        return Mth.wrapDegrees(pose.yaw - pose.prevYaw) * Mth.DEG_TO_RAD;
    }
}
