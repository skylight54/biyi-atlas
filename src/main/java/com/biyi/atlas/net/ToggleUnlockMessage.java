package com.biyi.atlas.net;

import com.biyi.atlas.server.AtlasUnlockManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 → 服务端：请求解锁/锁定某个区域（作者在魔杖地图里点的"解锁/锁定"）。
 *
 * <p>历史问题：客户端只改本地文件，登录时被服务端账本整包覆盖 → "这次解锁了下次又没解锁"。
 * 现在客户端改动会**写回服务端账本**，并拿回服务端推送的最新集合。</p>
 */
public class ToggleUnlockMessage {

    private final String regionId;
    private final boolean unlocked;

    public ToggleUnlockMessage(String regionId, boolean unlocked) {
        this.regionId = regionId == null ? "" : regionId;
        this.unlocked = unlocked;
    }

    public static void encode(ToggleUnlockMessage message, FriendlyByteBuf buffer) {
        buffer.writeUtf(message.regionId, 256);
        buffer.writeBoolean(message.unlocked);
    }

    public static ToggleUnlockMessage decode(FriendlyByteBuf buffer) {
        return new ToggleUnlockMessage(buffer.readUtf(256), buffer.readBoolean());
    }

    public static void handle(ToggleUnlockMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || message.regionId.isEmpty()) {
                return;
            }
            // 权限：作者/管理员（创造或权限等级 2）才能改解锁状态
            if (!player.isCreative() && !player.hasPermissions(2)) {
                player.displayClientMessage(
                        Component.literal("[避役图志] 没有权限修改区域解锁状态"), true);
                return;
            }
            if (message.unlocked) {
                AtlasUnlockManager.grant(player, message.regionId);
            } else {
                AtlasUnlockManager.revoke(player, message.regionId);
            }
        });
        context.setPacketHandled(true);
    }
}
