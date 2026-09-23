package com.byd.clusternav.core;

import java.lang.reflect.*;

/** Calls the vendor manager's typed API; never guesses Binder transaction numbers. */
public final class ContainerChannel {
    private final Object manager;
    private final Method send;
    public ContainerChannel(Object manager) throws Exception {
        if(manager==null)throw new IllegalStateException("系统未提供 AutoContainer 管理器");
        this.manager=manager;
        send=manager.getClass().getMethod("sendInfo",int.class,int.class,String.class);
    }
    public String signature(){return send.toGenericString();}
    public Object mode(int mode) throws Exception {return send(NavigationRoute.containerCommand(mode));}
    public Object complete() throws Exception {return send(35);}
    private Object send(int command) throws Exception {
        try {return send.invoke(manager,1000,command,"");}
        catch(InvocationTargetException e) {
            Throwable cause=e.getCause();
            if(cause instanceof Exception)throw (Exception)cause;
            throw e;
        }
    }
    public static boolean returnedWithoutRejection(Object result) {
        return !(result instanceof Boolean && !((Boolean)result))
            && !(result instanceof Number && ((Number)result).longValue()<0);
    }
}
