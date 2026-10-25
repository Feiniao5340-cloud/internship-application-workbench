package com.getjobs.worker.manager;

import com.google.gson.JsonObject;
import com.microsoft.playwright.*;

/** Creates automation detail tabs without Chromium's default foreground activation. */
public final class BackgroundBrowserPages {
    private BackgroundBrowserPages() {}
    public static Page create(BrowserContext context) {
        if(context.pages().isEmpty())throw new IllegalStateException("后台标签页缺少已核对的浏览器上下文");
        var reference=context.newCDPSession(context.pages().getFirst());
        JsonObject target;
        try{target=reference.send("Target.getTargetInfo").getAsJsonObject("targetInfo");}finally{reference.detach();}
        var parameters=new JsonObject();parameters.addProperty("url","about:blank");parameters.addProperty("background",true);parameters.addProperty("newWindow",false);
        if(target.has("browserContextId"))parameters.addProperty("browserContextId",target.get("browserContextId").getAsString());
        var session=context.browser().newBrowserCDPSession();
        try{return context.waitForPage(new BrowserContext.WaitForPageOptions().setTimeout(10000),()->session.send("Target.createTarget",parameters));}
        finally{session.detach();}
    }
}
