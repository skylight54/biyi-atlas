package com.biyi.atlas.net;

import com.biyi.atlas.BiyiAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/** 服务端 → 客户端的两类消息：打开地图、解锁区域。 */
public final class AtlasNetwork {

    private static final String PROTOCOL = "5";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(BiyiAtlas.MODID, "main"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals);

    private AtlasNetwork() {
    }

    public static void register() {
        int id = 0;
        CHANNEL.registerMessage(100,AreaTerrainMessage.class,AreaTerrainMessage::encode,AreaTerrainMessage::decode,AreaTerrainMessage::handle);
        CHANNEL.registerMessage(id++, OpenAtlasMessage.class,
                OpenAtlasMessage::encode,
                OpenAtlasMessage::decode,
                OpenAtlasMessage::handle);
        CHANNEL.registerMessage(id++, UnlockRegionMessage.class,
                UnlockRegionMessage::encode,
                UnlockRegionMessage::decode,
                UnlockRegionMessage::handle);
        CHANNEL.registerMessage(id++, TeleportRequestMessage.class,
                TeleportRequestMessage::encode,
                TeleportRequestMessage::decode,
                TeleportRequestMessage::handle);
        CHANNEL.registerMessage(id++, GiveLicenseMessage.class,
                GiveLicenseMessage::encode,
                GiveLicenseMessage::decode,
                GiveLicenseMessage::handle);
        CHANNEL.registerMessage(id++, CaptureRequestMessage.class,
                CaptureRequestMessage::encode,
                CaptureRequestMessage::decode,
                CaptureRequestMessage::handle);
        CHANNEL.registerMessage(id++, ToggleUnlockMessage.class,
                ToggleUnlockMessage::encode,
                ToggleUnlockMessage::decode,
                ToggleUnlockMessage::handle);
        CHANNEL.registerMessage(id++, RegionVisibilityMessage.class,
                RegionVisibilityMessage::encode,
                RegionVisibilityMessage::decode,
                RegionVisibilityMessage::handle);
        CHANNEL.registerMessage(id++, ContentReloadMessage.class,
                ContentReloadMessage::encode,
                ContentReloadMessage::decode,
                ContentReloadMessage::handle);
        CHANNEL.registerMessage(id++, RegionGeometryMessage.class,
                RegionGeometryMessage::encode,
                RegionGeometryMessage::decode,
                RegionGeometryMessage::handle);
        CHANNEL.registerMessage(id++, AuthoringUploadMessage.class,
                AuthoringUploadMessage::encode,
                AuthoringUploadMessage::decode,
                AuthoringUploadMessage::handle);
        CHANNEL.registerMessage(id++, ElementGeometryMessage.class,
                ElementGeometryMessage::encode,
                ElementGeometryMessage::decode,
                ElementGeometryMessage::handle);
        CHANNEL.registerMessage(id, AuthoringStatusMessage.class,
                AuthoringStatusMessage::encode,
                AuthoringStatusMessage::decode,
                AuthoringStatusMessage::handle);
    }

    /**
     * 服务端下发**已按玩家过滤**的道路/地点（含道路逐段可见性掩码）。
     * 第一批带 reset=true，客户端据此清掉上一轮的服务端元素。
     */
    public static void sendElementGeometry(ServerPlayer player) {
        java.util.List<String> batches = com.biyi.atlas.server.AtlasElementService.buildBatches(player);
        for (int i = 0; i < batches.size(); i++) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new ElementGeometryMessage(i == 0, batches.get(i)));
        }
        if (batches.isEmpty()) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new ElementGeometryMessage(true, "[]"));
        }
    }

    /** 作者上传内容到服务端（压缩分块，A6 第②步）。 */
    public static void sendAuthoringUpload(byte[] content) {
        AuthoringUploadMessage.sendAll(content);
    }

    /** 服务端下发区域几何（A6 第②步：联机玩家无需作者本地内容即可看到区域）。 */
    public static void sendRegionGeometry(ServerPlayer player,
                                          java.util.Collection<com.biyi.atlas.server.AtlasRegionRegistry.Entry> entries) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), RegionGeometryMessage.of(entries));
    }

    /** 客户端告诉服务端"我的内容变了"（作者画完区域后调用，服务端重读并重算可见性）。 */
    public static void sendContentReload() {
        CHANNEL.sendToServer(new ContentReloadMessage());
    }

    /** 服务端告知客户端有无内容编辑权限（A6 第③步：非作者客户端将关闭本地写路径）。 */
    public static void sendAuthoringStatus(ServerPlayer player, boolean allowed) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new AuthoringStatusMessage(allowed));
    }

    /** 服务端下发"该玩家可见的区域集合"（A6 第②步：可见性权威在服务端）。 */
    public static void sendRegionVisibility(ServerPlayer player, java.util.Collection<String> visible) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new RegionVisibilityMessage(new java.util.ArrayList<>(visible)));
    }

    /** 客户端请求改写服务端账本里的解锁状态（作者在魔杖地图里点解锁/锁定）。 */
    public static void sendToggleUnlock(String regionId, boolean unlocked) {
        CHANNEL.sendToServer(new ToggleUnlockMessage(regionId, unlocked));
    }

    /** 让客户端以作者模式打开地图。 */
    public static void sendOpenAuthor(ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new OpenAtlasMessage(null, true));
    }

    /** 请求客户端生成一张地点俯视图（脚本/命令方块可用）。 */
    public static void sendCapture(ServerPlayer player, String name, int radius) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new CaptureRequestMessage(name, radius));
    }

    /** 请求传送到某个地图路标（服务端校验并落点）。 */
    public static void sendTeleportRequest(ResourceLocation dimension, double x, double z) {
        CHANNEL.sendToServer(new TeleportRequestMessage(dimension, x, z));
    }

    /** 请求服务端地作者发放一张绑定区域的许可证。 */
    public static void sendGiveLicense(String regionId) {
        CHANNEL.sendToServer(new GiveLicenseMessage(regionId));
    }

    /** 让该玩家打开地图；mapId 为空表示用默认地图。 */
    public static void sendOpen(ServerPlayer player, String mapId) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new OpenAtlasMessage(mapId));
    }

    /** 授权解锁某个区域（许可证/任务链用）。 */
    public static void sendUnlock(ServerPlayer player, String regionId) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new UnlockRegionMessage(java.util.List.of(regionId)));
    }

    /** 整包同步该玩家已解锁的区域集合（服务端权威）。 */
    public static void sendUnlockSync(ServerPlayer player, java.util.Collection<String> regionIds) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new UnlockRegionMessage(regionIds));
    }
}
