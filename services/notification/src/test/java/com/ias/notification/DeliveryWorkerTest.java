package com.ias.notification;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DeliveryWorkerTest {
    @Test void eachTickIsBoundedAndStopsWhenNoClaimRemains(){
        var calls=new AtomicInteger();
        try(var worker=new DeliveryWorker(()->{calls.incrementAndGet();return true;},3,Duration.ofMillis(100))){
            worker.tick();assertEquals(3,calls.get());assertEquals(3,worker.status().claimed());
        }
        try(var worker=new DeliveryWorker(()->{calls.incrementAndGet();return false;},3,Duration.ofMillis(100))){worker.tick();assertEquals(4,calls.get());}
    }
    @Test void infrastructureFailureDoesNotKillSubsequentPollingOrExposeException(){
        var calls=new AtomicInteger();
        try(var worker=new DeliveryWorker(()->{if(calls.incrementAndGet()==1)throw new Exception("private-text-must-not-be-retained");return false;},1,Duration.ofMillis(100))){
            worker.tick();worker.tick();assertEquals(2,worker.status().attemptedTicks());assertEquals(1,worker.status().failedTicks());assertFalse(worker.status().toString().contains("private-text"));
        }
    }
    @Test void concurrentTickCannotDuplicateInFlightPump()throws Exception{
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
        try(var worker=new DeliveryWorker(()->{calls.incrementAndGet();entered.countDown();assertTrue(release.await(2,TimeUnit.SECONDS));return false;},1,Duration.ofMillis(100));var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var first=pool.submit(worker::tick);assertTrue(entered.await(2,TimeUnit.SECONDS));
            try{worker.tick();assertEquals(1,calls.get());}finally{release.countDown();}first.get();
        }
    }
    @Test void scheduledLifecycleStartsOnceAndClosedWorkerCannotRestart()throws Exception{
        var entered=new CountDownLatch(1);var worker=new DeliveryWorker(()->{entered.countDown();return false;},1,Duration.ofMillis(100));
        try{worker.start();worker.start();assertTrue(entered.await(2,TimeUnit.SECONDS));}finally{worker.close();}
        var before=worker.status();worker.tick();assertEquals(before,worker.status());assertThrows(IllegalStateException.class,worker::start);worker.close();
    }
}
