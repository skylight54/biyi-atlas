package com.biyi.atlas.client;

import com.biyi.atlas.map.AtlasContent;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

/** 资源包重载（含 /reload）时重建地图内容：内置 → config → 游戏内编辑。 */
public class AtlasContentReloadListener extends SimplePreparableReloadListener<Void> {

    public static final AtlasContentReloadListener INSTANCE = new AtlasContentReloadListener();

    private AtlasContentReloadListener() {
    }

    @Override
    protected Void prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        return null;
    }

    @Override
    protected void apply(Void unused, ResourceManager resourceManager, ProfilerFiller profiler) {
        AtlasFogRenderer.reset();
        AtlasContent.get().reload(resourceManager, AtlasStorage.currentWorldKey());
    }

    /** 进入世界后再重建一次：这时才知道世界标识，能读到对应世界的游戏内编辑内容。 */
    public static void reloadNow() {
        Minecraft minecraft = Minecraft.getInstance();
        String worldKey = AtlasStorage.currentWorldKey();
        AtlasContent.get().reload(minecraft.getResourceManager(), worldKey);
        AtlasUnlocks.onWorldLoaded(worldKey);
    }
}
