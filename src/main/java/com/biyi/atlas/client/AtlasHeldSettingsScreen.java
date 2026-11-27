package com.biyi.atlas.client;

import com.biyi.atlas.map.HeldMapView;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Side controls over the actual first-person held map. No full-screen map or background wash. */
public final class AtlasHeldSettingsScreen extends Screen {
    private final long opened=System.nanoTime();
    private int left,top,panelWidth;
    public AtlasHeldSettingsScreen(){super(Component.literal("手持地图设置"));}
    public float raised(){float t=Math.min(1,(System.nanoTime()-opened)/350_000_000f);return t*t*(3-2*t);}
    @Override protected void init(){
        AtlasSettings.heldZoom();
        panelWidth=Math.min(190,Math.max(135,width/3));left=width-panelWidth-12;top=Math.max(28,(height-168)/2);
        addRenderableWidget(new LeatherControl(left+10,top+51,36,"−",b->zoom(-1)));
        addRenderableWidget(new LeatherControl(left+panelWidth-46,top+51,36,"+",b->zoom(1)));
        addRenderableWidget(new LeatherControl(left+10,top+80,panelWidth-20,"恢复默认缩放",b->{AtlasSettings.setHeldZoom(HeldMapView.DEFAULT);AtlasHeldRenderer.invalidate();}));
        addRenderableWidget(new LeatherControl(left+10,top+106,panelWidth-20,"打开大地图 [M]",b->AtlasScreen.openForMapKey()));
        addRenderableWidget(new LeatherControl(left+10,top+132,panelWidth-20,"收起设置",b->onClose()));
    }
    private void zoom(double delta){AtlasSettings.setHeldZoom(HeldMapView.scroll(AtlasSettings.heldZoom(),delta));AtlasHeldRenderer.invalidate();}
    private static final class LeatherControl extends Button {
        LeatherControl(int x,int y,int width,String text,OnPress press){super(x,y,width,20,Component.literal(text),press,DEFAULT_NARRATION);}
        @Override public void renderWidget(GuiGraphics g,int mouseX,int mouseY,float tick){
            int x=getX(),y=getY();boolean over=isHoveredOrFocused();
            g.fill(x,y,x+width,y+height,0xFF6B5030);
            g.fill(x+1,y+1,x+width-1,y+height-1,over?0xFFD9BC78:0xFFBDA06A);
            g.drawCenteredString(net.minecraft.client.Minecraft.getInstance().font,getMessage(),x+width/2,y+6,0xFF392B1B);
        }
    }
    @Override public boolean mouseScrolled(double x,double y,double delta){zoom(delta);return true;}
    @Override public boolean mouseClicked(double x,double y,int button){
        if(button==1&&hasShiftDown()){onClose();return true;}
        return super.mouseClicked(x,y,button);
    }
    @Override public boolean keyPressed(int key,int scan,int mods){
        if(AtlasKeys.OPEN.matches(key,scan)){AtlasScreen.openForMapKey();return true;}
        return super.keyPressed(key,scan,mods);
    }
    @Override public void removed(){AtlasSettings.save();}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void tick(){
        if(minecraft.player==null||!AtlasHeldRenderer.holdingMap(minecraft.player))onClose();
    }
    @Override public void render(GuiGraphics g,int mouseX,int mouseY,float partial){
        g.fill(left-2,top-2,left+panelWidth+2,top+160,0xE052422D);
        g.fill(left,top,left+panelWidth,top+158,0xF0C6AC73);
        g.drawString(font,title,left+10,top+10,0xFF392B1B,false);
        g.drawString(font,font.plainSubstrByWidth("滚轮缩放 · Esc 收起",panelWidth-20),left+10,top+29,0xFF51432E,false);
        String amount=String.format(java.util.Locale.ROOT,"×%.2f",AtlasSettings.heldZoom()/HeldMapView.DEFAULT);
        g.drawCenteredString(font,amount,left+panelWidth/2,top+57,0xFF392B1B);
        super.render(g,mouseX,mouseY,partial);
    }
}
