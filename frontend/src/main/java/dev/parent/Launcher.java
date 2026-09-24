package dev.parent;

/**
 * Entry point of the packaged application.
 *
 * Having a main class that does NOT extend {@code javafx.application.Application}
 * lets the fat jar / jpackage image start the JavaFX runtime from the class path
 * without module-path headaches.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        killOrphanedCopies();
        VetApp.main(args);
    }

    /**
     * Self-defense: older 4.0 builds left VetCustomerManager.exe running after
     * the app was closed. At every launch, any orphan copy that is not this JVM
     * gets killed. Best effort, Windows only.
     * Database is now online-only – no clinic-server process to spare.
     */
    private static void killOrphanedCopies() {
        try {
            if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
                return;
            }
            long self = ProcessHandle.current().pid();
            String killList = "VetCustomerManager.exe' OR Name='scan-cam.exe' OR Name='vetms-server.exe";
            String ps = "Get-CimInstance Win32_Process -Filter \"Name='" + killList + "'\" "
                    + "| Where-Object { $_.ProcessId -ne " + self + " } "
                    + "| ForEach-Object { try { Stop-Process -Id $_.ProcessId -Force -ErrorAction Stop } catch {} }";
            new ProcessBuilder("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", ps)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor();
        } catch (Throwable ignored) {
            // never let hygiene break the launch
        }
    }
}
