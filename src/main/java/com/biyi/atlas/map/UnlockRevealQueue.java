package com.biyi.atlas.map;

import java.util.*;
import java.util.function.Predicate;

/** Bounded explicit grant queue. Snapshot synchronization never calls offer. */
public final class UnlockRevealQueue {
    private final Map<String,Long> pending=new LinkedHashMap<>();
    public void offer(String id,long now){if(id!=null&&!id.isBlank()&&pending.size()<16)pending.putIfAbsent(id,now);}
    public String poll(long now,Predicate<String> ready){
        var it=pending.entrySet().iterator();
        while(it.hasNext()){
            var e=it.next();if(now-e.getValue()>30000){it.remove();continue;}
            if(ready.test(e.getKey())){it.remove();return e.getKey();}
        }
        return null;
    }
    public void clear(){pending.clear();}
}
