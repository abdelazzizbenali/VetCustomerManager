package dev.vetms.server.api;

import dev.parent.config.AppConfig;
import dev.parent.db.SupabaseClient;
import dev.vetms.server.VetmsServer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Liveness + database status.
 * {@code /api/health} is token-free so any clinic PC can check "is the
 * server machine reachable?" in a browser. Camera checks are gone - the
 * camera lives in the client app now; this server only speaks REST.
 */
@RestController
@RequestMapping("/api")
public final class HealthController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "up");
        m.put("service", "vetms-server (clinic data relay)");
        m.put("version", VetmsServer.VERSION);
        return m;
    }

    /** What a sister PC / browser sees when diagnosing the server machine. */
    @GetMapping("/checks")
    public Map<String, Object> checks() {
        AppConfig cfg = AppConfig.get();
        cfg.load();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("backend", "up");
        m.put("supabaseConfigured", cfg.hasSupabase());
        if (cfg.hasSupabase()) {
            try {
                int v = new SupabaseClient(cfg.supabaseUrl(), cfg.supabaseKey()).ping();
                m.put("supabaseOnline", true);
                m.put("supabaseDetail", "connected, schema v" + v);
            } catch (Throwable t) {
                m.put("supabaseOnline", false);
                m.put("supabaseDetail", t.getMessage() == null ? "unreachable" : t.getMessage());
            }
        } else {
            m.put("supabaseOnline", false);
            m.put("supabaseDetail", "not configured");
        }
        m.put("relay", "/api/supabase/** relays every REST call for sister PCs");
        return m;
    }
}
