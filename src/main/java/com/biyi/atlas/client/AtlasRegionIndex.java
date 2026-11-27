package com.biyi.atlas.client;

import com.biyi.atlas.map.AtlasContent;
import com.biyi.atlas.map.AtlasMapImage;
import com.biyi.atlas.map.AtlasPlace;
import com.biyi.atlas.map.AtlasRegion;
import com.biyi.atlas.map.AtlasRoad;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 区域 ↔ 要素 的**派生索引**（不落盘、不参与存档）：
 *
 * <ul>
 *   <li><b>逐段归属</b>：每段折线的中点落在哪个（最内层）区域 —— 取代过去"整条路只记一个 region、
 *       还按首点算"的四套口径；</li>
 *   <li><b>反向索引</b>：区域 → 引用它的道路段 / 地点 / 地点图，供删除事务、区域信息面板与校验器使用。</li>
 * </ul>
 *
 * <p><b>失效策略</b>：由 {@link AtlasContent#contentRevision()} 驱动，源一改就必须重建；
 * 不采用"每处写路径记得清缓存"的做法（漏一处就是隐蔽 bug）。</p>
 */
public final class AtlasRegionIndex {

    /** 一段折线的归属：所属道路、段号、区域 id、段中点。 */
    public record SegRef(AtlasRoad road, int segment, String regionId, double midX, double midZ) {
    }

    private static int builtRevision = Integer.MIN_VALUE;
    private static ResourceLocation builtDimension;
    private static int builtLayer = Integer.MIN_VALUE;

    private static final List<SegRef> SEGMENTS = new ArrayList<>();
    private static final Map<String, List<SegRef>> SEGMENTS_BY_REGION = new LinkedHashMap<>();
    private static final Map<String, List<AtlasPlace>> PLACES_BY_REGION = new LinkedHashMap<>();
    private static final Map<String, List<AtlasMapImage>> IMAGES_BY_REGION = new LinkedHashMap<>();
    /** 道路 key → 段归属（整条路查询用）。 */
    private static final Map<String, List<SegRef>> SEGMENTS_BY_ROAD = new LinkedHashMap<>();

    private AtlasRegionIndex() {
    }

    /** 确保索引与当前内容/维度/层一致（内部按修订号判失效）。 */
    public static void ensure(ResourceLocation dimension, int layer) {
        int revision = AtlasContent.get().contentRevision();
        if (revision == builtRevision && layer == builtLayer
                && (builtDimension == null ? dimension == null : builtDimension.equals(dimension))) {
            return;
        }
        rebuild(dimension, layer);
    }

    private static void rebuild(ResourceLocation dimension, int layer) {
        SEGMENTS.clear();
        SEGMENTS_BY_REGION.clear();
        PLACES_BY_REGION.clear();
        IMAGES_BY_REGION.clear();
        SEGMENTS_BY_ROAD.clear();
        AtlasContent content = AtlasContent.get();

        for (AtlasRoad road : content.roadsAt(dimension, layer)) {
            List<double[]> points = road.points();
            List<SegRef> refs = new ArrayList<>();
            for (int segment = 0; segment + 1 < points.size(); segment++) {
                double[] a = points.get(segment);
                double[] b = points.get(segment + 1);
                double midX = (a[0] + b[0]) / 2.0D;
                double midZ = (a[1] + b[1]) / 2.0D;
                AtlasRegion region = content.regionInnermostAt(dimension, layer, midX, midZ);
                String regionId = region == null ? "" : region.id();
                SegRef ref = new SegRef(road, segment, regionId, midX, midZ);
                refs.add(ref);
                SEGMENTS.add(ref);
                if (!regionId.isEmpty()) {
                    SEGMENTS_BY_REGION.computeIfAbsent(regionId, key -> new ArrayList<>()).add(ref);
                }
            }
            SEGMENTS_BY_ROAD.put(road.key(), refs);
        }

        for (AtlasPlace place : content.placesAt(dimension, layer)) {
            if (place.region() != null && !place.region().isEmpty()) {
                PLACES_BY_REGION.computeIfAbsent(place.region(), key -> new ArrayList<>()).add(place);
            }
        }
        for (AtlasMapImage image : content.mapsAt(dimension, layer)) {
            if (image.region() != null && !image.region().isEmpty()) {
                IMAGES_BY_REGION.computeIfAbsent(image.region(), key -> new ArrayList<>()).add(image);
            }
        }

        builtRevision = content.contentRevision();
        builtDimension = dimension;
        builtLayer = layer;
    }

    /** 某一段折线的区域归属（空串 = 不在任何区域里）。 */
    public static String regionOf(AtlasRoad road, int segment) {
        List<SegRef> refs = SEGMENTS_BY_ROAD.get(road.key());
        if (refs == null || segment < 0 || segment >= refs.size()) {
            return "";
        }
        return refs.get(segment).regionId();
    }

    /** 某段折线中点坐标（{x,z}）；越界返回 null。 */
    public static double[] midpointOf(AtlasRoad road, int segment) {
        List<SegRef> refs = SEGMENTS_BY_ROAD.get(road.key());
        if (refs == null || segment < 0 || segment >= refs.size()) {
            return null;
        }
        SegRef ref = refs.get(segment);
        return new double[]{ref.midX(), ref.midZ()};
    }

    /** 这一层里引用该区域的所有道路段（反向索引）。 */
    public static List<SegRef> segmentsIn(String regionId) {
        return SEGMENTS_BY_REGION.getOrDefault(regionId, List.of());
    }

    public static List<AtlasPlace> placesIn(String regionId) {
        return PLACES_BY_REGION.getOrDefault(regionId, List.of());
    }

    public static List<AtlasMapImage> imagesIn(String regionId) {
        return IMAGES_BY_REGION.getOrDefault(regionId, List.of());
    }

    /** 该层总段数（体检/统计用）。 */
    public static int segmentCount() {
        return SEGMENTS.size();
    }

    /** 自检：反向索引与逐段归属是否一致（校验器 R07 用）。 */
    public static List<String> audit() {
        List<String> issues = new ArrayList<>();
        for (Map.Entry<String, List<SegRef>> entry : SEGMENTS_BY_REGION.entrySet()) {
            for (SegRef ref : entry.getValue()) {
                if (!entry.getKey().equals(ref.regionId())) {
                    issues.add("反向索引与段归属不一致：" + ref.road().name() + " 段 " + ref.segment());
                }
            }
        }
        return issues;
    }
}
