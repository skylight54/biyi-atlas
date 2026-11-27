package com.biyi.atlas.net;

import com.biyi.atlas.client.AtlasServerContent;
import com.biyi.atlas.server.AtlasRegionRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 服务端 → 客户端：**区域几何**（A6 第②步）。
 *
 * <p>让联机玩家**不需要作者的 {@code content.json}** 也能看到区域边界、层级与解锁态。
 * 坐标量化到 1/16 格（int）以省带宽；unlockId/unlockType 也带过去，客户端可做展示与预测。</p>
 */
public class RegionGeometryMessage {

    /** 一条区域的传输形态。 */
    public record Payload(String id, String name, String parent, String unlockType, String unlockId,
                          boolean defaultUnlocked, boolean mapArea, boolean childLocksContent,
                          int lineRgb, int fillRgb, int fillAlpha, double labelX, double labelZ,
                          boolean showOutlineWhenLocked, List<double[]> outline, String image,String dimension,int layer) {
    }

    private final List<Payload> regions;

    public RegionGeometryMessage(List<Payload> regions) {
        this.regions = regions;
    }
    public List<Payload> regions(){return regions;}

    public static RegionGeometryMessage of(java.util.Collection<AtlasRegionRegistry.Entry> entries) {
        List<Payload> payloads = new ArrayList<>(entries.size());
        for (AtlasRegionRegistry.Entry entry : entries) {
            payloads.add(new Payload(entry.id(), entry.name(), entry.parent(), entry.unlockType(),
                    entry.unlockId(), entry.defaultUnlocked(), entry.mapArea(), entry.childLocksContent(),
                    entry.lineRgb(), entry.fillRgb(), entry.fillAlpha(), entry.labelX(), entry.labelZ(),
                    entry.showOutlineWhenLocked(), entry.outline(), entry.image(),entry.dimension(),entry.layer()));
        }
        return new RegionGeometryMessage(payloads);
    }

    public static void encode(RegionGeometryMessage message, FriendlyByteBuf buffer) {
        buffer.writeVarInt(message.regions.size());
        for (Payload payload : message.regions) {
            buffer.writeUtf(payload.id());
            buffer.writeUtf(payload.name());
            buffer.writeUtf(payload.parent());
            buffer.writeUtf(payload.unlockType());
            buffer.writeUtf(payload.unlockId());
            buffer.writeBoolean(payload.defaultUnlocked());
            buffer.writeBoolean(payload.mapArea());
            buffer.writeBoolean(payload.childLocksContent());
            buffer.writeInt(payload.lineRgb());
            buffer.writeInt(payload.fillRgb());
            buffer.writeInt(payload.fillAlpha());
            buffer.writeDouble(payload.labelX());
            buffer.writeDouble(payload.labelZ());
            buffer.writeBoolean(payload.showOutlineWhenLocked());
            buffer.writeUtf(payload.image());
            buffer.writeUtf(payload.dimension());buffer.writeInt(payload.layer());
            buffer.writeVarInt(payload.outline().size());
            for (double[] point : payload.outline()) {
                buffer.writeVarInt((int) Math.round(point[0] * 16.0D));
                buffer.writeVarInt((int) Math.round(point[1] * 16.0D));
            }
        }
    }

    public static RegionGeometryMessage decode(FriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        if(count<0||count>10000)throw new IllegalArgumentException("区域数量超出限制");
        List<Payload> payloads = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String id = buffer.readUtf();
            String name = buffer.readUtf();
            String parent = buffer.readUtf();
            String unlockType = buffer.readUtf();
            String unlockId = buffer.readUtf();
            boolean defaultUnlocked = buffer.readBoolean();
            boolean mapArea = buffer.readBoolean();
            boolean childLocksContent = buffer.readBoolean();
            int lineRgb = buffer.readInt();
            int fillRgb = buffer.readInt();
            int fillAlpha = buffer.readInt();
            double labelX = buffer.readDouble();
            double labelZ = buffer.readDouble();
            boolean showOutlineWhenLocked = buffer.readBoolean();
            String image = buffer.readUtf();
            String dimension=buffer.readUtf();int layer=buffer.readInt();
            int points = buffer.readVarInt();
            if(points<0||points>100000)throw new IllegalArgumentException("区域顶点数量超出限制");
            List<double[]> outline = new ArrayList<>(points);
            for (int p = 0; p < points; p++) {
                outline.add(new double[]{buffer.readVarInt() / 16.0D, buffer.readVarInt() / 16.0D});
            }
            payloads.add(new Payload(id, name, parent, unlockType, unlockId, defaultUnlocked, mapArea,
                    childLocksContent, lineRgb, fillRgb, fillAlpha, labelX, labelZ,
                    showOutlineWhenLocked, outline, image,dimension,layer));
        }
        return new RegionGeometryMessage(payloads);
    }

    public static void handle(RegionGeometryMessage message, Supplier<NetworkEvent.Context> context) {
        NetworkEvent.Context ctx = context.get();
        ctx.enqueueWork(() -> AtlasServerContent.applyRegions(message.regions));
        ctx.setPacketHandled(true);
    }
}
