package com.ias.approval;
import com.ias.trust.Chain;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
/** Controlled separate operator CLI, never invoked on Boot or supplied from HTTP. */
public final class FinanceFeeRulePublisher {
 public record Plan(String operationId,String tenantId,String version,String thresholdCents){}
 static long amount(String value,boolean positive){if(value==null||!value.matches("0|[1-9][0-9]{0,18}"))throw new IllegalArgumentException();long n=Long.parseLong(value);if(positive&&n==0)throw new IllegalArgumentException();return n;}
 public static void main(String[] args){try{
  if(args.length!=1)throw new IllegalArgumentException();Path planPath=Path.of(args[0]);if(Files.size(planPath)>8192)throw new IllegalArgumentException();Plan plan=Chain.JSON.readValue(Files.readString(planPath),Plan.class);
  var props=new Properties();try(var in=Files.newInputStream(Path.of(System.getenv("IAS_FEE_RULE_OPERATOR_CONFIG")))){props.load(in);}
  String url=props.getProperty("jdbcUrl","");if(!url.matches("jdbc:mysql://127\\.0\\.0\\.1:3307/ias_approval_test(?:\\?[^#]*)?"))throw new IllegalArgumentException();
  try(var c=DriverManager.getConnection(url,props.getProperty("user"),props.getProperty("password"))){try(var s=c.createStatement();var r=s.executeQuery("SELECT @@port,@@datadir,DATABASE()")){r.next();if(r.getInt(1)!=3307||!"ias_approval_test".equals(r.getString(3))||!r.getString(2).replace('\\','/').equalsIgnoreCase("C:/ias-local/Develop/IAS-MySQL84/data/"))throw new IllegalStateException();}System.out.println(Chain.JSON.writeValueAsString(publish(c,plan,props.getProperty("operatorReference"))));}
 }catch(Exception e){System.err.println("FEE_RULE_PUBLICATION_NOT_CONFIRMED; reconcile the original immutable operation and plan");System.exit(1);}}
 public static Map<String,Object> publish(Connection c,Plan p,String operator)throws Exception{
  if(p==null||!UUID.fromString(p.operationId()).toString().equals(p.operationId())||p.version()==null||!p.version().matches("[A-Za-z0-9_.-]{1,64}")||operator==null||operator.isBlank()||operator.length()>100)throw new IllegalArgumentException();long tenant=amount(p.tenantId(),true),threshold=amount(p.thresholdCents(),false);
  if(!c.getAutoCommit())throw new IllegalStateException("Dedicated physical connection required");c.setAutoCommit(false);boolean committing=false;
  try{
   try(var q=c.prepareStatement("SELECT tenant_id,rule_version,threshold_cents,operator_reference FROM approval_fee_rule_publication WHERE operation_id=?")){q.setString(1,p.operationId());try(var r=q.executeQuery()){if(r.next()){if(r.getLong(1)!=tenant||!r.getString(2).equals(p.version())||r.getLong(3)!=threshold||!r.getString(4).equals(operator))throw new IllegalArgumentException();c.rollback();return receipt(p);}}}
   try(var q=c.prepareStatement("INSERT INTO approval_fee_rule_publication(tenant_id,rule_version,threshold_cents,operation_id,operator_reference,published_at) VALUES(?,?,?,?,?,?)")){q.setLong(1,tenant);q.setString(2,p.version());q.setLong(3,threshold);q.setString(4,p.operationId());q.setString(5,operator);q.setTimestamp(6,Timestamp.from(Instant.now()));q.executeUpdate();}committing=true;c.commit();return receipt(p);
  }catch(Exception failure){try{c.rollback();}catch(Exception rollback){failure.addSuppressed(rollback);try{c.abort(Runnable::run);}catch(Exception abort){failure.addSuppressed(abort);}}if(committing)try{c.abort(Runnable::run);}catch(Exception abort){failure.addSuppressed(abort);}throw failure;}
 }
 static Map<String,Object> receipt(Plan p){return Map.of("operationId",p.operationId(),"tenantId",p.tenantId(),"version",p.version(),"thresholdCents",p.thresholdCents(),"state","PUBLISHED");}
}
