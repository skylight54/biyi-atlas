package com.biyi.atlas.map;

import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;

/**
 * 地图上的一个地点。作者用魔杖放置，或写在 places.json 里随整合包分发。
 *
 * @param type   {@link #TYPE_POI} 普通地点（主城/副本/商店…）或 {@link #TYPE_TELEPORT} 传送点
 * @param layer  深度层：0 地表（默认）、-1 地下、+1 上层…
 * @param region 所属章节区域 id（空表示不受区域解锁限制）
 */
public record AtlasPlace(String name, String type, ResourceLocation dimension, int layer, String region,
                         double x, double z, String icon, int rgb, String description, boolean alwaysLabel,
                         boolean interactive) {

    public static final String TYPE_POI = "poi";
    public static final String TYPE_TELEPORT = "teleport";

    public static AtlasPlace poi(String name, ResourceLocation dimension, int layer, String region,
                                 double x, double z, String icon, int rgb, String description) {
        return new AtlasPlace(name, TYPE_POI, dimension, layer, region, x, z, icon, rgb, description, true, true);
    }

    public static AtlasPlace teleport(String name, ResourceLocation dimension, int layer, String region,
                                      double x, double z, String description) {
        return new AtlasPlace(name, TYPE_TELEPORT, dimension, layer, region, x, z,
                "portal", 0x3F5C9E, description, true, true);
    }

    public boolean isTeleport() {
        return TYPE_TELEPORT.equals(type);
    }

    /** 改所属区域（区域被删时用它把引用置空，避免悬空引用）。 */
    public AtlasPlace withRegion(String newRegion) {
        return new AtlasPlace(name, type, dimension, layer, newRegion, x, z, icon, rgb, description,
                alwaysLabel, interactive);
    }

    /** 改名称/描述（编辑界面用）。 */
    public AtlasPlace withBasic(String newName, String newDescription) {
        return new AtlasPlace(newName, type, dimension, layer, region, x, z, icon, rgb, newDescription,
                alwaysLabel, interactive);
    }

    /** 是否可交互：false = 纯装饰（玩家不能追踪/传送/规划路线，也不可点选）。 */
    public AtlasPlace withInteractive(boolean newInteractive) {
        return new AtlasPlace(name, type, dimension, layer, region, x, z, icon, rgb, description,
                alwaysLabel, newInteractive);
    }

    /** 同名同维度同层视为同一个地点，用于合并"预置内容"和"游戏内编辑内容"。 */
    public String key() {
        return dimension + "|" + layer + "|" + name;
    }

    public JsonObject toJson() {
        JsonObject object = new JsonObject();
        object.addProperty("name", name);
        object.addProperty("type", type);
        object.addProperty("dimension", dimension.toString());
        object.addProperty("layer", layer);
        object.addProperty("region", region == null ? "" : region);
        object.addProperty("x", x);
        object.addProperty("z", z);
        object.addProperty("icon", icon);
        object.addProperty("color", String.format("%06X", rgb & 0xFFFFFF));
        object.addProperty("description", description);
        object.addProperty("alwaysLabel", alwaysLabel);
        object.addProperty("interactive", interactive);
        return object;
    }

    public static AtlasPlace fromJson(JsonObject object) {
        ResourceLocation dimension = ResourceLocation.tryParse(
                GsonHelper.getAsString(object, "dimension", "minecraft:overworld"));
        if (dimension == null) {
            return null;
        }
        return new AtlasPlace(
                GsonHelper.getAsString(object, "name", "地点"),
                GsonHelper.getAsString(object, "type", TYPE_POI),
                dimension,
                GsonHelper.getAsInt(object, "layer", 0),
                GsonHelper.getAsString(object, "region", ""),
                GsonHelper.getAsDouble(object, "x", 0.0D),
                GsonHelper.getAsDouble(object, "z", 0.0D),
                GsonHelper.getAsString(object, "icon", "flag"),
                parseRgb(GsonHelper.getAsString(object, "color", "8C4A44")),
                GsonHelper.getAsString(object, "description", ""),
                !object.has("alwaysLabel") || object.get("alwaysLabel").getAsBoolean(),
                !object.has("interactive") || object.get("interactive").getAsBoolean());
    }

    public static int parseRgb(String text) {
        String hex = text.startsWith("#") ? text.substring(1) : text;
        if (hex.length() == 8) {
            hex = hex.substring(2);
        }
        try {
            return Integer.parseInt(hex, 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return 0x8C4A44;
        }
    }
}
