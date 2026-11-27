package com.biyi.atlas.map;

import java.util.List;

/** Cached world-space cloud mask, independent of camera scale and game rendering. */
public final class FogRaster {
    public static final int SIZE=384;
    public record Mask(int[] pixels,float[] distance,double[] bounds,double[] origin,double radius){}
    public static double smooth(double value){double t=Math.max(0,Math.min(1,value));return t*t*(3-2*t);}
    public static double opacity(double distance,double progress){
        if(progress<=0)return 1;if(progress>=1)return 0;
        return smooth((distance-progress*1.3+.16)/.16)*(1-smooth((progress-.84)/.16));
    }
    public static double[] origin(List<double[]> polygon,double[] b){
        double cx=(b[0]+b[2])/2,cz=(b[1]+b[3])/2;
        if(MapPolygon.contains(polygon,cx,cz))return new double[]{cx,cz};
        double best=Double.MAX_VALUE;double[] result=polygon.get(0).clone();
        for(int z=0;z<24;z++)for(int x=0;x<24;x++){
            double wx=b[0]+(x+.5)*(b[2]-b[0])/24,wz=b[1]+(z+.5)*(b[3]-b[1])/24;
            double d=Math.hypot(wx-cx,wz-cz);
            if(d<best&&MapPolygon.contains(polygon,wx,wz)){best=d;result=new double[]{wx,wz};}
        }
        return result;
    }
    public static Mask bake(List<double[]> polygon,double[] raw,int[] cloud,int tw,int th){
        return bake(polygon,raw,cloud,tw,th,false);
    }
    public static double edgeAlpha(double signedInside,double width,double x,double z,boolean exterior){
        double n=.32+.17*Math.sin(x/width*1.7+Math.sin(z/width*.83))+.11*Math.cos(z/width*2.3-x/width*.7);
        // Fully cover the protected side. Only the decorative skirt crosses the polygon.
        double distance=exterior?signedInside:-signedInside;
        return distance<=0?1:1-smooth((distance/width-n)/.85);
    }
    public static Mask bake(List<double[]> polygon,double[] raw,int[] cloud,int tw,int th,boolean exterior){
        double feather=Math.max(2,Math.min(80,Math.min(raw[2]-raw[0],raw[3]-raw[1])*.055));
        double[] b={raw[0]-feather*1.7,raw[1]-feather*1.7,raw[2]+feather*1.7,raw[3]+feather*1.7};
        double[] center=origin(polygon,raw);double radius=0;
        for(var p:polygon)radius=Math.max(radius,Math.hypot(p[0]-center[0],p[1]-center[1]));
        radius=Math.max(1,radius);
        int[] pixels=new int[SIZE*SIZE];float[] distance=new float[pixels.length];
        for(int y=0;y<SIZE;y++)for(int x=0;x<SIZE;x++){
            double wx=b[0]+(x+.5)*(b[2]-b[0])/SIZE,wz=b[1]+(y+.5)*(b[3]-b[1])/SIZE;
            double edge=Double.MAX_VALUE;
            for(int i=0;i<polygon.size();i++){
                var a=polygon.get(i);var c=polygon.get((i+1)%polygon.size());
                double dx=c[0]-a[0],dz=c[1]-a[1],len=dx*dx+dz*dz;
                double t=len<1e-9?0:Math.max(0,Math.min(1,((wx-a[0])*dx+(wz-a[1])*dz)/len));
                edge=Math.min(edge,Math.hypot(wx-a[0]-t*dx,wz-a[1]-t*dz));
            }
            boolean inside=MapPolygon.contains(polygon,wx,wz);
            double alpha=edgeAlpha(inside?edge:-edge,feather,wx,wz,exterior);
            // Mirrored sampling keeps texture boundaries continuous even for a non-perfect tile.
            int tx=mirror((int)Math.floor(wx*tw/750),tw),ty=mirror((int)Math.floor(wz*th/750),th);
            int rgb=cloud[ty*tw+tx]&0xFFFFFF;
            if(exterior)rgb=((int)(((rgb>>16)&255)*.17)<<16)|((int)(((rgb>>8)&255)*.22)<<8)|(int)((rgb&255)*.24);
            int k=y*SIZE+x;pixels[k]=((int)Math.round(alpha*255)<<24)|rgb;
            double curl=.035*Math.sin(wx*.031+wz*.012)+.025*Math.cos(wz*.026-wx*.009);
            distance[k]=(float)Math.max(0,Math.hypot(wx-center[0],wz-center[1])/radius+curl);
        }
        return new Mask(pixels,distance,b,center,radius);
    }
    private static int mirror(int n,int size){int p=Math.floorMod(n,size*2);return p<size?p:size*2-1-p;}
    public static int[] dissolve(Mask mask,double progress){
        int[] out=mask.pixels().clone();
        for(int i=0;i<out.length;i++)out[i]=((int)Math.round((out[i]>>>24)*opacity(mask.distance()[i],progress))<<24)|(out[i]&0xFFFFFF);
        return out;
    }
}
