package com.byd.clusternav.core;

import android.app.*;
import android.content.Intent;
import android.os.IBinder;

/** Foreground service for an active navigation test or diagnostic capture. */
public final class SessionService extends Service {
    @Override public void onCreate(){
        super.onCreate();
        NotificationManager manager=getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("diagnostic","仪表导航运行",NotificationManager.IMPORTANCE_LOW));
        Intent open=new Intent(this,com.byd.clusternav.ui.MainActivity.class);
        PendingIntent pending=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        startForeground(24,new Notification.Builder(this,"diagnostic")
                .setContentTitle("仪表导航正在启动或诊断")
                .setContentText("点击返回控制台，可随时关闭仪表地图")
                .setSmallIcon(android.R.drawable.ic_menu_compass).setContentIntent(pending).setOngoing(true).build());
    }
    @Override public int onStartCommand(Intent intent,int flags,int id){return START_NOT_STICKY;}
    @Override public IBinder onBind(Intent intent){return null;}
}
