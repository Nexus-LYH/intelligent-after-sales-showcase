package com.ias.approval;

import org.junit.jupiter.api.Test;
import org.h2.jdbcx.JdbcDataSource;
import org.h2.tools.RunScript;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import javax.sql.DataSource;

/** JDBC owner-core evidence only: H2 is not real MySQL or a source/W/P/S integration. */
class ApprovalFinanceGenerationTest {
    JdbcDataSource database()throws Exception{
        var d=new ApprovalJdbcTest().database();
        return d;
    }
    ApprovalFinanceGeneration.Binding binding(UUID ticket,String generation){return new ApprovalFinanceGeneration.Binding("1","3",ticket,UUID.randomUUID(),UUID.randomUUID(),"4",UUID.randomUUID(),UUID.randomUUID(),"a".repeat(64),UUID.randomUUID(),generation);}
    ApprovalFinanceGeneration owner(JdbcDataSource d){return new ApprovalFinanceGeneration(d,b->{});}
    long count(JdbcDataSource d,String table)throws Exception{try(var c=d.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+table)){r.next();return r.getLong(1);}}
    void application(JdbcDataSource d,UUID ticket,String state,String mode)throws Exception{
        String id=UUID.randomUUID().toString();try(var c=d.getConnection();var s=c.createStatement()){
            s.execute("INSERT INTO parts_application(id,tenant_id,state,row_version,snapshot) VALUES('"+id+"',1,'"+state+"',2,'{\"original\":true}')");
            s.execute("INSERT INTO approval_source_reference(tenant_id,application_id,ticket_id,company_id,round_id,assignment_id,command_digest) VALUES(1,'"+id+"','"+ticket+"',3,'"+UUID.randomUUID()+"','"+UUID.randomUUID()+"','"+"b".repeat(64)+"')");
            if(mode!=null)s.execute("INSERT INTO approval_execution_policy(tenant_id,application_id,charge_mode) VALUES(1,'"+id+"','"+mode+"')");
        }
    }
    @Test void productionDefaultVerifiesBeforeAnyWrite()throws Exception{
        var d=database();assertEquals(503,assertThrows(ApprovalFailure.class,()->new ApprovalFinanceGeneration(d).fence(binding(UUID.randomUUID(),"1"))).status());
        assertEquals(0,count(d,"approval_finance_generation"));assertEquals(0,count(d,"approval_finance_fence"));
    }
    @Test void emptyScopeDurablyFencesAndReplaysIdenticalOwnerAck()throws Exception{
        var d=database();var b=binding(UUID.randomUUID(),"1");var p=owner(d);var first=p.fence(b);
        assertEquals("OWNER_FENCED",first.state());assertEquals(false,first.ack().get("globalFreezeAck"));assertEquals("approval",first.ack().get("owner"));
        assertEquals(first.ack(),p.fence(b).ack());assertEquals(1,count(d,"approval_finance_fence"));assertEquals(1,count(d,"approval_finance_owner_ack"));assertEquals(2,count(d,"approval_finance_fence_audit"));
        assertTrue(first.ack().get("coverageDigest").toString().matches("[0-9a-f]{64}"));
        try(var c=d.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT recorded_at FROM approval_finance_owner_ack")){
            r.next();assertEquals(java.time.LocalDateTime.ofInstant(java.time.Instant.parse(first.ack().get("recordedAt").toString()),java.time.ZoneOffset.UTC),r.getObject(1,java.time.LocalDateTime.class));
        }
    }
    @Test void sameFreezeChangedParametersAndOldGenerationAreRejected()throws Exception{
        var d=database();var b=binding(UUID.randomUUID(),"1");var p=owner(d);p.fence(b);
        var changed=new ApprovalFinanceGeneration.Binding(b.tenantId(),"9",b.ticketId(),b.roundId(),b.groupId(),b.expectedGroupVersion(),b.quoteId(),b.sourceOperationId(),b.commandDigest(),b.freezeId(),b.generation());
        assertEquals(409,assertThrows(ApprovalFailure.class,()->p.fence(changed)).status());
        assertEquals(409,assertThrows(ApprovalFailure.class,()->p.fence(binding(b.ticketId(),"1"))).status());
        assertEquals(409,assertThrows(ApprovalFailure.class,()->p.fence(binding(b.ticketId(),"2"))).status());
        assertEquals(409,assertThrows(ApprovalFailure.class,()->p.fence(binding(UUID.randomUUID(),"2"))).status());
        assertEquals(b.freezeId().toString(),p.fence(b).ack().get("freezeId"));
    }
    @Test void pendingPaidAndUnknownApprovedWaitWithoutCancellingHistory()throws Exception{
        for(String[] pair:new String[][]{{"PENDING","CUSTOMER_PAID"},{"WAITING_CONFIGURATION","CUSTOMER_PAID"},{"APPROVED",null},{"PENDING",null}}){
            var d=database();UUID t=UUID.randomUUID();application(d,t,pair[0],pair[1]);var result=owner(d).fence(binding(t,"1"));
            assertEquals("WAITING_OWNER_OPERATIONS",result.state());assertNull(result.ack());assertEquals(0,count(d,"approval_finance_owner_ack"));
            try(var c=d.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT state FROM parts_application")){r.next();assertEquals(pair[0],r.getString(1));}
        }
    }
    @Test void approvedPaidAndTerminatedHistoryCoveredWhileFreeDoesNotBlock()throws Exception{
        var d=database();UUID t=UUID.randomUUID();application(d,t,"APPROVED","CUSTOMER_PAID");application(d,t,"REJECTED",null);application(d,t,"WITHDRAWN","CUSTOMER_PAID");application(d,t,"PENDING","FREE");
        var result=owner(d).fence(binding(t,"1"));assertEquals("OWNER_FENCED",result.state());
        var snapshot=(Map<?,?>)result.ack().get("localSnapshot");assertEquals(3,((List<?>)snapshot.get("facts")).size());
    }
    @Test void paidIntentWithoutReceiptWaitsAndBlocksNewPaidWrites()throws Exception{
        var d=database();UUID t=UUID.randomUUID(),op=UUID.randomUUID();
        try(var c=d.getConnection()){c.setAutoCommit(false);ApprovalFinanceGeneration.lockPaidMutation(c,"1",t,0,"2",op,"SUBMIT","c".repeat(64));c.commit();}
        assertEquals("WAITING_OWNER_OPERATIONS",owner(d).fence(binding(t,"1")).state());assertEquals(0,count(d,"approval_finance_owner_ack"));
        try(var c=d.getConnection()){c.setAutoCommit(false);assertEquals(409,assertThrows(ApprovalFailure.class,()->ApprovalFinanceGeneration.lockPaidMutation(c,"1",t,0,"2",UUID.randomUUID(),"DECIDE","d".repeat(64))).status());c.rollback();}
    }
    @Test void intentFullBindingAndTransactionRequirementAreEnforced()throws Exception{
        var d=database();UUID t=UUID.randomUUID(),op=UUID.randomUUID();try(var c=d.getConnection()){
            assertEquals(500,assertThrows(ApprovalFailure.class,()->ApprovalFinanceGeneration.lockPaidMutation(c,"1",t,0,"2",op,"SUBMIT","c".repeat(64))).status());
            c.setAutoCommit(false);ApprovalFinanceGeneration.lockPaidMutation(c,"1",t,0,"2",op,"SUBMIT","c".repeat(64));ApprovalFinanceGeneration.lockPaidMutation(c,"1",t,0,"2",op,"SUBMIT","c".repeat(64));
            assertEquals(409,assertThrows(ApprovalFailure.class,()->ApprovalFinanceGeneration.lockPaidMutation(c,"1",t,0,"2",op,"SUBMIT","d".repeat(64))).status());c.rollback();
        }assertEquals(0,count(d,"approval_finance_write_intent"));
    }
    @Test void auditFailureRollsBackFenceGenerationAndAck()throws Exception{
        var d=database();try(var c=d.getConnection();var s=c.createStatement()){s.execute("ALTER TABLE approval_finance_fence_audit ADD CONSTRAINT reject_owner CHECK(event_type<>'OWNER_FENCED')");}
        assertEquals(503,assertThrows(ApprovalFailure.class,()->owner(d).fence(binding(UUID.randomUUID(),"1"))).status());
        for(String table:List.of("approval_finance_generation","approval_finance_fence","approval_finance_owner_ack","approval_finance_fence_audit"))assertEquals(0,count(d,table));
    }
    @Test void generationLockSerializesFreezeAfterPaidTransaction()throws Exception{
        var d=database();UUID t=UUID.randomUUID();var pool=Executors.newSingleThreadExecutor();
        try(var c=d.getConnection()){
            c.setAutoCommit(false);ApprovalFinanceGeneration.lockPaidMutation(c,"1",t,0,"2",UUID.randomUUID(),"SUBMIT","e".repeat(64));
            var f=pool.submit(()->owner(d).fence(binding(t,"1")));assertThrows(TimeoutException.class,()->f.get(150,TimeUnit.MILLISECONDS));c.commit();
            assertEquals("WAITING_OWNER_OPERATIONS",f.get(5,TimeUnit.SECONDS).state());assertEquals(0,count(d,"approval_finance_owner_ack"));
        }finally{pool.shutdownNow();}
    }
    @Test void confirmedPaidOperationIsCoveredWithOriginalReceipt()throws Exception{
        var d=database();UUID t=UUID.randomUUID(),op=UUID.randomUUID();try(var c=d.getConnection()){
            c.setAutoCommit(false);ApprovalFinanceGeneration.lockPaidMutation(c,"1",t,0,"2",op,"SUBMIT","c".repeat(64));
            try(var p=c.prepareStatement("INSERT INTO approval_operation(tenant_id,actor_id,operation_id,command_hash,action,receipt) VALUES(1,2,?,'hash','SUBMIT','{\"accepted\":true}')")){p.setString(1,op.toString());p.executeUpdate();}c.commit();
        }
        var result=owner(d).fence(binding(t,"1"));assertEquals("OWNER_FENCED",result.state());
        assertTrue(result.ack().get("localSnapshot").toString().contains("accepted=true"));
    }
    @Test void uncertainCommitDoesNotRollbackResetOrCloseAndQuarantinesFailedAbort()throws Exception{
        var d=database();Connection real=d.getConnection();Connection wrapper=mock(Connection.class,org.mockito.AdditionalAnswers.delegatesTo(real));
        doThrow(new SQLException("injected uncertain commit")).when(wrapper).commit();doThrow(new SQLException("injected abort failure")).when(wrapper).abort(any());
        DataSource proxy=mock(DataSource.class);when(proxy.getConnection()).thenReturn(wrapper);var p=new ApprovalFinanceGeneration(proxy,b->{});
        assertEquals(503,assertThrows(ApprovalFailure.class,()->p.fence(binding(UUID.randomUUID(),"1"))).status());
        verify(wrapper,never()).rollback();verify(wrapper,never()).close();verify(wrapper,never()).setAutoCommit(true);verify(wrapper).abort(any());
        assertEquals(503,assertThrows(ApprovalFailure.class,()->p.fence(binding(UUID.randomUUID(),"1"))).status());verify(proxy,times(1)).getConnection();
        real.rollback();real.close();
    }
    @Test void sourceProofIsRecheckedInsideGenerationLockBeforeFenceOrAck()throws Exception{
        var d=database();var calls=new java.util.concurrent.atomic.AtomicInteger();
        var p=new ApprovalFinanceGeneration(d,b->{if(calls.incrementAndGet()==2)throw new ApprovalFailure(503,"SOURCE_PROOF_CHANGED");});
        assertEquals(503,assertThrows(ApprovalFailure.class,()->p.fence(binding(UUID.randomUUID(),"1"))).status());assertEquals(2,calls.get());
        for(String table:List.of("approval_finance_generation","approval_finance_fence","approval_finance_owner_ack","approval_finance_fence_audit"))assertEquals(0,count(d,table));
    }
}
