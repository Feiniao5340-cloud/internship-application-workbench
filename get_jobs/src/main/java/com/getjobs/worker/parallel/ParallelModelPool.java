package com.getjobs.worker.parallel;

import com.getjobs.worker.boss.InternshipModel;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** A fair, bounded model queue shared by platform workers. */
public class ParallelModelPool extends InternshipModel {
    private final InternshipModel delegate;
    private final Semaphore permits;
    private final int concurrency;
    private final AtomicInteger active=new AtomicInteger(),waiting=new AtomicInteger();
    private volatile Map<String,Object> selfTest=Map.of("state","not_run");
    public ParallelModelPool(InternshipModel delegate,int concurrency) {super(null);if(concurrency<1)throw new IllegalArgumentException("模型并发必须为正数");this.delegate=delegate;this.concurrency=concurrency;this.permits=new Semaphore(concurrency,true);}
    @Override public String classify(String prompt,BooleanSupplier stopped) {
        boolean acquired=false;waiting.incrementAndGet();
        try {
            while(!(acquired=permits.tryAcquire(200,TimeUnit.MILLISECONDS)))if(stopped.getAsBoolean())throw new IllegalStateException("模型排队已取消");
            waiting.decrementAndGet();active.incrementAndGet();if(stopped.getAsBoolean())throw new IllegalStateException("模型判断已取消");
            return delegate.classify(prompt,stopped);
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("模型排队中断",e);}
        finally {if(acquired){active.decrementAndGet();permits.release();}else waiting.decrementAndGet();}
    }
    @Override public synchronized Map<String,Object> check(BooleanSupplier stopped) {
        selfTest=Map.of("state","checking");
        try {
            var result=new java.util.LinkedHashMap<>(super.check(stopped));
            var positive=(Map<?,?>)result.get("sample");var negative=(Map<?,?>)result.get("negativeSample");
            boolean passed="apply".equals(positive.get("decision"))&&"skip".equals(negative.get("decision"));
            selfTest=Map.of("state",passed?"passed":"failed","at",java.time.Instant.now().toString(),
                    "positiveDecision",positive.get("decision"),"positiveReason",positive.get("reason"),
                    "negativeDecision",negative.get("decision"),"negativeReason",negative.get("reason"));
            result.put("passed",passed);result.put("connection",status());return result;
        }catch(RuntimeException e){selfTest=Map.of("state","failed","at",java.time.Instant.now().toString(),"message","模型自检执行失败，请检查模型连接或网络");throw e;}
    }
    @Override public Map<String,Object> status(){var result=new java.util.LinkedHashMap<>(delegate.status());
        result.put("connectionReady",result.getOrDefault("ready",false));var check=selfTest;
        result.put("selfTest",check);if("failed".equals(check.get("state"))){result.put("ready",false);result.put("message","模型自检未通过，请查看正反样例的判断原因");}
        result.put("active",active.get());result.put("waiting",waiting.get());result.put("maxConcurrent",concurrency);return result;}
}
