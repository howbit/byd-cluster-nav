package com.byd.clusternav.core;

import android.content.*;
import android.os.*;
import com.byd.clusternav.adb.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.zip.*;

public final class SessionEngine {
    public interface Listener {void onUpdate();}
    private static SessionEngine instance;
    public static synchronized SessionEngine get(Context context) {if(instance==null)instance=new SessionEngine(context.getApplicationContext());return instance;}
    private final Context context;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(),control=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final AtomicInteger generation=new AtomicInteger();
    private final AtomicBoolean working=new AtomicBoolean(false);
    private final AtomicBoolean restoring=new AtomicBoolean(false);
    private final StringBuilder log=new StringBuilder();
    private volatile Listener listener;
    public volatile String status="先采集诊断，自动识别厂商仪表通道。测试请在驻车时进行。";
    public volatile JSONObject probe;
    public volatile File latestZip;
    public volatile boolean canConfirm=false,confirmed=false;
    public volatile long deadline=0;
    private volatile String token;
    private File report;
    private int entryIndex=0;
    private SessionEngine(Context c) {
        context=c;
        String saved=prefs().getString("activeToken",null);
        token=saved;
        File last=new File(c.getFilesDir(),"latest-report.zip");if(last.exists())latestZip=last;
        if(saved!=null)status="上次测试记录尚在；可先点关闭仪表地图，再采集诊断。";
        else if(prefs().contains("restartBaseline"))status="已保存车机重启前记录。手动重启后，可点“重启后对照采集”。";
    }
    private SharedPreferences prefs(){return context.getSharedPreferences("diagnostic26",0);}
    public void listen(Listener value){listener=value;notifyUi();}
    public boolean busy(){return working.get()||restoring.get();}
    public synchronized String logs(){return log.toString();}
    private void notifyUi(){main.post(()->{Listener l=listener;if(l!=null)l.onUpdate();});}
    private void state(String value){status=value;note(value);}
    private synchronized void note(String value) {
        String line=new SimpleDateFormat("HH:mm:ss",Locale.ROOT).format(new Date())+"  "+value+"\n";
        log.append(line);if(log.length()>22000)log.delete(0,log.length()-22000);
        if(report!=null)try(FileOutputStream out=new FileOutputStream(new File(report,"session.txt"),true)){out.write(line.getBytes(StandardCharsets.UTF_8));}catch(IOException ignored){}
        notifyUi();
    }
    private synchronized void save(String name,String data) throws IOException {
        try(FileOutputStream out=new FileOutputStream(new File(report,name))){out.write(data.getBytes(StandardCharsets.UTF_8));}
    }
    private synchronized void begin(String kind) throws IOException {
        report=new File(context.getFilesDir(),"report-"+System.currentTimeMillis()+"-"+kind);if(!report.mkdirs())throw new IOException("无法建立报告目录");entryIndex=0;
        save("about.txt","ClusterNav 2.9.8\n"+android.os.Build.MANUFACTURER+" "+android.os.Build.MODEL+" Android "+android.os.Build.VERSION.RELEASE+" SDK "+android.os.Build.VERSION.SDK_INT+"\nNavigation property values from this OEM Amap: 1=close,2=simple,3=small,4=full.\nWindow/Surface evidence does not prove GMSL output or visible map pixels.\n");
    }
    public String bridge(String args){return "env CLASSPATH="+AdbClient.quote(context.getPackageCodePath())+" app_process /system/bin com.byd.clusternav.core.NativeBridge "+args;}
    private String root(String t){return "/data/local/tmp/clusternav26_"+t;}
    private void check(int version){if(generation.get()!=version)throw new CancellationException("已请求恢复/取消");}
    private ShellResult command(AdbClient adb,String label,String cmd,int seconds,int version)throws Exception {
        check(version);note(label);
        ShellResult r=adb.execute(cmd,seconds);
        synchronized(this){save(String.format(Locale.ROOT,"%02d_%s.txt",++entryIndex,label.replaceAll("[^A-Za-z0-9_-]","_")),"$ "+cmd+"\nexit="+r.exitCode+" error="+r.error+"\n"+r.output);}
        check(version);return r;
    }
    private void require(ShellResult r,String action)throws IOException {if(!r.success)throw new IOException(action+"失败："+(r.error==null?"":r.error)+" "+shortText(r.output));}
    private String shortText(String s){return s.length()>600?s.substring(0,600):s;}
    private JSONObject readProbe(AdbClient adb,int version)throws Exception {
        ShellResult r=command(adb,"probe",bridge("probe"),15,version);require(r,"读取屏幕与组件");
        int i=r.output.indexOf("CN_JSON:");if(i<0)throw new IOException("系统探针未返回 JSON");
        JSONObject p=new JSONObject(r.output.substring(i+8).trim());
        if(p.optInt("uid",-1)!=2000)throw new IOException("当前 ADB 不是预期 Shell UID 2000");
        probe=p;notifyUi();return p;
    }
    private boolean openJob(String kind) {
        if(restoring.get()){state("正在关闭仪表地图，请等待状态返回。");return false;}
        if(!working.compareAndSet(false,true)){state("已有任务进行中；关闭仪表地图按钮仍然可用。");return false;}
        try{begin(kind);context.startForegroundService(new Intent(context,SessionService.class));}
        catch(Exception e){working.set(false);state("无法启动任务："+e);return false;}
        return true;
    }
    private void finish(){
        try{zipReport();}catch(Exception e){note("报告打包失败："+e);}
        working.set(false);canConfirm=false;deadline=0;
        context.stopService(new Intent(context,SessionService.class));notifyUi();
    }
    public void diagnose(){diagnose(0);}
    public void beforeReboot(){diagnose(1);}
    public void afterReboot(){diagnose(2);}
    private void diagnose(int phase) {
        if(!openJob(phase==1?"before-reboot":phase==2?"after-reboot":"diagnosis"))return;final int v=generation.get();
        worker.execute(()->{
            try {
                AdbClient adb=new AdbClient(context);state("正在连接本机 ADB；首次使用请允许车机弹出的调试授权。");
                ShellResult id=command(adb,"adb_id","id",35,v);require(id,"ADB 连接");
                JSONObject p=null;
                try{p=readProbe(adb,v);}catch(CancellationException e){throw e;}catch(Exception e){note("系统探针不可用，将继续采集系统转储："+e.getMessage());}
                capture(adb,v);
                try {Object c=context.getSystemService("AutoContainer");save("app_container.txt","class="+(c==null?"null":c.getClass().getName())+"\n"+new ContainerChannel(c).signature());}
                catch(Throwable e){save("app_container.txt",e.toString());}
                String assessment=p==null?"组件探针不可用，已保留 dumpsys 原始证据，请导出分析。":summary(p);
                if(phase!=0){
                    if(p==null)throw new IOException("重启信息未能读取，未更新对照基准");
                    if(phase==1){
                        p.put("sampledAt",System.currentTimeMillis());
                        if(!prefs().edit().putString("restartBaseline",p.toString()).commit())throw new IOException("重启前基准保存失败");
                        save("reboot_baseline.json",p.toString(2));
                        assessment="重启前采样已保存。现在可以手动重启车机；重新打开本软件后，先点“重启后对照采集”。软件不会自动重启车机。";
                    }else{
                        String old=prefs().getString("restartBaseline",null);
                        if(old==null)throw new IOException("没有本版重启前基准，请先点“车机重启前采样”；本次原始诊断仍会保留");
                        JSONObject before=new JSONObject(old);
                        boolean changed=BootEvidence.changed(before.optString("bootId"),p.optString("bootId"),before.optInt("bootCount",-1),p.optInt("bootCount",-1),before.optLong("elapsedRealtime",-1),p.optLong("elapsedRealtime",-1));
                        JSONObject compare=new JSONObject().put("rebootDetected",changed).put("before",before).put("after",p);
                        save("reboot_comparison.json",compare.toString(2));
                        assessment=changed?"检测到系统重启，已保存前后副屏、地图进程和窗口对照。请记录此时仪表是否已出图，再做地图测试。":"未检测到系统重启的证据，已保存两次对照。仅退出软件或关闭屏幕可能不算系统重启。";
                    }
                }
                state(assessment+" 诊断报告已生成，可导出 ZIP。");
                save("assessment.txt",assessment);
            }catch(Exception e){state("诊断未完成："+e.getMessage());}finally{finish();}
        });
    }
    private void capture(AdbClient adb,int v)throws Exception {
        String[][] commands={
            {"display","dumpsys display"},
            {"surface_displays","dumpsys SurfaceFlinger --display-id"},
            {"surface_displays_vendor","dumpsys SurfaceFlinger --displays"},
            {"surface_layers","dumpsys SurfaceFlinger --list"},
            {"host_displays","dumpsys -t 5 SurfaceFlinger_host --display-id"},
            {"host_layers","dumpsys -t 5 SurfaceFlinger_host --list"},
            {"windows","dumpsys window windows"},
            {"activities","dumpsys activity activities"},
            {"properties","getprop | grep -Ei 'fission|clu[._]|display|gmsl|hwc|ro.build.version|ro.product.(model|device|board|name)|apps.setting.product.inswver'"},
            {"overlay","settings get global overlay_display_devices"},
            {"hardware_nodes","ls -l /sys/module/max96745/parameters/clu_size /sys/class/drm /sys/class/graphics; cat /sys/module/max96745/parameters/clu_size"},
            {"services","service list | grep -Ei 'auto|display|fission|SurfaceFlinger'"},
            {"map_package","dumpsys package com.byd.automap"},
            {"launcher_package","dumpsys package com.byd.launchermap"},
            {"map_services","dumpsys activity services com.byd.automap"},
            {"map_log",logCommand()}
        };
        for(String[] c:commands) {ShellResult r=command(adb,c[0],c[1],12,v);if(!r.success)note(c[0]+" 未返回完整结果（已保留错误）");}
    }
    private String logCommand(){return "logcat -d -v threadtime | grep -Ei 'BydMapService|PushService|MeterActivity|HomeMapFragment|meter |fission|ClusterNav|amap_surface_ex|AndroidRuntime|Permission Denial|SecurityException' | grep -Eiv 'AdbDebuggingManager|DPfinishLw|public.?key|pubkey|AdbService' | tail -n 1600";}
    private String summary(JSONObject p)throws JSONException {
        if(usesContainer(p))return "已识别厂商仪表通道。新版会先切换模式并等待真实副屏，再重启高德刷新识别；仍须以仪表实际出图为准。";
        JSONArray ds=p.getJSONArray("displays");int fission=0,other=0,overlays=0;
        for(int i=0;i<ds.length();i++){JSONObject d=ds.getJSONObject(i);if(isOverlay(d))overlays++;else if(selectable(d)){if(d.optString("name").startsWith("fission_"))fission++;else other++;}}
        if(fission>0)return "发现 "+fission+" 个厂商 fission 副屏候选，可进行限时画面测试；尚未证明物理链路正常。";
        if(other>0)return "发现非模拟副屏，请在列表中选定候选后先做色卡测试；其与仪表的连接尚未确认。";
        return "尚未识别出可调用的厂商仪表通道或真实副屏"+(overlays>0?"，检测到模拟/叠加副屏":"")+"。请导出报告，其中保留了服务接口和宿主显示信息。";
    }
    public static boolean usesContainer(JSONObject p){return NavigationRoute.CONTAINER.equals(p.optString("preferredRoute"));}
    public static boolean isOverlay(JSONObject d){return DisplayPolicy.isOverlay(d.optString("name"),d.optInt("type",-1),d.optString("uniqueId"));}
    public static boolean selectable(JSONObject d){return DisplayPolicy.selectable(d.optInt("id",-1),d.optString("name"),d.optInt("type",-1),d.optString("uniqueId"),d.optBoolean("valid"));}
    private JSONObject select(JSONObject p,String identity)throws Exception {
        JSONArray ds=p.getJSONArray("displays");JSONObject match=null;int count=0;
        for(int i=0;i<ds.length();i++){
            JSONObject d=ds.getJSONObject(i);if(!selectable(d))continue;
            if(identity!=null?identity.equals(identity(d)):d.optString("name").startsWith("fission_")){match=d;count++;}
        }
        if(count!=1)throw new IOException(identity==null?"没有唯一的厂商副屏。请先采集诊断并从列表选择真实副屏候选。":"所选屏幕已消失或身份发生变化，请重新采集诊断。");
        return match;
    }
    public static String identity(JSONObject d){return d.optInt("id")+"|"+d.optString("name")+"|"+d.optString("uniqueId");}
    private JSONObject map(JSONObject p,String chosen)throws Exception {
        JSONArray maps=p.getJSONArray("maps");List<JSONObject> matches=new ArrayList<>();
        for(int i=0;i<maps.length();i++){
            JSONObject m=maps.getJSONObject(i);if(!m.optBoolean("installed") || (chosen!=null&&!chosen.equals(m.optString("package"))))continue;
            JSONArray acts=m.optJSONArray("activities");if(acts==null)continue;
            for(int j=0;j<acts.length();j++){JSONObject a=acts.getJSONObject(j);if(a.optString("name").equals("com.byd.automap.extra.MeterActivity")&&a.optBoolean("enabled")&&a.optBoolean("exported")){matches.add(m);break;}}
        }
        if(matches.size()!=1)throw new IOException("没有唯一且可启动的高德 MeterActivity，请检查诊断中的安装版本并选择地图包。");
        return matches.get(0);
    }
    private void pause(int millis,int v)throws Exception {
        long until=SystemClock.elapsedRealtime()+millis;
        while(SystemClock.elapsedRealtime()<until){check(v);Thread.sleep(100);notifyUi();}
    }
    private String guardCommand(String t,String route) {
        // Keep the ADB parent alive until the detached Java process has written .ready.
        return "nohup "+bridge("guard "+t+" "+route)+" >"+AdbClient.quote(root(t)+".log")+" 2>&1 </dev/null & "+
            "cn_guard=$!; cn_wait=0; while [ ! -s "+AdbClient.quote(root(t)+".ready")+" ]; do "+
            "if ! kill -0 \"$cn_guard\" 2>/dev/null; then cat "+AdbClient.quote(root(t)+".log")+"; exit 1; fi; "+
            "cn_wait=$((cn_wait+1)); if [ \"$cn_wait\" -ge 60 ]; then cat "+AdbClient.quote(root(t)+".log")+"; exit 1; fi; "+
            "sleep 0.1; done; cat "+AdbClient.quote(root(t)+".ready");
    }
    private JSONObject waitVendorDisplay(AdbClient adb,int v)throws Exception {
        long until=Math.min(SystemClock.elapsedRealtime()+12000,deadline-22000);
        do{
            JSONObject current=readProbe(adb,v);
            try{return select(current,null);}catch(IOException ignored){}
            pause(500,v);
        }while(SystemClock.elapsedRealtime()<until);
        throw new IOException("副屏尚未就绪，未重启地图。请导出记录；可进一步做车机重启前后对照。");
    }
    private boolean visible(AdbClient adb,int v,String className,int id)throws Exception {
        ShellResult windows=command(adb,"windows_after","dumpsys window windows",5,v);
        return windows.success&&WindowEvidence.hasSurfaceOnDisplay(windows.output,className,id);
    }
    public static void returnToHome(AdbClient adb,Context context) {
        try {
            if(adb!=null) {
                adb.execute("am start -a android.intent.action.MAIN -c android.intent.category.HOME --display 0",5);
            }
        }catch(Exception ignored){}
        try {
            Intent home=new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(home);
        }catch(Exception ignored){}
    }
    public void test(int mode,boolean pattern,boolean restart,String displayIdentity,String mapPackage) {
        test(mode,pattern,restart,displayIdentity,mapPackage,false,false);
    }
    public void test(int mode,boolean pattern,boolean restart,String displayIdentity,String mapPackage,boolean unattendedBoot,boolean returnHome) {
        if(mode!=3&&mode!=4)return;
        if(!openJob(pattern?"pattern":"map"))return;final int v=generation.get();
        worker.execute(()->{
            boolean armed=false;String t=null;
            try {
                AdbClient adb=new AdbClient(context);
                JSONObject p=readProbe(adb,v);
                boolean hosted="container".equals(displayIdentity)||(displayIdentity==null&&usesContainer(p));
                if(hosted && !usesContainer(p))throw new IOException("当前系统没有符合条件的 AutoContainer 接口，请重新采集诊断。");
                if(hosted && pattern)throw new IOException("厂商仪表通道请直接测试地图；色卡只适用于本机真实副屏。");
                JSONObject d=hosted?null:select(p,displayIdentity);int id=d==null?-1:d.getInt("id");
                if(!p.has("autoWriteSignature")||!p.has("rawClusterState"))throw new IOException("系统导航状态接口不可写或不可读，无法验证关闭，已停止切屏。");
                String route=hosted?NavigationRoute.CONTAINER:NavigationRoute.LOCAL;
                save("route.txt","route="+route+"\nrestartMap="+restart+"\nproduct="+p.optString("product")+"\nfissionSingleOs="+p.optString("fissionSingleOs")+"\nlocalDisplay="+id+"\n");
                JSONObject m=pattern?null:map(p,mapPackage);
                if(m!=null&&m.optString("launcher").isEmpty())throw new IOException("地图没有可解析的启动入口");
                if(m!=null)command(adb,"services_before","dumpsys activity services "+AdbClient.quote(m.getString("package")),8,v);
                // Fast path check: secondary display already online and hardware mode is already active
                JSONObject existingVendorDisplay = null;
                if(hosted) {
                    try { existingVendorDisplay = select(p, "container".equals(displayIdentity)?null:displayIdentity); } catch (Exception ignored) {}
                }
                String className = pattern ? "com.byd.clusternav.ui.PatternActivity" : "com.byd.automap.extra.MeterActivity";
                boolean mapAlreadyRendered = false;
                if(hosted && existingVendorDisplay != null && p.optInt("rawClusterState") == mode) {
                    int existingId = existingVendorDisplay.getInt("id");
                    mapAlreadyRendered = visible(adb, v, className, existingId);
                }

                if(mapAlreadyRendered) {
                    // FAST TAKEOVER PATH 1: Already running on secondary display!
                    d = existingVendorDisplay;
                    id = d.getInt("id");
                    confirmed = true;
                    canConfirm = false;
                    note("⚡ 极速接管：副屏 (Display "+id+") 与地图已在全屏运行，跳过 6 秒握手！");
                    state("仪表导航已在副屏全屏运行中。");
                    if(returnHome) {
                        state("正在自动返回车机桌面 (Display 0)...");
                        returnToHome(adb, context);
                    }
                    command(adb, "surface_layers_after", "dumpsys SurfaceFlinger --list", 5, v);
                    command(adb, "test_log", logCommand(), 8, v);
                    return;
                }

                t=UUID.randomUUID().toString();token=t;confirmed=false;
                if(!prefs().edit().putString("activeToken",t).commit())throw new IOException("无法保存测试关闭记录");
                // Record the backend before guard startup, so even a startup failure can be closed.
                armed=true;
                require(command(adb,"guard_init",bridge("init "+t+" "+route),8,v),"保存测试通道");
                ShellResult ready=command(adb,"guard_start",guardCommand(t,route),12,v);
                require(ready,"启动关闭守护");
                deadline=Long.parseLong(ready.output.trim());

                if(hosted && existingVendorDisplay != null && p.optInt("rawClusterState") == mode) {
                    // Adopt only after the shell bridge verifies the live mode and exact display identity.
                    d = existingVendorDisplay;
                    id = d.getInt("id");
                    require(command(adb,"adopt_existing_channel",bridge("adopt "+t+" "+mode+" "+id+" "+AdbClient.quote(identity(d))),8,v),"接管现有仪表通道");
                    note("⚡ 极速拉起：副屏 (Display "+id+") 已就绪，跳过 6 秒握手，直接加载高德地图！");
                } else {
                    // COLD INIT PATH: First time or reset
                    require(command(adb,"cluster_set",bridge("set "+mode+" "+t),10,v),"切换仪表模式");
                    if(hosted) {
                        state("等待底层真实副屏通道建立…");
                        pause(6200,v);
                        require(command(adb,"container_followup",bridge("complete "+t),8,v),"完成厂商地图切换");
                        require(command(adb,"navigation_select",bridge("navigate "+t),8,v),"请求仪表地图状态");
                        d=waitVendorDisplay(adb,v);id=d.getInt("id");
                        note("真实副屏已就绪："+identity(d)+" "+d.optInt("width")+"×"+d.optInt("height"));
                    }
                }
                boolean surface=false;
                if(pattern){
                    require(command(adb,"secondary_pattern","am start --display "+id+" -f 0x18000000 -n "+context.getPackageName()+"/"+className,8,v),"启动色卡");
                }else{
                    String pkg=m.getString("package");
                    state(restart?"副屏已就绪，正在重启高德以刷新副屏能力；地图数据会保留。":"副屏已就绪，正在打开高德，本次不重启地图进程。");
                    require(command(adb,"map_reinitialize",bridge("map-start "+t+" "+pkg+" "+restart+" "+id),20,v),"初始化地图");
                    // Let PushService initialize; an exported activity alone is not proof its service is ready.
                    boolean serviceReady=false;
                    for(int i=0;i<6&&SystemClock.elapsedRealtime()<deadline-14000;i++){
                        pause(350,v);
                        ShellResult services=command(adb,"map_services_after","dumpsys activity services "+AdbClient.quote(pkg),5,v);
                        serviceReady=services.success&&ServiceEvidence.runningPushService(services.output,pkg);
                        if(serviceReady)break;
                    }
                    if(!serviceReady)throw new IOException("未确认高德 PushService 已启动。请检查高德初始化/隐私协议是否完成，并导出报告。");
                    pause(500,v);
                    surface=visible(adb,v,className,id);
                    if(!surface){
                        note("按原厂入口启动 MeterActivity。");
                        require(command(adb,"meter_start",bridge("meter-start "+t+" "+pkg+" "+id),10,v),"打开仪表地图页面");
                    }
                }
                for(int i=0;!surface&&i<5&&SystemClock.elapsedRealtime()<deadline-5000;i++){
                    pause(400,v);surface=visible(adb,v,className,id);
                }
                if(!surface)throw new IOException("未确认目标 Display "+id+" 上的可见地图窗口与 Surface，将关闭测试。");
                
                // Auto-confirm: 自动检测出图并自动保持，取消一切倒计时与人工确认
                require(command(adb,"auto_confirm",bridge("confirm "+t),8,v),"自动保持仪表导航");
                confirmed=true;canConfirm=false;
                state("仪表导航已自动保持，检测到副屏地图窗口；请以仪表实际画面为准。");
                if(returnHome) {
                    state("正在自动返回车机桌面 (Display 0)...");
                    returnToHome(adb,context);
                }
                command(adb,"surface_layers_after","dumpsys SurfaceFlinger --list",5,v);
                if(hosted)command(adb,"host_layers_after","dumpsys -t 3 SurfaceFlinger_host --list",5,v);
                while(!confirmed&&SystemClock.elapsedRealtime()<deadline+500){check(v);Thread.sleep(200);notifyUi();}
                canConfirm=false;
                if(confirmed)state(unattendedBoot?"开机自启已保持导航，请核对仪表实际画面。":"已检测到副屏地图窗口并保持导航，可随时关闭。");
                else {
                    String donePath=AdbClient.quote(root(t)+".done");
                    ShellResult done=command(adb,"guard_result","cn_wait=0; while [ ! -f "+donePath+" ] && [ \"$cn_wait\" -lt 25 ]; do cn_wait=$((cn_wait+1)); sleep .2; done; cat "+donePath+"; cat "+AdbClient.quote(root(t)+".log"),8,v);
                    state(done.output.contains("RESTORE_CALL_RETURNED")?"限时测试结束，仪表状态已读回“关闭”。请核对实际画面。":"限时测试结束，自动关闭结果未确认，请点关闭仪表地图。");
                    if(done.output.contains("RESTORE_CALL_RETURNED"))clearToken(t);
                }
                readProbe(adb,v);
                command(adb,"test_log",logCommand(),8,v);
            }catch(Exception e){
                canConfirm=false;state("测试停止："+e.getMessage());
                if(armed&&!confirmed){
                    ShellResult r=new AdbClient(context).execute(bridge("restore "+t),15);
                    note("失败后关闭："+(r.success?"已读回关闭状态，请核对仪表":"未确认，请点关闭仪表地图")+"\n"+shortText(r.output));
                    try{save("failure_close.txt",r.output);}catch(Exception ignored){}
                    if(r.success)clearToken(t);
                }
                // Even failed runs retain the after-state, needed to distinguish no-display from no-map.
                if(generation.get()==v)try{
                    AdbClient adb=new AdbClient(context);readProbe(adb,v);
                    command(adb,"failure_windows","dumpsys window windows",5,v);
                    command(adb,"failure_services","dumpsys activity services com.byd.automap",5,v);
                    command(adb,"failure_log",logCommand(),8,v);
                }catch(Exception diagnosticError){note("失败后补充采集未完成："+diagnosticError.getMessage());}
            }finally{finish();}
        });
    }
    public void confirmVisible() {
        if(!canConfirm || token==null)return;final String t=token;final int v=generation.get();canConfirm=false;notifyUi();
        control.execute(()->{try{ShellResult r=new AdbClient(context).execute(bridge("confirm "+t),6);if(generation.get()==v&&r.success&&r.output.contains("CN_CONFIRMED")){confirmed=true;note("用户确认仪表画面正常，已取消限时复位。");}else {note("保持导航未生效："+r.error+" "+shortText(r.output));}}catch(Exception e){note("保持导航失败："+e);}});
    }
    private void clearToken(String t){if(t!=null&&t.equals(token)){token=null;prefs().edit().remove("activeToken").apply();}}
    public void restore() {
        if(!restoring.compareAndSet(false,true))return;
        context.stopService(new Intent(context,BootStartupService.class));
        generation.incrementAndGet();canConfirm=false;confirmed=false;final String t=token;
        state("正在关闭仪表地图并读取实际状态…");
        control.execute(()->{
            try {
            ShellResult r=new AdbClient(context).execute(bridge("restore "+(t==null?"none":t)),15);
            note("复位返回 exit="+r.exitCode+"\n"+shortText(r.output));
            if(report!=null)save("manual_close.txt",r.output);
            state(r.success?"仪表状态已读回“关闭”。请核对实际画面；导航菜单选项可能仍会保留。":"关闭状态未确认："+r.error+"。请检查 ADB 连接，必要时用仪表菜单选择关闭。");
            if(r.success)clearToken(t);
            try{if(report!=null)zipReport();}catch(Exception e){note("报告更新失败："+e);}
            }catch(Exception e){state("复位未完成："+e.getMessage());}finally{restoring.set(false);notifyUi();}
        });
    }
    public void overlay(boolean undo) {
        if(!openJob("overlay"))return;final int v=generation.get();
        worker.execute(()->{
            try {
                AdbClient adb=new AdbClient(context);SharedPreferences prefs=prefs();
                if(undo){
                    if(!prefs.contains("overlayBackup"))throw new IOException("没有本版保存的模拟屏设置备份");
                    String backup=prefs.getString("overlayBackup","");
                    ShellResult current=command(adb,"current_overlay","settings get global overlay_display_devices",5,v);require(current,"读取当前模拟屏设置");
                    if(!current.output.trim().equals("null")&&!current.output.trim().isEmpty())throw new IOException("模拟屏配置已再次改变，为避免覆盖，请先导出诊断");
                    String cmd=backup.equals("null")?"settings delete global overlay_display_devices":"settings put global overlay_display_devices "+AdbClient.quote(backup);
                    require(command(adb,"undo_overlay",cmd,5,v),"恢复模拟屏配置");
                    prefs.edit().remove("overlayBackup").commit();state("已还原清理前的模拟屏设置。请重新采集诊断。");
                }else{
                    ShellResult old=command(adb,"overlay_before","settings get global overlay_display_devices",5,v);require(old,"读取模拟屏");String previous=old.output.trim();
                    if(previous.equals("null")||previous.isEmpty()){state("没有模拟副屏需要清理。");return;}
                    if(prefs.contains("overlayBackup"))throw new IOException("已有一次清理备份，请先撤销，避免覆盖备份");
                    if(!prefs.edit().putString("overlayBackup",previous).commit())throw new IOException("无法保存设置备份");
                    require(command(adb,"clear_overlay","settings delete global overlay_display_devices",5,v),"清理模拟副屏");
                    state("模拟副屏已清理，原设置已备份。高德下次启动才能重新读取屏幕能力。");
                }
            }catch(Exception e){state("模拟屏操作停止："+e.getMessage());}finally{finish();}
        });
    }
    private synchronized void zipReport()throws IOException {
        if(report==null)return;
        File temp=new File(context.getFilesDir(),"latest-report.tmp");
        try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(temp))){
            // Keep preceding diagnostics alongside failed tests; "latest" is an aggregate bundle.
            File[] dirs=context.getFilesDir().listFiles(f->f.isDirectory()&&f.getName().startsWith("report-"));
            if(dirs!=null){Arrays.sort(dirs,Comparator.comparing(File::getName));int first=Math.max(0,dirs.length-6);
                for(int i=first;i<dirs.length;i++){File dir=dirs[i];File[] files=dir.listFiles();if(files!=null)for(File f:files){if(!f.isFile())continue;zip.putNextEntry(new ZipEntry(dir.getName()+"/"+f.getName()));try(FileInputStream in=new FileInputStream(f)){byte[] b=new byte[8192];int n;while((n=in.read(b))>=0)zip.write(b,0,n);}zip.closeEntry();}}
            }
        }
        File target=new File(context.getFilesDir(),"latest-report.zip");
        if(!temp.renameTo(target))throw new IOException("报告文件替换失败");latestZip=target;
        File publicDir=context.getExternalFilesDir("diagnostics");
        if(publicDir!=null){publicDir.mkdirs();try(FileInputStream in=new FileInputStream(target);FileOutputStream out=new FileOutputStream(new File(publicDir,"latest-report.zip"))){byte[] b=new byte[8192];int n;while((n=in.read(b))>=0)out.write(b,0,n);}}
        notifyUi();
    }
}
