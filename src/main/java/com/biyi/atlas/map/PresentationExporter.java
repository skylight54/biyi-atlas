package com.biyi.atlas.map;

import java.nio.file.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import com.google.gson.*;

/** AI输入：按区域真实坐标导出底片与世界信息，不生成不存在的地形。 */
public final class PresentationExporter {
    private PresentationExporter(){}
    public static void export(AtlasRegion area,TerrainSurvey data,Path dir)throws Exception{
        Files.createDirectories(dir);double[] b=area.bounds();
        int size=1024;var image=new BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB);
        int min=Integer.MAX_VALUE,max=Integer.MIN_VALUE,known=0;int[] types=new int[7];
        for(int y=0;y<size;y++)for(int x=0;x<size;x++){
            double wx=b[0]+(x+.5)*(b[2]-b[0])/size,wz=b[1]+(y+.5)*(b[3]-b[1])/size;
            if(!area.contains(wx,wz))continue;
            int gx=(int)Math.floor(wx/4),gz=(int)Math.floor(wz/4);
            if(!data.has(gx,gz))continue;
            int sample=data.get(gx,gz),h=TerrainSurvey.height(sample),kind=TerrainSurvey.kind(sample);
            min=Math.min(min,h);max=Math.max(max,h);known++;if(kind<types.length)types[kind]++;
            double natural=TerrainRaster.naturalHeight(data,gx,gz);
            double dx=data.has(gx+1,gz)?TerrainRaster.naturalHeight(data,gx+1,gz)-natural:0;
            double dz=data.has(gx,gz+1)?TerrainRaster.naturalHeight(data,gx,gz+1)-natural:0;
            int shade=(int)Math.max(-16,Math.min(14,(-dx-dz)*1.25));
            int rgb=switch(kind){case TerrainSurvey.WATER->0x536D72;case TerrainSurvey.FOREST->0xBAC4B7;case TerrainSurvey.SNOW->0xEAEBE5;case TerrainSurvey.SAND->0xD6D0B9;default->0xD5DAD1;};
            int r=Math.max(0,Math.min(255,((rgb>>16)&255)+shade)),g=Math.max(0,Math.min(255,((rgb>>8)&255)+shade)),bl=Math.max(0,Math.min(255,(rgb&255)+shade));
            if(data.buildingAt((int)Math.floor(wx),(int)Math.floor(wz))){r=239;g=240;bl=231;}
            image.setRGB(x,y,0xFF000000|(r<<16)|(g<<8)|bl);
        }
        ImageIO.write(image,"png",dir.resolve("base.png").toFile());
        var manifest=new JsonObject();manifest.addProperty("area",area.id());manifest.addProperty("name",area.name());
        manifest.addProperty("shape",PresentationSpec.shape(area.dimension().toString(),area.layer(),area.outline()));
        manifest.addProperty("base",data.fingerprint());manifest.add("bounds",new Gson().toJsonTree(b));
        manifest.addProperty("dimension",area.dimension().toString());manifest.addProperty("layer",area.layer());
        manifest.addProperty("samples",data.size());manifest.addProperty("knownPixels",known);
        manifest.addProperty("minY",min);manifest.addProperty("maxY",max);manifest.add("typePixels",new Gson().toJsonTree(types));
        manifest.add("outline",new Gson().toJsonTree(area.outline()));manifest.addProperty("north","image top = negative Z; image right = positive X");
        manifest.addProperty("instructions","Preserve world bounds, water/land boundaries and terrain locations. No labels, icons or roads: runtime overlays supply them. Do not invent geography. Keep original image aspect and edge registration. Output PNG <=2048 per axis and <=2MiB. Import manifest unchanged; any changed base or boundary invalidates image.");
        Files.writeString(dir.resolve("manifest.json"),new GsonBuilder().setPrettyPrinting().create().toJson(manifest));
    }
}
