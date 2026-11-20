package com.ias.notification;
import jakarta.servlet.http.HttpServletRequest;
import com.ias.trust.Problem;
import java.util.List;

public interface NotificationAccess {
    record Actor(long tenant,long user){public Actor{if(tenant<=0||user<=0)throw new Problem(401,"SESSION_INVALID");}}
    record Recipient(long user,String kind){public Recipient{if(user<=0||!List.of("APPLICANT","REVIEWER","ENGINEER","FINANCE","CUSTOMER_ADMIN","CUSTOMER").contains(kind))throw new Problem(503,"DEPENDENCY_UNAVAILABLE");}}
    Actor actor(HttpServletRequest request,boolean write);
    List<Recipient> recipients(DomainEvent event,HttpServletRequest request);
    boolean visible(Actor actor,DomainEvent event,String kind,HttpServletRequest request);
    final class Unavailable implements NotificationAccess {
        private Problem failure(){return new Problem(503,"NOTIFICATION_TRUST_NOT_CONFIGURED");}
        public Actor actor(HttpServletRequest r,boolean write){throw failure();}
        public List<Recipient> recipients(DomainEvent e,HttpServletRequest r){throw failure();}
        public boolean visible(Actor a,DomainEvent e,String k,HttpServletRequest r){throw failure();}
    }
}
