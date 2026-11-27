package com.biyi.atlas.map;

import com.biyi.atlas.BiyiAtlas;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.GsonHelper;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 地图内容（区域 + 地点 + 道路）的合并视图，三层来源后者覆盖前者：
 * <ol>
 *     <li>模组内置 {@code assets/biyi_atlas/atlas/{regions,places,roads}.json}</li>
 *     <li>整合包配置 {@code config/biyi_atlas/{regions,places,roads}.json}</li>
 *     <li>游戏内用地图魔杖编辑的内容 {@code <gameDir>/biyi_atlas/authored/<世界>/content.json}</li>
 * </ol>
 */
public final class AtlasContent {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtlasContent INSTANCE = new AtlasContent();

    private static final ResourceLocation BUILTIN_REGIONS =
            new ResourceLocation(BiyiAtlas.MODID, "atlas/regions.json");
    private static final ResourceLocation BUILTIN_PLACES =
            new ResourceLocation(BiyiAtlas.MODID, "atlas/places.json");
    private static final ResourceLocation BUILTIN_ROADS =
            new ResourceLocation(BiyiAtlas.MODID, "atlas/roads.json");
    private static final ResourceLocation BUILTIN_MAPS =
            new ResourceLocation(BiyiAtlas.MODID, "atlas/maps.json");

    /** 合并后的内容（只读视图）。 */
    private final Map<String, AtlasRegion> regions = new LinkedHashMap<>();
    private final Map<String, AtlasPlace> places = new LinkedHashMap<>();
    private final List<AtlasRoad> roads = new ArrayList<>();
    /** 作者手绘的地图图层（羊皮纸手绘图）。 */
    private final List<AtlasMapImage> maps = new ArrayList<>();

    /** 游戏内编辑出来的内容，会被写回磁盘。 */
    private final Map<String, AtlasRegion> authoredRegions = new LinkedHashMap<>();
    private final Map<String, AtlasPlace> authoredPlaces = new LinkedHashMap<>();
    private final List<AtlasRoad> authoredRoads = new ArrayList<>();
    private final List<AtlasMapImage> authoredMaps = new ArrayList<>();

    private String worldKey = "unknown";
    private ResourceManager lastResourceManager;
    private boolean dirty;
    /** 内容修订号（派生缓存用）。 */
    private int contentRevision;
    /** 三层版本号（读盘时填充；0 = 旧扁平数据）。 */
    private int loadedDataVersion;
    private int loadedRegionsSchema;
    private int loadedRoadsSchema;
    /** 只读保护：存档版本比程序新，或检测到迁移中断。 */
    private boolean readOnly;
    /** 写保护（非作者客户端：不把改动写回本地内容文件，A6 第③步）。 */
    private boolean writeProtected;

    public void setWriteProtected(boolean value) {
        this.writeProtected = value;
        LOGGER.info("[BiyiAtlas] 本地内容写路径{}", value ? "已关闭（非作者客户端）" : "已开启");
    }

    public static void setInstanceWriteProtected(boolean value) {
        get().setWriteProtected(value);
    }

    public boolean isWriteProtected() {
        return writeProtected;
    }

    /** 文件里原始的 regions schema（迁移前），用于备份文件命名 .bak.v<n>。 */
    private int fileRegionsSchemaAtLoad = -1;
    /**
     * 墓碑：被作者删掉的 id。内置/config 里定义的区域即使从内存删掉，
     * 下次 reload 仍会从资源包读回来（D-02"删了又复活"）——墓碑就是用来压掉它们的。
     */
    private final java.util.Set<String> removedRegions = new java.util.LinkedHashSet<>();
    private final java.util.Set<String> removedPlaces = new java.util.LinkedHashSet<>();
    private final java.util.Set<String> removedRoads = new java.util.LinkedHashSet<>();
    private final java.util.Set<String> removedMaps = new java.util.LinkedHashSet<>();

    private boolean projectSnapshot;
    private AtlasContent() {
    }

    public static AtlasContent get() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------ 查询

    public List<AtlasRegion> regions() {
        return new ArrayList<>(regions.values());
    }

    /** 全部地点（跨维度/层，校验器与统计用）。 */
    public List<AtlasPlace> places() {
        return new ArrayList<>(places.values());
    }

    /** 全部道路（跨维度/层，校验器与统计用）。 */
    public List<AtlasRoad> roads() {
        return new ArrayList<>(roads);
    }

    /** 全部地点图（跨维度/层，校验器与统计用）。 */
    public List<AtlasMapImage> maps() {
        return new ArrayList<>(maps);
    }

    public AtlasRegion regionById(String id) {
        return id == null ? null : regions.get(id);
    }

    /** 某个世界坐标落在哪个区域里（同一层优先）。 */
    public AtlasRegion regionAt(ResourceLocation dimension, int layer, double x, double z) {
        for (AtlasRegion region : regions.values()) {
            if (region.dimension().equals(dimension) && region.layer() == layer && region.contains(x, z)) {
                return region;
            }
        }
        return null;
    }

    /** 最内层命中的区域：嵌套时返回子区域而不是父区域（面积更小者优先）。 */
    public AtlasRegion regionInnermostAt(ResourceLocation dimension, int layer, double x, double z) {
        AtlasRegion best = null;
        for (AtlasRegion region : regions.values()) {
            if (!region.dimension().equals(dimension) || region.layer() != layer || !region.contains(x, z)) {
                continue;
            }
            if (best == null || region.area() < best.area()) {
                best = region;
            }
        }
        return best;
    }

    /** 直接子区域。 */
    public List<AtlasRegion> childrenOf(String parentId) {
        List<AtlasRegion> result = new ArrayList<>();
        if (parentId == null || parentId.isEmpty()) {
            return result;
        }
        for (AtlasRegion region : regions.values()) {
            if (parentId.equals(region.parent())) {
                result.add(region);
            }
        }
        return result;
    }

    /** 所有后代（递归，带访问集合防环）。 */
    public List<AtlasRegion> descendantsOf(String parentId) {
        List<AtlasRegion> result = new ArrayList<>();
        collectDescendants(parentId, result, new java.util.HashSet<>(), 0);
        return result;
    }

    private void collectDescendants(String parentId, List<AtlasRegion> out, java.util.Set<String> visited,
                                    int depth) {
        if (depth > 8 || parentId == null || parentId.isEmpty() || !visited.add(parentId)) {
            return;
        }
        for (AtlasRegion child : childrenOf(parentId)) {
            out.add(child);
            collectDescendants(child.id(), out, visited, depth + 1);
        }
    }

    /** 顶层"地图范围"区域（父为空且 mapArea）。 */
    public List<AtlasRegion> mapAreas(ResourceLocation dimension) {
        List<AtlasRegion> result = new ArrayList<>();
        for (AtlasRegion region : regions.values()) {
            if (region.dimension().equals(dimension) && region.mapArea() && !region.isChild()) {
                result.add(region);
            }
        }
        return result;
    }

    /** 把 candidate 设为 parentId 的子区域是否会成环（parentId 是 candidate 的后代）。 */
    public boolean wouldCreateCycle(String candidateId, String parentId) {
        if (parentId == null || parentId.isEmpty() || candidateId == null) {
            return false;
        }
        java.util.Set<String> visited = new java.util.HashSet<>();
        String current = parentId;
        int depth = 0;
        while (current != null && !current.isEmpty() && depth++ < 16 && visited.add(current)) {
            if (current.equals(candidateId)) {
                return true;
            }
            AtlasRegion region = regions.get(current);
            current = region == null ? null : region.parent();
        }
        return false;
    }

    /**
     * 同层重叠检测：candidate 与"同父级/同层"的其它区域若多边形有交，返回与之冲突的区域。
     * 用包围盒快速排除 + 顶点包含判定（够用且可解释；不追求精确多边形求交）。
     */
    public AtlasRegion overlapsSibling(AtlasRegion candidate) {
        double[] a = candidate.bounds();
        for (AtlasRegion other : regions.values()) {
            if (other.id().equals(candidate.id())
                    || !other.dimension().equals(candidate.dimension())
                    || other.layer() != candidate.layer()) {
                continue;
            }
            String otherParent = other.parent() == null ? "" : other.parent();
            String candidateParent = candidate.parent() == null ? "" : candidate.parent();
            if (!otherParent.equals(candidateParent)) {
                continue;
            }
            double[] b = other.bounds();
            if (a[2] < b[0] || b[2] < a[0] || a[3] < b[1] || b[3] < a[1]) {
                continue;
            }
            // 包围盒相交 → 再判顶点包含（任一方向）
            if (containsAny(other, candidate) || containsAny(candidate, other)) {
                return other;
            }
        }
        return null;
    }

    private boolean containsAny(AtlasRegion host, AtlasRegion guest) {
        for (double[] point : guest.outline()) {
            if (host.contains(point[0], point[1])) {
                return true;
            }
        }
        return false;
    }

    /** 生成不冲突的区域 id（P0-1：绝不复用已存在的 id）。 */
    public String nextRegionId(ResourceLocation dimension, int layer) {
        // 永不复用：把现存 id 与墓碑里的 id 一起算"用过的最大编号"
        int max = 0;
        for (String id : regions.keySet()) {
            max = Math.max(max, numericSuffix(id));
        }
        for (String id : removedRegions) {
            max = Math.max(max, numericSuffix(id));
        }
        return "region_" + (max + 1);
    }

    /** 取 id 末尾的数字部分（{@code region_12 → 12}，没有则 0）。 */
    private static int numericSuffix(String id) {
        if (id == null) {
            return 0;
        }
        int underscore = id.lastIndexOf('_');
        if (underscore < 0 || underscore + 1 >= id.length()) {
            return 0;
        }
        try {
            return Integer.parseInt(id.substring(underscore + 1));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    public List<AtlasRegion> regionsAt(ResourceLocation dimension, int layer) {
        List<AtlasRegion> result = new ArrayList<>();
        for (AtlasRegion region : regions.values()) {
            if (region.dimension().equals(dimension) && region.layer() == layer) {
                result.add(region);
            }
        }
        return result;
    }

    public List<AtlasPlace> placesAt(ResourceLocation dimension) {
        List<AtlasPlace> result = new ArrayList<>();
        for (AtlasPlace place : places.values()) {
            if (place.dimension().equals(dimension)) {
                result.add(place);
            }
        }
        return result;
    }

    public List<AtlasPlace> placesAt(ResourceLocation dimension, int layer) {
        List<AtlasPlace> result = new ArrayList<>();
        for (AtlasPlace place : places.values()) {
            if (place.dimension().equals(dimension) && place.layer() == layer) {
                result.add(place);
            }
        }
        return result;
    }

    public List<AtlasRoad> roadsAt(ResourceLocation dimension) {
        List<AtlasRoad> result = new ArrayList<>();
        for (AtlasRoad road : roads) {
            if (road.dimension().equals(dimension)) {
                result.add(road);
            }
        }
        return result;
    }

    public List<AtlasRoad> roadsAt(ResourceLocation dimension, int layer) {
        List<AtlasRoad> result = new ArrayList<>();
        for (AtlasRoad road : roads) {
            if (road.dimension().equals(dimension) && road.layer() == layer) {
                result.add(road);
            }
        }
        return result;
    }

    public List<AtlasMapImage> mapsAt(ResourceLocation dimension, int layer) {
        List<AtlasMapImage> result = new ArrayList<>();
        for (AtlasMapImage image : maps) {
            if (image.dimension().equals(dimension) && image.layer() == layer) {
                result.add(image);
            }
        }
        return result;
    }

    public int authoredCount() {
        return authoredRegions.size() + authoredPlaces.size() + authoredRoads.size();
    }

    // ------------------------------------------------------------------ 修改

    public void addRegion(AtlasRegion region) {
        AtlasRegion existing = regions.get(region.id());
        if (existing != null && !existing.equals(region)) {
            // P0-1：绝不用新区域静默覆盖旧区域
            LOGGER.warn("[BiyiAtlas] 区域 id 冲突，拒绝覆盖：{}（已有区域「{}」）",
                    region.id(), existing.name());
            return;
        }
        removedRegions.remove(region.id());   // 重新创建同 id → 撤销墓碑
        regions.put(region.id(), region);
        authoredRegions.put(region.id(), region);
        markDirty();
    }

    public boolean removeRegion(AtlasRegion region) {
        if (regions.remove(region.id()) == null) {
            return false;
        }
        authoredRegions.remove(region.id());
        removedRegions.add(region.id());     // 墓碑：压掉内置/config 里的同名定义
        markDirty();
        return true;
    }

    /**
     * 删除区域（含级联与引用清理）——删除语义集中在这里，避免各处自己拼：
     * <ol>
     *   <li>把引用它的地点/道路/地点图的 region 置空（不 fail-open、不留悬空）；</li>
     *   <li>写下墓碑，保证 reload 后内置/config 的同名区域不会"复活"；</li>
     *   <li>返回受影响的要素数量，供界面提示。</li>
     * </ol>
     */
    public int deleteRegionCascade(String regionId) {
        if (regionId == null || regionId.isEmpty()) {
            return 0;
        }
        beginTransaction();
        try {
            int affected = 0;
            for (AtlasPlace place : new ArrayList<>(places.values())) {
                if (regionId.equals(place.region())) {
                    AtlasPlace updated = place.withRegion("");
                    places.put(updated.key(), updated);
                    authoredPlaces.remove(place.key());
                    authoredPlaces.put(updated.key(), updated);
                    affected++;
                }
            }
            for (AtlasRoad road : new ArrayList<>(roads)) {
                if (regionId.equals(road.region())) {
                    replaceRoad(road, road.withRegion(""));
                    affected++;
                }
            }
            for (AtlasMapImage image : new ArrayList<>(maps)) {
                if (regionId.equals(image.region())) {
                    AtlasMapImage updated = image.withRegion("");
                    maps.replaceAll(existing -> existing.id().equals(updated.id()) ? updated : existing);
                    authoredMaps.replaceAll(existing -> existing.id().equals(updated.id()) ? updated : existing);
                    affected++;
                }
            }
            AtlasRegion region = regions.get(regionId);
            if (region != null) {
                removeRegion(region);            // 写墓碑 + 从内容与作者集合移除（否则删了会复活）
            } else {
                removedRegions.add(regionId);    // 内置/配置来源：也要写墓碑
                markDirty();
            }
            commitTransaction();
            return affected;
        } catch (RuntimeException | Error error) {
            rollbackTransaction();
            throw error;
        }
    }

    /** 元素面数量统计（区域信息面板/日志用）。 */
    public int elementCountIn(String regionId) {
        int count = 0;
        for (AtlasPlace place : places.values()) {
            if (regionId.equals(place.region())) {
                count++;
            }
        }
        for (AtlasRoad road : roads) {
            if (regionId.equals(road.region())) {
                count++;
            }
        }
        for (AtlasMapImage image : maps) {
            if (regionId.equals(image.region())) {
                count++;
            }
        }
        return count;
    }

    public void addPlace(AtlasPlace place) {
        removedPlaces.remove(place.key());
        places.put(place.key(), place);
        authoredPlaces.put(place.key(), place);
        markDirty();
    }

    public boolean removePlace(AtlasPlace place) {
        if (places.remove(place.key()) == null) {
            return false;
        }
        authoredPlaces.remove(place.key());
        removedPlaces.add(place.key());
        markDirty();
        return true;
    }

    /** 新增/替换一个区域（图鉴、映像图等改动走这里）。 */
    public void replaceRegion(AtlasRegion region) {
        regions.put(region.id(), region);
        authoredRegions.put(region.id(), region);
        markDirty();
    }

    /** 按 key 取地点（编辑器用）。 */
    public AtlasPlace placeByKey(String key) {
        return places.get(key);
    }

    /** 某条线某个节点的名字（支线共享节点时复用父节点的名字）。 */
    public String nodeNameOf(String roadKey, String nodeId) {
        AtlasRoad road = roadByKey(roadKey);
        if (road == null) {
            return "岔口";
        }
        AtlasRoadNode node = road.nodeById(nodeId);
        return node == null ? "岔口" : node.name();
    }

    /** 半径内最近的已有节点（记录终点吸附用 → 让环路/接驳自动闭合）。 */
    public AtlasRoadNode nodeNear(ResourceLocation dimension, int layer, double x, double z, double radius) {
        AtlasRoadNode best = null;
        double bestDistance = radius;
        for (AtlasRoad road : roads) {
            if (!road.dimension().equals(dimension) || road.layer() != layer) {
                continue;
            }
            for (AtlasRoadNode node : road.nodes()) {
                double[] point = nodePointOf(road, node);
                if (point == null) {
                    continue;
                }
                double distance = Math.hypot(point[0] - x, point[1] - z);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = node;
                }
            }
        }
        return best;
    }

    /** 节点坐标（不存在返回 null）。 */
    public double[] nodePositionOf(ResourceLocation dimension, int layer, String nodeId) {
        for (AtlasRoad road : roads) {
            if (!road.dimension().equals(dimension) || road.layer() != layer) {
                continue;
            }
            AtlasRoadNode node = road.nodeById(nodeId);
            double[] point = nodePointOf(road, node);
            if (point != null) {
                return point;
            }
        }
        return null;
    }

    private double[] nodePointOf(AtlasRoad road, AtlasRoadNode node) {
        if (road == null || node == null || node.index() < 0 || node.index() >= road.points().size()) {
            return null;
        }
        return road.points().get(node.index());
    }

    /** 所有节点（含所属线），用于建图/体检。 */
    public List<AtlasRoadNode> allNodes(ResourceLocation dimension, int layer) {
        List<AtlasRoadNode> result = new ArrayList<>();
        for (AtlasRoad road : roads) {
            if (road.dimension().equals(dimension) && road.layer() == layer) {
                result.addAll(road.nodes());
            }
        }
        return result;
    }

    /**
     * 道路体检：把路网上"看着还行、走不通"的问题列出来（供作者一键检查）。
     *
     * <p>检查项：顶点不足 / 零长段 / 重复顶点 / 节点下标越界 / 支线父线或父节点丢失 /
     * 端点可缝合（两条线端点很近但没有共享节点）/ 重复 key。</p>
     */
    public List<String> checkRoads(ResourceLocation dimension, int layer) {
        List<String> issues = new ArrayList<>();
        java.util.Set<String> keys = new java.util.HashSet<>();
        List<AtlasRoad> scoped = new ArrayList<>();
        for (AtlasRoad road : roads) {
            if (road.dimension().equals(dimension) && road.layer() == layer) {
                scoped.add(road);
            }
        }
        for (AtlasRoad road : scoped) {
            if (!keys.add(road.key())) {
                issues.add("线路 id 重复：" + road.key());
            }
            List<double[]> points = road.points();
            if (points.size() < 2) {
                issues.add("「" + road.name() + "」顶点不足 2 个");
            }
            for (int i = 0; i + 1 < points.size(); i++) {
                double distance = Math.hypot(points.get(i + 1)[0] - points.get(i)[0],
                        points.get(i + 1)[1] - points.get(i)[1]);
                if (distance < 0.25D) {
                    issues.add("「" + road.name() + "」第 " + (i + 1) + " 段是零长段");
                }
            }
            for (AtlasRoadNode node : road.nodes()) {
                if (node.index() < 0 || node.index() >= points.size()) {
                    issues.add("「" + road.name() + "」节点「" + node.name() + "」下标越界");
                }
            }
            if (!road.branchOf().isEmpty()) {
                String[] parts = road.branchOf().split("#");
                AtlasRoad parent = parts.length > 0 ? roadByKey(parts[0]) : null;
                if (parent == null) {
                    issues.add("支线「" + road.name() + "」找不到父线：" + road.branchOf());
                } else if (parts.length > 1 && parent.nodeById(parts[1]) == null) {
                    issues.add("支线「" + road.name() + "」的父节点已丢失：" + parts[1]);
                }
            }
        }
        // 端点可缝合：不同线上两个端点/节点距离很近（< 2 格）但 id 不同
        int stitched = 0;
        for (int i = 0; i < scoped.size(); i++) {
            for (int j = i + 1; j < scoped.size(); j++) {
                AtlasRoad a = scoped.get(i);
                AtlasRoad b = scoped.get(j);
                for (AtlasRoadNode na : a.nodes()) {
                    double[] pa = nodePointOf(a, na);
                    if (pa == null) {
                        continue;
                    }
                    for (AtlasRoadNode nb : b.nodes()) {
                        double[] pb = nodePointOf(b, nb);
                        if (pb == null || na.id().equals(nb.id())) {
                            continue;
                        }
                        if (Math.hypot(pa[0] - pb[0], pa[1] - pb[1]) < 2.0D && stitched < 5) {
                            stitched++;
                            issues.add("可缝合：「" + a.name() + "·" + na.name() + "」与「"
                                    + b.name() + "·" + nb.name() + "」相距 <2 格");
                        }
                    }
                }
            }
        }
        return issues;
    }

    /** 按 key 取道路（编辑器用）。 */
    public AtlasRoad roadByKeyPublic(String key) {
        return roadByKey(key);
    }

    /**
     * 缝合端点：把相距 ≤ radius 的两个"不同 id 节点"合并成同一个节点（两条线从此共享该节点）。
     * 返回合并次数。这是把"看着连着但走不通"的路接起来的快捷修复。
     */
    public int stitchCloseNodes(ResourceLocation dimension, int layer, double radius) {
        List<AtlasRoad> scoped = new ArrayList<>();
        for (AtlasRoad road : roads) {
            if (road.dimension().equals(dimension) && road.layer() == layer) {
                scoped.add(road);
            }
        }
        beginTransaction();
        int merged = 0;
        boolean changed = true;
        try {
        while (changed && merged < 200) {
            changed = false;
            outer:
            for (int i = 0; i < scoped.size(); i++) {
                AtlasRoad a = roadByKey(scoped.get(i).key());
                if (a == null) {
                    continue;
                }
                for (AtlasRoadNode na : new ArrayList<>(a.nodes())) {
                    double[] pa = nodePointOf(a, na);
                    if (pa == null) {
                        continue;
                    }
                    for (int j = 0; j < scoped.size(); j++) {
                        AtlasRoad b = roadByKey(scoped.get(j).key());
                        if (b == null) {
                            continue;
                        }
                        for (AtlasRoadNode nb : new ArrayList<>(b.nodes())) {
                            if (na.id().equals(nb.id())) {
                                continue;
                            }
                            double[] pb = nodePointOf(b, nb);
                            if (pb == null || Math.hypot(pa[0] - pb[0], pa[1] - pb[1]) > radius) {
                                continue;
                            }
                            // 把 b 里所有引用 nb.id 的节点改成 na.id，并把坐标对齐
                            List<AtlasRoadNode> nodes = new ArrayList<>();
                            int index = -1;
                            for (AtlasRoadNode node : b.nodes()) {
                                if (node.id().equals(nb.id())) {
                                    nodes.add(new AtlasRoadNode(na.id(), node.index(), na.name(), node.kind()));
                                    index = node.index();
                                } else {
                                    nodes.add(node);
                                }
                            }
                            List<double[]> points = new ArrayList<>(b.points());
                            if (index >= 0 && index < points.size()) {
                                points.set(index, new double[]{pa[0], pa[1]});
                            }
                            replaceRoad(b, b.withPoints(points, nodes));
                            // 被替换掉的节点 id 若被支线引用，同步改写（否则支线引用悬空）
                            rewriteBranchReferences(b.key(), nb.id(), na.id());
                            merged++;
                            changed = true;
                            break outer;
                        }
                    }
                }
            }
        }
        return merged;
        } catch (RuntimeException | Error error) {
            rollbackTransaction();
            throw error;
        }
    }

    /** 按 key 取道路（编辑器用）。 */
    public AtlasRoad roadByKey(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        for (AtlasRoad road : roads) {
            if (road.key().equals(key) || road.name().equals(key)) {
                return road;
            }
        }
        return null;
    }

    /** 替换一条道路（按 key 匹配，支线/节点更新走这里）。 */
    public boolean replaceRoad(AtlasRoad oldRoad, AtlasRoad newRoad) {
        int index = roads.indexOf(oldRoad);
        if (index < 0) {
            index = roads.indexOf(roadByKey(oldRoad.key()));
            if (index < 0) {
                return false;
            }
        }
        roads.set(index, newRoad);
        int authoredIndex = authoredRoads.indexOf(oldRoad);
        if (authoredIndex >= 0) {
            authoredRoads.set(authoredIndex, newRoad);
        } else {
            authoredRoads.removeIf(road -> road.key().equals(oldRoad.key()));
        }
        if (!authoredRoads.contains(newRoad)) {
            authoredRoads.add(newRoad);
        }
        markDirty();
        return true;
    }

    /** 按名字取道路（编辑器用）。 */
    public AtlasRoad roadByName(String name) {
        for (AtlasRoad road : roads) {
            if (road.name().equals(name)) {
                return road;
            }
        }
        return null;
    }

    public void addRoad(AtlasRoad road) {
        removedRoads.remove(road.key());
        roads.add(road);
        authoredRoads.add(road);
        markDirty();
    }

    /** 新增/替换一张地点图（"大型建筑俯视图"作为地图元素）。 */
    public void addMapImage(AtlasMapImage image) {
        removedMaps.remove(image.id());
        maps.removeIf(existing -> existing.id().equals(image.id()));
        maps.add(image);
        authoredMaps.removeIf(existing -> existing.id().equals(image.id()));
        authoredMaps.add(image);
        markDirty();
    }

    public boolean removeMapImage(AtlasMapImage image) {
        if (!maps.remove(image)) {
            return false;
        }
        authoredMaps.remove(image);
        removedMaps.add(image.id());     // 墓碑：压掉内置/config 里的同名图
        markDirty();
        return true;
    }

    public boolean removeRoad(AtlasRoad road) {
        if (road == null) {
            return false;
        }
        // 按 key 删除**所有**副本（历史问题：同名/同 id 的副本会让已删除的路"复活"）
        boolean removed = roads.removeIf(existing -> existing.key().equals(road.key()));
        authoredRoads.removeIf(existing -> existing.key().equals(road.key()));
        if (removed) {
            removedRoads.add(road.key());
            markDirty();
        }
        return removed;
    }

    /** 生成一个未被占用的道路 id（扫描全部已有 key，避免重名导致"编辑到另一条路"）。 */
    public String nextRoadId(ResourceLocation dimension, int layer) {
        int max = 0;
        for (AtlasRoad road : roads) {
            max = Math.max(max, numericSuffix(road.key()));
        }
        for (String id : removedRoads) {
            max = Math.max(max, numericSuffix(id));
        }
        for (int probe = max + 1; probe < 99999; probe++) {
            String candidate = "road_" + probe;
            boolean taken = false;
            for (AtlasRoad road : roads) {
                if (road.key().equals(candidate)) {
                    taken = true;
                    break;
                }
            }
            if (!taken) {
                return candidate;
            }
        }
        return "road_" + Long.toString(System.nanoTime(), 36);
    }

    /** 导出当前作者内容（与落盘格式一致）用于上传给服务端。 */
    public byte[] exportAuthoredBytes() {
        var root=toJson(regions, places, roads, maps);root.addProperty("ProjectSnapshot",true);
        return root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public void installProjectJson(String json){
        JsonObject root=com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        root.addProperty("ProjectSnapshot",true);
        try{new EditHistory(authoredFile(worldKey)).write(root.toString().getBytes(StandardCharsets.UTF_8));
            reload(net.minecraft.client.Minecraft.getInstance().getResourceManager(),worldKey);
        }catch(Exception e){throw new IllegalStateException("工程内容安装失败",e);}
    }

    /** 区域映像图的"缩放/偏移"调整（regionId → {scale, offsetX, offsetZ}）。 */
    private final Map<String, double[]> imageTransforms = new LinkedHashMap<>();

    /** 取映像图变换；没有则 null。 */
    public double[] imageTransform(String regionId) {
        return imageTransforms.get(regionId);
    }

    /** 设置映像图变换（scale 建议 0.2~4；偏移单位=方块）。 */
    public void setImageTransform(String regionId, double scale, double offsetX, double offsetZ) {
        if (regionId == null || regionId.isEmpty()) {
            return;
        }
        imageTransforms.put(regionId,
                new double[]{Math.max(0.2D, Math.min(4.0D, scale)), offsetX, offsetZ});
        markDirty();
    }

    public void clearImageTransform(String regionId) {
        if (imageTransforms.remove(regionId) != null) {
            markDirty();
        }
    }

    /**
     * 加入一个**服务端下发**的区域几何（只进内存视图，不写盘、不覆盖本地内容）。
     *
     * <p>关键：**本地已有同 id 区域时直接跳过**——服务端下发的是"够画边界"的精简版
     * （不含 image / 图鉴），覆盖会把作者本地的映像图与图鉴抹掉（曾经的 bug）。</p>
     */
    public boolean addServerRegion(AtlasRegion region) {
        if (region == null || removedRegions.contains(region.id()) || regions.containsKey(region.id())) {
            return false;
        }
        regions.put(region.id(), region);
        markDirty();
        return true;
    }
    public void acceptServerRegion(AtlasRegion region){regions.put(region.id(),region);markDirty();}
    public void discardRemoteView(java.util.Collection<String> regionIds,java.util.Collection<String> roadIds,java.util.Collection<String> placeIds){
        regionIds.forEach(regions::remove);roads.removeIf(r->roadIds.contains(r.key()));placeIds.forEach(places::remove);markDirty();
    }
    public void acceptServerRoad(AtlasRoad road){roads.removeIf(r->r.key().equals(road.key()));roads.add(road);markDirty();}
    public void acceptServerPlace(AtlasPlace place){places.put(place.key(),place);markDirty();}

    /**
     * 加入一条**服务端下发**的道路（只进内存视图）。
     * 若本地已有同 key 的路（作者/资源包同款），不重复添加——由服务端掩码约束本地那条的可见段。
     */
    public boolean addServerRoad(AtlasRoad road) {
        if (road == null || removedRoads.contains(road.key()) || roadByKey(road.key()) != null) {
            return false;
        }
        roads.add(road);
        markDirty();
        return true;
    }

    /** 加入一个**服务端下发**的地点（只进内存视图，本地同 key 则不重复）。 */
    public boolean addServerPlace(AtlasPlace place) {
        if (place == null || removedPlaces.contains(place.key()) || places.containsKey(place.key())) {
            return false;
        }
        places.put(place.key(), place);
        markDirty();
        return true;
    }

    /** 清掉服务端来源的道路/地点（断线或重新同步时）。 */
    public void clearServerElements(java.util.Collection<String> roadKeys,
                                    java.util.Collection<String> placeKeys) {
        for (String key : roadKeys) {
            boolean local = false;
            for (AtlasRoad road : authoredRoads) {
                if (road.key().equals(key)) {
                    local = true;
                    break;
                }
            }
            if (!local) {
                roads.removeIf(road -> road.key().equals(key));
            }
        }
        for (String key : placeKeys) {
            if (!authoredPlaces.containsKey(key)) {
                places.remove(key);
            }
        }
        markDirty();
    }

    /** 移除服务端来源的区域（断线/换服时调用）。 */
    public void removeServerRegions(java.util.Collection<String> ids) {
        for (String id : ids) {
            if (authoredRegions.containsKey(id)) {
                continue;   // 本地也有同 id：保留本地那份
            }
            regions.remove(id);
        }
        markDirty();
    }

    /** 批量设置"锁定即隐藏内容"（分层锁）。返回改动的区域数。 */
    public int setChildLocksContent(boolean value) {
        int changed = 0;
        for (AtlasRegion region : new ArrayList<>(regions.values())) {
            if (region.childLocksContent() == value) {
                continue;
            }
            AtlasRegion updated = region.withOwner(region.parent(), region.mapArea(), value);
            regions.put(updated.id(), updated);
            if (authoredRegions.containsKey(updated.id())) {
                authoredRegions.put(updated.id(), updated);
            }
            changed++;
        }
        if (changed > 0) {
            markDirty();
        }
        return changed;
    }

    /** 是否所有区域都开启了"锁定即隐藏内容"。 */
    public boolean allChildLocksContent() {
        for (AtlasRegion region : regions.values()) {
            if (!region.childLocksContent()) {
                return false;
            }
        }
        return true;
    }

    /** 清理指向"某条线的某个节点"的支线引用（删节点后用）。返回受影响的支线数。 */
    public int clearBranchReferences(String roadKey, String nodeId) {
        int affected = 0;
        for (AtlasRoad road : new ArrayList<>(roads)) {
            if (road.branchOf().isEmpty()) {
                continue;
            }
            String[] parts = road.branchOf().split("#");
            if (parts.length > 1 && parts[0].equals(roadKey) && parts[1].equals(nodeId)) {
                replaceRoad(road, road.withBranch("", road.nodes()));
                affected++;
            }
        }
        return affected;
    }

    /** 把指向 oldNodeId 的支线引用改指 newNodeId（合并节点后用）。返回受影响的支线数。 */
    public int rewriteBranchReferences(String roadKey, String oldNodeId, String newNodeId) {
        int affected = 0;
        for (AtlasRoad road : new ArrayList<>(roads)) {
            if (road.branchOf().isEmpty()) {
                continue;
            }
            String[] parts = road.branchOf().split("#");
            if (parts.length > 1 && parts[0].equals(roadKey) && parts[1].equals(oldNodeId)) {
                replaceRoad(road, road.withBranch(roadKey + "#" + newNodeId, road.nodes()));
                affected++;
            }
        }
        return affected;
    }

    /** 按 key 去重（同一条路可能同时来自内置/config/authored 三处）。 */    public void dedupeRoads() {
        java.util.Map<String, AtlasRoad> byKey = new java.util.LinkedHashMap<>();
        for (AtlasRoad road : roads) {
            if (road == null) {
                continue;
            }
            AtlasRoad existing = byKey.get(road.key());
            boolean authored = authoredRoads.contains(road);
            boolean existingAuthored = existing != null && authoredRoads.contains(existing);
            if (existing == null || (authored && !existingAuthored)) {
                byKey.put(road.key(), road);
            }
        }
        roads.clear();
        roads.addAll(byKey.values());
    }

    public void markDirty() {
        dirty = true;
        contentRevision++;
    }

    /** 内容修订号：任何增删改都会 +1，供派生缓存（区域索引/逐段归属/可见性集合）判失效。 */
    public int contentRevision() {
        return contentRevision;
    }

    // ------------------------------------------------------------------ 读写

    /** 资源包重载 / 进入世界时调用，重建合并视图。 */
    public void reload(ResourceManager resourceManager, String worldKey) {
        this.lastResourceManager = resourceManager;
        this.worldKey = worldKey;
        regions.clear();
        places.clear();
        roads.clear();
        maps.clear();
        authoredRegions.clear();
        authoredPlaces.clear();
        authoredRoads.clear();
        authoredMaps.clear();

        readInto(resourceManager, BUILTIN_REGIONS, BUILTIN_PLACES, BUILTIN_ROADS, BUILTIN_MAPS);
        readConfigInto();
        readAuthoredInto();
        applyTombstones();
        dedupeRoads();
        LOGGER.info("[BiyiAtlas] content loaded: {} region(s), {} place(s), {} road(s), {} map image(s) "
                        + "(authored: {}/{}/{}/{})",
                regions.size(), places.size(), roads.size(), maps.size(),
                authoredRegions.size(), authoredPlaces.size(), authoredRoads.size(), authoredMaps.size());
    }

    /** 应用墓碑：压掉内置/config 里那些"作者已删除"的条目（修 D-02 删了又复活）。 */
    private void applyTombstones() {
        if (removedRegions.isEmpty() && removedPlaces.isEmpty() && removedRoads.isEmpty()
                && removedMaps.isEmpty()) {
            return;
        }
        regions.keySet().removeAll(removedRegions);
        authoredRegions.keySet().removeAll(removedRegions);
        places.keySet().removeAll(removedPlaces);
        authoredPlaces.keySet().removeAll(removedPlaces);
        roads.removeIf(road -> removedRoads.contains(road.key()));
        authoredRoads.removeIf(road -> removedRoads.contains(road.key()));
        maps.removeIf(image -> removedMaps.contains(image.id()));
        authoredMaps.removeIf(image -> removedMaps.contains(image.id()));
        LOGGER.info("[BiyiAtlas] tombstones applied: regions={}, places={}, roads={}, maps={}",
                removedRegions.size(), removedPlaces.size(), removedRoads.size(), removedMaps.size());
    }

    private static void readSet(JsonObject root, String key, java.util.Set<String> target) {
        if (!root.has(key)) {
            return;
        }
        for (JsonElement element : GsonHelper.getAsJsonArray(root, key, new JsonArray())) {
            if (element.isJsonPrimitive()) {
                target.add(element.getAsString());
            }
        }
    }

    private static JsonArray toArray(java.util.Collection<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    /** 把游戏内编辑的内容写回磁盘（<gameDir>/biyi_atlas/authored/<世界>/content.json）。 */
    public void saveAuthored() {
        if (transactionDepth > 0) {
            transactionDirty = true;   // 事务进行中：暂缓落盘，commit 时一次写入
            return;
        }
        saveAuthoredNow();
    }

    /** Undo/redo reloads the full author document, including hierarchy, ownership and tombstones. */
    public boolean moveEditHistory(boolean redo) throws java.io.IOException {
        if(readOnly||writeProtected||inTransaction()||lastResourceManager==null)
            throw new IllegalStateException("当前不能撤销：只读、无作者权限或编辑未完成");
        if(dirty)saveAuthoredNow();
        if(dirty)throw new IllegalStateException("当前编辑保存失败，请先备份");
        boolean changed=new EditHistory(authoredFile(worldKey)).move(redo);
        if(changed) {
            reload(lastResourceManager,worldKey);dirty=false;contentRevision++;
            if(saveListener!=null)saveListener.run();
        }
        return changed;
    }

    // ------------------------------------------------------------ 事务（多步编辑的中断保护）

    /** 快照：浅拷贝即可（record 不可变，集合本身被增删/替换）。 */
    private record Snapshot(Map<String, AtlasRegion> regions, Map<String, AtlasPlace> places,
                            List<AtlasRoad> roads, List<AtlasMapImage> maps,
                            Map<String, AtlasRegion> authoredRegions,
                            Map<String, AtlasPlace> authoredPlaces,
                            List<AtlasRoad> authoredRoads, List<AtlasMapImage> authoredMaps,
                            java.util.Set<String> removedRegions, java.util.Set<String> removedPlaces,
                            java.util.Set<String> removedRoads, java.util.Set<String> removedMaps,
                            boolean dirty) {
    }

    /** 保存成功的回调（客户端用它通知服务端刷新区域注册表）。 */
    private Runnable saveListener;

    public void setSaveListener(Runnable listener) {
        this.saveListener = listener;
    }

    /** 对单例设置保存回调（客户端初始化时用）。 */
    public static void setInstanceSaveListener(Runnable listener) {
        get().setSaveListener(listener);
    }

    private Snapshot snapshot;
    private int transactionDepth;
    private boolean transactionDirty;

    /** 开事务：多步编辑（删除级联/切割/缝合/几何工具）都应包在其中。 */
    public void beginTransaction() {
        if (transactionDepth++ == 0) {
            snapshot = new Snapshot(new LinkedHashMap<>(regions), new LinkedHashMap<>(places),
                    new ArrayList<>(roads), new ArrayList<>(maps),
                    new LinkedHashMap<>(authoredRegions), new LinkedHashMap<>(authoredPlaces),
                    new ArrayList<>(authoredRoads), new ArrayList<>(authoredMaps),
                    new java.util.LinkedHashSet<>(removedRegions), new java.util.LinkedHashSet<>(removedPlaces),
                    new java.util.LinkedHashSet<>(removedRoads), new java.util.LinkedHashSet<>(removedMaps),
                    dirty);
        }
    }

    public void commitTransaction() {
        if (transactionDepth == 0) {
            return;
        }
        if (--transactionDepth == 0) {
            snapshot = null;
            if (transactionDirty) {
                transactionDirty = false;
                saveAuthoredNow();
            }
        }
    }

    /** 回滚到进入事务前的内容，绝不留半完成状态。 */
    public void rollbackTransaction() {
        if (transactionDepth == 0 || snapshot == null) {
            return;
        }
        regions.clear();
        regions.putAll(snapshot.regions());
        places.clear();
        places.putAll(snapshot.places());
        roads.clear();
        roads.addAll(snapshot.roads());
        maps.clear();
        maps.addAll(snapshot.maps());
        authoredRegions.clear();
        authoredRegions.putAll(snapshot.authoredRegions());
        authoredPlaces.clear();
        authoredPlaces.putAll(snapshot.authoredPlaces());
        authoredRoads.clear();
        authoredRoads.addAll(snapshot.authoredRoads());
        authoredMaps.clear();
        authoredMaps.addAll(snapshot.authoredMaps());
        removedRegions.clear();
        removedRegions.addAll(snapshot.removedRegions());
        removedPlaces.clear();
        removedPlaces.addAll(snapshot.removedPlaces());
        removedRoads.clear();
        removedRoads.addAll(snapshot.removedRoads());
        removedMaps.clear();
        removedMaps.addAll(snapshot.removedMaps());
        dirty = snapshot.dirty();
        transactionDepth = 0;
        transactionDirty = false;
        snapshot = null;
        contentRevision++;
        LOGGER.warn("[BiyiAtlas] 编辑事务已回滚（拒绝半完成状态）");
    }

    public boolean inTransaction() {
        return transactionDepth > 0;
    }

    /** 在事务里跑一段多步编辑：成功提交、异常回滚并抛出。 */
    public <T> T inTransaction(java.util.function.Supplier<T> action) {
        beginTransaction();
        try {
            T result = action.get();
            commitTransaction();
            return result;
        } catch (RuntimeException | Error error) {
            rollbackTransaction();
            throw error;
        }
    }

    /** 真正落盘（事务外调用）。 */
    private void saveAuthoredNow() {
        if (readOnly || writeProtected) {
            LOGGER.warn("[BiyiAtlas] 存档只读保护中（版本更高或迁移中断），本次不写盘");
            return;
        }
        if (!dirty) {
            return;
        }
        Path file = authoredFile(worldKey);
        try {
            // 旧扁平数据首次升级：先备份（.bak.v<旧版本号>），再原子写新形状
            if (loadedDataVersion < BiyiSchema.FILE_VERSION
                    || loadedRegionsSchema < BiyiSchema.REGIONS_SCHEMA
                    || loadedRoadsSchema < BiyiSchema.ROADS_SCHEMA) {
                AtomicIo.backup(file, fileRegionsSchemaAtLoad >= 0 ? fileRegionsSchemaAtLoad : loadedRegionsSchema);
            }
            byte[] payload = toJson(authoredRegions, authoredPlaces, authoredRoads, authoredMaps)
                    .toString().getBytes(StandardCharsets.UTF_8);
            new EditHistory(file).write(payload);
            loadedDataVersion = BiyiSchema.FILE_VERSION;
            loadedRegionsSchema = BiyiSchema.REGIONS_SCHEMA;
            loadedRoadsSchema = BiyiSchema.ROADS_SCHEMA;
            dirty = false;
            if (saveListener != null) {
                saveListener.run();
            }
            LOGGER.info("[BiyiAtlas] authored content saved: {} region(s), {} place(s), {} road(s), {} map(s) -> {}",
                    authoredRegions.size(), authoredPlaces.size(), authoredRoads.size(), authoredMaps.size(), file);
        } catch (Exception e) {
            LOGGER.warn("[BiyiAtlas] cannot save authored content: {}", e.toString());
        }
    }

    /** 当前存档的三层版本（根 / 区域套 / 路网套）。 */
    public int dataVersion() {
        return loadedDataVersion;
    }

    public int regionsSchema() {
        return loadedRegionsSchema;
    }

    public int roadsSchema() {
        return loadedRoadsSchema;
    }

    /** 是否处于只读保护（版本更高，或检测到迁移中断）。 */
    public boolean isReadOnly() {
        return readOnly;
    }

    /** 导出"合并后"的全部内容，作者可以直接把文件放进资源包的 atlas/ 目录。 */
    public Path exportMerged() {
        Path target = FMLPaths.CONFIGDIR.get().resolve(BiyiAtlas.MODID)
                .resolve("exported").resolve(worldKey).resolve("content.json");
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, toJson(regions, places, roads, maps).toString(), StandardCharsets.UTF_8);
            LOGGER.info("[BiyiAtlas] merged content exported to {}", target);
            return target;
        } catch (Exception e) {
            LOGGER.warn("[BiyiAtlas] export failed: {}", e.toString());
            return null;
        }
    }

    private JsonObject toJson(Map<String, AtlasRegion> regionMap, Map<String, AtlasPlace> placeMap,
                              List<AtlasRoad> roadList, List<AtlasMapImage> mapList) {
        JsonObject root = new JsonObject();
        if(projectSnapshot)root.addProperty("ProjectSnapshot",true);
        // 三层版本号：根 DataVersion（文件信封）+ 每套自己的 schema（被引用方 regions 在前）
        root.addProperty("DataVersion", BiyiSchema.FILE_VERSION);
        JsonArray regionArray = new JsonArray();
        for (AtlasRegion region : regionMap.values()) {
            regionArray.add(region.toJson());
        }
        root.add("Regions", section(BiyiSchema.REGIONS_SCHEMA, regionArray));
        JsonArray placeArray = new JsonArray();
        for (AtlasPlace place : placeMap.values()) {
            placeArray.add(place.toJson());
        }
        root.add("Places", section(BiyiSchema.REGIONS_SCHEMA, placeArray));
        JsonArray roadArray = new JsonArray();
        for (AtlasRoad road : roadList) {
            roadArray.add(road.toJson());
        }
        root.add("Roads", section(BiyiSchema.ROADS_SCHEMA, roadArray));
        JsonArray mapArray = new JsonArray();
        for (AtlasMapImage image : mapList) {
            mapArray.add(image.toJson());
        }
        root.add("Maps", section(BiyiSchema.REGIONS_SCHEMA, mapArray));
        JsonObject removed = new JsonObject();
        removed.add("regions", toArray(removedRegions));
        removed.add("places", toArray(removedPlaces));
        removed.add("roads", toArray(removedRoads));
        removed.add("maps", toArray(removedMaps));
        root.add("Removed", removed);
        // 映像图变换（regionId → [scale, offsetX, offsetZ]）：与区域分开存，避免动 region record 结构
        JsonObject transforms = new JsonObject();
        for (Map.Entry<String, double[]> entry : imageTransforms.entrySet()) {
            JsonArray triple = new JsonArray();
            triple.add(entry.getValue()[0]);
            triple.add(entry.getValue()[1]);
            triple.add(entry.getValue()[2]);
            transforms.add(entry.getKey(), triple);
        }
        root.add("ImageTransforms", transforms);
        return root;
    }

    private static JsonObject section(int schema, JsonArray list) {
        JsonObject object = new JsonObject();
        object.addProperty("schema", schema);
        object.add("list", list);
        return object;
    }

    /**
     * 取某一段的数组：兼容两种形状 ——
     * 新：{@code "Regions": {"schema":1,"list":[...]}}；旧扁平：{@code "regions":[...]}。
     */
    private static JsonArray section(JsonObject root, String name) {
        if (root.has(name)) {
            JsonElement element = root.get(name);
            if (element.isJsonObject()) {
                JsonObject object = element.getAsJsonObject();
                if (object.has("list")) {
                    return GsonHelper.getAsJsonArray(object, "list", new JsonArray());
                }
            } else if (element.isJsonArray()) {
                return element.getAsJsonArray();
            }
        }
        String legacy = name.substring(0, 1).toLowerCase(java.util.Locale.ROOT) + name.substring(1);
        return GsonHelper.getAsJsonArray(root, legacy, new JsonArray());
    }

    private static int sectionSchema(JsonObject root, String name) {
        if (root.has(name) && root.get(name).isJsonObject()) {
            return GsonHelper.getAsInt(root.getAsJsonObject(name), "schema", 0);
        }
        return 0;   // 旧扁平形状 = 史前数据
    }

    private void readInto(ResourceManager manager, ResourceLocation... paths) {
        for (ResourceLocation path : paths) {
            Optional<Resource> resource = manager.getResource(path);
            if (resource.isEmpty()) {
                continue;
            }
            try (Reader reader = resource.get().openAsReader()) {
                merge(reader);
            } catch (Exception e) {
                LOGGER.warn("[BiyiAtlas] cannot read builtin {}: {}", path, e.toString());
            }
        }
    }

    private void readConfigInto() {
        Path dir = FMLPaths.CONFIGDIR.get().resolve(BiyiAtlas.MODID);
        for (String name : new String[]{"regions.json", "places.json", "roads.json", "maps.json"}) {
            Path file = dir.resolve(name);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                merge(reader);
            } catch (Exception e) {
                LOGGER.warn("[BiyiAtlas] cannot read config {}: {}", file, e.toString());
            }
        }
    }

    private void readAuthoredInto() {
        Path file = authoredFile(worldKey);        if (!Files.isRegularFile(file)) {
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = GsonHelper.parse(reader);
            projectSnapshot=root.has("ProjectSnapshot")&&root.get("ProjectSnapshot").getAsBoolean();
            if(root.has("ProjectSnapshot")&&root.get("ProjectSnapshot").getAsBoolean()){
                regions.clear();places.clear();roads.clear();maps.clear();
            }
            // 版本解析（缺省 = 0 = 史前旧扁平数据）
            loadedDataVersion = GsonHelper.getAsInt(root, "DataVersion", 0);
            loadedRegionsSchema = sectionSchema(root, "Regions");
            fileRegionsSchemaAtLoad = loadedRegionsSchema;
            loadedRoadsSchema = sectionSchema(root, "Roads");
            readOnly = false;
            if (BiyiSchema.tooNew(loadedDataVersion, loadedRegionsSchema, loadedRoadsSchema)) {
                readOnly = true;
                LOGGER.warn("[BiyiAtlas] 存档版本比程序新（file={}, regions={}, roads={}）→ 只读保护，不写回",
                        loadedDataVersion, loadedRegionsSchema, loadedRoadsSchema);
            } else if (BiyiSchema.interrupted(loadedRegionsSchema, loadedRoadsSchema)) {
                readOnly = true;
                LOGGER.error("[BiyiAtlas] 检测到迁移中断（regions={}, roads={}）→ 只读保护，请人工处理",
                        loadedRegionsSchema, loadedRoadsSchema);
            } else if (loadedDataVersion < BiyiSchema.FILE_VERSION
                    || loadedRegionsSchema < BiyiSchema.REGIONS_SCHEMA
                    || loadedRoadsSchema < BiyiSchema.ROADS_SCHEMA) {
                LOGGER.info("[BiyiAtlas] 旧版存档（file={}, regions={}, roads={}），下次保存时升级并备份",
                        loadedDataVersion, loadedRegionsSchema, loadedRoadsSchema);
            }
            // 墓碑（删除名单）
            removedRegions.clear();
            removedPlaces.clear();
            removedRoads.clear();
            removedMaps.clear();
            // 映像图变换（regionId → [scale, offsetX, offsetZ]）
            imageTransforms.clear();
            if (root.has("ImageTransforms") && root.get("ImageTransforms").isJsonObject()) {
                JsonObject transforms = root.getAsJsonObject("ImageTransforms");
                for (String key : transforms.keySet()) {
                    JsonElement value = transforms.get(key);
                    if (value != null && value.isJsonArray() && value.getAsJsonArray().size() >= 3) {
                        JsonArray triple = value.getAsJsonArray();
                        imageTransforms.put(key, new double[]{triple.get(0).getAsDouble(),
                                triple.get(1).getAsDouble(), triple.get(2).getAsDouble()});
                    }
                }
            }
            if (root.has("Removed") && root.get("Removed").isJsonObject()) {
                JsonObject removed = root.getAsJsonObject("Removed");
                readSet(removed, "regions", removedRegions);
                readSet(removed, "places", removedPlaces);
                readSet(removed, "roads", removedRoads);
                readSet(removed, "maps", removedMaps);
            }
            for (AtlasRegion region : readRegions(root)) {
                regions.put(region.id(), region);
                authoredRegions.put(region.id(), region);
            }
            for (AtlasPlace place : readPlaces(root)) {
                places.put(place.key(), place);
                authoredPlaces.put(place.key(), place);
            }
            for (AtlasRoad road : readRoads(root)) {
                roads.add(road);
                authoredRoads.add(road);
            }
            for (AtlasMapImage image : readMaps(root)) {
                maps.add(image);
                authoredMaps.add(image);
            }
            // 迁移：Regions schema 1 → 2（子区域默认"锁定即隐藏其内容"）
            if (loadedRegionsSchema < 2) {
                int migratedCount = 0;
                for (AtlasRegion region : new ArrayList<>(regions.values())) {
                    if (region.childLocksContent()) {
                        continue;
                    }
                    AtlasRegion migratedRegion = region.withOwner(region.parent(), region.mapArea(), true);
                    regions.put(migratedRegion.id(), migratedRegion);
                    if (authoredRegions.containsKey(migratedRegion.id())) {
                        authoredRegions.put(migratedRegion.id(), migratedRegion);
                    }
                    migratedCount++;
                }
                loadedRegionsSchema = 2;
                markDirty();
                LOGGER.info("[BiyiAtlas] 区域 schema 1→2 迁移：{} 个区域改为\"锁定即隐藏内容\"（下次保存写入，自动 .bak 备份）",
                        migratedCount);
            }
        } catch (Exception e) {
            LOGGER.warn("[BiyiAtlas] cannot read authored {}: {}", file, e.toString());
        }
    }

    private void merge(Reader reader) {
        try {
            JsonObject root = GsonHelper.parse(reader);
            for (AtlasRegion region : readRegions(root)) {
                regions.put(region.id(), region);
            }
            for (AtlasPlace place : readPlaces(root)) {
                places.put(place.key(), place);
            }
            roads.addAll(readRoads(root));
            maps.addAll(readMaps(root));
        } catch (Exception e) {
            LOGGER.warn("[BiyiAtlas] bad content json: {}", e.toString());
        }
    }

    private static List<AtlasRegion> readRegions(JsonObject root) {
        List<AtlasRegion> result = new ArrayList<>();
        JsonArray array = section(root, "Regions");
        for (JsonElement element : array) {
            if (element.isJsonObject()) {
                AtlasRegion region = AtlasRegion.fromJson(element.getAsJsonObject());
                if (region != null) {
                    result.add(region);
                }
            }
        }
        return result;
    }

    private static List<AtlasPlace> readPlaces(JsonObject root) {
        List<AtlasPlace> result = new ArrayList<>();
        JsonArray array = section(root, "Places");
        for (JsonElement element : array) {
            if (element.isJsonObject()) {
                AtlasPlace place = AtlasPlace.fromJson(element.getAsJsonObject());
                if (place != null) {
                    result.add(place);
                }
            }
        }
        return result;
    }

    private static List<AtlasRoad> readRoads(JsonObject root) {
        List<AtlasRoad> result = new ArrayList<>();
        JsonArray array = section(root, "Roads");
        for (JsonElement element : array) {
            if (element.isJsonObject()) {
                AtlasRoad road = AtlasRoad.fromJson(element.getAsJsonObject());
                if (road != null) {
                    result.add(road);
                }
            }
        }
        return result;
    }

    private static List<AtlasMapImage> readMaps(JsonObject root) {
        List<AtlasMapImage> result = new ArrayList<>();
        JsonArray array = section(root, "Maps");
        for (JsonElement element : array) {
            if (element.isJsonObject()) {
                AtlasMapImage image = AtlasMapImage.fromJson(element.getAsJsonObject());
                if (image != null) {
                    result.add(image);
                }
            }
        }
        return result;
    }

    static Path authoredFile(String worldKey) {        return FMLPaths.GAMEDIR.get().resolve(BiyiAtlas.MODID).resolve("authored")
                .resolve(worldKey).resolve("content.json");
    }
}
