package com.biyi.atlas.client;

import net.minecraft.resources.ResourceLocation;

/** 玩家正在"追踪"的目标：地图上点一下地点/路标就设为它，关掉地图后 HUD 会指路。 */
public final class AtlasTracker {

    public record Target(String name, ResourceLocation dimension, double x, double z, int rgb, boolean teleport) {
    }

    private static Target target;

    private AtlasTracker() {
    }

    public static Target target() {
        return target;
    }

    public static void set(Target newTarget) {
        target = newTarget;
    }

    public static void clear() {
        target = null;
    }
}
