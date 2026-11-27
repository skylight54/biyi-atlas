package com.biyi.atlas.map;

/** Vanilla 1.20.1 map tilt curve: pitch down raises the map toward the viewer. */
public final class HeldMapMotion {
    public static float tilt(float pitch){
        float t=Math.max(0,Math.min(1,1-pitch/45f+.1f));
        return (float)(.5-Math.cos(t*Math.PI)*.5);
    }
    public static boolean twoHanded(boolean mainHand,boolean offhandEmpty){return mainHand&&offhandEmpty;}
}
