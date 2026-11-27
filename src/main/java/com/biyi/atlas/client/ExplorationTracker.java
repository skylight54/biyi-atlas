package com.biyi.atlas.client;

import com.biyi.atlas.map.AtlasMapData;
import com.biyi.atlas.map.TerrainCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
/** 客户端探索采集：玩家周围区块逐步并入地图数据。 */
public final class ExplorationTracker {

    private static final int RADIUS_CHUNKS = 4;
    private static final int INTERVAL_TICKS = 10;

    private static int cooldown;

    private ExplorationTracker() {
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null || mc.player == null) {
            return;
        }
        if (--cooldown > 0) {
            return;
        }
        cooldown = INTERVAL_TICKS;

        ResourceLocation dimension = level.dimension().location();
        AtlasMapData data = AtlasStorage.get(dimension);
        int pcx = mc.player.chunkPosition().x;
        int pcz = mc.player.chunkPosition().z;

        boolean changed = false;
        for (int dx = -RADIUS_CHUNKS; dx <= RADIUS_CHUNKS; dx++) {
            for (int dz = -RADIUS_CHUNKS; dz <= RADIUS_CHUNKS; dz++) {
                if (dx * dx + dz * dz > RADIUS_CHUNKS * RADIUS_CHUNKS) {
                    continue;
                }
                int cx = pcx + dx;
                int cz = pcz + dz;
                if (data.has(cx, cz) || !level.hasChunk(cx, cz)) {
                    continue;
                }
                sample(level, data, cx, cz);
                changed = true;
            }
        }
        if (changed) {
            AtlasStorage.markDirty();
        }
    }

    private static void sample(Level level, AtlasMapData data, int chunkX, int chunkZ) {
        int x = (chunkX << 4) + 8;
        int z = (chunkZ << 4) + 8;
        BlockPos top = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE,
                new BlockPos(x, level.getMinBuildHeight(), z));
        BlockState state = level.getBlockState(top);
        // 存生物群系调色板下标（不是 MapColor）：这样瓦片映射可以像 Antique Atlas 那样按 biome 配
        String biomeId = level.getBiome(top).unwrapKey()
                .map(key -> key.location().toString())
                .orElse("");
        int index = biomeId.isEmpty() ? 0 : data.biomeIndex(biomeId);
        data.put(chunkX, chunkZ, index, top.getY());
    }
}
