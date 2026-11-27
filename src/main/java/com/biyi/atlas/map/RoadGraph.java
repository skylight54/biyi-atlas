package com.biyi.atlas.map;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * 路网图（**派生态**，不落盘）：把作者手绘的折线 + 节点编译成一张可寻路的图。
 *
 * <p>构图规则：</p>
 * <ul>
 *   <li>每条线上，**节点所在顶点**与**两端点**是图的顶点；中间普通顶点只是边的折线形状；</li>
 *   <li>顶点身份：若该处有节点 → 用**节点 id**（因此两条线共享节点 id 时自然连通，= 岔口）；</li>
 *   <li>端点没有节点时用坐标量化 id {@code v<qx>_<qz>}，所以"吸附/重合的端点"也会连通；</li>
 *   <li>边是双向的（暂不支持单行道）。</li>
 * </ul>
 *
 * <p>寻路：A\*，欧氏距离作启发式；带**展开上限**与**时间上限**；预算耗尽时返回
 * **部分路径**（到最接近目标的点），并把 {@code complete=false} 报给上层。</p>
 */
public final class RoadGraph {

    /** 图顶点。 */
    public record Node(String id, double x, double z, String name) {
    }

    /** 一条边：终点 id + 代价（格）+ 折线形状（含起点与终点）。 */
    public record Edge(String from, String to, double cost, List<double[]> polyline) {
    }

    /** 一条指令点：在路径折线里的下标 + 顶点名。 */
    public record Mark(int pointIndex, String name) {
    }

    /** 寻路结果。 */
    public record Path(List<double[]> points, List<Mark> marks, double length, boolean complete) {
        public boolean isEmpty() {
            return points.size() < 2;
        }
    }

    private static final int DEFAULT_MAX_EXPANSIONS = 50_000;
    private static final long DEFAULT_BUDGET_MS = 500L;

    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final Map<String, List<Edge>> adjacency = new LinkedHashMap<>();

    private RoadGraph() {
    }

    // ================================================================ 构图

    public static RoadGraph build(ResourceLocation dimension, int layer) {
        RoadGraph graph = new RoadGraph();
        AtlasContent content = AtlasContent.get();
        for (AtlasRoad road : content.roadsAt(dimension, layer)) {
            graph.addRoad(road);
        }
        return graph;
    }

    private void addRoad(AtlasRoad road) {
        List<double[]> points = road.points();
        if (points.size() < 2) {
            return;
        }
        Map<Integer, AtlasRoadNode> nodeAt = new HashMap<>();
        for (AtlasRoadNode node : road.nodes()) {
            if (node.index() >= 0 && node.index() < points.size()) {
                nodeAt.putIfAbsent(node.index(), node);
            }
        }
        int start = 0;
        for (int i = 1; i < points.size(); i++) {
            boolean isEnd = i == points.size() - 1;
            boolean isGraphVertex = isEnd || nodeAt.containsKey(i);
            if (!isGraphVertex) {
                continue;
            }
            List<double[]> polyline = new ArrayList<>(points.subList(start, i + 1));
            double cost = length(polyline);
            String fromId = vertexId(points, nodeAt, start);
            String toId = vertexId(points, nodeAt, i);
            String fromName = vertexName(points, nodeAt, start);
            String toName = vertexName(points, nodeAt, i);
            nodes.putIfAbsent(fromId, new Node(fromId, points.get(start)[0], points.get(start)[1], fromName));
            nodes.putIfAbsent(toId, new Node(toId, points.get(i)[0], points.get(i)[1], toName));
            if (!fromId.equals(toId) && cost > 1.0E-6D) {
                adjacency.computeIfAbsent(fromId, key -> new ArrayList<>())
                        .add(new Edge(fromId, toId, cost, polyline));
                List<double[]> reversed = new ArrayList<>(polyline);
                java.util.Collections.reverse(reversed);
                adjacency.computeIfAbsent(toId, key -> new ArrayList<>())
                        .add(new Edge(toId, fromId, cost, reversed));
            }
            start = i;
        }
    }

    private static String vertexId(List<double[]> points, Map<Integer, AtlasRoadNode> nodeAt, int index) {
        AtlasRoadNode node = nodeAt.get(index);
        if (node != null) {
            return node.id();
        }
        double[] point = points.get(index);
        return "v" + Math.round(point[0] * 16.0D) + "_" + Math.round(point[1] * 16.0D);
    }

    private static String vertexName(List<double[]> points, Map<Integer, AtlasRoadNode> nodeAt, int index) {
        AtlasRoadNode node = nodeAt.get(index);
        if (node != null) {
            return node.name();
        }
        return index == 0 || index == points.size() - 1 ? "端点" : "路口";
    }

    private static double length(List<double[]> polyline) {
        double sum = 0.0D;
        for (int i = 0; i + 1 < polyline.size(); i++) {
            sum += Math.hypot(polyline.get(i + 1)[0] - polyline.get(i)[0],
                    polyline.get(i + 1)[1] - polyline.get(i)[1]);
        }
        return sum;
    }

    // ================================================================ 寻路

    public List<Node> nodes() {
        return new ArrayList<>(nodes.values());
    }

    public int edgeCount() {
        int count = 0;
        for (List<Edge> edges : adjacency.values()) {
            count += edges.size();
        }
        return count / 2;
    }

    /**
     * 从 (fromX,fromZ) 到 (toX,toZ) 规划路线。
     *
     * <p>起终点先吸附到**最近的图顶点**（半径 {@code snapRadius} 格）；找不到就返回 {@code null}
     * （上层应提示"附近没有路"，而不是硬连一条直线）。</p>
     */
    public Path route(double fromX, double fromZ, double toX, double toZ, double snapRadius) {
        Node start = nearestNode(fromX, fromZ);
        Node goal = nearestNode(toX, toZ);
        if (start == null || goal == null) {
            return null;
        }
        if (Math.hypot(start.x() - fromX, start.z() - fromZ) > snapRadius
                || Math.hypot(goal.x() - toX, goal.z() - toZ) > snapRadius) {
            return null;
        }
        return aStar(start, goal, DEFAULT_MAX_EXPANSIONS, DEFAULT_BUDGET_MS);
    }

    public Node nearestNode(double x, double z) {
        Node best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Node node : nodes.values()) {
            double distance = Math.hypot(node.x() - x, node.z() - z);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = node;
            }
        }
        return best;
    }

    private Path aStar(Node start, Node goal, int maxExpansions, long budgetMs) {
        long deadline = System.currentTimeMillis() + budgetMs;
        Map<String, Double> best = new HashMap<>();
        Map<String, Edge> cameFrom = new HashMap<>();
        PriorityQueue<Object[]> open = new PriorityQueue<>(Comparator.comparingDouble(entry -> (double) entry[0]));
        best.put(start.id(), 0.0D);
        open.add(new Object[]{heuristic(start, goal), start.id(), 0.0D});
        int expansions = 0;
        Node closest = start;
        double closestDistance = heuristic(start, goal);

        while (!open.isEmpty()) {
            Object[] entry = open.poll();
            String currentId = (String) entry[1];
            double g = (double) entry[2];
            if (g > best.getOrDefault(currentId, Double.MAX_VALUE) + 1.0E-9D) {
                continue;
            }
            Node current = nodes.get(currentId);
            double distance = heuristic(current, goal);
            if (distance < closestDistance) {
                closestDistance = distance;
                closest = current;
            }
            if (currentId.equals(goal.id())) {
                return reconstruct(start, goal, cameFrom, true);
            }
            expansions++;
            if (expansions > maxExpansions || System.currentTimeMillis() > deadline) {
                // 预算耗尽：返回"到最接近目标的点"的部分路径
                return closest == start ? null : reconstruct(start, closest, cameFrom, false);
            }
            for (Edge edge : adjacency.getOrDefault(currentId, List.of())) {
                double candidate = g + edge.cost();
                if (candidate + 1.0E-9D < best.getOrDefault(edge.to(), Double.MAX_VALUE)) {
                    best.put(edge.to(), candidate);
                    cameFrom.put(edge.to(), edge);
                    double h = heuristic(nodes.get(edge.to()), goal);
                    open.add(new Object[]{candidate + h, edge.to(), candidate});
                }
            }
        }
        return closest == start ? null : reconstruct(start, closest, cameFrom, false);
    }

    private static double heuristic(Node a, Node b) {
        return Math.hypot(a.x() - b.x(), a.z() - b.z());
    }

    private Path reconstruct(Node start, Node goal, Map<String, Edge> cameFrom, boolean complete) {
        List<Edge> reversed = new ArrayList<>();
        String current = goal.id();
        while (!current.equals(start.id())) {
            Edge edge = cameFrom.get(current);
            if (edge == null) {
                break;
            }
            reversed.add(edge);
            current = edge.from();
        }
        java.util.Collections.reverse(reversed);
        List<double[]> points = new ArrayList<>();
        List<Mark> marks = new ArrayList<>();
        double total = 0.0D;
        for (Edge edge : reversed) {
            List<double[]> polyline = edge.polyline();
            if (points.isEmpty()) {
                points.addAll(polyline);
            } else {
                points.addAll(polyline.subList(1, polyline.size()));
            }
            total += edge.cost();
            Node to = nodes.get(edge.to());
            marks.add(new Mark(points.size() - 1, to == null ? "路口" : to.name()));
        }
        return new Path(points, marks, total, complete);
    }
}
