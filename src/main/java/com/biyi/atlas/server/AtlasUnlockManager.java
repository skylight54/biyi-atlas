package com.biyi.atlas.server;

import com.biyi.atlas.BiyiAtlas;
import com.biyi.atlas.net.AtlasNetwork;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 服务端权威的区域解锁账本。
 *
 * <p>按调研结论：Forge 1.20.1 的 Capability **不会自动同步到客户端，也不随死亡保留**，
 * 所以不用 Capability；解锁集合存玩家持久 NBT（{@code ServerPlayer#getPersistentData()}，
 * 随玩家存档保存），客户端只做展示缓存，登录/变更时由服务端整包推送。</p>
 */
@Mod.EventBusSubscriber(modid = BiyiAtlas.MODID)
public final class AtlasUnlockManager {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String TAG_ROOT = "biyi_atlas";
    private static final String TAG_UNLOCKED = "unlocked";

    private AtlasUnlockManager() {
    }

    public static Set<String> unlocked(ServerPlayer player) {
        Set<String> result = new LinkedHashSet<>();
        ListTag list = player.getPersistentData().getCompound(TAG_ROOT).getList(TAG_UNLOCKED, 8);
        for (int i = 0; i < list.size(); i++) {
            result.add(list.getString(i));
        }
        return result;
    }

    /** 授权解锁并把最新集合推给该玩家。 */
    public static void grant(ServerPlayer player, String regionId) {
        if (regionId == null || regionId.isEmpty()) {
            return;
        }
        // A6 第①步：服务端白名单校验（历史实现是零校验，任何字符串都会进账本）
        // 先按文件修改时间刷新：作者刚画的新区域必须立刻被认识，否则会被当成未知 id 拒掉
        AtlasRegionRegistry.refreshIfStale(player.server);
        if (AtlasRegionRegistry.isLoaded() && !AtlasRegionRegistry.isKnown(regionId)) {
            LOGGER.warn("[BiyiAtlas] 拒绝解锁未知区域：player={}, region={}（白名单 {} 个）",
                    player.getName().getString(), regionId, AtlasRegionRegistry.size());
            player.displayClientMessage(Component.literal(
                    "[避役图志] 未知区域 id：" + regionId + "（服务端白名单里没有；请先导出内容或检查拼写）"), false);
            return;
        }
        Set<String> current = unlocked(player);
        if (!current.add(regionId)) {
            sync(player, current);
            return;
        }
        write(player, current);
        sync(player, current, regionId);
        LOGGER.info("[BiyiAtlas] unlock: {} → {}", player.getName().getString(), regionId);
    }

    public static void revoke(ServerPlayer player, String regionId) {
        Set<String> current = unlocked(player);
        if (current.remove(regionId)) {
            write(player, current);
            sync(player, current);
            LOGGER.info("[BiyiAtlas] lock: {} → {}", player.getName().getString(), regionId);
        }
    }

    /** 开服时把区域白名单读进内存（A6 第①步：服务端持有区域 id）。 */
    @SubscribeEvent
    public static void onServerStarted(net.minecraftforge.event.server.ServerStartedEvent event) {
        AtlasRegionRegistry.reload(event.getServer());
    }

    /** 开服前也读一次（命令可能早于 ServerStarted 被调用）。 */
    @SubscribeEvent
    public static void onServerAboutToStart(net.minecraftforge.event.server.ServerAboutToStartEvent event) {
        AtlasRegionRegistry.reload(event.getServer());
    }

    /** 玩家退出：清掉未完成的上传分块，避免内存泄漏。 */
    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            com.biyi.atlas.net.AuthoringUploadMessage.forget(player.getUUID());
        }
    }

    /** 登录时整包推送：换机器/清缓存/联机进服都能拿到正确状态。 */
    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sync(player, unlocked(player));
        }
    }

    @SubscribeEvent
    public static void onClone(PlayerEvent.Clone event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            // 死亡/换维度后重推一次，避免客户端缓存与账本脱节
            sync(player, unlocked(player));
        }
    }

    private static void write(ServerPlayer player, Set<String> ids) {
        CompoundTag root = player.getPersistentData().getCompound(TAG_ROOT);
        ListTag list = new ListTag();
        for (String id : ids) {
            list.add(StringTag.valueOf(id));
        }
        root.put(TAG_UNLOCKED, list);
        player.getPersistentData().put(TAG_ROOT, root);
    }

    private static void sync(ServerPlayer player, Set<String> ids) {
        sync(player,ids,"");
    }

    private static void sync(ServerPlayer player, Set<String> ids,String announced) {
        AtlasNetwork.CHANNEL.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(()->player),new com.biyi.atlas.net.UnlockRegionMessage(ids,announced));
        // A6 第②步：下发"该玩家可见的区域集合"，客户端据此决定区域级可见性
        AtlasNetwork.sendRegionVisibility(player, AtlasVisibilityService.visibleFor(player));
        // A6 第②步：同时下发区域几何（联机玩家没有作者本地内容也能看到边界）
        AtlasRegionRegistry.refreshIfStale(player.server);
        AtlasNetwork.sendRegionGeometry(player, AtlasRegionRegistry.entries());
        // A6 第②步收尾：按玩家过滤后的道路/地点（未解锁区域的要素根本不下发）
        AtlasNetwork.sendElementGeometry(player);
        // A6 第③步：告知有无编辑权限（非作者客户端将关闭本地写路径）
        AtlasNetwork.sendAuthoringStatus(player, player.isCreative() || player.hasPermissions(2));
    }
}
