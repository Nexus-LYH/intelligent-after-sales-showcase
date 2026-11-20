package com.ias.identity;

import com.ias.identity.auth.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ContextConfiguration;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Stops/restarts only the independently verified 3307 instance; never touches MySQL84 on 3306. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="logging.level.com.zaxxer.hikari=ERROR")
@ContextConfiguration(initializers=AuthFlowTest.MigrateBeforeContext.class)
class DatabaseOutageTest {
    @LocalServerPort int port;
    @Autowired PasswordEncoder encoder;
    final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    final JsonMapper json=JsonMapper.builder().build();
    private HttpResponse<String> request(String method,String path,String cookie,String payload,Map<String,String> headers) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(12));
        if (cookie!=null) builder.header("Cookie",cookie);
        headers.forEach(builder::header);
        if (method.equals("POST")) builder.header("Content-Type","application/json");
        return client.send(builder.method(method,payload==null ? HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(payload)).build(),HttpResponse.BodyHandlers.ofString());
    }
    @SuppressWarnings("unchecked") private Map<String,Object> body(HttpResponse<String> response) { return json.readValue(response.body(),Map.class); }
    private String cookie(HttpResponse<String> response,String name) {
        return response.headers().allValues("Set-Cookie").stream().filter(value->value.startsWith(name+"=")&&!value.contains("Max-Age=0")).map(value->value.split(";",2)[0]).findFirst().orElseThrow();
    }
    private void databaseScript(String name) throws Exception {
        Path binaries=Path.of("C:/ias-local/Develop/mysql8.4/main/bin");
        if (name.startsWith("Stop")) {
            try (var guard=TestDatabase.connection()) { } // Guard port, datadir and schema before shutdown.
            long serverPid=Long.parseLong(Files.readString(Path.of("C:/ias-local/Develop/IAS-MySQL84/mysql.pid")).strip());
            ProcessHandle oldServer=ProcessHandle.of(serverPid).orElseThrow();
            Process process=new ProcessBuilder(binaries.resolve("mysql.exe").toString(),
                "--defaults-file=C:/ias-local/Develop/IAS-MySQL84/secrets/mysql-root.cnf","--execute=SHUTDOWN;")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            process.getOutputStream().close();
            assertTrue(process.waitFor(10,TimeUnit.SECONDS),"Dedicated shutdown command timed out");
            assertEquals(0,process.exitValue(),"Dedicated shutdown command failed");
            // TCP port release precedes InnoDB file-handle release on Windows. Wait for actual process exit.
            oldServer.onExit().get(15,TimeUnit.SECONDS);
            long end=System.nanoTime()+10_000_000_000L;
            while (System.nanoTime()<end) {
                try (var socket=new java.net.Socket()) { socket.connect(new java.net.InetSocketAddress("127.0.0.1",3307),100); }
                catch (java.io.IOException stopped) { return; }
                Thread.sleep(100);
            }
            fail("Dedicated server did not release its port");
        } else {
            // A failed shutdown can leave the verified server running; do not start a second instance.
            try (var guard=TestDatabase.connection()) { return; }
            catch (java.sql.SQLException unavailable) { /* Start the dedicated instance below. */ }
            // Native process without a console or inherited pipes. No Windows service/global configuration changes.
            Process server=new ProcessBuilder(binaries.resolve("mysqld.exe").toString(),"--defaults-file=C:/ias-local/Develop/IAS-MySQL84/my.ini")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            server.getOutputStream().close();
            // InnoDB crash recovery on the local Windows installation can take over 20 seconds.
            long end=System.nanoTime()+90_000_000_000L;
            while (System.nanoTime()<end) {
                try (var guard=TestDatabase.connection()) { return; }
                catch (java.sql.SQLException starting) { Thread.sleep(100); }
            }
            fail("Dedicated server did not become ready");
        }
    }
    @Test void actualDatabaseUnavailableNeverReturnsOldIdentityOrSuccessfulLogout() throws Exception {
        String suffix=UUID.randomUUID().toString().replace("-","").substring(0,12), tenantCode="OUT_"+suffix.toUpperCase(), account="out."+suffix;
        String password=new AuthCrypto().randomToken();
        TestDatabase.update("INSERT INTO tenant(code_normalized,name,status,created_at,updated_at) VALUES (?,'故障测试租户',1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",tenantCode);
        long tenant=TestDatabase.scalar("SELECT id FROM tenant WHERE code_normalized=?",tenantCode);
        TestDatabase.update("INSERT INTO identity_user(realm,tenant_id,account_normalized,identity_type,display_name,password_verifier,status,must_change_password,binding_state,created_at,updated_at) VALUES ('TENANT',?,?,'EMPLOYEE','故障测试用户',?,1,0,0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",tenant,account,encoder.encode(password));
        var pre=request("GET","/api/auth/csrf",null,null,Map.of()); assertEquals(200,pre.statusCode());
        var login=request("POST","/api/auth/login",cookie(pre,AuthService.PREAUTH_COOKIE),json.writeValueAsString(Map.of("realm","TENANT","tenantCode",tenantCode,"account",account,"password",password)),Map.of("Origin","http://127.0.0.1:8081","X-CSRF-TOKEN",(String)body(pre).get("token"),"X-Login-Attempt-Id",UUID.randomUUID().toString()));
        assertEquals(200,login.statusCode()); String auth=cookie(login,AuthService.AUTH_COOKIE);
        var confirmed=request("GET","/api/auth/me",auth,null,Map.of()); assertEquals(200,confirmed.statusCode());
        auth=cookie(confirmed,AuthService.AUTH_COOKIE);
        var authCsrf=request("GET","/api/auth/csrf",auth,null,Map.of());
        var pending=request("GET","/api/auth/csrf",null,null,Map.of());
        try {
            databaseScript("Stop-IdentityMySQL.ps1");
            for (String path:List.of("/api/auth/me","/api/auth/csrf")) {
                var unavailable=request("GET",path,auth,null,Map.of()); assertEquals(503,unavailable.statusCode());
                assertEquals("IDENTITY_UNAVAILABLE",body(unavailable).get("code")); assertTrue(unavailable.headers().allValues("Set-Cookie").isEmpty());
            }
            var logout=request("POST","/api/auth/logout",auth,null,Map.of("Origin","http://127.0.0.1:8081","X-CSRF-TOKEN",(String)body(authCsrf).get("token")));
            assertEquals(503,logout.statusCode()); assertTrue(logout.headers().allValues("Set-Cookie").isEmpty());
            var failedLogin=request("POST","/api/auth/login",cookie(pending,AuthService.PREAUTH_COOKIE),json.writeValueAsString(Map.of("realm","TENANT","tenantCode",tenantCode,"account",account,"password",password)),Map.of("Origin","http://127.0.0.1:8081","X-CSRF-TOKEN",(String)body(pending).get("token"),"X-Login-Attempt-Id",UUID.randomUUID().toString()));
            assertEquals(503,failedLogin.statusCode()); assertTrue(failedLogin.headers().allValues("Set-Cookie").isEmpty());
        } finally { databaseScript("Start-IdentityMySQL.ps1"); }
        boolean recovered=false;
        for (int retry=0;retry<10;retry++) {
            var verified=request("GET","/api/auth/me",auth,null,Map.of());
            if (verified.statusCode()==200) { recovered=true; break; }
            assertEquals(503,verified.statusCode(),"During pool recovery only fail-closed unavailability is acceptable");
            assertTrue(verified.headers().allValues("Set-Cookie").isEmpty());
            Thread.sleep(500);
        }
        assertTrue(recovered,"Database recovered but authoritative identity verification did not recover");
    }
}
