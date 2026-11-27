package com.biyi.atlas.client;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 客户端持有的**服务端权威可见性**（A6 第②步）。
 *
 * <p>服务端登录/解锁变更时会下发"该玩家可见的区域 id 集合"；收到后
 * {@link #authoritative()} 为 true，此后区域级可见性一律以该集合为准（**不在集合里 = 不可见**）。
 * 断开连接即失效，退回本地判定（单人作者模式仍用本地）。</p>
 */
public final class AtlasServerView {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Set<String> VISIBLE = new LinkedHashSet<>();
    private static volatile boolean authoritative;

    private AtlasServerView() {
    }

    public static void apply(Set<String> visible) {
        VISIBLE.clear();
        if (visible != null) {
            VISIBLE.addAll(visible);
        }
        authoritative = true;
        LOGGER.info("[BiyiAtlas] 收到服务端可见性：{} 个区域（此后区域级可见性由服务端权威决定）", VISIBLE.size());
    }

    public static void clear() {
        VISIBLE.clear();
        authoritative = false;
    }

    public static boolean authoritative() {
        return authoritative;
    }

    public static boolean visible(String regionId) {
        if (regionId == null || regionId.isEmpty()) {
            return true;
        }
        return VISIBLE.contains(regionId);
    }

    public static Set<String> ids() {
        return Collections.unmodifiableSet(VISIBLE);
    }

    public static int size() {
        return VISIBLE.size();
    }
}
