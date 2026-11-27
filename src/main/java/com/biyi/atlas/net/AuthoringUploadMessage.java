package com.biyi.atlas.net;

import com.biyi.atlas.server.AtlasRegionRegistry;
import com.biyi.atlas.server.AtlasServerStore;
import com.biyi.atlas.server.AtlasVisibilityService;
import com.mojang.logging.LogUtils;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.slf4j.Logger;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.zip.GZIPOutputStream;

/**
 * 客户端（作者）→ 服务端：**上传内容**（A6 第②步）。
 *
 * <p>为什么分块：区域/道路几何可以很大，单个自定义负载有大小上限。这里按 24 KB 分块传输，
 * 服务端按 {@code (玩家, 传输序号)} 组装，校验权限后原子写入
 * {@code <存档>/data/biyi_atlas_content.json}，随即重读注册表并把几何/可见性广播给所有玩家。</p>
 *
 * <p>权限：需要创造模式或权限等级 ≥ 2（与解锁命令一致）。不满足者静默拒绝并回执提示。</p>
 */
public class AuthoringUploadMessage {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int CHUNK_SIZE = 24 * 1024;

    private final int transferId;
    private final int index;
    private final int total;
    private final byte[] data;

    public AuthoringUploadMessage(int transferId, int index, int total, byte[] data) {
        this.transferId = transferId;
        this.index = index;
        this.total = total;
        this.data = data;
    }

    /** 把内容压缩后分块发出的辅助方法。 */
    public static void sendAll(byte[] content) {
        byte[] payload = gzip(content);
        int total = Math.max(1, (payload.length + CHUNK_SIZE - 1) / CHUNK_SIZE);
        int transferId = (int) (System.currentTimeMillis() & 0x7FFFFFFF);
        for (int index = 0; index < total; index++) {
            int from = index * CHUNK_SIZE;
            int to = Math.min(payload.length, from + CHUNK_SIZE);
            byte[] chunk = java.util.Arrays.copyOfRange(payload, from, to);
            AtlasNetwork.CHANNEL.sendToServer(new AuthoringUploadMessage(transferId, index, total, chunk));
        }
    }

    private static byte[] gzip(byte[] raw) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 3 + 16);
             GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(raw);
            gzip.finish();
            return out.toByteArray();
        } catch (Exception error) {
            return raw;   // 压缩失败就原样传（服务端会按 gzip 魔术字节判断是否解压）
        }
    }

    private static byte[] gunzip(byte[] data) {
        if (data.length < 2 || (data[0] & 0xFF) != 0x1F || (data[1] & 0xFF) != 0x8B) {
            return data;   // 不是 gzip，原样使用
        }
        try (java.util.zip.GZIPInputStream in =
                     new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(data))) {
            return in.readAllBytes();
        } catch (Exception error) {
            LOGGER.warn("[BiyiAtlas] 解压上传内容失败: {}", error.toString());
            return new byte[0];
        }
    }

    public static void encode(AuthoringUploadMessage message, FriendlyByteBuf buffer) {
        buffer.writeVarInt(message.transferId);
        buffer.writeVarInt(message.index);
        buffer.writeVarInt(message.total);
        buffer.writeByteArray(message.data);
    }

    public static AuthoringUploadMessage decode(FriendlyByteBuf buffer) {
        return new AuthoringUploadMessage(buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(),
                buffer.readByteArray());
    }

    public static void handle(AuthoringUploadMessage message, Supplier<NetworkEvent.Context> context) {
        NetworkEvent.Context ctx = context.get();
        ctx.enqueueWork(() -> receive(ctx.getSender(), message));
        ctx.setPacketHandled(true);
    }

    // ---------------------------------------------------------------- 服务端组装

    /** 正在进行的传输：玩家 uuid+transferId → 分块表。 */
    private static final Map<String, Map<Integer, byte[]>> TRANSFERS = new HashMap<>();

    private static void receive(ServerPlayer player, AuthoringUploadMessage message) {
        if (player == null) {
            return;
        }
        boolean allowed = player.isCreative() || player.hasPermissions(2);
        if (!allowed) {
            player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    "[避役图志] 只有作者（创造模式或权限 2）才能上传地图内容"), false);
            return;
        }
        if (message.total <= 0 || message.total > 4096 || message.index < 0 || message.index >= message.total) {
            return;
        }
        String key = player.getUUID() + "#" + message.transferId;
        Map<Integer, byte[]> chunks = TRANSFERS.computeIfAbsent(key, ignored -> new HashMap<>());
        chunks.put(message.index, message.data);
        if (chunks.size() < message.total) {
            return;   // 还没收齐
        }
        TRANSFERS.remove(key);

        ByteArrayOutputStream assembled = new ByteArrayOutputStream();
        for (int i = 0; i < message.total; i++) {
            byte[] chunk = chunks.get(i);
            if (chunk == null) {
                player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        "[避役图志] 上传缺少第 " + i + " 块，已取消"), false);
                return;
            }
            assembled.writeBytes(chunk);
        }
        byte[] raw = gunzip(assembled.toByteArray());
        if (raw.length == 0) {
            return;
        }
        MinecraftServer server = player.server;
        String existing=AtlasServerStore.readText(server);
        if(existing!=null&&java.util.Arrays.equals(raw,existing.getBytes(java.nio.charset.StandardCharsets.UTF_8)))return;
        if (!AtlasServerStore.write(server, raw)) {
            player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    "[避役图志] 服务端保存内容失败，请看日志"), false);
            return;
        }
        LOGGER.info("[BiyiAtlas] 作者 {} 上传内容：{} 字节（压缩后 {}）",
                player.getName().getString(), raw.length, assembled.size());

        // 重读注册表并把几何/可见性广播给所有玩家（新内容对所有人在线生效）
        AtlasRegionRegistry.reload(server);
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            AtlasNetwork.sendRegionVisibility(online, AtlasVisibilityService.visibleFor(online));
            AtlasNetwork.sendRegionGeometry(online, AtlasRegionRegistry.entries());
            AtlasNetwork.sendElementGeometry(online);
        }
        player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                "[避役图志] 内容已同步到服务端（" + AtlasRegionRegistry.size() + " 个区域）"), true);
    }

    /** 玩家断开时清掉未完成的传输。 */
    public static void forget(UUID playerId) {
        AreaTerrainMessage.forget(playerId);
        TRANSFERS.keySet().removeIf(key -> key.startsWith(playerId.toString() + "#"));
    }
}
