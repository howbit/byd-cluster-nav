package com.byd.clusternav.core;

import java.util.regex.Pattern;

/** A successful write is not sufficient; the exact package must appear in a fresh readback. */
public final class ConfigurationEvidence {
    private ConfigurationEvidence(){}
    public static boolean deviceIdleVerified(boolean writeOk,boolean readOk,String output,String packageName){
        if(!writeOk||!readOk||output==null||packageName==null||packageName.isEmpty())return false;
        String boundary="(?m)(?:^|[\\s,])"+Pattern.quote(packageName)+"(?=$|[\\s,])";
        return Pattern.compile(boundary).matcher(output).find();
    }
}
