package com.biyi.atlas.client;

import com.biyi.atlas.map.RoadGraph;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * 玩家侧"引路"状态：当前路线、剩余距离/时间、下一条转向指引。
 *
 * <p>设计取舍（照 R2 调研结论）：</p>
 * <ul>
 *   <li>**画整条路线**（不是只画视距内一段）：已走段灰色、未走段高亮，只对下一段加箭头 ——
 *       避开 Clairvoyance"走出线段末端就断掉"的挫败；</li>
 *   <li>转向判定只用**纯角度区间**（Valhalla 的 9 段 LUT 语义：0°=直行、180°=掉头），
 *       中文用 OSRM 官方译法（直行/稍左/左/急左/掉头…）；</li>
 *   <li>路线状态**不参与存档序列化**（Clairvoyance 的"存档残留"坑），换世界即清。</li>
 * </ul>
 */
public final class AtlasRoute {

    private static RoadGraph.Path current;
    private static String targetName = "";
    private static double startX;
    private static double startZ;

    private AtlasRoute() {
    }

    public static void clear() {
        current = null;
        targetName = "";
    }

    public static boolean has() {
        return current != null && !current.isEmpty();
    }

    public static RoadGraph.Path path() {
        return current;
    }

    public static String targetName() {
        return targetName;
    }

    public static double startX() {
        return startX;
    }

    public static double startZ() {
        return startZ;
    }

    /**
     * 规划到目标的路线。返回给玩家看的提示文案（失败时说明原因，绝不硬连直线）。
     */
    public static String plan(ResourceLocation dimension, int layer, double fromX, double fromZ,
                              double toX, double toZ, String target) {
        RoadGraph graph = RoadGraph.build(dimension, layer);
        if (graph.nodes().isEmpty()) {
            clear();
            return "这一层还没有路网，无法规划路线（先画路或记录路径）";
        }
        RoadGraph.Path path = graph.route(fromX, fromZ, toX, toZ, 48.0D);
        if (path == null || path.isEmpty()) {
            clear();
            return "附近没有可用的道路（48 格内没有路网顶点）";
        }
        current = path;
        targetName = target == null ? "" : target;
        startX = fromX;
        startZ = fromZ;
        String suffix = path.complete() ? "" : "（路网未完全连通，只给出到最接近点的部分路线）";
        return "已规划路线：" + (int) path.length() + " 格 · 约 " + secondsOf(path.length()) + " 秒"
                + (targetName.isEmpty() ? "" : " · 目标「" + targetName + "」") + suffix;
    }

    /** 从当前位置起，沿路线剩余的距离（格）。 */
    public static double remainingFrom(double x, double z) {
        if (!has()) {
            return 0.0D;
        }
        List<double[]> points = current.points();
        int nearest = nearestIndex(points, x, z);
        double remaining = 0.0D;
        for (int i = nearest; i + 1 < points.size(); i++) {
            remaining += Math.hypot(points.get(i + 1)[0] - points.get(i)[0],
                    points.get(i + 1)[1] - points.get(i)[1]);
        }
        return remaining;
    }

    /** 已走过的索引（用于"已走灰、未走高亮"）。 */
    public static int progressIndex(double x, double z) {
        return has() ? nearestIndex(current.points(), x, z) : 0;
    }

    public static int secondsOf(double blocks) {
        return (int) Math.round(blocks / 4.317D);   // 步行 4.317 格/秒
    }

    /**
     * 下一条转向指引："{@code 在【岔口名】向右} · 还有 N 格"。
     *
     * <p>角度用"入边方向"与"出边方向"的夹角：直行 0°、掉头 180°（与 Valhalla 一致）。</p>
     */
    public static String instruction(double x, double z) {
        if (!has()) {
            return "";
        }
        List<double[]> points = current.points();
        int nearest = nearestIndex(points, x, z);
        RoadGraph.Mark mark = null;
        for (RoadGraph.Mark candidate : current.marks()) {
            if (candidate.pointIndex() > nearest + 1) {
                mark = candidate;
                break;
            }
        }
        if (mark == null) {
            return "就在附近了（剩余 " + (int) remainingFrom(x, z) + " 格）";
        }
        int index = Math.max(1, Math.min(points.size() - 2, mark.pointIndex()));
        double[] before = points.get(index - 1);
        double[] at = points.get(index);
        double[] after = points.get(index + 1);
        double inX = at[0] - before[0];
        double inZ = at[1] - before[1];
        double outX = after[0] - at[0];
        double outZ = after[1] - at[1];
        double turn = turnAngle(inX, inZ, outX, outZ);
        boolean right = (inX * outZ - inZ * outX) > 0.0D;
        String action = turnWord(turn, right);
        double distance = distanceAlong(points, nearest, index);
        return "在【" + mark.name() + "】" + action + " · 还有 " + (int) distance + " 格";
    }

    /** 夹角（度）：0 = 直行，180 = 掉头。 */
    public static double turnAngle(double inX, double inZ, double outX, double outZ) {
        double a = Math.hypot(inX, inZ);
        double b = Math.hypot(outX, outZ);
        if (a < 1.0E-9D || b < 1.0E-9D) {
            return 0.0D;
        }
        double cos = (inX * outX + inZ * outZ) / (a * b);
        cos = Math.max(-1.0D, Math.min(1.0D, cos));
        return Math.toDegrees(Math.acos(cos));
    }

    /** Valhalla 9 段 LUT 的中文词（掉头带不放宽）。 */
    public static String turnWord(double turn, boolean right) {
        if (turn < 25.0D) {
            return "直行";
        }
        if (turn < 60.0D) {
            return right ? "稍向右" : "稍向左";
        }
        if (turn < 135.0D) {
            return right ? "向右" : "向左";
        }
        if (turn < 160.0D) {
            return right ? "急向右" : "急向左";
        }
        return "掉头";
    }

    private static double distanceAlong(List<double[]> points, int from, int to) {
        double sum = 0.0D;
        for (int i = Math.max(0, from); i < Math.min(points.size() - 1, to); i++) {
            sum += Math.hypot(points.get(i + 1)[0] - points.get(i)[0],
                    points.get(i + 1)[1] - points.get(i)[1]);
        }
        return sum;
    }

    private static int nearestIndex(List<double[]> points, double x, double z) {
        int best = 0;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < points.size(); i++) {
            double distance = Math.hypot(points.get(i)[0] - x, points.get(i)[1] - z);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }
}
