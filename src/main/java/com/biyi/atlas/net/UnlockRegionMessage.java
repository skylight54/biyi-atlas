package com.biyi.atlas.net;

import com.biyi.atlas.client.AtlasUnlocks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/**
 * 服务端 → 客户端：同步"该玩家已解锁的区域集合"（整包，服务端权威）。
 * 客户端把它当展示缓存写入本地文件。
 */
public class UnlockRegionMessage {

    private final List<String> regionIds;
    private final String announced;
    public String announcement(){return announced;}
    public List<String> regionIds(){return List.copyOf(regionIds);}

    public UnlockRegionMessage(Collection<String> regionIds) {
        this(regionIds, "");
    }

    public UnlockRegionMessage(Collection<String> regionIds,String announced) {
        this.regionIds = new ArrayList<>(regionIds);
        this.announced=announced;
    }

    public static void encode(UnlockRegionMessage message, FriendlyByteBuf buffer) {
        buffer.writeVarInt(message.regionIds.size());
        for (String id : message.regionIds) {
            buffer.writeUtf(id);
        }
        buffer.writeUtf(message.announced);
    }

    public static UnlockRegionMessage decode(FriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        List<String> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add(buffer.readUtf());
        }
        return new UnlockRegionMessage(ids,buffer.readUtf());
    }

    public static void handle(UnlockRegionMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> {
                    AtlasUnlocks.setAll(message.regionIds);
                    com.biyi.atlas.client.AtlasUnlockReveal.announce(message.announced);
                }));
        context.setPacketHandled(true);
    }
}
