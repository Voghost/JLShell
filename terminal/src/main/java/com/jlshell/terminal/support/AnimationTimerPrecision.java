package com.jlshell.terminal.support;

import com.sun.jna.Native;
import com.sun.jna.win32.StdCallLibrary;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.Supplier;

/** Process-local Windows timer lease, held only while at least one scroll animation is running. */
final class AnimationTimerPrecision {
    interface TimerLibrary {
        int begin(int period);
        int end(int period);
    }
    public interface WinMM extends StdCallLibrary {
        int timeBeginPeriod(int period);
        int timeEndPeriod(int period);
    }
    private static final class Holder {
        static final AnimationTimerPrecision INSTANCE = new AnimationTimerPrecision(
                System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows")
                        && !Boolean.getBoolean("jlshell.terminal.disablePrecisionTimer"),
                () -> {
                    WinMM api = Native.load("winmm", WinMM.class);
                    return new TimerLibrary() {
                        public int begin(int period) { return api.timeBeginPeriod(period); }
                        public int end(int period) { return api.timeEndPeriod(period); }
                    };
                });
    }
    static AnimationTimerPrecision shared() { return Holder.INSTANCE; }
    private final boolean enabled;
    private final Supplier<TimerLibrary> loader;
    private final Set<Object> owners = Collections.newSetFromMap(new IdentityHashMap<>());
    private TimerLibrary library;
    private boolean unavailable;

    AnimationTimerPrecision(boolean enabled, Supplier<TimerLibrary> loader) {
        this.enabled = enabled;
        this.loader = loader;
    }
    synchronized void request(Object owner) {
        if (!enabled || unavailable || owners.contains(owner)) return;
        try {
            if (owners.isEmpty()) {
                if (library == null) library = loader.get();
                if (library.begin(1) != 0) { unavailable = true; return; }
            }
            owners.add(owner);
        } catch (RuntimeException | LinkageError error) {
            unavailable = true;
            org.slf4j.LoggerFactory.getLogger(AnimationTimerPrecision.class)
                    .debug("High precision scroll timer unavailable; using the normal timer", error);
        }
    }
    synchronized void cancel(Object owner) {
        if (!owners.remove(owner) || !owners.isEmpty()) return;
        try { library.end(1); }
        catch (RuntimeException | LinkageError error) {
            unavailable = true;
            org.slf4j.LoggerFactory.getLogger(AnimationTimerPrecision.class)
                    .debug("Could not release high precision scroll timer", error);
        }
    }
}
