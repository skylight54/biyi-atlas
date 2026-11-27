package com.biyi.atlas.map;

/**
 * 羊皮纸配色表：把原版 {@code MapColor} 的 id 映射成纸面颜色。
 * 整合包作者可通过资源包中的 atlas/style.json 覆盖。
 */
public final class AtlasPalette {

    /** 原版 MapColor id → 羊皮纸风格颜色（RGB）。缺省值刻意压低饱和度。 */
    private static final int[] DEFAULT = {
            0x000000, // 0 NONE
            0x6E7F55, // 1 GRASS
            0xD9C79A, // 2 SAND
            0xC9BD9E, // 3 WOOL
            0xB4553A, // 4 FIRE
            0xA9C3C6, // 5 ICE
            0x9AA0A1, // 6 METAL
            0x5F7A4C, // 7 PLANT
            0xE8E4D5, // 8 SNOW
            0xB29B8C, // 9 CLAY
            0x8C6F52, // 10 DIRT
            0x8B8B84, // 11 STONE
            0x6E8CA0, // 12 WATER
            0x7A5F42, // 13 WOOD
            0xDCD6C6, // 14 QUARTZ
            0xC8792F, // 15 COLOR_ORANGE
            0xA85B8E, // 16 COLOR_MAGENTA
            0x8FA9C6, // 17 COLOR_LIGHT_BLUE
            0xC9B049, // 18 COLOR_YELLOW
            0x8AA85B, // 19 COLOR_LIGHT_GREEN
            0xC48A94, // 20 COLOR_PINK
            0x7D7D7A, // 21 COLOR_GRAY
            0xB0AFA6, // 22 COLOR_LIGHT_GRAY
            0x5F8F94, // 23 COLOR_CYAN
            0x7A5F8E, // 24 COLOR_PURPLE
            0x5A6E9E, // 25 COLOR_BLUE
            0x7C5B3F, // 26 COLOR_BROWN
            0x5C7A3F, // 27 COLOR_GREEN
            0xA04A44, // 28 COLOR_RED
            0x3A3A38, // 29 COLOR_BLACK
            0xC9A227, // 30 GOLD
            0x6FB0A8, // 31 DIAMOND
            0x3F5C9E, // 32 LAPIS
            0x4FA05A, // 33 EMERALD
            0x6B4E2E, // 34 PODZOL
            0x6E3B3B, // 35 NETHER
            0xD6CFC2, // 36 TERRACOTTA_WHITE
            0xA9683F, // 37 TERRACOTTA_ORANGE
            0xA66B8A, // 38 TERRACOTTA_MAGENTA
            0x7E8FA6, // 39 TERRACOTTA_LIGHT_BLUE
            0xB49A55, // 40 TERRACOTTA_YELLOW
            0x7F9A55, // 41 TERRACOTTA_LIGHT_GREEN
            0xB0767C, // 42 TERRACOTTA_PINK
            0x6F6C69, // 43 TERRACOTTA_GRAY
            0x9E968B, // 44 TERRACOTTA_LIGHT_GRAY
            0x5C7C7E, // 45 TERRACOTTA_CYAN
            0x6E5478, // 46 TERRACOTTA_PURPLE
            0x4F5F86, // 47 TERRACOTTA_BLUE
            0x6B4C36, // 48 TERRACOTTA_BROWN
            0x52683A, // 49 TERRACOTTA_GREEN
            0x8C4A44, // 50 TERRACOTTA_RED
            0x3B3735, // 51 TERRACOTTA_BLACK
            0x7A3546, // 52 CRIMSON_NYLIUM
            0x6E3A50, // 53 CRIMSON_STEM
            0x5C2F3E, // 54 CRIMSON_HYPHAE
            0x2F6B62, // 55 WARPED_NYLIUM
            0x3A6E6B, // 56 WARPED_STEM
            0x2E5A56, // 57 WARPED_HYPHAE
            0x2A7E77, // 58 WARPED_WART_BLOCK
            0x575752, // 59 DEEPSLATE
            0xA6805F, // 60 RAW_IRON
            0x6E7F5A, // 61 GLOW_LICHEN
    };

    private static int[] colors = DEFAULT.clone();

    private AtlasPalette() {
    }

    public static void setColors(int[] palette) {
        colors = palette == null || palette.length == 0 ? DEFAULT.clone() : palette;
    }

    public static int[] colors() {
        return colors;
    }

    public static int[] defaults() {
        return DEFAULT.clone();
    }

    /** 取某个地图色的纸面颜色（ARGB，不透明）。 */
    public static int color(int colorId) {
        if (colorId >= 0 && colorId < colors.length) {
            int rgb = colors[colorId];
            if ((rgb & 0xFFFFFF) != 0) {
                return 0xFF000000 | rgb;
            }
        }
        return 0xFF9E9583;
    }

    /** 按地表高度做明暗处理：越高越亮，海平面附近略暗。 */
    public static int shaded(int argb, int height) {
        double factor = 1.0 + (height - 64) * 0.0045;
        factor = Math.max(0.84, Math.min(1.14, factor));
        int r = (int) Math.min(255, ((argb >> 16) & 0xFF) * factor);
        int g = (int) Math.min(255, ((argb >> 8) & 0xFF) * factor);
        int b = (int) Math.min(255, (argb & 0xFF) * factor);
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }
}
