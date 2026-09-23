package com.byd.clusternav.tests;
public final class DisplayKeeper {
    public static void main(String[] args)throws Exception{
        java.lang.reflect.Method method=com.byd.clusternav.core.NativeBridge.class.getDeclaredMethod("context");method.setAccessible(true);
        android.content.Context context=((android.content.Context)method.invoke(null)).createPackageContext("com.android.shell",0);
        android.graphics.SurfaceTexture texture=new android.graphics.SurfaceTexture(false);
        android.view.Surface surface=new android.view.Surface(texture);
        android.hardware.display.VirtualDisplay display=((android.hardware.display.DisplayManager)context.getSystemService("display")).createVirtualDisplay("fission_test26",800,480,160,surface,1|2|8);
        if(display==null)throw new IllegalStateException("No display");
        System.out.println("DISPLAY="+display.getDisplay().getDisplayId());System.out.flush();
        try{Thread.sleep(180000);}finally{display.release();surface.release();texture.release();}
        System.exit(0);
    }
}
