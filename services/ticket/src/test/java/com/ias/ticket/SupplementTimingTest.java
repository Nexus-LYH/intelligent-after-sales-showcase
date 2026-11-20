package com.ias.ticket;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class SupplementTimingTest {
 final Instant start=Instant.parse("2026-01-01T00:00:00Z");Instant h(long n){return start.plusSeconds(n*3600);}
 @Test void independentBudgetsGive180Minus72Minus72Equals36(){var d=CompletionTiming.calculate(start,h(180),List.of(new CompletionTiming.Interval(h(90),h(164))),List.of(new CompletionTiming.Interval(h(1),h(81))),false,false);assertEquals(36*3600000L,d.confirmation().effectiveMs());assertEquals(72*3600000L,d.excludedSupplementMs());assertEquals(90*3600000L,d.confirmation().firstMs());assertEquals(18*3600000L,d.firstEffectiveMs());}
 @Test void supplementBoundaryOneMillisecondBeforeAtAfter(){for(long delta:new long[]{-1,0,1}){var d=CompletionTiming.calculate(start,h(73).plusMillis(delta),List.of(),List.of(new CompletionTiming.Interval(h(1),null)),false,true);assertEquals(Math.min(72*3600000L+delta,CompletionTiming.LIMIT),d.excludedSupplementMs());assertEquals(delta>=0,d.needsCoordination());assertEquals(delta>0,d.supplementOver72());assertEquals(3600000L+Math.max(0,delta),d.confirmation().effectiveMs());}}
 @Test void repeatedSupplementAndReturnDoNotResetEitherBudget(){var d=CompletionTiming.calculate(start,h(180),List.of(new CompletionTiming.Interval(h(45),h(85)),new CompletionTiming.Interval(h(130),h(164))),List.of(new CompletionTiming.Interval(h(1),h(41)),new CompletionTiming.Interval(h(86),h(126))),false,false);assertEquals(36*3600000L,d.confirmation().effectiveMs());assertEquals(80*3600000L,d.supplementWaitMs());assertEquals(5*3600000L,d.firstEffectiveMs());}
 @Test void crossTypeOverlapFailsClosed(){assertThrows(IllegalStateException.class,()->CompletionTiming.calculate(start,h(10),List.of(new CompletionTiming.Interval(h(3),h(8))),List.of(new CompletionTiming.Interval(h(1),h(4))),false,false));}
 @Test void oldTicketsPreserveConfirmationMath(){var intervals=List.of(new CompletionTiming.Interval(h(3),h(77)));assertEquals(CompletionTiming.calculate(start,h(77),intervals,false),CompletionTiming.calculate(start,h(77),intervals,List.of(),false,false).confirmation());}
}
