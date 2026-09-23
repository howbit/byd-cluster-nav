package com.byd.clusternav.core;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;
import java.util.Locale;

/**
 * Accessibility service for automated BYD DiLink background protection and autostart configuration.
 * Automatically interacts with:
 * 1. com.byd.appstartmanagement (自启动管理 / 应用加速管理)
 * 2. com.byd.rapidmode (系统加速 / 应用控制)
 */
public class AutoSetupAccessibilityService extends AccessibilityService {
    private static final String TAG = "AutoSetupA11y";

    public static final String PACKAGE_APP_START = "com.byd.appstartmanagement";
    public static final String PACKAGE_RAPID_MODE = "com.byd.rapidmode";

    public static final String ACTION_SETUP_COMPLETED = "com.byd.clusternav.action.AUTO_SETUP_COMPLETED";
    public static final String ACTION_RESET_FLAGS = "com.byd.clusternav.action.RESET_AUTO_SETUP";
    public static final String ACTION_CANCEL = "com.byd.clusternav.action.CANCEL_AUTO_SETUP";

    private static volatile boolean appStartHandled = false;
    private static volatile boolean rapidModeHandled = false;
    private static volatile boolean appStartPending = false;
    private static volatile boolean rapidModePending = false;
    private static volatile boolean appStartFailed = false;
    private static volatile boolean rapidModeFailed = false;
    private static volatile boolean completionQueued = false;
    private static volatile boolean setupActive = false;
    private static volatile int setupGeneration = 0;
    private static volatile int scrollAttempts = 0;
    private static final int MAX_SCROLLS = 10;

    public static boolean appStartVerifiedThisProcess() { return appStartHandled; }
    public static boolean rapidModeVerifiedThisProcess() { return rapidModeHandled; }

    public static void resetFlags() {
        setupGeneration++;
        setupActive = true;
        appStartHandled = false;
        rapidModeHandled = false;
        appStartPending = false;
        rapidModePending = false;
        appStartFailed = false;
        rapidModeFailed = false;
        completionQueued = false;
        scrollAttempts = 0;
        Log.i(TAG, "AutoSetupAccessibilityService flags reset");
    }
    public static void cancelAutomation() {
        setupActive = false;
        setupGeneration++;
        appStartPending = false;
        rapidModePending = false;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            cancelAutomation();
            disableSelf();
        } else if (intent != null && ACTION_RESET_FLAGS.equals(intent.getAction())) resetFlags();
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "AutoSetupAccessibilityService connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!setupActive) return;
        if (event == null) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null) return;
        String pkgName = pkg.toString();

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            root = event.getSource();
        }
        if (root == null) return;

        try {
            if (PACKAGE_APP_START.equals(pkgName)) {
                handleAppStartManagement(root);
            } else if (PACKAGE_RAPID_MODE.equals(pkgName) && appStartHandled) {
                handleRapidMode(root, event);
            }
        } catch (Exception e) {
            Log.w(TAG, "Error processing a11y event: " + e.getMessage());
        } finally {
            root.recycle();
        }

        if (appStartHandled && rapidModeHandled && !completionQueued) {
            completionQueued = true;
            final int generation = setupGeneration;
            Log.i(TAG, "All BYD background settings configured automatically. Disabling self in 600ms.");
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (!setupActive || generation != setupGeneration) return;
                setupActive = false;
                try {
                    Intent bringBack = getPackageManager().getLaunchIntentForPackage(getPackageName());
                    if (bringBack != null) {
                        bringBack.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        startActivity(bringBack);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to bring MainActivity to front: " + e.getMessage());
                }
                sendBroadcast(new Intent(ACTION_SETUP_COMPLETED).setPackage(getPackageName()));
                disableSelf();
            }, 600);
        }
    }

    private void handleAppStartManagement(AccessibilityNodeInfo root) {
        if (appStartHandled || appStartPending || appStartFailed) return;
        CharSequence label = getApplicationInfo().loadLabel(getPackageManager());
        String targetName = label != null ? label.toString() : "仪表导航助手";

        AccessibilityNodeInfo itemNode = null;

        // 1. First priority: Search for target app name by text
        List<AccessibilityNodeInfo> textMatches = root.findAccessibilityNodeInfosByText(targetName);
        if (textMatches == null || textMatches.isEmpty()) {
            textMatches = root.findAccessibilityNodeInfosByText("仪表导航");
        }
        if (textMatches == null || textMatches.isEmpty()) {
            textMatches = root.findAccessibilityNodeInfosByText("ClusterNav");
        }

        if (textMatches != null && !textMatches.isEmpty()) {
            for (AccessibilityNodeInfo tm : textMatches) {
                if (tm != null && tm.getText() != null) {
                    String str = tm.getText().toString();
                    if (str.contains("仪表导航") || str.contains("ClusterNav")) {
                        itemNode = findRowContainer(tm);
                        if (itemNode != null) break;
                    }
                }
            }
        }

        // 2. Fallback: Search by installed_app ID
        if (itemNode == null) {
            List<AccessibilityNodeInfo> appItems = root.findAccessibilityNodeInfosByViewId("com.byd.appstartmanagement:id/installed_app");
            if (appItems == null || appItems.isEmpty()) {
                appItems = root.findAccessibilityNodeInfosByViewId("installed_app");
            }
            if (appItems != null) {
                for (AccessibilityNodeInfo item : appItems) {
                    if (item == null) continue;
                    List<AccessibilityNodeInfo> textNodes = item.findAccessibilityNodeInfosByViewId("com.byd.appstartmanagement:id/text");
                    if (textNodes == null || textNodes.isEmpty()) {
                        textNodes = item.findAccessibilityNodeInfosByViewId("text");
                    }
                    if (textNodes != null) {
                        for (AccessibilityNodeInfo t : textNodes) {
                            if (t != null && t.getText() != null) {
                                String txt = t.getText().toString();
                                if (txt.contains(targetName) || txt.contains("仪表导航") || txt.contains("ClusterNav")) {
                                    itemNode = item;
                                    break;
                                }
                            }
                        }
                    }
                    if (itemNode != null) break;
                }
            }
        }

        if (itemNode != null) {
            Log.i(TAG, "AppStartManagement: Found target app row: " + targetName);
            AccessibilityNodeInfo switchNode = findSwitchNode(itemNode);

            // In BYD AppStartManagement ("选择禁止后台启动项"):
            // Switch ON (checked / true) = 禁止后台自启 (Prohibit background start)
            // Switch OFF (unchecked / false) = 允许后台自启 (Allow background start)
            Boolean isChecked = isNodeChecked(switchNode);
            Log.i(TAG, "AppStartManagement: Current switch state isChecked=" + isChecked);

            if (isChecked == null) {
                Log.w(TAG,"AppStartManagement: switch state unknown; leaving it unchanged");
                appStartFailed = true;
                return;
            }
            if (isChecked) {
                Log.i(TAG, "AppStartManagement: Switch is ON (prohibiting autostart). Clicking to toggle OFF...");
                boolean clicked = false;
                if (switchNode != null) {
                    clicked = clickNodeOrParent(switchNode);
                }
                if (!clicked) {
                    clicked = clickNodeOrParent(itemNode);
                }
                Log.i(TAG, "AppStartManagement: Click performed, result=" + clicked);
                if (clicked) {
                    appStartPending = true;
                    verifySwitchAfterClick(true,targetName);
                } else appStartFailed = true;
                return;
            } else {
                Log.i(TAG, "AppStartManagement: Switch is already OFF (background start allowed).");
            }
            completeAppStart();
            return;
        }

        if (!appStartHandled && scrollAttempts < MAX_SCROLLS) {
            scrollAttempts++;
            Log.i(TAG, "AppStartManagement: Target app not on screen, scrolling down (attempt " + scrollAttempts + ")");
            AccessibilityNodeInfo scrollable = findScrollableNode(root);
            if (scrollable != null) {
                scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            } else {
                root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            }
        }
    }

    private void handleRapidMode(AccessibilityNodeInfo root, AccessibilityEvent event) {
        if (rapidModeHandled || rapidModePending || rapidModeFailed) return;

        CharSequence label = getApplicationInfo().loadLabel(getPackageManager());
        String targetName = label != null ? label.toString() : "仪表导航助手";

        // 1. Check if we are inside AppsControlActivity (App List)
        AccessibilityNodeInfo itemNode = null;
        List<AccessibilityNodeInfo> textMatches = root.findAccessibilityNodeInfosByText(targetName);
        if (textMatches == null || textMatches.isEmpty()) {
            textMatches = root.findAccessibilityNodeInfosByText("仪表导航");
        }
        if (textMatches != null && !textMatches.isEmpty()) {
            for (AccessibilityNodeInfo tm : textMatches) {
                if (tm != null && tm.getText() != null) {
                    String str = tm.getText().toString();
                    if (str.contains("仪表导航") || str.contains("ClusterNav")) {
                        itemNode = findRowContainer(tm);
                        if (itemNode != null) break;
                    }
                }
            }
        }

        if (itemNode == null) {
            List<AccessibilityNodeInfo> appItems = root.findAccessibilityNodeInfosByViewId("com.byd.rapidmode:id/installed_app");
            if (appItems == null || appItems.isEmpty()) {
                appItems = root.findAccessibilityNodeInfosByViewId("installed_app");
            }
            if (appItems != null) {
                for (AccessibilityNodeInfo item : appItems) {
                    if (item == null) continue;
                    List<AccessibilityNodeInfo> textNodes = item.findAccessibilityNodeInfosByViewId("com.byd.rapidmode:id/text");
                    if (textNodes == null || textNodes.isEmpty()) {
                        textNodes = item.findAccessibilityNodeInfosByViewId("text");
                    }
                    if (textNodes != null) {
                        for (AccessibilityNodeInfo t : textNodes) {
                            if (t != null && t.getText() != null) {
                                String txt = t.getText().toString();
                                if (txt.contains(targetName) || txt.contains("仪表导航") || txt.contains("ClusterNav")) {
                                    itemNode = item;
                                    break;
                                }
                            }
                        }
                    }
                    if (itemNode != null) break;
                }
            }
        }

        if (itemNode != null) {
            AccessibilityNodeInfo switchNode = findSwitchNode(itemNode);
            Boolean isChecked = isNodeChecked(switchNode);
            Log.i(TAG, "RapidMode AppsControlActivity: target app found, switch isChecked=" + isChecked);

            // In BYD RapidMode AppsControlActivity:
            // Switch ON (checked) = Accelerated whitelist (允许后台常驻保护)
            // If not checked, click to enable!
            if (isChecked == null) {
                Log.w(TAG,"RapidMode: switch state unknown; leaving it unchanged");
                rapidModeFailed = true;
                return;
            }
            if (!isChecked) {
                Log.i(TAG, "RapidMode: Enabling rapid acceleration whitelist for " + targetName);
                boolean clicked = false;
                if (switchNode != null) {
                    clicked = clickNodeOrParent(switchNode);
                }
                if (!clicked) {
                    clicked = clickNodeOrParent(itemNode);
                }
                Log.i(TAG, "RapidMode: Click result=" + clicked);
                if (clicked) {
                    rapidModePending = true;
                    verifySwitchAfterClick(false,targetName);
                } else rapidModeFailed = true;
                return;
            } else {
                Log.i(TAG, "RapidMode: Acceleration whitelist already enabled for " + targetName);
            }
            completeRapidMode();
            return;
        }

        // 2. We are on the RapidModeActivity overview screen: click into "应用加速管理"
        boolean clicked = false;
        List<AccessibilityNodeInfo> listEntries = root.findAccessibilityNodeInfosByViewId("com.byd.rapidmode:id/ll_apps_list");
        if (listEntries == null || listEntries.isEmpty()) {
            listEntries = root.findAccessibilityNodeInfosByViewId("ll_apps_list");
        }
        if (listEntries != null && !listEntries.isEmpty()) {
            Log.i(TAG, "RapidModeActivity: Clicking ll_apps_list to enter AppsControlActivity");
            clicked = clickNodeOrParent(listEntries.get(0));
        }

        if (!clicked) {
            List<AccessibilityNodeInfo> textEntries = root.findAccessibilityNodeInfosByText("应用加速管理");
            if (textEntries != null && !textEntries.isEmpty()) {
                Log.i(TAG, "RapidModeActivity: Clicking '应用加速管理' text node to enter AppsControlActivity");
                for (AccessibilityNodeInfo tn : textEntries) {
                    if (clickNodeOrParent(tn)) {
                        clicked = true;
                        break;
                    }
                }
            }
        }

        if (!rapidModeHandled) {
            AccessibilityNodeInfo scrollable = findScrollableNode(root);
            if (scrollable != null) {
                scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            } else {
                root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            }
        }
    }

    private void verifySwitchAfterClick(boolean appStart,String targetName) {
        final int generation = setupGeneration;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!setupActive || generation != setupGeneration) return;
            String expectedPackage=appStart?PACKAGE_APP_START:PACKAGE_RAPID_MODE;
            Boolean observed=null;
            AccessibilityNodeInfo current=getRootInActiveWindow();
            if (current != null) {
                try {
                    if(current.getPackageName()!=null && expectedPackage.contentEquals(current.getPackageName())) {
                        AccessibilityNodeInfo row=findTargetRow(current,targetName);
                        observed=isNodeChecked(findSwitchNode(row));
                    }
                } finally {current.recycle();}
            }
            boolean desired=SwitchEvidence.desired(observed,!appStart);
            if (appStart) {
                appStartPending=false;
                if(desired)completeAppStart();else appStartFailed=true;
            } else {
                rapidModePending=false;
                if(desired)completeRapidMode();else rapidModeFailed=true;
            }
            if(!desired)Log.w(TAG,"Switch click could not be verified for "+expectedPackage+"; observed="+observed);
        },700);
    }

    private AccessibilityNodeInfo findTargetRow(AccessibilityNodeInfo root,String targetName) {
        if(root==null)return null;
        for(String text:new String[]{targetName,"仪表导航"}){
            List<AccessibilityNodeInfo> matches=root.findAccessibilityNodeInfosByText(text);
            if(matches==null)continue;
            for(AccessibilityNodeInfo match:matches){
                if(match!=null&&match.getText()!=null&&match.getText().toString().contains(text))
                    return findRowContainer(match);
            }
        }
        return null;
    }

    private void completeAppStart() {
        if(appStartHandled || !setupActive)return;
        appStartHandled=true;
        getSharedPreferences("diagnostic26",MODE_PRIVATE).edit()
                .putBoolean("status_app_start_allowed",true)
                .putLong("status_app_start_time",System.currentTimeMillis()).apply();
        final int generation=setupGeneration;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if(!setupActive || generation!=setupGeneration)return;
            AccessibilityNodeInfo current=getRootInActiveWindow();
            if(current!=null){try{closeAppStartDialog(current);}finally{current.recycle();}}
            else performGlobalAction(GLOBAL_ACTION_BACK);
        },400);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if(!setupActive || generation!=setupGeneration)return;
            try{
                Intent intent=new Intent();
                intent.setClassName(PACKAGE_RAPID_MODE,"com.byd.rapidmode.RapidModeActivity");
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            }catch(Exception e){Log.w(TAG,"Cannot launch RapidMode from a11y service",e);}
        },900);
    }

    private void completeRapidMode() {
        if(rapidModeHandled || !setupActive)return;
        rapidModeHandled=true;
        getSharedPreferences("diagnostic26",MODE_PRIVATE).edit()
                .putBoolean("status_rapid_mode_enabled",true)
                .putLong("status_rapid_mode_time",System.currentTimeMillis()).apply();
        final int generation=setupGeneration;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if(!setupActive || generation!=setupGeneration)return;
            AccessibilityNodeInfo current=getRootInActiveWindow();
            if(current!=null){
                try{
                    List<AccessibilityNodeInfo> back=current.findAccessibilityNodeInfosByViewId("com.byd.rapidmode:id/btn_back");
                    if(back==null||back.isEmpty()||!clickNodeOrParent(back.get(0)))performGlobalAction(GLOBAL_ACTION_BACK);
                }finally{current.recycle();}
            }else performGlobalAction(GLOBAL_ACTION_BACK);
        },400);
    }

    private AccessibilityNodeInfo findScrollableNode(AccessibilityNodeInfo root) {
        if (root == null) return null;
        if (root.isScrollable()) return root;
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            AccessibilityNodeInfo found = findScrollableNode(child);
            if (found != null) return found;
        }
        return null;
    }

    private AccessibilityNodeInfo findRowContainer(AccessibilityNodeInfo node) {
        if (node == null) return null;
        AccessibilityNodeInfo curr = node;
        for (int depth = 0; depth < 5; depth++) {
            AccessibilityNodeInfo parent = curr.getParent();
            if (parent == null) return curr;
            CharSequence cls = parent.getClassName();
            if (cls != null) {
                String c = cls.toString();
                if (c.contains("ListView") || c.contains("RecyclerView") || c.contains("ScrollView")) {
                    return curr;
                }
            }
            if (parent.getChildCount() >= 2 && findSwitchNode(parent) != null) {
                return parent;
            }
            curr = parent;
        }
        return curr;
    }

    private AccessibilityNodeInfo findSwitchNode(AccessibilityNodeInfo root) {
        if (root == null) return null;
        if (root.isCheckable()) return root;
        CharSequence cls = root.getClassName();
        if (cls != null) {
            String c = cls.toString();
            if (c.contains("Switch") || c.contains("CheckBox") || c.contains("CompoundButton") || c.contains("ToggleButton")) {
                return root;
            }
        }
        CharSequence resId = root.getViewIdResourceName();
        if (resId != null) {
            String id = resId.toString().toLowerCase(Locale.ROOT);
            if (id.contains("select_button") || id.contains("switch") || id.contains("toggle") || id.contains("check")) {
                return root;
            }
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            AccessibilityNodeInfo found = findSwitchNode(child);
            if (found != null) return found;
        }
        return null;
    }

    private Boolean isNodeChecked(AccessibilityNodeInfo node) {
        if (node == null) return null;
        CharSequence desc = node.getContentDescription();
        CharSequence txt = node.getText();
        return SwitchEvidence.read(node.isCheckable(),node.isChecked(),desc==null?null:desc.toString(),txt==null?null:txt.toString());
    }

    private void closeAppStartDialog(AccessibilityNodeInfo root) {
        if (root == null) {
            performGlobalAction(GLOBAL_ACTION_BACK);
            return;
        }
        for (String idName : new String[]{"iv_close", "btn_close", "close", "ib_close", "iv_back", "btn_back"}) {
            List<AccessibilityNodeInfo> closeBtns = root.findAccessibilityNodeInfosByViewId("com.byd.appstartmanagement:id/" + idName);
            if (closeBtns == null || closeBtns.isEmpty()) {
                closeBtns = root.findAccessibilityNodeInfosByViewId(idName);
            }
            if (closeBtns != null && !closeBtns.isEmpty()) {
                if (clickNodeOrParent(closeBtns.get(0))) return;
            }
        }
        for (String txt : new String[]{"关闭", "确定", "完成", "取消"}) {
            List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByText(txt);
            if (list != null && !list.isEmpty()) {
                if (clickNodeOrParent(list.get(0))) return;
            }
        }
        performGlobalAction(GLOBAL_ACTION_BACK);
    }

    private boolean clickNodeOrParent(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        AccessibilityNodeInfo p = node.getParent();
        if (p != null) {
            if (p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            AccessibilityNodeInfo pp = p.getParent();
            if (pp != null && pp.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        }
        return false;
    }

    @Override
    public void onInterrupt() {
        Log.i(TAG, "AutoSetupAccessibilityService interrupted");
    }
}
