package com.ias.identity.bootstrap;
import com.ias.identity.auth.AuthCrypto;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
/** Operator-selected manifest administrators only; never inferred from session.revoke or run on startup. */
public final class GrantAssetMain {
 public static void main(String[]args){
  try(var c=DriverManager.getConnection("jdbc:mysql://127.0.0.1:3307/ias_identity_test?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",System.getenv("IAS_MIGRATION_USER"),System.getenv("IAS_MIGRATION_PASSWORD"))){
   try(var s=c.createStatement();var r=s.executeQuery("SELECT @@port,DATABASE(),@@datadir")){r.next();if(r.getInt(1)!=3307||!"ias_identity_test".equals(r.getString(2))||!"C:/ias-local/Develop/IAS-MySQL84/data/".equalsIgnoreCase(r.getString(3).replace('\\','/')))throw new IllegalStateException();}
   var plan=JsonMapper.builder().build().readValue(Files.readString(Path.of(System.getenv("IAS_BOOTSTRAP_PLAN"))),AccountInitializer.Plan.class);
   c.setAutoCommit(false);int changed=0;
   try{
    var tenants=plan.users().stream().filter(u->u.administrator()&&u.realm().equals("TENANT")).sorted(Comparator.comparing(AccountInitializer.UserSpec::tenantCode).thenComparing(AccountInitializer.UserSpec::account)).toList();
    for(var user:tenants){long tenant,id,version;
     try(var s=c.prepareStatement("SELECT id FROM tenant WHERE code_normalized=? FOR UPDATE")){s.setString(1,AuthCrypto.tenantCode(user.tenantCode()));try(var r=s.executeQuery()){if(!r.next())throw new IllegalStateException();tenant=r.getLong(1);}}
     try(var s=c.prepareStatement("SELECT id,security_version,identity_type FROM identity_user WHERE tenant_id=? AND account_normalized=? FOR UPDATE")){s.setLong(1,tenant);s.setString(2,AuthCrypto.account(user.account()));try(var r=s.executeQuery()){if(!r.next()||!r.getString(3).equals("EMPLOYEE"))throw new IllegalStateException();id=r.getLong(1);version=r.getLong(2);}}
     boolean inserted=false;
     for(String code:List.of("asset.read","asset.manage","identity.customer.provision","ticket.read")){
      try(var s=c.prepareStatement("SELECT enabled FROM identity_user_capability WHERE user_id=? AND capability_code=?")){s.setLong(1,id);s.setString(2,code);try(var r=s.executeQuery()){if(r.next()){if(r.getInt(1)!=1)throw new IllegalStateException();continue;}}}
      try(var s=c.prepareStatement("INSERT INTO identity_user_capability(user_id,capability_code,enabled,created_at,updated_at) VALUES(?,?,1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))")){s.setLong(1,id);s.setString(2,code);s.executeUpdate();inserted=true;}
     }
     if(inserted){if(version==Long.MAX_VALUE)throw new IllegalStateException();try(var s=c.prepareStatement("UPDATE identity_user SET security_version=security_version+1,row_version=row_version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=?")){s.setLong(1,id);s.executeUpdate();}
      try(var s=c.prepareStatement("INSERT INTO identity_security_audit(occurred_at,action,actor_type,target_type,target_id,tenant_id,result,reason_code,user_security_version_before,user_security_version_after,trace_id) VALUES(UTC_TIMESTAMP(3),'ASSET_TICKET_CAPABILITIES_GRANTED','SYSTEM','USER',?,?,'SUCCESS','EXPLICIT_MANIFEST',?,?,?)")){s.setLong(1,id);s.setLong(2,tenant);s.setLong(3,version);s.setLong(4,version+1);s.setString(5,UUID.randomUUID().toString());s.executeUpdate();}changed++;
     }
    }
    c.commit();System.out.println("{\"state\":\"COMMITTED\",\"changedUsers\":"+changed+"}");
   }catch(Exception e){c.rollback();throw e;}
  }catch(Exception e){System.err.println("Explicit asset capability grant not confirmed; no password reset performed.");System.exit(1);}
 }
}

