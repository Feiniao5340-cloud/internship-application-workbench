package com.getjobs.worker.manager;

import com.microsoft.playwright.BrowserContext;
import com.sun.jna.*;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.util.*;
import java.util.concurrent.*;

/** Controls only windows belonging to the exact browser PID obtained from our own CDP connection. */
public final class BackgroundChromeWindows implements AutoCloseable {
    interface Windows {
        Map<Long,Integer> windows();
        long foreground();
        boolean visible(long handle);
        void hide(long handle);
        void show(long handle);
        void foreground(long handle);
    }
    interface User32 extends StdCallLibrary {
        interface EnumProc extends StdCallCallback { boolean invoke(Pointer window,Pointer data); }
        boolean EnumWindows(EnumProc callback,Pointer data);
        int GetWindowThreadProcessId(Pointer window,IntByReference pid);
        boolean IsWindowVisible(Pointer window);
        Pointer GetForegroundWindow();
        boolean SetForegroundWindow(Pointer window);
        boolean ShowWindowAsync(Pointer window,int command);
        boolean SetWindowPos(Pointer window,Pointer insertAfter,int x,int y,int width,int height,int flags);
    }
    static class NativeWindows implements Windows {
        final User32 api=Native.load("user32",User32.class);
        public Map<Long,Integer> windows(){Map<Long,Integer> all=new LinkedHashMap<>();
            if(!api.EnumWindows((window,data)->{var pid=new IntByReference();if(api.GetWindowThreadProcessId(window,pid)!=0)all.put(Pointer.nativeValue(window),pid.getValue());return true;},null))throw new IllegalStateException("无法检查后台浏览器窗口");
            return all;
        }
        public long foreground(){return Pointer.nativeValue(api.GetForegroundWindow());}
        public boolean visible(long h){return api.IsWindowVisible(new Pointer(h));}
        public void hide(long h){api.ShowWindowAsync(new Pointer(h),0);}
        public void show(long h){api.SetWindowPos(new Pointer(h),null,80,80,1440,1000,0x0004|0x0010);api.ShowWindowAsync(new Pointer(h),4);}
        public void foreground(long h){if(h!=0)api.SetForegroundWindow(new Pointer(h));}
    }
    private final int browserPid;
    private static final Set<Integer> OWNED_BROWSERS=ConcurrentHashMap.newKeySet();
    private final Windows windows;
    private final java.util.function.BooleanSupplier ownerAlive;
    private final ScheduledExecutorService monitor=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"background-chrome-windows");t.setDaemon(true);return t;});
    private long previousForeground;
    private final Set<Long> hiddenByUs=new HashSet<>();
    private boolean closed;
    private volatile boolean failed;
    BackgroundChromeWindows(int pid,Windows windows,long foreground){this(pid,windows,foreground,()->true);}
    BackgroundChromeWindows(int pid,Windows windows,long foreground,java.util.function.BooleanSupplier alive){if(pid<=0)throw new IllegalArgumentException("无效浏览器进程");browserPid=pid;this.windows=windows;previousForeground=foreground;ownerAlive=alive;OWNED_BROWSERS.add(pid);}
    public static long currentForeground(){return new NativeWindows().foreground();}
    public static BackgroundChromeWindows start(BrowserContext context,long foreground) {
        if(!System.getProperty("os.name").startsWith("Windows"))throw new IllegalStateException("普通Chrome后台窗口控制目前仅支持Windows");
        var browser=context.browser();if(browser==null)throw new IllegalStateException("无法核实当前Chrome身份，未启用后台窗口控制");
        var session=browser.newBrowserCDPSession();int pid=0;
        try {
            var data=new org.json.JSONObject(session.send("SystemInfo.getProcessInfo").toString());
            for(Object item:data.getJSONArray("processInfo")){var process=(org.json.JSONObject)item;if("browser".equals(process.optString("type")))pid=process.getInt("id");}
        }finally{session.detach();}
        var process=ProcessHandle.of(pid).orElseThrow(()->new IllegalStateException("浏览器进程已结束"));
        var owner=new BackgroundChromeWindows(pid,new NativeWindows(),foreground,process::isAlive);owner.tick();owner.ensureHealthy();
        owner.monitor.scheduleWithFixedDelay(owner::tick,150,150,TimeUnit.MILLISECONDS);return owner;
    }
    synchronized void tick() {
        if(closed)return;
        if(!ownerAlive.getAsBoolean()){failed=true;close();return;}
        try {
            Map<Long,Integer> all=windows.windows();long active=windows.foreground();
            if(active!=0&&all.containsKey(active)&&!OWNED_BROWSERS.contains(all.get(active)))previousForeground=active;
            for(var entry:all.entrySet())if(entry.getValue()==browserPid&&windows.visible(entry.getKey())){hiddenByUs.add(entry.getKey());windows.hide(entry.getKey());}
            if(all.getOrDefault(windows.foreground(),0)==browserPid&&previousForeground!=0&&all.containsKey(previousForeground)&&!OWNED_BROWSERS.contains(all.get(previousForeground)))windows.foreground(previousForeground);
        }catch(RuntimeException e){failed=true;}
    }
    public void ensureHealthy(){if(failed)throw new IllegalStateException("后台浏览器窗口控制失败，已停止自动操作，请检查工作台");}
    public synchronized void showAndClose(){close();if(!ownerAlive.getAsBoolean())return;for(var entry:windows.windows().entrySet())if(entry.getValue()==browserPid&&hiddenByUs.contains(entry.getKey()))windows.show(entry.getKey());}
    @Override public synchronized void close(){closed=true;OWNED_BROWSERS.remove(browserPid);monitor.shutdownNow();}
}
