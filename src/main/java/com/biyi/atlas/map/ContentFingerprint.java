package com.biyi.atlas.map;

/** 相同内容即使被运行时视图标为dirty，也不再次上传。连接重置后允许重发。 */
public final class ContentFingerprint {
    private byte[] sent;
    public boolean changed(byte[] bytes){return !java.util.Arrays.equals(sent,bytes);}
    public void sent(byte[] bytes){sent=bytes.clone();}
    public void reset(){sent=null;}
}
