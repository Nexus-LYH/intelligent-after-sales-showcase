package com.ias.identity.bootstrap;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Launcher explicitly selected by the offline script; regular java -jar never invokes this class. */
public final class EmployeeBootstrapMain {
    public static void main(String[] args){
        String stage="INPUT";
        try{
            if(args.length!=0)throw new IllegalArgumentException("No password or account command-line arguments");
            Path plan=Path.of(required("IAS_BOOTSTRAP_PLAN")),secret=Path.of(required("IAS_BOOTSTRAP_SECRET_FILE"));
            if(Files.size(plan)>65536||Files.size(secret)>65536)throw new IllegalArgumentException("Inputs too large");
            var json=JsonMapper.builder().build();
            stage="PLAN";var spec=json.readValue(Files.readString(plan),AccountInitializer.Plan.class);
            stage="SECRETS";@SuppressWarnings("unchecked") Map<String,String> passwords=json.readValue(Files.readString(secret),Map.class);
            stage="CONNECT";try(var c=DriverManager.getConnection("jdbc:mysql://127.0.0.1:3307/ias_identity_test?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",required("IAS_INITIALIZER_USER"),required("IAS_INITIALIZER_PASSWORD"))){
                stage="INITIALIZE";var result=AccountInitializer.initializeEmployees(c,spec,passwords,new Argon2PasswordEncoder(16,32,1,19456,2));
                System.out.println(json.writeValueAsString(Map.of("state","COMMITTED","objects",result)));
            }
        }catch(Exception e){
            // Do not print exception messages/SQL/JSON payloads; COMMIT acknowledgement may be unknown.
            System.err.println("{\"code\":\"INITIALIZATION_NOT_CONFIRMED\",\"nextAction\":\"CHECK_PLAN_AND_RERUN_SAME_PLAN\"}");
            System.err.println("{\"stage\":\""+stage+"\",\"exceptionType\":\""+e.getClass().getSimpleName()+"\",\"sqlState\":\""+(e instanceof SQLException sql?sql.getSQLState():"NONE")+"\"}");
            System.exit(1);
        }
    }
    private static String required(String key){String value=System.getenv(key);if(value==null||value.isBlank())throw new IllegalArgumentException("Missing injected input");return value;}
}
