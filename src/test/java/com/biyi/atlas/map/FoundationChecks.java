package com.biyi.atlas.map;

import com.biyi.atlas.client.AtlasServerView;
import com.biyi.atlas.client.AtlasVisibility;
import com.biyi.atlas.client.AtlasUiLayout;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;

final class FoundationChecks {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static void run() {
        for (boolean creative : new boolean[]{false,true}) {
            for (boolean known : new boolean[]{false,true}) {
                for (boolean grant : new boolean[]{false,true}) {
                    var expected = creative && known && grant ? MapAudience.EDITOR : MapAudience.PLAYER;
                    require(MapAudience.forMapKey(creative, known, grant) == expected, "map-key role");
                }
            }
        }
        var camera = new MapViewport(321, 239, -200.25, 7.75, 0.37);
        for (double world : new double[]{-399.75, -1, 0, 128, 300.3}) {
            require(Math.abs(camera.worldX(camera.screenX(world)) - world) < 1e-9, "X round trip");
            require(Math.abs(camera.worldZ(camera.screenY(world)) - world) < 1e-9, "Z round trip");
        }
        for (int width : new int[]{320, 348, 420, 470, 488, 640}) {
            for (boolean author : new boolean[]{false,true}) {
                var header = AtlasUiLayout.frame(width, 240, author).header();
                for (var button : AtlasUiLayout.navigation(width, author)) {
                    require(header.contains(16 + button.x() + button.width() - 1,
                            34 + button.y() + button.height() - 1), "navigation within header");
                }
            }
        }
        var stream = FoundationChecks.class.getResourceAsStream("/foundation/atlas.json");
        if (stream == null) throw new AssertionError("missing shared fixture");
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            checkFixture(JsonParser.parseReader(reader).getAsJsonObject());
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    private static void checkFixture(JsonObject fixture) {
        var regions = fixture.getAsJsonArray("regions");
        var bay = AtlasRegion.fromJson(regions.get(0).getAsJsonObject());
        var archive = AtlasRegion.fromJson(regions.get(1).getAsJsonObject());
        var landmark = fixture.getAsJsonObject("landmark");
        for (String key : new String[]{"anchor", "entrance"}) {
            var point = landmark.getAsJsonArray(key);
            require(bay.contains(point.get(0).getAsDouble(), point.get(2).getAsDouble()), key);
        }
        AtlasServerView.apply(Set.of(fixture.getAsJsonArray("playerVisible").get(0).getAsString()));
        try {
            require(AtlasVisibility.regionVisible(bay.id()), "published parent visible");
            require(!AtlasVisibility.visible(AtlasVisibility.Kind.MARKER, archive.id(), 16, 16, false,
                    bay.dimension(), bay.layer()), "player cannot see locked marker");
            require(AtlasVisibility.visible(AtlasVisibility.Kind.MARKER, archive.id(), 16, 16, true,
                    bay.dimension(), bay.layer()), "editor can inspect locked marker");
        } finally {
            AtlasServerView.clear();
        }
        var terrain = new TerrainSurvey();
        var known = fixture.getAsJsonArray("knownBlock");
        int x = known.get(0).getAsInt(), z = known.get(1).getAsInt();
        terrain.put(Math.floorDiv(x, 4), Math.floorDiv(z, 4), 64, TerrainSurvey.LAND);
        var present = TerrainTilePos.atBlock(x, z);
        var missing = fixture.getAsJsonArray("missingBlock");
        var absent = TerrainTilePos.atBlock(missing.get(0).getAsInt(), missing.get(1).getAsInt());
        require(terrain.hasTile(present.x(), present.z()) && !terrain.hasTile(absent.x(), absent.z()), "tile occupancy");
        require(TerrainTilePos.atBlock(-129, 127).equals(new TerrainTilePos(-2, 0)), "half-open tiles");
        String current = "7";
        for (var example : fixture.getAsJsonArray("publish")) {
            var edit = example.getAsJsonObject();
            var receipt = new AreaPublishReceipt(edit.get("operationId").getAsString(), edit.get("areaId").getAsString(),
                    AreaPublishReceipt.Outcome.valueOf(edit.get("outcome").getAsString()),
                    edit.get("currentRevision").getAsString(), edit.get("reason").getAsString());
            boolean matchesBase = current.equals(edit.get("baseRevision").getAsString());
            require((receipt.outcome() == AreaPublishReceipt.Outcome.PUBLISHED) == matchesBase, "fixture revision");
            require(matchesBase || current.equals(receipt.currentRevision()), "conflict preserves revision");
            current = receipt.currentRevision();
        }
    }
}
