package com.biyi.atlas.map;

import net.minecraft.nbt.CompoundTag;

/**
 * 地图上的一个路标。主要由作者用"路标魔杖"右键 NPC 生成（传送点/任务点/剧情点…），
 * 玩家只读；点开可以看信息。
 */
public record AtlasWaypoint(String name, String type, int layer, double x, double z, String icon,
                            String description) {

    public static final String DEFAULT_ICON = "pin";

    public static AtlasWaypoint of(String name, double x, double z) {
        return new AtlasWaypoint(name, WaypointType.CUSTOM.name(), 0, x, z, DEFAULT_ICON, "");
    }

    public WaypointType waypointType() {
        return WaypointType.byId(type);
    }

    /** 显示用图标：作者指定的 icon 优先，否则用类型自带图标。 */
    public String displayIcon() {
        if (icon != null && !icon.isEmpty()) {
            return icon;
        }
        return waypointType().icon();
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("name", name);
        tag.putString("type", type);
        tag.putInt("layer", layer);
        tag.putDouble("x", x);
        tag.putDouble("z", z);
        tag.putString("icon", icon);
        tag.putString("description", description);
        return tag;
    }

    public static AtlasWaypoint load(CompoundTag tag) {
        return new AtlasWaypoint(
                tag.getString("name"),
                tag.contains("type") ? tag.getString("type") : WaypointType.CUSTOM.name(),
                tag.contains("layer") ? tag.getInt("layer") : 0,
                tag.getDouble("x"),
                tag.getDouble("z"),
                tag.contains("icon") ? tag.getString("icon") : DEFAULT_ICON,
                tag.contains("description") ? tag.getString("description") : "");
    }
}
