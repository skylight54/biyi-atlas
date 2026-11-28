package com.biyi.atlas.client;

import com.biyi.atlas.map.AtlasMapData;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import java.io.IOException;
import java.nio.file.*;

/** Private pins share the waypoint codec and deletion history with the original map data. */
final class PlayerPinStore {
    private final Path file;
    final AtlasMapData data;

    private static PlayerPinStore session;
    private static Object level;
    private static ResourceLocation sessionDimension;

    static PlayerPinStore forSession(Minecraft client, ResourceLocation dimension) throws IOException {
        if (session == null || level != client.level || !dimension.equals(sessionDimension)) {
            session = new PlayerPinStore(client, dimension);
            level = client.level;
            sessionDimension = dimension;
        }
        return session;
    }

    static void clearSession() {
        session = null;
        level = null;
        sessionDimension = null;
    }

    private PlayerPinStore(Minecraft client, ResourceLocation dimension) throws IOException {
        this(client.gameDirectory.toPath().resolve("biyi_atlas/data"),AtlasStorage.currentWorldKey(),client.player.getUUID(),dimension);
    }

    PlayerPinStore(Path base,String world,java.util.UUID owner,ResourceLocation dimension) throws IOException {
        file = base.resolve(world)
                .resolve("players").resolve(owner.toString()).resolve(dimension.getNamespace())
                .resolve(dimension.getPath().replace("/", "%2f") + ".nbt");
        data = Files.exists(file) ? AtlasMapData.load(NbtIo.readCompressed(file.toFile())) : new AtlasMapData();
    }

    void save() throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), "pins-", ".nbt");
        try {
            NbtIo.writeCompressed(data.save(), temp.toFile());
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
