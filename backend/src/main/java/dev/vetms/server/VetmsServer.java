package dev.vetms.server;

import tools.jackson.databind.ObjectMapper;
import dev.parent.config.AppConfig;
import dev.parent.config.AppDirs;
import dev.parent.config.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * VetCustomerManager SERVER - the clinic's central data relay. It holds the
 * ONE Supabase configuration of the clinic and forwards every REST call for
 * any number of sister PCs ({@code /api/supabase/**}), guarded by a per-boot
 * bearer token written to {@code server-info.json} in the per-user data
 * directory. The camera and the whole UI live in the client app; this
 * service speaks REST only.
 *
 * <p>Bind address: loopback by default; {@code VETMS_BIND=0.0.0.0} (or the
 * "Server" launcher launched with {@code VETMS_BIND} set) exposes it to the
 * LAN so every PC in the clinic shares one database.</p>
 */
@SpringBootApplication
@EnableScheduling
public class VetmsServer {

    public static final String VERSION = "4.0.0";
    /** Preferred loopback port; a random free one is taken when occupied. */
    private static final int DEFAULT_PORT = 9677;

    public static void main(String[] args) {
        AppConfig.get().load();

        int port = pickPort();
        String token = UUID.randomUUID().toString().replace("-", "");
        System.setProperty("vetms.token", token);
        writeServerInfo(port, token);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                Files.deleteIfExists(AppDirs.dataDir().resolve("server-info.json"));
            } catch (IOException ignored) {
            }
        }, "vetms-server-info-cleanup"));

        SpringApplication app = new SpringApplication(VetmsServer.class);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("server.port", String.valueOf(port));
        // LAN mode: run as the clinic's data server on all interfaces when
        // asked (VETMS_BIND=0.0.0.0), loopback otherwise for local pairing.
        props.put("server.address", System.getenv().getOrDefault("VETMS_BIND", "127.0.0.1"));
        props.put("spring.main.banner-mode", "off");
        props.put("spring.main.log-startup-info", "false");
        props.put("logging.level.root", "WARN");
        props.put("logging.level.dev.vetms.server", "INFO");
        // every incoming relay call rides on a virtual thread
        props.put("spring.threads.virtual.enabled", "true");
        props.put("spring.mvc.async.request-timeout", "-1");
        app.setDefaultProperties(props);
        app.run(args);

        String bind = System.getenv().getOrDefault("VETMS_BIND", "127.0.0.1");
        Log.info("vetms-server " + VERSION + " listening on " + bind + ":" + port
                + ("0.0.0.0".equals(bind) ? " (LAN mode - sister PCs can reach it on this machine's IP)" : ""));
    }

    private static int pickPort() {
        String env = System.getenv("VETMS_PORT");
        int wanted = env == null || env.isBlank() ? DEFAULT_PORT : Integer.parseInt(env.trim());
        if (isFree(wanted)) {
            return wanted;
        }
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort(); // occupied: take any free loopback port
        } catch (IOException e) {
            return wanted;
        }
    }

    private static boolean isFree(int port) {
        try (ServerSocket ignored = new ServerSocket(port)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void writeServerInfo(int port, String token) {
        try {
            Path file = AppDirs.dataDir().resolve("server-info.json");
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("port", port);
            info.put("token", token);
            info.put("pid", ProcessHandle.current().pid());
            info.put("version", VERSION);
            info.put("startedAt", java.time.Instant.now().toString());
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(file.toFile(), info);
        } catch (Throwable t) {
            Log.warn("Could not write server-info.json: " + t.getMessage());
        }
    }
}
