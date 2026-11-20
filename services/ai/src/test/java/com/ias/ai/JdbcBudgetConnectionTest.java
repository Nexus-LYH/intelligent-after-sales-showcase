package com.ias.ai;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import javax.sql.DataSource;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exception proxies over real H2 transactions; not physical process/commit-time kill evidence. */
class JdbcBudgetConnectionTest {
 JdbcBudgetLedgerTest fixture;
 @BeforeEach void prepare()throws Exception{fixture=new JdbcBudgetLedgerTest();fixture.setup();}
 @AfterEach void stop()throws Exception{fixture.cleanup();}
 @Test void unknownCommitBeforeCommitAbortsNeverResetsOrReturnsConnection()throws Exception {fault(false,false,false);}
 @Test void committedButReceiptUnknownIsRecoveredWithoutSecondHold()throws Exception {fault(true,true,false);}
 @Test void rollbackAndAbortFailuresQuarantineWithoutClosingOrReturningConnection()throws Exception {fault(false,true,true);}
 @Test void arbitraryPoolingWrapperCannotBeAcceptedAsPhysicalDatasource(){assertThrows(IllegalArgumentException.class,()->new JdbcBudgetLedger(mock(DataSource.class),(t,a,b,p,x)->true));}
 void fault(boolean committed,boolean rollbackFails,boolean abortFails)throws Exception {
  Connection physical=fixture.db.getConnection();AtomicInteger closes=new AtomicInteger(),resets=new AtomicInteger(),aborts=new AtomicInteger();
  Connection wrapped=(Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(p,m,args)->{
   switch(m.getName()){
    case "commit" -> {if(committed)physical.commit();throw new SQLException("INJECTED_COMMIT_RESULT_UNKNOWN");}
    case "rollback" -> {if(rollbackFails)throw new SQLException("INJECTED_ROLLBACK_FAILURE");physical.rollback();return null;}
    case "abort" -> {aborts.incrementAndGet();if(abortFails)throw new SQLException("INJECTED_ABORT_FAILURE");physical.close();return null;}
    case "close" -> {closes.incrementAndGet();physical.close();return null;}
    case "setAutoCommit" -> {if(Boolean.TRUE.equals(args[0]))resets.incrementAndGet();}
   }
   try{return m.invoke(physical,args);}catch(InvocationTargetException e){throw e.getCause();}
  });
  JdbcDataSource faultSource=spy(fixture.db);doReturn(wrapped).when(faultSource).getConnection();
  var ledger=new JdbcBudgetLedger(faultSource,(t,a,b,p,x)->true);var command=fixture.command(UUID.randomUUID());
  try{assertThrows(SQLException.class,()->ledger.reserve(command));assertEquals(0,closes.get());assertEquals(0,resets.get());assertEquals(1,aborts.get());if(abortFails)assertThrows(SQLException.class,()->ledger.reserve(command));}
  finally{physical.close();}
  assertEquals(committed?1:0,fixture.count("ai_reservation"));assertEquals(committed?1:0,fixture.count("ai_budget_audit"));
  assertEquals(BudgetAdmission.State.RESERVED,fixture.ledger.reserve(command).state());assertEquals(1,fixture.count("ai_reservation"));assertEquals(1,fixture.count("ai_budget_audit"));
 }
}
