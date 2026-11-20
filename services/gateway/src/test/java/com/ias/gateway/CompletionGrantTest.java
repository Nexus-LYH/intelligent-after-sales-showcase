package com.ias.gateway;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Zero-row probes still perform real MySQL privilege checks; no business facts are modified. */
class CompletionGrantTest {
 @Test void actualRuntimePrincipalHasOnlyAppendHistoryAndBoundedPointerUpdate()throws Exception{
  var files=new TicketFlowTest();var h=files.h;h.credentials=h.read(h.privateRoot.resolve("mysql-credentials.json"));
  try(var c=files.ticketDb(true);var s=c.createStatement()){
   for(String table:List.of("ticket_solution","ticket_solution_decision","ticket_closure")){
    assertThrows(SQLException.class,()->s.executeUpdate("UPDATE "+table+" SET operation_id=operation_id WHERE 1=0"));
    assertThrows(SQLException.class,()->s.executeUpdate("DELETE FROM "+table+" WHERE 1=0"));
   }
   assertEquals(0,s.executeUpdate("UPDATE ticket_record SET current_solution_id=current_solution_id WHERE 1=0"));
   assertThrows(SQLException.class,()->s.executeUpdate("UPDATE ticket_record SET completion_scope=completion_scope WHERE 1=0"));
   assertThrows(SQLException.class,()->s.executeUpdate("UPDATE ticket_record SET related_business_json=related_business_json WHERE 1=0"));
   assertEquals(0,s.executeUpdate("UPDATE ticket_actor_lock SET user_id=user_id WHERE 1=0"));
   assertThrows(SQLException.class,()->s.executeUpdate("UPDATE ticket_actor_lock SET tenant_id=tenant_id WHERE 1=0"));
   assertThrows(SQLException.class,()->s.executeUpdate("DELETE FROM ticket_actor_lock WHERE 1=0"));
  }
  Files.writeString(Path.of("target","batch7-runtime-grants.json"),h.json.writeValueAsString(Map.of("mode","actual3307 runtime principal zero-row probes","historyUpdateDeleteDenied",true,"currentSolutionPointerUpdateAllowed",true,"relatedBusinessAndScopeUpdateDenied",true,"actorUserColumnOnly",true,"businessRowsChanged",0)));
 }
}
