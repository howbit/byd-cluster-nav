"""Fake vendor context on emulator-5580 ONLY. No test hooks enter the production APK."""
import os,pathlib,subprocess,zipfile,time,uuid,json
ROOT=pathlib.Path(__file__).resolve().parent;BUILD=ROOT/'build'
SDK=pathlib.Path(os.environ['ANDROID_SDK_ROOT'])
JAVA=pathlib.Path(os.environ['JAVA_HOME'])
EXE='.exe' if os.name=='nt' else ''
BT=SDK/'build-tools/35.0.0';TEST=BUILD/'guard-test';TEST.mkdir(exist_ok=True)
classes=TEST/'classes';classes.mkdir(exist_ok=True)
def run(args):subprocess.run([str(x)for x in args],check=True)
run([JAVA/'bin'/('javac'+EXE),'-encoding','UTF-8','--release','8','-classpath',str(BUILD/'android.jar')+os.pathsep+str(BUILD/'classes.jar'),'-d',classes,ROOT/'tests/GuardBridge.java'])
jar=TEST/'test.jar'
with zipfile.ZipFile(jar,'w')as z:
 for p in classes.rglob('*.class'):z.write(p,p.relative_to(classes).as_posix())
run([JAVA/'bin'/('java'+EXE),'-cp',BT/'lib/d8.jar','com.android.tools.r8.D8','--min-api','26','--lib',BUILD/'android.jar','--classpath',BUILD/'classes.jar','--output',TEST,jar])
adb=[str(SDK/'platform-tools'/('adb'+EXE)),'-s','emulator-5580']
def shell(cmd):
 p=subprocess.run(adb+['shell',cmd],capture_output=True,timeout=15)
 return p.returncode,p.stdout.decode('utf-8',errors='replace')
assert shell('getprop ro.kernel.qemu')[1].strip()=='1','Tests require an emulator'
pkg='com.byd.clusternav.diagnostic'
apk=shell('pm path '+pkg)[1].strip().split('package:',1)[1]
run(adb+['push',TEST/'classes.dex','/data/local/tmp/cn26-tests.dex'])
base="env CLASSPATH='"+apk+":/data/local/tmp/cn26-tests.dex' app_process /system/bin com.byd.clusternav.tests.GuardBridge "
count=0
def check(ok,label):
 global count
 assert ok,label
 count+=1;print('PASS',label,flush=True)
def call(t,cmd):return shell(base+'/data/local/tmp/cn26-test-'+t+'.operations '+cmd)
def root(t):return '/data/local/tmp/clusternav26_'+t
def arm(route='container'):
 t=str(uuid.uuid4());r=root(t)
 assert call(t,'init '+t+' '+route)[0]==0
 command='nohup '+base+'/data/local/tmp/cn26-test-'+t+'.operations guard '+t+' '+route+' >'+r+'.log 2>&1 </dev/null & cn_guard=$!; cn_wait=0; while [ ! -s '+r+'.ready ]; do if ! kill -0 "$cn_guard" 2>/dev/null; then cat '+r+'.log; exit 1; fi; cn_wait=$((cn_wait+1)); if [ "$cn_wait" -ge 60 ]; then cat '+r+'.log; exit 1; fi; sleep 0.1; done; cat '+r+'.ready'
 code,out=shell(command)
 assert code==0 and out.strip().isdigit(),out
 return t,time.monotonic()

def ops(t):
 return [line.split(',')for line in shell('cat /data/local/tmp/cn26-test-'+t+'.operations')[1].splitlines()if ','in line]
def codes(t,group=1000):return [int(x[2])for x in ops(t) if int(x[1])==group]
# Independent timeout runs alongside shorter cancellation/readback cases.
a,started=arm();check(call(a,'set 4 '+a)[0]==0,'full mode entered with typed int-return API')
check(call(a,'complete '+a)[0]!=0 and codes(a)==[16],'followup before 6 seconds blocked')
check(call(a,'confirm '+a)[0]!=0,'confirmation before completed sequence blocked')
shell('am force-stop '+pkg)
b,_=arm();check(call(b,'set 3 '+b)[0]==0,'split mode entered')
check(call(b,'restore '+b)[0]==0,'manual close during delay returned')
check(codes(b,1007)==[1],'close writes OEM navigation state 1, not 0')
check(call(b,'complete '+b)[0]!=0 and codes(b)==[17,18],'stopped session cannot receive late followup')
check(call(b,'set 4 '+b)[0]!=0 and call(b,'navigate '+b)[0]!=0,'stopped session cannot reactivate or select navigation')
check(call(b,'map-start '+b+' com.byd.automap true 1')[0]!=0,'stopped session cannot restart map')
check(call(b,'meter-start '+b+' com.byd.automap 1')[0]!=0,'stopped session cannot start meter')
c,cstart=arm();check(call(c,'set 4 '+c)[0]==0,'confirmation case activated')
time.sleep(6.2)
check(call(a,'complete '+a)[0]==0,'followup executes after 6 second interval')
check(call(a,'navigate '+a)[0]==0 and codes(a,1007)==[4],'full navigation state requested after menu preparation')
check(call(c,'complete '+c)[0]==0,'confirmation sequence completed')
check(call(c,'confirm '+c)[0]!=0,'cannot confirm before selecting current navigation mode')
check(call(c,'navigate '+c)[0]==0,'navigation selection returned')
check(call(c,'map-start '+c+' malicious.pkg true 1')[0]!=0,'unapproved map package rejected')
check(call(c,'map-start '+c+' com.byd.automap true 99')[0]!=0,'absent vendor display blocks map force-stop')
check(call(c,'confirm '+c)[0]==0,'completed sequence can be confirmed')
time.sleep(.5)
check('USER_CONFIRMED'in shell('cat '+root(c)+'.done')[1] and codes(c)==[16,35],'confirmed guard exits without close command')
check(call(c,'complete '+c)[0]!=0,'confirmed session blocks duplicate followup')
check(call(c,'restore '+c)[0]==0 and codes(c)==[16,35,18] and codes(c,1007)==[4,1],'explicit close works after confirmation')
# A late external state change must not be hidden by an earlier .restored marker.
shell('echo 4 > /data/local/tmp/cn26-test-'+c+'.operations.state')
check(call(c,'restore '+c)[0]==0 and codes(c,1007)==[4,1,1],'close rechecks actual state before skipping')
# Recover route loss before the guard starts (the physical report 1 failure).
missing=str(uuid.uuid4())
code,out=call(missing,'restore '+missing)
check(code==0 and 'sessionRouteMissing' in out and codes(missing,1007)==[1],'missing route still closes detected backend')
check(call(missing,'init '+missing+' container')[0]!=0,'stop tombstone prevents late initialization')
# A returning API is not proof of closed state.
d,_=arm();check(call(d,'set 4 '+d)[0]==0,'readback failure case activated')
shell('echo 4 > /data/local/tmp/cn26-test-'+d+'.operations.state')
shell('touch /data/local/tmp/cn26-test-'+d+'.operations.state-deny')
code,out=call(d,'restore '+d)
check(code!=0 and 'CN_CLOSE_UNCONFIRMED' in out,'state readback mismatch is reported as failure')
check(18 in codes(d),'container close still attempted after state mismatch')
shell('rm /data/local/tmp/cn26-test-'+d+'.operations.state-deny')
check(call(d,'restore '+d)[0]==0,'failed close can be retried')
# A container rejection must also remain a failure despite a correct state readback.
e,_=arm();call(e,'set 3 '+e)
shell('touch /data/local/tmp/cn26-test-'+e+'.operations.menu-deny')
check(call(e,'restore '+e)[0]!=0 and codes(e,1007)[-1]==1,'menu rejection preserves failure but attempts state close')
shell('rm /data/local/tmp/cn26-test-'+e+'.operations.menu-deny')
check(call(e,'restore '+e)[0]==0,'menu close retry succeeds')
time.sleep(max(0,started+63-time.monotonic()))
check('RESTORE_CALL_RETURNED'in shell('cat '+root(a)+'.done')[1] and codes(a)==[16,35,18] and codes(a,1007)==[4,1],'60 second guard survives app force-stop and closes both channels')
sequence=ops(a);menu=[x for x in sequence if x[1]=='1000']
check(int(menu[1][0])-int(menu[0][0])>=6000,'recorded followup interval >= 6000 ms')
check(call(a,'set 4 '+a)[0]!=0 and call(a,'complete '+a)[0]!=0 and call(a,'navigate '+a)[0]!=0,'expired session blocks all activation commands')
print('PASS',count,'guard integration assertions')
(TEST/'results.json').write_text(json.dumps({'assertions':count,'timeout':ops(a),'cancelled':ops(b),'confirmed':ops(c),'readback_mismatch':ops(d),'menu_rejection':ops(e)},indent=2),encoding='utf-8')
