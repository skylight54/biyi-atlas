package com.biyi.atlas.client;

import com.biyi.atlas.BiyiAtlas;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = BiyiAtlas.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ClientSetup {

    private ClientSetup() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        ClientBridge.setAtlasOpener(AtlasScreen::open);
        ClientBridge.setAuthorOpener(AtlasScreen::openAuthor);
        ClientBridge.setWaypointHooks(AtlasWaypoints::placeAt, AtlasWaypoints::cycleType);
        ClientBridge.setBestiaryAdder(AtlasBestiary::addEntry);
    }

    @SubscribeEvent
    public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(AtlasStyle.INSTANCE);
        event.registerReloadListener(AtlasContentReloadListener.INSTANCE);
    }
}
