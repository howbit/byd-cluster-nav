package com.byd.clusternav.core;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/** Hand the boot task to a foreground service so the broadcast can finish promptly. */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "ClusterBootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Log.i(TAG, "BootReceiver received action: " + action);

        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)) return;

        SharedPreferences prefs = context.getSharedPreferences("diagnostic26", Context.MODE_PRIVATE);
        boolean autoStartApp = prefs.getBoolean("auto_start_app", false);
        boolean autoBootMap = prefs.getBoolean("auto_boot_enabled", true);
        if (!autoStartApp && !autoBootMap) {
            Log.i(TAG, "Neither auto start app nor auto boot map is enabled. Ignoring boot event.");
            return;
        }

        try {
            context.startForegroundService(new Intent(context,BootStartupService.class).setAction(BootStartupService.ACTION_BOOT));
        } catch (Exception e) {
            Log.e(TAG,"Unable to schedule boot navigation",e);
        }
    }
}
