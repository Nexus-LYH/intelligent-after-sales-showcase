import com.sun.net.httpserver.*;
import javax.net.ssl.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.nio.charset.StandardCharsets;

/** Disposable test-only TLS peer; accepts only the fixed synthetic ticket-identity edge. */
public class ProbeTlsFixture {
 public static void main(String[] args)throws Exception{
  var password="fixture-only".toCharArray();var store=KeyStore.getInstance("PKCS12");
  try(var in=Files.newInputStream(Path.of(args[0]))){store.load(in,password);}
  var keys=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());keys.init(store,password);
  var ssl=SSLContext.getInstance("TLS");ssl.init(keys.getKeyManagers(),null,null);
  var server=HttpsServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),8441),4);server.setHttpsConfigurator(new HttpsConfigurator(ssl));
  server.createContext("/internal/service-probe",exchange->{
   boolean valid="POST".equals(exchange.getRequestMethod())&&"ticket-identity".equals(exchange.getRequestHeaders().getFirst("X-IAS-Edge"))&&("Bearer "+"a".repeat(43)).equals(exchange.getRequestHeaders().getFirst("Authorization"));
   byte[] bytes=(valid?"{\"role\":\"identity\",\"state\":\"AUTHENTICATED\"}":"{}").getBytes(StandardCharsets.UTF_8);
   exchange.sendResponseHeaders(valid?200:403,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}
  });server.start();System.out.println("FIXTURE_READY");
 }
}
