"""Offline Android 29 build. Provide your own SDK, JDK and signing key via env."""
import os, pathlib, subprocess, zipfile, hashlib, sys, shutil
ROOT=pathlib.Path(__file__).resolve().parent
SDK=pathlib.Path(os.environ['ANDROID_SDK_ROOT'])
JAVA=pathlib.Path(os.environ['JAVA_HOME'])
BT=SDK/'build-tools'/os.environ.get('BUILD_TOOLS_VERSION','35.0.0'); JAR=SDK/'platforms'/'android-29'/'android.jar'
suffix='.exe' if os.name=='nt' else ''
def java_tool(name):return JAVA/'bin'/(name+suffix)
def sdk_tool(name):return BT/(name+suffix)
BUILD=ROOT/'build'; BUILD.mkdir(exist_ok=True)
# Work around Windows ACL canonicalization failures on externally provisioned SDK jars.
local_jar=BUILD/'android.jar'
if not local_jar.exists(): shutil.copyfile(JAR,local_jar)
JAR=local_jar
CLASSES=BUILD/'classes'; GEN=BUILD/'gen'; DEX=BUILD/'dex'
for p in (CLASSES,GEN,DEX):
    assert p.resolve().parent == BUILD.resolve() and BUILD.resolve().parent == ROOT
    if p.exists(): shutil.rmtree(p)
    p.mkdir()
env=dict(os.environ,JAVA_HOME=str(JAVA),PATH=str(JAVA/'bin')+os.pathsep+os.environ.get('PATH',''))
def run(args):
    args=[str(a) for a in args]
    print('RUN',pathlib.Path(args[0]).name,flush=True)
    subprocess.run(args,check=True,env=env,cwd=ROOT)
def argfile(name,values):
    p=BUILD/name;p.write_text('\n'.join('"'+str(v).replace('\\','/')+'"' for v in values),encoding='utf-8');return '@'+str(p)
run([sdk_tool('aapt'),'package','-f','-m','-J',GEN,'-M',ROOT/'AndroidManifest.xml','-S',ROOT/'res','-I',JAR])
run([java_tool('javac'),'-encoding','UTF-8','--release','8','-classpath',JAR,'-d',CLASSES,argfile('java.args',list((ROOT/'src').rglob('*.java'))+list(GEN.rglob('*.java')))])
class_jar=BUILD/'classes.jar'
with zipfile.ZipFile(class_jar,'w') as z:
    for p in CLASSES.rglob('*.class'):z.write(p,p.relative_to(CLASSES).as_posix())
run([java_tool('java'),'-cp',BT/'lib/d8.jar','com.android.tools.r8.D8','--min-api','26','--lib',JAR,'--output',DEX,class_jar])
unsigned=BUILD/'unsigned.apk';aligned=BUILD/'aligned.apk'
run([sdk_tool('aapt'),'package','-f','-M',ROOT/'AndroidManifest.xml','-S',ROOT/'res','-I',JAR,'-F',unsigned])
with zipfile.ZipFile(unsigned,'a') as z:
    for d in DEX.glob('*.dex'):z.write(d,d.name)
run([sdk_tool('zipalign'),'-f','4',unsigned,aligned])
key=pathlib.Path(os.environ['SIGNING_KEYSTORE']);output=pathlib.Path(os.environ.get('OUTPUT_APK',str(BUILD/'clusternav-v2.9.8.apk')))
output.parent.mkdir(parents=True,exist_ok=True)
run([java_tool('java'),'-jar',BT/'lib/apksigner.jar','sign','--ks',key,'--ks-key-alias',os.environ.get('SIGNING_ALIAS','clusternav'),'--ks-pass','env:SIGNING_PASSWORD','--key-pass','env:SIGNING_PASSWORD','--out',output,aligned])
run([java_tool('java'),'-jar',BT/'lib/apksigner.jar','verify','--verbose','--print-certs',output])
run([sdk_tool('zipalign'),'-c','4',output])
print('APK',output,'SHA256',hashlib.sha256(output.read_bytes()).hexdigest())
TEST=BUILD/'tests';TEST.mkdir(exist_ok=True)
pure=[ROOT/'src/com/byd/clusternav/adb/ShellResult.java',ROOT/'src/com/byd/clusternav/core/DisplayPolicy.java',ROOT/'src/com/byd/clusternav/core/WindowEvidence.java',ROOT/'src/com/byd/clusternav/core/NavigationRoute.java',ROOT/'src/com/byd/clusternav/core/ContainerChannel.java',ROOT/'src/com/byd/clusternav/core/BootEvidence.java',ROOT/'src/com/byd/clusternav/core/ServiceEvidence.java',ROOT/'src/com/byd/clusternav/core/SwitchEvidence.java',ROOT/'src/com/byd/clusternav/core/ConfigurationEvidence.java',ROOT/'tests/CoreTest.java']
run([java_tool('javac'),'-encoding','UTF-8','-d',TEST]+pure)
run([java_tool('java'),'-cp',TEST,'CoreTest'])
