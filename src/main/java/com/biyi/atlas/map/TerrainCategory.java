package com.biyi.atlas.map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

/**
 * 手绘地形类别。存储上把类别编码成 {@code 64 + ordinal}，
 * 这样旧的"原版地图色 id"数据（&lt;64）仍能识别并退化渲染，不会画成乱七八糟的东西。
 *
 * <p>1.20.1 已移除 {@code Biome.BiomeCategory}，所以这里用 BiomeTags + 温度 + 高度 + 方块地图色分类。</p>
 */
public final class TerrainCategory {

    public static final int OFFSET = 64;

    public static final int WATER = 1;
    public static final int SAND = 2;
    public static final int PLAINS = 3;
    public static final int FOREST = 4;
    public static final int TAIGA = 5;
    public static final int DESERT = 6;
    public static final int SAVANNA = 7;
    public static final int JUNGLE = 8;
    public static final int SWAMP = 9;
    public static final int MOUNTAIN = 10;
    public static final int SNOW = 11;
    public static final int MUSHROOM = 12;
    public static final int NETHER = 13;
    public static final int END = 14;
    public static final int OTHER = 15;

    /** 图集里的行顺序必须与这些常量一致（0 行 = unknown）。 */
    public static final int ROWS = 16;

    private TerrainCategory() {
    }

    public static int encode(int category) {
        return OFFSET + category;
    }

    /** 解码存储值：新数据 = 64+类别；旧数据（原版地图色 id）退化到最接近的类别。 */
    public static int decode(int stored) {
        if (stored >= OFFSET) {
            int category = stored - OFFSET;
            return category >= 0 && category < ROWS ? category : OTHER;
        }
        return legacyFromMapColor(stored);
    }

    private static int legacyFromMapColor(int mapColorId) {
        return switch (mapColorId) {
            case 12 -> WATER;
            case 2 -> SAND;
            case 8 -> SNOW;
            case 11, 59, 60 -> MOUNTAIN;
            case 1 -> PLAINS;
            case 7, 27 -> FOREST;
            case 4 -> DESERT;
            case 35 -> NETHER;
            default -> OTHER;
        };
    }

    /**
     * 按生物群系分类（渲染时用：我们存档里存的是 biome 调色板下标）。
     * 高度 >110 归到山地、极寒归到雪地，由调用方补充。
     */
    public static int classifyBiome(Holder<Biome> biome, Biome value) {
        if (biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_RIVER)) {
            return WATER;
        }
        if (biome.is(BiomeTags.IS_BEACH)) {
            return SAND;
        }
        if (biome.is(BiomeTags.IS_BADLANDS) || value.getBaseTemperature() > 1.5F) {
            return DESERT;
        }
        if (biome.is(BiomeTags.IS_JUNGLE)) {
            return JUNGLE;
        }
        if (biome.is(BiomeTags.IS_TAIGA)) {
            return TAIGA;
        }
        if (biome.is(BiomeTags.IS_FOREST)) {
            return FOREST;
        }
        if (biome.is(BiomeTags.IS_SAVANNA)) {
            return SAVANNA;
        }
        if (biome.is(BiomeTags.IS_MOUNTAIN)) {
            return MOUNTAIN;
        }
        if (value.getBaseTemperature() <= 0.15F) {
            return SNOW;
        }
        return PLAINS;
    }

    /** 采样时的分类：维度 → 水 → 雪 → 山 → 沙/沙漠 → 森林系 → 草原 → 兜底。 */
    public static int classify(Level level, BlockPos pos, BlockState state, Holder<Biome> biome, int height) {
        if (level.dimension() == Level.NETHER) {
            return NETHER;
        }
        if (level.dimension() == Level.END) {
            return END;
        }
        boolean water = !state.getFluidState().isEmpty();
        if (water || biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_RIVER)) {
            return WATER;
        }
        MapColor mapColor = state.getMapColor(level, pos);
        if (mapColor == MapColor.SNOW || biome.value().coldEnoughToSnow(pos)) {
            return SNOW;
        }
        if (height >= 110 || biome.is(BiomeTags.IS_MOUNTAIN)) {
            return MOUNTAIN;
        }
        if (biome.is(BiomeTags.IS_BEACH) || mapColor == MapColor.SAND) {
            return SAND;
        }
        if (biome.is(BiomeTags.IS_BADLANDS) || biome.value().getBaseTemperature() > 1.5F) {
            return DESERT;
        }
        if (biome.is(BiomeTags.IS_JUNGLE)) {
            return JUNGLE;
        }
        if (biome.is(BiomeTags.IS_TAIGA)) {
            return TAIGA;
        }
        if (biome.is(BiomeTags.IS_FOREST) || mapColor == MapColor.PLANT) {
            return FOREST;
        }
        if (biome.is(BiomeTags.IS_SAVANNA)) {
            return SAVANNA;
        }
        if (mapColor == MapColor.GRASS) {
            return PLAINS;
        }
        return OTHER;
    }
}
