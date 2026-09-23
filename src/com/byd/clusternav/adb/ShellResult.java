package com.byd.clusternav.adb;

/** Shell v1 needs an explicit exit marker: stream close alone is not success. */
public final class ShellResult {
    public final boolean success;
    public final int exitCode;
    public final String output;
    public final String error;
    public ShellResult(int code,String output,String error) {
        exitCode=code; this.output=output; this.error=error; success=code==0 && error==null;
    }
    public static ShellResult parse(String raw,String marker) {
        String normalized=raw.replace("\r\n","\n");
        int i=normalized.lastIndexOf("\n"+marker);
        if(i<0) return new ShellResult(-1,raw,"命令未返回退出码，可能超时或连接中断");
        String tail=normalized.substring(i+1+marker.length()).trim();
        try {
            int code=Integer.parseInt(tail);
            if(code<0 || code>255) throw new NumberFormatException();
            return new ShellResult(code,normalized.substring(0,i),code==0?null:"shell exit="+code);
        } catch(NumberFormatException e) { return new ShellResult(-1,raw,"非法退出码"); }
    }
}
