package dev.xyat.realmcontrol.teleport.util;

public final class CommandFlag {
    private static final ThreadLocal<Boolean> TELEPORT_COMMAND = ThreadLocal.withInitial(() -> false);

    private CommandFlag() {
    }

    public static void set(boolean value) {
        TELEPORT_COMMAND.set(value);
    }

    public static boolean get() {
        return TELEPORT_COMMAND.get();
    }
}
