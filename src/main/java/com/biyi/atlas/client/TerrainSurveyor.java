package com.biyi.atlas.client;

import com.biyi.atlas.map.AtlasMapData;
import com.biyi.atlas.map.TerrainSurvey;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

/** 有预算的地表调查，不申请加载任何区块，不接触旧探索数据。 */
public final class TerrainSurveyor {
    private static int cooldown;
    private static boolean cacheChecked;
    private static final java.util.Map<Long, Long> retryAfter = new java.util.HashMap<>();
    private TerrainSurveyor() {}
    public static void reset() { cooldown = 0; cacheChecked = false; retryAfter.clear(); }
    public static String refreshNearby(){
        var client=Minecraft.getInstance();
        if(client.level==null||client.player==null)return "尚未进入世界";
        if(!client.level.dimension().equals(Level.OVERWORLD))return "当前只支持主世界地表调查";
        var data=AtlasStorage.get(client.level.dimension().location());
        int cx=client.player.chunkPosition().x,cz=client.player.chunkPosition().z;
        for(int z=-4;z<=4;z++)for(int x=-4;x<=4;x++)data.terrain().invalidateStructureChunk(cx+x,cz+z);
        cooldown=0;retryAfter.clear();AtlasStorage.markDirty();
        return "已安排重新调查附近已加载地形和建筑，地图将逐步更新";
    }
    public static void tick() {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null || client.isPaused()) return;
        if (!client.level.dimension().equals(Level.OVERWORLD)) return;
        if (--cooldown > 0) return;
        cooldown = 4;
        var data = AtlasStorage.get(client.level.dimension().location());
        if(!AtlasAuthoring.allowed())return; // 玩家使用服务端发布的区域底图，不持续扫描世界。
        if (!cacheChecked) {
            int removed = data.terrain().removeInvalidHeights(client.level.getMinBuildHeight(), client.level.getMaxBuildHeight());
            if (removed > 0) AtlasStorage.markDirty();
            cacheChecked = true;
        }
        int px = client.player.chunkPosition().x, pz = client.player.chunkPosition().z;
        int budget = 1; // 建筑轮廓为每方块采样，本轮降低单次区块预算。
        int range = Math.min(12, Math.max(4, client.options.renderDistance().get()));
        for (int radius = 0; radius <= range; radius++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius || dx * dx + dz * dz > range * range) continue;
                    int cx = px + dx, cz = pz + dz;
                    if ((data.terrain().hasChunk(cx, cz) && data.terrain().hasStructureChunk(cx, cz))
                            || !client.level.hasChunk(cx, cz)) continue;
                    long key = AtlasMapData.key(cx, cz);
                    long now = client.level.getGameTime();
                    if (retryAfter.getOrDefault(key, 0L) > now) continue;
                    boolean complete = sample(client.level, data, cx, cz);
                    if (!complete) retryAfter.put(key, now + 100); else retryAfter.remove(key);
                    AtlasStorage.markDirty();
                    if (--budget == 0) return;
                }
            }
        }
    }
    private static boolean sample(Level level, AtlasMapData data, int cx, int cz) {
        return com.biyi.atlas.map.SurfaceSurvey.sample(level,data.terrain(),cx,cz);
    }
}