package com.biyi.atlas.map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条道路：折线 + 节点（往 {@code points} 里插入顶点时登记下标）。
 *
 * <p>所有"改一个字段"的操作走 {@code withXxx(...)} 单一重建入口，避免逐字段复制漏字段（P0-2）。</p>
 *
 * @param id       稳定标识（空则用名字），支线用它引用父线
 * @param nodes    节点列表（下标指向 points）
 * @param branchOf 支线来源：{@code 父线id#父节点id}，主线为空
 */
public record AtlasRoad(String id, String name, ResourceLocation dimension, int layer, String region,
                        List<double[]> points, int width, int rgb, boolean dashed,
                        List<AtlasRoadNode> nodes, String branchOf) {

    public AtlasRoad withName(String newName) {
        return new AtlasRoad(id, newName, dimension, layer, region, points, width, rgb, dashed, nodes, branchOf);
    }

    public AtlasRoad withLayer(int newLayer) {
        return new AtlasRoad(id, name, dimension, newLayer, region, points, width, rgb, dashed, nodes, branchOf);
    }

    public AtlasRoad withWidth(int newWidth) {
        return new AtlasRoad(id, name, dimension, layer, region, points, newWidth, rgb, dashed, nodes, branchOf);
    }

    public AtlasRoad withDashed(boolean newDashed) {
        return new AtlasRoad(id, name, dimension, layer, region, points, width, rgb, newDashed, nodes, branchOf);
    }

    public AtlasRoad withRegion(String newRegion) {
        return new AtlasRoad(id, name, dimension, layer, newRegion, points, width, rgb, dashed, nodes, branchOf);
    }

    public AtlasRoad withPoints(List<double[]> newPoints, List<AtlasRoadNode> newNodes) {
        return new AtlasRoad(id, name, dimension, layer, region, newPoints, width, rgb, dashed,
                newNodes, branchOf);
    }

    public AtlasRoad withNodes(List<AtlasRoadNode> newNodes) {
        return new AtlasRoad(id, name, dimension, layer, region, points, width, rgb, dashed, newNodes,
                branchOf);
    }

    public AtlasRoad withBranch(String newBranchOf, List<AtlasRoadNode> newNodes) {
        return new AtlasRoad(id, name, dimension, layer, region, points, width, rgb, dashed, newNodes,
                newBranchOf);
    }

    /** 稳定标识（id 为空时退回名字，兼容旧数据）。 */
    public String key() {
        return id == null || id.isEmpty() ? name : id;
    }

    public AtlasRoadNode nodeById(String nodeId) {
        for (AtlasRoadNode node : nodes) {
            if (node.id().equals(nodeId)) {
                return node;
            }
        }
        return null;
    }

    /** 某个点下标对应的节点（可能没有）。 */
    public AtlasRoadNode nodeAtIndex(int index) {
        for (AtlasRoadNode node : nodes) {
            if (node.index() == index) {
                return node;
            }
        }
        return null;
    }

    public JsonObject toJson() {
        JsonObject object = new JsonObject();
        object.addProperty("id", key());
        object.addProperty("name", name);
        object.addProperty("dimension", dimension.toString());
        object.addProperty("layer", layer);
        object.addProperty("width", width);
        object.addProperty("color", String.format("%06X", rgb & 0xFFFFFF));
        object.addProperty("dashed", dashed);
        if (region != null && !region.isEmpty()) {
            object.addProperty("region", region);
        }
        if (branchOf != null && !branchOf.isEmpty()) {
            object.addProperty("branchOf", branchOf);
        }
        JsonArray nodeArray = new JsonArray();
        for (AtlasRoadNode node : nodes) {
            nodeArray.add(node.toJson());
        }
        object.add("nodes", nodeArray);
        JsonArray array = new JsonArray();
        for (double[] point : points) {
            JsonArray pair = new JsonArray();
            pair.add(point[0]);
            pair.add(point[1]);
            array.add(pair);
        }
        object.add("points", array);
        return object;
    }

    public static AtlasRoad fromJson(JsonObject object) {
        ResourceLocation dimension = ResourceLocation.tryParse(
                GsonHelper.getAsString(object, "dimension", "minecraft:overworld"));
        if (dimension == null) {
            return null;
        }
        List<double[]> points = new ArrayList<>();
        if (object.has("points")) {
            for (JsonElement element : object.getAsJsonArray("points")) {
                JsonArray pair = element.getAsJsonArray();
                if (pair.size() >= 2) {
                    points.add(new double[]{pair.get(0).getAsDouble(), pair.get(1).getAsDouble()});
                }
            }
        }
        List<AtlasRoadNode> nodes = new ArrayList<>();
        if (object.has("nodes")) {
            for (JsonElement element : object.getAsJsonArray("nodes")) {
                if (element.isJsonObject()) {
                    nodes.add(AtlasRoadNode.fromJson(element.getAsJsonObject()));
                }
            }
        }
        String name = GsonHelper.getAsString(object, "name", "道路");
        String id = GsonHelper.getAsString(object, "id", "");
        if (id.isEmpty()) {
            id = name;
        }
        return new AtlasRoad(
                id,
                name,
                dimension,
                GsonHelper.getAsInt(object, "layer", 0),
                GsonHelper.getAsString(object, "region", ""),
                points,
                Math.max(1, GsonHelper.getAsInt(object, "width", 3)),
                AtlasPlace.parseRgb(GsonHelper.getAsString(object, "color", "8C6F52")),
                object.has("dashed") && object.get("dashed").getAsBoolean(),
                nodes,
                GsonHelper.getAsString(object, "branchOf", ""));
    }
}
