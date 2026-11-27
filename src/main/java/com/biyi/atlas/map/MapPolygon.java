package com.biyi.atlas.map;
import java.util.List;
public final class MapPolygon {
    private MapPolygon(){}
    public static boolean intersectsRect(List<double[]> p,double x1,double z1,double x2,double z2){
        if(p.size()<3)return false;
        if(contains(p,x1,z1)||contains(p,x2,z1)||contains(p,x1,z2)||contains(p,x2,z2))return true;
        for(int i=0;i<p.size();i++){
            double[] a=p.get(i),b=p.get((i+1)%p.size());
            if(a[0]>=x1&&a[0]<=x2&&a[1]>=z1&&a[1]<=z2)return true;
            if(MapStroke.clip(a[0],a[1],b[0],b[1],x1,z1,x2,z2)!=null)return true;
        }
        return false;
    }
    public static boolean contains(List<double[]> p,double x,double z){
        boolean inside=false;
        for(int i=0,j=p.size()-1;i<p.size();j=i++){
            double[] a=p.get(j),b=p.get(i);
            if((a[1]>z)!=(b[1]>z)&&x<(b[0]-a[0])*(z-a[1])/(b[1]-a[1])+a[0])inside=!inside;
        }
        return inside;
    }
}
