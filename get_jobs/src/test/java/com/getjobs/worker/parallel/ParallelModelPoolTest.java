package com.getjobs.worker.parallel;

import com.getjobs.worker.boss.InternshipModel;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ParallelModelPoolTest {
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
