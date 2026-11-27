package com.biyi.atlas.command;

import com.biyi.atlas.BiyiAtlas;
import com.biyi.atlas.net.AtlasNetwork;
import com.biyi.atlas.server.AtlasUnlockManager;
import com.biyi.atlas.server.AtlasRegionRegistry;
import com.biyi.atlas.item.RegionLicenseItem;
import com.biyi.atlas.registry.ModItems;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 服务端命令，方便整合包作者和地图作者测试与脚本调用：
 * <ul>
 *     <li>{@code /biyi_atlas open} —— 让执行者打开地图</li>
 *     <li>{@code /biyi_atlas give [玩家]} —— 发放地图物品</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = BiyiAtlas.MODID)
public final class AtlasCommands {

    private AtlasCommands() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("biyi_atlas")
                .then(Commands.literal("project").requires(source -> source.hasPermission(2))
                        .then(Commands.literal("export").then(Commands.argument("name",StringArgumentType.word()).executes(c -> project(c.getSource(),StringArgumentType.getString(c,"name"),false))))
                        .then(Commands.literal("import").then(Commands.argument("name",StringArgumentType.word()).executes(c -> project(c.getSource(),StringArgumentType.getString(c,"name"),true)))))
                .then(Commands.literal("open")
                        .executes(context -> open(context.getSource().getPlayerOrException()))
                        .then(Commands.argument("target", EntityArgument.player())
                                .requires(source -> source.hasPermission(2))
                                .executes(context -> open(EntityArgument.getPlayer(context, "target")))))
                .then(Commands.literal("give")
                        .executes(context -> give(context.getSource().getPlayerOrException(), false))
                        .then(Commands.argument("target", EntityArgument.player())
                                .requires(source -> source.hasPermission(2))
                                .executes(context -> give(EntityArgument.getPlayer(context, "target"), true))))
                .then(Commands.literal("wand")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> wand(context.getSource().getPlayerOrException(), false))
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(context -> wand(EntityArgument.getPlayer(context, "target"), true))))
                .then(Commands.literal("author")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> author(context.getSource().getPlayerOrException(), false))
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(context -> author(EntityArgument.getPlayer(context, "target"), true))))
                .then(Commands.literal("capture")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(context -> capture(context.getSource().getPlayerOrException(), false,
                                        StringArgumentType.getString(context, "name"), 128))
                                .then(Commands.argument("radius", com.mojang.brigadier.arguments.IntegerArgumentType
                                                .integer(16, 512))
                                        .executes(context -> capture(context.getSource().getPlayerOrException(),
                                                false, StringArgumentType.getString(context, "name"),
                                                com.mojang.brigadier.arguments.IntegerArgumentType
                                                        .getInteger(context, "radius")))
                                        .then(Commands.argument("target", EntityArgument.player())
                                                .executes(context -> capture(
                                                        EntityArgument.getPlayer(context, "target"), true,
                                                        StringArgumentType.getString(context, "name"),
                                                        com.mojang.brigadier.arguments.IntegerArgumentType
                                                                .getInteger(context, "radius")))))))
                .then(Commands.literal("pointwand")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> pointWand(context.getSource().getPlayerOrException(), false))
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(context -> pointWand(EntityArgument.getPlayer(context, "target"), true))))
                .then(Commands.literal("unlock")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("region", StringArgumentType.string())
                                .suggests(AtlasCommands::suggestKnownRegions)
                                .executes(context -> unlock(context.getSource().getPlayerOrException(),
                                        StringArgumentType.getString(context, "region")))
                                .then(Commands.argument("target", EntityArgument.player())
                                        .executes(context -> unlock(EntityArgument.getPlayer(context, "target"),
                                                StringArgumentType.getString(context, "region"))))))
                .then(Commands.literal("license")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("region", StringArgumentType.string())
                                .suggests(AtlasCommands::suggestKnownRegions)
                                .executes(context -> license(context.getSource().getPlayerOrException(),
                                        StringArgumentType.getString(context, "region")))
                                .then(Commands.argument("target", EntityArgument.player())
                                        .executes(context -> license(EntityArgument.getPlayer(context, "target"),
                                                StringArgumentType.getString(context, "region")))))));
    }

    private static int project(net.minecraft.commands.CommandSourceStack source,String name,boolean install){
        try{
            if(!name.matches("[a-zA-Z0-9_-]{1,64}"))throw new IllegalArgumentException("工程名使用字母、数字、下划线，最多64字符");
            var server=source.getServer();
            var game=net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get();
            var world=server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize();
            var path=game.resolve("biyi_atlas/projects/"+name+".biyi.zip");
            if(install){
                if(server.isDedicatedServer())throw new IllegalArgumentException("当前工程导入用于单人/局域网主机；专服请停服后迁移工程数据并分发自定义资源");
                var backup=com.biyi.atlas.map.ProjectBundle.install(path,world,game);
                AtlasRegionRegistry.reload(server);
                String json=com.biyi.atlas.server.AtlasServerStore.readText(server);
                for(var p:server.getPlayerList().getPlayers()){
                    AtlasNetwork.sendRegionVisibility(p,com.biyi.atlas.server.AtlasVisibilityService.visibleFor(p));
                    AtlasNetwork.sendRegionGeometry(p,AtlasRegionRegistry.entries());AtlasNetwork.sendElementGeometry(p);
                    if(p.isCreative()||p.hasPermissions(2))com.biyi.atlas.net.AreaTerrainMessage.projectSnapshot(p,json);
                }
                source.sendSuccess(()->Component.literal("地图工程已导入；旧文件备份："+backup+"。重新进入世界以刷新自定义图标和底图缓存。"),false);
            }else{
                com.biyi.atlas.map.ProjectBundle.export(world,game,path);
                source.sendSuccess(()->Component.literal("完整地图工程已导出："+path+"（不含世界方块，需配合相同地图存档/模组资源）"),false);
            }
            return 1;
        }catch(Exception e){source.sendFailure(Component.literal("工程操作失败："+e.getMessage()));return 0;}
    }

    /**
     * 区域 id 补全。参数从 {@code word()} 改成 {@code string()} 是为了支持子区域的命名空间 id
     * （形如 {@code region_1/2}，含斜杠，{@code word()} 不接受）。
     *
     * <p>服务端没有区域定义（内容在客户端/资源包），所以这里补全的是**服务端账本里已有的 id**，
     * 至少把"已经解锁过的"列出来，避免手打错。</p>
     */
    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>
            suggestKnownRegions(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> context,
                                com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        try {
            ServerPlayer player = context.getSource().getPlayerOrException();
            // 先补服务端白名单里的真实区域 id（A6 第①步），再补该玩家已解锁的
            for (String id : AtlasRegionRegistry.ids()) {
                builder.suggest(id);
            }
            for (String id : AtlasUnlockManager.unlocked(player)) {
                builder.suggest(id);
            }
        } catch (Exception ignored) {
            // 控制台执行时没有玩家：只补白名单
            for (String id : AtlasRegionRegistry.ids()) {
                builder.suggest(id);
            }
        }
        return builder.buildFuture();
    }

    private static int author(ServerPlayer player, boolean other) {
        AtlasNetwork.sendOpenAuthor(player);
        player.displayClientMessage(Component.literal(other ? "已让该玩家以作者模式打开地图" : "以作者模式打开地图"), false);
        return 1;
    }

    private static int capture(ServerPlayer player, boolean other, String name, int radius) {
        AtlasNetwork.sendCapture(player, name, radius);
        player.displayClientMessage(Component.literal("[避役图志] 正在生成地点俯视图：" + name
                + "（±" + radius + "）"), false);
        return 1;
    }

    private static int pointWand(ServerPlayer player, boolean other) {
        ItemStack stack = new ItemStack(ModItems.WAYPOINT_WAND.get());
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
        player.displayClientMessage(Component.literal(other
                ? "已发放 1 支路标魔杖"
                : "已获得 1 支路标魔杖（右键 NPC 标注 · 潜行右键切类型 · 右键空气开地图）"), false);
        return 1;
    }

    private static int unlock(ServerPlayer player, String region) {        AtlasUnlockManager.grant(player, region);
        player.displayClientMessage(Component.literal("已解锁区域：" + region), false);
        return 1;
    }

    private static int license(ServerPlayer player, String region) {
        ItemStack stack = RegionLicenseItem.forRegion(ModItems.REGION_LICENSE.get(), region);
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
        player.displayClientMessage(Component.literal("已发放许可证：" + region), false);
        return 1;
    }

    private static int wand(ServerPlayer player, boolean other) {
        ItemStack stack = new ItemStack(ModItems.MAP_WAND.get());
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
        player.displayClientMessage(Component.literal(other
                ? "已发放 1 支地图魔杖"
                : "已获得 1 支地图魔杖（右键打开作者模式地图）"), false);
        return 1;
    }

    private static int open(ServerPlayer player) {
        AtlasNetwork.sendOpen(player, null);
        player.displayClientMessage(Component.literal("已为你打开避役地图"), false);
        return 1;
    }

    private static int give(ServerPlayer player, boolean other) {
        ItemStack stack = new ItemStack(ModItems.ATLAS.get());
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
        player.displayClientMessage(Component.literal(other
                ? "已发放 1 张避役地图"
                : "已获得 1 张避役地图"), false);
        return 1;
    }
}
