package com.ias.finance;

import org.h2.jdbcx.JdbcDataSource;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.ias.finance.FeeGroup.*;
import static com.ias.finance.JdbcFeeGroups.*;

class JdbcFeeGroupsTest {
    JdbcDataSource source;JdbcFeeGroups store;Access authority;Identity owner;Actor actor=new Actor(1,10);AtomicBoolean allowed;
    @BeforeEach void setup()throws Exception{
        source=new JdbcDataSource();source.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=3000");
        try(var db=source.getConnection()){for(String migration:List.of("V1__finance_group.sql","V2__customer_quote_confirmation.sql","V3__quote_decision.sql","V4__money_ledger.sql","V5__quote_source_intent.sql","V8__money_closure.sql","V9__money_notification_owner.sql","V10__money_notification_delivery.sql","V11__money_review_facts.sql","V12__correction_review_attestation.sql","V13__part_approval_claim.sql"))RunScript.execute(db,new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/db/migration/"+migration)),StandardCharsets.UTF_8));}
        allowed=new AtomicBoolean(true);owner=new Identity(1,2,UUID.randomUUID());
        store=new JdbcFeeGroups(source,authority=new Access(){
            public void current(Actor a,Identity i,String action){if(!allowed.get())throw new SecurityException("DENIED");}
            public void submission(Actor a,Identity i,Submit c,QuoteDetails d){if(!c.rule().version().startsWith("PUBLISHED"))throw new SecurityException("RULE_NOT_PUBLISHED");}
            public void approval(Actor a,Identity i,UUID id,ApprovalFact f){}
            public void verifiedCost(Actor a,Identity i,CostFact f){}
            public void execution(Actor a,Identity i,UUID l){}
        });
        store.open(actor,owner,UUID.randomUUID());
    }
    Submit command(long version,long cents,long threshold,Set<UUID> replaces){return new Submit(UUID.randomUUID(),UUID.randomUUID(),version,replaces,List.of(new PlanLine(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),cents,false)),new PublishedRule("PUBLISHED_V"+threshold,threshold));}
    QuoteDetails details(Submit c){var kinds=new HashMap<UUID,String>();c.additions().forEach(l->kinds.put(l.id(),"LABOR"));return new QuoteDetails(false,"Verified repair quotation",kinds);}
    long count(String table)throws Exception{try(var db=source.getConnection();var q=db.createStatement();var rows=q.executeQuery("SELECT COUNT(*) FROM "+table)){rows.next();return rows.getLong(1);}}
    @Test void submissionPersistsFrozenVersionReceiptAndPermanentFinanceRuleAcrossRestart()throws Exception{
        var first=command(0,230000,200000,Set.of());var receipt=store.submit(actor,owner,first,details(first));
        assertTrue(receipt.basis().financialReviewRequired());assertEquals(receipt,store.submit(actor,owner,first,details(first)));
        var second=command(1,10000,300000,Set.of(first.additions().getFirst().id()));var result=store.submit(actor,owner,second,details(second));
        assertEquals(10000,result.basis().qCents());assertTrue(result.basis().financialReviewRequired());assertEquals(first.applicationId(),result.basis().firstFinanceTrigger().applicationId());
        assertEquals(230000,store.quote(actor,owner,first.applicationId()).basis().bCents());assertEquals(200000,store.quote(actor,owner,first.applicationId()).command().rule().thresholdCents());
        assertEquals(2,count("finance_quote_version"));assertEquals(3,count("finance_operation"));assertEquals(3,count("finance_audit"));assertEquals(3,count("finance_outbox"));
    }
    @Test void highWaterAndCorrectedActualCostsRemainSeparateFromNewQuotes()throws Exception{
        var first=command(0,230000,200000,Set.of());store.submit(actor,owner,first,details(first));
        store.approved(actor,owner,UUID.randomUUID(),1,first.applicationId(),new ApprovalFact(UUID.randomUUID(),true,true));
        var cost=new CostFact(UUID.randomUUID(),240000,null,"verified physical execution");store.cost(actor,owner,UUID.randomUUID(),2,cost);
        store.cost(actor,owner,UUID.randomUUID(),3,new CostFact(UUID.randomUUID(),-20000,cost.id(),"verified recording correction"));
        var replacement=command(4,180000,300000,Set.of(first.additions().getFirst().id()));var result=store.submit(actor,owner,replacement,details(replacement));
        assertEquals(180000,result.basis().qCents());assertEquals(220000,result.basis().cCents());assertEquals(230000,result.basis().hCents());assertEquals(230000,result.basis().bCents());assertTrue(result.basis().financialReviewRequired());assertEquals(2,count("finance_cost_fact"));assertEquals(1,count("finance_approval_fact"));
    }
    @Test void replayNeverBypassesCurrentAuthorityOwnerOrWholeRequest()throws Exception{
        var command=command(0,100,1000,Set.of());store.submit(actor,owner,command,details(command));
        var altered=new QuoteDetails(true,"different customer responsibility",details(command).kinds());
        assertThrows(IllegalStateException.class,()->store.submit(actor,owner,command,altered));
        assertThrows(IllegalStateException.class,()->store.submit(new Actor(1,11),owner,command,details(command)));
        assertThrows(SecurityException.class,()->store.read(new Actor(2,10),owner));
        assertThrows(SecurityException.class,()->store.read(actor,new Identity(1,3,owner.faultGroupId())));
        allowed.set(false);assertThrows(SecurityException.class,()->store.submit(actor,owner,command,details(command)));assertThrows(SecurityException.class,()->store.quote(actor,owner,command.applicationId()));
    }
    @Test void concurrentDifferentOperationsOnSameVersionCommitOnlyOne()throws Exception{
        var a=command(0,100,1000,Set.of());var b=command(0,200,1000,Set.of());var start=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var results=new ArrayList<Future<Boolean>>();for(Submit command:List.of(a,b))results.add(pool.submit(()->{start.await();try{store.submit(actor,owner,command,details(command));return true;}catch(IllegalStateException expected){return false;}}));start.countDown();int success=0;for(var result:results)if(result.get(5,TimeUnit.SECONDS))success++;assertEquals(1,success);
        }
        assertEquals(1,store.read(actor,owner).version());assertEquals(1,count("finance_quote_version"));assertEquals(2,count("finance_audit"));
    }
    @Test void concurrentIdenticalOperationCreatesOneReceiptQuoteAndAudit()throws Exception{
        var command=command(0,100,1000,Set.of());var start=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var a=pool.submit(()->{start.await();return store.submit(actor,owner,command,details(command));});var b=pool.submit(()->{start.await();return store.submit(actor,owner,command,details(command));});start.countDown();assertEquals(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS));
        }assertEquals(1,count("finance_quote_version"));assertEquals(2,count("finance_audit"));
    }
    @Test void auditFailureRollsBackGroupQuoteReceiptAndOutbox()throws Exception{
        try(var db=source.getConnection();var q=db.createStatement()){q.execute("ALTER TABLE finance_audit ADD CONSTRAINT test_reject_quote CHECK(action_name<>'QUOTE_SUBMITTED')");}
        var command=command(0,230000,200000,Set.of());assertThrows(java.sql.SQLException.class,()->store.submit(actor,owner,command,details(command)));
        assertEquals(0,store.read(actor,owner).version());assertFalse(store.read(actor,owner).everRequiredFinance());assertEquals(0,count("finance_quote_version"));assertEquals(1,count("finance_operation"));assertEquals(1,count("finance_outbox"));
    }
    @Test void unpublishedRuleAndIncompleteApprovalOrOverCorrectionCannotWrite()throws Exception{
        var bad=new Submit(UUID.randomUUID(),UUID.randomUUID(),0,Set.of(),List.of(new PlanLine(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),230000,false)),new PublishedRule("draft",200000));
        assertThrows(SecurityException.class,()->store.submit(actor,owner,bad,details(bad)));
        var command=command(0,230000,200000,Set.of());store.submit(actor,owner,command,details(command));
        assertThrows(IllegalStateException.class,()->store.approved(actor,owner,UUID.randomUUID(),1,command.applicationId(),new ApprovalFact(UUID.randomUUID(),true,false)));
        var fact=new CostFact(UUID.randomUUID(),100,null,"actual cost");store.cost(actor,owner,UUID.randomUUID(),1,fact);
        assertThrows(IllegalArgumentException.class,()->store.cost(actor,owner,UUID.randomUUID(),2,new CostFact(UUID.randomUUID(),-101,fact.id(),"too much correction")));
        assertEquals(2,store.read(actor,owner).version());assertEquals(0,count("finance_approval_fact"));assertEquals(1,count("finance_cost_fact"));
    }
    @Test void executedLineCannotBeReplacedAndExecutionReplaysOriginalVersion()throws Exception{
        var command=command(0,100,1000,Set.of());store.submit(actor,owner,command,details(command));var operation=UUID.randomUUID();var line=command.additions().getFirst().id();
        var receipt=store.protectExecution(actor,owner,operation,1,line);assertEquals(receipt,store.protectExecution(actor,owner,operation,1,line));
        var replacement=command(2,50,1000,Set.of(line));assertThrows(IllegalStateException.class,()->store.submit(actor,owner,replacement,details(replacement)));
        assertTrue(store.read(actor,owner).lines().get(line).executed());assertEquals(1,count("finance_quote_version"));
    }
    @Test void paidQuoteCannotSkipUnimplementedCustomerConfirmationGate()throws Exception{
        var command=command(0,100,1000,Set.of());var paid=new QuoteDetails(true,"Customer paid quotation",details(command).kinds());store.submit(actor,owner,command,paid);
        var failure=assertThrows(IllegalStateException.class,()->store.approved(actor,owner,UUID.randomUUID(),1,command.applicationId(),new ApprovalFact(UUID.randomUUID(),true,true)));
        assertEquals("CUSTOMER_QUOTE_CONFIRMATION_REQUIRED",failure.getMessage());assertEquals(0,count("finance_approval_fact"));assertEquals(1,store.read(actor,owner).version());
    }
    @Test void unknownCommitWithSuccessfulRollbackDiscardsConnectionThenSameKeyRecovers()throws Exception{uncertain(false,false,false);}
    @Test void committedButLostAckAndRollbackFailureRecoversWithoutDuplicatingFacts()throws Exception{uncertain(true,true,false);}
    @Test void failedRollbackAndAbortQuarantineWithoutResetOrPoolReturn()throws Exception{uncertain(false,true,true);}
    void uncertain(boolean committed,boolean rollbackFails,boolean abortFails)throws Exception{
        Connection physical=source.getConnection();var closes=new AtomicInteger();var resets=new AtomicInteger();var aborts=new AtomicInteger();
        var proxy=(Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(p,m,args)->{
            switch(m.getName()){
                case "commit"->{if(committed)physical.commit();throw new SQLException("INJECTED_UNKNOWN_COMMIT");}
                case "rollback"->{if(rollbackFails)throw new SQLException("INJECTED_ROLLBACK_FAILURE");physical.rollback();return null;}
                case "abort"->{aborts.incrementAndGet();if(abortFails)throw new SQLException("INJECTED_ABORT_FAILURE");physical.close();return null;}
                case "close"->{closes.incrementAndGet();physical.close();return null;}
                case "setAutoCommit"->{if(Boolean.TRUE.equals(args[0]))resets.incrementAndGet();}
            }
            try{return m.invoke(physical,args);}catch(InvocationTargetException failure){throw failure.getCause();}
        });
        var faultSource=mock(DataSource.class);var connections=new AtomicInteger();when(faultSource.getConnection()).thenAnswer(invocation->connections.getAndIncrement()==0?source.getConnection():proxy);var faultStore=new JdbcFeeGroups(faultSource,authority);var command=command(0,230000,200000,Set.of());
        try{
            assertThrows(SQLException.class,()->faultStore.submit(actor,owner,command,details(command)));assertEquals(0,closes.get());assertEquals(0,resets.get());assertEquals(1,aborts.get());
            if(abortFails)assertThrows(SQLException.class,()->faultStore.submit(actor,owner,command,details(command)));
        }finally{physical.close();}
        assertEquals(committed?1:0,count("finance_quote_version"));var receipt=store.submit(actor,owner,command,details(command));assertEquals(1,receipt.groupVersion());
        assertEquals(1,count("finance_quote_version"));assertEquals(2,count("finance_operation"));assertEquals(2,count("finance_audit"));assertEquals(2,count("finance_outbox"));assertEquals(1,store.read(actor,owner).version());
    }
}
