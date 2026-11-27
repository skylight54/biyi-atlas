package com.biyi.atlas.client;

import com.biyi.atlas.BiyiAtlas;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 客户端命令（只在玩家本地执行）：把地图作者工具脚本化，打字即可用，不必开界面点按钮。
 *
 * <pre>
 * /biyi_atlas_local author              打开作者模式地图
 * /biyi_atlas_local capture &lt;名字&gt; [半径]  生成地点俯视图（默认 ±128）
 * /biyi_atlas_local import &lt;名字&gt; [图层]   从旅行地图导入（图层如 day/topo/Y=9）
 * /biyi_atlas_local external &lt;名字&gt;        导入 import 目录里的外部图片
 * /biyi_atlas_local npc [半径]             扫描附近 NPC 标成路标
 * /biyi_atlas_local layers                 列出可用的旅行地图图层
 * </pre>
 */
@Mod.EventBusSubscriber(modid = BiyiAtlas.MODID, value = Dist.CLIENT)
public final class AtlasClientCommands {

    private AtlasClientCommands() {
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("biyi_atlas_local")
                .then(Commands.literal("export_project").then(Commands.argument("name",StringArgumentType.word()).executes(c->{
                    if(!AtlasAuthoring.allowed())return 0;ClientEvents.uploadContent();
                    net.minecraft.client.Minecraft.getInstance().player.connection.sendCommand("biyi_atlas project export "+StringArgumentType.getString(c,"name"));return 1;
                })))
                .then(Commands.literal("preview_reveal").then(Commands.argument("id",StringArgumentType.string())
                        .executes(context->{
                            String id=StringArgumentType.getString(context,"id");
                            if(!AtlasAuthoring.allowed()){say("需要作者权限");return 0;}
                            if(com.biyi.atlas.map.AtlasContent.get().regionById(id)==null||!AtlasVisibility.regionVisible(id)||!AtlasUnlocks.isVisible(id)){say("请先选择已解锁的有效区域");return 0;}
                            AtlasUnlockReveal.announce(id);say("已排队预览解锁演出，不改解锁账本");return 1;
                        })))
                .then(Commands.literal("prepare_area").then(Commands.argument("id",StringArgumentType.string())
                        .executes(context->{say(AtlasMapSession.prepare(StringArgumentType.getString(context,"id")));return 1;})))
                .then(Commands.literal("cancel_prepare").executes(context->{com.biyi.atlas.net.AreaTerrainMessage.cancelPrepare();return 1;}))
                .then(Commands.literal("publish_area").then(Commands.argument("id",StringArgumentType.string())
                        .executes(context->{say(AtlasMapSession.publish(StringArgumentType.getString(context,"id")));return 1;})))
                .then(Commands.literal("load_area").then(Commands.argument("id",StringArgumentType.string())
                        .executes(context->{var area=com.biyi.atlas.map.AtlasContent.get().regionById(StringArgumentType.getString(context,"id"));
                            if(area!=null&&area.mapArea()&&AtlasUnlocks.isVisible(area.id())){AtlasMapSession.select(area);say("已切换区域："+area.name());}return 1;})))
                .then(Commands.literal("resurvey").executes(context -> {
                    say(TerrainSurveyor.refreshNearby());
                    return 1;
                }))
                .then(Commands.literal("author")
                        .executes(context -> {
                            if (!AtlasAuthoring.allowed()) {
                            context.getSource().sendFailure(net.minecraft.network.chat.Component.literal(
                                    "[避役图志] 你没有内容编辑权限（服务端判定），无法进入作者模式"));
                            return 0;
                        }
                        AtlasScreen.openAuthor();
                            return 1;
                        }))
                .then(Commands.literal("layers")
                        .executes(context -> {
                            say("可用图层：" + String.join(", ", JourneyMapImport.layers()));
                            return 1;
                        }))
                .then(Commands.literal("verify")
                        .executes(context -> {
                            // 存档一致性体检：只报不改（规则见 AtlasValidator）
                            com.biyi.atlas.map.AtlasValidator.Report report =
                                    com.biyi.atlas.map.AtlasValidator.run();
                            com.biyi.atlas.map.AtlasContent content = com.biyi.atlas.map.AtlasContent.get();
                            say("存档版本：文件 V" + content.dataVersion() + " · 区域 schema V"
                                    + content.regionsSchema() + " · 路网 schema V" + content.roadsSchema()
                                    + (content.isReadOnly() ? "（只读保护）" : ""));
                            say("一致性体检：" + report.summary());
                            int shown = 0;
                            for (com.biyi.atlas.map.AtlasValidator.Finding finding : report.findings()) {
                                if (shown++ >= 8) {
                                    say("… 其余问题见日志（共 " + report.findings().size() + " 项）");
                                    break;
                                }
                                say("[" + finding.ruleId() + "] " + finding.message()
                                        + (finding.refs().isEmpty() ? "" : " → "
                                                + String.join(", ", finding.refs())));
                            }
                            org.slf4j.LoggerFactory.getLogger("BiyiAtlas")
                                    .info("[BiyiAtlas] validator report:\n{}", report.render());
                            return report.hasErrors() ? 0 : 1;
                        }))
                .then(Commands.literal("npc")
                        .executes(context -> {
                            say(NpcScanner.scan(128));
                            return 1;
                        })
                        .then(Commands.argument("radius", IntegerArgumentType.integer(8, 512))
                                .executes(context -> {
                                    say(NpcScanner.scan(IntegerArgumentType.getInteger(context, "radius")));
                                    return 1;
                                }))));
    }

    private static int capture(String name, int radius) {
        say(LocationCapture.capture(radius, name));
        return 1;
    }

    private static int journey(String name, String layer) {
        if (layer != null && !layer.isEmpty()) {
            JourneyMapImport.setLayer(layer.startsWith("Y=") || isLayerFolder(layer) ? layer : layer);
        }
        net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
        if (minecraft.player == null) {
            return 0;
        }
        double radius = 128.0D;
        say(JourneyMapImport.importArea(name, minecraft.player.getX() - radius,
                minecraft.player.getZ() - radius, minecraft.player.getX() + radius,
                minecraft.player.getZ() + radius));
        return 1;
    }

    private static boolean isLayerFolder(String value) {
        return value.matches("-?\\d+") || JourneyMapImport.layers().contains(value);
    }

    private static void say(String text) {
        net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.literal("[避役图志] " + text), false);
        }
    }
}
