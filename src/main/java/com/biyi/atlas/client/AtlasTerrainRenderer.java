package com.biyi.atlas.client;

import com.biyi.atlas.BiyiAtlas;
import com.biyi.atlas.map.TerrainSurvey;
import com.biyi.atlas.map.TerrainContours;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

/** 地形先画、道路后画；仅用 blit 与显式矩形裁剪，兼容原工作端的渲染约束。 */
public final class AtlasTerrainRenderer {
    private static final ResourceLocation PIXEL = new ResourceLocation(BiyiAtlas.MODID, "textures/atlas/pixel.png");
    private record Cell(int x, int z, int sample) {}
    private final GuiGraphics graphics;
    private final int width, height;
    private final double centerX, centerZ, scale;
    private int budget = 18000;

    private AtlasTerrainRenderer(GuiGraphics graphics, int width, int height,
                                 double centerX, double centerZ, double scale) {
        this.graphics = graphics;
        this.width = width; this.height = height;
        this.centerX = centerX; this.centerZ = centerZ; this.scale = scale;
    }

    public static void render(GuiGraphics graphics, TerrainSurvey data, int width, int height,
                              double centerX, double centerZ, double scale, BiPredicate<Double, Double> visible) {
        if (scale <= 0 || data.size() == 0) return;
        try {
            new AtlasTerrainRenderer(graphics, width, height, centerX, centerZ, scale).draw(data, visible);
        } finally {
            graphics.setColor(1, 1, 1, 1);
        }
    }

    private double sx(double x) { return width / 2.0 + (x - centerX) * scale; }
    private double sy(double z) { return height / 2.0 + (z - centerZ) * scale; }

    private void draw(TerrainSurvey data, BiPredicate<Double, Double> visible) {
        int step = 1;
        // 缩小时采用世界网格对齐的跳采样，不扫描整个探索存档。
        while (step < 4096 && (4 * step * scale < 3
                || (width / (4.0 * step * scale) + 2) * (height / (4.0 * step * scale) + 2) > 3500)) step *= 2;
        int minX = Math.floorDiv((int) Math.floor((centerX - width / (2 * scale)) / 4), step) * step;
        int minZ = Math.floorDiv((int) Math.floor((centerZ - height / (2 * scale)) / 4), step) * step;
        int maxX = (int) Math.ceil((centerX + width / (2 * scale)) / 4);
        int maxZ = (int) Math.ceil((centerZ + height / (2 * scale)) / 4);
        List<Cell> cells = new ArrayList<>();
        for (int z = minZ; z <= maxZ; z += step) for (int x = minX; x <= maxX; x += step) {
            if (!data.has(x, z)) continue;
            // 未探索或受封锁的角点不插值，避免把地形推断到隐藏区域。
            if (!visible.test(x * 4.0, z * 4.0) || !visible.test((x + step) * 4.0, z * 4.0)
                    || !visible.test(x * 4.0, (z + step) * 4.0)
                    || !visible.test((x + step) * 4.0, (z + step) * 4.0)) continue;
            if (step > 1 && (!data.has(x + step - 1, z) || !data.has(x, z + step - 1)
                    || !data.has(x + step - 1, z + step - 1))) continue;
            int sample = data.get(x, z);
            cells.add(new Cell(x, z, sample));
            int altitude = TerrainSurvey.height(sample);
            int slope = data.has(x - 1, z - 1) ? altitude - TerrainSurvey.height(data.get(x - 1, z - 1)) : 0;
            int shade = Math.max(-10, Math.min(8, -slope / 3));
            // 相邻格子共用同一取整边界，半透明底色不重复覆盖出深色网格。
            rect(Math.round(sx(x * 4.0)), Math.round(sy(z * 4.0)),
                    Math.round(sx((x + step) * 4.0)), Math.round(sy((z + step) * 4.0)),
                    color(TerrainSurvey.kind(sample), shade));
        }
        // 所有底色先完成，避免后续格子的底色盖掉跨格等高线。
        for (Cell cell : cells) {
            int x = cell.x(), z = cell.z(), kind = TerrainSurvey.kind(cell.sample());
            if (kind == TerrainSurvey.WATER || kind == TerrainSurvey.LAVA) continue;
            if (kind == TerrainSurvey.FOREST && 4 * step * scale >= 3) {
                double px = sx((x + step * 0.52) * 4), pz = sy((z + step * 0.52) * 4);
                // 小型俯视树冠；稳定世界坐标，不随移动随机跳动。
                if (Math.floorMod(x * 31 + z * 17, 3) == 0) {
                    rect(px - 2, pz - 1, px + 3, pz + 2, 0x40808F88);
                    rect(px - 1, pz - 2, px + 2, pz + 3, 0x40808F88);
                }
            }
            if (!data.has(x + step, z) || !data.has(x + step, z + step) || !data.has(x, z + step)) continue;
            int a = TerrainSurvey.height(cell.sample()), b = TerrainSurvey.height(data.get(x + step, z));
            int c = TerrainSurvey.height(data.get(x + step, z + step)), d = TerrainSurvey.height(data.get(x, z + step));
            if (TerrainSurvey.kind(data.get(x + step, z)) == TerrainSurvey.WATER
                    || TerrainSurvey.kind(data.get(x + step, z + step)) == TerrainSurvey.WATER
                    || TerrainSurvey.kind(data.get(x, z + step)) == TerrainSurvey.WATER) continue;
            int low = Math.min(Math.min(a, b), Math.min(c, d));
            int high = Math.max(Math.max(a, b), Math.max(c, d));
            // LOD 只改变横向采样距离，不把等高线高差放大成64/128格。
            int interval = 8;
            while ((high - low) / interval > 16) interval *= 2;
            for (int level = (Math.floorDiv(low, interval) + 1) * interval; level <= high; level += interval) {
                for (var line : TerrainContours.atLevel(a, b, c, d, level)) {
                    line(sx((x + line.x1() * step) * 4), sy((z + line.z1() * step) * 4),
                            sx((x + line.x2() * step) * 4), sy((z + line.z2() * step) * 4), 0x50868C89);
                }
            }
        }
    }

    private static int color(int kind, int shade) {
        int base = switch (kind) {
            case TerrainSurvey.WATER -> 0x526A70;
            case TerrainSurvey.FOREST -> 0xC3CCC6;
            case TerrainSurvey.SNOW -> 0xEBEEEC;
            case TerrainSurvey.SAND -> 0xD9D5C9;
            case TerrainSurvey.LAVA -> 0x94736B;
            default -> 0xD4D8D5;
        };
        int r = Math.max(0, Math.min(255, (base >> 16) + shade));
        int g = Math.max(0, Math.min(255, ((base >> 8) & 255) + shade));
        int b = Math.max(0, Math.min(255, (base & 255) + shade));
        // 保留底纸纤维，避免普通陆地用不透明单色矩形覆盖整块纸面。
        int alpha = switch (kind) {
            case TerrainSurvey.WATER, TerrainSurvey.LAVA -> 220;
            case TerrainSurvey.SNOW -> 130;
            case TerrainSurvey.FOREST -> 105;
            default -> 65;
        };
        return alpha << 24 | r << 16 | g << 8 | b;
    }

    private void line(double x1, double y1, double x2, double y2, int color) {
        int steps = Math.max(1, (int) Math.ceil(Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1))));
        for (int i = 0; i <= steps && budget > 0; i++) {
            double t = (double) i / steps;
            double x = Math.floor(x1 + (x2 - x1) * t), y = Math.floor(y1 + (y2 - y1) * t);
            rect(x, y, x + 1, y + 1, color);
        }
    }

    private void rect(double x1, double y1, double x2, double y2, int color) {
        int left = Math.max(16, (int) Math.floor(x1)), top = Math.max(16, (int) Math.floor(y1));
        int right = Math.min(width - 16, (int) Math.ceil(x2)), bottom = Math.min(height - 16, (int) Math.ceil(y2));
        if (right <= left || bottom <= top || budget-- <= 0) return;
        graphics.setColor(((color >>> 16) & 255) / 255F, ((color >>> 8) & 255) / 255F,
                (color & 255) / 255F, ((color >>> 24) & 255) / 255F);
        graphics.blit(PIXEL, left, top, right - left, bottom - top, 0, 0, 16, 16, 16, 16);
    }
}
