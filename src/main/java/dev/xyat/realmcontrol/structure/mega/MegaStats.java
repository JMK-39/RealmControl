package dev.xyat.realmcontrol.structure.mega;

import java.util.concurrent.atomic.AtomicLong;

/** How long placing mega tiles has taken since the last reset, over how many tile parts (a tile within one chunk). */
public final class MegaStats {
    private static final AtomicLong NANOS = new AtomicLong(), PARTS = new AtomicLong(), SLOWEST = new AtomicLong();

    private MegaStats() {
    }

    static void record(long nanos) {
        NANOS.addAndGet(nanos);
        PARTS.incrementAndGet();
        SLOWEST.accumulateAndGet(nanos, Math::max);
    }

    public static long nanos() {
        return NANOS.get();
    }

    public static long parts() {
        return PARTS.get();
    }

    public static long slowestNanos() {
        return SLOWEST.get();
    }

    public static void reset() {
        NANOS.set(0L);
        PARTS.set(0L);
        SLOWEST.set(0L);
    }
}
