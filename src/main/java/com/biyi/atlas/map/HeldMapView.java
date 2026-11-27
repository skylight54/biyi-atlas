package com.biyi.atlas.map;

/** Local view preferences; never change authored geometry or exploration permissions. */
public final class HeldMapView {
    public static final double MIN=.125,MAX=1,DEFAULT=.35;
    private HeldMapView(){}
    public static double clamp(double value){return Double.isFinite(value)?Math.max(MIN,Math.min(MAX,value)):DEFAULT;}
    public static double scroll(double value,double delta){return clamp(clamp(value)*Math.pow(1.2,Math.max(-8,Math.min(8,Double.isFinite(delta)?delta:0))));}
}
