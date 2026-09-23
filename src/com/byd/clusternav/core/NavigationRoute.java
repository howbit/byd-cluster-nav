package com.byd.clusternav.core;

/** Route selection is independent of Android's local Display inventory. */
public final class NavigationRoute {
    public static final String CONTAINER="container", LOCAL="local", NONE="none";
    public static boolean containerSupported(String singleOs,String product,boolean hasManager) {
        // Freedom 1.10 explicitly excludes these single-system/newer products.
        return hasManager && "0".equals(singleOs) && product!=null && !product.isEmpty()
            && !product.equals("DiLink5.0") && !product.equals("DiLink6.0")
            && !product.equals("DiLink5.1") && !product.equals("IVI");
    }
    public static String preferred(String singleOs,String product,boolean hasManager,boolean hasAuto) {
        if(containerSupported(singleOs,product,true))return hasManager?CONTAINER:NONE;
        return hasAuto?LOCAL:NONE;
    }
    public static String validate(String route) {
        if(!CONTAINER.equals(route)&&!LOCAL.equals(route))throw new IllegalArgumentException("Unknown navigation route");
        return route;
    }
    public static int containerCommand(int mode) {
        if(mode==4)return 16;
        if(mode==3)return 17;
        if(mode==0)return 18;
        throw new IllegalArgumentException("Unknown navigation mode");
    }
}
