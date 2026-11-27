package com.biyi.atlas.map;

import java.util.ArrayList;
import java.util.List;

/** 连续线段的扫描行几何；不依赖游戏渲染器，便于验证长线裁剪与接缝。 */
public final class MapStroke {
    public record Span(int left, int top, int right) {}
    private MapStroke() {}

    /** 返回线段在矩形中的参数区间，避免远处长线消耗绘制预算。 */
    public static double[] clip(double x1, double y1, double x2, double y2,
                                double left, double top, double right, double bottom) {
        double[] p = {x1 - x2, x2 - x1, y1 - y2, y2 - y1};
        double[] q = {x1 - left, right - x1, y1 - top, bottom - y1};
        double low = 0, high = 1;
        for (int i = 0; i < 4; i++) {
            if (Math.abs(p[i]) < 1e-10) { if (q[i] < 0) return null; continue; }
            double t = q[i] / p[i];
            if (p[i] < 0) low = Math.max(low, t); else high = Math.min(high, t);
            if (low > high) return null;
        }
        return new double[]{low, high};
    }

    public static List<Span> spans(double x1, double y1, double x2, double y2, double width,
                                   int left, int top, int right, int bottom) {
        List<Span> result = new ArrayList<>();
        double half = Math.max(0.5, width / 2);
        double length = Math.hypot(x2 - x1, y2 - y1);
        if (length < 1e-8) return result;
        double nx = -(y2 - y1) / length * half, ny = (x2 - x1) / length * half;
        double[] xs = {x1 + nx, x2 + nx, x2 - nx, x1 - nx};
        double[] ys = {y1 + ny, y2 + ny, y2 - ny, y1 - ny};
        double minY = Math.min(y1,y2)-half;
        double maxY = Math.max(y1,y2)+half;
        for (int y = Math.max(top, (int) Math.floor(minY)); y < Math.min(bottom, (int) Math.ceil(maxY)); y++) {
            double scan = y + 0.5, lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < 4; i++) {
                int j = (i + 1) % 4;
                if ((ys[i] <= scan && ys[j] > scan) || (ys[j] <= scan && ys[i] > scan)) {
                    double x = xs[i] + (xs[j] - xs[i]) * (scan - ys[i]) / (ys[j] - ys[i]);
                    lo = Math.min(lo, x); hi = Math.max(hi, x);
                }
            }
            // 圆端帽使相邻折线自然连接，避免转折处出现方口、漏白。
            for(int endpoint=0;endpoint<2;endpoint++){
                double cx=endpoint==0?x1:x2,cy=endpoint==0?y1:y2;
                double dy=scan-cy;
                if(Math.abs(dy)<=half){double dx=Math.sqrt(half*half-dy*dy);lo=Math.min(lo,cx-dx);hi=Math.max(hi,cx+dx);}
            }
            if (!Double.isFinite(lo)) continue;
            int a = Math.max(left, (int) Math.floor(lo)), b = Math.min(right, (int) Math.ceil(hi));
            if (b > a) result.add(new Span(a, y, b));
        }
        return result;
    }
}
