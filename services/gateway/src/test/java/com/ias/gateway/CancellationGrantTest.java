package com.ias.gateway;
import org.junit.jupiter.api.Test;
import java.sql.SQLException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class CancellationGrantTest {
 @Test void cancellationFactsAreAppendOnlyAndPointerIsBounded()throws Exception{var files=new TicketFlowTest();files.h.credentials=files.h.read(files.h.privateRoot.resolve("mysql-credentials.json"));try(var c=files.ticketDb(true);var s=c.createStatement()){for(String table:List.of("ticket_cancel_request","ticket_cancel_decision","ticket_cancellation")){assertThrows(SQLException.class,()->s.executeUpdate("UPDATE "+table+" SET operation_id=operation_id WHERE 1=0"));assertThrows(SQLException.class,()->s.executeUpdate("DELETE FROM "+table+" WHERE 1=0"));}assertEquals(0,s.executeUpdate("UPDATE ticket_record SET current_cancel_request_id=current_cancel_request_id WHERE 1=0"));assertThrows(SQLException.class,()->s.executeUpdate("UPDATE ticket_record SET related_business_json=related_business_json WHERE 1=0"));}}
}
