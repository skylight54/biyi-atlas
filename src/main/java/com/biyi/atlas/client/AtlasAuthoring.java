package com.biyi.atlas.client;

import com.biyi.atlas.map.AtlasContent;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

/**
 * 客户端是否**被允许编辑内容**（A6 第③步）。
 *
 * <p>规则：默认允许（单人/离线世界，你就是服务端）；一旦服务端明确告知"不允许"
 * （非创造且权限 &lt; 2），则：退出作者模式、关闭本地内容写路径、拒绝本地作者命令。</p>
 */
public final class AtlasAuthoring {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile boolean allowed = true;
    private static boolean known;
    public static boolean known(){return known;}
    public static boolean confirmed(){return known && allowed;}

    private AtlasAuthoring() {
    }

    public static boolean allowed() {
        return allowed;
    }

    /** 服务端下发权限结果。 */
    public static void apply(boolean canAuthor) {
        known=true;
        boolean changed = allowed != canAuthor;
        allowed = canAuthor;
        AtlasContent.setInstanceWriteProtected(!canAuthor);
        AtlasServerContent.flushPending();
        if (!canAuthor) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft != null && minecraft.screen instanceof AtlasScreen screen && screen.isAuthorMode()) {
                AtlasScreen.open();
            }
        }
        if (changed) {
            LOGGER.info("[BiyiAtlas] 服务端授权：内容编辑{}", canAuthor ? "允许" : "不允许（已关闭本地写路径）");
        }
    }

    /** 断线恢复默认（单人/离线可用）。 */
    public static void reset() {
        known=false;
        allowed = true;
        AtlasContent.setInstanceWriteProtected(false);
    }
}
