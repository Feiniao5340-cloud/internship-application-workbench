package com.getjobs.worker.boss;

import org.json.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class InternshipFlowTest {
    @TempDir Path directory;
    JSONObject profile() { return InternshipPolicy.defaults().put("confirmed",true).put("resumeText","参与库存分析工具需求分析并编写PRD。")
            .put("targetDirections","库存数据分析实习").put("excludedDirections","装卸搬运执行"); }
    JSONObject job() { return new JSONObject().put("title","库存数据分析实习生").put("company","测试公司")
            .put("city","示例甲城").put("salary","120-240元/天").put("jd","负责库存分析工具需求分析，编写PRD。服务仓储团队。实习生。 "); }
    JSONObject answer(String decision) { return new JSONObject().put("decision",decision).put("category","库存数据分析")
            .put("confidence",0.95).put("matchScore",85).put("reason","职责为产品需求分析，与简历事实匹配")
            .put("jdEvidence",new JSONArray().put("需求分析")).put("resumeEvidence",new JSONArray().put("编写PRD"))
            .put("unknowns",new JSONArray()).put("constraintViolations",new JSONArray()); }
    @Test void internshipAndLegalWordsDoNotBecomeAutomaticExclusions() {
        assertEquals("apply",InternshipPolicy.evaluate(profile(),job(),answer("apply").toString()).getString("decision"));
        assertTrue(InternshipPolicy.prompt(profile(),job()).contains("实习、助理、管培生不是默认排除词"));
    }
    @Test void modelRejectionCannotBeOverriddenByHighScore() {
        assertEquals("skip",InternshipPolicy.evaluate(profile(),job(),answer("skip").toString()).getString("decision"));
    }
    @Test void mixedDutiesStayForReview() {
        assertEquals("review",InternshipPolicy.evaluate(profile(),job(),answer("review").toString()).getString("decision"));
    }
    @Test void excludedEnglishWorkingLanguageCannotBeAutoConfirmedEvenWhenModelReviewsOrApplies() {
        var c=profile().put("excludedDirections","不接受必须以英文作为工作语言的职位；");
        var j=job().put("jd",job().getString("jd")+"\n6. 英语可以作为工作语言，能够阅读海外产品资料及用户反馈。");
        assertEquals("skip",InternshipPolicy.evaluate(c,j,answer("review").toString()).getString("decision"));
        assertFalse(InternshipPolicy.deliveryEligible(c,new JSONObject().put("job",j).put("result",answer("apply"))));
        var store=new InternshipStore(directory);store.saveSettings(c);store.save("english",reviewEntry(c,j));
        assertThrows(IllegalArgumentException.class,()->store.confirmReview("english","预先授权","scheduled-user-authorization"));
        for(String requirement:List.of("英语阅读能力良好。","无需英语作为工作语言。","加分项：英语可作为工作语言。")) {
            assertEquals("apply",InternshipPolicy.evaluate(c,job().put("jd",job().getString("jd")+requirement),answer("apply").toString()).getString("decision"),requirement);
        }
        assertEquals("apply",InternshipPolicy.evaluate(profile(),j,answer("apply").toString()).getString("decision"));
    }
    JSONObject reviewEntry(JSONObject c,JSONObject j) {
        return new JSONObject().put("status","screened").put("job",j).put("result",answer("review"))
                .put("settingsHash",InternshipPolicy.fingerprint(c));
    }
    @Test void scheduledAuthorizationIsTruthfulAndStillCannotOverrideHardExclusions() {
        var store=new InternshipStore(directory);var c=profile().put("minDailySalary",120);store.saveSettings(c);
        store.save("scheduled",reviewEntry(c,job()));
        store.confirmReview("scheduled","用户预先授权定时投递，未逐条人工审核","scheduled-user-authorization");
        store.approve("scheduled");assertTrue(InternshipPolicy.deliveryEligible(c,store.get("scheduled")));
        assertEquals("scheduled-user-authorization",store.get("scheduled").getJSONObject("humanReview").getString("origin"));
        store.save("low",reviewEntry(c,job().put("salary","90-120元/天")));
        assertThrows(IllegalArgumentException.class,()->store.confirmReview("low","预先授权","scheduled-user-authorization"));
    }
    @Test void humanCanConfirmReviewWithoutChangingModelDecision() {
        var store=new InternshipStore(directory);var c=profile();store.saveSettings(c);store.save("r",reviewEntry(c,job()));
        assertThrows(IllegalArgumentException.class,()->store.approve("r"));
        assertThrows(IllegalArgumentException.class,()->store.confirmReview("r",""));
        store.confirmReview("r","已核实主要职责为产品，满足已确认的实习条件");store.approve("r");
        var saved=new InternshipStore(directory).get("r");
        assertEquals("review",saved.getJSONObject("result").getString("decision"));
        assertTrue(InternshipPolicy.manuallyConfirmed(c,saved));assertTrue(saved.getBoolean("approved"));
        assertTrue(InternshipPolicy.deliveryEligible(c,saved));
    }
    @Test void humanReviewNeverOverridesHardSalaryOrCityRules() {
        var store=new InternshipStore(directory);var c=profile().put("minDailySalary",120).put("cities",new JSONArray().put("示例甲城"));store.saveSettings(c);
        for(String salary:List.of("90-120元/天","110-180元/天","10-15K","面议")) {
            store.save("r",reviewEntry(c,job().put("salary",salary)));
            assertThrows(IllegalArgumentException.class,()->store.confirmReview("r","本人确认"),salary);
        }
        store.save("r",reviewEntry(c,job().put("city","示例乙城")));
        assertThrows(IllegalArgumentException.class,()->store.confirmReview("r","本人确认"));
    }
    @Test void changedJobOrProfileInvalidatesHumanConfirmation() {
        var store=new InternshipStore(directory);var c=profile();store.saveSettings(c);store.save("r",reviewEntry(c,job()));
        store.confirmReview("r","已核实疑问");var saved=store.get("r");
        assertFalse(InternshipPolicy.manuallyConfirmed(new JSONObject(c.toString()).put("resumeText","另一份简历"),saved));
        saved.getJSONObject("job").put("jd","职责发生变化");
        assertFalse(InternshipPolicy.manuallyConfirmed(c,saved));
    }
    @Test void skippedOrStaleResultsCannotBeManuallyConfirmed() {
        var store=new InternshipStore(directory);var c=profile();store.saveSettings(c);
        store.save("r",reviewEntry(c,job()).put("result",answer("skip")));
        assertThrows(IllegalArgumentException.class,()->store.confirmReview("r","本人确认"));
        store.save("r",reviewEntry(c,job()).put("settingsHash","old"));
        assertThrows(IllegalArgumentException.class,()->store.confirmReview("r","本人确认"));
    }
    @Test void missingUnconfirmedResumeCannotSend() {
        assertEquals("review",InternshipPolicy.evaluate(profile().put("confirmed",false),job(),answer("apply").toString()).getString("decision"));
    }
    @Test void absentJdCannotSend() { assertEquals("review",InternshipPolicy.evaluate(profile(),job().put("jd",""),answer("apply").toString()).getString("decision")); }
    @Test void modelFailureCannotSend() { assertEquals("review",InternshipPolicy.evaluate(profile(),job(),"not JSON").getString("decision")); }
    @Test void fabricatedJdEvidenceCannotSend() { assertEquals("review",InternshipPolicy.evaluate(profile(),job(),answer("apply").put("jdEvidence",new JSONArray().put("不存在的职责")).toString()).getString("decision")); }
    @Test void fabricatedResumeEvidenceCannotSend() { assertEquals("review",InternshipPolicy.evaluate(profile(),job(),answer("apply").put("resumeEvidence",new JSONArray().put("十年工作经验")).toString()).getString("decision")); }
    @Test void unknownRequirementsCannotSend() { assertEquals("review",InternshipPolicy.evaluate(profile(),job(),answer("apply").put("unknowns",new JSONArray().put("出勤要求未披露")).toString()).getString("decision")); }
    @Test void violatingRequirementsCannotSend() { assertEquals("review",InternshipPolicy.evaluate(profile(),job(),answer("apply").put("constraintViolations",new JSONArray().put("出勤不符")).toString()).getString("decision")); }
    @Test void lowConfidenceCannotSend() { assertEquals("review",InternshipPolicy.evaluate(profile(),job(),answer("apply").put("confidence",0.6).toString()).getString("decision")); }
    @Test void cityMismatchSkipsAndUnknownCityReviews() {
        var c=profile().put("cities",new JSONArray().put("示例乙城"));
        assertEquals("skip",InternshipPolicy.evaluate(c,job(),answer("apply").toString()).getString("decision"));
        assertEquals("review",InternshipPolicy.evaluate(c,job().put("city",""),answer("apply").toString()).getString("decision"));
    }
    @Test void blacklistPrecedesModel() { assertEquals("skip",InternshipPolicy.evaluate(profile().put("companyBlacklist",new JSONArray().put("测试")),job(),answer("apply").toString()).getString("decision")); }
    @Test void approvalIsBoundToProfileAndRuntimeModeCanChange() {
        var store=new InternshipStore(directory); var c=profile(); store.saveSettings(c);
        store.save("a",new JSONObject().put("status","screened").put("settingsHash",InternshipPolicy.fingerprint(c)).put("result",answer("apply")));
        store.approve("a"); assertTrue(store.get("a").getBoolean("approved"));
        store.saveSettings(c.put("mode","review")); store.approve("a");
        store.saveSettings(c.put("resumeText","另一份简历")); assertThrows(IllegalArgumentException.class,()->store.approve("a"));
    }
    class Port implements InternshipDelivery.Port {
        List<String> calls=new ArrayList<>(); boolean target=true, text=true, image=true; AtomicBoolean stop;
        public boolean targetMatches(){return target;}
        public boolean openChat(){calls.add("chat");return true;}
        public boolean sendAndConfirmText(String s){calls.add("text");if(stop!=null)stop.set(true);return text;}
        public boolean sendAndConfirmImage(){calls.add("image");return image;}
        public boolean confirmExistingText(String s){calls.add("existing-text");return text;}
        public void returnToList(){calls.add("return");}
    }
    @Test void successfulDeliveryIsTextThenImageThenReturn() {
        var store=new InternshipStore(directory); var port=new Port();
        assertTrue(InternshipDelivery.send(store,"a",new JSONObject(),"hi",()->false,port));
        assertEquals(List.of("chat","text","image","return"),port.calls);
        assertEquals("complete",store.get("a").getString("status"));
    }
    @Test void completedDeliveryCannotRepeatAcrossRoundsOrRestartEvenWithChangedGreeting() {
        var store=new InternshipStore(directory);var first=new Port();
        assertTrue(InternshipDelivery.send(store,"same-job",new JSONObject(),"原招呼语",()->false,first));
        for(String source:List.of("recommend","search","recommend")) {
            var reopened=new InternshipStore(directory);var next=new Port();
            assertFalse(InternshipDelivery.send(reopened,"same-job",new JSONObject().put("source",source),"修改后的招呼语",()->false,next));
            assertTrue(next.calls.isEmpty(),"完成投递的岗位不应打开沟通、发文字或发图片");
            assertEquals("complete",reopened.get("same-job").getString("status"));
        }
    }
    @Test void failedTextNeverSendsImageAndSurvivesRestart() {
        var store=new InternshipStore(directory);var port=new Port();port.text=false;
        assertFalse(InternshipDelivery.send(store,"a",new JSONObject(),"hi",()->false,port));
        assertFalse(port.calls.contains("image"));var resumed=new InternshipStore(directory);var retry=new Port();
        assertFalse(InternshipDelivery.send(resumed,"a",new JSONObject(),"hi",()->false,retry));assertTrue(retry.calls.isEmpty());
    }
    @Test void beijingMidnightReleasesCompletedJobButSameDayAndPartialRemainLocked() {
        var store=new InternshipStore(directory);
        store.clock=java.time.Clock.fixed(java.time.Instant.parse("2026-10-18T15:59:59Z"),java.time.ZoneOffset.UTC);
        assertTrue(InternshipDelivery.send(store,"daily",new JSONObject(),"hi",()->false,new Port()));
        assertTrue(store.blocksRetry(store.get("daily")));
        store.clock=java.time.Clock.fixed(java.time.Instant.parse("2026-10-18T16:00:00Z"),java.time.ZoneOffset.UTC);
        assertFalse(store.blocksRetry(store.get("daily")),"北京时间零点解除当天去重，UTC仍为同一天");
        var tomorrow=new Port();assertTrue(InternshipDelivery.send(store,"daily",new JSONObject(),"hi",()->false,tomorrow));
        assertEquals(List.of("chat","text","image","return"),tomorrow.calls);
        assertTrue(store.blocksRetry(store.get("daily")),"第二天完成后，该天再次锁定");
        store.save("partial",new JSONObject().put("status","image_attempted"));
        store.clock=java.time.Clock.offset(store.clock,java.time.Duration.ofDays(1));
        assertTrue(store.blocksRetry(store.get("partial")),"跨天不能解除未确认发送的保护");
    }
    @Test void failedImageIsNotCompleteAndDoesNotRepeatText() {
        var store=new InternshipStore(directory);var port=new Port();port.image=false;
        assertFalse(InternshipDelivery.send(store,"a",new JSONObject(),"hi",()->false,port));
        assertEquals("image_attempted",store.get("a").getString("status"));assertTrue(store.get("a").getBoolean("textConfirmed"));
        assertFalse(InternshipDelivery.send(store,"a",new JSONObject(),"hi",()->false,new Port()));
    }
    @Test void stopBetweenTextAndImageBlocksImage() {
        var store=new InternshipStore(directory);var port=new Port();var stop=new AtomicBoolean();port.stop=stop;
        assertFalse(InternshipDelivery.send(store,"a",new JSONObject(),"hi",stop::get,port));assertFalse(port.calls.contains("image"));
        assertEquals("text_confirmed",store.get("a").getString("status"));
    }
    @Test void wrongTargetDoesNotOpenChat() {
        var port=new Port();port.target=false;
        assertFalse(InternshipDelivery.send(new InternshipStore(directory),"a",new JSONObject(),"hi",()->false,port));
        assertEquals(List.of("return"),port.calls);
    }
    @Test void reconciledImageRetryNeverResendsText() {
        var store=new InternshipStore(directory);var c=profile();store.saveSettings(c);
        var entry=new JSONObject().put("status","image_attempted").put("result",answer("apply"))
                .put("settingsHash",InternshipPolicy.fingerprint(c));store.save("a",entry);
        assertThrows(IllegalArgumentException.class,()->store.reconcile("a","image_only","",true));
        store.reconcile("a","image_only","已核对会话：文字已发，图片未发",true);store.approve("a");
        var port=new Port();assertTrue(InternshipDelivery.send(store,"a",store.get("a"),"hi",()->false,port));
        assertEquals(List.of("chat","existing-text","image","return"),port.calls);
        assertTrue(store.get("a").getJSONArray("history").length()>2);
    }
    @Test void imageRetryWithoutExistingTextRemainsBlocked() {
        var store=new InternshipStore(directory);store.save("a",new JSONObject().put("status","ready_image"));
        var port=new Port();port.text=false;
        assertFalse(InternshipDelivery.send(store,"a",store.get("a"),"hi",()->false,port));
        assertFalse(port.calls.contains("image"));assertFalse(port.calls.contains("text"));
    }
    @Test void existingOwnImagePreventsDuplicateUpload() {
        var store=new InternshipStore(directory);store.save("a",new JSONObject().put("status","ready_image"));
        var port=new Port(){public boolean hasExistingImage(){return true;}};
        assertFalse(InternshipDelivery.send(store,"a",store.get("a"),"hi",()->false,port));
        assertFalse(port.calls.contains("image"));assertFalse(port.calls.contains("text"));
        assertTrue(store.get("a").getString("lastDeliveryIssue").contains("未重复上传"));
    }
    @Test void corruptStateFailsClosed() {
        assertDoesNotThrow(()->java.nio.file.Files.writeString(directory.resolve("records.json"),"corrupt"));
        assertThrows(IllegalStateException.class,()->new InternshipStore(directory).records());
    }
    @Test void readOnlyGateRejectsAutoModeBeforeBrowserInitialization() {
        var store=new InternshipStore(directory);store.saveSettings(profile().put("mode","auto"));
        var runner=new InternshipRunner(store,null,null,null,null,null);
        org.springframework.test.util.ReflectionTestUtils.setField(runner,"liveEnabled",true);
        assertThrows(IllegalStateException.class,runner::start);assertFalse(runner.isRunning());
        assertEquals(false,runner.status().get("sendingEnabled"));
    }
    @Test void modelDisabledLeavesJdPendingWithoutCallingProvider() {
        var runner=new InternshipRunner(new InternshipStore(directory),null,null,null,null,null);
        assertTrue(runner.analyze(profile(),job()).getBoolean("pendingModel"));
    }
    @Test void monthlyAndUnknownPayCannotApply() {
        assertEquals("skip",InternshipPolicy.evaluate(profile(),job().put("salary","8-12K·13薪"),answer("apply").toString()).getString("decision"));
        assertEquals("skip",InternshipPolicy.evaluate(profile(),job().put("salary","4000元/月"),answer("apply").toString()).getString("decision"));
        assertEquals("review",InternshipPolicy.evaluate(profile(),job().put("salary","面议"),answer("apply").toString()).getString("decision"));
    }
    @Test void dailyMinimumCannotBeOverriddenByModel() {
        var c=profile().put("minDailySalary",120);
        assertEquals("skip",InternshipPolicy.evaluate(c,job().put("salary","80-100元/天"),answer("apply").toString()).getString("decision"));
        for(String salary:java.util.List.of("90-120元/天","110-180元/天","90到120元/天","90～120元/天","119.99-180元/天"))
            assertEquals("skip",InternshipPolicy.evaluate(c,job().put("salary",salary),answer("apply").toString()).getString("decision"),salary);
        assertEquals("apply",InternshipPolicy.evaluate(c,job().put("salary","120-240元/天"),answer("apply").toString()).getString("decision"));
        assertEquals("apply",InternshipPolicy.evaluate(c,job().put("salary","120元/天"),answer("apply").toString()).getString("decision"));
        assertNotEquals(InternshipPolicy.fingerprint(c),InternshipPolicy.fingerprint(profile()));
    }
    @Test void deliveryRejectsChangedJobIdentityAndRequirements() {
        var a=job().put("recruiter","招聘员甲");var b=new JSONObject(a.toString());
        assertTrue(InternshipRunner.sameDescription(a,b.put("jd",a.getString("jd")+"\n")));
        assertFalse(InternshipRunner.sameDescription(a,b.put("recruiter","招聘员乙")));
        assertFalse(InternshipRunner.sameDescription(a,new JSONObject(a.toString()).put("jd","职责已改为货物搬运")));
        assertFalse(InternshipRunner.sameDescription(a,new JSONObject(a.toString()).put("salary","60-90元/天")));
    }
    @Test void singleDeliveryNeedsEnabledSendingAndCurrentApproval() {
        var store=new InternshipStore(directory);store.saveSettings(profile());
        var runner=new InternshipRunner(store,null,null,null,null,null);
        assertThrows(IllegalStateException.class,()->runner.deliver("missing"));
        org.springframework.test.util.ReflectionTestUtils.setField(runner,"liveEnabled",true);
        org.springframework.test.util.ReflectionTestUtils.setField(runner,"sendingEnabled",true);
        assertThrows(IllegalStateException.class,()->runner.deliver("missing"));
        assertFalse(runner.isRunning());
    }
}
