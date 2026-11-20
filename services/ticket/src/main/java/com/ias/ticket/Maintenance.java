package com.ias.ticket;
import com.ias.trust.*;
import org.springframework.stereotype.Service;
import java.time.*;
import java.sql.Timestamp;
import java.nio.file.*;
import java.util.*;
@Service
public class Maintenance {
 final Tickets t;final FileStore files;
 public Maintenance(Tickets t,FileStore files){this.t=t;this.files=files;}
 public List<Map<String,Object>> run(Instant now,boolean execute,Set<String> ids){
  if(execute&&ids.isEmpty())throw new IllegalArgumentException("Explicit scoped IDs required for execution");var actions=new ArrayList<Map<String,Object>>();
  var selectedIds = ids.toArray();
  String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
  String operationScope = ids.isEmpty() ? "" : " AND operation_id IN (" + placeholders + ")";
  String fileScope = ids.isEmpty() ? "" : " AND id IN (" + placeholders + ")";
  String discoveryLimit = ids.isEmpty() ? " LIMIT 1000" : "";
  var ops=t.db.queryForList("SELECT * FROM ticket_create_operation WHERE state IN ('REGISTERED','RESERVED','COMMITTED','ABORTED')"+operationScope+" ORDER BY created_at"+discoveryLimit,selectedIds);
  for(var o:ops){String id=o.get("operation_id").toString();if(!ids.isEmpty()&&!ids.contains(id))continue;String state=o.get("state").toString();boolean idle=Instant.parse(Tickets.utc(o.get("updated_at"))).plusSeconds(86400).compareTo(now)<=0;boolean active=o.get("lease_until")!=null&&Instant.parse(Tickets.utc(o.get("lease_until"))).isAfter(Instant.now());String action=state.equals("COMMITTED")?"CONFIRM_COMMITTED":state.equals("ABORTED")?"RELEASE_ABORTED":idle&&!active?"ABORT_IDLE":"KEEP_ACTIVE_OR_YOUNG";actions.add(Map.of("operationId",id,"action",action));if(!execute||action.startsWith("KEEP"))continue;
   if(action.equals("ABORT_IDLE"))t.tx.executeWithoutResult(s->{t.lockActor(Tickets.id(o.get("tenant_id")),Tickets.id(o.get("actor_user_id")));var locked=t.db.queryForMap("SELECT * FROM ticket_create_operation WHERE operation_id=? FOR UPDATE",id);if(!Set.of("REGISTERED","RESERVED").contains(locked.get("state"))||locked.get("lease_until")!=null&&Instant.parse(Tickets.utc(locked.get("lease_until"))).isAfter(Instant.now())||Instant.parse(Tickets.utc(locked.get("updated_at"))).plusSeconds(86400).isAfter(now))return;t.db.update("UPDATE ticket_create_operation SET state='ABORTED',reason='IDLE_EXPIRED',lease_id=NULL,lease_until=NULL,row_version=row_version+1,updated_at=UTC_TIMESTAMP(3) WHERE operation_id=?",id);t.audit(locked,"CREATE_ABORTED");});
   var latest=t.db.queryForMap("SELECT * FROM ticket_create_operation WHERE operation_id=?",id);long deadline=System.currentTimeMillis()+10000;Chain.begin(deadline);try{if(latest.get("state").equals("COMMITTED"))t.confirm(latest,UUID.randomUUID().toString(),deadline);else if(latest.get("state").equals("ABORTED"))t.release(latest,UUID.randomUUID().toString(),deadline);}catch(Problem p){actions.add(Map.of("operationId",id,"action","COORDINATION_PENDING","code",p.code));}finally{Chain.end();}
  }
  var rows=t.db.queryForList("SELECT * FROM ticket_file WHERE state<>'DELETED'"+fileScope+" ORDER BY created_at"+discoveryLimit,selectedIds);
  for(var f:rows){String id=f.get("id").toString();if(!ids.isEmpty()&&!ids.contains(id))continue;boolean eligible=Instant.parse(Tickets.utc(f.get("updated_at"))).plusSeconds(86400).compareTo(now)<=0;boolean live=f.get("lease_until")!=null&&Instant.parse(Tickets.utc(f.get("lease_until"))).isAfter(Instant.now());long refs=t.db.queryForObject("SELECT COUNT(*) FROM ticket_file_reference WHERE file_id=?",Long.class,id);long unknown=t.db.queryForObject("SELECT COUNT(*) FROM ticket_create_operation WHERE operation_id=? AND state IN ('REGISTERED','RESERVED','COMMITTED')",Long.class,f.get("submission_id"));String action=refs>0?"KEEP_REFERENCED":live?"KEEP_ACTIVE_UPLOAD":unknown>0?"KEEP_UNKNOWN_OPERATION":f.get("state").equals("UPLOADING")?"FENCE_FAILED_UPLOAD":f.get("state").equals("DELETING")?"RETRY_DELETE":eligible?"DELETE_UNREFERENCED":"KEEP_YOUNG";actions.add(Map.of("fileId",id,"action",action));if(!execute||action.startsWith("KEEP"))continue;
   if(action.equals("FENCE_FAILED_UPLOAD")){
    String oldGeneration=t.tx.execute(s->{t.lockActor(Tickets.id(f.get("tenant_id")),Tickets.id(f.get("user_id")));var op=t.db.queryForList("SELECT state FROM ticket_create_operation WHERE operation_id=? FOR UPDATE",f.get("submission_id"));var locked=t.db.queryForMap("SELECT *,lease_until>UTC_TIMESTAMP(3) alive FROM ticket_file WHERE id=? FOR UPDATE",id);if(!locked.get("state").equals("UPLOADING")||FileStore.truth(locked.get("alive"))||!op.isEmpty()&&Set.of("REGISTERED","RESERVED","COMMITTED").contains(op.getFirst().get("state"))||t.db.queryForObject("SELECT COUNT(*) FROM ticket_file_reference WHERE file_id=?",Long.class,id)>0)return null;String generation=(String)locked.get("generation");t.db.update("UPDATE ticket_file SET state='FAILED',generation=NULL,lease_until=NULL,updated_at=UTC_TIMESTAMP(3) WHERE id=?",id);try{Files.deleteIfExists(FileStore.path(id,null));}catch(java.io.IOException ignored){}t.db.update("INSERT INTO ticket_audit(operation_id,tenant_id,actor_user_id,action,occurred_at) VALUES(?,?,?,'FILE_UPLOAD_FENCED',UTC_TIMESTAMP(3))",locked.get("submission_id"),locked.get("tenant_id"),locked.get("user_id"));return generation;});
    if(oldGeneration!=null)try{Files.deleteIfExists(FileStore.path(id,oldGeneration));}catch(java.io.IOException ignored){}continue;
   }
   boolean deleting=Boolean.TRUE.equals(t.tx.execute(s->{t.lockActor(Tickets.id(f.get("tenant_id")),Tickets.id(f.get("user_id")));var op=t.db.queryForList("SELECT state FROM ticket_create_operation WHERE operation_id=? FOR UPDATE",f.get("submission_id"));var locked=t.db.queryForMap("SELECT *,lease_until>UTC_TIMESTAMP(3) alive FROM ticket_file WHERE id=? FOR UPDATE",id);if(FileStore.truth(locked.get("alive"))||!op.isEmpty()&&Set.of("REGISTERED","RESERVED","COMMITTED").contains(op.getFirst().get("state"))||t.db.queryForObject("SELECT COUNT(*) FROM ticket_file_reference WHERE file_id=?",Long.class,id)>0)return false;if(!locked.get("state").equals("DELETING")&&Instant.parse(Tickets.utc(locked.get("updated_at"))).plusSeconds(86400).isAfter(now))return false;t.db.update("UPDATE ticket_file SET state='DELETING',generation=NULL,lease_until=NULL,updated_at=UTC_TIMESTAMP(3) WHERE id=?",id);t.db.update("INSERT INTO ticket_audit(operation_id,tenant_id,actor_user_id,action,occurred_at) VALUES(?,?,?,'FILE_CLEANUP_FENCED',UTC_TIMESTAMP(3))",locked.get("submission_id"),locked.get("tenant_id"),locked.get("user_id"));return true;}));
   if(deleting){files.remove(id);try(var paths=Files.newDirectoryStream(FileStore.ROOT,id+".*.part")){for(Path p:paths)if(!Files.isSymbolicLink(p))try{Files.deleteIfExists(p);}catch(java.io.IOException ignored){}}catch(java.io.IOException ignored){}}
  }
  // Unknown disk files are never guessed to be unused: quarantine/report only.
  try(var paths=Files.newDirectoryStream(FileStore.ROOT)){for(Path p:paths){String name=p.getFileName().toString();if(!name.matches("[0-9a-f-]{36}(?:\\.[0-9a-f-]{36}\\.part|\\.bin)"))continue;String id=name.substring(0,36);if(!ids.isEmpty()&&!ids.contains(id))continue;if(t.db.queryForObject("SELECT (SELECT COUNT(*) FROM ticket_file WHERE id=?)+(SELECT COUNT(*) FROM ticket_finance_voucher WHERE file_id=?)",Long.class,id,id)==0)actions.add(Map.of("fileId",id,"action","ORPHAN_REQUIRES_RECONCILIATION"));}}catch(java.io.IOException e){actions.add(Map.of("action","DISK_SCAN_UNAVAILABLE"));}
  for(var f:t.db.queryForList("SELECT * FROM ticket_file WHERE state IN ('READY','FAILED','DELETING','DELETED')"+fileScope+" ORDER BY created_at"+discoveryLimit,selectedIds)){
   String id=f.get("id").toString();if(!ids.isEmpty()&&!ids.contains(id))continue;
   if(f.get("state").equals("READY")&&!Files.isRegularFile(FileStore.path(id,null),LinkOption.NOFOLLOW_LINKS))actions.add(Map.of("fileId",id,"action","MISSING_BINARY_REQUIRES_RECONCILIATION"));
   try(var parts=Files.newDirectoryStream(FileStore.ROOT,id+".*.part")){for(Path p:parts){String name=p.getFileName().toString();if(!name.matches("[0-9a-f-]{36}\\.[0-9a-f-]{36}\\.part")||Files.isSymbolicLink(p))continue;String generation=name.substring(37,73);actions.add(Map.of("fileId",id,"action","FENCED_TEMP_RECONCILIATION"));if(execute)t.tx.executeWithoutResult(s->{var locked=t.db.queryForMap("SELECT state,generation FROM ticket_file WHERE id=? FOR UPDATE",id);if(locked.get("state").equals("UPLOADING")&&generation.equals(locked.get("generation")))return;try{Files.deleteIfExists(p);}catch(java.io.IOException ignored){}});}}
   catch(java.io.IOException e){actions.add(Map.of("fileId",id,"action","TEMP_DELETE_PENDING"));}
  }
  return actions;
 }
}


