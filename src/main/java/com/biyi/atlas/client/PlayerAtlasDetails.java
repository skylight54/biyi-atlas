package com.biyi.atlas.client;

import com.biyi.atlas.map.AtlasPlace;
import com.biyi.atlas.map.AtlasRegion;
import java.util.List;
import java.util.Optional;
import java.util.Objects;

/** Client-side adapters for published, player-visible entrances (06) and local floors (08). */
public interface PlayerAtlasDetails {
    record Floor(int layer, String name) {}
    default Optional<AtlasTracker.Target> entrance(AtlasPlace place) { return Optional.empty(); }
    default List<Floor> floors(AtlasRegion area) { return List.of(new Floor(area.layer(), "地表")); }

    final class Registry {
        private Registry() {}
        private static PlayerAtlasDetails provider = new PlayerAtlasDetails() {};
        public static void install(PlayerAtlasDetails details) { provider = Objects.requireNonNull(details); }
        public static PlayerAtlasDetails get() { return provider; }
    }
}
