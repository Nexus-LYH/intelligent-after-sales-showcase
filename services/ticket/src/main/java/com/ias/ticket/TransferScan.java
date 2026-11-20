package com.ias.ticket;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.scheduling.annotation.Scheduled;
import java.time.Instant;
@Component @ConditionalOnProperty(name="ias.ticket.transfer-scan.enabled",havingValue="true",matchIfMissing=true)
public class TransferScan {
 final Transfers transfers;public TransferScan(Transfers transfers){this.transfers=transfers;}
 @EventListener(ApplicationReadyEvent.class) public void start(){tick();}
 @Scheduled(fixedDelay=60000,initialDelay=60000) public void tick(){try{transfers.scanAt(Instant.now());transfers.scanOwners();}catch(RuntimeException e){System.err.println("transfer.scan retry type="+e.getClass().getSimpleName());}}
}
