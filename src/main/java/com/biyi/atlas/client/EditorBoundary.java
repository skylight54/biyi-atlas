package com.biyi.atlas.client;

import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.util.List;

/** Polygon subtraction also catches edges cutting across the notch of a concave parent. */
final class EditorBoundary {
    private EditorBoundary() {}

    static boolean encloses(List<double[]> outer, List<double[]> inner) {
        Area child = area(inner);
        if (child.isEmpty()) return false;
        child.subtract(area(outer));
        return child.isEmpty();
    }

    private static Area area(List<double[]> points) {
        Path2D.Double path = new Path2D.Double();
        if (points.size() >= 3) {
            path.moveTo(points.get(0)[0], points.get(0)[1]);
            for (int i = 1; i < points.size(); i++) path.lineTo(points.get(i)[0], points.get(i)[1]);
            path.closePath();
        }
        return new Area(path);
    }
}
