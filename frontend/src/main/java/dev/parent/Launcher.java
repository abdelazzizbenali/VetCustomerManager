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
     * Self-defense: older 4.0 builds left vetms-server.exe running (it kept
     * the camera hostage AFTER the app was closed/uninstalled). At every
     * launch, any copy of OUR processes that is not this JVM gets killed.
     * Best effort, Windows only, invisible everywhere else.
     */
    private static void killOrphanedCopies() {
        try {
            if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
                return; // nowhere else can a process outlive us
            }
            long self = ProcessHandle.current().pid();
            // A live server on THIS machine is deliberate (clinic-server PC):
            // its server-info.json points at a running pid -> spare it.
            String killList = "VetCustomerManager.exe' OR Name='scan-cam.exe";
            if (!clinicServerAlive()) {
                killList = "vetms-server.exe' OR Name='" + killList;
            }
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

    /** server-info.json names a still-running pid -> the server is wanted here. */
    private static boolean clinicServerAlive() {
        try {
            java.nio.file.Path file = dev.parent.config.AppDirs.dataDir().resolve("server-info.json");
            if (!java.nio.file.Files.isRegularFile(file)) {
                return false;
            }
            String text = java.nio.file.Files.readString(file);
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"pid\"\s*:\s*(\d+)").matcher(text);
            return m.find() && ProcessHandle.of(Long.parseLong(m.group(1))).isPresent();
        } catch (Throwable t) {
            return false;
        }
    }
}
