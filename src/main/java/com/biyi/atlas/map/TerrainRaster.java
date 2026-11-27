package com.biyi.atlas.map;

/** 世界对齐的128格地图图块，1像素/方块；平滑真实高程并连续插值地貌。 */
public final class TerrainRaster {
    public static final int SIZE = 128;
    private TerrainRaster() {}
    public static int[] bake(TerrainSurvey data, int tx, int tz) {
        return bake(data,tx,tz,0);
    }
    public static int[] bakePadded(TerrainSurvey data,int tx,int tz){return bake(data,tx,tz,1);}
    private static int[] bake(TerrainSurvey data,int tx,int tz,int pad){
        int stride=SIZE+pad*2;int[] pixels = new int[stride * stride];
        double[][] ground=new double[37][37];
        for(int z=-2;z<=34;z++)for(int x=-2;x<=34;x++)
            ground[z+2][x+2]=data.has(tx*32+x,tz*32+z)?naturalHeight(data,tx*32+x,tz*32+z):Double.NaN;
        double[][] heights = new double[35][35];
        for (int z = -1; z <= 33; z++) for (int x = -1; x <= 33; x++) {
            int gx = tx * 32 + x, gz = tz * 32 + z;
            if (!data.has(gx, gz)) { heights[z + 1][x + 1] = Double.NaN; continue; }
            double sum = 0, weight = 0;
            for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
                if (!data.has(gx + dx, gz + dz)) continue;
                double w = (dx == 0 ? 2 : 1) * (dz == 0 ? 2 : 1);
                sum += ground[z+dz+2][x+dx+2] * w; weight += w;
            }
            heights[z + 1][x + 1] = sum / weight;
        }
        for (int pz = -pad; pz < SIZE+pad; pz++) for (int px = -pad; px < SIZE+pad; px++) {
            int ix = Math.floorDiv(px,4), iz = Math.floorDiv(pz,4);
            int gx = tx * 32 + ix, gz = tz * 32 + iz;
            if (!data.has(gx, gz) || !data.has(gx + 1, gz) || !data.has(gx, gz + 1) || !data.has(gx + 1, gz + 1)) continue;
            double u = (Math.floorMod(px,4) + .5) / 4, v = (Math.floorMod(pz,4) + .5) / 4;
            double[] weights = {(1-u)*(1-v), u*(1-v), (1-u)*v, u*v};
            int[] values = {data.get(gx,gz),data.get(gx+1,gz),data.get(gx,gz+1),data.get(gx+1,gz+1)};
            double a = heights[iz+1][ix+1], b = heights[iz+1][ix+2];
            double c = heights[iz+2][ix+1], d = heights[iz+2][ix+2];
            double h = a*weights[0]+b*weights[1]+c*weights[2]+d*weights[3];
            double dx = ((b-a)*(1-v)+(d-c)*v)/4;
            double dz = ((c-a)*(1-u)+(d-b)*u)/4;
            double water=0, forest=0, snow=0, sand=0, lava=0;
            for (int i=0;i<4;i++) switch(TerrainSurvey.kind(values[i])) {
                case TerrainSurvey.WATER -> water += weights[i];
                case TerrainSurvey.FOREST -> forest += weights[i];
                case TerrainSurvey.SNOW -> snow += weights[i];
                case TerrainSurvey.SAND -> sand += weights[i];
                case TerrainSurvey.LAVA -> lava += weights[i];
            }
            double w = smooth(.35,.65,water), f = forest*(1-w), s=snow*(1-w);
            double shade = Math.max(-17,Math.min(14,(-dx-dz)*5));
            // 各图块按世界位置使用同一细粒纹理，避免随机重绘和接缝。
            int wx=tx*SIZE+px, wz=tz*SIZE+pz;
            double grain = (Math.floorMod(wx*73856093 ^ wz*19349663,101)/100.0-.5)*2;
            double r=218+shade+grain-22*f+16*s+3*sand;
            double g=221+shade+grain-15*f+16*s-2*sand;
            double bl=214+shade+grain-16*f+23*s-8*sand;
            // 高差越陡，等高线相对更稀；线宽按高程梯度折算成亚像素覆盖。
            double interval=16;
            double distance=Math.abs(h/interval-Math.rint(h/interval))*interval;
            double ink=(1-smooth(.1,Math.max(.5,Math.hypot(dx,dz)*.65),distance))*.15*(1-w)
                    *Math.min(1,Math.hypot(dx,dz)*2);
            r*=1-ink;g*=1-ink;bl*=1-ink;
            // 林地是细微的俯视树冠明暗，不使用规则圆点阵列。
            double canopy=(noise(wx/7.3,wz/7.3)+noise(wx/2.7,wz/2.7)*.35-.675)*2.2*f;
            r+=canopy;g+=canopy;bl+=canopy;
            r=r*(1-w)+77*w;g=g*(1-w)+101*w;bl=bl*(1-w)+105*w;
            r=r*(1-lava)+133*lava;g=g*(1-lava)+98*lava;bl=bl*(1-lava)+85*lava;
            if(data.buildingAt(wx,wz)) {
                boolean border=!data.buildingAt(wx-1,wz)||!data.buildingAt(wx+1,wz)
                        ||!data.buildingAt(wx,wz-1)||!data.buildingAt(wx,wz+1);
                r=border?145:238;g=border?157:239;bl=border?151:231;
            }
            int alpha=232;
            // 探索边缘渐隐，不用矩形边界硬切。
            double edge=1;
            for(int ez=-1;ez<=1;ez++) for(int ex=-1;ex<=1;ex++)
                if(!data.has(gx+ex,gz+ez)) edge=Math.min(edge,.35);
            alpha=(int)(alpha*edge);
            pixels[(pz+pad)*stride+px+pad]=alpha<<24|channel(r)<<16|channel(g)<<8|channel(bl);
        }
        return pixels;
    }
    private static int channel(double v){return Math.max(0,Math.min(255,(int)Math.round(v)));}
    /** 屋顶下不可直接观测的高程，以附近非建筑表面插值，避免画出屋顶等高山包。 */
    public static double naturalHeight(TerrainSurvey data,int x,int z){
        int sample=data.get(x,z);
        if(TerrainSurvey.kind(sample)!=TerrainSurvey.BUILDING)return TerrainSurvey.height(sample);
        for(int radius=1;radius<=8;radius++){
            double sum=0;int n=0;
            for(int dz=-radius;dz<=radius;dz++)for(int dx=-radius;dx<=radius;dx++){
                if(Math.max(Math.abs(dx),Math.abs(dz))!=radius||!data.has(x+dx,z+dz))continue;
                int value=data.get(x+dx,z+dz);
                if(TerrainSurvey.kind(value)==TerrainSurvey.BUILDING)continue;
                sum+=TerrainSurvey.height(value);n++;
            }
            if(n>0)return sum/n;
        }
        return TerrainSurvey.height(sample);
    }
    private static double noise(double x,double z){
        int a=(int)Math.floor(x),b=(int)Math.floor(z);
        double u=x-a,v=z-b;u=u*u*(3-2*u);v=v*v*(3-2*v);
        return hash(a,b)*(1-u)*(1-v)+hash(a+1,b)*u*(1-v)+hash(a,b+1)*(1-u)*v+hash(a+1,b+1)*u*v;
    }
    private static double hash(int x,int z){return Math.floorMod(x*73856093^z*19349663,1009)/1008.0;}
    private static double smooth(double low,double high,double x){
        double t=Math.max(0,Math.min(1,(x-low)/Math.max(.0001,high-low)));return t*t*(3-2*t);
    }
}
