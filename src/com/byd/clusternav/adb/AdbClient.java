package com.byd.clusternav.adb;

import android.content.Context;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static com.byd.clusternav.adb.AdbProtocol.*;

/** Local ADB only; bounded packets/output/deadline, checked transport and shell status. */
public final class AdbClient {
    private final AdbKeyManager keys;
    public AdbClient(Context context) { keys=new AdbKeyManager(context); }
    public static String quote(String s) { return "'"+s.replace("'","'\\''")+"'"; }
    public ShellResult execute(String command) { return execute(command, 8); }
    public ShellResult execute(String command,int timeoutSeconds) {
        String marker="__CN_EXIT_"+UUID.randomUUID().toString().replace("-","")+"=";
        String wrapped="( "+command+" ); cn_result=$?; printf '\\n"+marker+"%s\\n' \"$cn_result\"";
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        long deadline=System.nanoTime()+timeoutSeconds*1000000000L;
        try (Socket socket=new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1",5555),2500);
            socket.setTcpNoDelay(true);
            DataInputStream in=new DataInputStream(socket.getInputStream());
            OutputStream out=socket.getOutputStream();
            send(out,CMD_CNXN,ADB_VERSION,MAX_DATA,"host::clusternav\0".getBytes(StandardCharsets.UTF_8));
            boolean signature=false,publicSent=false;
            while(true) {
                AdbMessage m=read(in,socket,deadline);
                if(m.command==CMD_CNXN) break;
                if(m.command!=CMD_AUTH || m.arg0!=AUTH_TYPE_TOKEN) throw new IOException("ADB 握手被拒绝");
                if(!signature) { send(out,CMD_AUTH,AUTH_TYPE_SIGNATURE,0,keys.signToken(m.data)); signature=true; }
                else if(!publicSent) { send(out,CMD_AUTH,AUTH_TYPE_RSA_PUBLIC,0,keys.getPublicKeyBytes()); publicSent=true; }
                else throw new IOException("请在车机 ADB 授权弹窗中允许本应用");
            }
            send(out,CMD_OPEN,1,0,("shell:"+wrapped+"\0").getBytes(StandardCharsets.UTF_8));
            int remote=0;
            while(true) {
                AdbMessage m=read(in,socket,deadline);
                if(m.arg1!=1) throw new IOException("ADB stream ID 不匹配");
                if(remote!=0 && m.arg0!=remote) throw new IOException("ADB remote ID 不匹配");
                if(m.command==CMD_OKAY) remote=m.arg0;
                else if(m.command==CMD_WRTE) {
                    remote=m.arg0;
                    if(bytes.size()+m.data.length>6*1024*1024) throw new IOException("单命令输出超过 6 MB");
                    bytes.write(m.data);
                    send(out,CMD_OKAY,1,remote,new byte[0]);
                } else if(m.command==CMD_CLSE) {
                    send(out,CMD_CLSE,1,m.arg0,new byte[0]);
                    return ShellResult.parse(new String(bytes.toByteArray(),StandardCharsets.UTF_8),marker);
                } else throw new IOException("意外的 ADB 数据包");
            }
        } catch(Exception e) {
            return new ShellResult(-1,new String(bytes.toByteArray(),StandardCharsets.UTF_8),e.toString());
        }
    }
    private static void send(OutputStream out,int command,int a,int b,byte[] data) throws IOException {
        out.write(new AdbMessage(command,a,b,data.length,calculateChecksum(data),command^-1,data).toByteArray()); out.flush();
    }
    private static void fully(DataInputStream in,Socket socket,byte[] data,long deadline) throws IOException {
        int offset=0;
        while(offset<data.length) {
            long ms=(deadline-System.nanoTime())/1000000L;
            if(ms<=0) throw new SocketTimeoutException("ADB 总时限已到");
            socket.setSoTimeout((int)Math.min(10000,ms));
            int n=in.read(data,offset,data.length-offset);
            if(n<0) throw new EOFException("ADB 连接提前断开");
            offset+=n;
        }
    }
    private static AdbMessage read(DataInputStream in,Socket socket,long deadline) throws IOException {
        byte[] header=new byte[24]; fully(in,socket,header,deadline);
        ByteBuffer b=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int cmd=b.getInt(),a=b.getInt(),c=b.getInt(),length=b.getInt(),crc=b.getInt(),magic=b.getInt();
        if(magic!=(cmd^-1) || length<0 || length>1024*1024) throw new IOException("非法 ADB 包头");
        byte[] data=new byte[length]; fully(in,socket,data,deadline);
        if(crc!=calculateChecksum(data)) throw new IOException("ADB 校验和错误");
        return new AdbMessage(cmd,a,c,length,crc,magic,data);
    }
}
