package com.byd.clusternav.core;

/** Only a ProcessRecord in the matching service block counts as initialized. */
public final class ServiceEvidence {
    public static boolean runningPushService(String dump,String pkg){
        String[] blocks=dump.split("(?m)^\\s*\\* ServiceRecord");
        for(int i=1;i<blocks.length;i++){
            String block=blocks[i];int end=block.indexOf('\n');
            String header=end<0?block:block.substring(0,end);
            if((header.contains(pkg+"/.service.PushService}")||header.contains(pkg+"/com.byd.automap.service.PushService}"))&&block.contains("app=ProcessRecord{"))return true;
        }
        return false;
    }
}
