package com.getjobs.worker.boss;
import org.json.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InternshipRankingTest {
    @TempDir Path directory;
    InternshipStore store;InternshipRunner runner;JSONObject settings;String round;
    @BeforeEach void setup() {
        store=new InternshipStore(directory);settings=InternshipPolicy.defaults().put("confirmed",true).put("resumeText","产品需求分析和PRD经历")
                .put("targetDirections","库存分析实习").put("greeting","你好").put("mode","auto").put("minDailySalary",120).put("maxSend",2);
        store.saveSettings(settings);settings.put("intervalSeconds",0);round=store.beginRound(10);
        runner=spy(new InternshipRunner(store,null,null,null,null,null));
        org.springframework.test.util.ReflectionTestUtils.setField(runner,"sendingEnabled",true);
        org.springframework.test.util.ReflectionTestUtils.setField(runner,"scheduledAutoReview",true);
    }
    void candidate(String id,String decision,int score,double confidence,String salary) {
        var job=new JSONObject().put("title","库存分析实习生").put("company","测试公司").put("url","https://www.zhipin.com/job_detail/"+id+".html")
                .put("jd","产品需求分析和PRD，招聘实习生").put("salary",salary).put("city","示例乙城");
        var result=new JSONObject().put("decision",decision).put("matchScore",score).put("confidence",confidence).put("reason","产品经历匹配");
        store.save(id,new JSONObject().put("job",job).put("result",result).put("status","screened").put("approved",false)
                .put("roundId",round).put("settingsHash",InternshipPolicy.fingerprint(settings)));
    }
    @Test void highestFitUsesLimitedSlotsAcrossApplyAndAuthorizedReviewAndIgnoresHardFailures() {
        candidate("early-low","apply",40,0.99,"240元/天");candidate("best","review",95,0.8,"240元/天");
        candidate("second","apply",90,0.95,"240元/天");candidate("blocked","review",99,1,"90-120元/天");candidate("rejected","skip",100,1,"240元/天");
        List<String> sent=new ArrayList<>();
        doAnswer(call->{String id=call.getArgument(1);sent.add(id);var entry=store.get(id).put("status","complete");store.save(id,entry);return true;})
                .when(runner).sendCandidate(any(),anyString(),any(),any());
        runner.rankAndDeliver(settings,round,null);
        assertEquals(List.of("best","second"),sent,"投递限额必须给高匹配候选，而非采集顺序或置信度");
        assertEquals("screened",store.get("early-low").getString("status"));
        assertEquals(5,store.get("rejected").getInt("matchRank"));
        assertEquals("scheduled-user-authorization",store.get("best").getJSONObject("humanReview").getString("origin"));
    }
    @Test void scanOnlyStillRanksAndNeverSends() {
        candidate("low","apply",40,0.99,"240元/天");candidate("high","apply",95,0.9,"240元/天");
        runner.rankAndDeliver(settings.put("mode","scan"),round,null);
        assertEquals(1,store.get("high").getInt("matchRank"));verify(runner,never()).sendCandidate(any(),anyString(),any(),any());
    }
    @Test void partialTopCandidateStopsQueueWithoutTryingLowerCandidates() {
        candidate("low","apply",40,0.99,"240元/天");candidate("high","apply",95,0.9,"240元/天");
        List<String> sent=new ArrayList<>();doAnswer(call->{String id=call.getArgument(1);sent.add(id);store.save(id,store.get(id).put("status","image_attempted"));return false;})
                .when(runner).sendCandidate(any(),anyString(),any(),any());
        runner.rankAndDeliver(settings,round,null);assertEquals(List.of("high"),sent);assertTrue(runner.shouldStop());
    }
    @Test void invalidEvidenceIsModelFailureRatherThanAutoAuthorizedReview() {
        var fixture=new InternshipFlowTest();var c=fixture.profile();var j=fixture.job();
        var invalid=fixture.answer("review").put("jdEvidence",new org.json.JSONArray().put("伪造的非原文引用"));
        assertTrue(InternshipPolicy.evaluate(c,j,invalid.toString()).getBoolean("pendingModel"));
    }
    @Test void missingOrOutOfRangeScoreCannotBecomeAutoConfirmedCandidate() {
        var fixture=new InternshipFlowTest();var c=fixture.profile();var j=fixture.job();
        for(Object value:List.of(-1,101,80.5,"invalid"))assertTrue(InternshipPolicy.evaluate(c,j,fixture.answer("apply").put("matchScore",value).toString()).getBoolean("pendingModel"));
        var missing=fixture.answer("apply");missing.remove("matchScore");assertTrue(InternshipPolicy.evaluate(c,j,missing.toString()).getBoolean("pendingModel"));
        assertTrue(InternshipModel.schema().getJSONArray("required").toList().contains("matchScore"));
    }
}
