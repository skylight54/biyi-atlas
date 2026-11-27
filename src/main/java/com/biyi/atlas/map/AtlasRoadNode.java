package com.biyi.atlas.map;

import com.google.gson.JsonObject;
import net.minecraft.util.GsonHelper;

import java.util.UUID;

/**
 * 道路上的一个节点：作者可以在任意位置加节点，支线从节点岔出。
 *
 * @param id    稳定标识（不随插入顶点/排序变化）
 * @param index 位于 {@code points} 的下标
 * @param name  显示名（默认"节点 N"）
 * @param kind  {@link #KIND_ANCHOR} 手动锚点 / {@link #KIND_JUNCTION} 岔口 / {@link #KIND_END} 端点 /
 *              {@link #KIND_BOUNDARY} 区域交界（自动生成）
 */
public record AtlasRoadNode(String id, int index, String name, String kind) {

    public static final String KIND_ANCHOR = "anchor";
    public static final String KIND_JUNCTION = "junction";
    public static final String KIND_END = "end";
    public static final String KIND_BOUNDARY = "boundary";

    public static AtlasRoadNode of(int index, String name) {
        return new AtlasRoadNode(UUID.randomUUID().toString().substring(0, 8), index, name, KIND_ANCHOR);
    }

    public AtlasRoadNode withIndex(int newIndex) {
        return new AtlasRoadNode(id, newIndex, name, kind);
    }

    public AtlasRoadNode withName(String newName) {
        return new AtlasRoadNode(id, index, newName, kind);
    }

    public AtlasRoadNode withKind(String newKind) {
        return new AtlasRoadNode(id, index, name, newKind);
    }

    public JsonObject toJson() {
        JsonObject object = new JsonObject();
        object.addProperty("id", id);
        object.addProperty("index", index);
        object.addProperty("name", name);
        object.addProperty("kind", kind);
        return object;
    }

    public static AtlasRoadNode fromJson(JsonObject object) {
        int index = GsonHelper.getAsInt(object, "index", 0);
        // 关键：缺 id 时用**确定性**回退（旧版这里随机生成 UUID，导致每次加载 id 都变 → branchOf 断链）
        String id = GsonHelper.getAsString(object, "id", "");
        if (id.isEmpty()) {
            id = "n" + index;
        }
        return new AtlasRoadNode(
                id,
                index,
                GsonHelper.getAsString(object, "name", "节点"),
                GsonHelper.getAsString(object, "kind", KIND_ANCHOR));
    }
}
