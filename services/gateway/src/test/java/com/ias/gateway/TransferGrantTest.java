package com.ias.gateway;
import org.junit.jupiter.api.Test;
import java.sql.SQLException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class TransferGrantTest {
 @Test void historiesAppendOnlyAndCurrentPointerBounded()throws Exception{var f=new TicketFlowTest();f.h.credentials=f.h.read(f.h.privateRoot.resolve("mysql-credentials.json"));try(var c=f.ticketDb(true);var s=c.createStatement()){for(String table:List.of("ticket_transfer_request","ticket_transfer_end","ticket_coordination_event")){assertThrows(SQLException.class,()->s.executeUpdate("UPDATE "+table+" SET ticket_id=ticket_id WHERE 1=0"));assertThrows(SQLException.class,()->s.executeUpdate("DELETE FROM "+table+" WHERE 1=0"));}assertEquals(0,s.executeUpdate("UPDATE ticket_record SET current_transfer_id=current_transfer_id WHERE 1=0"));assertThrows(SQLException.class,()->s.executeUpdate("UPDATE ticket_assignment SET engineer_user_id=engineer_user_id WHERE 1=0"));}}
}
