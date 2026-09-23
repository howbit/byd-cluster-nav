package com.byd.clusternav.tests;
import android.app.*;
import android.os.Bundle;
import android.content.Context;
import android.util.Base64;
import java.util.*;
import java.math.BigInteger;
import java.nio.*;
import java.security.*;
import java.security.spec.*;
import javax.crypto.Cipher;
import com.byd.clusternav.adb.*;
public final class AndroidChecks extends Instrumentation {
    int count=0;
    void check(boolean ok,String name){count++;if(!ok)throw new AssertionError(name);}
    public void onCreate(Bundle args){super.onCreate(args);start();}
    public void onStart(){Bundle result=new Bundle();try{
        Context c=getTargetContext();AdbKeyManager first=new AdbKeyManager(c),second=new AdbKeyManager(c);
        byte[] encoded=first.getPublicKeyBytes();
        check(Arrays.equals(encoded,second.getPublicKeyBytes()),"key persisted identically");
        String text=new String(encoded,"UTF-8");check(text.endsWith("\0"),"ADB pub NUL");
        byte[] binary=Base64.decode(text.split(" ")[0],Base64.DEFAULT);check(binary.length==524,"ADB pub format length");
        ByteBuffer buffer=ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN);check(buffer.getInt()==64,"RSA word count");
        long n0=buffer.getInt()&0xffffffffL;byte[] nBytes=new byte[256],rrBytes=new byte[256];buffer.get(nBytes);buffer.get(rrBytes);int exponent=buffer.getInt();
        BigInteger n=le(nBytes),rr=le(rrBytes),word=BigInteger.ONE.shiftLeft(32);
        check(n.modInverse(word).negate().mod(word).longValue()==n0,"n0inv");
        check(BigInteger.ONE.shiftLeft(4096).mod(n).equals(rr),"Montgomery rr");
        PublicKey pub=KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n,BigInteger.valueOf(exponent)));
        byte[] token=new byte[20];new SecureRandom().nextBytes(token);byte[] signed=second.signToken(token);
        Cipher cipher=Cipher.getInstance("RSA/ECB/PKCS1Padding");cipher.init(Cipher.DECRYPT_MODE,pub);byte[] decoded=cipher.doFinal(signed);
        check(decoded.length==35&&Arrays.equals(token,Arrays.copyOfRange(decoded,15,35)),"ADB SHA1 DigestInfo signature on Android provider");
        AdbClient adb=new AdbClient(c);ShellResult good=adb.execute("printf '中文传输正常'",8);check(good.success&&good.output.equals("中文传输正常"),"local ADB Unicode shell");
        ShellResult bad=adb.execute("printf 'expected-error'; exit 7",8);check(!bad.success&&bad.exitCode==7&&bad.output.equals("expected-error"),"nonzero shell exit survives stream close");
        ShellResult timeout=adb.execute("sleep 2",1);check(!timeout.success&&timeout.exitCode==-1,"timeout is not success");
        result.putString("result","PASS "+count+" Android runtime assertions");finish(Activity.RESULT_OK,result);
    }catch(Throwable e){result.putString("result","FAIL "+e);finish(Activity.RESULT_CANCELED,result);}}
    static BigInteger le(byte[] v){byte[] out=new byte[v.length];for(int i=0;i<v.length;i++)out[i]=v[v.length-1-i];return new BigInteger(1,out);}
}
