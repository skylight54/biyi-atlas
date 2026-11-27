package com.biyi.atlas.client;

import com.biyi.atlas.map.AtlasContent;
import com.biyi.atlas.map.AtlasRegion;
import com.biyi.atlas.map.MapAudience;
import net.minecraft.resources.ResourceLocation;
import java.util.List;

/** Read access shared by the HUD, the player map and the author preview. */
public final class AtlasReadView {
    private AtlasReadView() {}

    public static List<AtlasRegion> regions(MapAudience audience, ResourceLocation dimension, int layer) {
        if (!AtlasAuthoring.known()) return List.of();
        var regions = AtlasContent.get().regionsAt(dimension, layer);
        if (audience == MapAudience.EDITOR && AtlasAuthoring.confirmed()) return List.copyOf(regions);
        return regions.stream().filter(region -> AtlasVisibility.regionVisible(region.id())).toList();
    }

    public static boolean elementVisible(MapAudience audience, AtlasVisibility.Kind kind, String regionId,
                                         ResourceLocation dimension, int layer, double x, double z) {
        if (!AtlasAuthoring.known()) return false;
        boolean editor = audience == MapAudience.EDITOR && AtlasAuthoring.confirmed();
        if (!editor) {
            var area = AtlasMapSession.active();
            if (area == null || !area.dimension().equals(dimension) || !area.contains(x, z)) return false;
        }
        return AtlasVisibility.visible(kind, regionId, x, z, editor, dimension, layer);
    }
}
