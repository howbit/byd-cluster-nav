package com.byd.clusternav.core;
public final class BootEvidence {
    public static boolean changed(String beforeId,String afterId,int beforeCount,int afterCount,long beforeTime,long afterTime){
        if(beforeId!=null&&afterId!=null&&!beforeId.isEmpty()&&!afterId.isEmpty()&&!beforeId.equals(afterId))return true;
        if(beforeCount>=0&&afterCount>=0&&beforeCount!=afterCount)return true;
        return beforeTime>0&&afterTime>=0&&afterTime+1000<beforeTime;
    }
}
