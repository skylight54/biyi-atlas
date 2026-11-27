package com.biyi.atlas.client;

import com.biyi.atlas.BiyiAtlas;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = BiyiAtlas.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class AtlasKeys {
    public static final KeyMapping OPEN = new KeyMapping("key.biyi_atlas.open",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M, "key.categories.biyi_atlas");

    private AtlasKeys() {}

    @SubscribeEvent
    public static void register(RegisterKeyMappingsEvent event) {
        event.register(OPEN);
    }
}
