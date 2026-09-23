package dev.parent.util;

import dev.parent.config.Log;
import javafx.application.Platform;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * One-line async for the whole UI, built on **Java 21 virtual threads**:
 * every screen loads its data off the FX thread so the interface never
 * freezes, even with thousands of rows or a slow disk.
 *
 * <pre>{@code
 * Bg.load(() -> dao.expensiveQuery(), rows -> table.getItems().setAll(rows));
 * }</pre>
 */
public final class Bg {

    private static final ExecutorService EXEC = Executors.newVirtualThreadPerTaskExecutor();

    private Bg() {
    }

    /** Runs {@code background} on a virtual thread, delivers the result on the FX thread. */
    public static <T> void load(Supplier<T> background, Consumer<T> foreground) {
        EXEC.submit(() -> {
            final T result;
            try {
                result = background.get();
            } catch (Throwable t) {
                Log.error("Background task failed", t);
                Platform.runLater(() ->
                        FxUtil.error(null, "Operation failed", String.valueOf(t.getMessage())));
                return;
            }
            Platform.runLater(() -> foreground.accept(result));
        });
    }

    /** Fire-and-forget work on a virtual thread (exceptions are logged, swallowed). */
    public static void run(String name, Runnable background) {
        EXEC.submit(() -> {
            Thread.currentThread().setName("bg-" + name);
            try {
                background.run();
            } catch (Throwable t) {
                Log.error("Background task '" + name + "' failed", t);
            }
        });
    }
}
