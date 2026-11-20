package com.ias.ticket;

import java.nio.file.*;
import java.sql.*;
import java.util.Set;
import org.flywaydb.core.Flyway;

/** Restricted local migration entry point, launched outside the server with a migration account. */
public final class LocalMigrationCli {
 private LocalMigrationCli(){}
 static boolean permitted(String schema){return Set.of("ias_identity_test","ias_asset_test","ias_ticket_test","ias_approval_test","ias_stock_test","ias_notification_test","ias_finance_test").contains(schema);}
 public static void main(String[] args){
  try{
   if(args.length!=2||!permitted(args[0]))throw new IllegalArgumentException();
   Path migrations=Path.of(args[1]);if(!migrations.isAbsolute()||!Files.isDirectory(migrations)||Files.isSymbolicLink(migrations))throw new IllegalArgumentException();
   String user=System.getenv("IAS_MIGRATION_USER"),password=System.getenv("IAS_MIGRATION_PASSWORD");
   if(user==null||!user.matches("[A-Za-z0-9_]+")||password==null||password.isBlank())throw new IllegalArgumentException();
   String schema=args[0],url="jdbc:mysql://127.0.0.1:3307/"+schema+"?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
   try(var connection=DriverManager.getConnection(url,user,password);var query=connection.createStatement();var result=query.executeQuery("SELECT @@port,@@datadir,DATABASE()")){
    if(!result.next()||result.getInt(1)!=3307||!schema.equals(result.getString(3))||!result.getString(2).replace('\\','/').equalsIgnoreCase("C:/ias-local/Develop/IAS-MySQL84/data/"))throw new IllegalStateException();
   }
   var flyway=Flyway.configure().dataSource(url,user,password).locations("filesystem:"+migrations).cleanDisabled(true).validateOnMigrate(true).load();
   flyway.migrate();flyway.validate();var repeat=flyway.migrate();if(repeat.migrationsExecuted!=0)throw new IllegalStateException();flyway.validate();
   System.out.println("IAS_MIGRATION_REENTRANT_OK:"+schema);
  }catch(Exception e){System.err.println("IAS_MIGRATION_FAILED");System.exit(1);}
 }
}
