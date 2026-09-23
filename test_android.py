"""Run only against the explicitly selected local test emulator, never a vehicle."""
import os,pathlib,subprocess,zipfile,sys
ROOT=pathlib.Path(__file__).resolve().parent;BUILD=ROOT/'build'
SDK=pathlib.Path(os.environ['ANDROID_SDK_ROOT'])
JAVA=pathlib.Path(os.environ['JAVA_HOME'])
EXE='.exe' if os.name=='nt' else ''
BT=SDK/'build-tools/35.0.0';JAR=BUILD/'android.jar';TEST=BUILD/'android-test';TEST.mkdir(exist_ok=True)
classes=TEST/'classes';classes.mkdir(exist_ok=True)
def run(args):subprocess.run([str(a)for a in args],check=True)
run([JAVA/'bin'/('javac'+EXE),'-encoding','UTF-8','--release','8','-classpath',str(JAR)+os.pathsep+str(BUILD/'classes.jar'),'-d',classes,ROOT/'tests/AndroidChecks.java',ROOT/'tests/BootChecks.java'])
jar=TEST/'test.jar'
with zipfile.ZipFile(jar,'w')as z:
    for p in classes.rglob('*.class'):z.write(p,p.relative_to(classes).as_posix())
run([JAVA/'bin'/('java'+EXE),'-cp',BT/'lib/d8.jar','com.android.tools.r8.D8','--min-api','26','--lib',JAR,'--classpath',BUILD/'classes.jar','--output',TEST,jar])
unsigned=TEST/'unsigned.apk';apk=TEST/'checks.apk'
run([BT/('aapt'+EXE),'package','-f','-M',ROOT/'tests/AndroidManifest.xml','-I',JAR,'-F',unsigned])
with zipfile.ZipFile(unsigned,'a')as z:z.write(TEST/'classes.dex','classes.dex')
run([JAVA/'bin'/('java'+EXE),'-jar',BT/'lib/apksigner.jar','sign','--ks',os.environ['SIGNING_KEYSTORE'],'--ks-key-alias',os.environ.get('SIGNING_ALIAS','clusternav'),'--ks-pass','env:SIGNING_PASSWORD','--key-pass','env:SIGNING_PASSWORD','--out',apk,unsigned])
adb=[SDK/'platform-tools'/('adb'+EXE),'-s','emulator-5580']
run(adb+['install','--no-incremental','-r',apk])
boot_only='--boot-only' in sys.argv
test_name='BootChecks' if boot_only else 'AndroidChecks'
result=subprocess.run([str(a)for a in adb+['shell','am','instrument','-w','com.byd.clusternav.tests/com.byd.clusternav.tests.'+test_name]],capture_output=True,check=True)
text=result.stdout.decode('utf-8',errors='replace');print(text)
expected='PASS 3 boot runtime assertions' if boot_only else 'PASS 10 Android runtime assertions'
assert expected in text and 'FAIL' not in text,text
