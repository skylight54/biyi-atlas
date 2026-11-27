package com.biyi.atlas.client;

import com.biyi.atlas.map.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.nbt.CompoundTag;
import com.mojang.blaze3d.platform.NativeImage;

/** 一个活动区域的可选展示层。任何校验失败都使用原始底图。 */
public final class AtlasPresentation {
    private static ResourceLocation texture;
    private static String areaId="",shape="",status="无匹配展示图，使用底图";
    private static int width,height;
    private static boolean enabled=true;
    private static PresentationRegistration registration=PresentationRegistration.IDENTITY;
    public static PresentationRegistration registration(){return registration;}
    private record Choice(String id,String name,boolean compatible){}
    private static final java.util.Map<String,java.util.List<Choice>> galleries=new java.util.HashMap<>();
    private static final java.util.Map<String,String> selections=new java.util.HashMap<>();
    public static void resetLibrary(){clear();galleries.clear();selections.clear();}
    public static String choiceLabel(String id){
        String selected=selections.getOrDefault(id,"base");
        for(var c:galleries.getOrDefault(id,java.util.List.of()))if(c.id.equals(selected))return c.name+(c.compatible?"":"（已过期，使用底图）");
        return "保底地形";
    }
    public static void cycle(String id){
        var list=new java.util.ArrayList<Choice>();list.add(new Choice("base","保底地形",true));
        for(var c:galleries.getOrDefault(id,java.util.List.of()))if(c.compatible)list.add(c);
        int index=0;for(int i=0;i<list.size();i++)if(list.get(i).id.equals(selections.get(id)))index=i;
        com.biyi.atlas.net.AreaTerrainMessage.selectArt(id,list.get((index+1)%list.size()).id);
    }
    public static void clear(){if(texture!=null)Minecraft.getInstance().getTextureManager().release(texture);texture=null;areaId="";registration=PresentationRegistration.IDENTITY;status="无匹配展示图，使用底图";}
    public static void toggle(){enabled=!enabled;}
    public static boolean enabled(){return enabled;}
    public static String status(){return enabled?status:"展示图已关闭，使用底图";}
    public static ResourceLocation texture(){return texture;}
    public static int width(){return width;}
    public static int height(){return height;}
    public static boolean usable(AtlasRegion area){return enabled&&texture!=null&&area!=null&&area.id().equals(areaId)&&shape.equals(PresentationSpec.shape(area.dimension().toString(),area.layer(),area.outline()));}
    public static void receive(AtlasRegion area,CompoundTag delivery){
        clear();
        if(delivery.contains("gallery")){
            var gallery=delivery.getCompound("gallery");var rows=gallery.getList("variants",10);var choices=new java.util.ArrayList<Choice>();
            for(int i=0;i<rows.size();i++){var row=rows.getCompound(i);choices.add(new Choice(row.getString("id"),row.getString("name"),row.getBoolean("compatible")));}
            galleries.put(area.id(),choices);selections.put(area.id(),gallery.getString("selected"));
            if(Minecraft.getInstance().screen instanceof AtlasEditScreen edit)edit.refreshLabels();
        }
        if(!delivery.contains("presentation"))return;
        var art=delivery.getCompound("presentation");byte[] png=art.getByteArray("png");
        try{
            PresentationSpec.validatePng(png);
            shape=PresentationSpec.shape(area.dimension().toString(),area.layer(),area.outline());
            if(!shape.equals(art.getString("shape")))return;
            registration=PresentationRegistration.from(art);
            var image=NativeImage.read(new java.io.ByteArrayInputStream(png));
            width=image.getWidth();height=image.getHeight();
            var dynamic=new DynamicTexture(image);dynamic.setFilter(true,false);
            texture=Minecraft.getInstance().getTextureManager().register("biyi_presentation",dynamic);
            areaId=area.id();status="展示图已就绪（底图可随时切换）";
        }catch(Exception e){status="展示图无效，已回到底图";}
    }
}
