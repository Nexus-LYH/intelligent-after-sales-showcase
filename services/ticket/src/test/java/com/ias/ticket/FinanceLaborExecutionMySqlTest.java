package com.ias.ticket;

import com.ias.trust.Problem;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in fresh-schema runner. Never use default ias_* schemas; root coordinates window/grants. */
@EnabledIfEnvironmentVariable(named="IAS_BEW_TEST_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:3307/qa_bew_execution_[a-z0-9_]+.*")
class FinanceLaborExecutionMySqlTest {
 @Test void realInnoDbSameKeyContentionAuditRollbackAndAppendOnlyRuntime()throws Exception{
  var owner=new DriverManagerDataSource(System.getenv("IAS_BEW_TEST_URL"),System.getenv("IAS_BEW_TEST_OWNER"),System.getenv("IAS_BEW_TEST_OWNER_PASSWORD"));
  var admin=new JdbcTemplate(owner);var guard=admin.queryForMap("SELECT @@port port,@@datadir datadir,DATABASE() db");assertEquals(3307,((Number)guard.get("port")).intValue());assertEquals("C:/ias-local/Develop/IAS-MySQL84/data/",guard.get("datadir").toString().replace('\\','/'));String schema=guard.get("db").toString();assertTrue(schema.matches("qa_bew_execution_[a-z0-9_]+"));assertEquals(0,admin.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Long.class));
  String user=System.getenv("IAS_BEW_TEST_USER");assertNotNull(user);assertTrue(user.matches("[a-z0-9_]{1,32}"));
  var f=new FinanceLaborExecutionStoreTest();try{
   f.initialize(owner,false);
   for(String table:List.of("ticket_record","ticket_create_operation","ticket_processing_record","ticket_finance_quote_fence","ticket_finance_coordination","ticket_finance_quote_source_result","ticket_finance_owner_ack"))admin.execute("GRANT SELECT ON "+schema+"."+table+" TO '"+user+"'@'127.0.0.1'");
   for(String table:List.of("ticket_fee_labor_execution","ticket_fee_labor_operation","ticket_fee_labor_audit"))admin.execute("GRANT SELECT,INSERT ON "+schema+"."+table+" TO '"+user+"'@'127.0.0.1'");
   admin.execute("GRANT SELECT,INSERT ON "+schema+".ticket_fee_notification_outbox TO '"+user+"'@'127.0.0.1'");
   admin.execute("GRANT UPDATE(delivery_state,attempt_count,next_attempt_at,lease_until,last_error,delivered_at) ON "+schema+".ticket_fee_notification_outbox TO '"+user+"'@'127.0.0.1'");
   admin.execute("GRANT UPDATE(row_version) ON "+schema+".ticket_record TO '"+user+"'@'127.0.0.1'");
   f.source=new DriverManagerDataSource(System.getenv("IAS_BEW_TEST_URL"),user,System.getenv("IAS_BEW_TEST_PASSWORD"));f.db=new JdbcTemplate(f.source);f.store=new FinanceLaborExecutionStore(f.source,f.authority());
   admin.execute("ALTER TABLE ticket_fee_labor_audit ADD CONSTRAINT qa_bew_audit CHECK(actor_user_id<>42)");assertEquals(503,assertThrows(Problem.class,f::execute).status);f.empty();admin.execute("ALTER TABLE ticket_fee_labor_audit DROP CHECK qa_bew_audit");
   f.sameUuidSixConcurrentCallsHaveOneReceipt();var receipt=f.execute();assertEquals(receipt,f.store.operation(f.ticket,f.original.get("operationId").toString()));assertEquals("EXECUTED",f.store.query(f.query()).get("state"));
   String event=f.original.get("operationId").toString();var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(f.source));new TicketFeeNotificationDelivery(f.db,tx,e->{throw new IllegalStateException("NOTIFICATION_UNAVAILABLE");}).tick();assertEquals("PENDING",f.db.queryForObject("SELECT delivery_state FROM ticket_fee_notification_outbox WHERE event_id=?",String.class,event));f.db.update("UPDATE ticket_fee_notification_outbox SET next_attempt_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(3)) WHERE event_id=?",event);new TicketFeeNotificationDelivery(f.db,tx,e->Map.of("eventId",e.get("eventId"),"ack","COMMITTED_DUPLICATE")).tick();assertEquals("DELIVERED",f.db.queryForObject("SELECT delivery_state FROM ticket_fee_notification_outbox WHERE event_id=?",String.class,event));
   for(String table:List.of("ticket_fee_labor_execution","ticket_fee_labor_operation","ticket_fee_labor_audit")){
    assertThrows(org.springframework.dao.DataAccessException.class,()->f.db.update("DELETE FROM "+table));
    assertThrows(org.springframework.dao.DataAccessException.class,()->f.db.update("UPDATE "+table+" SET actor_user_id=77"));
   }
   assertThrows(org.springframework.dao.DataAccessException.class,()->f.db.update("UPDATE ticket_record SET engineer_user_id=77"));
   assertEquals(1,f.count("ticket_fee_labor_execution"));assertEquals(1,f.count("ticket_fee_labor_operation"));assertEquals(1,f.count("ticket_fee_labor_audit"));assertEquals(1,f.count("ticket_fee_notification_outbox"));assertEquals(2,f.version());
  }finally{f.close();}
 }
}
