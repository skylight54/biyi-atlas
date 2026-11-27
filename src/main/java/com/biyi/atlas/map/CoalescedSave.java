package com.biyi.atlas.map;

/** 限速期间保留脏状态，保证最后一次编辑不会被节流丢弃。 */
public final class CoalescedSave {
    private boolean dirty;
    private long next;
    public void changed(){dirty=true;}
    public boolean poll(long now){
        if(!dirty||now<next)return false;
        dirty=false;next=now+5000;return true;
    }
    public void reset(){dirty=false;next=0;}
}
