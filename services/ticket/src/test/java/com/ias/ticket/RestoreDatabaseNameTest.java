package com.ias.ticket;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RestoreDatabaseNameTest {
 @Test void acceptsOnlyExplicitIsolatedPrefix(){assertEquals("ias_ticket_test",TicketApplication.databaseName(null));assertEquals("ias_restore_demo_ticket",TicketApplication.databaseName("ias_restore_demo"));assertThrows(IllegalStateException.class,()->TicketApplication.databaseName("ias_ticket_test"));}
}
