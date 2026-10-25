package com.getjobs.worker.parallel;

import com.getjobs.worker.boss.InternshipModel;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ParallelModelPoolTest {
    @Test void failedSemanticCheckCannotBeOverwrittenByASuccessfulConnectionOrClassification() {
        AtomicBoolean reject=new AtomicBoolean(true);
        InternshipModel fake=new InternshipModel(null){
            public String classify(String prompt,java.util.function.BooleanSupplier stop){
                boolean legal=prompt.contains("招募仓库作业实习生");
                return new org.json.JSONObject().put("decision",legal?"skip":reject.get()?"review":"apply").put("category",legal?"仓库作业":"库存数据分析")
                        .put("reason",legal?"装卸搬运执行":"用户研究与PRD匹配").put("confidence",0.98).put("matchScore",legal?0:98)
                        .put("jdEvidence",new org.json.JSONArray().put("jd:0001")).put("resumeEvidence",new org.json.JSONArray().put("resume:0001"))
                        .put("unknowns",new org.json.JSONArray()).put("constraintViolations",new org.json.JSONArray()).toString();
            }
            public Map<String,Object> status(){return Map.of("ready",true,"message","模型连接正常");}
        };
        var pool=new ParallelModelPool(fake,2);assertEquals(false,pool.check(()->false).get("passed"));
        assertEquals(false,pool.status().get("ready"));assertEquals(true,pool.status().get("connectionReady"));
        reject.set(false);pool.classify("fixture",()->false);assertEquals(false,pool.status().get("ready"));
        assertEquals(true,pool.check(()->false).get("passed"));assertEquals(true,pool.status().get("ready"));
    }
    @Test void concurrentPlatformsCannotExceedTwoModelCalls() throws Exception {
        AtomicInteger active=new AtomicInteger(),maximum=new AtomicInteger();CountDownLatch entered=new CountDownLatch(2),release=new CountDownLatch(1);
        InternshipModel fake=new InternshipModel(null){public String classify(String p,java.util.function.BooleanSupplier stop){int n=active.incrementAndGet();maximum.accumulateAndGet(n,Math::max);entered.countDown();try{release.await();return "{}";}catch(InterruptedException e){throw new IllegalStateException(e);}finally{active.decrementAndGet();}}public Map<String,Object> status(){return Map.of("ready",true);}};
        var pool=new ParallelModelPool(fake,2);var executor=Executors.newFixedThreadPool(5);
        try{List<Future<String>> results=new ArrayList<>();for(int i=0;i<5;i++)results.add(executor.submit(()->pool.classify("fixture",()->false)));
            assertTrue(entered.await(2,TimeUnit.SECONDS));assertEquals(2,maximum.get());release.countDown();for(var r:results)assertEquals("{}",r.get(3,TimeUnit.SECONDS));assertEquals(0,pool.status().get("waiting"));
        }finally{release.countDown();executor.shutdownNow();}
    }
    @Test void waitingCancellationNeverCallsTheModelOrLeaksPermit() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();
        InternshipModel fake=new InternshipModel(null){public String classify(String p,java.util.function.BooleanSupplier stop){calls.incrementAndGet();entered.countDown();try{release.await();return "{}";}catch(InterruptedException e){throw new IllegalStateException(e);}}public Map<String,Object> status(){return Map.of();}};
        var pool=new ParallelModelPool(fake,1);var executor=Executors.newSingleThreadExecutor();
        try{var first=executor.submit(()->pool.classify("first",()->false));assertTrue(entered.await(2,TimeUnit.SECONDS));assertThrows(IllegalStateException.class,()->pool.classify("cancelled",()->true));release.countDown();first.get();assertEquals(1,calls.get());assertEquals(0,pool.status().get("waiting"));}
        finally{release.countDown();executor.shutdownNow();}
    }
}
