package com.ias.gateway;
import org.junit.jupiter.api.Test;
import java.sql.SQLException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class ReopenGrantTest {
 @Test void roundMetadataFrozenProjectionAndReopenHistoryAreAppendOnly()throws Exception{var f=new TicketFlowTest();f.h.credentials=f.h.read(f.h.privateRoot.resolve("mysql-credentials.json"));try(var c=f.ticketDb(true);var s=c.createStatement()){for(String table:List.of("ticket_round","ticket_round_freeze","ticket_reopen_request","ticket_reopen_decision")){assertThrows(SQLException.class,()->s.executeUpdate("UPDATE "+table+" SET ticket_id=ticket_id WHERE 1=0"));assertThrows(SQLException.class,()->s.executeUpdate("DELETE FROM "+table+" WHERE 1=0"));}assertThrows(SQLException.class,()->s.executeUpdate("UPDATE ticket_round_freeze SET snapshot_json=snapshot_json WHERE 1=0"));assertEquals(0,s.executeUpdate("UPDATE ticket_record SET current_reopen_id=current_reopen_id,round_started_at=round_started_at WHERE 1=0"));assertThrows(SQLException.class,()->s.executeUpdate("UPDATE ticket_record SET submitted_at=submitted_at WHERE 1=0"));}}
}
