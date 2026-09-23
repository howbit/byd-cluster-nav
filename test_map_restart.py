"""Android emulator only: real am force-stop/start and virtual display; fake map/vendor service.
Requires test_guard.py to have built its separate guard DEX. Never run against a vehicle.
"""
import os,pathlib,subprocess,zipfile,time,uuid,re,json,shlex,xml.etree.ElementTree as ET
ROOT=pathlib.Path(__file__).resolve().parent;BUILD=ROOT/'build';TEST=BUILD/'map-test';TEST.mkdir(exist_ok=True)
SDK=pathlib.Path(os.environ['ANDROID_SDK_ROOT'])
JAVA=pathlib.Path(os.environ['JAVA_HOME'])
EXE='.exe' if os.name=='nt' else ''
BT=SDK/'build-tools/35.0.0';JAR=BUILD/'android.jar'
adb=[str(SDK/'platform-tools'/('adb'+EXE)),'-s','emulator-5580']
def run(args):subprocess.run([str(x) for x in args],check=True)
def shell(cmd):
 p=subprocess.run(adb+['shell',cmd],capture_output=True,timeout=25)
 return p.returncode,p.stdout.decode('utf-8',errors='replace')
def ok(cmd):
 code,out=shell(cmd);assert code==0,(cmd,out);return out
assert ok('getprop ro.kernel.qemu').strip()=='1'
assert shell('pm path com.byd.automap')[0]!=0,'Do not replace an existing map, even on the emulator'
def compile_dex(name,sources,classpath):
 folder=TEST/name;classes=folder/'classes';classes.mkdir(parents=True,exist_ok=True)
 run([JAVA/'bin'/('javac'+EXE),'-encoding','UTF-8','--release','8','-classpath',classpath,'-d',classes,*sources])
 jar=folder/'classes.jar'
 with zipfile.ZipFile(jar,'w')as z:
  for p in classes.rglob('*.class'):z.write(p,p.relative_to(classes).as_posix())
 run([JAVA/'bin'/('java'+EXE),'-cp',BT/'lib/d8.jar','com.android.tools.r8.D8','--min-api','26','--lib',JAR,'--classpath',BUILD/'classes.jar','--output',folder,jar])
 return folder/'classes.dex'
dex=compile_dex('fixture',list((ROOT/'tests/fixture').glob('*.java')),str(JAR))
unsigned=TEST/'unsigned.apk';fixture=TEST/'fixture.apk'
run([BT/('aapt'+EXE),'package','-f','-M',ROOT/'tests/fixture/AndroidManifest.xml','-I',JAR,'-F',unsigned])
with zipfile.ZipFile(unsigned,'a')as z:z.write(dex,'classes.dex')
run([JAVA/'bin'/('java'+EXE),'-jar',BT/'lib/apksigner.jar','sign','--ks',os.environ['SIGNING_KEYSTORE'],'--ks-key-alias',os.environ.get('SIGNING_ALIAS','clusternav'),'--ks-pass','env:SIGNING_PASSWORD','--key-pass','env:SIGNING_PASSWORD','--out',fixture,unsigned])
keeper=compile_dex('keeper',[ROOT/'tests/DisplayKeeper.java'],str(JAR)+os.pathsep+str(BUILD/'classes.jar'))
run(adb+['push',keeper,'/data/local/tmp/cn26-keeper.dex'])
apk=ok('pm path com.byd.clusternav.diagnostic').strip().split('package:',1)[1]
count=0;records={};t=str(uuid.uuid4());root='/data/local/tmp/clusternav26_'+t
base="env CLASSPATH='"+apk+":/data/local/tmp/cn26-tests.dex' app_process /system/bin com.byd.clusternav.tests.GuardBridge /data/local/tmp/cn26-test-"+t+'.operations '
def call(cmd):return ok(base+cmd)
def check(value,label):
 global count
 assert value,label
 count+=1;print('PASS',label,flush=True)
def cache():
 data=ok('run-as com.byd.automap cat shared_prefs/cache.xml');node=ET.fromstring(data)
 return {n.attrib['name']:n.attrib.get('value',n.text)for n in node}
keeper_pid=None;installed=False
try:
 run(adb+['install','--no-incremental',fixture]);installed=True
 ok('am start -W --display 0 -n com.byd.automap/.MainActivity');time.sleep(1)
 before=cache();records['before']=before
 check(before['support']=='false','map starts before vendor display and caches unsupported')
 launch="nohup env CLASSPATH='"+apk+":/data/local/tmp/cn26-keeper.dex' app_process /system/bin com.byd.clusternav.tests.DisplayKeeper >/data/local/tmp/cn26-display.log 2>&1 </dev/null & cn_keeper=$!; echo $cn_keeper; cn_wait=0; while ! grep -q DISPLAY= /data/local/tmp/cn26-display.log; do cn_wait=$((cn_wait+1)); if [ $cn_wait -ge 80 ] || ! kill -0 $cn_keeper 2>/dev/null; then cat /data/local/tmp/cn26-display.log; exit 1; fi; sleep .1; done"
 keeper_pid=ok(launch).strip();assert keeper_pid.isdigit(),keeper_pid
 display=None
 for _ in range(30):
  text=ok('cat /data/local/tmp/cn26-display.log');match=re.search(r'DISPLAY=(\d+)',text)
  if match:display=int(match[1]);break
  time.sleep(.2)
 assert display is not None,text
 check(cache()['support']=='false','adding display alone does not update initialized service cache')
 call('init '+t+' container')
 ok('nohup '+base+'guard '+t+' container >'+root+'.log 2>&1 </dev/null & cn_wait=0; while [ ! -s '+root+'.ready ]; do cn_wait=$((cn_wait+1)); if [ "$cn_wait" -ge 60 ]; then cat '+root+'.log; exit 1; fi; sleep .1; done; cat '+root+'.ready')
 call('set 4 '+t);time.sleep(6.1);call('complete '+t);call('navigate '+t)
 out=call('map-start '+t+' com.byd.automap true '+str(display));records['restart']=out
 time.sleep(1);after=cache();records['after']=after
 check('CN_MAP_STARTED' in out and before['pid']!=after['pid'],'native bridge really force-stops and relaunches map process')
 check(after['support']=='true','new service sees already-created vendor display')
 out=call('meter-start '+t+' com.byd.automap '+str(display));records['meter']=out
 check('CN_METER_STARTED' in out,'real Android am accepts dynamically selected display and MeterActivity')
 time.sleep(1)
 windows=ok('dumpsys window windows');(TEST/'windows.txt').write_text(windows,encoding='utf-8')
 blocks=re.split(r'(?m)^\s*Window #\d+',windows)
 check(any('com.byd.automap.extra.MeterActivity' in b and re.search(r'mDisplayId='+str(display)+r'\b',b)and'mHasSurface=true'in b and'isOnScreen=true'in b for b in blocks),'meter window has visible Surface on actual selected display')
 # A new session must be able to adopt a surviving vendor display after its map window disappears.
 adopted=str(uuid.uuid4());adopt_root='/data/local/tmp/clusternav26_'+adopted
 adopt_ops='/data/local/tmp/cn26-test-'+adopted+'.operations'
 adopt_base="env CLASSPATH='"+apk+":/data/local/tmp/cn26-tests.dex' app_process /system/bin com.byd.clusternav.tests.GuardBridge "+adopt_ops+' '
 ok('echo 4 > '+adopt_ops+'.state')
 probe=json.loads(ok(adopt_base+'probe').split('CN_JSON:',1)[1].splitlines()[0])
 candidate=next(d for d in probe['displays']if d['id']==display)
 identity=str(display)+'|'+candidate['name']+'|'+candidate['uniqueId']
 ok(adopt_base+'init '+adopted+' container')
 ok('nohup '+adopt_base+'guard '+adopted+' container >'+adopt_root+'.log 2>&1 </dev/null & cn_wait=0; while [ ! -s '+adopt_root+'.ready ]; do cn_wait=$((cn_wait+1)); if [ "$cn_wait" -ge 60 ]; then cat '+adopt_root+'.log; exit 1; fi; sleep .1; done')
 check(shell(adopt_base+'map-start '+adopted+' com.byd.automap false '+str(display))[0]!=0,'new session cannot launch map without verified adoption')
 check(shell(adopt_base+'adopt '+adopted+' 4 '+str(display)+' '+shlex.quote(identity+'-wrong'))[0]!=0,'adoption rejects a changed display identity')
 check(shell(adopt_base+'adopt '+adopted+' 3 '+str(display)+' '+shlex.quote(identity))[0]!=0,'adoption rejects a changed hardware mode')
 adopted_output=ok(adopt_base+'adopt '+adopted+' 4 '+str(display)+' '+shlex.quote(identity))
 check('CN_EXISTING_CHANNEL_ADOPTED' in adopted_output,'verified existing channel can be adopted without new 16/35 handshake')
 check('CN_MAP_STARTED' in ok(adopt_base+'map-start '+adopted+' com.byd.automap false '+str(display)),'adopted session can start map')
 check('CN_METER_STARTED' in ok(adopt_base+'meter-start '+adopted+' com.byd.automap '+str(display)),'adopted session records mode for meter fallback')
 check(shell('test ! -s '+adopt_ops)[0]==0,'adoption did not rewrite vendor mode before launching map')
 ok(adopt_base+'restore '+adopted)
 call('restore '+t)
 check(shell(base+'map-start '+t+' com.byd.automap true '+str(display))[0]!=0,'manual stop prevents another map restart')
 check(cache()['pid']==after['pid'],'blocked restart leaves map process intact')
finally:
 shell(base+'restore '+t)
 if 'adopted' in locals():shell(adopt_base+'restore '+adopted)
 if keeper_pid:shell('kill '+keeper_pid)
 if installed:run(adb+['uninstall','com.byd.automap'])
print('PASS',count,'map restart integration assertions')
(TEST/'results.json').write_text(json.dumps(dict(assertions=count,**records),indent=2),encoding='utf-8')
