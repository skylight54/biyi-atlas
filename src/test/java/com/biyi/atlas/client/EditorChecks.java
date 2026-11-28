package com.biyi.atlas.client;

import java.util.List;

/** Standalone Java 17 checks, run with assertions enabled. */
public final class EditorChecks {
    public static void main(String[] args) {
        var boundary = List.of(p(0, 0), p(8, 0), p(8, 8), p(6, 8), p(6, 2),
                p(2, 2), p(2, 8), p(0, 8));
        assert !EditorBoundary.encloses(boundary, List.of(p(1, 7), p(7, 7), p(1, 1)));
        assert EditorBoundary.encloses(boundary, List.of(p(0, 0), p(8, 0), p(2, 2)));
        var recovery = new EditorDraft();
        var level = new Object();
        var connection = new Object();
        var point = p(-15, 34);
        var draft = new EditorDraft.Geometry("minecraft:overworld", -1, "ROAD", "POLY", "POLY",
                8, -13, 1.5, List.of(point), List.of());
        recovery.save(level, connection, draft);
        point[0] = 0;
        var restored = recovery.take(level, connection);
        assert restored.road().get(0)[0] == -15 && restored.layer() == -1;
        assert restored.scale() == 1.5 && restored.tool().equals("ROAD");
        assert recovery.take(level, connection) == null;
        recovery.save(level, connection, draft);
        assert recovery.take(level, new Object()) == null;
        recovery.save(level, connection, draft);
        assert recovery.take(new Object(), connection) == null;
        System.out.println("EditorChecks PASS");
    }

    private static double[] p(double x, double z) { return new double[]{x, z}; }
}
