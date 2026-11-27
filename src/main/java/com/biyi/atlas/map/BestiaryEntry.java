package com.biyi.atlas.map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.util.GsonHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * 怪物图鉴条目：一个区域里可以出现的怪物（通常是预设的 CNPC NPC）。
 *
 * @param name       名字（NPC 显示名）
 * @param title      称号/说明
 * @param health     最大生命（-1 表示未知）
 * @param drops      掉落物名字
 * @param entityType 生物类型 id（用于图标/排查）
 */
public record BestiaryEntry(String name, String title, int health, List<String> drops, String entityType) {

    public static BestiaryEntry of(String name, String entityType) {
        return new BestiaryEntry(name, "", -1, new ArrayList<>(), entityType);
    }

    public JsonObject toJson() {
        JsonObject object = new JsonObject();
        object.addProperty("name", name);
        object.addProperty("title", title);
        object.addProperty("health", health);
        object.addProperty("entityType", entityType);
        JsonArray array = new JsonArray();
        for (String drop : drops) {
            array.add(drop);
        }
        object.add("drops", array);
        return object;
    }

    public static BestiaryEntry fromJson(JsonObject object) {
        List<String> drops = new ArrayList<>();
        if (object.has("drops")) {
            for (JsonElement element : GsonHelper.getAsJsonArray(object, "drops")) {
                drops.add(element.getAsString());
            }
        }
        return new BestiaryEntry(
                GsonHelper.getAsString(object, "name", "未知"),
                GsonHelper.getAsString(object, "title", ""),
                GsonHelper.getAsInt(object, "health", -1),
                drops,
                GsonHelper.getAsString(object, "entityType", ""));
    }

    /** 用于去重：同名同类型算同一种。 */
    public String key() {
        return name + "|" + entityType;
    }
}
