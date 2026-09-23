package com.byd.clusternav.tests;

import android.content.ContextWrapper;
import android.os.SystemClock;
import java.io.*;
import java.lang.reflect.Field;
import java.util.Arrays;
import com.byd.clusternav.core.NativeBridge;

/** Emulator-only fake vendor context, packaged in the separate test DEX, never the APK. */
public final class GuardBridge {
    public static final class Manager {
        final String log;
        Manager(String log){this.log=log;}
        public int sendInfo(int group,int command,String text) throws IOException {
            try(FileWriter out=new FileWriter(log,true)){out.write(SystemClock.elapsedRealtime()+","+group+","+command+","+text+"\n");}
            return new File(log+".menu-deny").exists()?-1:0;
        }
    }
    public static final class Auto {
        final String log;
        Auto(String log){this.log=log;}
        public int setInt(int area,int property,int value)throws IOException{
            if(area!=1007||property!=1276157976)throw new IllegalArgumentException("wrong write property");
            try(FileWriter out=new FileWriter(log,true)){out.write(SystemClock.elapsedRealtime()+","+area+","+value+",property="+property+"\n");}
            if(!new File(log+".state-deny").exists())try(FileWriter out=new FileWriter(log+".state")){out.write(String.valueOf(value));}
            return 0;
        }
        public int getInt(int area,int property)throws IOException{
            if(area!=1007||property!=1086337074)throw new IllegalArgumentException("wrong read property");
            File f=new File(log+".state");if(!f.exists())return 0;
            try(BufferedReader in=new BufferedReader(new FileReader(f))){return Integer.parseInt(in.readLine());}
        }
    }
    public static void main(String[] args)throws Exception {
        if(args.length<2||!args[0].matches("/data/local/tmp/cn26-test-[a-f0-9-]+\\.operations"))throw new IllegalArgumentException();
        final Manager manager=new Manager(args[0]);
        final Auto auto=new Auto(args[0]);
        java.lang.reflect.Method getContext=NativeBridge.class.getDeclaredMethod("context");getContext.setAccessible(true);
        android.content.Context original=(android.content.Context)getContext.invoke(null);
        Field context=NativeBridge.class.getDeclaredField("context");context.setAccessible(true);
        context.set(null,new ContextWrapper(original){public Object getSystemService(String name){return name.equals("AutoContainer")?manager:name.equals("auto")?auto:super.getSystemService(name);}});
        NativeBridge.main(Arrays.copyOfRange(args,1,args.length));
    }
}
