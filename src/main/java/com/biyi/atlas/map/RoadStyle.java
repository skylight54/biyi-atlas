package com.biyi.atlas.map;

/** 道路使用纸白主体和低对比描边；宽度在屏幕空间有界。 */
public final class RoadStyle {
    private RoadStyle(){}
    public static int width(int authored,double zoom){return Math.max(1,Math.min(3,(int)Math.round(Math.sqrt(Math.max(.01,zoom))*Math.max(1,authored)*.55)));}
    public static int edge(int rgb){
        int r=(((rgb>>16)&255)+3*157)/4,g=(((rgb>>8)&255)+3*159)/4,b=((rgb&255)+3*145)/4;
        return 0x65000000|(r<<16)|(g<<8)|b;
    }
    public static final int INK=0xC7D7D1BB;
}
