package com.ias.ticket;
import com.ias.trust.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import java.time.*;
import java.util.*;
/** Explicit offline operator process, never an HTTP route, runner or automatic identity impersonation. */
public final class TicketMaintenanceMain {
 public static void main(String[]args){boolean execute=Arrays.asList(args).contains("--execute");String scope=System.getenv("IAS_MAINTENANCE_IDS");Set<String> ids=new HashSet<>();if(scope!=null&&!scope.isEmpty())for(String id:scope.split(","))ids.add(Tickets.uuid(id));Instant now=Instant.now();String testNow=System.getenv("IAS_MAINTENANCE_TEST_NOW");if(testNow!=null){if(ids.isEmpty())throw new IllegalStateException("Controlled clock requires scoped IDs");now=Instant.parse(testNow);}if(!Chain.role().equals("ticket"))throw new IllegalStateException("Ticket role required");try(var context=new SpringApplicationBuilder(TicketApplication.class).web(WebApplicationType.NONE).run()){var result=context.getBean(Maintenance.class).run(now,execute,ids);System.out.println(Chain.JSON.writeValueAsString(Map.of("mode",execute?"EXECUTE":"DRY_RUN","controlledClock",testNow!=null,"actions",result)));}}
}
