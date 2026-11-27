package com.biyi.atlas.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import com.biyi.atlas.map.MapStroke;
import java.util.Set;

/** 与纸面一致的细墨地标符号；自定义图标仍使用作者原图。 */
final class AtlasMapSymbols {
    private static final int INK=0xFF52615A,PAPER=0xECE8E8DA,RED=0xFF9D6259;
    private static final Set<String> BUILTIN=Set.of("town","village","dungeon","ruins","ruin","danger","camp","quest","shop","portal","pin","flag","tree","forest","mountain","bridge","gate","teleport","shrine","tower");
    private static void line(GuiGraphics g,double x,double y,double a,double b,int color){
        for(var r:MapStroke.spans(x,y,a,b,1,-10000,-10000,10000,10000))AtlasUiTheme.rect(g,r.left(),r.top(),r.right()-r.left(),1,color);
    }
    private static void path(GuiGraphics g,int color,double... p){for(int i=0;i+3<p.length;i+=2)line(g,p[i],p[i+1],p[i+2],p[i+3],color);}
    static boolean marker(GuiGraphics g,String id,int x,int y,int size){
        if(!BUILTIN.contains(id))return false;
        var pose=g.pose();pose.pushPose();pose.translate(x,y,0);pose.scale(size/20f,size/20f,1);
        // 小幅留白的菱形纸印，不使用亮色贴纸或厚边框。
        for(int row=1;row<19;row++){int half=9-Math.abs(row-10);if(half>0)AtlasUiTheme.rect(g,10-half,row,half*2,1,PAPER);}
        path(g,0x9A87958B,10,1,19,10,10,19,1,10,10,1);
        switch(id){
            case "town","village","shop","gate","tower"->{path(g,INK,5,9,10,5,15,9,5,9,5,14,15,14,15,9);path(g,INK,9,14,9,11,11,11,11,14);}
            case "dungeon","ruins","ruin"->{path(g,INK,5,14,5,8,8,6,12,6,15,8,15,14,5,14);path(g,INK,8,14,8,10,10,8,12,10,12,14);}
            case "danger"->{path(g,RED,10,4,4,15,16,15,10,4);path(g,RED,10,8,10,11);AtlasUiTheme.rect(g,10,13,1,1,RED);}
            case "mountain"->{path(g,INK,4,14,8,6,12,14,16,14,12,7,10,10);path(g,INK,7,9,8,10,9,9);}
            case "tree","forest"->{path(g,INK,10,5,5,12,15,12,10,5);path(g,INK,10,12,10,16);}
            case "bridge"->{path(g,INK,4,13,6,9,14,9,16,13);path(g,INK,5,13,15,13,15,15);}
            case "flag"->{path(g,RED,7,15,7,5,14,6,12,9,7,9);}
            case "quest"->{path(g,RED,7,7,7,5,13,5,13,8,10,10,10,12);AtlasUiTheme.rect(g,9,14,2,2,RED);}
            case "camp"->{path(g,INK,4,14,10,5,16,14,4,14,10,5);path(g,INK,8,14,10,10,12,14);}
            case "portal","teleport","shrine"->{path(g,INK,6,15,6,6,14,6,14,15);path(g,RED,10,8,8,11,10,14,12,11,10,8);}
            default->{path(g,RED,10,5,6,9,10,15,14,9,10,5);AtlasUiTheme.rect(g,9,8,2,2,RED);}
        }
        pose.popPose();return true;
    }
    static void player(GuiGraphics g,int x,int y,float yaw,ResourceLocation skin){
        // 朝向环与玩家头像分别绘制，头像始终朝正，避免仅靠一支箭头辨认自己。
        var pose=g.pose();pose.pushPose();pose.translate(x,y,3);
        for(int row=-9;row<=9;row++){int half=(int)Math.floor(Math.sqrt(81-row*row));AtlasUiTheme.rect(g,-half,row,2*half+1,1,PAPER);}
        for(int i=0;i<24;i++){double a=i*Math.PI/12,b=(i+1)*Math.PI/12;line(g,Math.cos(a)*9,Math.sin(a)*9,Math.cos(b)*9,Math.sin(b)*9,INK);}
        pose.pushPose();pose.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(yaw+180));
        path(g,RED,-4,-10,0,-15,4,-10);pose.popPose();
        g.blit(skin,-5,-5,10,10,8,8,8,8,64,64);g.blit(skin,-5,-5,10,10,40,8,8,8,64,64);
        pose.popPose();
    }
}
