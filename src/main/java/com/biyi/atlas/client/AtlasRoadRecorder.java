package com.biyi.atlas.client;

import com.biyi.atlas.map.AtlasContent;
import com.biyi.atlas.map.AtlasRoad;
import com.biyi.atlas.map.AtlasRoadNode;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 道路自动记录：作者开着记录在地图里走一趟，轨迹就变成一条路。
 * 每 10 tick 采一个点，移动不足 3 格不记，避免站着不动时堆点。
 */
public final class AtlasRoadRecorder {

    private static final int INTERVAL_TICKS = 10;
    private static final double MIN_STEP = 3.0D;

    private static boolean recording;
    /** 当前作者编辑层（由地图界面同步过来）。 */
    private static int recordLayer;

    // ---- 支线状态：从某个节点开始画一条岔路 ----
    private static String branchRoadKey = "";
    private static String branchNodeId = "";
    private static double branchX;
    private static double branchZ;

    public static void setLayer(int layer) {
        recordLayer = layer;
    }

    /** 从某条路的某个节点开岔：记录起点强制为该节点坐标。 */
    public static void beginBranch(com.biyi.atlas.map.AtlasRoad road, com.biyi.atlas.map.AtlasRoadNode node) {
        if (road == null || node == null || node.index() >= road.points().size()) {
            return;
        }
        branchRoadKey = road.key();
        branchNodeId = node.id();
        branchX = road.points().get(node.index())[0];
        branchZ = road.points().get(node.index())[1];
        points.clear();
        points.add(new double[]{branchX, branchZ});
        start(road.dimension(), true);
    }

    public static boolean hasBranch() {
        return !branchRoadKey.isEmpty() && !branchNodeId.isEmpty();
    }

    public static double[] branchStart() {
        return new double[]{branchX, branchZ};
    }

    /** 支线来源字符串：{@code 父线id#父节点id}。 */
    public static String branchOf() {
        return hasBranch() ? branchRoadKey + "#" + branchNodeId : "";
    }

    public static String branchParentKey() {
        return branchRoadKey;
    }

    public static String branchNodeId() {
        return branchNodeId;
    }

    public static void clearBranch() {
        branchRoadKey = "";
        branchNodeId = "";
    }
    private static final List<double[]> points = new ArrayList<>();
    private static ResourceLocation dimension;
    private static int cooldown;

    private AtlasRoadRecorder() {
    }

    public static boolean isRecording() {
        return recording;
    }

    public static int pointCount() {
        return points.size();
    }

    public static void toggle() {
        if (recording) {
            stop();
        } else {
            start();
        }
    }

    public static void start() {
        start(null, false);
    }

    /** 开始记录：{@code branchFirst=true} 时保留已放入的起点（支线用）。 */
    public static void start(net.minecraft.resources.ResourceLocation forcedDimension, boolean branchFirst) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        // 关键：支线模式不能清空起点（否则 points 为空但 recording=true → tick 里取空列表崩溃）
        if (!branchFirst) {
            points.clear();
            points.add(new double[]{minecraft.player.getX(), minecraft.player.getZ()});
        } else if (points.isEmpty()) {
            points.add(new double[]{minecraft.player.getX(), minecraft.player.getZ()});
        }
        dimension = forcedDimension != null ? forcedDimension : minecraft.level.dimension().location();
        recording = true;
        cooldown = INTERVAL_TICKS;
        message(branchFirst
                ? "支线记录中：走到目的地，再按一次 R 结束"
                : "开始记录道路轨迹，走一条路，再按一次 R 结束");
    }

    public static void stop() {
        recording = false;
        Minecraft minecraft = Minecraft.getInstance();
        if (points.size() < 2) {
            message("轨迹太短，没有生成道路");
            points.clear();
            return;
        }
        String name = (hasBranch() ? "支线 " : "路径 ")
                + (AtlasContent.get().roadsAt(dimension).size() + 1);
        String id = AtlasContent.get().nextRoadId(dimension, recordLayer);   // 统一 id 生成（永不复用）
        // 用作者当前编辑层，而不是硬编码 0：否则在 -1/+1 层录的道路会掉到地表层
        // 支线：起点必须"共享"父节点 id（OSM common node 模型），否则岔口在图上是断的
        List<AtlasRoadNode> newNodes = new ArrayList<>();
        if (hasBranch()) {
            newNodes.add(new AtlasRoadNode(branchNodeId(), 0,
                    AtlasContent.get().nodeNameOf(branchParentKey(), branchNodeId()),
                    AtlasRoadNode.KIND_JUNCTION));
        } else {
            newNodes.add(AtlasRoadNode.of(0, "起点").withKind(AtlasRoadNode.KIND_END));
        }
        // 终点吸附：落在已有节点 3 格内就继承它的 id，让环路/接驳自动闭合
        AtlasRoadNode snapped = AtlasContent.get().nodeNear(dimension, recordLayer,
                points.get(points.size() - 1)[0], points.get(points.size() - 1)[1], 3.0D);
        double[] snappedPosition = snapped == null ? null
                : AtlasContent.get().nodePositionOf(dimension, recordLayer, snapped.id());
        if (snapped != null && snappedPosition != null && !snapped.id().equals(branchNodeId())) {
            points.set(points.size() - 1, snappedPosition);
            newNodes.add(snapped.withIndex(points.size() - 1));
        } else {
            newNodes.add(AtlasRoadNode.of(points.size() - 1, "终点")
                    .withKind(AtlasRoadNode.KIND_END));
        }
        AtlasRoad road = new AtlasRoad(id, name, dimension, recordLayer, "", new ArrayList<>(points), 3,
                0x8C6F52, false, newNodes, branchOf());
        AtlasContent.get().addRoad(road);
        AtlasContent.get().saveAuthored();
        // 支线：把父线上那个节点标记成岔口
        if (hasBranch()) {
            AtlasRoad parent = AtlasContent.get().roadByKey(branchParentKey());
            if (parent != null) {
                List<AtlasRoadNode> nodes = new ArrayList<>(parent.nodes());
                for (int i = 0; i < nodes.size(); i++) {
                    if (nodes.get(i).id().equals(branchNodeId())) {
                        nodes.set(i, nodes.get(i).withKind(AtlasRoadNode.KIND_JUNCTION));
                    }
                }
                AtlasContent.get().replaceRoad(parent, parent.withNodes(nodes));
                AtlasContent.get().saveAuthored();
            }
            message("已生成支线「" + name + "」（从节点岔出）");
            clearBranch();
        } else {
            message("已生成道路「" + name + "」，共 " + points.size() + " 个点");
        }
        points.clear();
        // 名字由玩家在元素列表里改；这里只在日志里留痕，方便排查
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.literal("[避役图志] " + name), false);
        }
    }

    public static void tick() {
        if (!recording) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        ResourceLocation current = minecraft.level.dimension().location();
        if (!current.equals(dimension)) {
            stop();
            return;
        }
        if (--cooldown > 0) {
            return;
        }
        cooldown = INTERVAL_TICKS;
        double x = minecraft.player.getX();
        double z = minecraft.player.getZ();
        // 防御：任何情况下 points 为空都不能崩（历史 bug：支线起点被清空 → IndexOutOfBounds）
        if (points.isEmpty()) {
            points.add(new double[]{x, z});
            return;
        }
        double[] last = points.get(points.size() - 1);
        if (Math.hypot(x - last[0], z - last[1]) >= MIN_STEP) {
            points.add(new double[]{x, z});
        }
    }

    /** 界面里画"正在记录"的预览折线用。 */
    public static List<double[]> previewPoints() {
        return points;
    }

    public static ResourceLocation recordedDimension() {
        return dimension;
    }

    private static void message(String text) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.literal("[避役图志] " + text), false);
        }
    }

    /** 退出存档时清理静态状态（否则换存档会带着上一条未完成的轨迹，R2-D14）。 */
    public static void resetForLogout() {
        recording = false;
        points.clear();
        clearBranch();
        cooldown = 0;
    }
}
