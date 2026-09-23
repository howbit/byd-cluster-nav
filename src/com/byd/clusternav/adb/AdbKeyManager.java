package com.byd.clusternav.adb;

import android.content.Context;
import android.util.Base64;
import java.io.*;
import java.math.BigInteger;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.*;
import java.security.spec.*;
import javax.crypto.Cipher;

/** Each installation owns a new key. No pre-authorized third-party private key. */
public final class AdbKeyManager {
    private final PrivateKey privateKey;
    private final RSAPublicKey publicKey;
    public AdbKeyManager(Context context) {
        try {
            File file = new File(context.getNoBackupFilesDir(), "local-adb-v24.keypair");
            KeyFactory factory = KeyFactory.getInstance("RSA");
            synchronized (AdbKeyManager.class) {
                if (file.exists()) {
                    // Conscrypt can expose only RSAPrivateKeySpec after PKCS8 import.
                    // Persist X509 public data instead of assuming CRT factors are exposed.
                    try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
                        privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(readPart(in)));
                        publicKey = (RSAPublicKey) factory.generatePublic(new X509EncodedKeySpec(readPart(in)));
                    }
                } else {
                    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                    generator.initialize(2048);
                    KeyPair pair = generator.generateKeyPair();
                    privateKey = pair.getPrivate();
                    publicKey = (RSAPublicKey) pair.getPublic();
                    File temp = new File(file.getPath() + ".tmp");
                    try (FileOutputStream fileOut = new FileOutputStream(temp);DataOutputStream out=new DataOutputStream(fileOut)) {
                        byte[] priv=privateKey.getEncoded(),pub=publicKey.getEncoded();
                        out.writeInt(priv.length);out.write(priv);out.writeInt(pub.length);out.write(pub);out.flush();fileOut.getFD().sync();
                    }
                    if (!temp.renameTo(file)) throw new IOException("保存本机 ADB 密钥失败");
                }
            }
        } catch (Exception e) { android.util.Log.e("ClusterNav","ADB key initialization failed",e);throw new IllegalStateException("ADB 密钥初始化失败："+e, e); }
    }
    private static byte[] readPart(DataInputStream in)throws IOException {
        int length=in.readInt();if(length<32||length>8192)throw new IOException("本机 ADB 密钥文件损坏");
        byte[] data=new byte[length];in.readFully(data);return data;
    }
    public byte[] signToken(byte[] token) throws Exception {
        if (token.length != 20) throw new IOException("非法 ADB 鉴权 token 长度");
        // DigestInfo(SHA-1) + the already hashed 20-byte token, PKCS#1 v1.5.
        byte[] prefix = {0x30,0x21,0x30,0x09,0x06,0x05,0x2b,0x0e,0x03,0x02,0x1a,0x05,0x00,0x04,0x14};
        byte[] digest = new byte[prefix.length + token.length];
        System.arraycopy(prefix,0,digest,0,prefix.length);
        System.arraycopy(token,0,digest,prefix.length,token.length);
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE,privateKey);
        return cipher.doFinal(digest);
    }
    public byte[] getPublicKeyBytes() {
        BigInteger n = publicKey.getModulus();
        ByteBuffer out = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        BigInteger word = BigInteger.ONE.shiftLeft(32);
        out.putInt(64);
        out.putInt(n.modInverse(word).negate().mod(word).intValue());
        putLE(out,n);
        putLE(out,BigInteger.ONE.shiftLeft(4096).mod(n));
        out.putInt(publicKey.getPublicExponent().intValue());
        return (Base64.encodeToString(out.array(),Base64.NO_WRAP) + " clusternav@localhost\0").getBytes(StandardCharsets.UTF_8);
    }
    private static void putLE(ByteBuffer out, BigInteger value) {
        for (int i=0;i<64;i++) out.putInt(value.shiftRight(i*32).intValue());
    }
}
