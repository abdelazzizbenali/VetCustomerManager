package dev.vetms.server.api;

import tools.jackson.databind.JsonNode;
import dev.parent.config.AppConfig;
import dev.parent.db.Json;
import dev.parent.db.SupabaseClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Server-side product resolution, straight from Supabase - the client asks
 * the backend instead of knowing the database protocol itself.
 */
@RestController
@RequestMapping("/api/products")
public final class ProductsController {

    @GetMapping("/by-barcode/{code}")
    public ResponseEntity<JsonNode> byBarcode(@PathVariable String code) throws Exception {
        AppConfig cfg = AppConfig.get();
        if (!cfg.hasSupabase()) {
            return ResponseEntity.status(503).body(Json.MAPPER.createObjectNode()
                    .put("found", false).put("reason", "backend not configured"));
        }
        String enc = URLEncoder.encode(code.trim(), StandardCharsets.UTF_8);
        JsonNode rows = new SupabaseClient(cfg.supabaseUrl(), cfg.supabaseKey())
                .get("/rest/v1/medicines?barcode=eq." + enc + "&select=*&limit=1");
        if (rows.isArray() && !rows.isEmpty()) {
            return ResponseEntity.ok(rows.get(0));
        }
        return ResponseEntity.status(404).body(Json.MAPPER.createObjectNode()
                .put("found", false).put("barcode", code));
    }

    @GetMapping("/search")
    public Object search(@RequestParam("q") String q,
                         @RequestParam(value = "limit", defaultValue = "40") int limit) throws Exception {
        AppConfig cfg = AppConfig.get();
        if (!cfg.hasSupabase()) {
            return Map.of("results", java.util.List.of(), "reason", "backend not configured");
        }
        String enc = URLEncoder.encode(q.trim(), StandardCharsets.UTF_8);
        return new SupabaseClient(cfg.supabaseUrl(), cfg.supabaseKey())
                .get("/rest/v1/medicines?or=(name.ilike.*" + enc + "*,barcode.ilike.*" + enc
                        + "*,category.ilike.*" + enc + "*)&select=*&order=name.asc&limit="
                        + Math.max(1, Math.min(200, limit)));
    }
}
