package com.biyi.atlas.map;

import java.util.ArrayList;
import java.util.List;

/** 按固定对角线拆成两个三角形，避免鞍点产生不确定的等高线连接。 */
public final class TerrainContours {
    public record Segment(double x1, double z1, double x2, double z2) {}
    private TerrainContours() {}

    public static List<Segment> atLevel(double nw, double ne, double se, double sw, double level) {
        List<Segment> result = new ArrayList<>(2);
        triangle(result, new double[][]{{0, 0, nw}, {1, 0, ne}, {1, 1, se}}, level);
        triangle(result, new double[][]{{0, 0, nw}, {1, 1, se}, {0, 1, sw}}, level);
        return result;
    }

    private static void triangle(List<Segment> result, double[][] points, double level) {
        double[][] hits = new double[2][2];
        int n = 0;
        for (int i = 0; i < 3; i++) {
            double[] a = points[i], b = points[(i + 1) % 3];
            // 半开规则：平坦边不重复生成，两侧格子在共享边上得到相同交点。
            if ((a[2] < level) == (b[2] < level)) continue;
            double t = (level - a[2]) / (b[2] - a[2]);
            hits[n][0] = a[0] + (b[0] - a[0]) * t;
            hits[n++][1] = a[1] + (b[1] - a[1]) * t;
        }
        if (n == 2 && Math.hypot(hits[0][0] - hits[1][0], hits[0][1] - hits[1][1]) > 1e-8) {
            result.add(new Segment(hits[0][0], hits[0][1], hits[1][0], hits[1][1]));
        }
    }
}
