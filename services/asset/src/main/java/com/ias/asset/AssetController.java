package com.ias.asset;

import com.ias.trust.*;
import jakarta.servlet.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

@RestController
public class AssetController {
 private final JdbcTemplate db;private final TransactionTemplate tx;private final AssetMapper mapper;
 public AssetController(JdbcTemplate db,TransactionTemplate tx,AssetMapper mapper){this.db=db;this.tx=tx;this.mapper=mapper;}
 private record Actor(long tenant,long user,String type,String token,String csrf,String trace,long deadline,Map<String,Object> identity){@Override public String toString(){return "AssetActor[redacted]";}}
 private Actor actor(HttpServletRequest r,String operation){
  if(!r.getMethod().equals("GET"))Chain.source(r);
  String token=Chain.cookie(r),csrf=r.getHeader("X-CSRF-TOKEN");long deadline=Chain.deadline(r);
  var identity=Chain.identity("asset-identity",token,operation,csrf,Chain.trace(r),deadline);Chain.fresh(identity,deadline);
  return new Actor(id(identity.get("tenantId")),id(identity.get("userId")),(String)identity.get("identityType"),token,csrf,Chain.trace(r),deadline,identity);
 }
 private void fresh(Actor a,String operation){Chain.identity("asset-identity",a.token,operation,a.csrf,a.trace,a.deadline);Chain.remaining(a.deadline);}
 private String table(String kind){return switch(kind){case "customer-companies"->"asset_company";case "product-models"->"asset_model";case "devices"->"asset_device";default->throw new Problem(404,"TARGET_NOT_ACCESSIBLE");};}
 @GetMapping("/api/asset/{kind}") public Map<String,Object> list(@PathVariable String kind,HttpServletRequest r){
  Actor a=actor(r,"ASSET_READ");String table=table(kind);if(a.type.equals("CUSTOMER")&&kind.equals("product-models"))throw new Problem(403,"ACCESS_DENIED");
  for(var e:r.getParameterMap().entrySet())if(!Set.of("page","size","keyword","status","companyId","modelId").contains(e.getKey())||e.getValue().length!=1)throw bad();
  int page=number(r.getParameter("page"),1),size=number(r.getParameter("size"),20);if(page>1000000||!Set.of(20,50,100).contains(size))throw bad();
  List<Object> args=new ArrayList<>();args.add(a.tenant);String where=" o.tenant_id=?";
  if(a.type.equals("CUSTOMER")){String company=kind.equals("devices")?"o.company_id":"o.id";where+=" AND EXISTS(SELECT 1 FROM asset_membership m WHERE m.tenant_id=o.tenant_id AND m.user_id=? AND m.company_id="+company+" AND m.state='ACTIVE')";args.add(a.user);}
  String keyword=r.getParameter("keyword"),status=r.getParameter("status");
  if(keyword!=null&&!keyword.isEmpty()){if(keyword.codePointCount(0,keyword.length())>100)throw bad();where+=" AND (o.name LIKE ? ESCAPE '!' OR o.code LIKE ? ESCAPE '!')";String q="%"+keyword.replace("!","!!").replace("%","!%").replace("_","!_")+"%";args.add(q);args.add(q);}
  if(status!=null){if(!Set.of("ENABLED","DISABLED").contains(status))throw bad();where+=" AND o.status=?";args.add(status);}
  for(String filter:List.of("companyId","modelId"))if(r.getParameter(filter)!=null){if(!kind.equals("devices"))throw bad();where+=" AND o."+(filter.equals("companyId")?"company_id":"model_id")+"=?";args.add(id(r.getParameter(filter)));}
  long total=db.queryForObject("SELECT COUNT(*) FROM "+table+" o WHERE "+where,Long.class,args.toArray());args.add(size);args.add((page-1L)*size);
  return Map.of("items",db.queryForList("SELECT o.* FROM "+table+" o WHERE "+where+" ORDER BY o.id DESC LIMIT ? OFFSET ?",args.toArray()).stream().map(this::dto).toList(),"total",total,"page",page,"size",size);
 }
 @GetMapping("/api/asset/{kind}/{objectId}") public Map<String,Object> detail(@PathVariable String kind,@PathVariable String objectId,HttpServletRequest r){Actor a=actor(r,"ASSET_READ");String table=table(kind);Map<String,Object> row=row(table,a.tenant,id(objectId));visible(a,kind,row);return dto(row);}
 private void visible(Actor a,String kind,Map<String,Object> row){if(a.type.equals("CUSTOMER")){if(kind.equals("product-models"))throw new Problem(403,"ACCESS_DENIED");long company=((Number)row.get(kind.equals("devices")?"company_id":"id")).longValue();if(db.queryForObject("SELECT COUNT(*) FROM asset_membership WHERE tenant_id=? AND user_id=? AND company_id=? AND state='ACTIVE'",Long.class,a.tenant,a.user,company)==0)throw new Problem(404,"TARGET_NOT_ACCESSIBLE");}}
 @PostMapping("/api/asset/{kind}") public Map<String,Object> create(@PathVariable String kind,@RequestBody Map<String,Object> b,HttpServletRequest r){Actor a=actor(r,"ASSET_WRITE");return write(a,kind,null,false,b,key(r));}
 @PatchMapping("/api/asset/{kind}/{objectId}") public Map<String,Object> edit(@PathVariable String kind,@PathVariable String objectId,@RequestBody Map<String,Object>b,HttpServletRequest r){Actor a=actor(r,"ASSET_WRITE");return write(a,kind,id(objectId),false,b,key(r));}
 @PostMapping("/api/asset/{kind}/{objectId}/status") public Map<String,Object> status(@PathVariable String kind,@PathVariable String objectId,@RequestBody Map<String,Object>b,HttpServletRequest r){Actor a=actor(r,"ASSET_WRITE");if(!Set.of("customer-companies","devices").contains(kind))throw new Problem(403,"ACCESS_DENIED");return write(a,kind,id(objectId),true,b,key(r));}
 private Map<String,Object> write(Actor a,String kind,Long target,boolean status,Map<String,Object>b,String key){
  String table=table(kind),operation=kind+":"+(target==null?"CREATE":status?"STATUS":"EDIT");byte[] digest=digest(operation+":"+target,b);
  try{return tx.execute(t->{
   Map<String,Object> prior=operation(key,a,false);if(prior!=null){if(!MessageDigest.isEqual((byte[])prior.get("request_digest"),digest))throw new Problem(409,"IDEMPOTENCY_CONFLICT");return json(prior.get("result_json"));}
   Map<String,Object> values=new LinkedHashMap<>(),old=null;
   if(target!=null){if(kind.equals("customer-companies"))mapper.lockCompany(a.tenant,target);else db.queryForList("SELECT id FROM "+table+" WHERE tenant_id=? AND id=? FOR UPDATE",a.tenant,target);
    prior=operation(key,a,false);if(prior!=null){if(!MessageDigest.isEqual((byte[])prior.get("request_digest"),digest))throw new Problem(409,"IDEMPOTENCY_CONFLICT");return json(prior.get("result_json"));}
    old=row(table,a.tenant,target);if(id(b.get("expectedVersion"))!=((Number)old.get("row_version")).longValue())throw new Problem(409,"VERSION_CONFLICT");}
   if(status){fields(b,Set.of("expectedVersion","status","reason"));String state=string(b,"status",16,true),reason=string(b,"reason",200,true);if(!Set.of("ENABLED","DISABLED").contains(state)||reason.codePointCount(0,reason.length())<5)throw bad();values.put("status",state);values.put("disabled_reason",state.equals("DISABLED")?reason:null);}
   else{
    Set<String> allowed=new HashSet<>(switch(kind){case "customer-companies"->Set.of("name","contactName","phone","address");case "product-models"->Set.of("name","category","description");default->Set.of("name","location","purchaseDate","warrantyStart","warrantyEnd");});
    if(target==null){allowed.add("code");if(kind.equals("devices"))allowed.addAll(Set.of("serialNumber","companyId","modelId"));}else allowed.add("expectedVersion");fields(b,allowed);if(target!=null&&b.size()==1)throw bad();
    for(String field:allowed){if(!b.containsKey(field)||field.equals("expectedVersion"))continue;Object value;
     if(Set.of("companyId","modelId").contains(field))value=id(b.get(field));
     else if(Set.of("purchaseDate","warrantyStart","warrantyEnd").contains(field)){value=date(b.get(field));}
     else {int max=switch(field){case "code"->32;case "phone"->30;case "contactName","category"->50;case "location","address"->200;case "description"->1000;default->100;};value=string(b,field,max,Set.of("code","name","serialNumber").contains(field));if(field.equals("code")){String code=(String)value;if(!code.matches("[A-Za-z0-9_-]{2,32}"))throw bad();value=code.toUpperCase(Locale.ROOT);}}
     values.put(snake(field),value);
    }
    if(target==null){for(String required:kind.equals("devices")?List.of("code","name","serial_number","company_id","model_id"):List.of("code","name"))if(!values.containsKey(required)||values.get(required)==null)throw bad();}
   }
   if(kind.equals("devices")){
    var merged=new HashMap<String,Object>();if(old!=null)merged.putAll(old);merged.putAll(values);validateDates(merged);
    if(target==null){long company=((Number)values.get("company_id")).longValue(),model=((Number)values.get("model_id")).longValue();mapper.lockCompany(a.tenant,company);mapper.lockModel(a.tenant,model);if(!"ENABLED".equals(row("asset_company",a.tenant,company).get("status"))||!"ENABLED".equals(row("asset_model",a.tenant,model).get("status")))throw new Problem(409,"INVALID_REFERENCE");}
   }
   fresh(a,"ASSET_WRITE");insertOperation(key,a,operation,target,digest,b,"REGISTERED");
   long objectId;
   if(target==null){values.put("tenant_id",a.tenant);String cols=String.join(",",values.keySet());List<Object> args=new ArrayList<>(values.values());String marks=String.join(",",Collections.nCopies(args.size(),"?"));db.update("INSERT INTO "+table+"("+cols+",created_at,updated_at) VALUES("+marks+",UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",args.toArray());objectId=db.queryForObject("SELECT LAST_INSERT_ID()",Long.class);}
   else{String updates=String.join(",",values.keySet().stream().map(c->c+"=?").toList());List<Object> args=new ArrayList<>(values.values());args.add(a.tenant);args.add(target);db.update("UPDATE "+table+" SET "+updates+",row_version=row_version+1,updated_at=UTC_TIMESTAMP(3) WHERE tenant_id=? AND id=?",args.toArray());objectId=target;}
   Map<String,Object> result=dto(row(table,a.tenant,objectId));result.put("operationId",key);audit(a,operation,objectId,key);db.update("UPDATE asset_operation SET state='COMPLETED',result_json=?,updated_at=UTC_TIMESTAMP(3) WHERE operation_id=?",Chain.JSON.writeValueAsString(result),key);Chain.remaining(a.deadline);return result;
  });}catch(org.springframework.dao.DuplicateKeyException e){Map<String,Object> prior=operation(key,a,false);if(prior!=null){if(!MessageDigest.isEqual((byte[])prior.get("request_digest"),digest))throw new Problem(409,"IDEMPOTENCY_CONFLICT");if(prior.get("result_json")!=null)return json(prior.get("result_json"));}throw new Problem(409,"CODE_CONFLICT");}
 }
 @PostMapping("/api/asset/customer-companies/{company}/members") public Map<String,Object> enroll(@PathVariable String company,@RequestBody Map<String,Object>b,HttpServletRequest r){
  Actor a=actor(r,"PROVISION");fields(b,Set.of("account","displayName","initialPassword"));String account=string(b,"account",100,true),name=string(b,"displayName",50,true);if(!account.matches("[A-Za-z0-9._-]{1,100}"))throw bad();account=account.toLowerCase(Locale.ROOT);
  if(b.get("initialPassword")!=null&&!(b.get("initialPassword") instanceof String))throw bad();String password=(String)b.get("initialPassword");
  long companyId=id(company);String key=key(r);Map<String,Object> nonsecret=Map.of("account",account,"displayName",name,"companyId",company);byte[] hash=digest("ENROLL",nonsecret);
  try{tx.executeWithoutResult(t->{mapper.lockCompany(a.tenant,companyId);Map<String,Object> prior=operation(key,a,false);if(prior!=null){if(!MessageDigest.isEqual((byte[])prior.get("request_digest"),hash))throw new Problem(409,"IDEMPOTENCY_CONFLICT");return;}if(password==null||password.codePointCount(0,password.length())<12||password.codePointCount(0,password.length())>128)throw bad();if(!"ENABLED".equals(row("asset_company",a.tenant,companyId).get("status")))throw new Problem(409,"INVALID_STATE");fresh(a,"PROVISION");insertOperation(key,a,"ENROLL",companyId,hash,nonsecret,"REGISTERED");});}
  catch(org.springframework.dao.DuplicateKeyException e){var prior=operation(key,a,true);if(!MessageDigest.isEqual((byte[])prior.get("request_digest"),hash))throw new Problem(409,"IDEMPOTENCY_CONFLICT");}
  return advance(a,key,password);
 }
 @GetMapping("/api/asset/customer-enrollments/{op}") public Map<String,Object> enrollment(@PathVariable String op,HttpServletRequest r){Actor a=actor(r,"PROVISION_READ");return enrollmentView(operation(uuid(op),a,true));}
 @PostMapping("/api/asset/customer-enrollments/{op}/resume") public Map<String,Object> resume(@PathVariable String op,@RequestBody Map<String,Object>b,HttpServletRequest r){if(!b.isEmpty())throw bad();Actor a=actor(r,"PROVISION");operation(uuid(op),a,true);return advance(a,op,null);}
 private Map<String,Object> advance(Actor a,String op,String password){
  Map<String,Object> current=operation(op,a,true);if(!current.get("kind").equals("ENROLL"))throw new Problem(404,"TARGET_NOT_ACCESSIBLE");if(current.get("state").equals("COMPLETED"))return enrollmentView(current);
  Map<String,Object> input=json(current.get("input_json"));long company=((Number)current.get("target_id")).longValue();
  Map<String,Object> request=new HashMap<>();request.put("sessionToken",a.token);request.put("csrfToken",a.csrf);request.put("operationId",op);
  Map<String,Object> identity;
  try{identity=Chain.call("asset-identity","/internal/identity/customer-enrollments/query",request,a.trace,a.deadline);}
  catch(Problem e){
   if(e.status!=404)throw e;
   if(password==null){db.update("UPDATE asset_operation SET state='AWAITING_INITIAL_PASSWORD',updated_at=UTC_TIMESTAMP(3) WHERE operation_id=?",op);return enrollmentView(operation(op,a,true));}
   if(password.codePointCount(0,password.length())<12||password.codePointCount(0,password.length())>128)throw bad();
   request.put("account",input.get("account"));request.put("displayName",input.get("displayName"));request.put("initialPassword",password);
   identity=Chain.call("asset-identity","/internal/identity/customer-enrollments/create",request,a.trace,a.deadline);request.remove("initialPassword");
  }
  String userId=(String)identity.get("userId");
  tx.executeWithoutResult(t->{
   mapper.lockCompany(a.tenant,company);if(!"ENABLED".equals(row("asset_company",a.tenant,company).get("status"))){db.update("UPDATE asset_operation SET state='BLOCKED',user_id=?,updated_at=UTC_TIMESTAMP(3) WHERE operation_id=?",id(userId),op);return;}
   fresh(a,"PROVISION");
   if(db.queryForObject("SELECT COUNT(*) FROM asset_membership WHERE operation_id=?",Long.class,op)==0){db.update("INSERT INTO asset_membership(tenant_id,company_id,user_id,operation_id,state,created_at,updated_at) VALUES(?,?,?,?,'PROVISIONING',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",a.tenant,company,id(userId),op);audit(a,"CUSTOMER_MEMBERSHIP_COMMITTED",id(userId),op);}
   db.update("UPDATE asset_operation SET state='MEMBERSHIP_COMMITTED',user_id=?,updated_at=UTC_TIMESTAMP(3) WHERE operation_id=?",id(userId),op);
  });
  if(operation(op,a,true).get("state").equals("BLOCKED"))return enrollmentView(operation(op,a,true));
  // Hold company fence through activation; reverse evidence is a non-locking current read, not a distributed transaction.
  return tx.execute(t->{
   mapper.lockCompany(a.tenant,company);if(!"ENABLED".equals(row("asset_company",a.tenant,company).get("status"))){db.update("UPDATE asset_operation SET state='BLOCKED',updated_at=UTC_TIMESTAMP(3) WHERE operation_id=?",op);return enrollmentView(operation(op,a,true));}
   fresh(a,"PROVISION");Chain.call("asset-identity","/internal/identity/customer-enrollments/activate",request,a.trace,a.deadline);
   if(db.update("UPDATE asset_membership SET state='ACTIVE',row_version=row_version+1,updated_at=UTC_TIMESTAMP(3) WHERE operation_id=? AND state='PROVISIONING'",op)==1)audit(a,"CUSTOMER_BOUND",id(userId),op);
   Chain.remaining(a.deadline);db.update("UPDATE asset_operation SET state='COMPLETED',updated_at=UTC_TIMESTAMP(3) WHERE operation_id=?",op);return enrollmentView(operation(op,a,true));
  });
 }
 private Map<String,Object> enrollmentView(Map<String,Object> op){
  if(!op.get("kind").equals("ENROLL"))throw new Problem(404,"TARGET_NOT_ACCESSIBLE");Map<String,Object> input=json(op.get("input_json"));Map<String,Object> out=new LinkedHashMap<>(input);
  String state=op.get("state").toString();out.put("operationId",op.get("operation_id"));out.put("state",state);out.put("userId",op.get("user_id")==null?null:op.get("user_id").toString());out.put("nextAction",state.equals("COMPLETED")?"NONE":state.equals("AWAITING_INITIAL_PASSWORD")?"ENTER_INITIAL_PASSWORD":"RESUME");return out;
 }
 @PostMapping("/internal/asset/enrollment-evidence") public Map<String,Object> evidence(@RequestBody Map<String,Object>b){
  String op=uuid((String)b.get("operationId"));long tenant=id(b.get("tenantId")),user=id(b.get("userId")),actor=id(b.get("actorUserId"));
  long count=db.queryForObject("SELECT COUNT(*) FROM asset_operation o JOIN asset_membership m ON m.operation_id=o.operation_id JOIN asset_company c ON c.id=m.company_id AND c.tenant_id=m.tenant_id WHERE o.operation_id=? AND o.tenant_id=? AND o.actor_user_id=? AND o.user_id=? AND m.user_id=? AND o.state IN ('MEMBERSHIP_COMMITTED','COMPLETED') AND c.status='ENABLED' AND m.state IN ('PROVISIONING','ACTIVE')",Long.class,op,tenant,actor,user,user);
  return Map.of("ready",count==1);
 }
 @GetMapping("/api/asset/operations/{op}") public Map<String,Object> operationResult(@PathVariable String op,HttpServletRequest r){Actor a=actor(r,"ASSET_READ");var row=operation(uuid(op),a,true);if(row.get("kind").equals("ENROLL"))actor(r,"PROVISION_READ");return Map.of("operationId",op,"kind",row.get("kind"),"state",row.get("state"),"result",row.get("result_json")==null?Map.of():json(row.get("result_json")));}
 @GetMapping("/api/asset/me/customer-companies") public Map<String,Object> myCompanies(HttpServletRequest r){Actor a=actor(r,"ASSET_READ");if(!a.type.equals("CUSTOMER"))throw new Problem(403,"ACCESS_DENIED");return Map.of("items",db.queryForList("SELECT c.* FROM asset_company c JOIN asset_membership m ON m.tenant_id=c.tenant_id AND m.company_id=c.id WHERE c.tenant_id=? AND m.user_id=? AND m.state='ACTIVE' ORDER BY c.id DESC",a.tenant,a.user).stream().map(this::dto).toList());}
 @GetMapping("/api/asset/customer-companies/{company}/members") public Map<String,Object> members(@PathVariable String company,HttpServletRequest r){for(var entry:r.getParameterMap().entrySet())if(!Set.of("page","size").contains(entry.getKey())||entry.getValue().length!=1)throw bad();Actor a=actor(r,"ASSET_READ");if(!a.type.equals("EMPLOYEE"))throw new Problem(403,"ACCESS_DENIED");long id=id(company);row("asset_company",a.tenant,id);int page=number(r.getParameter("page"),1),size=number(r.getParameter("size"),20);if(page>1000000||!Set.of(20,50,100).contains(size))throw bad();long total=db.queryForObject("SELECT COUNT(*) FROM asset_membership WHERE tenant_id=? AND company_id=?",Long.class,a.tenant,id);return Map.of("items",db.queryForList("SELECT id,user_id,company_id,state,row_version,created_at FROM asset_membership WHERE tenant_id=? AND company_id=? ORDER BY id DESC LIMIT ? OFFSET ?",a.tenant,id,size,(page-1L)*size).stream().map(this::dto).toList(),"total",total,"page",page,"size",size);}
 private Map<String,Object> row(String table,long tenant,long id){var rows=db.queryForList("SELECT * FROM "+table+" WHERE tenant_id=? AND id=?",tenant,id);if(rows.isEmpty())throw new Problem(404,"TARGET_NOT_ACCESSIBLE");return rows.getFirst();}
 private Map<String,Object> operation(String key,Actor a,boolean required){var rows=db.queryForList("SELECT * FROM asset_operation WHERE operation_id=?",key);if(rows.isEmpty()){if(required)throw new Problem(404,"TARGET_NOT_ACCESSIBLE");return null;}var row=rows.getFirst();if(((Number)row.get("tenant_id")).longValue()!=a.tenant||((Number)row.get("actor_user_id")).longValue()!=a.user)throw new Problem(404,"TARGET_NOT_ACCESSIBLE");return row;}
 private void insertOperation(String key,Actor a,String kind,Long target,byte[]digest,Map<String,Object>b,String state){db.update("INSERT INTO asset_operation(operation_id,tenant_id,actor_user_id,kind,target_id,request_digest,input_json,state,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",key,a.tenant,a.user,kind,target,digest,Chain.JSON.writeValueAsString(new TreeMap<>(b)),state);}
 private void audit(Actor a,String action,long target,String op){db.update("INSERT INTO asset_audit(tenant_id,actor_user_id,action,target_id,operation_id,occurred_at,trace_id) VALUES(?,?,?,?,?,UTC_TIMESTAMP(3),?)",a.tenant,a.user,action,target,op,a.trace);}
 private Map<String,Object> dto(Map<String,Object> row){var result=new LinkedHashMap<String,Object>();for(var e:row.entrySet()){String k=e.getKey();if(k.equals("tenant_id")||k.equals("operation_id"))continue;Object v=e.getValue();if(v instanceof java.sql.Timestamp t)v=t.toLocalDateTime().toInstant(ZoneOffset.UTC).toString();else if(v instanceof java.sql.Date d)v=d.toLocalDate().toString();else if(v instanceof LocalDateTime t)v=t.toInstant(ZoneOffset.UTC).toString();else if(v!=null&&(k.equals("id")||k.endsWith("_id")||k.endsWith("_version")))v=v.toString();result.put(camel(k),v);}return result;}
 @SuppressWarnings("unchecked") private static Map<String,Object> json(Object v){try{return Chain.JSON.readValue(v instanceof byte[] b?new String(b,StandardCharsets.UTF_8):v.toString(),Map.class);}catch(Exception e){throw new Problem(503,"DEPENDENCY_UNAVAILABLE");}}
 private static byte[] digest(String prefix,Map<String,Object>b){try{return MessageDigest.getInstance("SHA-256").digest((prefix+"\n"+Chain.JSON.writeValueAsString(new TreeMap<>(b))).getBytes(StandardCharsets.UTF_8));}catch(Exception e){throw bad();}}
 private static String snake(String field){return field.replaceAll("([a-z])([A-Z])","$1_$2").toLowerCase(Locale.ROOT);}
 private static String camel(String field){StringBuilder r=new StringBuilder();boolean upper=false;for(char c:field.toCharArray())if(c=='_')upper=true;else{r.append(upper?Character.toUpperCase(c):c);upper=false;}return r.toString();}
 private static String string(Map<String,Object>b,String key,int max,boolean required){Object v=b.get(key);if(v==null){if(required)throw bad();return null;}if(!(v instanceof String s)||s.codePointCount(0,s.length())>max||(required&&s.isBlank())||s.codePoints().anyMatch(c->Character.isISOControl(c)||(c>=0xD800&&c<=0xDFFF)))throw bad();return s;}
 private static long id(Object v){try{if(!(v instanceof String s)||!s.matches("[1-9][0-9]{0,18}"))throw bad();return Long.parseLong(s);}catch(NumberFormatException e){throw bad();}}
 private static int number(String v,int fallback){if(v==null)return fallback;try{if(!v.matches("[1-9][0-9]{0,6}"))throw bad();return Integer.parseInt(v);}catch(NumberFormatException e){throw bad();}}
 private static String key(HttpServletRequest r){var values=Collections.list(r.getHeaders("Idempotency-Key"));if(values.size()!=1)throw bad();return uuid(values.getFirst());}
 private static String uuid(String s){try{if(!UUID.fromString(s).toString().equals(s))throw bad();return s;}catch(Exception e){throw bad();}}
 private static void fields(Map<String,Object>b,Set<String> allowed){if(!allowed.containsAll(b.keySet()))throw bad();}
 private static Object date(Object v){if(v==null)return null;try{if(!(v instanceof String s)||!s.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw bad();return LocalDate.parse(s);}catch(Exception e){throw bad();}}
 private static LocalDate day(Object v){return v==null?null:v instanceof java.sql.Date d?d.toLocalDate():v instanceof LocalDate d?d:LocalDate.parse(v.toString());}
 private static void validateDates(Map<String,Object>b){var start=day(b.get("warranty_start"));var end=day(b.get("warranty_end"));var purchase=day(b.get("purchase_date"));if((start==null)!=(end==null)||(start!=null&&start.isAfter(end))||(purchase!=null&&end!=null&&purchase.isAfter(end)))throw bad();}
 private static Problem bad(){return new Problem(400,"VALIDATION_ERROR");}
 @ExceptionHandler(Problem.class)ResponseEntity<?> problem(Problem e){return ResponseEntity.status(e.status).body(Map.of("code",e.code,"traceId",UUID.randomUUID().toString()));}
 @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,org.springframework.web.HttpMediaTypeNotSupportedException.class})ResponseEntity<?> malformed(Exception e){return ResponseEntity.badRequest().body(Map.of("code","VALIDATION_ERROR"));}
 @ExceptionHandler(Exception.class)ResponseEntity<?> failure(Exception e,HttpServletRequest r){return ResponseEntity.status(503).body(Map.of("code",r.getMethod().equals("GET")?"DEPENDENCY_UNAVAILABLE":"OPERATION_RESULT_UNKNOWN","traceId",UUID.randomUUID().toString()));}
}
