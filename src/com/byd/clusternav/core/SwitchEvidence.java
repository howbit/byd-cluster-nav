package com.byd.clusternav.core;

import java.util.Locale;

/** Read an OEM toggle as true, false or unknown; unknown must never be treated as OFF. */
public final class SwitchEvidence {
    private SwitchEvidence(){}

    public static Boolean read(boolean checkable,boolean checked,String description,String text){
        if(checkable)return checked;
        Boolean d=parse(description);
        return d!=null?d:parse(text);
    }
    private static Boolean parse(String value){
        if(value==null)return null;
        String s=value.trim().toLowerCase(Locale.ROOT);
        if(s.equals("off")||s.equals("false")||s.equals("关闭")||s.contains("已关闭"))return false;
        if(s.equals("on")||s.equals("true")||s.equals("开启")||s.contains("已开启")||s.contains("已选中"))return true;
        return null;
    }
    public static boolean desired(Boolean observed,boolean expected){return observed!=null&&observed==expected;}
}
