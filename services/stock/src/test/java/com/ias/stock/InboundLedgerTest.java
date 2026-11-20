package com.ias.stock;

import org.h2.jdbcx.JdbcDataSource;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationTargetException;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InboundLedgerTest {
    JdbcDataSource db; InboundLedger ledger; AtomicBoolean allowed;
    final InboundLedger.Actor actor=new InboundLedger.Actor(1,10);
    @BeforeEach void prepare() throws Exception {
        db=new JdbcDataSource();db.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        try(Connection c=db.getConnection()) {
            RunScript.execute(c,new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/db/migration/V1__stock_inbound.sql")),StandardCharsets.UTF_8));
            RunScript.execute(c,new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/db/migration/V3__stock_inbound_outbox.sql")),StandardCharsets.UTF_8));
            c.createStatement().execute("INSERT INTO stock_warehouse VALUES(11,1,'W1',TRUE),(22,2,'W2',TRUE)");
            c.createStatement().execute("INSERT INTO stock_part VALUES(101,1,'PART','part','model','pcs',TRUE,0),(202,2,'PART','part','model','pcs',TRUE,0)");
        }
        allowed=new AtomicBoolean(true);
        ledger=new InboundLedger(db,(a,c)->{if(!allowed.get())throw new SecurityException("CURRENT_SCOPE_OR_EVIDENCE_DENIED");});
    }
    InboundLedger.Command command(UUID op,long quantity){return new InboundLedger.Command(op,11,101,quantity,"Purchase delivery","Batch one",UUID.fromString("00000000-0000-0000-0000-000000000001"));}
    long scalar(String sql)throws SQLException{try(Connection c=db.getConnection();Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)){r.next();return r.getLong(1);}}
    void execute(String sql)throws SQLException{try(Connection c=db.getConnection();Statement s=c.createStatement()){s.execute(sql);}}
    @Test void inboundPersistsSingleMovementAuditAndStableReceipt()throws Exception {
        var cmd=command(UUID.randomUUID(),10);var first=ledger.receive(actor,cmd);var replay=ledger.receive(actor,cmd);
        assertEquals(first,replay);assertEquals(10,scalar("SELECT on_hand FROM stock_balance"));
        for(String table:List.of("stock_operation","stock_movement","stock_audit"))assertEquals(1,scalar("SELECT COUNT(*) FROM "+table));
        assertEquals(0,scalar("SELECT reserved FROM stock_balance"));
    }
    @Test void sameUuidChangedQuantityOrActorCannotIncreaseAgain()throws Exception {
        UUID op=UUID.randomUUID();ledger.receive(actor,command(op,10));
        assertThrows(IllegalStateException.class,()->ledger.receive(actor,command(op,11)));
        assertThrows(IllegalStateException.class,()->ledger.receive(new InboundLedger.Actor(1,12),command(op,10)));
        assertEquals(10,scalar("SELECT on_hand FROM stock_balance"));
    }
    @Test void pureQueryPreservesOriginalReceiptAfterFurtherInbound()throws Exception {
        var cmd=command(UUID.randomUUID(),10);var first=ledger.receive(actor,cmd);
        ledger.receive(actor,command(UUID.randomUUID(),7));
        assertEquals(first,ledger.find(actor,cmd.operationId()).orElseThrow());
        assertEquals(17,scalar("SELECT on_hand FROM stock_balance"));
        assertEquals(2,scalar("SELECT COUNT(*) FROM stock_outbox"));
        assertTrue(ledger.find(actor,UUID.randomUUID()).isEmpty());
        assertEquals(2,scalar("SELECT COUNT(*) FROM stock_operation"));
    }
    @Test void queryCannotBorrowTenantActorOrRevokedEvidence()throws Exception {
        var cmd=command(UUID.randomUUID(),10);ledger.receive(actor,cmd);
        assertTrue(ledger.find(new InboundLedger.Actor(2,10),cmd.operationId()).isEmpty());
        assertTrue(ledger.find(new InboundLedger.Actor(1,11),cmd.operationId()).isEmpty());
        allowed.set(false);
        assertThrows(SecurityException.class,()->ledger.find(actor,cmd.operationId()));
    }
    @Test void outboxFailureRollsBackEveryInboundFactAndOriginalKeyRecovers()throws Exception {
        execute("ALTER TABLE stock_outbox ADD CONSTRAINT reject_event CHECK(event_type<>'STOCK_RECEIVED')");
        var cmd=command(UUID.randomUUID(),10);
        assertThrows(SQLException.class,()->ledger.receive(actor,cmd));
        for(String table:List.of("stock_balance","stock_operation","stock_movement","stock_audit","stock_outbox"))
            assertEquals(0,scalar("SELECT COUNT(*) FROM "+table));
        assertTrue(ledger.find(actor,cmd.operationId()).isEmpty());
        execute("ALTER TABLE stock_outbox DROP CONSTRAINT reject_event");
        assertEquals(10,ledger.receive(actor,cmd).onHand());
        ledger.receive(actor,cmd);
        assertEquals(1,scalar("SELECT COUNT(*) FROM stock_outbox"));
    }
    @Test void revokedCurrentScopeDeniesEvenCommittedReceipt()throws Exception {
        var cmd=command(UUID.randomUUID(),10);ledger.receive(actor,cmd);allowed.set(false);
        assertThrows(SecurityException.class,()->ledger.receive(actor,cmd));assertEquals(1,scalar("SELECT COUNT(*) FROM stock_operation"));
    }
    @Test void tenantWarehouseAndPartCannotBeBorrowed()throws Exception {
        assertThrows(SecurityException.class,()->ledger.receive(new InboundLedger.Actor(2,20),command(UUID.randomUUID(),10)));
        var mixed=new InboundLedger.Command(UUID.randomUUID(),11,202,10,"source","batch",UUID.randomUUID());
        assertThrows(SecurityException.class,()->ledger.receive(actor,mixed));assertEquals(0,scalar("SELECT COUNT(*) FROM stock_balance"));
    }
    @Test void auditFailureRollsBackBalanceMovementReceiptThenOriginalKeyRecovers()throws Exception {
        execute("ALTER TABLE stock_audit ADD CONSTRAINT reject_audit CHECK(actor_id<>10)");
        var cmd=command(UUID.randomUUID(),10);assertThrows(SQLException.class,()->ledger.receive(actor,cmd));
        for(String t:List.of("stock_balance","stock_operation","stock_movement","stock_audit"))assertEquals(0,scalar("SELECT COUNT(*) FROM "+t));
        execute("ALTER TABLE stock_audit DROP CONSTRAINT reject_audit");assertEquals(10,ledger.receive(actor,cmd).onHand());
    }
    @Test void competingSameKeyCreatesOneFact()throws Exception {
        var cmd=command(UUID.randomUUID(),10);try(var pool=Executors.newFixedThreadPool(6)) {
            List<Callable<InboundLedger.Receipt>> calls=new ArrayList<>();for(int i=0;i<6;i++)calls.add(()->ledger.receive(actor,cmd));
            for(var future:pool.invokeAll(calls))assertEquals(10,future.get().onHand());
        }
        assertEquals(10,scalar("SELECT on_hand FROM stock_balance"));assertEquals(1,scalar("SELECT COUNT(*) FROM stock_operation"));
    }
    @Test void distinctInboundKeysAccumulateWithoutLostUpdates()throws Exception {
        try(var pool=Executors.newFixedThreadPool(6)) {
            List<Callable<InboundLedger.Receipt>> calls=new ArrayList<>();for(int i=0;i<12;i++)calls.add(()->ledger.receive(actor,command(UUID.randomUUID(),1)));
            for(var future:pool.invokeAll(calls))future.get();
        }
        assertEquals(12,scalar("SELECT on_hand FROM stock_balance"));assertEquals(12,scalar("SELECT COUNT(*) FROM stock_audit"));
    }
    @Test void overflowAndDisabledCatalogDoNotWritePartialFacts()throws Exception {
        ledger.receive(actor,command(UUID.randomUUID(),Long.MAX_VALUE));
        assertThrows(ArithmeticException.class,()->ledger.receive(actor,command(UUID.randomUUID(),1)));
        execute("UPDATE stock_part SET active=FALSE");assertThrows(SecurityException.class,()->ledger.receive(actor,command(UUID.randomUUID(),1)));
        assertEquals(Long.MAX_VALUE,scalar("SELECT on_hand FROM stock_balance"));assertEquals(1,scalar("SELECT COUNT(*) FROM stock_movement"));
    }
    @Test void invalidQuantitiesAndMissingSourceFailBeforeOpeningTransaction() {
        assertThrows(IllegalArgumentException.class,()->command(UUID.randomUUID(),0));
        assertThrows(IllegalArgumentException.class,()->command(UUID.randomUUID(),-1));
        assertThrows(IllegalArgumentException.class,()->new InboundLedger.Command(UUID.randomUUID(),11,101,1," ","batch",UUID.randomUUID()));
    }
    @Test void uncertainCommitWithSuccessfulRollbackAbortsWithoutPoolClose()throws Exception {
        verifyUncertain(false,false,false);
    }
    @Test void uncertainCommitAlreadyCommittedRecoversOriginalFactWithoutIncrement()throws Exception {
        verifyUncertain(true,true,false);
    }
    @Test void rollbackAndAbortFailuresQuarantineWithoutPoolCloseOrAutoCommitReset()throws Exception {
        verifyUncertain(false,true,true);
    }
    void verifyUncertain(boolean commitReallyHappened,boolean rollbackFails,boolean abortFails)throws Exception {
        Connection physical=db.getConnection();AtomicInteger poolClose=new AtomicInteger(),reset=new AtomicInteger(),aborts=new AtomicInteger();
        Connection proxy=(Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(p,m,args)->{
            switch(m.getName()) {
                case "commit" -> {if(commitReallyHappened)physical.commit();throw new SQLException("INJECTED_COMMIT_RESULT_UNKNOWN");}
                case "rollback" -> {if(rollbackFails)throw new SQLException("INJECTED_ROLLBACK_FAILURE");physical.rollback();return null;}
                case "abort" -> {aborts.incrementAndGet();if(abortFails)throw new SQLException("INJECTED_ABORT_FAILURE");physical.close();return null;}
                case "close" -> {poolClose.incrementAndGet();physical.close();return null;}
                case "setAutoCommit" -> {if(Boolean.TRUE.equals(args[0]))reset.incrementAndGet();}
            }
            try{return m.invoke(physical,args);}catch(InvocationTargetException e){throw e.getCause();}
        });
        DataSource faultSource=mock(DataSource.class);when(faultSource.getConnection()).thenReturn(proxy);
        InboundLedger uncertain=new InboundLedger(faultSource,(a,c)->{});var cmd=command(UUID.randomUUID(),10);
        try {
            assertThrows(SQLException.class,()->uncertain.receive(actor,cmd));
            assertEquals(0,poolClose.get());assertEquals(0,reset.get());assertEquals(1,aborts.get());
            if(abortFails)assertThrows(SQLException.class,()->uncertain.receive(actor,cmd));
        }finally{physical.close();}
        assertEquals(commitReallyHappened?1:0,scalar("SELECT COUNT(*) FROM stock_operation"));
        // New connection + same key reconciles both outcomes; no partial movement/audit/balance commit.
        assertEquals(10,ledger.receive(actor,cmd).onHand());
        assertEquals(10,scalar("SELECT on_hand FROM stock_balance"));
        for(String t:List.of("stock_operation","stock_movement","stock_audit"))assertEquals(1,scalar("SELECT COUNT(*) FROM "+t));
    }
}
