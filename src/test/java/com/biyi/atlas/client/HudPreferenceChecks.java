package com.biyi.atlas.client;

import com.biyi.atlas.map.MapViewport;
import java.nio.file.Files;
import java.nio.file.Path;

public final class HudPreferenceChecks {
    public static void main(String[] args) throws Exception {
        Path file = Files.createTempFile("atlas-hud", ".json");
        try {
            Files.writeString(file, "{}");
            AtlasSettings.load(file);
            require(AtlasSettings.zoom() == 1 && AtlasSettings.corner() == AtlasSettings.Corner.右上);
            Files.writeString(file, "{\"heldZoom\":0.125}");
            AtlasSettings.load(file);
            require(AtlasSettings.zoom() == .5);
            Files.writeString(file, "{\"hudZoom\":1.5,\"hudCorner\":\"左下\"}");
            AtlasSettings.load(file);
            double scale = AtlasSettings.hudCamera(128, 0, 0).pixelsPerBlock();
            for (double position : new double[]{0, -500, 0}) {
                var camera = AtlasSettings.hudCamera(128, position, position);
                require(camera.screenX(position) == 64 && camera.screenY(position) == 64);
                require(camera.pixelsPerBlock() == scale);
            }
            var fullscreen = new MapViewport(640, 480, 12, -80, 8);
            require(fullscreen.pixelsPerBlock() != scale);
            AtlasSettings.save(file);
            Path defaults = Files.createTempFile("atlas-defaults", ".json");
            try {
                Files.writeString(defaults, "{\"hudZoom\":9}");
                AtlasSettings.load(defaults);
                require(AtlasSettings.zoom() == 3);
                AtlasSettings.load(file);
                require(AtlasSettings.zoom() == 1.5 && AtlasSettings.corner() == AtlasSettings.Corner.左下);
            } finally { Files.deleteIfExists(defaults); }
        } finally { Files.deleteIfExists(file); }
        System.out.println("HudPreferenceChecks PASS");
    }

    private static void require(boolean condition) {
        if (!condition) throw new AssertionError();
    }
}
