package com.ias.notification;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Owner-process polling. Durable leases, retries and dead letters remain in JdbcDelivery. */
public final class DeliveryWorker implements AutoCloseable {
    @FunctionalInterface public interface Pump { boolean one() throws Exception; }
    public record Status(long attemptedTicks, long claimed, long failedTicks, boolean running) {}
    private final Pump pump;
    private final int batch;
    private final Duration interval;
    private final AtomicBoolean running=new AtomicBoolean();
    private final AtomicBoolean closed=new AtomicBoolean();
    private final AtomicLong ticks=new AtomicLong(),claimed=new AtomicLong(),failures=new AtomicLong();
    private ScheduledExecutorService executor;

    public DeliveryWorker(JdbcDelivery delivery, JdbcDelivery.Transport transport, int batch, Duration interval) {
        this(()->delivery.pumpOne(transport),batch,interval);
        Objects.requireNonNull(delivery);Objects.requireNonNull(transport);
    }
    public DeliveryWorker(Pump pump,int batch,Duration interval) {
        this.pump=Objects.requireNonNull(pump);this.interval=Objects.requireNonNull(interval);
        if(batch<1||batch>100||interval.toMillis()<100||interval.compareTo(Duration.ofMinutes(1))>0)
            throw new IllegalArgumentException("INVALID_WORKER_POLICY");
        this.batch=batch;
    }
    public synchronized void start() {
        if(closed.get())throw new IllegalStateException("WORKER_CLOSED");
        if(executor!=null)return;
        executor=Executors.newSingleThreadScheduledExecutor(r->{var thread=new Thread(r,"owner-outbox-delivery");thread.setDaemon(true);return thread;});
        executor.scheduleWithFixedDelay(this::tick,0,interval.toMillis(),TimeUnit.MILLISECONDS);
    }
    /** Public for lifecycle probes; overlapping invocations do not share connections or transport calls. */
    public void tick() {
        if(closed.get()||!running.compareAndSet(false,true))return;
        try {
            ticks.incrementAndGet();
            for(int n=0;n<batch&&!closed.get()&&!Thread.currentThread().isInterrupted();n++){
                if(!pump.one())break;
                claimed.incrementAndGet();
            }
        } catch(Exception failure) {
            // Retain only aggregate health. JDBC/HTTP exception messages may contain private configuration.
            failures.incrementAndGet();
            if(failure instanceof InterruptedException)Thread.currentThread().interrupt();
        } finally {running.set(false);}
    }
    public Status status(){return new Status(ticks.get(),claimed.get(),failures.get(),running.get());}
    @Override public synchronized void close() {
        if(!closed.compareAndSet(false,true))return;
        if(executor!=null){executor.shutdownNow();try{executor.awaitTermination(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    }
}
