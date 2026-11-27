package com.biyi.atlas.map;

import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;

/**
 * 作者手绘的地图图层：把一张 PNG 按世界坐标贴到地图上（羊皮纸手绘图）。
 * 区域解锁后，属于该区域的地图图层才显示——这就是"解锁后看到预设地图"。
 *
 * @param width          贴图宽（像素）
 * @param height         贴图高（像素）
 * @param blocksPerPixel 一个贴图像素对应多少世界方块（决定缩放）
 * @param alpha          0~255，做旧/淡化用
 */
public record AtlasMapImage(String id, ResourceLocation dimension, int layer, String region,
                            ResourceLocation texture, double originX, double originZ,
                            int width, int height, double blocksPerPixel, int alpha,
                            String name, String description) {

    public AtlasMapImage(String id, ResourceLocation dimension, int layer, String region,
                         ResourceLocation texture, double originX, double originZ,
                         int width, int height, double blocksPerPixel, int alpha) {
        this(id, dimension, layer, region, texture, originX, originZ, width, height, blocksPerPixel, alpha,
                id, "");
    }

    /** 改所属区域（区域被删时用它把引用置空）。 */
    public AtlasMapImage withRegion(String newRegion) {
        return new AtlasMapImage(id, dimension, layer, newRegion, texture, originX, originZ, width, height,
                blocksPerPixel, alpha, name, description);
    }

    /** 图像在地图上的中心点（用于追踪/定位）。 */
    public double centerX() {
        return originX + width * blocksPerPixel / 2.0D;    }

    public double centerZ() {
        return originZ + height * blocksPerPixel / 2.0D;
    }

    /** 允许写成 "biyi_atlas:atlas/map/ch1" 或完整 "biyi_atlas:textures/atlas/map/ch1.png"。 */
    public static ResourceLocation normalizeTexture(String value) {
        String path = value;
        String namespace = "biyi_atlas";
        int colon = value.indexOf(':');
        if (colon >= 0) {
            namespace = value.substring(0, colon);
            path = value.substring(colon + 1);
        }
        if (!path.startsWith("textures/")) {
            path = "textures/" + path;
        }
        if (!path.endsWith(".png")) {
            path = path + ".png";
        }
        return ResourceLocation.tryParse(namespace + ":" + path);
    }

    public JsonObject toJson() {
        JsonObject object = new JsonObject();
        object.addProperty("id", id);
        object.addProperty("dimension", dimension.toString());
        object.addProperty("layer", layer);
        object.addProperty("region", region == null ? "" : region);
        object.addProperty("texture", texture.toString());
        object.addProperty("originX", originX);
        object.addProperty("originZ", originZ);
        object.addProperty("width", width);
        object.addProperty("height", height);
        object.addProperty("blocksPerPixel", blocksPerPixel);
        object.addProperty("alpha", alpha);
        object.addProperty("name", name);
        object.addProperty("description", description);
        return object;
    }

    public static AtlasMapImage fromJson(JsonObject object) {
        ResourceLocation dimension = ResourceLocation.tryParse(
                GsonHelper.getAsString(object, "dimension", "minecraft:overworld"));
        ResourceLocation texture = normalizeTexture(GsonHelper.getAsString(object, "texture", ""));
        if (dimension == null || texture == null || !object.has("width")) {
            return null;
        }
        int width = Math.max(1, GsonHelper.getAsInt(object, "width", 1));
        int height = Math.max(1, GsonHelper.getAsInt(object, "height", 1));
        return new AtlasMapImage(
                GsonHelper.getAsString(object, "id", texture.getPath()),
                dimension,
                GsonHelper.getAsInt(object, "layer", 0),
                GsonHelper.getAsString(object, "region", ""),
                texture,
                GsonHelper.getAsDouble(object, "originX", 0.0D),
                GsonHelper.getAsDouble(object, "originZ", 0.0D),
                width,
                height,
                Math.max(0.01D, GsonHelper.getAsDouble(object, "blocksPerPixel", 4.0D)),
                Math.max(0, Math.min(255, GsonHelper.getAsInt(object, "alpha", 255))),
                GsonHelper.getAsString(object, "name", texture.getPath()),
                GsonHelper.getAsString(object, "description", ""));
    }
}
