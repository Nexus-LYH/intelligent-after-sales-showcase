package com.ias.ticket;

import java.time.*;
import java.util.*;

/** A clock is an explicit input: read projections never mutate time or notification facts. */
public final class CompletionTiming {
 public static final long LIMIT=72*60*60*1000L;
 public record Interval(Instant submitted,Instant decided){}
 public record Result(long waitMs,long excludedMs,long effectiveMs,Long firstMs,boolean currentOver72,boolean needsCoordination){}
 public record Dual(Result confirmation,long supplementWaitMs,long excludedSupplementMs,long currentSupplementWaitMs,Long firstEffectiveMs,boolean supplementOver72,boolean needsCoordination){}
 public static Dual calculate(Instant start,Instant now,List<Interval> confirmations,List<Interval> supplements,boolean pendingConfirmation,boolean pendingSupplement){
  var confirmation=calculate(start,now,confirmations,pendingConfirmation);var supplement=calculate(start,now,supplements,pendingSupplement);
  var all=new ArrayList<Interval>();all.addAll(confirmations);all.addAll(supplements);all.sort(Comparator.comparing(Interval::submitted));Instant end=start;
  for(var i:all){Instant until=i.decided()==null?now:i.decided();if(i.submitted().isBefore(end))throw new IllegalStateException("Overlapping waiting intervals");end=until;}
  long elapsed=Duration.between(start,now).toMillis(),effective=elapsed-confirmation.excludedMs()-supplement.excludedMs();if(effective<0)throw new IllegalStateException("Invalid waiting deductions");
  Long firstEffective=null;if(confirmation.firstMs()!=null){Instant first=start.plusMillis(confirmation.firstMs());long before=0;for(var i:supplements){if(!i.submitted().isBefore(first))continue;Instant until=i.decided()==null||i.decided().isAfter(first)?first:i.decided();before=Math.addExact(before,Duration.between(i.submitted(),until).toMillis());}firstEffective=confirmation.firstMs()-Math.min(before,LIMIT);}
  long current=0;for(var i:supplements)if(i.decided()==null)current=Duration.between(i.submitted(),now).toMillis();
  return new Dual(new Result(confirmation.waitMs(),confirmation.excludedMs(),effective,confirmation.firstMs(),confirmation.currentOver72(),confirmation.needsCoordination()),supplement.waitMs(),supplement.excludedMs(),current,firstEffective,supplement.currentOver72(),confirmation.needsCoordination()||supplement.needsCoordination());
 }
 public static Result calculate(Instant start,Instant now,List<Interval> intervals,boolean pending){
  if(now.isBefore(start))throw new IllegalStateException("Clock precedes round start");
  Instant end=start;long wait=0;Long first=null;boolean open=false;long current=0;
  for(var interval:intervals){
   Instant until=interval.decided()==null?now:interval.decided();
   if(open||interval.submitted().isBefore(end)||until.isBefore(interval.submitted())||until.isAfter(now))throw new IllegalStateException("Invalid confirmation interval");
   if(first==null)first=Duration.between(start,interval.submitted()).toMillis();
   long duration=Duration.between(interval.submitted(),until).toMillis();wait=Math.addExact(wait,duration);end=until;
   if(interval.decided()==null){open=true;current=duration;}
  }
  long elapsed=Duration.between(start,now).toMillis(),excluded=Math.min(wait,LIMIT);
  if(excluded>elapsed)throw new IllegalStateException("Overlapping confirmation intervals");
  return new Result(wait,excluded,elapsed-excluded,first,pending&&current>LIMIT,pending&&wait>=LIMIT);
 }
 private CompletionTiming(){}
}
