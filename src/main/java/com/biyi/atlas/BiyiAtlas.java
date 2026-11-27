package com.biyi.atlas;

import com.biyi.atlas.net.AtlasNetwork;
import com.biyi.atlas.registry.ModCreativeTabs;
import com.biyi.atlas.registry.ModItems;
import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * 避役图志 (Biyi Atlas) —— 面向整合包地图作者的自定义羊皮纸地图模组。
 *
 * <p>玩法：探索自动绘制 + 手持地图物品右键打开全屏地图；样式与标记由作者配置。</p>
 */
@Mod(BiyiAtlas.MODID)
public class BiyiAtlas {

    public static final String MODID = "biyi_atlas";
    private static final Logger LOGGER = LogUtils.getLogger();

    public BiyiAtlas() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModItems.ITEMS.register(modBus);
        ModCreativeTabs.TABS.register(modBus);
        modBus.addListener(this::commonSetup);
        modBus.addListener(ModItems::addToCreativeTabs);
        MinecraftForge.EVENT_BUS.register(this);
        AtlasNetwork.register();
        LOGGER.info("[BiyiAtlas] mod constructed, MODID={}", MODID);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("[BiyiAtlas] common setup done");
    }
}
