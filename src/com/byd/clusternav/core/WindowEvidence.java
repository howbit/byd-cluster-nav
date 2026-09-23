package com.byd.clusternav.core;

import java.util.regex.*;
public final class WindowEvidence {
    public static boolean hasSurfaceOnDisplay(String dump,String className,int id) {
        // Evaluate each Window block independently; global matches can link unrelated windows.
        String[] blocks=dump.split("(?m)(?=^[ \\t]*Window #\\d+ Window\\{)");
        for(String b:blocks) {
            int end=b.indexOf('\n');String header=end<0?b:b.substring(0,end);
            Matcher component=Pattern.compile("([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)").matcher(header);
            boolean target=false;
            while(component.find()){
                String cls=component.group(2);
                if(cls.startsWith("."))cls=component.group(1)+cls;
                if(cls.equals(className))target=true;
            }
            if(!target)continue;
            if(Pattern.compile("\\bmDisplayId="+id+"\\b").matcher(b).find() && b.contains("mHasSurface=true") && (b.contains("isOnScreen=true") || b.contains("isVisible=true")))return true;
        }
        return false;
    }
}
