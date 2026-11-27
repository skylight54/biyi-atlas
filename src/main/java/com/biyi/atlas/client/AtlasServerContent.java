package com.biyi.atlas.client;

import com.biyi.atlas.map.AtlasContent;
import com.biyi.atlas.map.AtlasPlace;
import com.biyi.atlas.map.AtlasRegion;
import com.biyi.atlas.map.AtlasRoad;
import com.biyi.atlas.net.RegionGeometryMessage;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 服务端下发的**区域几何**（A6 第②步）。
 *
 * <p>收到后写进 {@link AtlasContent} 的**内存视图**（不写盘、不覆盖作者数据）：
 * 联机玩家即使没有作者的 {@code content.json}，也能看到区域边界/层级/名称。
 * 作者模式下这些几何仍与本地内容合并展示。</p>
 */
public final class AtlasServerContent {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 服务端来源的区域 id（不参与本地保存，避免把服务端下的数据写回作者文件）。 */
    private static final Set<String> SERVER_REGION_IDS = new LinkedHashSet<>();

    private AtlasServerContent() {
    }
    private static List<RegionGeometryMessage.Payload> pendingRegions;
    private record Pending(String json,boolean reset){}
    private static final List<Pending> pendingElements=new ArrayList<>();
    public static void flushPending(){
        if(pendingRegions!=null){var p=pendingRegions;pendingRegions=null;applyRegions(p);}
        var batches=new ArrayList<>(pendingElements);pendingElements.clear();
        for(var p:batches)applyElements(p.json(),p.reset());
    }

    public static void applyRegions(List<RegionGeometryMessage.Payload> payloads) {
        if(!AtlasAuthoring.known()){pendingRegions=List.copyOf(payloads);return;}
        List<AtlasRegion> built = new ArrayList<>(payloads.size());
        for (RegionGeometryMessage.Payload payload : payloads) {
            if (payload.outline().size() < 3) {
                continue;
            }
            built.add(new AtlasRegion(
                    payload.id(),
                    payload.name(),
                    java.util.Optional.ofNullable(ResourceLocation.tryParse(payload.dimension())).orElse(net.minecraft.world.level.Level.OVERWORLD.location()),
                    payload.layer(),
                    new ArrayList<>(payload.outline()),
                    payload.lineRgb(),
                    payload.fillRgb(),
                    payload.fillAlpha(),
                    payload.name(),
                    payload.labelX(),
                    payload.labelZ(),
                    payload.showOutlineWhenLocked(),
                    payload.unlockType(),
                    payload.unlockId(),
                    payload.defaultUnlocked(),
                    payload.image(),
                    new ArrayList<>(),
                    payload.parent(),
                    payload.mapArea(),
                    payload.childLocksContent()));
        }
        AtlasContent content = AtlasContent.get();
        if(AtlasAuthoring.allowed())content.removeServerRegions(new ArrayList<>(SERVER_REGION_IDS));
        else content.discardRemoteView(new ArrayList<>(SERVER_REGION_IDS),java.util.List.of(),java.util.List.of());
        SERVER_REGION_IDS.clear();
        int added = 0;
        for (AtlasRegion region : built) {
            if(!AtlasAuthoring.allowed()){
                content.acceptServerRegion(region);SERVER_REGION_IDS.add(region.id());added++;continue;
            }
            if (content.addServerRegion(region)) {
                SERVER_REGION_IDS.add(region.id());   // 只记录"确实由服务端加进来的"，本地已有的不动
                added++;
            }
        }
        LOGGER.info("[BiyiAtlas] 收到服务端区域几何：{} 个区域（新增 {}，其余本地已有则保留本地版本）",
                built.size(), added);
    }

    /** 服务端来源的道路 key → 逐段可见性掩码（1=可见、0=不可见）。 */
    private static final java.util.Map<String, String> ROAD_MASKS = new java.util.LinkedHashMap<>();
    private static final Set<String> SERVER_ROAD_KEYS = new LinkedHashSet<>();
    private static final Set<String> SERVER_PLACE_KEYS = new LinkedHashSet<>();

    /** 服务端是否允许这条路的这一段（服务端给了掩码就以此为准）。 */
    public static boolean maskAllows(String roadKey, int segment) {
        String mask = ROAD_MASKS.get(roadKey);
        if (mask == null || segment < 0 || segment >= mask.length()) {
            return true;   // 没有掩码：服务端只下发了可见内容，按可见处理
        }
        return mask.charAt(segment) == '1';
    }

    public static boolean hasMask(String roadKey) {
        return ROAD_MASKS.containsKey(roadKey);
    }

    /** 应用服务端下发的道路/地点（已按本玩家过滤；reset=true 时先清上一轮）。 */
    public static void applyElements(String json, boolean reset) {
        if(!AtlasAuthoring.known()){
            if(reset)pendingElements.clear();
            if(pendingElements.size()<256)pendingElements.add(new Pending(json,reset));return;
        }
        AtlasContent content = AtlasContent.get();
        if (reset) {
            if(!AtlasAuthoring.allowed())content.discardRemoteView(java.util.List.of(),new ArrayList<>(SERVER_ROAD_KEYS),new ArrayList<>(SERVER_PLACE_KEYS));
            content.clearServerElements(new ArrayList<>(SERVER_ROAD_KEYS), new ArrayList<>(SERVER_PLACE_KEYS));
            ROAD_MASKS.clear();
            SERVER_ROAD_KEYS.clear();
            SERVER_PLACE_KEYS.clear();
        }
        if (json == null || json.isEmpty()) {
            return;
        }
        com.google.gson.JsonArray array;
        try {
            array = com.google.gson.JsonParser.parseString(json).getAsJsonArray();
        } catch (Exception error) {
            LOGGER.warn("[BiyiAtlas] 解析服务端元素失败: {}", error.toString());
            return;
        }
        int roads = 0;
        int places = 0;
        for (com.google.gson.JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            com.google.gson.JsonObject object = element.getAsJsonObject();
            String kind = net.minecraft.util.GsonHelper.getAsString(object, "kind", "");
            if ("road".equals(kind)) {
                AtlasRoad road = AtlasRoad.fromJson(object);
                if (road == null) {
                    continue;
                }
                String mask = net.minecraft.util.GsonHelper.getAsString(object, "serverMask", "");
                if (!mask.isEmpty()) {
                    ROAD_MASKS.put(road.key(), mask);
                }
                SERVER_ROAD_KEYS.add(road.key());
                if(!AtlasAuthoring.allowed()){content.acceptServerRoad(road);roads++;continue;}
                if (content.addServerRoad(road)) {
                    roads++;
                }
            } else if ("place".equals(kind)) {
                AtlasPlace place = AtlasPlace.fromJson(object);
                if (place == null) {
                    continue;
                }
                SERVER_PLACE_KEYS.add(place.key());
                if(!AtlasAuthoring.allowed()){content.acceptServerPlace(place);places++;continue;}
                if (content.addServerPlace(place)) {
                    places++;
                }
            }
        }
        LOGGER.info("[BiyiAtlas] 收到服务端元素：新增 {} 条路（带段掩码 {}），{} 个地点",
                roads, ROAD_MASKS.size(), places);
    }

    public static void clear() {
        pendingRegions=null;pendingElements.clear();
        AtlasContent.get().removeServerRegions(SERVER_REGION_IDS);
        AtlasContent.get().clearServerElements(new ArrayList<>(SERVER_ROAD_KEYS),
                new ArrayList<>(SERVER_PLACE_KEYS));
        SERVER_REGION_IDS.clear();
        SERVER_ROAD_KEYS.clear();
        SERVER_PLACE_KEYS.clear();
        ROAD_MASKS.clear();
    }

    public static int size() {
        return SERVER_REGION_IDS.size();
    }
}
