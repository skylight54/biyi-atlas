package com.biyi.atlas.client;

import com.biyi.atlas.BiyiAtlas;
import net.minecraft.resources.ResourceLocation;

/** 手绘地形瓦片图集的定位与选图（图集：横向 4 变体，纵向 16 个类别，单格 16px）。 */
public final class TerrainTiles {

    public static final ResourceLocation ATLAS =
            new ResourceLocation(BiyiAtlas.MODID, "textures/atlas/terrain.png");
    /** 地形交界墨线（4 个方向：上/右/下/左）。 */
    public static final ResourceLocation EDGES =
            new ResourceLocation(BiyiAtlas.MODID, "textures/atlas/terrain_edges.png");
    public static final int TILE_PX = 16;
    public static final int VARIANTS = 4;
    public static final int ATLAS_W = TILE_PX * VARIANTS;
    public static final int ATLAS_H = TILE_PX * 16;
    public static final int EDGES_W = TILE_PX * 4;
    public static final int EDGES_H = TILE_PX;

    private TerrainTiles() {
    }

    /** 用区块坐标做稳定的变体选择，保证同一块地方每次都长一样。 */
    public static int variantAt(int chunkX, int chunkZ) {
        return Math.floorMod(chunkX * 31 + chunkZ * 17, VARIANTS);
    }
}
