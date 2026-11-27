package com.biyi.atlas.map;

import java.util.List;
/** 新展示成品图的坐标与版本约束，与旧映像图系统独立。 */
public final class PresentationSpec {
    public static final int MAX_BYTES=3*1024*1024,MAX_SIZE=2048;
    private PresentationSpec(){}
    public static String shape(String dimension,int layer,List<double[]> outline){
        try{
            var hash=java.security.MessageDigest.getInstance("SHA-256");
            hash.update((dimension+":"+layer).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var b=java.nio.ByteBuffer.allocate(16);
            for(var p:outline){b.clear();b.putDouble(p[0]).putDouble(p[1]);hash.update(b.array());}
            return java.util.HexFormat.of().formatHex(hash.digest());
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    public static void validatePng(byte[] png){
        if(png.length<24||png.length>MAX_BYTES)throw new IllegalArgumentException("展示图PNG必须小于3MiB");
        var b=java.nio.ByteBuffer.wrap(png);
        if(b.getLong()!=0x89504e470d0a1a0aL)throw new IllegalArgumentException("展示图必须是PNG");
        int w=b.getInt(16),h=b.getInt(20);
        if(w<1||h<1||w>MAX_SIZE||h>MAX_SIZE)throw new IllegalArgumentException("展示图尺寸上限2048×2048");
    }
}
