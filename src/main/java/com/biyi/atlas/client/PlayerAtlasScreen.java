package com.biyi.atlas.client;

import com.biyi.atlas.map.*;
import com.mojang.math.Axis;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.util.*;

public final class PlayerAtlasScreen extends AtlasScreen {
    private final Map<String, MapViewport> cameras = new HashMap<>();
    private ResourceLocation dim;
    private AtlasRegion area, lastArea;
    private PlayerPinStore pins;
    private int selectedLayer;
    private MapViewport camera;
    private AtlasPlace place;
    private AtlasWaypoint pin;
    private boolean own, pressed, dragged, sawReveal, started;
    private double downX, downY;
    private static final int INK = 0xff343e39, PAPER = 0xffe3d7b7;
    private final List<Action> actions = new ArrayList<>();
    private record Action(int x,int y,int w,int h,Runnable run) {
        boolean hit(double mx,double my){return mx>=x && mx<x+w && my>=y && my<y+h;}
    }

    public PlayerAtlasScreen() { super(false); }

    @Override public void init() {
        dim = minecraft.level.dimension().location();
        if (camera == null) {
            camera = centered(.12);
            try { pins = new PlayerPinStore(minecraft, dim); }
            catch (IOException ex) { notifyPlayer("私人路标读取失败：" + ex.getMessage()); }
        } else camera = resized(camera);
        pressed = false;
        actions.clear();
    }

    private void notifyPlayer(String text) { minecraft.player.displayClientMessage(Component.literal(text), true); }
    private MapViewport centered(double zoom) {
        return new MapViewport(width,height,minecraft.player.getX(),minecraft.player.getZ(),zoom);
    }
    private MapViewport resized(MapViewport v) {
        return new MapViewport(width,height,v.centerX(),v.centerZ(),v.pixelsPerBlock());
    }
    private List<AtlasRegion> areas() {
        return AtlasReadView.regions(MapAudience.PLAYER,dim,0).stream().filter(r -> r.mapArea() && !r.isChild()).toList();
    }
    private AtlasRegion containing(double x,double z) {
        return areas().stream().filter(r -> r.contains(x,z)).min(Comparator.comparingDouble(r -> {
            double[] b=r.bounds(); return (b[2]-b[0])*(b[3]-b[1]);
        })).orElse(null);
    }
    private void enter(AtlasRegion next) {
        cameras.put(area==null?"":area.id(),camera);
        area=next;
        selectedLayer=next==null?0:next.layer();
        place=null; pin=null;
        if(next!=null) {
            lastArea=next;
            if(AtlasMapSession.active()!=next) AtlasMapSession.select(next);
        }
        MapViewport saved=cameras.get(next==null?"":next.id());
        if(saved!=null) camera=resized(saved);
        else if(next==null) camera=centered(.12);
        else {
            double[] b=next.bounds();
            double zoom=Math.min((width-36)/Math.max(1,b[2]-b[0]),(height-60)/Math.max(1,b[3]-b[1]));
            camera=new MapViewport(width,height,(b[0]+b[2])/2,(b[1]+b[3])/2,Math.max(.001,Math.min(4,zoom)));
        }
    }
    private MapViewport worldView() {
        double px=minecraft.player.getX(), pz=minecraft.player.getZ(), zoom=.12;
        for(var region:areas()){
            double[] b=region.bounds();
            double dx=Math.max(Math.abs(px-b[0]),Math.abs(px-b[2]));
            double dz=Math.max(Math.abs(pz-b[1]),Math.abs(pz-b[3]));
            zoom=Math.min(zoom,Math.min((width-32)/(2*Math.max(1,dx)),(height-54)/(2*Math.max(1,dz))));
        }
        return centered(Math.max(.000001,zoom));
    }
    private int layer(){return selectedLayer;}
    private boolean visible(AtlasVisibility.Kind kind,String region,double x,double z) {
        if(!AtlasAuthoring.known()) return false;
        return area==null ? AtlasVisibility.visible(kind,region,x,z,false,dim,0)
                : AtlasReadView.elementVisible(MapAudience.PLAYER,kind,region,dim,layer(),x,z);
    }
    @Override public void render(GuiGraphics g,int mx,int my,float delta) {
        if(revealing()){ sawReveal=true; super.render(g,mx,my,delta); return; }
        if(sawReveal){sawReveal=false; started=true; enter(AtlasMapSession.active());}
        if(!started && AtlasAuthoring.known()){
            started=true; camera=worldView(); enter(containing(minecraft.player.getX(),minecraft.player.getZ()));
        }
        if(area!=null){
            var live=AtlasContent.get().regionById(area.id());
            if(live==null || !AtlasVisibility.regionVisible(area.id())) enter(null);
            else if(live!=area || AtlasMapSession.active()!=area) enter(live);
        }
        actions.clear();
        g.fill(0,0,width,height,PAPER);
        if(area!=null) drawPlayerCanvas(g,dim,layer(),camera);
        else if(AtlasAuthoring.known()) {
            for(var r:areas()){
                var points=r.outline();
                for(int i=0;i<points.size();i++){
                    double[] a=points.get(i), b=points.get((i+1)%points.size());
                    double dx=(b[0]-a[0])*camera.pixelsPerBlock(),dy=(b[1]-a[1])*camera.pixelsPerBlock();
                    var pose=g.pose(); pose.pushPose();
                    pose.translate(camera.screenX(a[0]),camera.screenY(a[1]),0);
                    pose.mulPose(Axis.ZP.rotation((float)Math.atan2(dy,dx)));
                    g.fill(0,0,(int)Math.ceil(Math.hypot(dx,dy)),1,0xff6a775a);pose.popPose();
                }
                g.drawCenteredString(font,r.name(),sx(r.labelX()),sy(r.labelZ()),INK);
            }
            g.drawCenteredString(font,"◆",sx(minecraft.player.getX()),sy(minecraft.player.getZ()),0xff865136);
        }
        if(pins!=null) for(var p:pins.data.waypoints()){
            if(p.layer()==layer() && visible(AtlasVisibility.Kind.WAYPOINT,null,p.x(),p.z()))
                g.drawCenteredString(font,"⚑",sx(p.x()),sy(p.z())-9,0xffae521e);
        }
        g.fill(0,0,width,22,0xff504d40);
        int w=width/5;
        button(g,0,0,w,area==null?"返回区域":"世界总览",()->{
            if(area!=null) enter(null);
            else if(lastArea!=null && areas().contains(lastArea)) enter(lastArea);
            else {var r=containing(minecraft.player.getX(),minecraft.player.getZ()); if(r!=null)enter(r);else notifyPlayer("点击总览上的区域进入");}
        });
        button(g,w,0,w,"定位",()->camera=centered(camera.pixelsPerBlock()));
        button(g,w*2,0,w,"设置",this::openDisplaySettings);
        button(g,w*3,0,w,"恢复路标",()->{
            try{if(pins!=null && pins.data.restoreDeletedWaypoint())savePins();else notifyPlayer("没有可恢复的私人路标");}
            catch(IllegalStateException ex){notifyPlayer(ex.getMessage());}
        });
        button(g,w*4,0,width-w*4,"关闭",this::onClose);
        var floors=area==null?List.of(new PlayerAtlasDetails.Floor(0,"地表")):PlayerAtlasDetails.Registry.get().floors(area);
        var floor=floors.stream().filter(f->f.layer()==layer()).findFirst().orElse(new PlayerAtlasDetails.Floor(layer(),"地表"));
        if(floors.size()>1){
            button(g,width-66,height-34,66,floor.name()+" ▸",()->{
                selectedLayer=floors.get((floors.indexOf(floor)+1)%floors.size()).layer();
                pin=null;place=null;
            });
        }
        String status=!AtlasAuthoring.known()?"等待地图权限…":(area==null?"世界总览":area.name())+
                 "　"+floor.name()+" · 已探索 "+AtlasStorage.get(dim).size()+" 区块 · 已开放 "+AtlasReadView.regions(MapAudience.PLAYER,dim,layer()).size()+" 区域";
        g.fill(0,height-13,width,height,0xff504d40);
        g.drawString(font,font.plainSubstrByWidth(status,width-8),4,height-11,0xfff6e9c9,false);
        if(place!=null && (!visible(AtlasVisibility.Kind.PLACE,place.region(),place.x(),place.z())
                || !AtlasContent.get().placesAt(dim,layer()).contains(place))) place=null;
        if(pin!=null && !visible(AtlasVisibility.Kind.WAYPOINT,null,pin.x(),pin.z()))pin=null;
        if(place!=null || pin!=null) card(g);
    }
    private int sx(double x){return (int)Math.round(camera.screenX(x));}
    private int sy(double z){return (int)Math.round(camera.screenY(z));}
    private void button(GuiGraphics g,int x,int y,int w,String label,Runnable action){
        g.fill(x+1,y+1,x+w-1,y+19,0xff575e4c);
        g.drawCenteredString(font,font.plainSubstrByWidth(label,Math.max(1,w-4)),x+w/2,y+6,0xfffaf1d8);
        actions.add(new Action(x,y,w,20,action));
    }
    private void card(GuiGraphics g){
        int w=Math.min(144,width-8), ax=sx(place!=null?place.x():pin.x()), ay=sy(place!=null?place.z():pin.z());
        int x=Math.max(3,Math.min(width-w-3,ax+w+12<width?ax+10:ax-w-10));
        int y=Math.max(23,Math.min(height-77,ay-32));
        g.fill(x,y,x+w,y+62,0xfff5eace);
        String name=place!=null?place.name():pin.name(), desc=place!=null?place.description():pin.description();
        g.drawString(font,font.plainSubstrByWidth(name,w-22),x+4,y+5,INK,false);
        g.drawString(font,font.plainSubstrByWidth(desc,w-8),x+4,y+24,INK,false);
        button(g,x+w-21,y,21,"×",()->{place=null;pin=null;});
        if(own && pin!=null){
            button(g,x,y+41,w/2,"编辑",()->edit(pin,pin.x(),pin.z()));
            button(g,x+w/2,y+41,w-w/2,"删除",()->{pins.data.removeWaypoint(pin);pin=null;savePins();});
        } else if(pin!=null) button(g,x,y+41,w,"追踪路标",()->AtlasTracker.set(new AtlasTracker.Target(pin.name(),dim,pin.x(),pin.z(),INK,false)));
        else {
            var entrance=PlayerAtlasDetails.Registry.get().entrance(place);
            if(entrance.isPresent()) button(g,x,y+41,w,"追踪入口",()->AtlasTracker.set(entrance.get()));
            else g.drawString(font,"入口待标注",x+4,y+45,INK,false);
        }
        actions.add(new Action(x,y,w,62,()->{}));
    }
    private void savePins(){
        try{pins.save();}catch(IOException ex){notifyPlayer("路标保存失败："+ex.getMessage());}
    }
    private void edit(AtlasWaypoint old,double x,double z){
        if(pins==null)return;
        minecraft.setScreen(new AtlasEditScreen(this,"私人路标",List.of("X "+(int)Math.floor(x)+"   Z "+(int)Math.floor(z)),
                List.of(new AtlasEditScreen.TextInput("名称",old==null?"我的路标":old.name()),
                        new AtlasEditScreen.TextInput("备注",old==null?"":old.description())),List.of(),values->{
            String name=values.get("名称").trim();
            if(name.isEmpty()) name="我的路标";
            var added=old==null?pins.data.addWaypoint(name,x,z):old;
            pin=new AtlasWaypoint(name,added.type(),layer(),x,z,added.icon(),values.get("备注"));
            pins.data.replaceWaypoint(added,pin);own=true;place=null;savePins();
        }));
    }
    private boolean hit(double mx,double my,double x,double z){
        return Math.abs(mx-sx(x))<=9 && my>=sy(z)-16 && my<=sy(z)+3;
    }
    private void pick(double mx,double my){
        place=null;pin=null;
        if(!AtlasAuthoring.known())return;
        if(pins!=null)for(var p:pins.data.waypoints())if(p.layer()==layer() && hit(mx,my,p.x(),p.z()) && visible(AtlasVisibility.Kind.WAYPOINT,null,p.x(),p.z())){pin=p;own=true;return;}
        if(area!=null){
            for(var p:AtlasStorage.get(dim).waypoints())if(p.layer()==layer() && hit(mx,my,p.x(),p.z()) && visible(AtlasVisibility.Kind.WAYPOINT,null,p.x(),p.z())){pin=p;own=false;return;}
            for(var p:AtlasContent.get().placesAt(dim,layer()))if(p.interactive() && hit(mx,my,p.x(),p.z()) && visible(AtlasVisibility.Kind.PLACE,p.region(),p.x(),p.z())){place=p;own=false;return;}
        }
        double x=camera.worldX(mx),z=camera.worldZ(my);
        if(area==null){var r=containing(x,z);if(r!=null){enter(r);return;}}
        if(visible(AtlasVisibility.Kind.WAYPOINT,null,x,z))edit(null,x,z);
    }
    @Override public boolean mouseClicked(double x,double y,int b){
        if(revealing())return super.mouseClicked(x,y,b);
        if(b!=0)return false;
        for(var a:actions)if(a.hit(x,y)){a.run.run();return true;}
        pressed=y>=22 && y<height-13; dragged=false;downX=x;downY=y;return true;
    }
    @Override public boolean mouseDragged(double x,double y,int b,double dx,double dy){
        if(pressed && b==0){
            if(!dragged){if(Math.hypot(x-downX,y-downY)<3)return true;dx=x-downX;dy=y-downY;dragged=true;}
            camera=new MapViewport(width,height,camera.centerX()-dx/camera.pixelsPerBlock(),camera.centerZ()-dy/camera.pixelsPerBlock(),camera.pixelsPerBlock());
        }
        return true;
    }
    @Override public boolean mouseReleased(double x,double y,int b){
        if(b==0 && pressed && !dragged && Math.hypot(x-downX,y-downY)<3 && y>=22 && y<height-13)pick(x,y);
        pressed=false;return true;
    }
    @Override public boolean mouseScrolled(double x,double y,double delta){
        if(revealing())return super.mouseScrolled(x,y,delta);
        double scale=Math.max(.001,Math.min(8,camera.pixelsPerBlock()*Math.pow(1.18,delta)));
        camera=new MapViewport(width,height,camera.worldX(x)-(x-width*.5)/scale,camera.worldZ(y)-(y-height*.5)/scale,scale);
        return true;
    }
    @Override public boolean keyPressed(int key,int scan,int mods){
        if(revealing())return super.keyPressed(key,scan,mods);
        if(key==256 && (place!=null || pin!=null)){place=null;pin=null;return true;}
        if(key==256 || AtlasKeys.OPEN.matches(key,scan)){onClose();return true;}
        return false;
    }
}
