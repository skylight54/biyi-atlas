package com.biyi.atlas.map;

/** Read-only state for the selected base map; sample counts can include local author previews. */
public record AreaMapStatus(String areaId, State state, int sampleCount, String detail) {
    public enum State { NO_AREA, REQUESTING, UNPUBLISHED, PARTIAL, COMPLETE }
}
