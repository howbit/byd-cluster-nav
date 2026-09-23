package com.byd.automap.service;
public final class PushService extends android.app.Service {
    public void onCreate(){super.onCreate();boolean support=false;
        for(android.view.Display display:((android.hardware.display.DisplayManager)getSystemService(DISPLAY_SERVICE)).getDisplays())if(display.getName().startsWith("fission_"))support=true;
        getSharedPreferences("cache",0).edit().putBoolean("support",support).putInt("pid",android.os.Process.myPid()).commit();
    }
    public android.os.IBinder onBind(android.content.Intent intent){return null;}
}
