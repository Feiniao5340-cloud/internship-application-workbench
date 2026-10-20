package com.getjobs.worker.boss;

import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** Shared orchestration for the real browser and local simulated ports. */
public final class InternshipDelivery {
    public interface Port {
        boolean targetMatches();
        boolean openChat();
        boolean sendAndConfirmText(String text);
        default boolean confirmExistingText(String text) { return false; }
        default boolean hasExistingImage() { return false; }
        default void allowPreviousDayMessages(boolean allow) {}
        boolean sendAndConfirmImage();
        void returnToList();
    }
    public static boolean send(InternshipStore store, String id, JSONObject entry, String greeting,
                               BooleanSupplier stopped, Port browser) {
        JSONObject prior=store.get(id);
        boolean imageOnly=prior!=null && prior.optString("status").equals("ready_image");
        if (store.blocksRetry(prior)) return false;
        browser.allowPreviousDayMessages(!imageOnly&&store.completedBeforeToday(prior));
        try {
            if (stopped.getAsBoolean() || !browser.targetMatches()) return fail(store,id,entry,"发送前岗位或登录状态核对未通过");
            entry.put("status", "chat_attempted"); store.save(id, entry);
            if (stopped.getAsBoolean() || !browser.openChat() || !browser.targetMatches()) return fail(store,id,entry,"聊天对象核对未通过，未继续发送文字或图片；请核对真实会话");
            if (stopped.getAsBoolean()) return fail(store,id,entry,"已暂停，未继续发送");
            if (imageOnly) {
                if (!browser.confirmExistingText(greeting)) return fail(store,id,entry,"未核实已有招呼语，未补发图片");
            } else {
                entry.put("status", "text_attempted"); store.save(id, entry);
                if (!browser.sendAndConfirmText(greeting)) return fail(store,id,entry,"招呼语未完整确认，未继续发送图片");
            }
            entry.put("status", "text_confirmed").put("textConfirmed", true); store.save(id, entry);
            if (stopped.getAsBoolean() || !browser.targetMatches()) return fail(store,id,entry,"文字已确认，发送图片前登录或聊天对象核对未通过");
            if(browser.hasExistingImage())return fail(store,id,entry,"当前会话已有本人发送的图片，未重复上传；请核对后更新发送状态");
            entry.put("status", "image_attempted"); store.save(id, entry);
            if (!browser.sendAndConfirmImage()) return fail(store,id,entry,"图片上传或送达未完整确认，请核对会话后仅补发图片");
            entry.remove("lastDeliveryIssue");
            entry.put("status", "complete").put("imageConfirmed", true); store.save(id, entry);
            return true;
        } catch(RuntimeException error) {
            fail(store,id,entry,"页面加载或发送操作中断（"+entry.optString("status","尚未发送")+"），请核对真实会话；不要重复发送招呼语");
            throw error;
        } finally {
            // Ambiguous/partial attempts remain locked until real-chat reconciliation.
            browser.returnToList();
        }
    }
    private static boolean fail(InternshipStore store,String id,JSONObject entry,String message) {
        entry.put("lastDeliveryIssue",message);if(entry.has("status"))store.save(id,entry);return false;
    }
}
