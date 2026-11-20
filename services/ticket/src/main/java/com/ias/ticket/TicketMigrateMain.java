package com.ias.ticket;
import org.flywaydb.core.Flyway;
import java.sql.*;
public final class TicketMigrateMain {
 public static void main(String[]args){
  String url="jdbc:mysql://127.0.0.1:3307/ias_ticket_test?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",user=System.getenv("IAS_TICKET_MIGRATION_USER"),password=System.getenv("IAS_TICKET_MIGRATION_PASSWORD");
  try(var c=DriverManager.getConnection(url,user,password);var s=c.createStatement();var r=s.executeQuery("SELECT @@port,@@datadir,DATABASE()")){
   r.next();if(r.getInt(1)!=3307||!"ias_ticket_test".equals(r.getString(3))||!"C:/ias-local/Develop/IAS-MySQL84/data/".equalsIgnoreCase(r.getString(2).replace('\\','/')))throw new IllegalStateException("Dedicated ticket database required");
   Flyway.configure().dataSource(url,user,password).locations("classpath:db/migration").cleanDisabled(true).load().migrate();
  }catch(Exception e){System.err.println("Ticket migration not confirmed; inspect restricted operator configuration.");System.exit(1);}
 }
}

