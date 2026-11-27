package com.biyi.atlas.map;
import java.util.*;
/** 将可见格子精确合并为矩形，保留锁区孔洞。 */
public final class TileMask {
    private TileMask(){}
    public static List<int[]> rectangles(boolean[] mask,int side){
        if(mask.length!=side*side)throw new IllegalArgumentException("mask size");
        List<int[]> result=new ArrayList<>();Map<Integer,int[]> previous=new HashMap<>();
        for(int row=0;row<side;row++){
            int start=-1;
            for(int col=0;col<=side;col++){
                boolean show=col<side&&mask[row*side+col];
                if(show&&start<0)start=col;
                if(!show&&start>=0){
                    int key=start*(side+1)+col;int[] rect=previous.get(key);
                    if(rect!=null&&rect[3]==row)rect[3]=row+1;
                    else{rect=new int[]{start,row,col,row+1};result.add(rect);previous.put(key,rect);}
                    start=-1;
                }
            }
        }
        return result;
    }
}
