package dev.parent.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves (and creates) the per-user application data directory:
 *   Windows : %APPDATA%\VetCustomerManager
 *   macOS   : ~/Library/Application Support/VetCustomerManager
 *   Linux   : ~/.vetcustomermanager
 *
 * Everything the program needs at runtime lives there:
 *   config.properties  - the Supabase URL / API key and device settings
 *   data.sqlite        - the local offline cache of the online database
 *   app.log            - diagnostic log
 */
public final class AppDirs {

    private static Path dataDir;

    private AppDirs() {
    }

    public static synchronized Path dataDir() {
        if (dataDir == null) {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                String appData = System.getenv("APPDATA");
                dataDir = (appData != null && !appData.isBlank()
                        ? Path.of(appData)
                        : Path.of(System.getProperty("user.home"), "AppData", "Roaming"))
                        .resolve("VetCustomerManager");
            } else if (os.contains("mac")) {
                dataDir = Path.of(System.getProperty("user.home"),
                        "Library", "Application Support", "VetCustomerManager");
            } else {
                dataDir = Path.of(System.getProperty("user.home"), ".vetcustomermanager");
            }
        }
        return dataDir;
    }

    /** Creates the directory (and proves it is writable). */
    public static synchronized Path ensure() {
        Path dir = dataDir();
        try {
            Files.createDirectories(dir);
            Path probe = dir.resolve(".write-test");
            Files.writeString(probe, "ok");
            Files.deleteIfExists(probe);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write to " + dir, e);
        }
        return dir;
    }

    public static Path configFile() {
        return dataDir().resolve("config.properties");
    }

    public static Path databaseFile() {
        return dataDir().resolve("data.sqlite");
    }

    public static Path logFile() {
        return dataDir().resolve("app.log");
    }
}
