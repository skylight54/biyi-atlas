package com.biyi.atlas.map;

import net.minecraft.nbt.NbtIo;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.File;

/** 从实际NBT生成离线预览，不修改来源文件。 */
public final class TerrainRasterPreview {
    public static void main(String[] args) throws Exception {
        var data=AtlasMapData.load(NbtIo.readCompressed(new File(args[0]))).terrain();
        BufferedImage image=new BufferedImage(768,768,BufferedImage.TYPE_INT_ARGB);
        var graphics=image.createGraphics();
        graphics.setColor(new java.awt.Color(222,225,218));
        graphics.fillRect(0,0,768,768);
        int originX=args.length>3?Integer.parseInt(args[3]):-20;
        int originZ=args.length>4?Integer.parseInt(args[4]):-20;
        for(int tz=originZ;tz<originZ+6;tz++)for(int tx=originX;tx<originX+6;tx++){
            int[] pixels=TerrainRaster.bake(data,tx,tz);
            BufferedImage tile=new BufferedImage(128,128,BufferedImage.TYPE_INT_ARGB);
            tile.setRGB(0,0,128,128,pixels,0,128);
            graphics.drawImage(tile,(tx-originX)*128,(tz-originZ)*128,null);
        }
        if(args.length>2){
            var root=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(java.nio.file.Path.of(args[2]))).getAsJsonObject();
            for(var element:root.getAsJsonObject("Roads").getAsJsonArray("list")){
                var road=AtlasRoad.fromJson(element.getAsJsonObject());
                if(road.layer()!=0||!road.dimension().toString().equals("minecraft:overworld"))continue;
                int w=RoadStyle.width(road.width(),1);
                for(int pass=0;pass<2;pass++){
                    graphics.setColor(new java.awt.Color(pass==0?RoadStyle.edge(road.rgb()):RoadStyle.INK,true));
                    for(int i=0;i+1<road.points().size();i++){
                        var a=road.points().get(i);var b=road.points().get(i+1);
                        // 同游戏的扫描行笔划；虚线按实际距离切分。
                        double length=Math.hypot(b[0]-a[0],b[1]-a[1]);
                        double step=road.dashed()?10:Math.max(1,length);
                        for(double t=0;t<length;t+=step){
                            double end=Math.min(length,road.dashed()?t+6:length);
                            for(var span:MapStroke.spans(a[0]+(b[0]-a[0])*t/length-originX*128,a[1]+(b[1]-a[1])*t/length-originZ*128,
                                    a[0]+(b[0]-a[0])*end/length-originX*128,a[1]+(b[1]-a[1])*end/length-originZ*128,pass==0?w+2:w,0,0,768,768))
                                graphics.fillRect(span.left(),span.top(),span.right()-span.left(),1);
                        }
                    }
                }
            }
        }
        graphics.dispose();
        ImageIO.write(image,"png",new File(args[1]));
        System.out.println("Actual NBT terrain + authored roads preview written (offline; no game UI or visibility mask).");
    }
}
