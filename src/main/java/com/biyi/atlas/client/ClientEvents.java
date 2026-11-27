package com.biyi.atlas.client;

import com.biyi.atlas.BiyiAtlas;
import com.biyi.atlas.map.AtlasContent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = BiyiAtlas.MODID, value = Dist.CLIENT)
public final class ClientEvents {

    private ClientEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
        while (AtlasKeys.OPEN.consumeClick()) {
            if (client.screen == null && client.player != null && client.level != null) {
                AtlasScreen.openForMapKey();
            }
        }
        TerrainSurveyor.tick();
        AtlasMapSession.tick();
        AtlasUnlockReveal.tick();
        AtlasAiBridge.tick();
        AtlasHeldRenderer.tick();
        if (client.getConnection()!=null && AtlasAuthoring.allowed()&&contentSync.poll(System.currentTimeMillis())) {
            uploadContent();
        }
        AtlasStorage.tickAutosave();
        AtlasRoadRecorder.tick();
    }

    /** Intercept use before block/item interactions, including a map held in the offhand. */
    @SubscribeEvent
    public static void onUseMapSettings(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isUseItem()) return;
        var client = net.minecraft.client.Minecraft.getInstance();
        // Forge sends a use event for each hand; consume the second one after opening too.
        if (client.screen instanceof AtlasHeldSettingsScreen) {
            event.setCanceled(true);
            event.setSwingHand(false);
            return;
        }
        if (client.screen != null || client.player == null || client.level == null
                || !client.options.getCameraType().isFirstPerson()
                || !client.options.keyShift.isDown() || !AtlasHeldRenderer.holdingMap(client.player)) return;
        event.setCanceled(true);
        event.setSwingHand(false);
        client.setScreen(new AtlasHeldSettingsScreen());
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        contentSync.reset();
        uploaded.reset();
        AtlasContentReloadListener.reloadNow();
        // 把磁盘上已生成的地点俯视图重新注册成贴图（否则地图上是紫黑格）
        // 映像图停用，不再加载旧大图贴图。
        CustomIcons.loadAll();
        // 内容保存后通知服务端重读（否则新画的区域服务端不认识 → 解锁被拒、玩家看不到里面道路）
        AtlasContent.setInstanceSaveListener(() -> {
            net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
            if (minecraft == null || minecraft.getConnection() == null) {
                return;
            }
            if(AtlasAuthoring.allowed())contentSync.changed();
        });
    }

    private static final com.biyi.atlas.map.CoalescedSave contentSync=new com.biyi.atlas.map.CoalescedSave();
    private static final com.biyi.atlas.map.ContentFingerprint uploaded=new com.biyi.atlas.map.ContentFingerprint();
    public static void uploadContent(){
        byte[] bytes=AtlasContent.get().exportAuthoredBytes();
        if(!uploaded.changed(bytes))return;
        com.biyi.atlas.net.AtlasNetwork.sendAuthoringUpload(bytes);
        uploaded.sent(bytes);
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        AtlasContent.setInstanceSaveListener(null);
        contentSync.reset();
        uploaded.reset();
        AtlasMapSession.reset();
        AtlasUnlockReveal.clear();
        AtlasFogRenderer.reset();
        AtlasHeldRenderer.reset();
        AtlasTerrainTiles.reset();
        TerrainSurveyor.reset();
        AtlasContent.get().saveAuthored();
        AtlasStorage.flushAndReset();
        AtlasTracker.clear();
        AtlasRoute.clear();                  // 路线不参与存档，换世界即清（避开 Clairvoyance 的存档残留坑）
        AtlasServerView.clear();             // 服务端下发的可见性只对当前连接有效
        AtlasServerContent.clear();          // 服务端下发的区域几何同理
        AtlasAuthoring.reset();              // 权限状态只对当前连接有效
        AtlasRoadRecorder.resetForLogout();   // 记录器静态状态跨存档残留（R2-D14）
    }
}
