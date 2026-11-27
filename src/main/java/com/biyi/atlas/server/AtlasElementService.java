package com.biyi.atlas.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.GsonHelper;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 服务端**按玩家过滤道路/地点**（A6 第②步收尾：逐段可见性）。
 *
 * <p>做法：直接对存档内内容 JSON 做筛选与标注（不解析成对象，省事且不会丢字段）——</p>
 * <ul>
 *   <li>**地点**：所在区域对该玩家可见才下发；区域未知（客户端也没定义）→ fail-closed 丢弃；</li>
 *   <li>**道路**：跨区域的路按**段**计算——逐段取中点、求"最内层区域"，可见则 '1' 否则 '0'，
 *       写进 {@code serverMask} 字段一起下发；客户端据此**只画授权段**。</li>
 * </ul>
 *
 * <p>输出按约 16 KB 一批的多段 JSON 字符串，避免单个网络包过大。</p>
 */
public final class AtlasElementService {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int BATCH_CHARS = 16 * 1024;

    private AtlasElementService() {
    }

    /** 一批一批的元素 JSON（每批是一个 JSON 数组字符串）。 */
    public static List<String> buildBatches(ServerPlayer player) {
        List<String> batches = new ArrayList<>();
        if (player == null) {
            return batches;
        }
        MinecraftServer server = player.server;
        AtlasRegionRegistry.refreshIfStale(server);
        String text = AtlasRegionRegistry.rawContentText(server);
        if (text == null || text.isEmpty()) {
            return batches;
        }
        JsonObject root;
        try {
            root = GsonHelper.parse(text);
        } catch (Exception error) {
            LOGGER.warn("[BiyiAtlas] 解析服务端内容失败: {}", error.toString());
            return batches;
        }
        Set<String> visible = AtlasVisibilityService.visibleFor(player);
        List<JsonElement> out = new ArrayList<>();

        JsonElement roads = section(root, "Roads", "roads");
        if (roads != null && roads.isJsonArray()) {
            for (JsonElement item : roads.getAsJsonArray()) {
                if (!item.isJsonObject()) {
                    continue;
                }
                JsonObject road = item.getAsJsonObject().deepCopy();
                road.addProperty("kind", "road");
                String mask = segmentMask(road, visible);
                if (mask == null) {
                    continue;   // 整条都不可见：干脆不发
                }
                if (mask.indexOf('0') >= 0) {
                    road.addProperty("serverMask", mask);
                }
                out.add(road);
            }
        }

        JsonElement places = section(root, "Places", "places");
        if (places != null && places.isJsonArray()) {
            for (JsonElement item : places.getAsJsonArray()) {
                if (!item.isJsonObject()) {
                    continue;
                }
                JsonObject place = item.getAsJsonObject().deepCopy();
                String region = GsonHelper.getAsString(place, "region", "");
                if (!region.isEmpty() && !visible.contains(region)) {
                    continue;
                }
                // 关键：还要按**坐标**判一次（元素可能挂在父区域上，但实际落在未解锁的子区域里）
                double px = GsonHelper.getAsDouble(place, "x", 0.0D);
                double pz = GsonHelper.getAsDouble(place, "z", 0.0D);
                String innermost = innermostRegion(px, pz);
                if (innermost != null && !innermost.isEmpty() && !visible.contains(innermost)) {
                    continue;
                }
                place.addProperty("kind", "place");
                out.add(place);
            }
        }

        // 分批
        JsonArray batch = new JsonArray();
        int size = 2;
        for (JsonElement element : out) {
            int elementSize = element.toString().length();
            if (size + elementSize > BATCH_CHARS && batch.size() > 0) {
                batches.add(batch.toString());
                batch = new JsonArray();
                size = 2;
            }
            batch.add(element);
            size += elementSize + 1;
        }
        if (batch.size() > 0) {
            batches.add(batch.toString());
        }
        return batches;
    }

    private static JsonElement section(JsonObject root, String newKey, String legacyKey) {
        if (root.has(newKey) && root.get(newKey).isJsonObject()) {
            JsonObject section = root.getAsJsonObject(newKey);
            if (section.has("list")) {
                return section.get("list");
            }
        }
        return root.get(legacyKey);
    }

    /**
     * 逐段可见性掩码；整条都不可见返回 {@code null}（不下发）。
     */
    private static String segmentMask(JsonObject road, Set<String> visible) {
        JsonElement pointsElement = road.get("points");
        if (pointsElement == null || !pointsElement.isJsonArray()) {
            // 没有点集时退化为"按整条 region 判定"
            String region = GsonHelper.getAsString(road, "region", "");
            return region.isEmpty() || visible.contains(region) ? "1" : null;
        }
        JsonArray points = pointsElement.getAsJsonArray();
        if (points.size() < 2) {
            return null;
        }
        StringBuilder mask = new StringBuilder(points.size() - 1);
        int visibleSegments = 0;
        for (int i = 0; i + 1 < points.size(); i++) {
            double[] a = point(points.get(i));
            double[] b = point(points.get(i + 1));
            if (a == null || b == null) {
                mask.append('0');
                continue;
            }
            String region = innermostRegion((a[0] + b[0]) / 2.0D, (a[1] + b[1]) / 2.0D);
            boolean ok = region == null || region.isEmpty() || visible.contains(region);
            if (ok) {
                mask.append('1');
                visibleSegments++;
            } else {
                mask.append('0');
            }
        }
        return visibleSegments == 0 ? null : mask.toString();
    }

    private static double[] point(JsonElement element) {
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            return new double[]{GsonHelper.getAsDouble(object, "x", 0.0D),
                    GsonHelper.getAsDouble(object, "z", 0.0D)};
        }
        if (element.isJsonArray() && element.getAsJsonArray().size() >= 2) {
            return new double[]{element.getAsJsonArray().get(0).getAsDouble(),
                    element.getAsJsonArray().get(1).getAsDouble()};
        }
        return null;
    }

    /** 坐标处**最内层**区域 id（没有则 null）。 */
    public static String innermostRegion(double x, double z) {
        String best = null;
        int bestDepth = -1;
        for (AtlasRegionRegistry.Entry entry : AtlasRegionRegistry.entries()) {
            List<double[]> outline = entry.outline();
            if (outline.size() < 3 || !contains(outline, x, z)) {
                continue;
            }
            int depth = AtlasRegionRegistry.ancestors(entry.id()).size();
            if (depth > bestDepth) {
                bestDepth = depth;
                best = entry.id();
            }
        }
        return best;
    }

    private static boolean contains(List<double[]> outline, double x, double z) {
        boolean inside = false;
        for (int i = 0, j = outline.size() - 1; i < outline.size(); j = i++) {
            double xi = outline.get(i)[0];
            double zi = outline.get(i)[1];
            double xj = outline.get(j)[0];
            double zj = outline.get(j)[1];
            if ((zi > z) != (zj > z) && x < (xj - xi) * (z - zi) / (zj - zi) + xi) {
                inside = !inside;
            }
        }
        return inside;
    }

    /** 供日志/调试：某玩家可见的元素数量。 */
    public static Set<String> visibleRegionIds(ServerPlayer player) {
        return new LinkedHashSet<>(AtlasVisibilityService.visibleFor(player));
    }
}
