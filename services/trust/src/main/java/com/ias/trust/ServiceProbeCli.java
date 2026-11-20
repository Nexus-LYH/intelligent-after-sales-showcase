package com.ias.trust;

import java.util.*;

/** One authenticated local TLS probe. Private role config is loaded only through IAS_CHAIN_CONFIG. */
public final class ServiceProbeCli {
 private ServiceProbeCli(){}
 static String edge(String role){return switch(role){case "identity"->"ticket-identity";case "asset"->"ticket-asset";case "ticket"->"stock-ticket";case "approval"->"ticket-approval";case "stock"->"ticket-stock";case "notification"->"ticket-notification";case "finance"->"ticket-finance";default->throw new IllegalArgumentException();};}
 static int port(String role){return switch(role){case "identity"->8441;case "asset"->8442;case "ticket"->8443;case "approval"->8444;case "stock"->8445;case "notification"->8446;case "finance"->8447;default->throw new IllegalArgumentException();};}
 public static void main(String[] args){
  try{
   if(args.length!=1||!Chain.active())throw new IllegalArgumentException();String role=args[0],edge=edge(role);
   if(!edge.startsWith(Chain.role()+"-")||!Chain.base(role).equals("https://127.0.0.1:"+port(role)))throw new IllegalArgumentException();
   var response=Chain.call(edge,"/internal/service-probe",Map.of(),UUID.randomUUID().toString(),System.currentTimeMillis()+5000);
   if(!response.keySet().equals(Set.of("role","state"))||!role.equals(response.get("role"))||!"AUTHENTICATED".equals(response.get("state")))throw new IllegalArgumentException();
   System.out.print(Chain.JSON.writeValueAsString(Map.of("role",role,"httpVerified",true,"tlsVerified",true,"roleIdentityVerified",true,"trustAllUsed",false)));
  }catch(Exception e){System.err.print("SERVICE_PROBE_FAILED");System.exit(1);}
 }
}
