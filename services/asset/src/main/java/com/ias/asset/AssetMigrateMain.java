package com.ias.asset;
import org.flywaydb.core.Flyway;
import java.sql.*;
public final class AssetMigrateMain {
 public static void main(String[]args){
  String url="jdbc:mysql://127.0.0.1:3307/ias_asset_test?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",user=System.getenv("IAS_ASSET_MIGRATION_USER"),password=System.getenv("IAS_ASSET_MIGRATION_PASSWORD");
  try(var c=DriverManager.getConnection(url,user,password);var s=c.createStatement();var r=s.executeQuery("SELECT @@port,@@datadir,DATABASE()")){
   r.next();if(r.getInt(1)!=3307||!"ias_asset_test".equals(r.getString(3))||!"C:/ias-local/Develop/IAS-MySQL84/data/".equalsIgnoreCase(r.getString(2).replace('\\','/')))throw new IllegalStateException("Dedicated asset database required");
   Flyway.configure().dataSource(url,user,password).locations("classpath:db/migration").cleanDisabled(true).load().migrate();
  }catch(Exception e){System.err.println("Asset migration not confirmed; inspect restricted operator configuration.");System.exit(1);}
 }
}
