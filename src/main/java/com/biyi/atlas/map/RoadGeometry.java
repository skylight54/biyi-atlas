package com.biyi.atlas.map;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 路网几何工具：把"一堆互不相干的折线"变成**一张连通的图**。
 *
 * <ul>
 *   <li>{@link #generateJunctions} —— 两线真交叉处插入**共享节点**（同一 id 进两条线）。
 *       算法照 JOSM {@code Geometry.addIntersections}：包围盒预筛 → 跳过同线相邻段 →
 *       相对判据求交（{@code |det| > 1e-12*mag}，不用绝对阈值）→ **JTS 的包围盒复核**（算出交点
 *       必须落在两段包围盒内，否则丢弃）。</li>
 *   <li>{@link #snapEndpoints} —— 端点吸附：手绘接驳时把端点并入附近已有节点，或两端点合并成共享节点。</li>
 *   <li>{@link #simplifyAll} —— Douglas–Peucker 抽稀，**受保护顶点**（节点所在顶点与两端点）不删：
 *       按受保护下标把折线切成子链，各自简化后拼回。</li>
 * </ul>
 */
public final class RoadGeometry {

    private RoadGeometry() {
    }

    /** 一条线上要插入的一个点：段号、段内参数、世界坐标、节点 id。 */
    private record Insertion(int segment, double t, double x, double z, String nodeId) {
    }

    // ================================================================ 交叉岔口

    public static int generateJunctions(ResourceLocation dimension, int layer, double tolerance) {
        AtlasContent content = AtlasContent.get();
        List<AtlasRoad> working = new ArrayList<>(content.roadsAt(dimension, layer));
        if (working.size() < 2) {
            return 0;
        }
        Map<String, List<double[]>> points = new LinkedHashMap<>();
        Map<String, List<AtlasRoadNode>> nodes = new LinkedHashMap<>();
        for (AtlasRoad road : working) {
            points.put(road.key(), new ArrayList<>(road.points()));
            nodes.put(road.key(), new ArrayList<>(road.nodes()));
        }

        int inserted = 0;
        for (int i = 0; i < working.size(); i++) {
            for (int j = i + 1; j < working.size(); j++) {
                AtlasRoad a = working.get(i);
                AtlasRoad b = working.get(j);
                List<double[]> pa = points.get(a.key());
                List<double[]> pb = points.get(b.key());
                if (!boundsOverlap(pa, pb)) {
                    continue;   // 包围盒预筛
                }
                inserted += intersectPair(a, b, points, nodes, tolerance);
            }
        }

        content.beginTransaction();
        try {
            int changed = 0;
            for (AtlasRoad road : working) {
                List<double[]> newPoints = points.get(road.key());
                List<AtlasRoadNode> newNodes = nodes.get(road.key());
                if (newPoints.size() != road.points().size() || newNodes.size() != road.nodes().size()) {
                    content.replaceRoad(road, road.withPoints(newPoints, newNodes));
                    changed++;
                }
            }
            if (changed > 0) {
                content.saveAuthored();
            }
            content.commitTransaction();
        } catch (RuntimeException | Error error) {
            content.rollbackTransaction();
            throw error;
        }
        return inserted;
    }

    private static int intersectPair(AtlasRoad a, AtlasRoad b, Map<String, List<double[]>> points,
                                     Map<String, List<AtlasRoadNode>> nodes, double tolerance) {
        List<double[]> pa = points.get(a.key());
        List<double[]> pb = points.get(b.key());
        List<Insertion> insertA = new ArrayList<>();
        List<Insertion> insertB = new ArrayList<>();
        for (int i = 0; i + 1 < pa.size(); i++) {
            for (int j = 0; j + 1 < pb.size(); j++) {
                double[] hit = segmentIntersection(pa.get(i), pa.get(i + 1), pb.get(j), pb.get(j + 1));
                if (hit == null) {
                    continue;
                }
                String nodeId = sharedNodeId(hit[0], hit[1], a, b, points, nodes, tolerance);
                insertA.add(new Insertion(i, hit[2], hit[0], hit[1], nodeId));
                insertB.add(new Insertion(j, hit[3], hit[0], hit[1], nodeId));
            }
        }
        if (insertA.isEmpty()) {
            return 0;
        }
        int count = applyInsertions(a, insertA, points, nodes);
        applyInsertions(b, insertB, points, nodes);
        return count;
    }

    /** 决定该交点用哪个节点 id：优先复用两线已有的近邻节点，否则用**按坐标量化**的确定性 id。 */
    private static String sharedNodeId(double x, double z, AtlasRoad a, AtlasRoad b,
                                       Map<String, List<double[]>> points,
                                       Map<String, List<AtlasRoadNode>> nodes, double tolerance) {
        for (String key : List.of(a.key(), b.key())) {
            List<double[]> pts = points.get(key);
            List<AtlasRoadNode> nds = nodes.get(key);
            if (pts == null || nds == null) {
                continue;
            }
            for (AtlasRoadNode node : nds) {
                if (node.index() < 0 || node.index() >= pts.size()) {
                    continue;
                }
                double[] p = pts.get(node.index());
                if (Math.hypot(p[0] - x, p[1] - z) <= tolerance) {
                    return node.id();
                }
            }
        }
        long qx = Math.round(x * 16.0D);
        long qz = Math.round(z * 16.0D);
        return "j" + qx + "_" + qz;
    }

    /** 按段号降序插入（从后往前插，避免前面的插入影响后面的段号）。 */
    private static int applyInsertions(AtlasRoad road, List<Insertion> insertions,
                                       Map<String, List<double[]>> points,
                                       Map<String, List<AtlasRoadNode>> nodes) {
        List<double[]> pts = points.get(road.key());
        List<AtlasRoadNode> nds = nodes.get(road.key());
        insertions.sort(Comparator.comparingInt(Insertion::segment).reversed()
                .thenComparing(Comparator.comparingDouble(Insertion::t).reversed()));
        int added = 0;
        for (Insertion insertion : insertions) {
            int segment = insertion.segment();
            if (segment < 0 || segment + 1 >= pts.size()) {
                continue;
            }
            double[] from = pts.get(segment);
            double[] to = pts.get(segment + 1);
            double[] point = {from[0] + (to[0] - from[0]) * insertion.t(),
                    from[1] + (to[1] - from[1]) * insertion.t()};
            int at = segment + 1;
            pts.add(at, point);
            for (int k = 0; k < nds.size(); k++) {
                AtlasRoadNode node = nds.get(k);
                if (node.index() >= at) {
                    nds.set(k, node.withIndex(node.index() + 1));
                }
            }
            if (!hasNodeNear(pts, nds, point, 0.25D)) {
                nds.add(new AtlasRoadNode(insertion.nodeId(), at, "岔口", AtlasRoadNode.KIND_JUNCTION));
                added++;
            }
        }
        return added;
    }

    private static boolean hasNodeNear(List<double[]> pts, List<AtlasRoadNode> nds, double[] point,
                                       double radius) {
        for (AtlasRoadNode node : nds) {
            if (node.index() < 0 || node.index() >= pts.size()) {
                continue;
            }
            double[] p = pts.get(node.index());
            if (Math.hypot(p[0] - point[0], p[1] - point[1]) <= radius) {
                return true;
            }
        }
        return false;
    }

    private static boolean boundsOverlap(List<double[]> a, List<double[]> b) {
        double[] ba = bounds(a);
        double[] bb = bounds(b);
        if (ba == null || bb == null) {
            return false;
        }
        return !(ba[2] < bb[0] || bb[2] < ba[0] || ba[3] < bb[1] || bb[3] < ba[1]);
    }

    private static double[] bounds(List<double[]> points) {
        if (points.isEmpty()) {
            return null;
        }
        double minX = Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        for (double[] point : points) {
            minX = Math.min(minX, point[0]);
            minZ = Math.min(minZ, point[1]);
            maxX = Math.max(maxX, point[0]);
            maxZ = Math.max(maxZ, point[1]);
        }
        return new double[]{minX, minZ, maxX, maxZ};
    }

    /**
     * 段段求交。返回 {@code {x, z, tA, tB}}（参数在 (0,1) 开区间内才认"真交叉"）。
     *
     * <p>相对判据：{@code |det| > 1e-12 * (|a1*b2| + |a2*b1|)}；随后做 **JTS 式包围盒复核**，
     * 算出的交点必须落在两段的包围盒内，否则丢弃（防 naive 浮点产出"不存在的交点"）。</p>
     */
    private static double[] segmentIntersection(double[] p1, double[] p2, double[] p3, double[] p4) {
        double a1 = p2[0] - p1[0];
        double a2 = p2[1] - p1[1];
        double b1 = p4[0] - p3[0];
        double b2 = p4[1] - p3[1];
        double det = a1 * b2 - a2 * b1;
        double magnitude = Math.abs(a1 * b2) + Math.abs(a2 * b1);
        if (magnitude <= 0.0D || Math.abs(det) <= 1.0E-12D * magnitude) {
            return null;   // 平行/退化
        }
        double c1 = p3[0] - p1[0];
        double c2 = p3[1] - p1[1];
        double u = (c1 * b2 - c2 * b1) / det;
        double v = (c1 * a2 - c2 * a1) / det;
        if (u <= 0.0D || u >= 1.0D || v <= 0.0D || v >= 1.0D) {
            return null;   // 端点接触交给"吸附端点"处理，这里只要中间真交叉
        }
        double x = p1[0] + a1 * u;
        double z = p1[1] + a2 * u;
        if (!insideBounds(x, z, p1, p2) || !insideBounds(x, z, p3, p4)) {
            return null;   // JTS 复核
        }
        return new double[]{x, z, u, v};
    }

    private static boolean insideBounds(double x, double z, double[] a, double[] b) {
        double minX = Math.min(a[0], b[0]) - 1.0E-6D;
        double maxX = Math.max(a[0], b[0]) + 1.0E-6D;
        double minZ = Math.min(a[1], b[1]) - 1.0E-6D;
        double maxZ = Math.max(a[1], b[1]) + 1.0E-6D;
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    // ================================================================ 端点吸附

    /** 端点吸附：把端点并入附近已有节点，或把两个端点合并成共享节点。返回被吸附的端点数。 */
    public static int snapEndpoints(ResourceLocation dimension, int layer, double radius) {
        AtlasContent content = AtlasContent.get();
        List<AtlasRoad> working = new ArrayList<>(content.roadsAt(dimension, layer));
        Map<String, List<double[]>> points = new LinkedHashMap<>();
        Map<String, List<AtlasRoadNode>> nodes = new LinkedHashMap<>();
        for (AtlasRoad road : working) {
            points.put(road.key(), new ArrayList<>(road.points()));
            nodes.put(road.key(), new ArrayList<>(road.nodes()));
        }
        int snapped = 0;
        // 1) 端点 → 其它线的已有节点
        for (AtlasRoad road : working) {
            List<double[]> pts = points.get(road.key());
            List<AtlasRoadNode> nds = nodes.get(road.key());
            for (int index : new int[]{0, pts.size() - 1}) {
                if (pts.size() < 2) {
                    break;
                }
                double[] endpoint = pts.get(index);
                String bestId = null;
                double bestDistance = radius;
                double[] bestPoint = null;
                for (AtlasRoad other : working) {
                    if (other.key().equals(road.key())) {
                        continue;
                    }
                    List<double[]> otherPoints = points.get(other.key());
                    for (AtlasRoadNode node : nodes.get(other.key())) {
                        if (node.index() < 0 || node.index() >= otherPoints.size()) {
                            continue;
                        }
                        double[] p = otherPoints.get(node.index());
                        double distance = Math.hypot(p[0] - endpoint[0], p[1] - endpoint[1]);
                        if (distance <= bestDistance) {
                            bestDistance = distance;
                            bestId = node.id();
                            bestPoint = p;
                        }
                    }
                }
                if (bestId != null && bestPoint != null) {
                    double[] moved = {bestPoint[0], bestPoint[1]};
                    pts.set(index, moved);
                    if (!hasNodeNear(pts, nds, moved, 0.25D)) {
                        nds.add(new AtlasRoadNode(bestId, index, "接驳", AtlasRoadNode.KIND_JUNCTION));
                    }
                    snapped++;
                }
            }
        }
        // 2) 端点 ↔ 端点：都没节点时合并成共享节点
        for (int i = 0; i < working.size(); i++) {
            for (int j = i + 1; j < working.size(); j++) {
                AtlasRoad a = working.get(i);
                AtlasRoad b = working.get(j);
                List<double[]> pa = points.get(a.key());
                List<double[]> pb = points.get(b.key());
                for (int ia : new int[]{0, pa.size() - 1}) {
                    for (int ib : new int[]{0, pb.size() - 1}) {
                        if (pa.isEmpty() || pb.isEmpty()) {
                            continue;
                        }
                        double[] x = pa.get(ia);
                        double[] y = pb.get(ib);
                        if (Math.hypot(x[0] - y[0], x[1] - y[1]) > radius) {
                            continue;
                        }
                        double mx = (x[0] + y[0]) / 2.0D;
                        double mz = (x[1] + y[1]) / 2.0D;
                        String id = "e" + Math.round(mx * 16.0D) + "_" + Math.round(mz * 16.0D);
                        pa.set(ia, new double[]{mx, mz});
                        pb.set(ib, new double[]{mx, mz});
                        addNodeIfAbsent(points.get(a.key()), nodes.get(a.key()), ia, id);
                        addNodeIfAbsent(points.get(b.key()), nodes.get(b.key()), ib, id);
                        snapped += 2;
                    }
                }
            }
        }
        content.beginTransaction();
        try {
            int changed = 0;
            for (AtlasRoad road : working) {
                List<double[]> newPoints = points.get(road.key());
                List<AtlasRoadNode> newNodes = nodes.get(road.key());
                if (!samePoints(newPoints, road.points()) || newNodes.size() != road.nodes().size()) {
                    content.replaceRoad(road, road.withPoints(newPoints, newNodes));
                    changed++;
                }
            }
            if (changed > 0) {
                content.saveAuthored();
            }
            content.commitTransaction();
        } catch (RuntimeException | Error error) {
            content.rollbackTransaction();
            throw error;
        }
        return snapped;
    }

    private static void addNodeIfAbsent(List<double[]> pts, List<AtlasRoadNode> nds, int index, String id) {
        for (AtlasRoadNode node : nds) {
            if (node.id().equals(id)) {
                return;
            }
        }
        nds.add(new AtlasRoadNode(id, index, "接驳", AtlasRoadNode.KIND_JUNCTION));
    }

    private static boolean samePoints(List<double[]> a, List<double[]> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (Math.abs(a.get(i)[0] - b.get(i)[0]) > 1.0E-6D
                    || Math.abs(a.get(i)[1] - b.get(i)[1]) > 1.0E-6D) {
                return false;
            }
        }
        return true;
    }

    // ================================================================ 折线简化

    /** 对本层所有道路做 Douglas–Peucker 抽稀（保护节点所在顶点与两端点）。返回减少的顶点数。 */
    public static int simplifyAll(ResourceLocation dimension, int layer, double epsilon) {
        AtlasContent content = AtlasContent.get();
        content.beginTransaction();
        try {
            int removed = 0;
            for (AtlasRoad road : new ArrayList<>(content.roadsAt(dimension, layer))) {
                AtlasRoad simplified = simplify(road, epsilon);
                if (simplified != null) {
                    removed += road.points().size() - simplified.points().size();
                    content.replaceRoad(road, simplified);
                }
            }
            if (removed > 0) {
                content.saveAuthored();
            }
            content.commitTransaction();
            return removed;
        } catch (RuntimeException | Error error) {
            content.rollbackTransaction();
            throw error;
        }
    }

    /** 简化一条路；没有可简化点时返回 null。 */
    public static AtlasRoad simplify(AtlasRoad road, double epsilon) {
        List<double[]> points = road.points();
        if (points.size() < 3) {
            return null;
        }
        List<Integer> protect = new ArrayList<>();
        protect.add(0);
        protect.add(points.size() - 1);
        for (AtlasRoadNode node : road.nodes()) {
            if (node.index() > 0 && node.index() < points.size() - 1) {
                protect.add(node.index());
            }
        }
        protect.sort(Integer::compareTo);

        List<double[]> result = new ArrayList<>();
        Map<Integer, Integer> indexMap = new LinkedHashMap<>();
        for (int k = 0; k + 1 < protect.size(); k++) {
            int from = protect.get(k);
            int to = protect.get(k + 1);
            List<double[]> chain = new ArrayList<>(points.subList(from, to + 1));
            List<double[]> simplified = douglasPeucker(chain, epsilon);
            int base = result.size();
            if (k > 0) {
                simplified.remove(0);   // 接缝点已由上一段加入
            }
            result.addAll(simplified);
            // 记录这一段的两个受保护端点在新数组里的下标
            indexMap.put(from, base - (k > 0 ? 1 : 0));
            indexMap.put(to, result.size() - 1);
        }
        if (result.size() >= points.size()) {
            return null;
        }
        List<AtlasRoadNode> nodes = new ArrayList<>();
        for (AtlasRoadNode node : road.nodes()) {
            Integer mapped = indexMap.get(node.index());
            nodes.add(mapped == null ? node : node.withIndex(mapped));
        }
        return road.withPoints(result, nodes);
    }

    /** 纯点集抽稀（渲染 LOD 用：不关心节点保护，只要形状）。 */
    public static List<double[]> simplifyPoints(List<double[]> points, double epsilon) {
        if (points.size() < 3 || epsilon <= 0.0D) {
            return points;
        }
        return douglasPeucker(points, epsilon);
    }

    private static List<double[]> douglasPeucker(List<double[]> points, double epsilon) {        if (points.size() < 3) {
            return new ArrayList<>(points);
        }
        boolean[] keep = new boolean[points.size()];
        keep[0] = true;
        keep[points.size() - 1] = true;
        simplifySegment(points, 0, points.size() - 1, epsilon, keep);
        List<double[]> result = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            if (keep[i]) {
                result.add(points.get(i));
            }
        }
        return result;
    }

    private static void simplifySegment(List<double[]> points, int from, int to, double epsilon,
                                        boolean[] keep) {
        if (to <= from + 1) {
            return;
        }
        double maxDistance = -1.0D;
        int index = -1;
        double[] a = points.get(from);
        double[] b = points.get(to);
        for (int i = from + 1; i < to; i++) {
            double distance = pointToSegment(points.get(i), a, b);
            if (distance > maxDistance) {
                maxDistance = distance;
                index = i;
            }
        }
        if (maxDistance > epsilon && index > 0) {
            keep[index] = true;
            simplifySegment(points, from, index, epsilon, keep);
            simplifySegment(points, index, to, epsilon, keep);
        }
    }

    private static double pointToSegment(double[] p, double[] a, double[] b) {
        double dx = b[0] - a[0];
        double dz = b[1] - a[1];
        double lengthSq = dx * dx + dz * dz;
        if (lengthSq < 1.0E-9D) {
            return Math.hypot(p[0] - a[0], p[1] - a[1]);
        }
        double t = Math.max(0.0D, Math.min(1.0D, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dz) / lengthSq));
        return Math.hypot(p[0] - (a[0] + t * dx), p[1] - (a[1] + t * dz));
    }
}
