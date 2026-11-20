package com.ias.ai;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class BudgetAdmissionTest {
    BudgetAdmission.Price price(){return new BudgetAdmission.Price("approved-model","CONFIG_V1","PRICE_V1","CNY",new BigDecimal("1"),new BigDecimal("2"));}
    BudgetAdmission.Bound bound(){return BudgetAdmission.bound(price(),100_000,100_000,1);}
    BudgetAdmission.ReferenceLedger ledger(String limit){return new BudgetAdmission.ReferenceLedger("1","2026-10","CNY",new BigDecimal(limit),(t,a,b)->a.equals("7"));}
    @Test void conservativeUpperBoundIncludesAllBillableAttempts(){assertEquals(new BigDecimal("0.900000"),BudgetAdmission.bound(price(),100_000,100_000,3).maximum());}
    @Test void noKnownBoundNoPaidAdmission(){assertThrows(IllegalArgumentException.class,()->BudgetAdmission.bound(price(),0,100,1));assertThrows(IllegalArgumentException.class,()->BudgetAdmission.bound(price(),100,100,0));assertThrows(NullPointerException.class,()->ledger("1").reserve(UUID.randomUUID(),"1","7",null));}
    @Test void unknownAndCancellationRetainFullReservation(){var l=ledger("0.3");UUID id=UUID.randomUUID();l.reserve(id,"1","7",bound());l.unknown(id,"1","7");assertThrows(IllegalArgumentException.class,()->l.releaseVerifiedNoCharge(id,"1","7",false));assertEquals(0,l.held().compareTo(new BigDecimal("0.3")));assertThrows(IllegalArgumentException.class,()->l.reserve(UUID.randomUUID(),"1","7",bound()));}
    @Test void committedOutcomeSettlesOnceAndReleasesUnusedBound(){var l=ledger("1");UUID id=UUID.randomUUID();l.reserve(id,"1","7",bound());l.settle(id,"1","7",new BigDecimal("0.10"));l.settle(id,"1","7",new BigDecimal("0.100"));assertEquals(0,l.held().compareTo(BigDecimal.ZERO));assertEquals(0,l.spent().compareTo(new BigDecimal("0.1")));assertThrows(IllegalArgumentException.class,()->l.settle(id,"1","7",new BigDecimal("0.2")));}
    @Test void sameOperationKeepsSameParametersAndCurrentScope(){var allowed=new AtomicBoolean(true);var l=new BudgetAdmission.ReferenceLedger("1","month","CNY",BigDecimal.ONE,(t,a,b)->allowed.get());UUID id=UUID.randomUUID();l.reserve(id,"1","7",bound());assertThrows(IllegalArgumentException.class,()->l.reserve(id,"1","7",BudgetAdmission.bound(price(),200_000,100_000,1)));allowed.set(false);assertThrows(IllegalArgumentException.class,()->l.reserve(id,"1","7",bound()));assertThrows(IllegalArgumentException.class,()->l.settle(id,"1","7",BigDecimal.ZERO));}
    @Test void verifiedNoChargeReleasesOnceButTimeoutDoesNot(){var l=ledger("0.3");UUID id=UUID.randomUUID();l.reserve(id,"1","7",bound());l.unknown(id,"1","7");l.releaseVerifiedNoCharge(id,"1","7",true);l.releaseVerifiedNoCharge(id,"1","7",true);assertEquals(0,l.held().compareTo(BigDecimal.ZERO));assertThrows(IllegalArgumentException.class,()->l.settle(id,"1","7",new BigDecimal("0.1")));}
    @Test void realOverrunIsPreservedAndFutureAdmissionStops(){var l=ledger("0.3");UUID id=UUID.randomUUID();l.reserve(id,"1","7",bound());l.settle(id,"1","7",new BigDecimal("0.4"));assertEquals(0,l.spent().compareTo(new BigDecimal("0.4")));assertThrows(IllegalArgumentException.class,()->l.reserve(UUID.randomUUID(),"1","7",bound()));}
    @Test void concurrentAcceptanceCannotOversubscribe()throws Exception{var l=ledger("0.9");ExecutorService pool=Executors.newFixedThreadPool(12);var start=new CountDownLatch(1);var accepted=new AtomicInteger();List<Future<?>> jobs=new ArrayList<>();try{for(int i=0;i<100;i++)jobs.add(pool.submit(()->{start.await();try{l.reserve(UUID.randomUUID(),"1","7",bound());accepted.incrementAndGet();}catch(IllegalArgumentException expected){assertEquals("BUDGET_EXHAUSTED",expected.getMessage());}return null;}));start.countDown();for(var job:jobs)job.get(10,TimeUnit.SECONDS);assertEquals(3,accepted.get());assertEquals(0,l.held().compareTo(new BigDecimal("0.9")));}finally{pool.shutdownNow();}}
    @Test void tenantActorAndCurrencyCannotBeSwapped(){var l=ledger("1");assertThrows(IllegalArgumentException.class,()->l.reserve(UUID.randomUUID(),"2","7",bound()));assertThrows(IllegalArgumentException.class,()->l.reserve(UUID.randomUUID(),"1","8",bound()));var usd=new BudgetAdmission.Price("model","v1","p1","USD",BigDecimal.ONE,BigDecimal.ONE);assertThrows(IllegalArgumentException.class,()->l.reserve(UUID.randomUUID(),"1","7",BudgetAdmission.bound(usd,100,100,1)));}
}
