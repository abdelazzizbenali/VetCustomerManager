package dev.parent.config;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Minimal file + console logger (kept dependency-free on purpose). */
public final class Log {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Log() {
    }

    public static void info(String msg) {
        write("INFO ", msg, null);
    }

    public static void warn(String msg) {
        write("WARN ", msg, null);
    }

    public static void error(String msg) {
        write("ERROR", msg, null);
    }

    public static void error(String msg, Throwable t) {
        write("ERROR", msg, t);
    }

    private static synchronized void write(String level, String msg, Throwable t) {
        String line = LocalDateTime.now().format(FMT) + " [" + level + "] " + msg;
        System.out.println(line);
        if (t != null) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            line += System.lineSeparator() + sw;
            t.printStackTrace();
        }
        try {
            Files.createDirectories(AppDirs.dataDir());
            Files.writeString(AppDirs.logFile(), line + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // logging must never break the application
        }
    }
}
