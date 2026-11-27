package com.biyi.atlas.net;

import com.biyi.atlas.client.ClientBridge;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 让客户端打开地图界面。带上 mapId 是为了以后支持“作者指定的多张地图”；
 * 当前为空时由客户端选默认地图。
 */
public class OpenAtlasMessage {

    private final String mapId;
    private final boolean author;

    public OpenAtlasMessage(String mapId) {
        this(mapId, false);
    }

    public OpenAtlasMessage(String mapId, boolean author) {
        this.mapId = mapId;
        this.author = author;
    }

    public static void encode(OpenAtlasMessage message, FriendlyByteBuf buffer) {
        buffer.writeBoolean(message.mapId != null);
        if (message.mapId != null) {
            buffer.writeUtf(message.mapId);
        }
        buffer.writeBoolean(message.author);
    }

    public static OpenAtlasMessage decode(FriendlyByteBuf buffer) {
        String mapId = buffer.readBoolean() ? buffer.readUtf() : null;
        return new OpenAtlasMessage(mapId, buffer.readBoolean());
    }

    public static void handle(OpenAtlasMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> {
                    if (message.author) {
                        ClientBridge.openAuthorAtlas();
                    } else {
                        ClientBridge.openAtlas();
                    }
                }));
        context.setPacketHandled(true);
    }
}
