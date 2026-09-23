package com.byd.clusternav.tests;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;

import com.byd.clusternav.core.BootReceiver;
import com.byd.clusternav.core.BootStartupService;

/** Exercise the real receiver and service on an emulator without changing vehicle state. */
public final class BootChecks extends Instrumentation {
    private int count;
    private void check(boolean condition,String label){count++;if(!condition)throw new AssertionError(label);}
    private boolean running(Context context){
        ActivityManager manager=(ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
        for(ActivityManager.RunningServiceInfo info:manager.getRunningServices(100))
            if(BootStartupService.class.getName().equals(info.service.getClassName()))return true;
        return false;
    }
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);start();}
    @Override public void onStart(){
        Bundle results=new Bundle();Context context=getTargetContext();
        SharedPreferences preferences=context.getSharedPreferences("diagnostic26",Context.MODE_PRIVATE);
        boolean had=preferences.contains("auto_boot_enabled");
        boolean previous=preferences.getBoolean("auto_boot_enabled",true);
        try{
            context.stopService(new Intent(context,BootStartupService.class));
            preferences.edit().putBoolean("auto_boot_enabled",true).commit();
            long start=SystemClock.elapsedRealtime();
            new BootReceiver().onReceive(context,new Intent(Intent.ACTION_BOOT_COMPLETED));
            check(SystemClock.elapsedRealtime()-start<2000,"receiver returns promptly without waiting for ADB");
            long deadline=SystemClock.elapsedRealtime()+3000;
            while(!running(context)&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(100);
            check(running(context),"separate foreground boot service owns the wait");
            context.stopService(new Intent(context,BootStartupService.class));
            deadline=SystemClock.elapsedRealtime()+3000;
            while(running(context)&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(100);
            check(!running(context),"boot wait can be cancelled");
            results.putString("result","PASS "+count+" boot runtime assertions");
            finish(Activity.RESULT_OK,results);
        }catch(Throwable error){results.putString("result","FAIL "+error);finish(Activity.RESULT_CANCELED,results);}
        finally{
            context.stopService(new Intent(context,BootStartupService.class));
            SharedPreferences.Editor editor=preferences.edit();
            if(had)editor.putBoolean("auto_boot_enabled",previous);else editor.remove("auto_boot_enabled");
            editor.commit();
        }
    }
}
