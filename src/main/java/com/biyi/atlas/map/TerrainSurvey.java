package com.biyi.atlas.map;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.nbt.CompoundTag;

/** 四格采样网格，独立于历史 biome 调色板；有符号高度支持负 Y。 */
public final class TerrainSurvey {
    public static final int SPACING = 4;
    public static final int LAND = 0, WATER = 1, FOREST = 2, SNOW = 3, SAND = 4, LAVA = 5, BUILDING = 6;
    private final Long2IntOpenHashMap structures = new Long2IntOpenHashMap();
    public boolean hasStructureChunk(int cx,int cz) {
        for(int z=0;z<4;z++)for(int x=0;x<4;x++)
            if(!structures.containsKey(AtlasMapData.key(cx*4+x,cz*4+z)))return false;
        return true;
    }
    public void invalidateStructureChunk(int cx,int cz){
        for(int z=0;z<4;z++)for(int x=0;x<4;x++)structures.remove(AtlasMapData.key(cx*4+x,cz*4+z));
    }
    public void putStructure(int gx,int gz,int mask) {
        structures.put(AtlasMapData.key(gx,gz),mask&65535);
        for(int tz=Math.floorDiv(gz-2,32);tz<=Math.floorDiv(gz+2,32);tz++)
            for(int tx=Math.floorDiv(gx-2,32);tx<=Math.floorDiv(gx+2,32);tx++)
                tileRevisions.merge(AtlasMapData.key(tx,tz),1,Integer::sum);
    }
    public boolean buildingAt(int wx,int wz) {
        int mask=structures.get(AtlasMapData.key(Math.floorDiv(wx,4),Math.floorDiv(wz,4)));
        int bit=Math.floorMod(wz,4)*4+Math.floorMod(wx,4);
        return (mask&(1<<bit))!=0;
    }
    private final Long2IntOpenHashMap samples = new Long2IntOpenHashMap();
    private final java.util.Map<Long, Integer> tileRevisions = new java.util.HashMap<>();
    private final java.util.Set<Long> occupiedTiles = new java.util.HashSet<>();
    public boolean hasTile(int x, int z) { return occupiedTiles.contains(AtlasMapData.key(x, z)); }
    public TerrainSurvey tileSnapshot(int tx,int tz){
        TerrainSurvey copy=new TerrainSurvey();
        for(int z=tz*32-10;z<=tz*32+42;z++)for(int x=tx*32-10;x<=tx*32+42;x++){
            long key=AtlasMapData.key(x,z);
            if(samples.containsKey(key))copy.samples.put(key,samples.get(key));
            if(structures.containsKey(key))copy.structures.put(key,structures.get(key));
        }
        return copy;
    }
    public TerrainSurvey filtered(java.util.function.BiPredicate<Double,Double> allowed){
        TerrainSurvey copy=new TerrainSurvey();
        for(var e:samples.long2IntEntrySet()){
            int x=AtlasMapData.keyX(e.getLongKey()),z=AtlasMapData.keyZ(e.getLongKey());
            if(!allowed.test(x*4.0,z*4.0)||!allowed.test(x*4.0+4,z*4.0+4)
                    ||!allowed.test(x*4.0+4,z*4.0)||!allowed.test(x*4.0,z*4.0+4)
                    ||!allowed.test(x*4.0+2,z*4.0+2))continue;
            copy.samples.put(e.getLongKey(),e.getIntValue());
            if(structures.containsKey(e.getLongKey()))copy.structures.put(e.getLongKey(),structures.get(e.getLongKey()));
            copy.occupiedTiles.add(AtlasMapData.key(Math.floorDiv(x,32),Math.floorDiv(z,32)));
        }
        return copy;
    }
    public int tileRevision(int tx, int tz) { return tileRevisions.getOrDefault(AtlasMapData.key(tx, tz), 0); }
    public boolean has(int x, int z) { return samples.containsKey(AtlasMapData.key(x, z)); }
    public int get(int x, int z) { return samples.get(AtlasMapData.key(x, z)); }
    public int size() { return samples.size(); }
    /** 与哈希表容量/插入顺序无关，供展示图校验底图版本。 */
    public String fingerprint(){
        try{
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            long[] keys=samples.keySet().toLongArray();java.util.Arrays.sort(keys);
            java.nio.ByteBuffer buffer=java.nio.ByteBuffer.allocate(16);
            for(long key:keys){buffer.clear();buffer.putLong(key).putInt(samples.get(key)).putInt(structures.get(key));digest.update(buffer.array());}
            return java.util.HexFormat.of().formatHex(digest.digest());
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    /** 作者局部发布只能更新覆盖到的采样，不能抹掉服务端准备好的其余范围。 */
    public void mergeFrom(TerrainSurvey incoming){
        for(var e:incoming.samples.long2IntEntrySet()){
            int x=AtlasMapData.keyX(e.getLongKey()),z=AtlasMapData.keyZ(e.getLongKey());
            put(x,z,height(e.getIntValue()),kind(e.getIntValue()));
        }
        for(var e:incoming.structures.long2IntEntrySet())putStructure(AtlasMapData.keyX(e.getLongKey()),AtlasMapData.keyZ(e.getLongKey()),e.getIntValue());
    }
    /** 只清除世界高度范围外的异常地形样本，不涉及路标和作者数据。 */
    public int removeInvalidHeights(int minHeight, int maxHeight) {
        int before = samples.size();
        var entries = samples.long2IntEntrySet().iterator();
        while (entries.hasNext()) {
            int h = height(entries.next().getIntValue());
            if (h < minHeight || h >= maxHeight) entries.remove();
        }
        return before - samples.size();
    }
    public static int height(int sample) { return (short) sample; }
    public static int kind(int sample) { return (sample >>> 16) & 255; }
    public void put(int x, int z, int height, int kind) {
        occupiedTiles.add(AtlasMapData.key(Math.floorDiv(x, 32), Math.floorDiv(z, 32)));
        samples.put(AtlasMapData.key(x, z), (kind << 16) | (height & 65535));
        // 平滑会访问相邻两格，边界修改也须使邻图块失效。
        for (int tz = Math.floorDiv(z - 2, 32); tz <= Math.floorDiv(z + 2, 32); tz++)
            for (int tx = Math.floorDiv(x - 2, 32); tx <= Math.floorDiv(x + 2, 32); tx++)
                tileRevisions.merge(AtlasMapData.key(tx, tz), 1, Integer::sum);
    }
    public boolean hasChunk(int chunkX, int chunkZ) {
        for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++) {
            if (!has(chunkX * 4 + x, chunkZ * 4 + z)) return false;
        }
        return true;
    }
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        long[] keys = new long[samples.size()];
        int[] values = new int[keys.length];
        int i = 0;
        for (var entry : samples.long2IntEntrySet()) {
            keys[i] = entry.getLongKey();
            values[i++] = entry.getIntValue();
        }
        tag.putInt("version", 1);
        tag.putLongArray("keys", keys);
        tag.putIntArray("values", values);
        long[] buildingKeys=new long[structures.size()];
        int[] masks=new int[buildingKeys.length];
        int b=0;
        for(var entry:structures.long2IntEntrySet()){buildingKeys[b]=entry.getLongKey();masks[b++]=entry.getIntValue();}
        tag.putLongArray("buildingKeys",buildingKeys);
        tag.putIntArray("buildingMasks",masks);
        return tag;
    }
    public void load(CompoundTag tag) {
        samples.clear();
        structures.clear();
        occupiedTiles.clear();
        tileRevisions.clear();
        if (tag.getInt("version") != 1) return;
        long[] keys = tag.getLongArray("keys");
        int[] values = tag.getIntArray("values");
        for (int i = 0; i < Math.min(keys.length, values.length); i++) {
            samples.put(keys[i], values[i]);
            occupiedTiles.add(AtlasMapData.key(Math.floorDiv(AtlasMapData.keyX(keys[i]),32),
                    Math.floorDiv(AtlasMapData.keyZ(keys[i]),32)));
        }
        long[] buildingKeys=tag.getLongArray("buildingKeys");
        int[] masks=tag.getIntArray("buildingMasks");
        for(int i=0;i<Math.min(buildingKeys.length,masks.length);i++)structures.put(buildingKeys[i],masks[i]&65535);
    }
}
