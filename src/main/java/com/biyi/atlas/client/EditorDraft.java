package com.biyi.atlas.client;

import java.lang.ref.WeakReference;
import java.util.List;

/** Single-use geometry recovery scoped to the same live level and connection. */
final class EditorDraft {
    record Geometry(String dimension, int layer, String tool, String regionBrush, String roadBrush,
                    double x, double z, double scale, List<double[]> road, List<double[]> region) {
        Geometry {
            road = road.stream().map(double[]::clone).toList();
            region = region.stream().map(double[]::clone).toList();
        }
    }
    private WeakReference<Object> level = new WeakReference<>(null);
    private WeakReference<Object> connection = new WeakReference<>(null);
    private Geometry geometry;

    void save(Object level, Object connection, Geometry draft) {
        this.level = new WeakReference<>(level);
        this.connection = new WeakReference<>(connection);
        this.geometry = draft;
    }

    Geometry take(Object level, Object connection) {
        var result = level != null && connection != null && level == this.level.get()
                && connection == this.connection.get() ? geometry : null;
        geometry = null;
        return result;
    }
}
