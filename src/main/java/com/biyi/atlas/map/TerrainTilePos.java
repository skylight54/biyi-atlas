package com.biyi.atlas.map;

/** Spatial index of the legacy 128-block base-map tile within one dimension and floor. */
public record TerrainTilePos(int x, int z) {
    public static final int BLOCKS = 128;

    public static TerrainTilePos atBlock(int blockX, int blockZ) {
        return new TerrainTilePos(Math.floorDiv(blockX, BLOCKS), Math.floorDiv(blockZ, BLOCKS));
    }
}
