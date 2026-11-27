package com.biyi.atlas.map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个区域：用折线多边形圈定的范围 + 许可证解锁条件。
 *
 * <p>层级（v2）：顶层区域（{@code mapArea=true}）是"地图可移动范围 / 可切换大区块"的单位；
 * 子区域通过 {@code parent} 指向父区域，最多两层。子区域自带的图鉴会向上汇总到父区域。</p>
 *
 * <p>所有"改一个字段"的操作都走 {@code withXxx(...)} 单一重建入口，
 * 避免逐字段复制时漏字段（P0-2）。</p>
 */
public record AtlasRegion(String id, String name, ResourceLocation dimension, int layer,
                          List<double[]> outline, int lineRgb, int fillRgb, int fillAlpha,
                          String labelText, double labelX, double labelZ,
                          boolean showOutlineWhenLocked, String unlockType, String unlockId,
                          boolean defaultUnlocked, String image, List<BestiaryEntry> bestiary,
                          String parent, boolean mapArea, boolean childLocksContent) {

    public static final String UNLOCK_LICENSE = "license";
    public static final String UNLOCK_ADVANCEMENT = "advancement";
    public static final String UNLOCK_ALWAYS = "always";

    /** 点是否落在区域内（射线法，半开区间；顶点不重复计数）。 */
    public boolean contains(double x, double z) {
        if (outline.size() < 3) {
            return false;
        }
        boolean inside = false;
        for (int i = 0, j = outline.size() - 1; i < outline.size(); j = i++) {
            double xi = outline.get(i)[0];
            double zi = outline.get(i)[1];
            double xj = outline.get(j)[0];
            double zj = outline.get(j)[1];
            if (((zi > z) != (zj > z)) && (x < (xj - xi) * (z - zi) / (zj - zi) + xi)) {
                inside = !inside;
            }
        }
        return inside;
    }

    /** 多边形面积（鞋带公式，恒正）。用于"最内层/最小"判定。 */
    public double area() {
        if (outline.size() < 3) {
            return 0.0D;
        }
        double sum = 0.0D;
        for (int i = 0, j = outline.size() - 1; i < outline.size(); j = i++) {
            sum += outline.get(j)[0] * outline.get(i)[1] - outline.get(i)[0] * outline.get(j)[1];
        }
        return Math.abs(sum) / 2.0D;
    }

    /** 多边形包围盒：{minX, minZ, maxX, maxZ}。 */
    public double[] bounds() {
        double minX = Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        for (double[] point : outline) {
            minX = Math.min(minX, point[0]);
            minZ = Math.min(minZ, point[1]);
            maxX = Math.max(maxX, point[0]);
            maxZ = Math.max(maxZ, point[1]);
        }
        return new double[]{minX, minZ, maxX, maxZ};
    }

    /** 是否是子区域。 */
    public boolean isChild() {
        return parent != null && !parent.isEmpty();
    }

    // ------------------------------------------------------------ 单一重建入口

    public AtlasRegion withBasic(String newName, String newLabelText, String newUnlockId, String newImage) {
        return new AtlasRegion(id, newName, dimension, layer, outline, lineRgb, fillRgb, fillAlpha,
                newLabelText, labelX, labelZ, showOutlineWhenLocked, unlockType, newUnlockId,
                defaultUnlocked, newImage, bestiary, parent, mapArea, childLocksContent);
    }

    public AtlasRegion withOwner(String newParent, boolean newMapArea, boolean newChildLocksContent) {
        return new AtlasRegion(id, name, dimension, layer, outline, lineRgb, fillRgb, fillAlpha,
                labelText, labelX, labelZ, showOutlineWhenLocked, unlockType, unlockId,
                defaultUnlocked, image, bestiary, newParent, newMapArea, newChildLocksContent);
    }

    public AtlasRegion withLayer(int newLayer) {
        return new AtlasRegion(id, name, dimension, newLayer, outline, lineRgb, fillRgb, fillAlpha,
                labelText, labelX, labelZ, showOutlineWhenLocked, unlockType, unlockId,
                defaultUnlocked, image, bestiary, parent, mapArea, childLocksContent);
    }

    public AtlasRegion withFlags(boolean outlineWhenLocked, boolean defaultOpen) {
        return new AtlasRegion(id, name, dimension, layer, outline, lineRgb, fillRgb, fillAlpha,
                labelText, labelX, labelZ, outlineWhenLocked, unlockType, unlockId,
                defaultOpen, image, bestiary, parent, mapArea, childLocksContent);
    }

    public AtlasRegion withBestiary(List<BestiaryEntry> newBestiary) {
        return new AtlasRegion(id, name, dimension, layer, outline, lineRgb, fillRgb, fillAlpha,
                labelText, labelX, labelZ, showOutlineWhenLocked, unlockType, unlockId,
                defaultUnlocked, image, newBestiary, parent, mapArea, childLocksContent);
    }

    public AtlasRegion withOutline(List<double[]> newOutline, double newLabelX, double newLabelZ) {
        return new AtlasRegion(id, name, dimension, layer, newOutline, lineRgb, fillRgb, fillAlpha,
                labelText, newLabelX, newLabelZ, showOutlineWhenLocked, unlockType, unlockId,
                defaultUnlocked, image, bestiary, parent, mapArea, childLocksContent);
    }

    // ------------------------------------------------------------ JSON

    public JsonObject toJson() {
        JsonObject object = new JsonObject();
        object.addProperty("id", id);
        object.addProperty("name", name);
        object.addProperty("dimension", dimension.toString());
        object.addProperty("layer", layer);
        object.addProperty("lineColor", String.format("%06X", lineRgb & 0xFFFFFF));
        object.addProperty("fillColor", String.format("%06X", fillRgb & 0xFFFFFF));
        object.addProperty("fillAlpha", fillAlpha);
        object.addProperty("labelText", labelText);
        object.addProperty("labelX", labelX);
        object.addProperty("labelZ", labelZ);
        object.addProperty("showOutlineWhenLocked", showOutlineWhenLocked);
        JsonObject unlock = new JsonObject();
        unlock.addProperty("type", unlockType);
        unlock.addProperty("id", unlockId);
        object.add("unlock", unlock);
        object.addProperty("defaultUnlocked", defaultUnlocked);
        if (image != null && !image.isEmpty()) {
            object.addProperty("image", image);
        }
        if (parent != null && !parent.isEmpty()) {
            object.addProperty("parent", parent);
        }
        object.addProperty("mapArea", mapArea);
        object.addProperty("childLocksContent", childLocksContent);
        JsonArray bestiaryArray = new JsonArray();
        for (BestiaryEntry entry : bestiary) {
            bestiaryArray.add(entry.toJson());
        }
        object.add("bestiary", bestiaryArray);
        JsonArray array = new JsonArray();
        for (double[] point : outline) {
            JsonArray pair = new JsonArray();
            pair.add(point[0]);
            pair.add(point[1]);
            array.add(pair);
        }
        object.add("outline", array);
        return object;
    }

    public static AtlasRegion fromJson(JsonObject object) {
        ResourceLocation dimension = ResourceLocation.tryParse(
                GsonHelper.getAsString(object, "dimension", "minecraft:overworld"));
        if (dimension == null || !object.has("outline")) {
            return null;
        }
        List<double[]> outline = new ArrayList<>();
        JsonArray array = object.getAsJsonArray("outline");
        for (int i = 0; i < array.size(); i++) {
            JsonArray pair = array.get(i).getAsJsonArray();
            if (pair.size() >= 2) {
                outline.add(new double[]{pair.get(0).getAsDouble(), pair.get(1).getAsDouble()});
            }
        }
        if (outline.size() < 3) {
            return null;
        }
        String id = GsonHelper.getAsString(object, "id", "");
        if (id.isEmpty()) {
            // 缺 id 不再落成空主键（空 id 与"不受区域限制"的约定冲突）：用首个顶点派生一个确定性 id
            id = "region_at_" + (int) Math.floor(outline.get(0)[0]) + "_" + (int) Math.floor(outline.get(0)[1]);
        }
        String unlockType = UNLOCK_LICENSE;
        String unlockId = id;
        if (object.has("unlock")) {
            JsonObject unlock = GsonHelper.getAsJsonObject(object, "unlock");
            unlockType = GsonHelper.getAsString(unlock, "type", UNLOCK_LICENSE);
            unlockId = GsonHelper.getAsString(unlock, "id", id);
        }
        List<BestiaryEntry> bestiary = new ArrayList<>();
        if (object.has("bestiary")) {
            for (JsonElement element : object.getAsJsonArray("bestiary")) {
                if (element.isJsonObject()) {
                    bestiary.add(BestiaryEntry.fromJson(element.getAsJsonObject()));
                }
            }
        }
        String parent = GsonHelper.getAsString(object, "parent", "");
        boolean mapArea = !object.has("mapArea") || object.get("mapArea").getAsBoolean();
        // 子区域默认不作为地图范围单位
        if (!parent.isEmpty() && !object.has("mapArea")) {
            mapArea = false;
        }
        return new AtlasRegion(
                id,
                GsonHelper.getAsString(object, "name", id),
                dimension,
                GsonHelper.getAsInt(object, "layer", 0),
                outline,
                AtlasPlace.parseRgb(GsonHelper.getAsString(object, "lineColor", "5A4632")),
                AtlasPlace.parseRgb(GsonHelper.getAsString(object, "fillColor", "7C8C5A")),
                Math.max(0, Math.min(255, GsonHelper.getAsInt(object, "fillAlpha", 40))),
                GsonHelper.getAsString(object, "labelText", GsonHelper.getAsString(object, "name", "")),
                GsonHelper.getAsDouble(object, "labelX", outline.get(0)[0]),
                GsonHelper.getAsDouble(object, "labelZ", outline.get(0)[1]),
                !object.has("showOutlineWhenLocked") || object.get("showOutlineWhenLocked").getAsBoolean(),
                unlockType,
                unlockId,
                object.has("defaultUnlocked") && object.get("defaultUnlocked").getAsBoolean(),
                GsonHelper.getAsString(object, "image", ""),
                bestiary,
                parent,
                mapArea,
                !object.has("childLocksContent") || object.get("childLocksContent").getAsBoolean());   // 缺省 = 锁定即隐藏
    }
}
