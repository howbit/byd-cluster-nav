"""UI, persisted baseline and real OS reboot on emulator-5580 only. Not a vehicle script."""
import os,pathlib,subprocess,time,re,zipfile,json,xml.etree.ElementTree as ET
ROOT=pathlib.Path(__file__).resolve().parent;OUT=ROOT/'build/reboot-test';OUT.mkdir(exist_ok=True)
ADB=str(pathlib.Path(os.environ['ANDROID_SDK_ROOT'])/'platform-tools'/('adb.exe' if os.name=='nt' else 'adb'))
PKG='com.byd.clusternav.diagnostic';COUNT=0
def adb(*args,check=True):
 p=subprocess.run([ADB,'-s','emulator-5580',*args],capture_output=True,timeout=25)
 if check:assert p.returncode==0,(args,p.stderr.decode(errors='replace'))
 return p.stdout.decode('utf-8',errors='replace')
assert adb('shell','getprop','ro.kernel.qemu').strip()=='1'
def check(value,label):
 global COUNT
 assert value,label
 COUNT+=1;print('PASS',label,flush=True)
def tree():
 adb('shell','uiautomator','dump','/sdcard/cn26-ui.xml')
 return ET.fromstring(adb('shell','cat','/sdcard/cn26-ui.xml'))
def find(label):
 for _ in range(8):
  for n in tree().iter('node'):
   if n.get('text')==label:return n
  adb('shell','input','swipe','850','620','850','180','200')
 raise AssertionError('Missing UI control: '+label)
def tap(label):
 node=find(label);assert node.get('enabled')=='true',label+' disabled'
 a,b,c,d=map(int,re.findall(r'\d+',node.get('bounds')))
 adb('shell','input','tap',str((a+c)//2),str((b+d)//2))
def latest_entry(filename,previous=None):
 until=time.monotonic()+100;path=OUT/'report.zip'
 while time.monotonic()<until:
  try:
   adb('pull','/sdcard/Android/data/'+PKG+'/files/diagnostics/latest-report.zip',str(path))
   with zipfile.ZipFile(path)as z:
    matches=sorted(n for n in z.namelist()if n.endswith('/'+filename))
    if matches and matches[-1]!=previous:return matches[-1],json.loads(z.read(matches[-1]))
  except (AssertionError,zipfile.BadZipFile):pass
  time.sleep(1)
 raise AssertionError('No completed '+filename)
def launch():
 adb('reverse','tcp:5555','tcp:5581')
 adb('shell','am','start','--display','0','-n',PKG+'/com.byd.clusternav.ui.MainActivity');time.sleep(1)
launch()
checkbox=find('副屏就绪后重启高德（中断当前导航，保留地图数据）')
check(checkbox.get('checked')=='true','map restart enabled by default in real Android UI')
tap('车机重启前采样');_,baseline=latest_entry('reboot_baseline.json')
check(bool(baseline.get('bootId'))or baseline.get('bootCount',-1)>=0,'pre-reboot baseline contains readable OS boot evidence')
tap('重启后对照采集');old_name,same=latest_entry('reboot_comparison.json')
check(same['rebootDetected']is False,'reopening comparison without OS reboot is not falsely detected')
adb('reboot');time.sleep(4)
until=time.monotonic()+150
while time.monotonic()<until:
 if adb('shell','getprop','sys.boot_completed',check=False).strip()=='1':break
 time.sleep(2)
else:raise AssertionError('emulator failed to reboot')
launch();tap('重启后对照采集');_,changed=latest_entry('reboot_comparison.json',old_name)
check(changed['rebootDetected']is True,'real emulator OS reboot detected using persisted pre-reboot baseline')
(OUT/'results.json').write_text(json.dumps(dict(assertions=COUNT,before=baseline,same_boot=same,after_reboot=changed),ensure_ascii=False,indent=2),encoding='utf-8')
adb('shell','am','force-stop',PKG);launch()
image=subprocess.run([ADB,'-s','emulator-5580','exec-out','screencap','-p'],capture_output=True,check=True).stdout
(OUT/'main.png').write_bytes(image)
print('PASS',COUNT,'reboot UI assertions',flush=True)
