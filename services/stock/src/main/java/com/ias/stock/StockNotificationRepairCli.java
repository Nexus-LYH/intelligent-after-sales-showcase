package com.ias.stock;

import com.ias.trust.Chain;
import com.ias.trust.Problem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Local operator tool. Reads the original stock owner outbox and requests audited notification repair. */
public final class StockNotificationRepairCli {
    public static void main(String[] arguments){
        try{
            if(arguments.length!=1||!Chain.active()||!"stock".equals(Chain.role()))throw new IllegalArgumentException();
            var plan=Chain.JSON.readValue(Files.readString(Path.of(arguments[0])),Map.class);
            if(!plan.keySet().equals(Set.of("operationId","eventId","tenantId","reason","expectedEnvelopeSha256")))throw new IllegalArgumentException();
            String operation=uuid(plan.get("operationId")),eventId=uuid(plan.get("eventId"));
            long tenant=Long.parseLong((String)plan.get("tenantId"));
            String reason=(String)plan.get("reason"),digest=(String)plan.get("expectedEnvelopeSha256");
            if(tenant<=0||reason==null||reason.isBlank()||reason.length()>500||digest==null||!digest.matches("[a-f0-9]{64}"))throw new IllegalArgumentException();
            Map<?,?> event;
            try(var connection=DriverManager.getConnection(System.getenv("SPRING_DATASOURCE_URL"),System.getenv("SPRING_DATASOURCE_USERNAME"),System.getenv("SPRING_DATASOURCE_PASSWORD"))){
                try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT @@port,DATABASE(),@@datadir")){
                    rows.next();
                    if(rows.getInt(1)!=3307||!"ias_stock_test".equals(rows.getString(2))||!"C:/ias-local/Develop/IAS-MySQL84/data/".equalsIgnoreCase(rows.getString(3).replace('\\','/')))throw new IllegalStateException();
                }
                try(var statement=connection.prepareStatement("SELECT envelope,event_digest FROM stock_part_fee_notification_outbox WHERE tenant_id=? AND event_id=?")){
                    statement.setLong(1,tenant);statement.setString(2,eventId);
                    try(var rows=statement.executeQuery()){
                        if(!rows.next()||!digest.equals(rows.getString("event_digest")))throw new IllegalStateException();
                        event=Chain.JSON.readValue(rows.getString("envelope"),Map.class);
                    }
                }
            }
            var result=Chain.call("stock-notification","/internal/notification/suppressed-parts/repair",Map.of("operationId",operation,"event",event,"reason",reason),UUID.randomUUID().toString(),System.currentTimeMillis()+10000);
            if(!operation.equals(result.get("operationId"))||!eventId.equals(result.get("eventId")))throw new IllegalStateException();
            System.out.println(Chain.JSON.writeValueAsString(result));
        }catch(Problem failure){
            System.err.println("LOCAL_STOCK_NOTIFICATION_REPAIR_FAILED status="+failure.status+" code="+failure.code);System.exit(1);
        }catch(Exception failure){
            System.err.println("LOCAL_STOCK_NOTIFICATION_REPAIR_FAILED");System.exit(1);
        }
    }
    private static String uuid(Object value){
        if(!(value instanceof String text)||!UUID.fromString(text).toString().equals(text))throw new IllegalArgumentException();
        return text;
    }
    private StockNotificationRepairCli(){}
}
