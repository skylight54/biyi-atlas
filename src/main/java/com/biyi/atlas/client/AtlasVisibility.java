package com.biyi.atlas.client;

import com.biyi.atlas.map.AtlasContent;
import com.biyi.atlas.map.AtlasRegion;
import net.minecraft.resources.ResourceLocation;

/**
 * 可见性**单一真源**：渲染、命中测试、悬停都必须调用这里，禁止各处自己拼判据。
 *
 * <p>改造前同一个问题有 5 种拼法（区域级/坐标级/作者旁路各写各的），导致
 * "看不见却点得中""作者也被区域过滤"这类不一致。现在收敛成三个询问：</p>
 *
 * <ul>
 *   <li>{@link #regionVisible(String)} —— 区域级：元素声明的区域是否解锁。
 *       <b>查不到区域 = 不可见</b>（fail-closed，修历史 fail-open：删除受保护区域后内容反而公开）。</li>
 *   <li>{@link #hiddenByLockedRegion(double, double, ResourceLocation, int)} —— 坐标级：
 *       点是否落在"会隐藏内容"的未解锁区域里。</li>
 *   <li>{@link #visible(String, double, double, boolean, ResourceLocation, int)} —— 元素级 =
 *       作者旁路 + 区域级 + 坐标级。</li>
 * </ul>
 */
public final class AtlasVisibility {

    /** 要素类型：不同类型的"未解锁区域封锁"由设置分别控制。 */
    public enum Kind {
        ROAD, NODE, PLACE, WAYPOINT, IMAGE, MARKER, REGION
    }

    private AtlasVisibility() {
    }

    /** 该类型在"未解锁区域"里是否要被封锁（读全局设置）。 */
    public static boolean kindLocked(Kind kind) {
        return switch (kind) {
            case ROAD, NODE -> AtlasSettings.lockRoads();
            case WAYPOINT -> AtlasSettings.lockWaypoints();
            case PLACE -> AtlasSettings.lockPlaces();
            case IMAGE -> AtlasSettings.lockImages();
            case MARKER, REGION -> true;
        };
    }

    /**
     * 区域级可见性。空 id = 不受区域约束（可见）；**id 查不到 = 不可见**。
     *
     * <p>fail-closed 很重要：服务端权威改造后，客户端本地可能没有该区域的定义，
     * 此时必须按"受保护"处理，否则锁区内容会被当成公开内容（X-01）。</p>
     */
    public static boolean regionVisible(String regionId) {
        if (regionId == null || regionId.isEmpty()) {
            return true;
        }
        if (AtlasServerView.authoritative()) {
            // A6 第②步：服务端权威集合优先（不在集合里 = 不可见），客户端不再自行推算
            return AtlasServerView.visible(regionId);
        }
        AtlasRegion region = AtlasContent.get().regionById(regionId);
        if (region == null) {
            return false;
        }
        return AtlasUnlocks.isVisible(region.id());
    }

    /**
     * 坐标级：点是否落在"会隐藏内容"的未解锁区域里。
     *
     * <p>语义（先按 Q-01，2026-10-10 依用户要求改为**分层锁**）：</p>
     * <ul>
     *   <li><b>顶层</b>未解锁区域 → 整块隐藏其内部内容（玩家在地图上只看到虚线边界与迷雾）；</li>
     *   <li><b>子区域</b>未解锁 → **默认也隐藏其内部内容**（`childLocksContent` 缺省 true，
     *       即"父解锁压不住未解锁的子区域"）；作者可对个别区域关掉这个开关，改成"锁边界但内容照显"。</li>
     * </ul>
     */
    public static boolean hiddenByLockedRegion(double worldX, double worldZ, ResourceLocation dimension,
                                               int layer) {
        // 坐标级判定**始终**生效：元素可能归属父区域（已解锁）但坐标落在未解锁的子区域内，
        // 只靠"区域 id 是否可见"会漏判（父解锁、子未解锁时子区域里的图标就露出来了）。
        // 客户端没有区域定义时循环为空 → 返回 false，不会误隐藏。
        for (AtlasRegion region : AtlasContent.get().regionsAt(dimension, layer)) {
            if (AtlasUnlocks.isVisible(region.id())) {
                continue;
            }
            if (!region.contains(worldX, worldZ)) {
                continue;
            }
            if (!region.isChild() || region.childLocksContent()) {
                return true;
            }
            // 未解锁的子区域且未要求锁内容 → 不隐藏；若它的祖先也锁着，由那个顶层区域兜住
        }
        return false;
    }

    /** 元素级：作者看得到一切；玩家必须同时通过区域级与该类型的封锁设置。 */
    public static boolean visible(Kind kind, String regionId, double worldX, double worldZ, boolean authorMode,
                                  ResourceLocation dimension, int layer) {
        if (authorMode) {
            return true;
        }
        if (!regionVisible(regionId)) {
            return false;
        }
        if (!kindLocked(kind)) {
            return true;   // 设置里关掉了"封锁此类要素"
        }
        return !hiddenByLockedRegion(worldX, worldZ, dimension, layer);
    }

    /** 兼容旧调用：默认按地点类处理。 */
    public static boolean visible(String regionId, double worldX, double worldZ, boolean authorMode,
                                  ResourceLocation dimension, int layer) {
        return visible(Kind.PLACE, regionId, worldX, worldZ, authorMode, dimension, layer);
    }
}
