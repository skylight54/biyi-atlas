package com.biyi.atlas.client;

import net.minecraft.resources.ResourceLocation;
import java.nio.file.Files;
import java.util.UUID;

public final class PlayerPinChecks {
    public static void main(String[] args) throws Exception {
        var root = Files.createTempDirectory("atlas-pins");
        var owner = UUID.randomUUID();
        var dim = ResourceLocation.tryParse("minecraft:overworld");
        var store = new PlayerPinStore(root,"world",owner,dim);
        var pin = store.data.addWaypoint("gate",-12,81);
        store.save();
        var loaded = new PlayerPinStore(root,"world",owner,dim);
        if (!loaded.data.waypoints().contains(pin)) throw new AssertionError("reload");
        if (!new PlayerPinStore(root,"world",UUID.randomUUID(),dim).data.waypoints().isEmpty()
                || !new PlayerPinStore(root,"other",owner,dim).data.waypoints().isEmpty()
                || !new PlayerPinStore(root,"world",owner,ResourceLocation.tryParse("minecraft:the_nether")).data.waypoints().isEmpty())
            throw new AssertionError("scope");
        loaded.data.removeWaypoint(pin);
        loaded.save();
        var deleted = new PlayerPinStore(root,"world",owner,dim);
        if (!deleted.data.waypoints().isEmpty() || !deleted.data.restoreDeletedWaypoint()
                || !deleted.data.waypoints().contains(pin)) throw new AssertionError("restore");
        System.out.println("PlayerPinChecks PASS");
    }
}
