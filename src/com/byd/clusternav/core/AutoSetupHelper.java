package com.byd.clusternav.core;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;

import com.byd.clusternav.adb.AdbClient;
import com.byd.clusternav.adb.ShellResult;

/**
 * Helper to orchestrate BYD DiLink developer options, ADB permissions,
 * and automated background whitelist setup (com.byd.rapidmode & com.byd.appstartmanagement).
 */
public class AutoSetupHelper {
    private static final String TAG = "AutoSetupHelper";

    public static final String PKG_DEVELOPMENT_TOOLS = "com.byd.byddevelopmenttools";
    public static final String CLS_ADB_SETTINGS = "com.byd.byddevelopmenttools.ADBSettingsActivity";

    public static final String PKG_APP_START = "com.byd.appstartmanagement";
    public static final String CLS_APP_START = "com.byd.appstartmanagement.frame.AppStartManagement";

    public static final String PKG_RAPID_MODE = "com.byd.rapidmode";
    public static final String CLS_RAPID_MODE = "com.byd.rapidmode.RapidModeActivity";

    /**
     * Launch BYD Developer Tools ADB settings activity safely.
     * Note: ADBSettingsActivity is not exported from uid 1000, so ordinary app UIDs
     * cannot start it via context.startActivity(). We prioritize ADB shell (uid 2000).
     */
    public static boolean openAdbDeveloperSettings(Context context) {
        // 1. Try launching through ADB shell first (uid 2000 has START_ANY_ACTIVITY permission)
        try {
            com.byd.clusternav.adb.AdbClient client = new com.byd.clusternav.adb.AdbClient(context);
            com.byd.clusternav.adb.ShellResult res = client.execute("am start -n " + PKG_DEVELOPMENT_TOOLS + "/" + CLS_ADB_SETTINGS, 2);
            if (res != null && res.success) {
                Log.i(TAG, "Launched BYD ADB settings via ADB shell");
                return true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "ADB shell launch attempt failed: " + t.getMessage());
        }

        // 2. Fallback to standard developer settings or general settings via context
        try {
            Intent fallback = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(fallback);
            return true;
        } catch (Throwable e) {
            Log.w(TAG, "Standard dev settings failed, trying general settings", e);
            try {
                Intent sys = new Intent(Settings.ACTION_SETTINGS);
                sys.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(sys);
                return true;
            } catch (Throwable ex) {
                Log.e(TAG, "All settings intents failed", ex);
                return false;
            }
        }
    }

    /**
     * Launch BYD Autostart Management activity directly.
     */
    public static boolean openAppStartManagement(Context context) {
        try {
            Intent intent = new Intent();
            intent.setClassName(PKG_APP_START, CLS_APP_START);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Cannot open AppStartManagement via Intent", e);
            return false;
        }
    }

    /**
     * Launch BYD RapidMode activity directly.
     */
    public static boolean openRapidMode(Context context) {
        try {
            Intent intent = new Intent();
            intent.setClassName(PKG_RAPID_MODE, CLS_RAPID_MODE);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Cannot open RapidMode via Intent", e);
            return false;
        }
    }

    /**
     * Execute full automated whitelist and keepalive configuration via ADB Shell.
     */
    public static String runAutomatedWhitelistSetup(AdbClient adb, Context context) {
        if (adb == null) {
            return "ADB 客户端未就绪，请先确认 127.0.0.1:5555 已连接";
        }

        String pkg = context.getPackageName();
        StringBuilder log = new StringBuilder();

        // 0. Reset accessibility service flags and clear stale status
        AutoSetupAccessibilityService.resetFlags();
        context.getSharedPreferences("diagnostic26", Context.MODE_PRIVATE).edit()
                .remove("status_app_start_allowed")
                .remove("status_rapid_mode_enabled")
                .remove("status_deviceidle_whitelisted")
                .apply();

        // 1. Grant SYSTEM_ALERT_WINDOW, RUN_IN_BACKGROUND and AppOps
        log.append("1. 授予悬浮窗与后台运行权限...\n");
        adb.execute("pm grant " + pkg + " android.permission.SYSTEM_ALERT_WINDOW"); // Special permission; appops result is authoritative.
        record(log,"悬浮窗",adb.execute("appops set " + pkg + " SYSTEM_ALERT_WINDOW allow"));
        record(log,"后台运行",adb.execute("appops set " + pkg + " RUN_IN_BACKGROUND allow"));
        record(log,"后台运行扩展",adb.execute("appops set " + pkg + " RUN_ANY_IN_BACKGROUND allow"));
        record(log,"忽略电池优化尝试",adb.execute("appops set " + pkg + " IGNORE_BATTERY_OPTIMIZATIONS allow"));

        // 2. Battery / DeviceIdle Whitelist
        log.append("2. 加入系统电池优化白名单 (deviceidle whitelist)...\n");
        ShellResult idleSet=adb.execute("cmd deviceidle whitelist +" + pkg);
        if(!idleSet.success)idleSet=adb.execute("dumpsys deviceidle whitelist +" + pkg);
        ShellResult idleRead=adb.execute("dumpsys deviceidle whitelist",5);
        boolean idleVerified=ConfigurationEvidence.deviceIdleVerified(idleSet.success,idleRead.success,idleRead.output,pkg);
        log.append("DeviceIdle 读回：").append(idleVerified?"已验证":"未验证").append('\n');
        context.getSharedPreferences("diagnostic26", Context.MODE_PRIVATE).edit()
                .putBoolean("status_deviceidle_whitelisted", idleVerified)
                .apply();

        // 3. Enable Accessibility Service silently via ADB
        log.append("3. 静默激活无障碍自动化服务...\n");
        String a11yComponent = pkg + "/com.byd.clusternav.core.AutoSetupAccessibilityService";
        ShellResult accessibilityEnabled=adb.execute("settings put secure accessibility_enabled 1");
        String a11yCmd = "cur=$(settings get secure enabled_accessibility_services); " +
                "if [ \"$cur\" = \"null\" ] || [ -z \"$cur\" ]; then " +
                "settings put secure enabled_accessibility_services " + a11yComponent + "; " +
                "elif ! echo \"$cur\" | grep -q \"" + a11yComponent + "\"; then " +
                "settings put secure enabled_accessibility_services \"$cur:" + a11yComponent + "\"; fi";
        ShellResult accessibilityList=adb.execute(a11yCmd);
        ShellResult accessibilityRead=adb.execute("settings get secure enabled_accessibility_services",5);
        boolean accessibilityVerified=accessibilityEnabled.success&&accessibilityList.success
                &&accessibilityRead.success&&accessibilityRead.output.contains(a11yComponent);
        log.append("无障碍服务列表读回：").append(accessibilityVerified?"已验证":"未验证").append('\n');

        // 4. Trigger BYD AppStartManagement
        log.append("4. 唤起自启动管理 (com.byd.appstartmanagement)...\n");
        ShellResult appStartPage=adb.execute("am start -n " + PKG_APP_START + "/.frame.AppStartManagement");
        record(log,"自启动设置页面",appStartPage);

        // Sleep to let accessibility service handle AppStartManagement and chain into RapidMode
        try {
            Thread.sleep(3500);
        } catch (InterruptedException ignored) {}

        // 5. If RapidMode hasn't completed yet, trigger it as fallback
        if (context.getSharedPreferences("diagnostic26", Context.MODE_PRIVATE).getBoolean("status_app_start_allowed", false)
                && !context.getSharedPreferences("diagnostic26", Context.MODE_PRIVATE).getBoolean("status_rapid_mode_enabled", false)) {
            log.append("5. 唤起系统加速管理 (com.byd.rapidmode.RapidModeActivity)...\n");
            record(log,"加速设置页面",adb.execute("am start -n " + PKG_RAPID_MODE + "/" + CLS_RAPID_MODE));
        }

        log.append("配置命令已执行；请以页面读回的各项状态为准。");
        return log.toString();
    }
    private static void record(StringBuilder log,String label,ShellResult result){
        log.append(label).append('：').append(result.success?"命令返回成功":"命令失败").append('\n');
        if(!result.success)Log.w(TAG,label+" failed: "+result.error+" "+result.output);
    }
}
