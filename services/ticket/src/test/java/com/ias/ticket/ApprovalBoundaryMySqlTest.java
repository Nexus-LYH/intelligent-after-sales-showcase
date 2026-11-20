package com.ias.ticket;

import com.ias.trust.Problem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Dedicated fresh schema only. Real MySQL/domain SQL; current identity is a test port, not TLS evidence. */
@EnabledIfEnvironmentVariable(named="IAS_TICKET_BOUNDARY_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:3307/qa_ticket_boundary_[a-z0-9_]+.*")
public class ApprovalBoundaryMySqlTest {
 @Test void minimumGrantsRollbackOriginalUuidAndActualCancellationRace()throws Exception{
  String url=System.getenv("IAS_TICKET_BOUNDARY_URL");
  var owner=new JdbcTemplate(new DriverManagerDataSource(url,System.getenv("IAS_TICKET_BOUNDARY_OWNER"),System.getenv("IAS_TICKET_BOUNDARY_OWNER_PASSWORD")));
  var guard=owner.queryForMap("SELECT @@port port,@@datadir datadir,DATABASE() db");assertEquals(3307,((Number)guard.get("port")).intValue());assertEquals("C:/ias-local/Develop/IAS-MySQL84/data/",guard.get("datadir").toString().replace('\\','/'));assertTrue(guard.get("db").toString().startsWith("qa_ticket_boundary_"));
  owner.execute("CREATE TABLE ticket_actor_lock(tenant_id BIGINT NOT NULL,user_id BIGINT NOT NULL,PRIMARY KEY(tenant_id,user_id)) ENGINE=InnoDB");
  owner.execute("CREATE TABLE ticket_record(id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,tenant_id BIGINT,round_id CHAR(36),assignment_id CHAR(36),company_id BIGINT,department_id BIGINT,engineer_user_id BIGINT,status VARCHAR(40),row_version BIGINT,current_cancel_request_id CHAR(36)) ENGINE=InnoDB");
  owner.execute("CREATE TABLE ticket_round(id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,ticket_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,PRIMARY KEY(id,ticket_id)) ENGINE=InnoDB");
  owner.execute("CREATE TABLE ticket_assignment(id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY) ENGINE=InnoDB");
  owner.execute("CREATE TABLE ticket_audit(id BIGINT AUTO_INCREMENT PRIMARY KEY,operation_id CHAR(36),tenant_id BIGINT,actor_user_id BIGINT,action VARCHAR(64),occurred_at DATETIME(3)) ENGINE=InnoDB");
  try(var in=getClass().getResourceAsStream("/db/migration/V9__approval_reference_boundary.sql")){owner.execute(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
  try(var in=getClass().getResourceAsStream("/db/migration/V10__approval_settlement_boundary.sql")){owner.execute(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
  String runtime=System.getenv("IAS_TICKET_BOUNDARY_USER"),schema=guard.get("db").toString();assertTrue(runtime.matches("[a-z0-9_]{1,32}"));assertTrue(schema.matches("qa_ticket_boundary_[a-z0-9_]+"));
  for(String table:List.of("ticket_approval_reference","ticket_approval_settlement","ticket_audit"))owner.execute("GRANT SELECT,INSERT ON "+schema+"."+table+" TO '"+runtime+"'@'127.0.0.1'");
  owner.execute("GRANT SELECT,UPDATE(status,row_version) ON "+schema+".ticket_record TO '"+runtime+"'@'127.0.0.1'");owner.execute("GRANT SELECT,INSERT,UPDATE(user_id) ON "+schema+".ticket_actor_lock TO '"+runtime+"'@'127.0.0.1'");
  for(String table:List.of("ticket_round","ticket_assignment"))owner.execute("GRANT SELECT ON "+schema+"."+table+" TO '"+runtime+"'@'127.0.0.1'");
  var source=new DriverManagerDataSource(url,System.getenv("IAS_TICKET_BOUNDARY_USER"),System.getenv("IAS_TICKET_BOUNDARY_PASSWORD"));var db=new JdbcTemplate(source);var tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setIsolationLevel(2);tx.setTimeout(5);var tickets=new Tickets(db,tx);
  var access=new StaffAccess(){@Override Map<String,Object> approvalFacts(Tickets.Actor a,String purpose){return facts(a);}@Override Map<String,Object> facts(Tickets.Actor a){return Map.of("enabled",true,"departmentEnabled",true,"tenantId","7","userId","42","departmentId","5","companyIds",List.of(),"permissions",List.of("ticket.engineer.read","ticket.record","approval.parts.read","approval.parts.submit"));}};
  var boundary=new ApprovalBoundary(tickets,access);var actor=new Tickets.Actor(7,42,"EMPLOYEE",null,null,"mysql-domain",Long.MAX_VALUE,null);
  var first=fixture(owner);String op=UUID.randomUUID().toString();owner.execute("ALTER TABLE ticket_audit ADD CONSTRAINT qa_boundary_fault CHECK(action<>'APPROVAL_REFERENCE_REGISTERED')");
  assertThrows(Exception.class,()->boundary.register(first[0],first[1],first[2],op,"a".repeat(64),actor));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ticket_approval_reference",Long.class));assertEquals(1,db.queryForObject("SELECT row_version FROM ticket_record WHERE id=?",Long.class,first[0]));
  owner.execute("ALTER TABLE ticket_audit DROP CHECK qa_boundary_fault");var registered=boundary.register(first[0],first[1],first[2],op,"a".repeat(64),actor);assertEquals(registered.get("recordedAt"),boundary.register(first[0],first[1],first[2],op,"a".repeat(64),actor).get("recordedAt"));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ticket_audit",Long.class));assertEquals("UNSUPPORTED_RELATED_BUSINESS",assertThrows(Problem.class,()->boundary.requireNoExternal(first[0])).code);
  var second=fixture(owner);String shared=UUID.randomUUID().toString();try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var gate=new CountDownLatch(1);var jobs=new ArrayList<Future<?>>();for(int i=0;i<6;i++)jobs.add(pool.submit(()->{gate.await();return boundary.register(second[0],second[1],second[2],shared,"b".repeat(64),actor);}));gate.countDown();for(var job:jobs)job.get();}assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ticket_approval_reference WHERE ticket_id=?",Long.class,second[0]));assertEquals(2,db.queryForObject("SELECT row_version FROM ticket_record WHERE id=?",Long.class,second[0]));
  var third=fixture(owner);try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var gate=new CountDownLatch(1);var submit=pool.submit(()->{gate.await();try{boundary.register(third[0],third[1],third[2],UUID.randomUUID().toString(),"c".repeat(64),actor);return true;}catch(Problem e){assertEquals("TICKET_NOT_APPROVABLE",e.code);return false;}});var cancel=pool.submit(()->{gate.await();try{return tx.execute(status->{db.queryForMap("SELECT * FROM ticket_record WHERE id=? FOR UPDATE",third[0]);boundary.requireNoExternal(third[0]);db.update("UPDATE ticket_record SET status='CANCELLED',row_version=row_version+1 WHERE id=?",third[0]);return true;});}catch(Problem e){assertEquals("UNSUPPORTED_RELATED_BUSINESS",e.code);return false;}});gate.countDown();assertNotEquals(submit.get(),cancel.get());}
  assertThrows(Exception.class,()->db.execute("CREATE TABLE prohibited(id INT)"));assertThrows(Exception.class,()->db.update("DELETE FROM ticket_approval_reference WHERE operation_id=?",op));assertThrows(Exception.class,()->db.update("UPDATE ticket_approval_reference SET command_digest=? WHERE operation_id=?","d".repeat(64),op));assertThrows(Exception.class,()->db.queryForList("SELECT id FROM ias_identity_test.tenant LIMIT 1"));
  assertThrows(Exception.class,()->db.queryForList("SELECT * FROM ticket_approval_reference WHERE operation_id=? FOR UPDATE",op));
 }
 String[] fixture(JdbcTemplate owner){String[] ids={UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString()};owner.update("INSERT INTO ticket_record VALUES(?,7,?,?,9,5,42,'IN_PROGRESS',1,NULL)",ids[0],ids[1],ids[2]);owner.update("INSERT INTO ticket_round VALUES(?,?)",ids[1],ids[0]);owner.update("INSERT INTO ticket_assignment VALUES(?)",ids[2]);return ids;}
}

