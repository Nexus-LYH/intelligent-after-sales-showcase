package com.ias.trust;

import tools.jackson.databind.json.JsonMapper;
import javax.net.ssl.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;

/** Local chain configuration is private, role-scoped and never serialized/logged. */
public final class Chain {
 public static final JsonMapper JSON=JsonMapper.builder().build();
 private static Map<String,Object> config;
 private static final Map<String,HttpClient> clients=new HashMap<>();
 private static final ThreadLocal<Long> localBudget=new ThreadLocal<>();
 private Chain(){}
 public static boolean active(){return System.getenv("IAS_CHAIN_CONFIG")!=null;}
 @SuppressWarnings("unchecked") public static synchronized Map<String,Object> config(){
  if(config==null)try{config=JSON.readValue(Files.readString(Path.of(System.getenv("IAS_CHAIN_CONFIG"))),Map.class);}catch(Exception e){throw new IllegalStateException("Private role-scoped chain configuration required");}
  return config;
 }
 public static String role(){return (String)config().get("role");}
 @SuppressWarnings("unchecked") public static String secret(String edge){Object value=((Map<String,Object>)config().get("edges")).get(edge);if(!(value instanceof String text)||!text.matches("[A-Za-z0-9_-]{43}"))throw new Problem(403,"SERVICE_AUTHENTICATION_FAILED");return text;}
 public static String cookie(jakarta.servlet.http.HttpServletRequest request){
  String token=null;boolean found=false;
  for(String header:Collections.list(request.getHeaders("Cookie")))for(String part:header.split(";")){
   int split=part.indexOf('=');if(split>=0&&part.substring(0,split).strip().equals("IAS-SESSION-DEV")){if(found)throw new Problem(400,"AMBIGUOUS_COOKIE");found=true;token=part.substring(split+1).strip();}
  }
  return token;
 }
 public static String single(jakarta.servlet.http.HttpServletRequest r,String name){var values=Collections.list(r.getHeaders(name));if(values.size()!=1)throw new Problem(403,"SERVICE_AUTHENTICATION_FAILED");return values.getFirst();}
 public static long deadline(jakarta.servlet.http.HttpServletRequest r){Object value=r.getAttribute("ias.chain.deadline");return value instanceof Long n?n:System.currentTimeMillis()+10000;}
 public static long nanoDeadline(long deadline){long wall=System.nanoTime()+Math.max(0,Math.min(10000,deadline-System.currentTimeMillis()))*1_000_000;Long local=localBudget.get();return local==null?wall:Math.min(wall,local);}
 public static void begin(long deadline){localBudget.set(nanoDeadline(deadline));}
 public static void end(){localBudget.remove();}
 public static void remaining(long deadline){Long local=localBudget.get();if(deadline-System.currentTimeMillis()<100||(local!=null&&System.nanoTime()>=local))throw new Problem(503,"OPERATION_RESULT_UNKNOWN");}
 public static String trace(jakarta.servlet.http.HttpServletRequest r){Object value=r.getAttribute("ias.chain.trace");return value==null?UUID.randomUUID().toString():value.toString();}
 public static void source(jakarta.servlet.http.HttpServletRequest r){
  String origin=r.getHeader("Origin");
  if(origin==null){String referer=r.getHeader("Referer");if(referer!=null)try{var uri=URI.create(referer);origin=uri.getScheme()+"://"+uri.getRawAuthority();}catch(Exception e){throw new Problem(403,"ORIGIN_REJECTED");}}
  if(Collections.list(r.getHeaders("Origin")).size()>1||!Set.of("http://127.0.0.1:4173","http://127.0.0.1:4174","http://127.0.0.1:5173","http://127.0.0.1:8080","http://127.0.0.1:8081").contains(origin==null?"":origin))throw new Problem(403,"ORIGIN_REJECTED");
 }
 public record Reply(int status,Map<String,List<String>> headers,byte[] bytes){@Override public String toString(){return "InternalReply[redacted]";}}
 public static Map<String,Object> call(String edge,String path,Map<String,Object> body,String trace,long deadline){
  Reply reply=send(edge,"POST",path,JSON.writeValueAsBytes(body),Map.of("Content-Type","application/json"),trace,deadline);
  Map<String,Object> result=parse(reply.bytes);
  if(reply.status>=400)throw new Problem(reply.status,String.valueOf(result.getOrDefault("code","DEPENDENCY_UNAVAILABLE")));
  return result;
 }
 @SuppressWarnings("unchecked") public static Map<String,Object> parse(byte[] bytes){try{return JSON.readValue(bytes,Map.class);}catch(Exception e){throw new Problem(503,"DEPENDENCY_UNAVAILABLE");}}
 @SuppressWarnings("unchecked") public static synchronized HttpClient client(String peer){
  return clients.computeIfAbsent(peer,name->{try{
   Map<String,Object> p=(Map<String,Object>)((Map<String,Object>)config().get("peers")).get(peer);
   KeyStore store=KeyStore.getInstance("PKCS12");try(var in=Files.newInputStream(Path.of((String)p.get("trustStore")))){store.load(in,((String)config().get("storePassword")).toCharArray());}
   TrustManagerFactory tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(store);
   SSLContext ssl=SSLContext.getInstance("TLS");ssl.init(null,tm.getTrustManagers(),null);
   return HttpClient.newBuilder().sslContext(ssl).connectTimeout(Duration.ofSeconds(1)).followRedirects(HttpClient.Redirect.NEVER).build();
  }catch(Exception e){throw new Problem(503,"DEPENDENCY_UNAVAILABLE");}});
 }
 public static boolean allowedEdge(String edge,String method,String path){
  if("/internal/service-probe".equals(path))return "POST".equals(method)&&Set.of("ticket-identity","ticket-asset","stock-ticket","ticket-approval","ticket-stock","ticket-notification","ticket-finance").contains(edge);
  return switch(edge){
   case "gateway-identity" -> ("POST".equals(method)&&path.equals("/api/identity/employees"))||("GET".equals(method)&&path.matches("/api/identity/employee-create-operations/[0-9a-f-]{36}"))||path.startsWith("/api/auth/")||path.matches("/api/identity/users(?:/[0-9]+(?:/sessions/revoke)?)?(?:\\?.*)?")||("GET".equals(method)&&path.matches("/api/identity/users/[0-9]+/(?:access|status-operations/[0-9a-f-]{36}|scope-operations/[0-9a-f-]{36})"))||("POST".equals(method)&&path.matches("/api/identity/users/[0-9]+/(?:status|scope)"))||("POST".equals(method)&&path.equals("/internal/auth/introspect"));
   case "gateway-asset" -> path.startsWith("/api/asset/");
   case "gateway-ticket" -> path.startsWith("/api/ticket/");
   case "gateway-finance" -> path.startsWith("/api/finance/");
   case "finance-identity" -> "POST".equals(method)&&path.equals("/internal/identity/domain-authorizations");
   case "finance-ticket" -> "POST".equals(method)&&Set.of("/internal/ticket/finance-context","/internal/ticket/finance-quotes/prepare","/internal/ticket/finance-quotes/source-commit","/internal/ticket/finance-voucher-fact","/internal/ticket/fee-executions/query","/internal/ticket/fee-executions/projection").contains(path);
   case "finance-stock" -> "POST".equals(method)&&Set.of("/internal/stock/finance-part-executions/query","/internal/stock/finance-part-executions/projection").contains(path);
   case "finance-notification" -> "POST".equals(method)&&path.equals("/internal/notification/finance-events");
   case "stock-notification" -> "POST".equals(method)&&Set.of("/internal/notification/events","/internal/notification/suppressed-parts/repair").contains(path);
   case "ticket-finance" -> "POST".equals(method)&&Set.of("/internal/finance/quote-sources/query","/internal/finance/fee-completions/record").contains(path);
   case "finance-asset" -> "POST".equals(method)&&path.equals("/internal/asset/finance-membership");
   case "finance-approval" -> "POST".equals(method)&&Set.of("/internal/approval/fee-rule","/internal/approval/fee-completions/query","/internal/approval/fee-completions/group","/internal/approval/finance-part-binding").contains(path);
    case "approval-finance" -> "POST".equals(method)&&path.equals("/internal/finance/fee-approval-context");
   case "gateway-approval" -> path.startsWith("/api/approval/");
   case "gateway-stock" -> path.startsWith("/api/stock/");
   case "gateway-notification" -> path.startsWith("/api/notification/");
   case "ticket-identity" -> "POST".equals(method)&&(path.equals("/internal/auth/introspect")||path.matches("/internal/identity/ticket-(authorizations|candidates)")||(path.equals("/internal/identity/domain-authorizations")||path.equals("/internal/identity/stock-engineer-qualification")));
   case "approval-identity" -> "POST".equals(method)&&Set.of("/internal/auth/introspect","/internal/identity/domain-authorizations","/internal/identity/approval-recipients","/internal/identity/fee-recipients").contains(path);
   case "stock-identity" -> "POST".equals(method)&&Set.of("/internal/auth/introspect","/internal/identity/domain-authorizations").contains(path);
   case "notification-identity" -> "POST".equals(method)&&Set.of("/internal/auth/introspect","/internal/identity/domain-authorizations","/internal/identity/approval-recipients","/internal/identity/fee-recipients").contains(path);
   case "approval-ticket" -> "POST".equals(method)&&(path.equals("/internal/ticket/approval-context")||path.matches("/internal/ticket/approval-references/(register|query|settle)")||path.equals("/internal/ticket/finance-quotes/proof")||path.equals("/internal/ticket/fee-approval-context"));
   case "stock-ticket" -> "POST".equals(method)&&(path.equals("/internal/ticket/approval-context")||path.matches("/internal/ticket/stock-executions/(prepare|complete|query)")||path.equals("/internal/ticket/finance-quotes/proof")||path.equals("/internal/ticket/fee-executions/projection")||path.equals("/internal/ticket/part-fee-permits/proof"));
   case "ticket-approval" -> "POST".equals(method)&&(path.matches("/internal/approval/finance-fences/(prepare|reconcile)")||Set.of("/internal/approval/fee-completions/query","/internal/approval/fee-completions/group").contains(path));
   case "ticket-stock" -> ("POST".equals(method)&&Set.of("/internal/stock/finance-fences/prepare","/internal/stock/finance-fences/reconcile","/internal/stock/finance-part-executions","/internal/stock/finance-part-executions/owner-result","/internal/stock/finance-part-executions/refuse","/internal/stock/parts-cleanup-proof").contains(path))||("GET".equals(method)&&path.matches("/internal/stock/finance-part-execution-operations/[0-9a-f-]{36}"));
   case "stock-approval" -> "POST".equals(method)&&Set.of("/internal/approval/stock-context","/internal/approval/fee-completions/query","/internal/approval/paid-coverage").contains(path);
   case "approval-stock" -> "POST".equals(method)&&Set.of("/internal/stock/approval-check","/internal/stock/part-catalog-proof").contains(path);
   case "approval-notification" -> "POST".equals(method)&&Set.of("/internal/notification/events","/internal/notification/fee-completions/repair").contains(path);
   case "ticket-notification" -> "POST".equals(method)&&path.equals("/internal/notification/events");
   case "notification-approval" -> "POST".equals(method)&&path.matches("/internal/approval/(facts|delivery-context|notification-context|fee-notification-context)");
   case "notification-ticket" -> "POST".equals(method)&&Set.of("/internal/ticket/fee-notification-context","/internal/ticket/customer-notification-context").contains(path);
   case "notification-finance" -> "POST".equals(method)&&path.equals("/internal/finance/money-notification-context");
   case "notification-stock" -> "POST".equals(method)&&path.equals("/internal/stock/part-fee-notification-context");
   case "ticket-asset" -> "POST".equals(method)&&(path.equals("/internal/asset/repair-access")||path.equals("/internal/asset/finance-membership")||path.matches("/internal/asset/repair-bindings/(reserve|[0-9a-f-]{36}/(?:query|confirm|release))"));
   case "asset-ticket" -> "POST".equals(method)&&path.matches("/internal/ticket/create-facts/[0-9a-f-]{36}");
   case "asset-identity" -> "POST".equals(method)&&(path.equals("/internal/auth/introspect")||(path.matches("/internal/identity/customer-enrollments/(create|query|activate)")||path.equals("/internal/identity/customer-eligibility")));
   case "identity-asset" -> "POST".equals(method)&&(path.equals("/internal/asset/enrollment-evidence")||path.equals("/internal/asset/company-references"));
   case "identity-stock" -> "POST".equals(method)&&path.equals("/internal/stock/warehouse-references");
   default -> false;
  };
 }
 public static String peer(String edge){String peer=edge.substring(edge.lastIndexOf('-')+1);if(!Set.of("identity","asset","ticket","approval","stock","notification","finance").contains(peer))throw new Problem(403,"SERVICE_AUTHENTICATION_FAILED");return peer;}
 @SuppressWarnings("unchecked") public static String base(String peer){
  int port=switch(peer){case "identity"->8441;case "asset"->8442;case "ticket"->8443;case "approval"->8444;case "stock"->8445;case "notification"->8446;case "finance"->8447;default->throw new Problem(403,"SERVICE_AUTHENTICATION_FAILED");};
  var peers=(Map<String,Object>)config().get("peers");var p=peers==null?null:(Map<String,Object>)peers.get(peer);Object configured=p==null?null:p.get("baseUrl");String value=configured==null?"https://127.0.0.1:"+port:configured.toString();
  // Operator-only endpoint wiring, never browser input. Exact peer trust/hostname checks remain mandatory.
  try{URI uri=URI.create(value);if(!"https".equals(uri.getScheme())||!"127.0.0.1".equals(uri.getHost())||uri.getPort()<1||uri.getPort()>65535||!uri.getRawPath().isEmpty()||uri.getRawQuery()!=null||uri.getRawFragment()!=null||uri.getRawUserInfo()!=null)throw new Exception();return value;}catch(Exception e){throw new Problem(503,"DEPENDENCY_UNAVAILABLE");}
 }
 public static Reply send(String edge,String method,String path,byte[] body,Map<String,String> headers,String trace,long deadline){
  remaining(deadline);if(!allowedEdge(edge,method,path))throw new Problem(403,"SERVICE_AUTHENTICATION_FAILED");
  String peer=peer(edge),base=base(peer);
  try{
   var b=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofMillis(Math.min(readTimeout(edge,method,path),Math.max(1,deadline-System.currentTimeMillis()))));
   for(var h:headers.entrySet())b.header(h.getKey(),h.getValue());
   b.header("Authorization","Bearer "+secret(edge)).header("X-IAS-Edge",edge).header("X-IAS-Trace",trace).header("X-IAS-Deadline",Long.toString(deadline));
   var response=client(peer).send(b.method(method,body.length==0?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(body)).build(),HttpResponse.BodyHandlers.ofByteArray());
   byte[] bytes=response.body();if(bytes.length>1_048_576)throw new Problem(503,"DEPENDENCY_UNAVAILABLE");remaining(deadline);return new Reply(response.statusCode(),response.headers().map(),bytes);
  }catch(Problem e){throw e;}catch(Exception e){throw new Problem(503,"DEPENDENCY_UNAVAILABLE");}
 }
 static long readTimeout(String edge,String method,String path){return "gateway-notification".equals(edge)&&"GET".equals(method)&&(path.equals("/api/notification/messages")||path.startsWith("/api/notification/messages?"))?8000:2000;}
 public static Map<String,Object> identity(String edge,String token,String operation,String csrf,String trace,long deadline){
  if(token==null||token.isEmpty())throw new Problem(401,"SESSION_INVALID");
  Map<String,Object> body=new HashMap<>();body.put("sessionToken",token);body.put("operation",operation);body.put("csrfToken",csrf);
  return call(edge,"/internal/auth/introspect",body,trace,deadline);
 }
 public static void fresh(Map<String,Object> identity,long deadline){remaining(deadline);try{long age=System.currentTimeMillis()-Instant.parse((String)identity.get("checkedAt")).toEpochMilli();if(age<0||age>2000)throw new Problem(503,"DEPENDENCY_UNAVAILABLE");}catch(Problem e){throw e;}catch(Exception e){throw new Problem(503,"DEPENDENCY_UNAVAILABLE");}}
}










