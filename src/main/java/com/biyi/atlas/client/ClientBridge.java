package com.biyi.atlas.client;

/**
 * 服务端/客户端之间的安全跳板：主逻辑（物品等）不直接引用客户端类，
 * 由客户端初始化时把「打开地图界面」的实现注入进来。
 */
public final class ClientBridge {

    private static Runnable atlasOpener = () -> {
    };
    private static Runnable authorOpener = () -> {
    };
    private static java.util.function.Consumer<net.minecraft.world.entity.Entity> waypointPlacer = entity -> {
    };
    private static java.util.function.Consumer<net.minecraft.world.item.ItemStack> typeCycler = stack -> {
    };
    private static java.util.function.Function<net.minecraft.world.entity.Entity, String> bestiaryAdder =
            entity -> "";

    private ClientBridge() {
    }

    public static void setBestiaryAdder(
            java.util.function.Function<net.minecraft.world.entity.Entity, String> adder) {
        bestiaryAdder = adder;
    }

    /** 图鉴魔杖右键 NPC：把怪物加入所在区域的图鉴。 */
    public static void addBestiary(net.minecraft.world.entity.Entity entity) {
        String result = bestiaryAdder.apply(entity);
        net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
        if (minecraft.player != null && result != null && !result.isEmpty()) {
            minecraft.player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal("[避役图志] " + result), false);
        }
    }

    public static void setWaypointHooks(
            java.util.function.Consumer<net.minecraft.world.entity.Entity> placer,
            java.util.function.Consumer<net.minecraft.world.item.ItemStack> cycler) {
        waypointPlacer = placer;
        typeCycler = cycler;
    }

    /** 右键实体时在地图上标注路标。 */
    public static void placeWaypoint(net.minecraft.world.entity.Entity entity) {
        waypointPlacer.accept(entity);
    }

    /** 切换路标魔杖当前类型。 */
    public static void cycleWaypointType(net.minecraft.world.item.ItemStack stack) {
        typeCycler.accept(stack);
    }

    public static void setAtlasOpener(Runnable opener) {
        atlasOpener = opener;
    }

    public static void setAuthorOpener(Runnable opener) {
        authorOpener = opener;
    }

    /** 普通地图（玩家）。 */
    public static void openAtlas() {
        atlasOpener.run();
    }

    /** 作者模式地图（地图魔杖，仅创造/管理员）。 */
    public static void openAuthorAtlas() {
        authorOpener.run();
    }
}
