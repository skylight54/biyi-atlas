package com.biyi.atlas.client;

import java.util.*;

/** 不依赖Minecraft的流式布局，绘制和命中使用同一组矩形。 */
public final class AtlasUiLayout {
    public record Size(int width,int height){}
    public record Rect(int x,int y,int width,int height){
        public boolean contains(double px,double py){return px>=x&&py>=y&&px<x+width&&py<y+height;}
    }
    public record Frame(Rect header,Rect drawer,int contentTop,int contentBottom){}
    public static List<Rect> navigation(int width, boolean author) {
        return flow(Math.max(1, width - 32), Collections.nCopies(author ? 10 : 7, new Size(42, 18)));
    }
    public static Frame frame(int width,int height){
        return frame(width,height,true);
    }
    public static Frame frame(int width,int height,boolean author){
        int margin=8,headerHeight=38 + contentHeight(navigation(width, author));
        int dw=Math.min(320,Math.max(180,(int)(width*.42)));
        dw=Math.min(dw,Math.max(100,width-24));
        if(!author)dw=Math.min(220,Math.max(130,(int)(width*.28)));
        int dh=author?Math.max(64,height-headerHeight-36):Math.min(172,Math.max(88,(int)((height-headerHeight-36)*.48)));
        Rect drawer=new Rect(width-dw-margin,author?headerHeight+12:height-dh-24,dw,dh);
        return new Frame(new Rect(margin,margin,width-margin*2,headerHeight-4),drawer,
                drawer.y()+48,drawer.y()+drawer.height()-14);
    }
    public static List<Rect> flow(int width,List<Size> sizes){
        List<Rect> result=new ArrayList<>();int x=0,y=0,rowHeight=0;
        for(Size s:sizes){
            int w=Math.max(1,Math.min(width,s.width())),h=Math.max(1,s.height());
            if(x>0&&x+w>width){x=0;y+=rowHeight+4;rowHeight=0;}
            result.add(new Rect(x,y,w,h));x+=w+4;rowHeight=Math.max(rowHeight,h);
        }
        return result;
    }
    public static int contentHeight(List<Rect> rects){
        return rects.stream().mapToInt(r->r.y()+r.height()).max().orElse(0);
    }
}
