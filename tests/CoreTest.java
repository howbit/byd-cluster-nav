import com.byd.clusternav.adb.ShellResult;
import com.byd.clusternav.core.DisplayPolicy;
import com.byd.clusternav.core.WindowEvidence;
import com.byd.clusternav.core.NavigationRoute;
import com.byd.clusternav.core.ContainerChannel;
import com.byd.clusternav.core.BootEvidence;
import com.byd.clusternav.core.ServiceEvidence;
import com.byd.clusternav.core.SwitchEvidence;
import com.byd.clusternav.core.ConfigurationEvidence;
import java.util.*;
public final class CoreTest {
    static int n;
    static void check(boolean b,String name){n++;if(!b)throw new AssertionError(name);}
    public static final class FakeManager {
        final List<String> calls=new ArrayList<>();
        public void sendInfo(int group,int code,String text){calls.add(group+":"+code+":"+text);}
    }
    public static final class DeniedManager {public void sendInfo(int group,int code,String text){throw new SecurityException("vendor denied");}}
    public static void main(String[] args)throws Exception {
        check(ShellResult.parse("正常\r\nMARK=0\r\n","MARK=").success,"success");
        check(!ShellResult.parse("Error: Activity missing\nMARK=1\n","MARK=").success,"nonzero exit");
        check(!ShellResult.parse("partial output","MARK=").success,"truncated stream");
        check(!ShellResult.parse("ok\nMARK=0\nextra","MARK=").success,"trailing stream corruption");
        check(!ShellResult.parse("ok\nMARK=bad\n","MARK=").success,"malformed status");
        check(!ShellResult.parse("ok\nMARK=999\n","MARK=").success,"out of bounds status");
        check(!DisplayPolicy.selectable(1,"叠加视图 #1",4,"overlay:1",true),"localized overlay");
        check(!DisplayPolicy.selectable(1,"Overlay #1",4,"overlay:1",true),"English overlay");
        check(!DisplayPolicy.selectable(7,"fission_0",4,"overlay:7",true),"overlay disguised as fission");
        check(!DisplayPolicy.selectable(0,"fission_main",1,"local:0",true),"never main display");
        check(DisplayPolicy.autoCandidate(7,"fission_0",5,"virtual:vendor",true),"non-default vendor ID");
        check(!DisplayPolicy.autoCandidate(2,"HDMI",2,"local:99",true),"generic output needs selection");
        check(DisplayPolicy.selectable(2,"HDMI",2,"local:99",true),"explicit physical candidate");
        check(!DisplayPolicy.selectable(2,"fission_1",5,"virtual:vendor",false),"invalid display");
        check(!DisplayPolicy.selectable(2,"fission_1",-1,"",true),"unknown type");
        String one="  Window #1 Window{abc u0 com.byd.automap/com.byd.automap.extra.MeterActivity}:\n    mDisplayId=7\n    mHasSurface=true\n    isOnScreen=true\n";
        String two="  Window #2 Window{aaa u0 other/app.Other}:\n    mDisplayId=1\n    mHasSurface=true\n    isOnScreen=true\n";
        check(WindowEvidence.hasSurfaceOnDisplay(one+two,"com.byd.automap.extra.MeterActivity",7),"correct activity and display");
        check(!WindowEvidence.hasSurfaceOnDisplay(one+two,"com.byd.automap.extra.MeterActivity",1),"do not pair other window");
        check(!WindowEvidence.hasSurfaceOnDisplay(one.replace("mHasSurface=true","mHasSurface=false"),"com.byd.automap.extra.MeterActivity",7),"surface absent");
        check(!WindowEvidence.hasSurfaceOnDisplay(one.replace("isOnScreen=true","isOnScreen=false"),"com.byd.automap.extra.MeterActivity",7),"offscreen window");
        check(!WindowEvidence.hasSurfaceOnDisplay(one,"com.byd.automap.extra.MeterActivity",77),"display boundary");
        check(WindowEvidence.hasSurfaceOnDisplay(one.replace("/com.byd.automap.extra.MeterActivity","/.extra.MeterActivity"),"com.byd.automap.extra.MeterActivity",7),"short component name");
        check(WindowEvidence.hasSurfaceOnDisplay("WINDOWS\n\n"+one,"com.byd.automap.extra.MeterActivity",7),"blank lines before window");
        check(!WindowEvidence.hasSurfaceOnDisplay(one.replace("com.byd.automap/com.byd.automap.extra.MeterActivity","fake.app/.MeterActivity"),"com.byd.automap.extra.MeterActivity",7),"same class leaf wrong package");
        check(NavigationRoute.preferred("0","DiLink3.0",true,true).equals("container"),"vehicle report selects container without any local display prerequisite");
        check(!NavigationRoute.containerSupported("1","DiLink3.0",true),"single OS excluded");
        check(!NavigationRoute.containerSupported("","DiLink3.0",true),"missing property not silently treated as zero");
        check(!NavigationRoute.containerSupported("0","DiLink6.0",true),"Freedom excluded product");
        check(!NavigationRoute.containerSupported("0","DiLink3.0",false),"missing manager");
        check(NavigationRoute.preferred("0","DiLink3.0",false,true).equals("none"),"missing dual-system manager never silently falls back to unrelated auto writes");
        check(NavigationRoute.preferred("0","DiLink3.0",false,false).equals("none"),"no vendor backend");
        FakeManager manager=new FakeManager();ContainerChannel ch=new ContainerChannel(manager);
        ch.mode(4);ch.complete();ch.mode(0);ch.mode(3);
        check(manager.calls.equals(Arrays.asList("1000:16:","1000:35:","1000:18:","1000:17:")),"exact typed API arguments full, followup, close and split");
        boolean rejected=false;try{ch.mode(99);}catch(IllegalArgumentException e){rejected=true;}
        check(rejected&&manager.calls.size()==4,"unknown mode never written");
        rejected=false;try{new ContainerChannel(new Object());}catch(NoSuchMethodException e){rejected=true;}
        check(rejected,"no guessed Binder fallback");
        rejected=false;try{new ContainerChannel(new DeniedManager()).mode(4);}catch(SecurityException e){rejected=true;}
        check(rejected,"vendor permission rejection preserved");
        check(ContainerChannel.returnedWithoutRejection(null),"void API only reports return");
        check(!ContainerChannel.returnedWithoutRejection(false)&&!ContainerChannel.returnedWithoutRejection(-1),"explicit failure stays failure");
        check(BootEvidence.changed("boot-a","boot-b",3,3,10000,20000),"kernel reboot ID changes despite elapsed increase");
        check(BootEvidence.changed("","",3,4,1000,3000),"framework reboot counter");
        check(BootEvidence.changed("","",-1,-1,20000,1000),"elapsed fallback");
        check(!BootEvidence.changed("boot-a","boot-a",3,3,1000,2000),"opening app again is not reboot");
        check(!BootEvidence.changed("","",-1,-1,-1,-1),"unknown clocks cannot prove reboot");
        check(!BootEvidence.changed("boot-a","",3,-1,1000,2000),"missing data is not reboot");
        check(!BootEvidence.changed("","",-1,-1,2000,1500),"small clock sampling variation ignored");
        String service="  * ServiceRecord{d29c90c u0 com.byd.automap/.service.PushService}\n    app=ProcessRecord{e88c46a 5064:com.byd.automap/u0a46}\n";
        check(ServiceEvidence.runningPushService(service,"com.byd.automap"),"actual vehicle abbreviated service name");
        check(ServiceEvidence.runningPushService(service.replace("/.service.","/com.byd.automap.service."),"com.byd.automap"),"full service name");
        check(!ServiceEvidence.runningPushService(service,"com.byd.launchermap"),"wrong installed map process");
        check(!ServiceEvidence.runningPushService(service.replace("app=ProcessRecord{e88c46a 5064:com.byd.automap/u0a46}","app=null")+service.replace(".service.PushService",".OtherService"),"com.byd.automap"),"other service process does not prove PushService initialization");
        check(!ServiceEvidence.runningPushService("ServiceRecord{com.byd.automap/.service.PushService} app=ProcessRecord{fake}","com.byd.automap"),"incomplete service dump rejected");
        check(Boolean.TRUE.equals(SwitchEvidence.read(true,true,"off",null)),"checkable switch state overrides stale description");
        check(Boolean.FALSE.equals(SwitchEvidence.read(true,false,"on",null)),"checkable OFF remains OFF");
        check(Boolean.FALSE.equals(SwitchEvidence.read(false,false,"已关闭",null)),"BYD autostart off readback");
        check(Boolean.TRUE.equals(SwitchEvidence.read(false,false,"已开启",null)),"BYD rapid-mode on readback");
        check(SwitchEvidence.read(false,false,"unknown",null)==null,"unknown switch is not silently OFF");
        check(SwitchEvidence.desired(false,false)&&SwitchEvidence.desired(true,true),"desired switch directions are verified separately");
        check(!SwitchEvidence.desired(null,false)&&!SwitchEvidence.desired(null,true),"missing switch cannot be marked successful");
        check(ConfigurationEvidence.deviceIdleVerified(true,true,"system-excidle,com.byd.clusternav.diagnostic\n","com.byd.clusternav.diagnostic"),"exact deviceidle package readback");
        check(!ConfigurationEvidence.deviceIdleVerified(false,true,"com.byd.clusternav.diagnostic","com.byd.clusternav.diagnostic"),"failed whitelist write cannot be success");
        check(!ConfigurationEvidence.deviceIdleVerified(true,false,"com.byd.clusternav.diagnostic","com.byd.clusternav.diagnostic"),"failed whitelist read cannot be success");
        check(!ConfigurationEvidence.deviceIdleVerified(true,true,"com.byd.clusternav.diagnostic.extra","com.byd.clusternav.diagnostic"),"similar package is not our whitelist");
        System.out.println("PASS "+n+" regression assertions");
    }
}
