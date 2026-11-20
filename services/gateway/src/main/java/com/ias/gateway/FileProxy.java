package com.ias.gateway;
import com.ias.trust.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;
public final class FileProxy extends OncePerRequestFilter {
 static final java.util.concurrent.ScheduledExecutorService deadlines=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{var thread=new Thread(r,"ias-file-deadline");thread.setDaemon(true);return thread;});
 final Semaphore slots=new Semaphore(4);final Map<String,long[]> rates=new HashMap<>();
 synchronized void rate(String ip,boolean upload){ip=ip+":"+upload;long now=System.nanoTime();rates.entrySet().removeIf(e->now-e.getValue()[0]>60_000_000_000L);var row=rates.get(ip);if(row==null){if(rates.size()>=1024)throw new Problem(429,"RATE_LIMITED");rates.put(ip,new long[]{now,1});}else if(++row[1]>(upload?30:300))throw new Problem(429,"RATE_LIMITED");}
 @Override protected boolean shouldNotFilter(HttpServletRequest r){return !(r.getRequestURI().matches("/api/ticket/tickets/[0-9a-f-]{36}/finance-vouchers/[0-9a-f-]{36}/content")&&Set.of("PUT","GET","HEAD").contains(r.getMethod())||r.getRequestURI().matches("/api/ticket/tickets/[0-9a-f-]{36}/finance-voucher-files/[0-9a-f-]{36}/content")&&Set.of("GET","HEAD").contains(r.getMethod())||r.getRequestURI().matches("/api/stock/warehouses/[1-9][0-9]*/inbound-files/[0-9a-f-]{36}")&&r.getMethod().equals("POST")||r.getRequestURI().matches("/api/stock/warehouses/[1-9][0-9]*/inbound-files/[0-9a-f-]{36}/content")&&Set.of("GET","HEAD").contains(r.getMethod())||(r.getRequestURI().equals("/api/ticket/files")||r.getRequestURI().matches("/api/ticket/tickets/[0-9a-f-]{36}/supplement-requests/[0-9a-f-]{36}/files"))&&r.getMethod().equals("POST")||r.getRequestURI().matches("/api/ticket/files/[0-9a-f-]{36}/content")&&Set.of("GET","HEAD").contains(r.getMethod()));}
 @Override protected void doFilterInternal(HttpServletRequest r,HttpServletResponse s,FilterChain chain)throws IOException{
  String trace=UUID.randomUUID().toString();long deadline=System.currentTimeMillis()+10000,transferEnd=System.nanoTime()+120_000_000_000L;boolean held=false;java.util.concurrent.ScheduledFuture<?> requestTimer=null,responseTimer=null;Chain.begin(deadline);s.setHeader("Cache-Control","no-store");s.setHeader("X-Trace-Id",trace);
  try{
   if(!slots.tryAcquire())throw new Problem(429,"RATE_LIMITED");held=true;boolean upload=Set.of("POST","PUT").contains(r.getMethod()),voucher=r.getRequestURI().contains("/finance-voucher"),stock=r.getRequestURI().startsWith("/api/stock/");String peer=stock?"stock":"ticket",edge="gateway-"+peer;rate(r.getRemoteAddr(),upload);if(upload)Chain.source(r);
   Chain.identity("gateway-identity",Chain.cookie(r),voucher?(upload?"MONEY_WRITE":"MONEY_READ"):stock?(upload?"STOCK_MANAGE":"STOCK_READ"):upload?(r.getRequestURI().equals("/api/ticket/files")?"TICKET_CREATE":"TICKET_CUSTOMER_SUPPLEMENT"):"TICKET_READ",r.getHeader("X-CSRF-TOKEN"),trace,deadline);
   var b=HttpRequest.newBuilder(URI.create(Chain.base(peer)+r.getRequestURI()+(r.getQueryString()==null?"":"?"+r.getQueryString()))).timeout(Duration.ofSeconds(120));
   for(String name:List.of("Cookie","Origin","Referer","Content-Type","Accept","X-CSRF-TOKEN","Idempotency-Key","Range")){var values=Collections.list(r.getHeaders(name));if(values.size()>1)throw new Problem(name.equals("Range")?416:400,name.equals("Range")?"RANGE_NOT_SATISFIABLE":"VALIDATION_ERROR");if(!values.isEmpty())b.header(name,values.getFirst());}
   b.header("Authorization","Bearer "+Chain.secret(edge)).header("X-IAS-Edge",edge).header("X-IAS-Trace",trace).header("X-IAS-Deadline",Long.toString(deadline));
   if(upload&&r.getContentLengthLong()>20L*1024*1024+65536)throw new Problem(413,"FILE_TOO_LARGE");
   var input=new FilterInputStream(r.getInputStream()){long count;@Override public int read(byte[]bytes,int off,int len)throws IOException{int n=super.read(bytes,off,len);if(n>0&&(count+=n)>20L*1024*1024+65536)throw new IOException("Multipart envelope exceeded");return n;}@Override public int read()throws IOException{int n=super.read();if(n>=0&&++count>20L*1024*1024+65536)throw new IOException("Multipart envelope exceeded");return n;}};
   if(upload)requestTimer=deadlines.schedule(()->{try{input.close();}catch(IOException ignored){}},120,java.util.concurrent.TimeUnit.SECONDS);
   var response=Chain.client(peer).send(b.method(r.getMethod(),upload?HttpRequest.BodyPublishers.ofInputStream(()->input):HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofInputStream());
   responseTimer=deadlines.schedule(()->{try{response.body().close();}catch(IOException ignored){}},Math.max(1,transferEnd-System.nanoTime()),java.util.concurrent.TimeUnit.NANOSECONDS);
   s.setStatus(response.statusCode());for(String header:List.of("content-type","content-length","content-disposition","accept-ranges","content-range","x-content-type-options","retry-after")){var values=response.headers().allValues(header);if(values.size()>1)throw new Problem(503,"DEPENDENCY_UNAVAILABLE");if(!values.isEmpty())s.setHeader(header,values.getFirst());}
   long end=System.nanoTime()+120_000_000_000L;try(var body=response.body()){if(!r.getMethod().equals("HEAD")){byte[] buffer=new byte[32768];for(int n;(n=body.read(buffer))!=-1;){if(System.nanoTime()>end)throw new IOException("Transfer deadline");s.getOutputStream().write(buffer,0,n);}}}
  }catch(Exception e){if(!s.isCommitted()){s.resetBuffer();s.setHeader("Content-Length",null);s.setStatus(e instanceof Problem p?p.status:503);s.setContentType("application/json");s.getWriter().write(Chain.JSON.writeValueAsString(Map.of("code",e instanceof Problem p?p.code:"OPERATION_RESULT_UNKNOWN","traceId",trace)));}}
  finally{if(requestTimer!=null)requestTimer.cancel(false);if(responseTimer!=null)responseTimer.cancel(false);if(held)slots.release();Chain.end();}
 }
}


