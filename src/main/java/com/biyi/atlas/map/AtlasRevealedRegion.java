package com.biyi.atlas.map;

import net.minecraft.resources.ResourceLocation;

/**
 * 作者在地图配置里声明的"已知区域"：这块矩形即使玩家没探索过也直接画出来，
 * 适合 RPG 地图包把大陆轮廓/城镇范围预先铺在地图上。
 */
public record AtlasRevealedRegion(ResourceLocation dimension, double x1, double z1, double x2, double z2, int rgb) {

    public boolean contains(ResourceLocation other, double x, double z) {
        if (!dimension.equals(other)) {
            return false;
        }
        double minX = Math.min(x1, x2);
        double maxX = Math.max(x1, x2);
        double minZ = Math.min(z1, z2);
        double maxZ = Math.max(z1, z2);
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }
}
