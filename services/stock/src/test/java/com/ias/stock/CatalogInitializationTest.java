package com.ias.stock;
import org.h2.jdbcx.JdbcDataSource;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class CatalogInitializationTest {
    JdbcDataSource source()throws Exception{var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");try(var db=source.getConnection()){for(String migration:List.of("V1__stock_inbound.sql","V2__stock_catalog_initialization.sql"))RunScript.execute(db,new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/db/migration/"+migration)),StandardCharsets.UTF_8));}return source;}
    CatalogInitialization.Plan plan(UUID id){return new CatalogInitialization.Plan(id,List.of("Main"),List.of(new CatalogInitialization.Part("P1","Filter","M1","pcs",0)));}
    @Test void generatesIdsAndSamePlanReplaysWithoutDuplicatingOrCreatingBalance()throws Exception{var source=source();var command=plan(UUID.randomUUID());var service=new CatalogInitialization();Map<String,Object> first;try(var db=source.getConnection()){first=service.initialize(db,1,"operator-A",command);}try(var db=source.getConnection()){assertEquals(first,service.initialize(db,1,"operator-A",command));}try(var db=source.getConnection();var q=db.createStatement()){for(String table:List.of("stock_warehouse","stock_part","stock_catalog_initialization")){try(var rs=q.executeQuery("SELECT COUNT(*) FROM "+table)){rs.next();assertEquals(1,rs.getInt(1));}}try(var rs=q.executeQuery("SELECT COUNT(*) FROM stock_balance")){rs.next();assertEquals(0,rs.getInt(1));}}}
    @Test void alteredInitializationAndDuplicateCodesDoNotSilentlyCreateObjects()throws Exception{var source=source();var service=new CatalogInitialization();try(var db=source.getConnection()){service.initialize(db,1,"operator-A",plan(UUID.randomUUID()));}try(var db=source.getConnection()){assertThrows(IllegalStateException.class,()->service.initialize(db,1,"operator-A",plan(UUID.randomUUID())));}var part=new CatalogInitialization.Part("P1","Filter","M1","pcs",0);assertThrows(IllegalArgumentException.class,()->new CatalogInitialization.Plan(UUID.randomUUID(),List.of("Main"),List.of(part,part)));}
    @Test void existingPartConflictRollsBackEntireInitialization()throws Exception{var source=source();try(var db=source.getConnection();var q=db.createStatement()){q.execute("INSERT INTO stock_part VALUES(10,1,'P1','existing','M','pcs',TRUE,0)");}try(var db=source.getConnection()){assertThrows(Exception.class,()->new CatalogInitialization().initialize(db,1,"operator-A",plan(UUID.randomUUID())));}try(var db=source.getConnection();var q=db.createStatement()){for(String table:List.of("stock_warehouse","stock_catalog_initialization")){try(var rs=q.executeQuery("SELECT COUNT(*) FROM "+table)){rs.next();assertEquals(0,rs.getInt(1));}}}}
}
