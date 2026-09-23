package dev.parent.util;

import dev.parent.config.AppConfig;
import dev.parent.config.Log;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Plays the little scan-feedback sounds bundled with the app
 * ({@code Found.wav} / {@code notFound.wav}). Sounds are cached in memory
 * so replaying them while scanning fast is instant and never touches disk.
 */
public final class SoundPlayer {

    public static final String FOUND = "/dev/parent/res/Found.wav";
    public static final String NOT_FOUND = "/dev/parent/res/notFound.wav";

    private static final Map<String, byte[]> CACHE = new HashMap<>();
    private static volatile boolean enabled = true;

    private SoundPlayer() {
    }

    public static void setEnabled(boolean on) {
        enabled = on;
    }

    /** Quick probe used by the startup checks: is there any audio output at all? */
    public static boolean audioAvailable() {
        try {
            return AudioSystem.getMixerInfo().length > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void playFound() {
        play(FOUND);
    }

    public static void playNotFound() {
        play(NOT_FOUND);
    }

    public static void play(String resource) {
        if (!enabled || !AppConfig.get().soundsEnabled()) {
            return;
        }
        Thread t = new Thread(() -> doPlay(resource), "sound-player");
        t.setDaemon(true);
        t.start();
    }

    private static void doPlay(String resource) {
        try {
            byte[] bytes = CACHE.computeIfAbsent(resource, SoundPlayer::readResource);
            if (bytes.length == 0) {
                return;
            }
            try (AudioInputStream in = AudioSystem.getAudioInputStream(new ByteArrayInputStream(bytes))) {
                Clip clip = AudioSystem.getClip();
                clip.open(in);
                clip.start();
            }
        } catch (Throwable t) {
            Log.warn("Could not play sound " + resource + ": " + t.getMessage());
        }
    }

    private static byte[] readResource(String resource) {
        try (var in = SoundPlayer.class.getResourceAsStream(resource)) {
            return in == null ? new byte[0] : in.readAllBytes();
        } catch (Throwable t) {
            Log.warn("Sound resource missing: " + resource);
            return new byte[0];
        }
    }
}
