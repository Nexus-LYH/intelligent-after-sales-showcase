package com.ias.asset;
import com.ias.trust.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
/** Tenant-scoped asset.manage retains the approved asset ownership boundary; clients cannot choose tenant/actor. */
public final class MembershipRoleService {
 private final JdbcTemplate db;private final TransactionTemplate tx;private final MembershipRoleAccess access;
 public MembershipRoleService(JdbcTemplate db,TransactionTemplate tx,MembershipRoleAccess access){this.db=db;this.tx=tx;this.access=access;}
 static Problem invalid(){return new Problem(400,"VALIDATION_ERROR");}
 static String uuid(Object v){try{String s=(String)v;if(!UUID.fromString(s).toString().equals(s))throw invalid();return s;}catch(Exception e){throw invalid();}}
 static long positive(Object v){if(!(v instanceof String s)||!s.matches("[1-9][0-9]{0,18}"))throw invalid();try{return Long.parseLong(s);}catch(Exception e){throw invalid();}}
 static String digest(long company,long member,long expected,String role){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Chain.JSON.writeValueAsBytes(List.of("MEMBERSHIP_ROLE_V1",Long.toString(company),Long.toString(member),Long.toString(expected),role))));}catch(Exception e){throw new IllegalStateException();}}
 Map<String,Object> company(MembershipRoleAccess.Actor a,long company,boolean lock){var rows=db.queryForList("SELECT id,status FROM asset_company WHERE tenant_id=? AND id=?"+(lock?" FOR UPDATE":""),a.tenant(),company);if(rows.isEmpty())throw new Problem(404,"TARGET_NOT_ACCESSIBLE");return rows.getFirst();}
 Map<String,Object> member(MembershipRoleAccess.Actor a,long company,long member){var rows=db.queryForList("SELECT * FROM asset_membership WHERE tenant_id=? AND company_id=? AND id=?",a.tenant(),company,member);if(rows.isEmpty())throw new Problem(404,"TARGET_NOT_ACCESSIBLE");return rows.getFirst();}
 String role(long tenant,long company,long member){var rows=db.queryForList("SELECT role_code FROM asset_membership_role WHERE tenant_id=? AND company_id=? AND membership_id=?",String.class,tenant,company,member);return rows.isEmpty()?"MEMBER":rows.getFirst();}
 public Map<String,Object> view(MembershipRoleAccess.Actor a,long company,long member){access.current(a,false);company(a,company,false);var m=member(a,company,member);return Map.of("membershipId",Long.toString(member),"companyId",Long.toString(company),"userId",m.get("user_id").toString(),"role",role(a.tenant(),company,member),"state",m.get("state"),"rowVersion",m.get("row_version").toString());}
 Map<String,Object> old(MembershipRoleAccess.Actor a,long company,String operation,String digest){var rows=db.queryForList("SELECT * FROM asset_membership_role_operation WHERE operation_id=?",operation);if(rows.isEmpty())return null;var p=rows.getFirst();if(((Number)p.get("tenant_id")).longValue()!=a.tenant()||((Number)p.get("company_id")).longValue()!=company||((Number)p.get("actor_user_id")).longValue()!=a.user())throw new Problem(404,"OPERATION_NOT_FOUND");if(digest!=null&&!digest.equals(p.get("command_digest")))throw new Problem(409,"IDEMPOTENCY_CONFLICT");return Chain.JSON.readValue(p.get("result_json").toString(),Map.class);}
 public Map<String,Object> operation(MembershipRoleAccess.Actor a,long company,String operation){access.current(a,false);company(a,company,false);var p=old(a,company,uuid(operation),null);if(p==null)throw new Problem(404,"OPERATION_NOT_FOUND");return p;}
 public Map<String,Object> change(MembershipRoleAccess.Actor a,long company,long member,String operation,long expected,String requested){
  uuid(operation);if(expected<1||!Set.of("ADMIN","MEMBER").contains(requested))throw invalid();String digest=digest(company,member,expected,requested);access.current(a,true);
  return tx.execute(status->{
   var c=company(a,company,true);access.current(a,true);var previous=old(a,company,operation,digest);if(previous!=null)return previous;
   if(!"ENABLED".equals(c.get("status")))throw new Problem(409,"COMPANY_DISABLED");var m=member(a,company,member);long version=((Number)m.get("row_version")).longValue(),target=((Number)m.get("user_id")).longValue();if(version!=expected||version==Long.MAX_VALUE)throw new Problem(409,"VERSION_CONFLICT");String before=role(a.tenant(),company,member);
   if(requested.equals("ADMIN")&&(!"ACTIVE".equals(m.get("state"))||!access.eligible(a,List.of(target)).contains(target)))throw new Problem(409,"CUSTOMER_ADMIN_INELIGIBLE");
   if(before.equals("ADMIN")&&requested.equals("MEMBER")&&"ACTIVE".equals(m.get("state"))){var other=db.queryForList("SELECT m.user_id FROM asset_membership m JOIN asset_membership_role r ON r.membership_id=m.id AND r.tenant_id=m.tenant_id AND r.company_id=m.company_id WHERE m.tenant_id=? AND m.company_id=? AND m.state='ACTIVE' AND r.role_code='ADMIN' AND m.id<>? ORDER BY m.user_id",Long.class,a.tenant(),company,member);if(access.eligible(a,other).isEmpty())throw new Problem(409,"LAST_ENTERPRISE_ADMIN");}
   var at=Timestamp.from(Instant.now());if(db.update("UPDATE asset_membership SET row_version=row_version+1,updated_at=? WHERE id=? AND tenant_id=? AND company_id=? AND row_version=?",at,member,a.tenant(),company,expected)!=1)throw new Problem(409,"VERSION_CONFLICT");
   if(db.update("UPDATE asset_membership_role SET role_code=?,operation_id=?,updated_at=? WHERE membership_id=? AND tenant_id=? AND company_id=?",requested,operation,at,member,a.tenant(),company)==0)db.update("INSERT INTO asset_membership_role(tenant_id,company_id,membership_id,role_code,operation_id,updated_at) VALUES(?,?,?,?,?,?)",a.tenant(),company,member,requested,operation,at);
   Map<String,Object> result=Map.of("operationId",operation,"companyId",Long.toString(company),"membershipId",Long.toString(member),"userId",Long.toString(target),"role",requested,"rowVersion",Long.toString(version+1),"state","COMPLETED");
   db.update("INSERT INTO asset_membership_role_operation(operation_id,tenant_id,company_id,membership_id,actor_user_id,command_digest,result_json,recorded_at) VALUES(?,?,?,?,?,?,?,?)",operation,a.tenant(),company,member,a.user(),digest,Chain.JSON.writeValueAsString(result),at);
   db.update("INSERT INTO asset_membership_role_audit(operation_id,tenant_id,company_id,membership_id,actor_user_id,previous_role,resulting_role,occurred_at) VALUES(?,?,?,?,?,?,?,?)",operation,a.tenant(),company,member,a.user(),before,requested,at);return result;
  });
 }
 public static boolean administrator(JdbcTemplate db,long tenant,long company,long user){return db.queryForObject("SELECT COUNT(*) FROM asset_membership m JOIN asset_company c ON c.tenant_id=m.tenant_id AND c.id=m.company_id JOIN asset_membership_role r ON r.tenant_id=m.tenant_id AND r.company_id=m.company_id AND r.membership_id=m.id WHERE m.tenant_id=? AND m.company_id=? AND m.user_id=? AND m.state='ACTIVE' AND c.status='ENABLED' AND r.role_code='ADMIN'",Long.class,tenant,company,user)==1;}
}
