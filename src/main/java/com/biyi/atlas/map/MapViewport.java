package com.biyi.atlas.map;

/** North-up camera in GUI pixels. Coordinates refer to the local drawing surface. */
public record MapViewport(int width, int height, double centerX, double centerZ, double pixelsPerBlock) {
    public MapViewport {
        if (width <= 0 || height <= 0 || !Double.isFinite(centerX) || !Double.isFinite(centerZ)
                || !Double.isFinite(pixelsPerBlock) || pixelsPerBlock <= 0) {
            throw new IllegalArgumentException("Invalid map viewport");
        }
    }

    public static double project(double world, double center, int extent, double scale) {
        return extent * 0.5 + (world - center) * scale;
    }

    public static double unproject(double gui, double center, int extent, double scale) {
        return center + (gui - extent * 0.5) / scale;
    }

    public double screenX(double x) { return project(x, centerX, width, pixelsPerBlock); }
    public double screenY(double z) { return project(z, centerZ, height, pixelsPerBlock); }
    public double worldX(double x) { return unproject(x, centerX, width, pixelsPerBlock); }
    public double worldZ(double y) { return unproject(y, centerZ, height, pixelsPerBlock); }
}
