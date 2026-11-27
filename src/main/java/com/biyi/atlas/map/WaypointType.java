package com.biyi.atlas.map;

/**
 * 路标类型：主要给"用 CNPC/NPC 做的传送点、任务点、剧情点"用。
 * 图标名对应 {@code textures/atlas/marker/<icon>.png}。
 */
public enum WaypointType {

    TELEPORT("传送点", "portal", 0x3E6E9E),
    QUEST("任务", "quest", 0xD4A64A),
    STORY("剧情", "flag", 0x8C4A44),
    SHOP("商店", "shop", 0xA8322D),
    TOWN("主城", "town", 0x6B4A2A),
    CUSTOM("自定义", "pin", 0x5A4632);

    private final String label;
    private final String icon;
    private final int rgb;

    WaypointType(String label, String icon, int rgb) {
        this.label = label;
        this.icon = icon;
        this.rgb = rgb;
    }

    public String label() {
        return label;
    }

    public String icon() {
        return icon;
    }

    public int rgb() {
        return rgb;
    }

    public WaypointType next() {
        WaypointType[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    public static WaypointType byId(String id) {
        if (id != null) {
            for (WaypointType type : values()) {
                if (type.name().equalsIgnoreCase(id)) {
                    return type;
                }
            }
        }
        return TELEPORT;
    }
}
