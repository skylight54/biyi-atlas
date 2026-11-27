package com.biyi.atlas.client;

import com.biyi.atlas.map.AtlasContent;
import net.minecraft.client.Minecraft;
import java.util.*;

/** Only explicit successful server grants enqueue a reveal. Login snapshots never do. */
public final class AtlasUnlockReveal {
    private static final com.biyi.atlas.map.UnlockRevealQueue QUEUE=new com.biyi.atlas.map.UnlockRevealQueue();
    public static void announce(String id){QUEUE.offer(id,net.minecraft.Util.getMillis());}
    public static void clear(){QUEUE.clear();}
    public static void tick(){
        var mc=Minecraft.getInstance();if(mc.level==null||mc.player==null){clear();return;}
        if(mc.screen instanceof AtlasScreen s&&s.revealing())return;
        if(mc.screen!=null&&!(mc.screen instanceof AtlasScreen))return;
        String id=QUEUE.poll(net.minecraft.Util.getMillis(),key->{
            var region=AtlasContent.get().regionById(key);
            return region!=null&&AtlasAuthoring.known()&&AtlasVisibility.regionVisible(key)&&AtlasUnlocks.isVisible(key);
        });
        if(id!=null)AtlasScreen.openReveal(AtlasContent.get().regionById(id));
    }
}
