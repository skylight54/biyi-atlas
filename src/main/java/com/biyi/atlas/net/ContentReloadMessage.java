package com.biyi.atlas.net;

import com.biyi.atlas.server.AtlasRegionRegistry;
import com.biyi.atlas.server.AtlasVisibilityService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 → 服务端：**我这边的内容变了，请重读区域数据并重算我的可见性**。
 *
 * <p>为什么需要：服务端的区域注册表只在开服/文件变更时读。作者在游戏里新画一个区域后，
 * 若服务端还不知道它，就会把解锁请求当成"未知区域 id"拒掉，下发的可见集合里也没有它
 * → 新区域内的道路对玩家永远不可见（本轮修的 bug 2）。有了这个包，画完即时同步。</p>
 */
public class ContentReloadMessage {

    public static void encode(ContentReloadMessage message, FriendlyByteBuf buffer) {
        // 无字段
    }

    public static ContentReloadMessage decode(FriendlyByteBuf buffer) {
        return new ContentReloadMessage();
    }

    public static void handle(ContentReloadMessage message, Supplier<NetworkEvent.Context> context) {
        NetworkEvent.Context ctx = context.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null || (!player.isCreative() && !player.hasPermissions(2))) {
                return;
            }
            AtlasRegionRegistry.reload(player.server);
            AtlasNetwork.sendRegionVisibility(player, AtlasVisibilityService.visibleFor(player));
            AtlasNetwork.sendRegionGeometry(player, AtlasRegionRegistry.entries());
            AtlasNetwork.sendElementGeometry(player);
        });
        ctx.setPacketHandled(true);
    }
}
