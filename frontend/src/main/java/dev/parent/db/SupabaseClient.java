package dev.parent.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import dev.parent.config.Log;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Thin client over the Supabase REST API (PostgREST). This is the exact same
 * API the future mobile applications will use, so every device speaks one
 * protocol against the online database.
 */
public final class SupabaseClient {

    /** Must match {@code app_meta.schema_version} installed by schema.sql. */
    public static final int EXPECTED_SCHEMA_VERSION = 1;

    private static final int PAGE_SIZE = 1000;

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient http;

    public SupabaseClient(String baseUrl, String apiKey) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** Friendly error carrying the HTTP status so the UI can give useful hints. */
    public static final class ApiException extends IOException {
        private final int status;

        public ApiException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    /** Accepts "xyz.supabase.co", "https://xyz.supabase.co/", ... and normalizes it. */
    public static String normalizeBaseUrl(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return "";
        }
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            s = "https://" + s;
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    public String baseUrl() {
        return baseUrl;
    }

    // ------------------------------------------------------------------ ping

    /**
     * Verifies connectivity, credentials and that the schema was installed.
     *
     * @return the installed schema version
     */
    public int ping() throws IOException, InterruptedException {
        HttpRequest req = base("/rest/v1/app_meta?select=app,schema_version&limit=1").GET().build();
        JsonNode body = send(req);
        if (!body.isArray() || body.isEmpty()) {
            throw new ApiException(428,
                    "The database answered but the VetCustomerManager tables were not found. " +
                    "Run supabase/schema.sql in the Supabase SQL editor first.");
        }
        return Json.integer(body.get(0), "schema_version");
    }

    // ------------------------------------------------------------- pull rows

    /** Fetches rows whose {@code updated_at} is strictly after {@code since} (or all rows when null). */
    public List<JsonNode> fetchUpdated(String table, Instant since) throws IOException, InterruptedException {
        List<JsonNode> all = new ArrayList<>();
        int offset = 0;
        while (true) {
            StringBuilder url = new StringBuilder("/rest/v1/").append(table).append("?select=*");
            url.append("&order=updated_at.asc");
            if (since != null) {
                url.append("&updated_at=gt.").append(URLEncoder.encode(since.toString(), StandardCharsets.UTF_8));
            }
            url.append("&limit=").append(PAGE_SIZE).append("&offset=").append(offset);
            JsonNode page = send(base(url.toString()).GET().build());
            if (!page.isArray()) {
                throw new IOException("Unexpected reply for table " + table + ": " + page);
            }
            page.forEach(all::add);
            if (page.size() < PAGE_SIZE) {
                break;
            }
            offset += PAGE_SIZE;
        }
        return all;
    }

    // ------------------------------------------------------------- push rows

    /** Upserts one row (matched on its {@code uuid}) and returns the server row. */
    public JsonNode upsert(String table, JsonNode row) throws IOException, InterruptedException {
        HttpRequest req = base("/rest/v1/" + table + "?on_conflict=uuid")
                .header("Prefer", "return=representation,resolution=merge-duplicates")
                .POST(HttpRequest.BodyPublishers.ofByteArray(Json.MAPPER.writeValueAsBytes(row)))
                .build();
        JsonNode body = send(req);
        if (body.isArray() && !body.isEmpty()) {
            return body.get(0);
        }
        if (body instanceof ArrayNode) {
            throw new IOException(table + " upsert returned no row (row rejected?)");
        }
        return body;
    }

    // ------------------------------------------------------------------ core

    /**
     * Where every REST call goes. If the user configured a clinic server
     * (Settings -> "Clinic server"), the same Supabase REST path is relayed
     * through it ({@code /api/supabase/**}) using the server's bearer token;
     * otherwise the request goes straight to the Supabase project.
     */
    private HttpRequest.Builder base(String pathAndQuery) {
        dev.parent.config.AppConfig cfg = dev.parent.config.AppConfig.get();
        if (cfg.hasRemoteServer()) {
            String relay = cfg.remoteServerUrl()
                    + "/api/supabase" + pathAndQuery.substring("/rest/v1".length());
            return HttpRequest.newBuilder()
                    .uri(URI.create(relay))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + cfg.remoteServerToken())
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("X-Client-Info", "vetcustomermanager-desktop/4.0.0 (via clinic server)");
        }
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + pathAndQuery))
                .timeout(Duration.ofSeconds(30))
                .header("apikey", apiKey)
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("X-Client-Info", "vetcustomermanager-desktop/4.0.0");
    }

    private JsonNode send(HttpRequest req) throws IOException, InterruptedException {
        HttpResponse<byte[]> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IllegalArgumentException e) {
            throw new ApiException(0, "Invalid Supabase URL: " + e.getMessage());
        }
        int status = resp.statusCode();
        if (status >= 200 && status < 300) {
            byte[] bytes = resp.body();
            if (bytes == null || bytes.length == 0) {
                return Json.MAPPER.createArrayNode();
            }
            return Json.parse(bytes);
        }
        String detail = resp.body() == null ? "" : new String(resp.body(), StandardCharsets.UTF_8);
        Log.warn("Supabase " + req.method() + " " + req.uri() + " -> " + status + " " + abbreviate(detail));
        throw new ApiException(status, explainStatus(status) + (detail.isBlank() ? "" : " - " + abbreviate(detail)));
    }

    private static String abbreviate(String s) {
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 220 ? s.substring(0, 220) + "..." : s;
    }

    private static String explainStatus(int status) {
        return switch (status) {
            case 400 -> "The request was rejected by the database";
            case 401, 403 -> "Wrong API key (check the anon public key)";
            case 404 -> "Table not found - run supabase/schema.sql in the SQL editor";
            case 409 -> "Conflict while saving";
            default -> "Database answered with HTTP " + status;
        };
    }
}
