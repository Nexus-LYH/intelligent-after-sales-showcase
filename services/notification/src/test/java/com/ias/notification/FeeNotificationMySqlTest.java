package com.ias.notification;

import com.mysql.cj.jdbc.MysqlDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.mock.web.MockHttpServletRequest;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in fresh-schema regression against the independently guarded local MySQL instance. */
@EnabledIfEnvironmentVariable(named="IAS_NOTIFICATION_MYSQL", matches="true")
class FeeNotificationMySqlTest {
    @Test void realFeeCursorCaptureSuppressionAndRollback() throws Exception {
        Properties options=new Properties();
        for(String line:Files.readAllLines(Path.of(System.getenv("IAS_NOTIFICATION_MYSQL_CONFIG")))) {
            if(!line.startsWith("[")&&line.contains("=")){int split=line.indexOf('=');options.setProperty(line.substring(0,split).strip(),line.substring(split+1).strip());}
        }
        String schema="qa_notification_"+UUID.randomUUID().toString().replace("-", "");
        MysqlDataSource db=new MysqlDataSource();
        db.setURL("jdbc:mysql://127.0.0.1:3307/?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        db.setUser(options.getProperty("user"));db.setPassword(options.getProperty("password"));
        try(var c=db.getConnection();var s=c.createStatement()) {
            try(var r=s.executeQuery("SELECT @@port,@@datadir")){r.next();assertEquals(3307,r.getInt(1));assertTrue(r.getString(2).replace('\\','/').toLowerCase().contains("ias-mysql84"));}
            s.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");
        }
        db.setURL("jdbc:mysql://127.0.0.1:3307/"+schema+"?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        try(var c=db.getConnection();var s=c.createStatement()) {
            for(String file:List.of("V1__durable_delivery.sql","V2__recipient_messages.sql","V3__fee_notifications.sql","V5__separate_notification_version_domains.sql","V6__backfill_notification_version_domains.sql","V7__suppressed_notification_repair_audit.sql","V8__suppression_repair_serialization.sql")) {
                String sql=new String(getClass().getResourceAsStream("/db/migration/"+file).readAllBytes(),StandardCharsets.UTF_8);
                for(String statement:sql.split(";"))if(!statement.isBlank())s.execute(statement);
            }
            MessageInbox inbox=new MessageInbox(db,new FeeNotificationTest.Access());
            var fixtures=new FeeNotificationTest();var request=new MockHttpServletRequest();
            var event=fixtures.event(UUID.randomUUID(),3,5);
            assertEquals("COMMITTED",inbox.receive(event,request).get("ack"));
            assertEquals("COMMITTED_DUPLICATE",inbox.receive(event,request).get("ack"));
            inbox.receive(fixtures.event(UUID.randomUUID(),2,9),request);
            inbox.receive(fixtures.event(UUID.randomUUID(),3,4),request);
            assertEquals(1,count(s,"notification_email_capture"));assertEquals(2,count(s,"fee_notification_suppression"));
            var retry=fixtures.event(UUID.randomUUID(),4,6);
            s.execute("ALTER TABLE notification_email_capture ADD CONSTRAINT injected_capture CHECK(event_id<>'"+retry.eventId()+"')");
            assertThrows(SQLException.class,()->inbox.receive(retry,request));
            assertEquals(3,count(s,"event_inbox"));assertEquals(1,count(s,"notification_message"));
            s.execute("ALTER TABLE notification_email_capture DROP CHECK injected_capture");
            assertEquals("COMMITTED",inbox.receive(retry,request).get("ack"));
            assertEquals(2,count(s,"notification_email_capture"));
            s.executeUpdate("DELETE FROM fee_authorization_notification_cursor");
            s.executeUpdate("DELETE FROM fee_event_notification_cursor");
            String backfill=new String(getClass().getResourceAsStream("/db/migration/V6__backfill_notification_version_domains.sql").readAllBytes(),StandardCharsets.UTF_8);
            for(String statement:backfill.split(";"))if(!statement.isBlank())s.execute(statement);
            assertEquals(1,count(s,"fee_authorization_notification_cursor"));
            assertEquals(2,count(s,"fee_event_notification_cursor"));
            var stock=fixtures.part(UUID.randomUUID(),4);
            var payload=new HashMap<>(stock.payload());payload.put("feeGroupId",event.payload().get("feeGroupId"));
            var sameGroupStock=new DomainEvent(stock.eventId(),stock.type(),1,stock.tenantId(),stock.aggregateId(),stock.aggregateVersion(),stock.occurredAt(),stock.producer(),stock.correlationId(),payload);
            assertEquals("COMMITTED",inbox.receive(sameGroupStock,request).get("ack"));
            assertEquals(3,count(s,"notification_email_capture"));
            var historical=fixtures.part(UUID.randomUUID(),4);
            s.executeUpdate("INSERT INTO event_inbox VALUES('notification','"+historical.eventId()+"',1,'"+historical.sha()+"',CURRENT_TIMESTAMP)");
            s.executeUpdate("INSERT INTO fee_notification_suppression VALUES('"+historical.eventId()+"',1,'"+historical.payload().get("feeGroupId")+"','OUT_OF_ORDER_VERSION',4,4,CURRENT_TIMESTAMP)");
            request.setSecure(true);request.setAttribute("ias.chain.edge","stock-notification");
            UUID repair=UUID.randomUUID();
            String runtime="qa_nr_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
            String password=UUID.randomUUID().toString()+UUID.randomUUID();
            s.execute("CREATE USER '"+runtime+"'@'127.0.0.1' IDENTIFIED BY '"+password+"'");
            for(String table:List.of("event_inbox","fee_notification_suppression","notification_suppression_repair"))s.execute("GRANT SELECT,INSERT ON "+schema+"."+table+" TO '"+runtime+"'@'127.0.0.1'");
            s.execute("GRANT SELECT,INSERT,UPDATE(object_type,object_id,scope_generation) ON "+schema+".notification_message TO '"+runtime+"'@'127.0.0.1'");
            s.execute("GRANT INSERT ON "+schema+".notification_email_capture TO '"+runtime+"'@'127.0.0.1'");
            s.execute("GRANT SELECT,INSERT,UPDATE(created_at) ON "+schema+".notification_suppression_repair_lock TO '"+runtime+"'@'127.0.0.1'");
            var restricted=new MysqlDataSource();restricted.setURL(db.getURL());restricted.setUser(runtime);restricted.setPassword(password);
            var repairInbox=new MessageInbox(restricted,new FeeNotificationTest.Access());
            var repaired=repairInbox.repairSuppressedPart(repair,historical,"verified legacy version-domain defect",request);
            assertEquals(repaired,repairInbox.repairSuppressedPart(repair,historical,"verified legacy version-domain defect",request));
            assertEquals(1,count(s,"notification_suppression_repair"));
            assertEquals(4,count(s,"notification_email_capture"));
            System.out.println("NOTIFICATION_MYSQL_FRESH_SCHEMA="+schema);
        }
    }
    static long count(Statement s,String table)throws SQLException{try(var r=s.executeQuery("SELECT COUNT(*) FROM "+table)){r.next();return r.getLong(1);}}
}
