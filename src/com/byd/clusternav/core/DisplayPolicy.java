package com.byd.clusternav.core;

/** Pure policy shared with JVM regression tests; IDs never inferred from array order. */
public final class DisplayPolicy {
    public static boolean isOverlay(String name,int type,String uniqueId) {
        String n=name.toLowerCase(java.util.Locale.ROOT);
        return type==4 || uniqueId.startsWith("overlay:") || n.contains("overlay") || name.contains("叠加") || name.contains("模拟");
    }
    public static boolean selectable(int id,String name,int type,String uniqueId,boolean valid) {
        if(id<=0 || !valid || isOverlay(name,type,uniqueId))return false;
        // Unknown generic virtual displays are not established cluster routes.
        return type==1 || type==2 || (name.startsWith("fission_") && type>=0);
    }
    public static boolean autoCandidate(int id,String name,int type,String uniqueId,boolean valid) {
        return selectable(id,name,type,uniqueId,valid) && name.startsWith("fission_");
    }
}
