package dev.vetms.server.api;

import dev.parent.config.AppConfig;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * The clinic data relay: {@code /api/supabase/**} forwards ANY Supabase REST
 * call (method + path + query + body + Prefer headers) to the real Supabase
 * project, using the credentials configured ONLY on this server machine.
 *
 * <p>Why: a clinic can run many PCs with ONE database configuration -
 * put the Supabase URL/key into the server PC's Settings, run the
 * optional "Server" launcher there, and point every other PC at
 * {@code http://<server-ip>:9677} + the server token. Then everything
 * (clients, products, sales) stays identical across machines.</p>
 */
@RestController
@RequestMapping("/api/supabase")
public final class SupabaseProxyController {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @RequestMapping("/**")
    public ResponseEntity<byte[]> relay(HttpServletRequest request,
                                        @RequestBody(required = false) byte[] body) {
        AppConfig cfg = AppConfig.get();
        if (!cfg.hasSupabase()) {
            return ResponseEntity.status(503).body((
                    "{\"error\":\"clinic server is not linked to Supabase yet - "
                            + "set the URL/key in this PC's Settings first\"}")
                    .getBytes(StandardCharsets.UTF_8));
        }
        try {
            String uri = request.getRequestURI();
            String sub = uri.substring("/api/supabase".length());        // e.g. empty
            String query = request.getQueryString();                     // keep verbatim
            String target = cfg.supabaseUrl() + "/rest/v1" + sub
                    + (query == null || query.isBlank() ? "" : "?" + query);

            HttpRequest.Builder out = HttpRequest.newBuilder()
                    .uri(URI.create(target))
                    .timeout(Duration.ofSeconds(30))
                    .header("apikey", cfg.supabaseKey())
                    .header("Authorization", "Bearer " + cfg.supabaseKey())
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("X-Client-Info", "vetcustomermanager-clinic-server/4.0.0");
            String prefer = request.getHeader("Prefer");
            if (prefer != null && !prefer.isBlank()) {
                out.header("Prefer", prefer);
            }
            byte[] payload = body == null ? new byte[0] : body;
            out.method(request.getMethod(),
                    payload.length == 0
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofByteArray(payload));

            HttpResponse<byte[]> resp = http.send(out.build(), HttpResponse.BodyHandlers.ofByteArray());
            String contentType = resp.headers().firstValue("content-type").orElse("application/json");
            return ResponseEntity.status(resp.statusCode())
                    .header("Content-Type", contentType)
                    .body(resp.body());
        } catch (Throwable t) {
            return ResponseEntity.status(502).body((
                    "{\"error\":\"relay failed: "
                            + String.valueOf(t.getMessage()).replace("\"", "'") + "\"}")
                    .getBytes(StandardCharsets.UTF_8));
        }
    }
}
