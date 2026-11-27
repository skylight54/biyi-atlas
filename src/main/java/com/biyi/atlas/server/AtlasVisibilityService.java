package com.biyi.atlas.server;

import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 服务端**按玩家计算区域可见性**（A6 第②步）。
 *
 * <p>规则（与客户端历史的 {@code AtlasUnlocks.isVisible} 保持一致，但现在由服务端权威决定）：</p>
 * <ul>
 *   <li>自身：{@code defaultUnlocked} 或 {@code unlockType=always} → 可见；
 *       或 {@code id} / {@code unlockId} 在该玩家账本里 → 可见（{@code advancement} 型暂按不可见，
 *       因为服务端要读进度需要额外查询，第③步再补）；</li>
 *   <li>祖先链：任一祖先不可见 → 自己也视为不可见（顶层锁住则整棵子树隐藏）；</li>
 *   <li>分层锁：未解锁的子区域**不在集合里** → 其内容对玩家隐藏（F-21 的服务端版本）。</li>
 * </ul>
 *
 * <p>客户端拿到这个集合后**不再自行推算**：不在集合里的区域，其内容一律不显示（fail-closed）。</p>
 */
public final class AtlasVisibilityService {

    private AtlasVisibilityService() {
    }

    public static Set<String> visibleFor(ServerPlayer player) {
        AtlasRegionRegistry.refreshIfStale(player.server);   // 内容可能刚变（作者新画了区域）
        Set<String> unlocked = AtlasUnlockManager.unlocked(player);
        Set<String> result = new LinkedHashSet<>();
        for (AtlasRegionRegistry.Entry entry : AtlasRegionRegistry.entries()) {
            if (selfVisible(entry, unlocked) && ancestorsVisible(entry, unlocked)) {
                result.add(entry.id());
            }
        }
        return result;
    }

    private static boolean selfVisible(AtlasRegionRegistry.Entry entry, Set<String> unlocked) {
        if (entry.defaultUnlocked() || "always".equals(entry.unlockType())) {
            return true;
        }
        return unlocked.contains(entry.id()) || unlocked.contains(entry.unlockId());
    }

    private static boolean ancestorsVisible(AtlasRegionRegistry.Entry entry, Set<String> unlocked) {
        List<String> chain = AtlasRegionRegistry.ancestors(entry.id());
        for (String ancestorId : chain) {
            AtlasRegionRegistry.Entry ancestor = AtlasRegionRegistry.entry(ancestorId);
            if (ancestor == null) {
                // 悬空父级（父已被删）→ 按顶层处理，避免子区域永久不可见
                return true;
            }
            if (!selfVisible(ancestor, unlocked)) {
                return false;
            }
        }
        return true;
    }
}
