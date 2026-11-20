package com.ias.ticket;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
class ReopenWindowTest {
 @Test void inclusive168HoursUsesActualClosureInstant(){Instant closed=Instant.parse("2026-10-01T03:12:34.123Z"),deadline=closed.plus(Duration.ofHours(168));assertFalse(Reopens.inWindow(closed,closed.minusMillis(1)));assertTrue(Reopens.inWindow(closed,closed));assertTrue(Reopens.inWindow(closed,deadline.minusMillis(1)));assertTrue(Reopens.inWindow(closed,deadline));assertFalse(Reopens.inWindow(closed,deadline.plusMillis(1)));}
}
