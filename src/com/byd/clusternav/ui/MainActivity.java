package com.byd.clusternav.ui;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.util.Log;
import android.view.*;
import android.widget.*;
import com.byd.clusternav.adb.AdbClient;
import com.byd.clusternav.adb.ShellResult;
import com.byd.clusternav.core.*;
import org.json.*;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class MainActivity extends Activity implements SessionEngine.Listener {
    private static final String PROJECT_URL = "https://github.com/howbit/byd-cluster-nav";
    private SessionEngine engine;
    private TextView statusBadge, logs, inventory, adbStatusBadge;
    private TextView appStartStatusBadge, rapidModeStatusBadge, batteryOptStatusBadge;
    private TextView clusterHardwareBadge, displayStatusBadge;
    private Spinner displays, maps;
    private CheckBox restart, autoBootMap, autoReturnHome, autoStartApp;
    private Button export, btnMeterTest, btnCloseMap, btnAutoSetup, btnOpenAdb, btnCheckAdb, btnAdvancedToggle;
    private Button tab1Btn, tab2Btn;
    private LinearLayout advancedPanel, page1Layout, page2Layout;
    private final List<Button> jobButtons = new ArrayList<>();
    private final List<String> displayValues = new ArrayList<>(), mapValues = new ArrayList<>();
    private JSONObject shownProbe;
    private LinearLayout page;

    // Daytime Light Theme Palette (Anti-glare, High Contrast)
    private final int BG_COLOR = Color.rgb(244, 247, 250);         // #F4F7FA Light silver-gray
    private final int CARD_BG = Color.rgb(255, 255, 255);          // #FFFFFF Pure white
    private final int CARD_STROKE = Color.rgb(222, 228, 236);      // #DEE4EC Subtle border
    private final int TEXT_PRIMARY = Color.rgb(26, 32, 44);         // #1A202C Deep charcoal
    private final int TEXT_MUTED = Color.rgb(100, 116, 139);        // #64748B Slate gray
    private final int ACCENT_BLUE = Color.rgb(24, 144, 255);       // #1890FF Tech Blue
    private final int ACCENT_GREEN = Color.rgb(39, 174, 96);       // #27AE60 Success green
    private final int ACCENT_RED = Color.rgb(220, 53, 69);         // #DC3545 Warning/Stop red
    private final int BTN_LIGHT_BG = Color.rgb(238, 242, 248);     // #EEF2F8 Secondary button
    private final int BTN_LIGHT_STROKE = Color.rgb(205, 215, 228);

    private int currentTab = 0;
    private int adbRetryCount = 0;
    private Toast mCurrentToast;
    private static final AtomicBoolean setupRunning=new AtomicBoolean();
    private static final AtomicBoolean setupHelperDone=new AtomicBoolean();
    private static final AtomicBoolean setupServiceDone=new AtomicBoolean();
    private static final AtomicInteger setupAttempt=new AtomicInteger();

    private void showToast(CharSequence msg, int duration) {
        runOnUiThread(() -> {
            if (mCurrentToast != null) {
                mCurrentToast.cancel();
            }
            mCurrentToast = Toast.makeText(this, msg, duration);
            mCurrentToast.show();
        });
    }

    private final BroadcastReceiver setupReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!setupRunning.get()) return;
            setupServiceDone.set(true);
            finishSetupIfReady();
        }
    };

    private void finishSetupIfReady() {
        if(!setupHelperDone.get() || !setupServiceDone.get() || !setupRunning.compareAndSet(true,false))return;
        updateOemSetupBadges();
        showToast("车机自启动与加速开关已读回；请查看各项状态",Toast.LENGTH_SHORT);
        checkAdbStatusAsync();
        switchTab(1);
        if(btnAutoSetup!=null){btnAutoSetup.setEnabled(true);btnAutoSetup.setText("★ 一键配置车机防杀白名单");}
        if(autoReturnHome!=null&&autoReturnHome.isChecked()){
            new Handler(Looper.getMainLooper()).postDelayed(() -> new Thread(() -> {
                try{SessionEngine.returnToHome(new AdbClient(MainActivity.this),MainActivity.this);}
                catch(Exception e){Log.w("MainActivity","Return home failed",e);}
            }).start(),2500);
        }
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        engine = SessionEngine.get(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG_COLOR);

        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(18), dp(20), dp(28));
        scroll.addView(page);
        setContentView(scroll);

        SharedPreferences sp = getSharedPreferences("diagnostic26", MODE_PRIVATE);
        if (!sp.contains("auto_boot_enabled")) {
            sp.edit().putBoolean("auto_boot_enabled", true).putInt("boot_mode", 4).apply();
        }
        if (!sp.contains("auto_return_home")) {
            sp.edit().putBoolean("auto_return_home", true).apply();
        }
        if (!sp.contains("restartMap")) {
            sp.edit().putBoolean("restartMap", true).apply();
        }
        registerReceiver(setupReceiver, new IntentFilter(AutoSetupAccessibilityService.ACTION_SETUP_COMPLETED));

        // Header Title
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(header, new LinearLayout.LayoutParams(-1, -2));

        TextView title = new TextView(this);
        title.setText("仪表导航助手");
        title.setTextSize(22);
        title.setTextColor(TEXT_PRIMARY);
        title.setTypeface(null, 1);
        header.addView(title);

        TextView ver = new TextView(this);
        ver.setText("  v2.9.8");
        ver.setTextSize(14);
        ver.setTextColor(ACCENT_BLUE);
        ver.setTypeface(null, 1);
        header.addView(ver);

        // Shared header entry: available from both tabs without covering navigation controls.
        header.addView(new View(this), new LinearLayout.LayoutParams(0, dp(1), 1));
        TextView projectLink = new TextView(this);
        projectLink.setText("GitHub 开源主页 ↗");
        projectLink.setTextSize(14);
        projectLink.setTextColor(ACCENT_BLUE);
        projectLink.setGravity(Gravity.CENTER);
        projectLink.setSingleLine(true);
        projectLink.setPadding(dp(12), 0, dp(12), 0);
        projectLink.setContentDescription("打开 ClusterNav GitHub 开源主页");
        GradientDrawable projectLinkBackground = new GradientDrawable();
        projectLinkBackground.setColor(CARD_BG);
        projectLinkBackground.setCornerRadius(dp(8));
        projectLinkBackground.setStroke(dp(1), ACCENT_BLUE);
        projectLink.setBackground(projectLinkBackground);
        header.addView(projectLink, new LinearLayout.LayoutParams(-2, dp(44)));
        projectLink.setOnClickListener(v -> openProjectPage());

        // Tabs Row
        LinearLayout tabsRow = new LinearLayout(this);
        tabsRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tabsLp = new LinearLayout.LayoutParams(-1, -2);
        tabsLp.setMargins(0, dp(12), 0, dp(14));
        tabsRow.setLayoutParams(tabsLp);

        tab1Btn = new Button(this);
        tab1Btn.setText("1. 首次配置向导");
        tab1Btn.setTextSize(14);
        tab1Btn.setTypeface(null, 1);
        tab1Btn.setAllCaps(false);
        tab1Btn.setMinHeight(dp(44));

        tab2Btn = new Button(this);
        tab2Btn.setText("2. 仪表导航");
        tab2Btn.setTextSize(14);
        tab2Btn.setTypeface(null, 1);
        tab2Btn.setAllCaps(false);
        tab2Btn.setMinHeight(dp(44));

        LinearLayout.LayoutParams tlp1 = new LinearLayout.LayoutParams(0, -2, 1);
        tlp1.setMargins(0, 0, dp(5), 0);
        tabsRow.addView(tab1Btn, tlp1);

        LinearLayout.LayoutParams tlp2 = new LinearLayout.LayoutParams(0, -2, 1);
        tlp2.setMargins(dp(5), 0, 0, 0);
        tabsRow.addView(tab2Btn, tlp2);

        page.addView(tabsRow);

        tab1Btn.setOnClickListener(v -> switchTab(0));
        tab2Btn.setOnClickListener(v -> switchTab(1));

        // Container for Page 1 and Page 2
        page1Layout = new LinearLayout(this);
        page1Layout.setOrientation(LinearLayout.VERTICAL);
        page.addView(page1Layout, new LinearLayout.LayoutParams(-1, -2));

        page2Layout = new LinearLayout(this);
        page2Layout.setOrientation(LinearLayout.VERTICAL);
        page.addView(page2Layout, new LinearLayout.LayoutParams(-1, -2));

        buildPage1Wizard(sp);
        buildPage2Control(sp);

        // Default tab selection: if already set up auto-boot, go straight to control tab
        int defaultTab = sp.getInt("selected_tab", sp.getBoolean("auto_boot_enabled", false) ? 1 : 0);
        switchTab(defaultTab);

        checkAdbStatusAsync();
    }

    private void openProjectPage() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_URL)));
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w("MainActivity", "No app can open project page", e);
            showToast("无法打开 GitHub 链接，请检查浏览器是否可用", Toast.LENGTH_LONG);
        }
    }

    private void switchTab(int tab) {
        currentTab = tab;
        getSharedPreferences("diagnostic26", MODE_PRIVATE).edit().putInt("selected_tab", tab).apply();

        if (tab == 0) {
            setTabActive(tab1Btn, true);
            setTabActive(tab2Btn, false);
            page1Layout.setVisibility(View.VISIBLE);
            page2Layout.setVisibility(View.GONE);
        } else {
            setTabActive(tab1Btn, false);
            setTabActive(tab2Btn, true);
            page1Layout.setVisibility(View.GONE);
            page2Layout.setVisibility(View.VISIBLE);
        }
        checkAdbStatusAsync();
    }

    private void setTabActive(Button btn, boolean active) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(8));
        if (active) {
            bg.setColor(ACCENT_BLUE);
            btn.setTextColor(Color.WHITE);
        } else {
            bg.setColor(BTN_LIGHT_BG);
            bg.setStroke(dp(1), BTN_LIGHT_STROKE);
            btn.setTextColor(TEXT_PRIMARY);
        }
        btn.setBackground(bg);
    }

    /**
     * Page 1: 首次安装配置向导 (ADB 申请 + 车机自启/应用加速防杀白名单)
     */
    private void buildPage1Wizard(SharedPreferences sp) {
        // Step 1: 无线 ADB 配置卡片
        LinearLayout adbCard = createCard();
        createCardHeader(adbCard, "步骤 1：无线 ADB 权限与连接", ACCENT_BLUE);

        adbStatusBadge = new TextView(this);
        adbStatusBadge.setText("● 检测无线 ADB 状态中...");
        adbStatusBadge.setTextSize(14);
        adbStatusBadge.setTypeface(null, 1);
        adbStatusBadge.setTextColor(TEXT_MUTED);
        adbStatusBadge.setPadding(0, 0, 0, dp(8));
        adbCard.addView(adbStatusBadge);

        TextView adbTip = new TextView(this);
        adbTip.setText("本软件需要本机无线 ADB 权限（127.0.0.1:5555）以拉起副屏通道。\n首次使用请点击下方按钮打开设置，车机弹出提示时请勾选【始终允许此计算机进行调试】。");
        adbTip.setTextSize(13);
        adbTip.setTextColor(TEXT_MUTED);
        adbTip.setPadding(0, 0, 0, dp(10));
        adbCard.addView(adbTip);

        LinearLayout adbRow = createRow();
        btnOpenAdb = createButton(adbRow, "打开无线 ADB 设置", this::openAdbSettingsSafe, false, Color.WHITE, ACCENT_BLUE);
        btnCheckAdb = createButton(adbRow, "检测连接状态", this::checkAdbStatusAsync, false, TEXT_PRIMARY, BTN_LIGHT_BG);
        adbCard.addView(adbRow);
        page1Layout.addView(adbCard);

        // Step 2: 车机自启动与后台防杀白名单
        LinearLayout whitelistCard = createCard();
        createCardHeader(whitelistCard, "步骤 2：车机自启动与防杀白名单", ACCENT_BLUE);

        appStartStatusBadge = new TextView(this);
        appStartStatusBadge.setText("● 自启动管理状态：检测中...");
        appStartStatusBadge.setTextSize(14);
        appStartStatusBadge.setTypeface(null, 1);
        appStartStatusBadge.setTextColor(TEXT_MUTED);
        appStartStatusBadge.setPadding(0, 0, 0, dp(4));
        whitelistCard.addView(appStartStatusBadge);

        rapidModeStatusBadge = new TextView(this);
        rapidModeStatusBadge.setText("● 系统加速保护状态：检测中...");
        rapidModeStatusBadge.setTextSize(14);
        rapidModeStatusBadge.setTypeface(null, 1);
        rapidModeStatusBadge.setTextColor(TEXT_MUTED);
        rapidModeStatusBadge.setPadding(0, 0, 0, dp(4));
        whitelistCard.addView(rapidModeStatusBadge);

        batteryOptStatusBadge = new TextView(this);
        batteryOptStatusBadge.setText("● 电池优化与后台状态：检测中...");
        batteryOptStatusBadge.setTextSize(14);
        batteryOptStatusBadge.setTypeface(null, 1);
        batteryOptStatusBadge.setTextColor(TEXT_MUTED);
        batteryOptStatusBadge.setPadding(0, 0, 0, dp(8));
        whitelistCard.addView(batteryOptStatusBadge);

        TextView wlTip = new TextView(this);
        wlTip.setText("避免系统在锁车、内存紧张时清理助手或地图进程。点击后将自动化完成：\n" +
                "1. 自启动管理：关闭“禁止后台启动”开关（允许后台自启）\n" +
                "2. 系统加速 / 应用加速管理：自动打开仪表导航助手开关（防杀常驻）\n" +
                "3. 电池优化白名单：系统级 deviceidle 白名单保护");
        wlTip.setTextSize(13);
        wlTip.setTextColor(TEXT_MUTED);
        wlTip.setPadding(0, 0, 0, dp(12));
        whitelistCard.addView(wlTip);

        btnAutoSetup = createButton(whitelistCard, "★ 一键配置车机防杀白名单", () -> {
            if(!setupRunning.compareAndSet(false,true))return;
            final int attempt=setupAttempt.incrementAndGet();
            setupHelperDone.set(false);
            setupServiceDone.set(false);
            if (btnAutoSetup != null) {
                btnAutoSetup.setEnabled(false);
                btnAutoSetup.setText("正在自动配置防杀白名单...");
            }
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if(setupAttempt.get()!=attempt||!setupRunning.compareAndSet(true,false))return;
                AutoSetupAccessibilityService.cancelAutomation();
                try{startService(new Intent(this,AutoSetupAccessibilityService.class).setAction(AutoSetupAccessibilityService.ACTION_CANCEL));}
                catch(Exception e){Log.w("MainActivity","Unable to stop setup service",e);}
                if(btnAutoSetup!=null){btnAutoSetup.setEnabled(true);btnAutoSetup.setText("★ 一键配置车机防杀白名单");}
                showToast("配置未全部完成；请检查各项状态后重试",Toast.LENGTH_LONG);
                checkAdbStatusAsync();
            },90000);
            showToast("正在下发白名单并自动勾选加速管理...", Toast.LENGTH_SHORT);
            new Thread(() -> {
                try {
                    AdbClient adb=new AdbClient(this);
                    ShellResult ready=adb.execute("echo adb_ready",2);
                    if(!ready.success||!ready.output.contains("adb_ready"))throw new IOException("本地 ADB 尚未就绪");
                    String res = AutoSetupHelper.runAutomatedWhitelistSetup(adb, this);
                    Log.i("MainActivity",res);
                    if(setupAttempt.get()!=attempt || !setupRunning.get())return;
                    setupHelperDone.set(true);
                    runOnUiThread(() -> {
                        showToast("权限命令已执行，正在核验车机开关",Toast.LENGTH_SHORT);
                        finishSetupIfReady();
                    });
                } catch (Exception e) {
                    if(setupAttempt.get()!=attempt)return;
                    setupRunning.set(false);
                    AutoSetupAccessibilityService.cancelAutomation();
                    try{startService(new Intent(this,AutoSetupAccessibilityService.class).setAction(AutoSetupAccessibilityService.ACTION_CANCEL));}
                    catch(Exception stopError){Log.w("MainActivity","Unable to stop setup service",stopError);}
                    showToast("配置下发失败: " + e.getMessage(), Toast.LENGTH_SHORT);
                    runOnUiThread(() -> {
                        if (btnAutoSetup != null) {
                            btnAutoSetup.setEnabled(true);
                            btnAutoSetup.setText("★ 一键配置车机防杀白名单");
                        }
                    });
                }
            }).start();
        }, false, Color.WHITE, ACCENT_GREEN);
        if(setupRunning.get() && btnAutoSetup!=null){
            btnAutoSetup.setEnabled(false);
            btnAutoSetup.setText("正在自动配置防杀白名单...");
        }

        LinearLayout manualRow = createRow();
        createButton(manualRow, "手动打开自启动管理", () -> AutoSetupHelper.openAppStartManagement(this), false, TEXT_MUTED, BTN_LIGHT_BG);
        createButton(manualRow, "手动打开系统加速", () -> AutoSetupHelper.openRapidMode(this), false, TEXT_MUTED, BTN_LIGHT_BG);
        whitelistCard.addView(manualRow);

        page1Layout.addView(whitelistCard);

        // Step 3: 前往仪表导航卡片
        LinearLayout doneCard = createCard();
        createCardHeader(doneCard, "步骤 3：完成并开始使用", ACCENT_BLUE);

        TextView doneTip = new TextView(this);
        doneTip.setText("上述两项设置完成之后，即可切换到【仪表导航】页面开启全屏仪表地图！");
        doneTip.setTextSize(13);
        doneTip.setTextColor(TEXT_PRIMARY);
        doneTip.setPadding(0, 0, 0, dp(10));
        doneCard.addView(doneTip);

        createButton(doneCard, "进入仪表导航 >>", () -> switchTab(1), false, Color.WHITE, ACCENT_BLUE);
        page1Layout.addView(doneCard);
    }

    /**
     * Page 2: 日常仪表导航控制与精简设置
     */
    private void buildPage2Control(SharedPreferences sp) {
        // Status Card
        LinearLayout statusCard = createCard();
        statusBadge = new TextView(this);
        statusBadge.setText("状态：" + (engine.status != null ? engine.status : "就绪"));
        statusBadge.setTextSize(15);
        statusBadge.setTextColor(TEXT_PRIMARY);
        statusBadge.setTypeface(null, 1);
        statusBadge.setPadding(dp(4), dp(2), dp(4), dp(2));
        statusCard.addView(statusBadge);

        clusterHardwareBadge = new TextView(this);
        clusterHardwareBadge.setText("● 仪表硬件模式：检测中...");
        clusterHardwareBadge.setTextSize(13);
        clusterHardwareBadge.setTextColor(TEXT_MUTED);
        clusterHardwareBadge.setPadding(dp(4), dp(2), dp(4), dp(2));
        statusCard.addView(clusterHardwareBadge);

        displayStatusBadge = new TextView(this);
        displayStatusBadge.setText("● 仪表副屏通道：检测中...");
        displayStatusBadge.setTextSize(13);
        displayStatusBadge.setTextColor(TEXT_MUTED);
        displayStatusBadge.setPadding(dp(4), dp(2), dp(4), dp(2));
        statusCard.addView(displayStatusBadge);

        page2Layout.addView(statusCard);

        // Card 1: 仪表导航主控 (开启 / 关闭 大按钮)
        LinearLayout actionCard = createCard();
        createCardHeader(actionCard, "仪表导航控制", ACCENT_BLUE);

        LinearLayout actRow = createRow();
        btnMeterTest = createButton(actRow, "开启仪表导航", () -> test(4, false), true, Color.WHITE, ACCENT_BLUE);
        btnCloseMap = createButton(actRow, "关闭仪表导航", () -> engine.restore(), false, Color.WHITE, ACCENT_RED);
        actionCard.addView(actRow);
        page2Layout.addView(actionCard);

        // Card 2: 核心开关选项 (地图开机自启动、启动成功后主屏返回桌面)
        LinearLayout settingsCard = createCard();
        createCardHeader(settingsCard, "运行与开机选项", ACCENT_BLUE);

        autoBootMap = new CheckBox(this);
        autoBootMap.setText("地图开机自启动 (车辆冷启动后后台自动拉起仪表地图)");
        autoBootMap.setTextColor(TEXT_PRIMARY);
        autoBootMap.setTextSize(15);
        autoBootMap.setChecked(sp.getBoolean("auto_boot_enabled", true));
        settingsCard.addView(autoBootMap);
        autoBootMap.setOnCheckedChangeListener((v, c) -> sp.edit().putBoolean("auto_boot_enabled", c).putInt("boot_mode", 4).apply());

        autoReturnHome = new CheckBox(this);
        autoReturnHome.setText("启动成功后主屏返回车机桌面 (副屏持续全屏，中控不被遮挡)");
        autoReturnHome.setTextColor(TEXT_PRIMARY);
        autoReturnHome.setTextSize(15);
        autoReturnHome.setChecked(sp.getBoolean("auto_return_home", true));
        settingsCard.addView(autoReturnHome);
        autoReturnHome.setOnCheckedChangeListener((v, c) -> sp.edit().putBoolean("auto_return_home", c).apply());

        page2Layout.addView(settingsCard);

        // Card 3: 折叠式高级诊断与运行记录
        LinearLayout advCard = createCard();
        btnAdvancedToggle = createButton(advCard, "▼ 展开高级诊断与调试记录", () -> {
            if (advancedPanel.getVisibility() == View.VISIBLE) {
                advancedPanel.setVisibility(View.GONE);
                btnAdvancedToggle.setText("▼ 展开高级诊断与调试记录");
            } else {
                advancedPanel.setVisibility(View.VISIBLE);
                btnAdvancedToggle.setText("▲ 收起高级诊断与调试记录");
            }
        }, false, TEXT_MUTED, BTN_LIGHT_BG);

        advancedPanel = new LinearLayout(this);
        advancedPanel.setOrientation(LinearLayout.VERTICAL);
        advancedPanel.setVisibility(View.GONE);
        advancedPanel.setPadding(0, dp(10), 0, 0);

        LinearLayout advRow1 = createRow();
        createButton(advRow1, "采集诊断", () -> engine.diagnose(), true, TEXT_PRIMARY, BTN_LIGHT_BG);
        export = createButton(advRow1, "导出诊断 ZIP", this::export, false, TEXT_PRIMARY, BTN_LIGHT_BG);
        createButton(advRow1, "复制运行记录", () -> {
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("仪表诊断", engine.logs()));
            showToast("运行记录已复制", Toast.LENGTH_SHORT);
        }, false, TEXT_PRIMARY, BTN_LIGHT_BG);
        advancedPanel.addView(advRow1);

        inventory = new TextView(this);
        inventory.setText("尚未采集屏幕和地图信息");
        inventory.setTextSize(13);
        inventory.setTextColor(TEXT_MUTED);
        inventory.setPadding(0, dp(6), 0, dp(6));
        advancedPanel.addView(inventory);

        TextView dLabel = new TextView(this);
        dLabel.setText("显示通道覆盖：");
        dLabel.setTextColor(TEXT_MUTED);
        dLabel.setTextSize(13);
        advancedPanel.addView(dLabel);
        displays = new Spinner(this);
        displayValues.add(null);
        displays.setAdapter(adapter(Arrays.asList("自动选择厂商仪表通道")));
        advancedPanel.addView(displays);

        TextView mLabel = new TextView(this);
        mLabel.setText("地图版本覆盖：");
        mLabel.setTextColor(TEXT_MUTED);
        mLabel.setTextSize(13);
        advancedPanel.addView(mLabel);
        maps = new Spinner(this);
        mapValues.add(null);
        maps.setAdapter(adapter(Arrays.asList("自动选择已安装的定制高德")));
        advancedPanel.addView(maps);

        LinearLayout advRow2 = createRow();
        createButton(advRow2, "车机重启前采样", () -> engine.beforeReboot(), true, TEXT_MUTED, BTN_LIGHT_BG);
        createButton(advRow2, "重启后对照采集", () -> engine.afterReboot(), true, TEXT_MUTED, BTN_LIGHT_BG);
        createButton(advRow2, "清理模拟副屏", () -> engine.overlay(false), true, TEXT_MUTED, BTN_LIGHT_BG);
        advancedPanel.addView(advRow2);

        logs = new TextView(this);
        logs.setTextSize(12);
        logs.setTextColor(TEXT_PRIMARY);
        logs.setTextIsSelectable(true);
        logs.setTypeface(android.graphics.Typeface.MONOSPACE);
        logs.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable logsBg = new GradientDrawable();
        logsBg.setColor(Color.rgb(240, 244, 248));
        logsBg.setCornerRadius(dp(6));
        logsBg.setStroke(dp(1), CARD_STROKE);
        logs.setBackground(logsBg);
        advancedPanel.addView(logs);

        advCard.addView(advancedPanel);
        page2Layout.addView(advCard);

        displays.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int position, long id) {
                updateRouteControls();
            }
            public void onNothingSelected(AdapterView<?> p) {}
        });
    }

    private void checkAdbStatusAsync() {
        new Thread(() -> {
            boolean ready = false;
            boolean deviceIdle = false;
            boolean runInBg = false;
            JSONObject autoProbe = null;
            String pkg = getPackageName();
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", 5555), 300);
                AdbClient adb = new AdbClient(this);
                ShellResult r = adb.execute("echo adb_ok", 2);
                ready = r.success && r.output.contains("adb_ok");
                if (ready) {
                    ShellResult d = adb.execute("dumpsys deviceidle whitelist", 2);
                    deviceIdle = d.success && d.output.contains(pkg);
                    ShellResult a = adb.execute("appops get " + pkg + " RUN_IN_BACKGROUND", 2);
                    runInBg = a.success && a.output.contains("allow");

                    // Query hardware mode and secondary display status in real-time
                    try {
                        ShellResult pr = adb.execute(engine.bridge("probe"), 6);
                        if (pr != null && pr.success) {
                            int idx = pr.output.indexOf("CN_JSON:");
                            if (idx >= 0) {
                                autoProbe = new JSONObject(pr.output.substring(idx + 8).trim());
                                engine.probe = autoProbe;
                            }
                        }
                    } catch (Exception e) {
                        Log.w("MainActivity", "Auto probe on ADB connect failed: " + e.getMessage());
                    }
                }
            } catch (Exception ignored) {}
            final boolean isReady = ready;
            final boolean isDeviceIdle = deviceIdle;
            final boolean isRunInBg = runInBg;
            final JSONObject p = autoProbe;

            if (!isReady && adbRetryCount < 3) {
                adbRetryCount++;
                new Handler(Looper.getMainLooper()).postDelayed(this::checkAdbStatusAsync, 2500);
            } else if (isReady) {
                adbRetryCount = 0;
            }

            runOnUiThread(() -> {
                if (p != null) {
                    fillProbe(p);
                } else if (!isReady) {
                    if (clusterHardwareBadge != null) {
                        clusterHardwareBadge.setText("● 仪表硬件模式：未检测 (请先开启无线 ADB)");
                        clusterHardwareBadge.setTextColor(TEXT_MUTED);
                    }
                } else {
                    if(clusterHardwareBadge!=null){clusterHardwareBadge.setText("● 仪表硬件模式：本次读取失败");clusterHardwareBadge.setTextColor(TEXT_MUTED);}
                    if(displayStatusBadge!=null){displayStatusBadge.setText("● 仪表副屏通道：本次读取失败");displayStatusBadge.setTextColor(TEXT_MUTED);}
                    if (displayStatusBadge != null) {
                        displayStatusBadge.setText("● 仪表副屏通道：未检测 (请先开启无线 ADB)");
                        displayStatusBadge.setTextColor(TEXT_MUTED);
                    }
                }
                if (adbStatusBadge != null) {
                    if (isReady) {
                        adbStatusBadge.setText("● 无线 ADB 状态：已连接 (127.0.0.1:5555)");
                        adbStatusBadge.setTextColor(ACCENT_GREEN);
                    } else {
                        adbStatusBadge.setText("● 无线 ADB 状态：未连接 (请点击下方按钮开启)");
                        adbStatusBadge.setTextColor(ACCENT_RED);
                    }
                }
                updateOemSetupBadges();
                if (batteryOptStatusBadge != null) {
                    if (!isReady) {
                        batteryOptStatusBadge.setText("● 电池优化与后台状态：ADB 未连接，无法核验");
                        batteryOptStatusBadge.setTextColor(TEXT_MUTED);
                    } else if (isDeviceIdle) {
                        batteryOptStatusBadge.setText("● 电池优化与后台状态：已加入系统白名单 (deviceidle allow)");
                        batteryOptStatusBadge.setTextColor(ACCENT_GREEN);
                    } else if (isRunInBg) {
                        batteryOptStatusBadge.setText("● 后台 AppOps：允许；DeviceIdle 白名单未验证");
                        batteryOptStatusBadge.setTextColor(ACCENT_BLUE);
                    } else {
                        batteryOptStatusBadge.setText("● 电池优化与后台状态：未加入白名单 (可一键配置)");
                        batteryOptStatusBadge.setTextColor(TEXT_MUTED);
                    }
                }
            });
        }).start();
    }

    private void updateOemSetupBadges() {
        SharedPreferences sp=getSharedPreferences("diagnostic26",MODE_PRIVATE);
        updateOemSetupBadge(appStartStatusBadge,"自启动管理状态","允许",
                sp.getBoolean("status_app_start_allowed",false),sp.getLong("status_app_start_time",0),
                AutoSetupAccessibilityService.appStartVerifiedThisProcess());
        updateOemSetupBadge(rapidModeStatusBadge,"系统加速保护状态","开启",
                sp.getBoolean("status_rapid_mode_enabled",false),sp.getLong("status_rapid_mode_time",0),
                AutoSetupAccessibilityService.rapidModeVerifiedThisProcess());
    }

    private void updateOemSetupBadge(TextView badge,String label,String desired,boolean verified,long verifiedAt,
                                     boolean readBackThisProcess) {
        if(badge==null)return;
        if(!verified){
            badge.setText("● "+label+"：尚未读回"+desired+"（可点击一键配置）");
            badge.setTextColor(TEXT_MUTED);
            return;
        }
        boolean thisProcess=readBackThisProcess;
        String time=verifiedAt>0
                ?new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.getDefault()).format(new Date(verifiedAt))
                :"时间未记录";
        badge.setText("● "+label+"："+(thisProcess?"本次":"上次")+"配置已读回"+desired
                +"（"+time+(thisProcess?"":"；本次未复核")+"）");
        badge.setTextColor(thisProcess?ACCENT_GREEN:ACCENT_BLUE);
    }

    private void openAdbSettingsSafe() {
        showToast("正在尝试唤起无线 ADB 设置...", Toast.LENGTH_SHORT);
        new Thread(() -> {
            boolean launched = AutoSetupHelper.openAdbDeveloperSettings(this);
            runOnUiThread(() -> {
                if (!launched) {
                    showToast("未能直接打开 ADB 设置，请手动在车机【设置-关于】连按版本号开启开发者模式", Toast.LENGTH_LONG);
                }
                new Handler(Looper.getMainLooper()).postDelayed(this::checkAdbStatusAsync, 2500);
            });
        }).start();
    }

    private LinearLayout createCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD_BG);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), CARD_STROKE);
        card.setBackground(bg);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(14));
        card.setLayoutParams(lp);
        return card;
    }

    private void createCardHeader(LinearLayout card, String title, int color) {
        TextView h = new TextView(this);
        h.setText(title);
        h.setTextSize(16);
        h.setTextColor(color);
        h.setTypeface(null, 1);
        h.setPadding(0, 0, 0, dp(10));
        card.addView(h);
    }

    private LinearLayout createRow() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(4), 0, dp(4));
        r.setLayoutParams(lp);
        return r;
    }

    private Button createButton(ViewGroup parent, String name, Runnable action, boolean job, int textColor, int bgColor) {
        Button b = new Button(this);
        b.setText(name);
        b.setTextSize(15);
        b.setTextColor(textColor);
        b.setAllCaps(false);
        b.setMinHeight(dp(48));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(8));
        if (bgColor == BTN_LIGHT_BG) {
            bg.setStroke(dp(1), BTN_LIGHT_STROKE);
        }
        b.setBackground(bg);
        b.setOnClickListener(v -> action.run());

        LinearLayout.LayoutParams lp = parent instanceof LinearLayout && ((LinearLayout) parent).getOrientation() == LinearLayout.HORIZONTAL
                ? new LinearLayout.LayoutParams(0, -2, 1)
                : new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        parent.addView(b, lp);

        if (job) jobButtons.add(b);
        return b;
    }

    private ArrayAdapter<String> adapter(List<String> labels) {
        ArrayAdapter<String> a = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, labels) {
            @Override
            public View getView(int pos, View convert, ViewGroup par) {
                View v = super.getView(pos, convert, par);
                if (v instanceof TextView) ((TextView) v).setTextColor(TEXT_PRIMARY);
                return v;
            }
        };
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return a;
    }

    private int dp(int value) {
        return (int) (getResources().getDisplayMetrics().density * value + .5f);
    }

    @Override
    protected void onStart() {
        super.onStart();
        engine.listen(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkAdbStatusAsync();
    }

    @Override
    protected void onStop() {
        engine.listen(null);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(setupReceiver);
        } catch (Exception ignored) {}
        super.onDestroy();
    }

    private void test(int mode, boolean pattern) {
        int d = displays.getSelectedItemPosition(), m = maps.getSelectedItemPosition();
        boolean shouldRestart = restart != null ? restart.isChecked() : getSharedPreferences("diagnostic26", MODE_PRIVATE).getBoolean("restartMap", true);
        engine.test(mode, pattern, shouldRestart,
                d >= 0 && d < displayValues.size() ? displayValues.get(d) : null,
                m >= 0 && m < mapValues.size() ? mapValues.get(m) : null,
                false,
                autoReturnHome != null && autoReturnHome.isChecked());
    }

    @Override
    public void onUpdate() {
        if (statusBadge == null) return;
        statusBadge.setText("状态：" + (engine.status != null ? engine.status : "就绪"));

        if (logs != null) logs.setText(engine.logs());
        if (export != null) export.setEnabled(engine.latestZip != null && !engine.busy());

        for (Button b : jobButtons) b.setEnabled(!engine.busy());
        if (displays != null) displays.setEnabled(!engine.busy());
        if (maps != null) maps.setEnabled(!engine.busy());
        if (restart != null) restart.setEnabled(!engine.busy());
        if (autoStartApp != null) autoStartApp.setEnabled(!engine.busy());
        if (autoBootMap != null) autoBootMap.setEnabled(!engine.busy());
        if (autoReturnHome != null) autoReturnHome.setEnabled(!engine.busy());

        if (engine.probe != null && engine.probe != shownProbe) {
            shownProbe = engine.probe;
            fillProbe(shownProbe);
        }
        updateRouteControls();
    }

    private void updateRouteControls() {
        if (displays == null || maps == null) return;
        int index = displays.getSelectedItemPosition();
        String value = index >= 0 && index < displayValues.size() ? displayValues.get(index) : null;
        maps.setEnabled(!engine.busy());
        if (restart != null) restart.setEnabled(!engine.busy());
    }

    private void fillProbe(JSONObject p) {
        if (p == null) return;
        try {
            int rawMode = p.optInt("rawClusterState", 1);
            if (clusterHardwareBadge != null) {
                if (rawMode == 4) {
                    clusterHardwareBadge.setText("● 仪表硬件模式：全屏模式 4 (已写入硬件生效)");
                    clusterHardwareBadge.setTextColor(ACCENT_GREEN);
                } else {
                    clusterHardwareBadge.setText("● 仪表硬件模式：模式 " + rawMode + " (未激活全屏)");
                    clusterHardwareBadge.setTextColor(ACCENT_RED);
                }
            }

            JSONObject vendorDisplay = null;
            JSONArray dsProbe = p.optJSONArray("displays");
            if (dsProbe != null) {
                for (int i = 0; i < dsProbe.length(); i++) {
                    JSONObject dItem = dsProbe.optJSONObject(i);
                    if (dItem != null && SessionEngine.selectable(dItem)) {
                        vendorDisplay = dItem;
                        break;
                    }
                }
            }
            if (displayStatusBadge != null) {
                if (vendorDisplay != null) {
                    displayStatusBadge.setText("● 仪表副屏通道：已就绪 (Display " + vendorDisplay.optInt("id") + ", " + vendorDisplay.optInt("width") + "×" + vendorDisplay.optInt("height") + " ⚡支持秒级接管)");
                    displayStatusBadge.setTextColor(ACCENT_GREEN);
                } else {
                    displayStatusBadge.setText("● 仪表副屏通道：未激活 (首次需 6 秒握手生成)");
                    displayStatusBadge.setTextColor(TEXT_MUTED);
                }
            }

            if (displays == null || maps == null) return;
            String selectedDisplay = displays.getSelectedItemPosition() < displayValues.size() ? displayValues.get(displays.getSelectedItemPosition()) : null;
            String selectedMap = maps.getSelectedItemPosition() < mapValues.size() ? mapValues.get(maps.getSelectedItemPosition()) : null;
            StringBuilder text = new StringBuilder(SessionEngine.usesContainer(p) ? "已识别厂商仪表通道；测试会等待真实副屏就绪后再启动地图。\n本机屏幕：\n" : "本机屏幕：\n");
            List<String> labels = new ArrayList<>();
            labels.add("自动选择厂商仪表通道");
            displayValues.clear();
            displayValues.add(null);
            if (SessionEngine.usesContainer(p)) {
                labels.add("厂商仪表通道（Freedom 对应流程）");
                displayValues.add("container");
            }
            JSONArray ds = p.getJSONArray("displays");
            for (int i = 0; i < ds.length(); i++) {
                JSONObject d = ds.getJSONObject(i);
                String line = "#" + d.optInt("id") + "  " + d.optString("name") + "  " + d.optInt("width") + "×" + d.optInt("height") + (SessionEngine.isOverlay(d) ? "［模拟屏，不用于仪表］" : d.optInt("id") == 0 ? "［主屏］" : "［路由待验证］");
                text.append(line).append('\n');
                if (SessionEngine.selectable(d)) {
                    labels.add(line);
                    displayValues.add(SessionEngine.identity(d));
                }
            }
            displays.setAdapter(adapter(labels));
            if (displayValues.contains(selectedDisplay)) displays.setSelection(displayValues.indexOf(selectedDisplay));

            labels = new ArrayList<>();
            labels.add("自动选择已安装的定制高德");
            mapValues.clear();
            mapValues.add(null);
            JSONArray ms = p.getJSONArray("maps");
            for (int i = 0; i < ms.length(); i++) {
                JSONObject m = ms.getJSONObject(i);
                if (m.optBoolean("installed")) {
                    String label = m.optString("package") + "  " + m.optString("versionName");
                    labels.add(label);
                    mapValues.add(m.optString("package"));
                    text.append("地图：").append(label).append('\n');
                }
            }
            maps.setAdapter(adapter(labels));
            if (mapValues.contains(selectedMap)) maps.setSelection(mapValues.indexOf(selectedMap));
            if (inventory != null) {
                inventory.setText(text);
            }
        } catch (Exception e) {
            if (inventory != null) {
                inventory.setText("屏幕信息解析失败：" + e);
            }
        }
    }

    private void export() {
        Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        save.setType("application/zip");
        save.addCategory(Intent.CATEGORY_OPENABLE);
        save.putExtra(Intent.EXTRA_TITLE, "BYD_诊断_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date()) + ".zip");
        try {
            startActivityForResult(save, 24);
        } catch (ActivityNotFoundException e) {
            new AlertDialog.Builder(this)
                    .setMessage("此车机没有系统文件保存器。报告也保存在：\nAndroid/data/" + getPackageName() + "/files/diagnostics/latest-report.zip\n可用文件管理器复制到 U 盘。")
                    .setPositiveButton("知道了", null)
                    .show();
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 24 || result != RESULT_OK || data == null || data.getData() == null) return;
        final android.net.Uri uri = data.getData();
        final File file = engine.latestZip;
        new Thread(() -> {
            try (InputStream in = new FileInputStream(file); OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IOException("无法打开保存位置");
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
                runOnUiThread(() -> showToast("诊断 ZIP 已保存", Toast.LENGTH_LONG));
            } catch (Exception e) {
                runOnUiThread(() -> showToast("保存失败：" + e.getMessage(), Toast.LENGTH_LONG));
            }
        }).start();
    }
}
