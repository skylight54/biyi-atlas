package com.biyi.atlas.map;

import java.util.function.IntPredicate;

/** 验证高度图候选点，失效时向下调查实际方块；空列不生成地形。 */
public final class TerrainSurface {
    private TerrainSurface() {}
    public static int find(int candidateY, int minY, int maxY, IntPredicate surfaceAt) {
        int start = candidateY < minY || candidateY >= maxY ? maxY - 1 : candidateY;
        for (int y = start; y >= minY; y--) if (surfaceAt.test(y)) return y;
        return Integer.MIN_VALUE;
    }
}
