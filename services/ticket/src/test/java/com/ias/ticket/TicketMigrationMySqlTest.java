package com.ias.ticket;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit migration runner for the dedicated 3307 ticket test database. */
@EnabledIfEnvironmentVariable(named="IAS_TICKET_MIGRATION_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:3307/ias_ticket_test.*")
class TicketMigrationMySqlTest {
 @Test void appliesAndValidatesCurrentMigrations()throws Exception{
  String url=System.getenv("IAS_TICKET_MIGRATION_URL"),user=System.getenv("IAS_TICKET_MIGRATION_USER"),password=System.getenv("IAS_TICKET_MIGRATION_PASSWORD");
  var result=Flyway.configure().dataSource(url,user,password).load().migrate();assertTrue(result.success);
  try(var c=DriverManager.getConnection(url,user,password);var s=c.prepareStatement("SELECT COUNT(*) FROM flyway_schema_history WHERE version='17' AND success=1")){try(var rows=s.executeQuery()){assertTrue(rows.next());assertEquals(1,rows.getInt(1));}}
 }
}
