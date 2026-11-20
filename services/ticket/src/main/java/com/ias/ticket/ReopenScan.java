package com.ias.ticket;
import com.ias.trust.*;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.scheduling.annotation.Scheduled;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** Only unaccepted assignments in reopened rounds are automatically released. */
@Component @ConditionalOnProperty(name="ias.ticket.reopen-scan.enabled",havingValue="true",matchIfMissing=true)
public class ReopenScan {
 final Tickets t;final StaffAccess access;
 public ReopenScan(Tickets t,StaffAccess access){this.t=t;this.access=access;}
 String cursor="";
 @EventListener(ApplicationReadyEvent.class) public void start(){tick();}
 @Scheduled(fixedDelay=60000,initialDelay=60000) public void tick(){try{scan();}catch(RuntimeException e){System.err.println("reopen.scan retry type="+e.getClass().getSimpleName());}}
 public int scan(){int count=0;var rows=t.db.queryForList("SELECT r.id FROM ticket_record r JOIN ticket_round rd ON rd.id=r.round_id AND rd.ticket_id=r.id WHERE rd.round_no>1 AND r.status='ASSIGNED' AND r.assignment_id IS NOT NULL AND r.id>? ORDER BY r.id LIMIT 25",cursor);if(rows.isEmpty()){cursor="";return 0;}for(var row:rows){cursor=row.get("id").toString();try{if(scanTicket(cursor))count++;}catch(RuntimeException e){System.err.println("reopen.scan ticket="+cursor+" type="+e.getClass().getSimpleName());}}return count;}
 public boolean scanTicket(String id){var rows=t.db.queryForList("SELECT r.* FROM ticket_record r JOIN ticket_round rd ON rd.id=r.round_id AND rd.ticket_id=r.id WHERE r.id=? AND rd.round_no>1 AND r.status='ASSIGNED' AND r.assignment_id IS NOT NULL",Tickets.uuid(id));if(rows.isEmpty())return false;var before=rows.getFirst();var transfers=new Transfers(t,access);if(transfers.sourceQualified(before))return false;return Boolean.TRUE.equals(t.tx.execute(tx->{t.lockActor(Tickets.id(before.get("tenant_id")),0);var row=t.db.queryForMap("SELECT * FROM ticket_record WHERE id=? FOR UPDATE",id);if(!"ASSIGNED".equals(row.get("status"))||!Objects.equals(row.get("assignment_id"),before.get("assignment_id"))||!row.get("round_id").equals(before.get("round_id")))return false;t.db.queryForList("SELECT id FROM ticket_assignment WHERE id=? FOR UPDATE",row.get("assignment_id"));if(transfers.sourceQualified(row))return false;String op=Transfers.stable("reopen-invalid-assignment:"+row.get("assignment_id"));if(t.db.queryForObject("SELECT COUNT(*) FROM ticket_action_operation WHERE operation_id=?",Long.class,op)>0)return false;Instant time=new Completion(t,access).now();var actor=new Tickets.Actor(Tickets.id(row.get("tenant_id")),0,"SYSTEM",null,null,op,System.currentTimeMillis()+5000,null);transfers.terminate(row,actor,op,"SOURCE_ASSIGNMENT_RELEASED",time);transfers.coordinate(row,null,"SOURCE_INELIGIBLE",time);t.db.update("UPDATE ticket_record SET assignment_id=NULL,department_id=NULL,engineer_user_id=NULL,status='WAITING_ASSIGNMENT',row_version=row_version+1 WHERE id=?",id);String record=UUID.randomUUID().toString();var out=new LinkedHashMap<String,Object>();out.putAll(Map.of("operationId",op,"action","reopen-release-invalid","state","COMPLETED","ticketId",id,"roundId",row.get("round_id"),"status","WAITING_ASSIGNMENT","rowVersion",Long.toString(Tickets.id(row.get("row_version"))+1),"recordId",record));out.put("assignmentId",null);new Reopens(t,access).write(row,0,op,"reopen-release-invalid",Tickets.hash(Map.of("assignmentId",row.get("assignment_id"))),out,record,row.get("round_id"),row.get("assignment_id"),"待接工程师资格已失效，已退回客服待分派协调。",time);return true;}));}
}
