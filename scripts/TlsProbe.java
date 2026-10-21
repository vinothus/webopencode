import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * A one-file HTTPS probe.
 *
 * <p>It tells you whether the JVM trusts the server it is talking to, using the truststore
 * that is in force right now. {@code doctor.bat} runs it to check the OpenCode gateway.</p>
 *
 * <p>Run it by hand:</p>
 * <pre>
 * java scripts\TlsProbe.java https://opencode.ai/zen/v1/models
 * java -Djavax.net.ssl.trustStore=lib\cacerts -Djavax.net.ssl.trustStorePassword=changeit ^
 *      scripts\TlsProbe.java https://opencode.ai/zen/v1/models
 * </pre>
 *
 * <p>Single-file source mode, so it needs a JDK 11 or later and nothing has to be compiled
 * first.</p>
 */
public class TlsProbe {

    public static void main(String[] args) {
        String trustStore = System.getProperty("javax.net.ssl.trustStore");
        System.out.println("  truststore: " + (trustStore == null ? "(the JDK default)" : trustStore));

        if (args.length < 1) {
            System.out.println("  no URL given");
            System.exit(2);
        }

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(args[0]))
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.println("  [ok] " + args[0] + " -> HTTP " + response.statusCode());
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            String message = String.valueOf(cause.getMessage()).split("\n")[0];
            System.out.println("  [X] " + args[0] + " -> " + cause.getClass().getSimpleName() + ": " + message);
            System.exit(1);
        }
    }
}
