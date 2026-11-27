package com.biyi.atlas.net;

import com.biyi.atlas.BiyiAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 → 服务端：请求传送到某个地图路标（传送点）。
 * 服务端负责校验与落点安全（取地表高度），客户端不直接改坐标。
 */
public class TeleportRequestMessage {

    private final ResourceLocation dimension;
    private final double x;
    private final double z;

    /** 每个玩家的传送冷却（tick）。 */
    private static final java.util.Map<java.util.UUID, Long> LAST_TELEPORT = new java.util.concurrent.ConcurrentHashMap<>();

    public TeleportRequestMessage(ResourceLocation dimension, double x, double z) {
        this.dimension = dimension;
        this.x = x;
        this.z = z;
    }

    public static void encode(TeleportRequestMessage message, FriendlyByteBuf buffer) {
        buffer.writeResourceLocation(message.dimension);
        buffer.writeDouble(message.x);
        buffer.writeDouble(message.z);
    }

    public static TeleportRequestMessage decode(FriendlyByteBuf buffer) {
        return new TeleportRequestMessage(buffer.readResourceLocation(), buffer.readDouble(), buffer.readDouble());
    }

    public static void handle(TeleportRequestMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            MinecraftServer server = player.getServer();
            if (server == null) {
                return;
            }
            // 服务端校验：坐标必须有限且在合理范围内（否则会写入 NaN 或强加载极远区块）
            if (!Double.isFinite(message.x) || !Double.isFinite(message.z)
                    || Math.abs(message.x) > 3.0E7D || Math.abs(message.z) > 3.0E7D) {
                return;
            }
            // 冷却：每人 3 秒，避免被当成"强加载区块"工具刷服务器
            long now = server.getTickCount();
            Long last = LAST_TELEPORT.get(player.getUUID());
            if (last != null && now - last < 60L) {
                player.displayClientMessage(Component.literal("[避役图志] 传送太频繁，请稍候"), true);
                return;
            }
            LAST_TELEPORT.put(player.getUUID(), now);
            ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, message.dimension);
            ServerLevel level = server.getLevel(key);
            if (level == null) {
                player.displayClientMessage(Component.literal("[避役图志] 找不到维度 " + message.dimension), false);
                return;
            }
            BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    BlockPos.containing(message.x, level.getMinBuildHeight(), message.z));
            player.teleportTo(level, message.x + 0.5D, surface.getY() + 1.0D, message.z + 0.5D,
                    player.getYRot(), player.getXRot());
            player.displayClientMessage(Component.literal("[避役图志] 已传送到地图路标"), false);
        });
        context.setPacketHandled(true);
    }
}
