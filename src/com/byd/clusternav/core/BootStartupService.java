package com.byd.clusternav.core;

import android.app.*;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import com.byd.clusternav.adb.AdbClient;
import com.byd.clusternav.adb.ShellResult;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns the bounded wait for local ADB after the boot broadcast has returned. */
public final class BootStartupService extends Service {
    public static final String ACTION_BOOT="com.byd.clusternav.action.BOOT_NAVIGATION";
    private static final String TAG="ClusterBootStartup";
    private static final long BOOT_WAIT_MS=120000;
    private final AtomicBoolean scheduled=new AtomicBoolean();
    private final ExecutorService worker=Executors.newSingleThreadExecutor();

    @Override public void onCreate(){
        super.onCreate();
        NotificationManager manager=getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("boot-startup","仪表导航开机启动",NotificationManager.IMPORTANCE_LOW));
        Intent open=new Intent(this,com.byd.clusternav.ui.MainActivity.class);
        PendingIntent pending=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        startForeground(25,new Notification.Builder(this,"boot-startup")
                .setContentTitle("等待车机调试连接")
                .setContentText("仪表导航助手正在准备开机导航")
                .setSmallIcon(android.R.drawable.ic_menu_compass).setContentIntent(pending).setOngoing(true).build());
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&ACTION_BOOT.equals(intent.getAction())&&scheduled.compareAndSet(false,true))
            worker.execute(()->runBoot(startId));
        return START_NOT_STICKY;
    }

    private void runBoot(int startId){
        try{
            SharedPreferences prefs=getSharedPreferences("diagnostic26",MODE_PRIVATE);
            if(!prefs.getBoolean("auto_boot_enabled",true)){
                if(prefs.getBoolean("auto_start_app",false)){
                    Intent open=new Intent(this,com.byd.clusternav.ui.MainActivity.class);
                    open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(open);
                }
                return;
            }
            long deadline=SystemClock.elapsedRealtime()+BOOT_WAIT_MS;
            while(!Thread.currentThread().isInterrupted()&&SystemClock.elapsedRealtime()<deadline){
                if(!prefs.getBoolean("auto_boot_enabled",true))return;
                try(Socket socket=new Socket()){
                    socket.connect(new InetSocketAddress("127.0.0.1",5555),250);
                    ShellResult result=new AdbClient(this).execute("echo adb_ready",2);
                    if(result.success&&result.output.contains("adb_ready")){
                        if(!prefs.getBoolean("auto_boot_enabled",true))return;
                        SessionEngine.get(this).test(prefs.getInt("boot_mode",4),false,
                                prefs.getBoolean("restartMap",true),null,"com.byd.automap",true,
                                prefs.getBoolean("auto_return_home",true));
                        return;
                    }
                }catch(Exception e){Log.d(TAG,"Waiting for local ADB: "+e.getMessage());}
                Thread.sleep(450);
            }
            Log.w(TAG,"Boot navigation stopped: local ADB not ready within two minutes");
        }catch(InterruptedException e){Thread.currentThread().interrupt();}
        catch(Exception e){Log.e(TAG,"Boot navigation failed",e);}
        finally{scheduled.set(false);stopSelf(startId);}
    }

    @Override public void onDestroy(){worker.shutdownNow();super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
