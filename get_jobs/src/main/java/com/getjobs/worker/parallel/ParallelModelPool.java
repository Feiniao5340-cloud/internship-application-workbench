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
    @Override public Map<String,Object> status(){var result=new java.util.LinkedHashMap<>(delegate.status());result.put("active",active.get());result.put("waiting",waiting.get());result.put("maxConcurrent",concurrency);return result;}
}
