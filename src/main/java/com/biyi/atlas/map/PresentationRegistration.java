package com.biyi.atlas.map;

import com.google.gson.JsonObject;
import net.minecraft.nbt.CompoundTag;
import java.util.ArrayList;
import java.util.List;

/** Normalized source image -> normalized map coordinates; never changes world geometry. */
public record PresentationRegistration(double a,double b,double tx,double c,double d,double tz) {
    public static final PresentationRegistration IDENTITY=new PresentationRegistration(1,0,0,0,1,0);
    public PresentationRegistration {
        for(double v:new double[]{a,b,tx,c,d,tz})if(!Double.isFinite(v))throw new IllegalArgumentException("配准参数必须是有限数值");
        if(a<.5||a>2||d<.5||d>2||Math.abs(b)>.25||Math.abs(c)>.25||Math.abs(tx)>.25||Math.abs(tz)>.25||a*d-b*c<.25)
            throw new IllegalArgumentException("配准超出允许的平移/缩放范围");
    }
    public double mapX(double u,double v){return a*u+b*v+tx;}
    public double mapZ(double u,double v){return c*u+d*v+tz;}
    public double sourceX(double x,double z){return (d*(x-tx)-b*(z-tz))/(a*d-b*c);}
    public double sourceZ(double x,double z){return (-c*(x-tx)+a*(z-tz))/(a*d-b*c);}
    public static PresentationRegistration fromManifest(JsonObject manifest){
        if(!manifest.has("registration"))return IDENTITY;
        var v=manifest.getAsJsonArray("registration");
        if(v.size()!=6)throw new IllegalArgumentException("registration需要6个参数");
        return new PresentationRegistration(v.get(0).getAsDouble(),v.get(1).getAsDouble(),v.get(2).getAsDouble(),v.get(3).getAsDouble(),v.get(4).getAsDouble(),v.get(5).getAsDouble());
    }
    public static PresentationRegistration from(CompoundTag art){
        if(!art.contains("registration"))return IDENTITY;
        if(!art.contains("registration",10))throw new IllegalArgumentException("配准格式无效");
        var v=art.getCompound("registration");
        for(String key:new String[]{"a","b","tx","c","d","tz"})if(!v.contains(key,99))throw new IllegalArgumentException("配准参数缺失："+key);
        return new PresentationRegistration(v.getDouble("a"),v.getDouble("b"),v.getDouble("tx"),v.getDouble("c"),v.getDouble("d"),v.getDouble("tz"));
    }
    public void writeTo(CompoundTag art){
        var v=new CompoundTag();v.putDouble("a",a);v.putDouble("b",b);v.putDouble("tx",tx);v.putDouble("c",c);v.putDouble("d",d);v.putDouble("tz",tz);art.put("registration",v);
    }
    public record Point(double x,double z){}
    /** Clip map-space polygons to the transformed source square, preventing UV wrap at edges. */
    public List<Point> clipSource(List<Point> input){
        boolean contained=true;
        for(Point p:input){double u=sourceX(p.x,p.z),v=sourceZ(p.x,p.z);if(u<0||u>1||v<0||v>1){contained=false;break;}}
        if(contained)return input;
        List<Point> points=input;
        for(int edge=0;edge<4&&!points.isEmpty();edge++){
            var next=new ArrayList<Point>();Point prev=points.get(points.size()-1);double pv=distance(prev,edge);
            for(Point point:points){
                double value=distance(point,edge);
                if((pv>=0)!=(value>=0)){
                    double t=pv/(pv-value);next.add(new Point(prev.x+(point.x-prev.x)*t,prev.z+(point.z-prev.z)*t));
                }
                if(value>=0)next.add(point);prev=point;pv=value;
            }
            points=next;
        }
        return points;
    }
    private double distance(Point p,int edge){
        return switch(edge){case 0->sourceX(p.x,p.z);case 1->1-sourceX(p.x,p.z);case 2->sourceZ(p.x,p.z);default->1-sourceZ(p.x,p.z);};
    }
}
