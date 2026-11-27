package com.biyi.atlas.map;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 单个维度的探索数据：每个区块记录「地图色 id + 地表高度」，外加玩家标的路标点。
 *
 * <p>packed = (colorId &lt;&lt; 16) | (height &amp; 0xFFFF)</p>
 */
public class AtlasMapData {
    private final TerrainSurvey terrain = new TerrainSurvey();
    public TerrainSurvey terrain() { return terrain; }

    private final Long2IntMap chunks = new Long2IntOpenHashMap();
    private final List<AtlasWaypoint> waypoints = new ArrayList<>();
    private final List<CompoundTag> deletedWaypoints = new ArrayList<>();
    /** 生物群系调色板：区块里存的是这里的下标（Antique Atlas 用同样的调色板思路）。 */
    private final List<String> biomePalette = new ArrayList<>();
    /** 玩家置顶的路标（key = 名称@x,z）。 */
    private final List<String> pinned = new ArrayList<>();

    public AtlasMapData() {
        chunks.defaultReturnValue(0);
    }

    public static long key(int chunkX, int chunkZ) {
        return ((long) chunkX & 0xFFFFFFFFL) | (((long) chunkZ & 0xFFFFFFFFL) << 32);
    }

    public static int keyX(long key) {
        return (int) key;
    }

    public static int keyZ(long key) {
        return (int) (key >> 32);
    }

    public static int pack(int colorId, int height) {
        return ((colorId & 0xFF) << 16) | (height & 0xFFFF);
    }

    public static int colorId(int packed) {
        return (packed >>> 16) & 0xFF;
    }

    public static int height(int packed) {
        return packed & 0xFFFF;
    }

    public boolean has(int chunkX, int chunkZ) {
        return chunks.containsKey(key(chunkX, chunkZ));
    }

    public int get(int chunkX, int chunkZ) {
        return chunks.get(key(chunkX, chunkZ));
    }

    public void put(int chunkX, int chunkZ, int colorId, int height) {
        chunks.put(key(chunkX, chunkZ), pack(colorId, Math.max(0, Math.min(300, height))));
    }

    public Long2IntMap chunks() {
        return chunks;
    }

    // ------------------------------------------------------------ 生物群系调色板

    /** 取（或新增）生物群系的调色板下标；上限 255，超出的一律并到最后一格。 */
    public int biomeIndex(String biomeId) {
        int index = biomePalette.indexOf(biomeId);
        if (index >= 0) {
            return index;
        }
        if (biomePalette.size() >= 256) {
            return 255;
        }
        biomePalette.add(biomeId);
        return biomePalette.size() - 1;
    }

    public List<String> biomePalette() {
        return Collections.unmodifiableList(biomePalette);
    }

    public String biomeAt(int index) {
        if (index < 0 || index >= biomePalette.size()) {
            return "";
        }
        return biomePalette.get(index);
    }

    // ------------------------------------------------------------ 置顶

    public static String pinKey(AtlasWaypoint waypoint) {
        return waypoint.name() + "@" + (int) waypoint.x() + "," + (int) waypoint.z();
    }

    public boolean isPinned(AtlasWaypoint waypoint) {
        return pinned.contains(pinKey(waypoint));
    }

    public void togglePin(AtlasWaypoint waypoint) {
        String key = pinKey(waypoint);
        if (!pinned.remove(key)) {
            pinned.add(key);
        }
    }

    /** 路标列表：置顶的排前面。 */
    public List<AtlasWaypoint> sortedWaypoints() {
        List<AtlasWaypoint> result = new ArrayList<>(waypoints);
        result.sort((a, b) -> {
            boolean pinA = isPinned(a);
            boolean pinB = isPinned(b);
            if (pinA != pinB) {
                return pinA ? -1 : 1;
            }
            return a.name().compareTo(b.name());
        });
        return result;
    }

    public int size() {
        return chunks.size();
    }

    public boolean isEmpty() {
        return chunks.isEmpty() && waypoints.isEmpty();
    }

    // ---------------------------------------------------------------- 路标点

    public List<AtlasWaypoint> waypoints() {
        return Collections.unmodifiableList(waypoints);
    }

    public AtlasWaypoint addWaypoint(String name, double x, double z) {
        AtlasWaypoint waypoint = AtlasWaypoint.of(name, x, z);
        waypoints.add(waypoint);
        return waypoint;
    }

    public AtlasWaypoint addWaypoint(String name, String type, double x, double z, String description) {
        AtlasWaypoint waypoint = new AtlasWaypoint(name, type, 0, x, z,
                WaypointType.byId(type).icon(), description);
        waypoints.add(waypoint);
        return waypoint;
    }

    public void replaceWaypoint(AtlasWaypoint oldWaypoint, AtlasWaypoint newWaypoint) {
        int index = waypoints.indexOf(oldWaypoint);
        if (index >= 0) {
            waypoints.set(index, newWaypoint);
        }
    }

    /** 找到列表中对应的路标（可能是替换后的新对象）。 */
    public AtlasWaypoint findWaypoint(AtlasWaypoint waypoint) {
        int index = waypoints.indexOf(waypoint);
        if (index >= 0) {
            return waypoints.get(index);
        }
        for (AtlasWaypoint candidate : waypoints) {
            if (candidate.name().equals(waypoint.name())
                    && Math.abs(candidate.x() - waypoint.x()) < 0.001D
                    && Math.abs(candidate.z() - waypoint.z()) < 0.001D) {
                return candidate;
            }
        }
        return null;
    }

    /** 附近是否已经有同名路标（用于 NPC 批量扫描去重）。 */
    public boolean hasWaypointAt(String name, double x, double z) {
        for (AtlasWaypoint waypoint : waypoints) {
            if (waypoint.name().equals(name)
                    && Math.abs(waypoint.x() - x) < 8.0D && Math.abs(waypoint.z() - z) < 8.0D) {
                return true;
            }
        }
        return false;
    }

    public boolean removeWaypoint(AtlasWaypoint waypoint) {
        int index=waypoints.indexOf(waypoint);
        if(index<0)return false;
        CompoundTag removed=waypoint.save();removed.putInt("originalIndex",index);
        removed.putBoolean("wasPinned",pinned.remove(pinKey(waypoint)));
        deletedWaypoints.add(removed);
        while(deletedWaypoints.size()>50)deletedWaypoints.remove(0);
        waypoints.remove(index);return true;
    }

    public boolean restoreDeletedWaypoint() {
        if(deletedWaypoints.isEmpty())return false;
        var tag=deletedWaypoints.get(deletedWaypoints.size()-1);var waypoint=AtlasWaypoint.load(tag);
        if(findWaypoint(waypoint)!=null)throw new IllegalStateException("同位置同名路标已存在，未重复恢复");
        waypoints.add(Math.max(0,Math.min(waypoints.size(),tag.getInt("originalIndex"))),waypoint);
        if(tag.getBoolean("wasPinned")&&!pinned.contains(pinKey(waypoint)))pinned.add(pinKey(waypoint));
        deletedWaypoints.remove(deletedWaypoints.size()-1);return true;
    }

    public int nextWaypointIndex() {
        return waypoints.size() + 1;
    }

    // ------------------------------------------------------------------ NBT

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.put("terrainSurvey", terrain.save());
        int n = chunks.size();
        long[] keys = new long[n];
        int[] values = new int[n];
        int i = 0;
        for (Long2IntMap.Entry entry : chunks.long2IntEntrySet()) {
            keys[i] = entry.getLongKey();
            values[i] = entry.getIntValue();
            i++;
        }
        tag.putLongArray("keys", keys);
        tag.putIntArray("values", values);
        tag.putInt("size", n);

        ListTag list = new ListTag();
        for (AtlasWaypoint waypoint : waypoints) {
            list.add(waypoint.save());
        }
        tag.put("waypoints", list);
        ListTag deleted = new ListTag();
        for(var removed:deletedWaypoints)deleted.add(removed.copy());
        tag.put("deletedWaypoints",deleted);

        ListTag biomes = new ListTag();
        for (String biome : biomePalette) {
            biomes.add(StringTag.valueOf(biome));
        }
        tag.put("biomePalette", biomes);

        ListTag pins = new ListTag();
        for (String pin : pinned) {
            pins.add(StringTag.valueOf(pin));
        }
        tag.put("pinned", pins);
        return tag;
    }

    public static AtlasMapData load(CompoundTag tag) {
        AtlasMapData data = new AtlasMapData();
        data.terrain.load(tag.getCompound("terrainSurvey"));
        long[] keys = tag.getLongArray("keys");
        int[] values = tag.getIntArray("values");
        int n = Math.min(keys.length, values.length);
        for (int i = 0; i < n; i++) {
            data.chunks.put(keys[i], values[i]);
        }
        ListTag list = tag.getList("waypoints", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            data.waypoints.add(AtlasWaypoint.load(list.getCompound(i)));
        }
        ListTag deleted=tag.getList("deletedWaypoints",Tag.TAG_COMPOUND);
        for(int i=Math.max(0,deleted.size()-50);i<deleted.size();i++)data.deletedWaypoints.add(deleted.getCompound(i).copy());
        ListTag biomes = tag.getList("biomePalette", Tag.TAG_STRING);
        for (int i = 0; i < biomes.size(); i++) {
            data.biomePalette.add(biomes.getString(i));
        }
        ListTag pins = tag.getList("pinned", Tag.TAG_STRING);
        for (int i = 0; i < pins.size(); i++) {
            data.pinned.add(pins.getString(i));
        }
        return data;
    }
}
