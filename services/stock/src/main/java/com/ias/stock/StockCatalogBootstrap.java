package com.ias.stock;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.Properties;

/** Runs separately from Boot. Credentials are external; no environment contents or exception details are printed. */
public final class StockCatalogBootstrap {
    public static void main(String[] args){
        try {
            if(args.length!=1)throw new IllegalArgumentException();
            Path config=Path.of(System.getenv("IAS_STOCK_BOOTSTRAP_CONFIG")).toAbsolutePath().normalize();
            var settings=new Properties();try(var in=Files.newInputStream(config)){settings.load(in);}
            String url=settings.getProperty("jdbcUrl","");
            if(!url.matches("jdbc:mysql://127\\.0\\.0\\.1:3307/ias_stock_[A-Za-z0-9_]+(?:\\?[^#]*)?"))throw new IllegalArgumentException();
            long tenant=Long.parseLong(settings.getProperty("permittedTenantId","0"));
            var mapper=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).build();
            Path planPath=Path.of(args[0]);if(Files.size(planPath)>262144)throw new IllegalArgumentException();
            var plan=mapper.readValue(Files.readString(planPath),CatalogInitialization.Plan.class);
            try(var db=DriverManager.getConnection(url,settings.getProperty("user"),settings.getProperty("password"))){System.out.println(mapper.writeValueAsString(new CatalogInitialization().initialize(db,tenant,settings.getProperty("operatorReference"),plan)));}
        }catch(Exception failure){System.err.println("STOCK_CATALOG_INITIALIZATION_FAILED: reconcile using the same operation/plan; no connection or credential details emitted");System.exit(1);}
    }
}
