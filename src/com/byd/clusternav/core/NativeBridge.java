package com.byd.clusternav.core;

import android.content.Context;
import android.content.Intent;
import android.content.pm.*;
import android.hardware.display.DisplayManager;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Display;
import java.io.*;
import java.lang.reflect.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

/** Invoked by app_process as the authorized ADB shell, never as root. */
public final class NativeBridge {
    public static final int AREA=1007, WRITE_PROPERTY=1276157976, READ_PROPERTY=1086337074;
    public static final int GUARD_MILLIS=60000, CLOSE_NAVIGATION=1;
    private static Context context;
    public static void main(String[] args) {
        int result=1;
        try {
            if(args.length==1 && args[0].equals("probe")) { System.out.println("CN_JSON:"+probe()); result=0; }
            else if(args.length==3 && args[0].equals("guard")) { guard(args[1],args[2]); result=0; }
            else if(args.length==2 && args[0].equals("complete")) { result=complete(args[1])?0:1; }
            else if(args.length==3 && args[0].equals("init")) { init(args[1],args[2]); result=0; }
            else if(args.length==5 && args[0].equals("adopt")) { adopt(args[1],Integer.parseInt(args[2]),Integer.parseInt(args[3]),args[4]); result=0; }
            else if(args.length==2 && args[0].equals("navigate")) { result=navigate(args[1])?0:1; }
            else if(args.length==5 && args[0].equals("map-start")) { startMap(args[1],args[2],Boolean.parseBoolean(args[3]),Integer.parseInt(args[4])); result=0; }
            else if(args.length==4 && args[0].equals("meter-start")) { startMeter(args[1],args[2],Integer.parseInt(args[3])); result=0; }
            else if(args.length==2 && args[0].equals("confirm")) { confirm(args[1]); result=0; }
            else if(args.length==2 && args[0].equals("restore")) { result=restore(args[1])?0:1; }
            else if(args.length==3 && args[0].equals("set")) {
                int mode=Integer.parseInt(args[1]);
                if(mode!=3 && mode!=4) throw new IllegalArgumentException("Only navigation mode 3/4");
                File root=token(args[2]);
                try(RandomAccessFile lock=new RandomAccessFile(root+".lock","rw");FileLock held=lock.getChannel().lock()) {
                    checkLive(root,10000);
                    if(new File(root+".activated").exists())throw new IOException("同一测试不能重复激活");
                    result=write(mode,route(root))?0:1;
                    if(result==0){save(new File(root+".mode"),String.valueOf(mode));save(new File(root+".activated"),String.valueOf(SystemClock.elapsedRealtime()));}
                }
            } else throw new IllegalArgumentException("probe | init/guard token route | set mode token | adopt token mode display identity | complete/navigate token | map-start token package restart display | meter-start token package display | restore token/none | confirm token");
        } catch(Throwable e) { e.printStackTrace(System.out); }
        System.exit(result);
    }
    private static Context context() throws Exception {
        if(context==null) {
            if(Looper.getMainLooper()==null) Looper.prepareMainLooper();
            Class<?> cls=Class.forName("android.app.ActivityThread");
            Object thread=cls.getMethod("systemMain").invoke(null);
            context=(Context)cls.getMethod("getSystemContext").invoke(thread);
        }
        return context;
    }
    private static Object service() throws Exception {
        Object auto=context().getSystemService("auto");
        if(auto==null) throw new IllegalStateException("系统未提供 auto 服务");
        return auto;
    }
    private static int readMode(Object auto) throws Exception {
        return ((Number)auto.getClass().getMethod("getInt",int.class,int.class).invoke(auto,AREA,READ_PROPERTY)).intValue();
    }
    private static ContainerChannel container() throws Exception {return new ContainerChannel(context().getSystemService("AutoContainer"));}
    private static String prop(String name) {
        try{return (String)Class.forName("android.os.SystemProperties").getMethod("get",String.class).invoke(null,name);}
        catch(Exception e){return "";}
    }
    private static String defaultRoute() {
        boolean hasContainer=false,hasAuto=false;
        try{container();hasContainer=true;}catch(Exception ignored){}
        try{service();hasAuto=true;}catch(Exception ignored){}
        return NavigationRoute.preferred(prop("ro.build.system.fission_single_os"),android.os.Build.PRODUCT,hasContainer,hasAuto);
    }
    private static boolean write(int mode,String route) {
        boolean accepted=false;
        try {
            Object returned;
            if(NavigationRoute.CONTAINER.equals(NavigationRoute.validate(route))) {
                returned=container().mode(mode);
                System.out.println("AutoContainer.sendInfo(1000,"+NavigationRoute.containerCommand(mode)+",empty) return="+returned);
            } else {
                return writeNavigation(mode==0?CLOSE_NAVIGATION:mode);
            }
            accepted=ContainerChannel.returnedWithoutRejection(returned);
        }catch(Throwable e){e.printStackTrace(System.out);}
        // This marker means only that the API returned without a reported rejection.
        System.out.println(accepted?"CN_CALL_RETURNED":"CN_WRITE_UNCONFIRMED");
        return accepted;
    }
    private static boolean writeNavigation(int mode) {
        if(mode!=CLOSE_NAVIGATION&&mode!=3&&mode!=4)throw new IllegalArgumentException("Unsupported navigation state");
        try {
            Object auto=service();Object returned=auto.getClass().getMethod("setInt",int.class,int.class,int.class).invoke(auto,AREA,WRITE_PROPERTY,mode);
            System.out.println("auto.setInt("+AREA+","+WRITE_PROPERTY+","+mode+") return="+returned);
            try{System.out.println("rawReadProperty="+readMode(auto));}catch(Throwable e){System.out.println("readUnavailable="+e);}
            return ContainerChannel.returnedWithoutRejection(returned);
        }catch(Throwable e){e.printStackTrace(System.out);return false;}
    }
    private static boolean closeNavigation(String chosenRoute) {
        NavigationRoute.validate(chosenRoute);
        // Amap's VehicleManager writes this property; MeterExtKt maps 1=close, 2=simple, 3=small, 4=full.
        boolean stateReturned=writeNavigation(CLOSE_NAVIGATION);
        boolean menuReturned=!NavigationRoute.CONTAINER.equals(chosenRoute)||write(0,chosenRoute);
        boolean stateClosed=false;
        if(stateReturned)try{
            long until=SystemClock.elapsedRealtime()+2500;
            do{int actual=readMode(service());System.out.println("closeReadback="+actual);if(actual==CLOSE_NAVIGATION){stateClosed=true;break;}Thread.sleep(150);}while(SystemClock.elapsedRealtime()<until);
        }catch(Throwable e){System.out.println("closeReadbackError="+e);}
        boolean ok=stateReturned&&menuReturned&&stateClosed;
        System.out.println(ok?"CN_STATE_CLOSED":"CN_CLOSE_UNCONFIRMED");return ok;
    }
    private static File token(String id) throws IOException {
        if(!id.matches("[a-f0-9-]{36}")) throw new IOException("invalid session token");
        return new File("/data/local/tmp/clusternav26_"+id);
    }
    private static void save(File path,String data) throws IOException {
        try(FileOutputStream out=new FileOutputStream(path)) {out.write(data.getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
    }
    private static String read(File path) throws IOException {
        try(FileInputStream in=new FileInputStream(path)) {byte[] data=new byte[4096];int n=in.read(data);return n<0?"":new String(data,0,n,StandardCharsets.UTF_8);}
    }
    private static String route(File root) throws IOException {return NavigationRoute.validate(read(new File(root+".route")).trim());}
    private static void init(String id,String chosenRoute)throws Exception {
        File root=token(id);NavigationRoute.validate(chosenRoute);
        try(RandomAccessFile lock=new RandomAccessFile(root+".lock","rw");FileLock held=lock.getChannel().lock()) {
            if(new File(root+".route").exists()||new File(root+".stop").exists())throw new IOException("测试记录已存在或已停止");
            save(new File(root+".route"),chosenRoute);
        }
        System.out.println("CN_SESSION_INITIALIZED");
    }
    private static void adopt(String id,int mode,int displayId,String expectedIdentity)throws Exception {
        if(mode!=3&&mode!=4)throw new IOException("无效地图模式");
        File root=token(id);
        try(RandomAccessFile lock=new RandomAccessFile(root+".lock","rw");FileLock held=lock.getChannel().lock()) {
            checkLive(root,18000);
            if(!NavigationRoute.CONTAINER.equals(route(root)))throw new IOException("只可接管厂商仪表通道");
            if(new File(root+".activated").exists())throw new IOException("本次会话已激活");
            Display display=realDisplay(displayId,true);
            if(!displayIdentity(display).equals(expectedIdentity))throw new IOException("副屏身份已改变");
            if(readMode(service())!=mode)throw new IOException("仪表模式已改变，需要完整握手");
            checkLive(root,18000);
            save(new File(root+".mode"),String.valueOf(mode));
            save(new File(root+".activated"),String.valueOf(SystemClock.elapsedRealtime()));
            save(new File(root+".followup"),"adopted existing channel");
            save(new File(root+".selected"),"adopted verified mode");
        }
        System.out.println("CN_EXISTING_CHANNEL_ADOPTED display="+displayId+" mode="+mode);
    }
    private static void checkLive(File root,int margin) throws IOException {
        if(new File(root+".stop").exists()||new File(root+".done").exists()||new File(root+".cancel").exists()||!new File(root+".ready").exists())throw new IOException("测试已结束或守护进程未就绪");
        long deadline=Long.parseLong(read(new File(root+".ready")).trim());
        if(SystemClock.elapsedRealtime()>=deadline-margin)throw new IOException("剩余测试时间不足");
    }
    private static boolean complete(String id) throws Exception {
        File root=token(id);
        try(RandomAccessFile lock=new RandomAccessFile(root+".lock","rw");FileLock held=lock.getChannel().lock()) {
            checkLive(root,3000);
            if(!NavigationRoute.CONTAINER.equals(route(root)))throw new IOException("此通道没有延迟命令");
            long activated=Long.parseLong(read(new File(root+".activated")).trim());
            long elapsed = SystemClock.elapsedRealtime() - activated;
            if(elapsed<6000)throw new IOException("尚未满足 6 秒切换间隔");
            checkLive(root,3000);
            if(SystemClock.elapsedRealtime()-activated<6000)throw new IOException("尚未满足 6 秒切换间隔");
            if(new File(root+".followup").exists())throw new IOException("已完成本次延迟命令");
            Object returned=container().complete();
            System.out.println("AutoContainer.sendInfo(1000,35,empty) return="+returned);
            boolean ok=ContainerChannel.returnedWithoutRejection(returned);
            if(ok)save(new File(root+".followup"),"returned");
            System.out.println(ok?"CN_CALL_RETURNED":"CN_WRITE_UNCONFIRMED");
            return ok;
        }
    }
    private static void guard(String id,String chosenRoute) throws Exception {
        File root=token(id);
        NavigationRoute.validate(chosenRoute);
        if(!chosenRoute.equals(route(root)))throw new IOException("守护通道与预先记录不符");
        if(new File(root+".stop").exists()){System.out.println("CN_STOPPED_BEFORE_GUARD");return;}
        if(new File(root+".ready").exists())throw new IOException("重复测试标识");
        long deadline=SystemClock.elapsedRealtime()+GUARD_MILLIS;
        save(new File(root+".ready"),String.valueOf(deadline));
        while(SystemClock.elapsedRealtime()<deadline && !new File(root+".stop").exists() && !new File(root+".cancel").exists()) Thread.sleep(200);
        try(RandomAccessFile lock=new RandomAccessFile(root+".lock","rw");FileLock held=lock.getChannel().lock()) {
            if(new File(root+".cancel").exists() && !new File(root+".stop").exists()) save(new File(root+".done"),"USER_CONFIRMED");
            else {
                save(new File(root+".stop"),"restore");
                boolean ok=restoreLocked(root);
                save(new File(root+".done"),ok?"RESTORE_CALL_RETURNED":"RESTORE_UNCONFIRMED");
            }
        }
    }
    private static boolean restore(String id) throws Exception {
        if(id.equals("none")) return closeNavigation(defaultRoute());
        File root=token(id);
        // Set stop before acquiring the lock to block any late activation.
        save(new File(root+".stop"),"user restore");
        try(RandomAccessFile lock=new RandomAccessFile(root+".lock","rw");FileLock held=lock.getChannel().lock()) { return restoreLocked(root); }
    }
    private static boolean restoreLocked(File root) throws Exception {
        if(new File(root+".restored").exists())try{if(readMode(service())==CLOSE_NAVIGATION){System.out.println("CN_ALREADY_RESTORED state=1");return true;}}catch(Exception ignored){}
        String backend;
        try{backend=route(root);}catch(FileNotFoundException e){backend=defaultRoute();System.out.println("sessionRouteMissing: using detected backend="+backend);}
        boolean ok=closeNavigation(backend);
        if(ok)save(new File(root+".restored"),"returned");
        return ok;
    }
    private static void confirm(String id) throws Exception {
        File root=token(id);
        try(RandomAccessFile lock=new RandomAccessFile(root+".lock","rw");FileLock held=lock.getChannel().lock()) {
            checkLive(root,2000);
            if(!new File(root+".activated").exists())throw new IOException("仪表通道尚未激活");
            if(NavigationRoute.CONTAINER.equals(route(root))&&!new File(root+".selected").exists())
                throw new IOException("仪表导航状态尚未读回");
            save(new File(root+".cancel"),"confirmed by user");
        }
        System.out.println("CN_CONFIRMED");
    }
    private static int activeMode(File root)throws IOException{
        int mode=Integer.parseInt(read(new File(root+".mode")).trim());if(mode!=3&&mode!=4)throw new IOException("无效地图模式");return mode;
    }
    private static boolean navigate(String id)throws Exception{
        File root=token(id);
        try(RandomAccessFile lock=new RandomAccessFile(root+".lock","rw");FileLock held=lock.getChannel().lock()) {
            checkLive(root,8000);
            if(!new File(root+".followup").exists())throw new IOException("菜单切换流程未完成");
            boolean ok=writeNavigation(activeMode(root));
            if(ok)save(new File(root+".selected"),"returned");
            System.out.println(ok?"CN_CALL_RETURNED":"CN_WRITE_UNCONFIRMED");return ok;
        }
    }
    private static void validMap(String pkg)throws IOException{
        if(!pkg.equals("com.byd.automap")&&!pkg.equals("com.byd.launchermap"))throw new IOException("不支持的地图包");
    }
    private static Display realDisplay(int wanted,boolean vendorOnly)throws Exception{
        DisplayManager manager=(DisplayManager)context().getSystemService(Context.DISPLAY_SERVICE);
        Display found=null;
        for(Display d:manager.getDisplays()){
            int type=((Number)Display.class.getMethod("getType").invoke(d)).intValue();
            String unique=(String)Display.class.getMethod("getUniqueId").invoke(d);
            boolean candidate=vendorOnly?DisplayPolicy.autoCandidate(d.getDisplayId(),d.getName(),type,unique,d.isValid()):DisplayPolicy.selectable(d.getDisplayId(),d.getName(),type,unique,d.isValid());
            if(candidate&&(wanted<0||d.getDisplayId()==wanted)){
                if(found!=null)throw new IOException("厂商副屏不唯一");found=d;
            }
        }
        if(found==null)throw new IOException("真实厂商副屏尚未就绪");return found;
    }
    private static String displayIdentity(Display d)throws Exception{return d.getDisplayId()+"|"+d.getName()+"|"+Display.class.getMethod("getUniqueId").invoke(d);}
    private static void startMap(String id,String pkg,boolean restart,int displayId)throws Exception{
        validMap(pkg);File root=token(id);
        try(RandomAccessFile lock=new RandomAccessFile(root+".lock","rw");FileLock held=lock.getChannel().lock()){
            checkLive(root,18000);Display d=realDisplay(displayId,NavigationRoute.CONTAINER.equals(route(root)));
            if(!new File(root+".activated").exists()||(NavigationRoute.CONTAINER.equals(route(root))&&!new File(root+".selected").exists()))throw new IOException("尚未请求仪表显示地图");
            Intent launch=context().getPackageManager().getLaunchIntentForPackage(pkg);
            if(launch==null)throw new IOException("地图缺少启动入口");
            if(restart)runAm("force-stop",pkg);
            // Re-check cancellation after stopping the process; no new launch after a stop request.
            checkLive(root,12000);
            if(!displayIdentity(d).equals(displayIdentity(realDisplay(displayId,NavigationRoute.CONTAINER.equals(route(root))))))throw new IOException("副屏身份已改变");
            runAm("start","--display","0","-n",launch.getComponent().flattenToString());
            save(new File(root+".map"),pkg);
            save(new File(root+".display"),displayIdentity(d));
            System.out.println("CN_MAP_STARTED package="+pkg+" restarted="+restart);
        }
    }
    private static void startMeter(String id,String pkg,int displayId)throws Exception{
        validMap(pkg);File root=token(id);
        try(RandomAccessFile lock=new RandomAccessFile(root+".lock","rw");FileLock held=lock.getChannel().lock()){
            checkLive(root,8000);Display d=realDisplay(displayId,NavigationRoute.CONTAINER.equals(route(root)));
            if(!displayIdentity(d).equals(read(new File(root+".display"))))throw new IOException("副屏身份已改变");
            if(!pkg.equals(read(new File(root+".map")).trim()))throw new IOException("地图启动记录不一致");
            runAm("start","--display",String.valueOf(d.getDisplayId()),"-f","0x18000000","-n",pkg+"/com.byd.automap.extra.MeterActivity","--ei","meterType",String.valueOf(activeMode(root)));
            System.out.println("CN_METER_STARTED display="+d.getDisplayId());
        }
    }
    private static void runAm(String... args)throws Exception{
        java.util.ArrayList<String> command=new java.util.ArrayList<>();command.add("/system/bin/am");java.util.Collections.addAll(command,args);
        Process p=new ProcessBuilder(command).redirectErrorStream(true).start();ByteArrayOutputStream output=new ByteArrayOutputStream();
        Thread reader=new Thread(()->{try{byte[] b=new byte[2048];int n;while((n=p.getInputStream().read(b))>=0){if(output.size()<131072)output.write(b,0,n);}}catch(IOException ignored){}});reader.setDaemon(true);reader.start();
        if(!p.waitFor(8,java.util.concurrent.TimeUnit.SECONDS)){p.destroy();throw new IOException("地图系统操作超时");}
        reader.join(500);String text=output.toString("UTF-8");System.out.println(text);
        if(p.exitValue()!=0||text.contains("Error:")||text.contains("Exception"))throw new IOException("地图系统操作失败："+text);
    }
    private static JSONObject probe() throws Exception {
        JSONObject root=new JSONObject();
        root.put("uid",android.os.Process.myUid());
        root.put("elapsedRealtime",SystemClock.elapsedRealtime());
        try{root.put("bootCount",android.provider.Settings.Global.getInt(context().getContentResolver(),"boot_count",-1));}catch(Exception e){root.put("bootCount",-1).put("bootCountError",e.toString());}
        try{root.put("bootId",read(new File("/proc/sys/kernel/random/boot_id")).trim());}catch(Exception e){root.put("bootId","");}
        root.put("product",android.os.Build.PRODUCT).put("fissionSingleOs",prop("ro.build.system.fission_single_os")).put("fissionMode",prop("ro.fission.mode"));
        try {Object c=context().getSystemService("AutoContainer");ContainerChannel ch=new ContainerChannel(c);root.put("containerManager",c.getClass().getName()).put("containerSignature",ch.signature());}
        catch(Throwable e){root.put("containerError",e.toString());}
        root.put("preferredRoute",defaultRoute());
        JSONArray displays=new JSONArray();
        DisplayManager manager=(DisplayManager)context().getSystemService(Context.DISPLAY_SERVICE);
        for(Display d:manager.getDisplays()) {
            JSONObject j=new JSONObject();DisplayMetrics m=new DisplayMetrics();d.getRealMetrics(m);
            j.put("id",d.getDisplayId()).put("name",d.getName()).put("valid",d.isValid()).put("state",d.getState()).put("flags",d.getFlags()).put("width",m.widthPixels).put("height",m.heightPixels).put("dpi",m.densityDpi);
            try {j.put("type",Display.class.getMethod("getType").invoke(d));}catch(Throwable e){j.put("type",-1);}
            try {j.put("uniqueId",Display.class.getMethod("getUniqueId").invoke(d));}catch(Throwable e){j.put("uniqueId","");}
            j.put("description",d.toString());displays.put(j);
        }
        root.put("displays",displays);
        JSONArray maps=new JSONArray();
        PackageManager pm=context().getPackageManager();
        for(String pkg:new String[]{"com.byd.automap","com.byd.launchermap"}) {
            JSONObject j=new JSONObject().put("package",pkg);
            try {
                PackageInfo p=pm.getPackageInfo(pkg,PackageManager.GET_ACTIVITIES|PackageManager.GET_SERVICES);
                j.put("installed",true).put("versionName",p.versionName).put("versionCode",p.getLongVersionCode());
                JSONArray activities=new JSONArray(),services=new JSONArray();
                if(p.activities!=null) for(ActivityInfo a:p.activities) {
                    if(a.name.contains("Meter")||a.name.contains("Startup")||a.name.contains("Central")||a.name.contains("MainActivity"))
                        activities.put(new JSONObject().put("name",a.name).put("exported",a.exported).put("enabled",a.enabled).put("permission",a.permission).put("launchMode",a.launchMode).put("resizeMode",safeField(a,"resizeMode")));
                }
                if(p.services!=null)for(ServiceInfo s:p.services)if(s.name.contains("PushService")||s.name.contains("BydMapService"))services.put(new JSONObject().put("name",s.name).put("exported",s.exported).put("enabled",s.enabled));
                android.content.Intent launch=pm.getLaunchIntentForPackage(pkg);
                if(launch!=null && launch.getComponent()!=null)j.put("launcher",launch.getComponent().flattenToString());
                j.put("activities",activities).put("services",services);
            } catch(PackageManager.NameNotFoundException e) {j.put("installed",false);}
            catch(Throwable e) {j.put("probeError",e.toString());}
            maps.put(j);
        }
        root.put("maps",maps);
        try {Object a=service();root.put("autoService",a.getClass().getName());
            try{root.put("autoWriteSignature",a.getClass().getMethod("setInt",int.class,int.class,int.class).toString());}catch(Throwable e){root.put("autoWriteError",e.toString());}
            try{root.put("rawClusterState",readMode(a));}catch(Throwable e){root.put("autoReadError",e.toString());}}
        catch(Throwable e){root.put("autoError",e.toString());}
        return root;
    }
    private static String safeField(Object o,String name) {try{return String.valueOf(o.getClass().getField(name).get(o));}catch(Throwable e){return "unknown";}}
}
