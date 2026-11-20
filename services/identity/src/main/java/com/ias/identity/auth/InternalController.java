package com.ias.identity.auth;

import com.ias.trust.*;
import jakarta.servlet.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

@RestController
public class InternalController {
 private final AuthService auth;private final JdbcTemplate db;private final TransactionTemplate tx;private final PasswordEncoder encoder;
 public InternalController(AuthService auth,JdbcTemplate db,TransactionTemplate tx,PasswordEncoder encoder){this.auth=auth;this.db=db;this.tx=tx;this.encoder=encoder;}
 @PostMapping("/internal/auth/introspect") public Map<String,Object> introspect(@RequestBody Map<String,Object> b,HttpServletRequest r){return auth.introspect(text(b,"sessionToken"),text(b,"operation"),(String)b.get("csrfToken"),Chain.nanoDeadline(Chain.deadline(r)));}
 @PostMapping("/internal/identity/customer-enrollments/{action}") public Map<String,Object> customer(@PathVariable String action,@RequestBody Map<String,Object> b,HttpServletRequest r){
  String op=text(b,"operationId");try{if(!UUID.fromString(op).toString().equals(op))throw new IllegalArgumentException();}catch(Exception e){throw new Problem(400,"VALIDATION_ERROR");}
  if(!Set.of("create","query","activate").contains(action))throw new Problem(403,"ACCESS_DENIED");
  long deadline=Chain.deadline(r);Map<String,Object> actor=authorized(b,action.equals("query")?"PROVISION_READ":"PROVISION",deadline);
  long tenant=Long.parseLong((String)actor.get("tenantId")),user=Long.parseLong((String)actor.get("userId"));
  String account=action.equals("create")?AuthCrypto.account(text(b,"account")):null,name=action.equals("create")?text(b,"displayName"):null;
  List<Map<String,Object>> existing=db.queryForList("SELECT * FROM identity_customer_enrollment WHERE operation_id=?",op);
  String verifier=null;
  if(action.equals("create")&&existing.isEmpty()){String password=text(b,"initialPassword");AuthCrypto.newPassword(password);if(AuthCrypto.codePoints(name)<1||AuthCrypto.codePoints(name)>50)throw new Problem(400,"VALIDATION_ERROR");verifier=encoder.encode(password);}
  final String encoded=verifier;
  return tx.execute(status->{
   db.queryForList("SELECT id FROM tenant WHERE id=? FOR UPDATE",tenant);db.queryForList("SELECT id FROM identity_user WHERE id=? FOR UPDATE",user);
   authorized(b,action.equals("query")?"PROVISION_READ":"PROVISION",deadline);Chain.fresh(actor,deadline);
   List<Map<String,Object>> rows=db.queryForList("SELECT * FROM identity_customer_enrollment WHERE operation_id=? FOR UPDATE",op);
   if(rows.isEmpty()){
    if(!action.equals("create"))throw new Problem(404,"TARGET_NOT_ACCESSIBLE");
    if(!db.queryForList("SELECT id FROM identity_user WHERE login_namespace_id=? AND account_normalized=?","TENANT:"+tenant,account).isEmpty())throw new Problem(409,"ACCOUNT_CONFLICT");
    if(encoded==null)throw new Problem(503,"OPERATION_RESULT_UNKNOWN");
    db.update("INSERT INTO identity_user(realm,tenant_id,account_normalized,identity_type,display_name,password_verifier,status,must_change_password,binding_state,created_at,updated_at) VALUES('TENANT',?,?,'CUSTOMER',?,?,1,1,1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",tenant,account,name,encoded);
    long id=db.queryForObject("SELECT id FROM identity_user WHERE login_namespace_id=? AND account_normalized=?",Long.class,"TENANT:"+tenant,account);
    db.update("INSERT INTO identity_customer_enrollment VALUES(?,?,?,?,? ,?,'PENDING',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",op,user,tenant,account,name,id);
    audit("CUSTOMER_PENDING",id,user,tenant,1,1,Chain.trace(r));rows=db.queryForList("SELECT * FROM identity_customer_enrollment WHERE operation_id=?",op);
   }
   Map<String,Object> row=rows.getFirst();
   if(((Number)row.get("actor_user_id")).longValue()!=user||((Number)row.get("tenant_id")).longValue()!=tenant)throw new Problem(404,"TARGET_NOT_ACCESSIBLE");
   if(action.equals("create")&&(!account.equals(row.get("account_normalized"))||!name.equals(row.get("display_name"))))throw new Problem(409,"IDEMPOTENCY_CONFLICT");
   long target=((Number)row.get("user_id")).longValue();
   if(action.equals("activate")&&!row.get("state").equals("BOUND")){
    Map<String,Object> proof=Chain.call("identity-asset","/internal/asset/enrollment-evidence",Map.of("operationId",op,"userId",Long.toString(target),"tenantId",Long.toString(tenant),"actorUserId",Long.toString(user)),Chain.trace(r),deadline);
    if(!Boolean.TRUE.equals(proof.get("ready")))throw new Problem(409,"INVALID_STATE");
    Map<String,Object> targetRow=db.queryForMap("SELECT security_version,binding_state FROM identity_user WHERE id=? FOR UPDATE",target);long version=((Number)targetRow.get("security_version")).longValue();
    if(version==Long.MAX_VALUE||((Number)targetRow.get("binding_state")).intValue()!=1)throw new Problem(409,"INVALID_STATE");
    db.update("UPDATE identity_user SET binding_state=2,security_version=security_version+1,row_version=row_version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=?",target);
    db.update("UPDATE identity_customer_enrollment SET state='BOUND',updated_at=UTC_TIMESTAMP(3) WHERE operation_id=?",op);audit("CUSTOMER_BOUND",target,user,tenant,version,version+1,Chain.trace(r));row.put("state","BOUND");
   }
   Chain.remaining(deadline);return Map.of("operationId",op,"userId",Long.toString(target),"state",row.get("state"));
  });
 }
 private Map<String,Object> authorized(Map<String,Object>b,String op,long deadline){return auth.introspect(text(b,"sessionToken"),op,(String)b.get("csrfToken"),Chain.nanoDeadline(deadline));}
 private void audit(String action,long target,long actor,long tenant,long before,long after,String trace){db.update("INSERT INTO identity_security_audit(occurred_at,action,actor_type,actor_user_id,actor_namespace_id,target_type,target_id,tenant_id,result,reason_code,user_security_version_before,user_security_version_after,trace_id) VALUES(UTC_TIMESTAMP(3),?,'USER',?,?,'USER',?,?,'SUCCESS','CUSTOMER_PROVISIONING',?,?,?)",action,actor,"TENANT:"+tenant,target,tenant,before,after,trace);}
 private static String text(Map<String,Object>b,String key){Object v=b.get(key);if(!(v instanceof String s)||s.isEmpty())throw new Problem(400,"VALIDATION_ERROR");return s;}
}
