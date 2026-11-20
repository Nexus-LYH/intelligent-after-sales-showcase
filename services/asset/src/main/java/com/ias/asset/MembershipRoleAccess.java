package com.ias.asset;
import com.ias.trust.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
public interface MembershipRoleAccess {
 record Actor(long tenant,long user,String token,String csrf,String trace,long deadline){@Override public String toString(){return "MembershipRoleActor[redacted]";}}
 void current(Actor actor,boolean write);
 Set<Long> eligible(Actor actor,Collection<Long> users);
 final class Trusted implements MembershipRoleAccess {
  public Actor actor(HttpServletRequest r,boolean write){if(write)Chain.source(r);String token=Chain.cookie(r),csrf=write?Chain.single(r,"X-CSRF-TOKEN"):null;var f=Chain.identity("asset-identity",token,write?"ASSET_WRITE":"ASSET_READ",csrf,Chain.trace(r),Chain.deadline(r));Chain.fresh(f,Chain.deadline(r));check(f);return new Actor(number(f.get("tenantId")),number(f.get("userId")),token,csrf,Chain.trace(r),Chain.deadline(r));}
  static long number(Object value){try{long v=Long.parseLong(value.toString());if(v<1)throw new Exception();return v;}catch(Exception e){throw new Problem(503,"INVALID_MEMBERSHIP_IDENTITY_FACT");}}
  static void check(Map<String,Object> f){if(!"EMPLOYEE".equals(f.get("identityType"))||!((List<?>)f.getOrDefault("permissions",List.of())).contains("asset.manage"))throw new Problem(403,"ACCESS_DENIED");}
  public void current(Actor a,boolean write){var f=Chain.identity("asset-identity",a.token(),write?"ASSET_WRITE":"ASSET_READ",a.csrf(),a.trace(),a.deadline());Chain.fresh(f,a.deadline());check(f);if(number(f.get("tenantId"))!=a.tenant()||number(f.get("userId"))!=a.user())throw new Problem(401,"SESSION_INVALID");}
  public Set<Long> eligible(Actor a,Collection<Long> users){if(users.isEmpty())return Set.of();if(users.size()>100)throw new Problem(503,"ADMIN_ELIGIBILITY_SET_TOO_LARGE");var ids=users.stream().sorted().map(Object::toString).toList();var f=Chain.call("asset-identity","/internal/identity/customer-eligibility",Map.of("tenantId",Long.toString(a.tenant()),"userIds",ids),a.trace(),a.deadline());Chain.fresh(f,a.deadline());if(!Long.toString(a.tenant()).equals(f.get("tenantId"))||!ids.equals(f.get("userIds"))||!(f.get("eligibleUserIds") instanceof List<?> values))throw new Problem(503,"INVALID_CUSTOMER_ELIGIBILITY_FACT");var result=new HashSet<Long>();for(var value:values){long id=number(value);if(!users.contains(id)||!result.add(id))throw new Problem(503,"INVALID_CUSTOMER_ELIGIBILITY_FACT");}return result;}
 }
}
