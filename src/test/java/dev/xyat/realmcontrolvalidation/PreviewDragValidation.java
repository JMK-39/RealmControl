//? if <1.21 {
package dev.xyat.realmcontrolvalidation;

import dev.xyat.kineticcore.api.client.event.KineticClientEvents;
import dev.xyat.kineticcore.api.client.gui.KineticGui;
import dev.xyat.kineticcore.api.client.gui.widget.KineticStructureView;
import dev.xyat.kineticcore.api.world.structure.KineticBlockSnapshot;
import dev.xyat.kineticcore.internal.client.gui.screen.KineticScreenHost;
import dev.xyat.kineticcore.internal.client.render.structure.StructurePreviewRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// Opt-in (-Drealmcontroldrag.guiValidation=true): opens the structure panel, previews large structures one after another
// and turns each with left drags sent through the screen, one move per frame as a mouse sends them. It logs how long
// each mesh took, the frame times meanwhile and while dragging, and, whenever the render thread has not finished a
// frame for 300 ms, what it is doing (its stack).
public final class PreviewDragValidation {
    private static final Logger LOG = LoggerFactory.getLogger(PreviewDragValidation.class);
    private static final String[] STRUCTURES = System.getProperty("realmcontroldrag.structures",
            "minecraft:village_plains,minecraft:village_taiga,minecraft:woodland_mansion,minecraft:bastion_remnant,minecraft:ancient_city").split(",");
    private static final long STALL_NANOS = 300_000_000L;
    private static final long SLOW_FRAME_NANOS = 100_000_000L;
    private static final long WAIT_MILLIS = 120_000L;
    private static final int DRAG_FRAMES = 150;

    private static boolean installed, started, finished;
    private static int index = -1, stage, dragFrame, failures, slowFrames;
    private static long stageSince;
    private static volatile Thread renderThread;
    private static volatile long lastFrame;
    private static volatile boolean watching;
    private static volatile String doing = "";
    private static final List<Long> intervals = new ArrayList<>(), eventNanos = new ArrayList<>();
    private static double startX, startY, dragX, dragY;
    private static float yawBefore, pitchBefore;

    private PreviewDragValidation() {}

    public static void install() {
        if (installed) return;
        installed = true;
        KineticClientEvents.onTick(KineticClientEvents.TickPhase.END, PreviewDragValidation::tick);
        KineticClientEvents.onScreenRenderBefore((screen, graphics, mouseX, mouseY, partialTick) -> beforeFrame(screen));
        KineticClientEvents.onScreenRenderAfter((screen, graphics, mouseX, mouseY, partialTick) -> afterFrame());
        Thread watchdog = new Thread(PreviewDragValidation::watch, "RealmControl preview drag watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    // Prints the render thread's stack when a frame has lasted 0.3 s, again at 1 s and every 2 s after while it lasts.
    private static void watch() {
        long frame = 0L;
        int dumps = 0;
        while (!finished) {
            try {
                Thread.sleep(25L);
            } catch (InterruptedException e) {
                return;
            }
            Thread render = renderThread;
            long last = lastFrame;
            if (!watching || render == null || last == 0L) continue;
            if (last != frame) {
                frame = last;
                dumps = 0;
            }
            long stalled = System.nanoTime() - last;
            long due = dumps == 0 ? STALL_NANOS : 1_000_000_000L + (dumps - 1) * 2_000_000_000L;
            if (stalled < due) continue;
            dumps++;
            StringBuilder trace = new StringBuilder();
            StackTraceElement[] stack = render.getStackTrace();
            for (int i = 0; i < Math.min(80, stack.length); i++) trace.append("\n\tat ").append(stack[i]);
            LOG.info("PREVIEW_DRAG_STALL doing={} ms={}{}", doing, stalled / 1_000_000L, trace);
        }
    }

    // While dragging, each frame gets one mouse move before the screen draws, as the mouse handler sends it.
    private static void beforeFrame(Screen screen) {
        if (finished || stage != 3 || !(screen instanceof KineticScreenHost host)) return;
        KineticStructureView view = view();
        if (view == null) return;
        long start = System.nanoTime();
        if (dragFrame == 0) {
            startX = dragX = host.toScreenX(view.controlX() + view.controlWidth() / 2D);
            startY = dragY = host.toScreenY(view.controlY() + view.controlHeight() / 2D);
            yawBefore = view.yaw();
            pitchBefore = view.pitch();
            StringBuilder under = new StringBuilder();
            for (var child : screen.children()) {
                if (child.isMouseOver(dragX, dragY)) under.append(' ').append(child.getClass().getName());
            }
            boolean clicked = screen.mouseClicked(dragX, dragY, 0);
            var focused = screen.getFocused();
            LOG.info("PREVIEW_DRAG_PRESS structure={} at={},{} handled={} focused={} dragging={} under=[{}] screen={}",
                    STRUCTURES[index], dragX, dragY, clicked, focused == null ? null : focused.getClass().getName(),
                    screen.isDragging(), under, screen.getClass().getName());
        } else if (dragFrame <= DRAG_FRAMES) {
            // Across for the first half, then down: a turn and a tilt.
            boolean across = dragFrame <= DRAG_FRAMES / 2;
            double dx = across ? 2 : 0, dy = across ? 0 : 1;
            dragX += dx;
            dragY += dy;
            boolean handled = screen.mouseDragged(dragX, dragY, 0, dx, dy);
            if (dragFrame <= 3) {
                var focused = screen.getFocused();
                LOG.info("PREVIEW_DRAG_MOVE frame={} handled={} focused={} dragging={} yaw={}", dragFrame, handled,
                        focused == null ? null : focused.getClass().getName(), screen.isDragging(), view.yaw());
            }
        } else if (dragFrame == DRAG_FRAMES + 1) {
            screen.mouseReleased(dragX, dragY, 0);
        }
        eventNanos.add(System.nanoTime() - start);
        dragFrame++;
    }

    private static void afterFrame() {
        long now = System.nanoTime();
        if (watching && lastFrame != 0L) {
            long interval = now - lastFrame;
            intervals.add(interval);
            if (interval > SLOW_FRAME_NANOS) slowFrames++;
        }
        lastFrame = now;
    }

    private static void tick() {
        if (finished) return;
        var mc = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        try {
            if (!started) {
                if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) return;
                started = true;
                renderThread = Thread.currentThread();
                openPanel();
                stageSince = now;
                return;
            }
            Object page = KineticGui.currentPage();
            if (index < 0) {
                if (page == null || !page.getClass().getSimpleName().equals("WorldGenPage")) {
                    require(now - stageSince < 30000L, "the structure panel did not open; showing " + mc.screen);
                    return;
                }
                next(page, now);
                return;
            }
            KineticStructureView view = view();
            switch (stage) {
                case 1 -> {
                    if (field(page, "previewResult").get(page) == null) {
                        require(now - stageSince < WAIT_MILLIS, "no sample within " + WAIT_MILLIS + " ms");
                        return;
                    }
                    KineticBlockSnapshot snapshot = view.snapshot();
                    LOG.info("PREVIEW_DRAG_SAMPLE structure={} size={}x{}x{} scale={} blocks={} containers={} waited={}ms",
                            STRUCTURES[index], snapshot.sizeX(), snapshot.sizeY(), snapshot.sizeZ(), snapshot.scale(),
                            snapshot.nonAirCount(), snapshot.containerCount(), now - stageSince);
                    begin(2, now, "mesh");
                }
                case 2 -> {
                    float built = StructurePreviewRuntime.progress(view.snapshot(), view.cutY());
                    if (built < 1F) {
                        require(now - stageSince < WAIT_MILLIS, "the mesh is at " + built + " after " + WAIT_MILLIS + " ms");
                        return;
                    }
                    report("mesh", now - stageSince);
                    // A moment of plain frames before the drag, so the drag's numbers are its own.
                    begin(4, now, "settle");
                }
                case 4 -> {
                    if (now - stageSince < 1000L) return;
                    // The view keeps its angle from the last structure; upside down, a sideways drag turns the other way.
                    view.resetView();
                    dragFrame = 0;
                    begin(3, now, "drag");
                }
                case 3 -> {
                    if (dragFrame <= DRAG_FRAMES + 1) {
                        require(now - stageSince < WAIT_MILLIS, "the drag did not finish: frame " + dragFrame);
                        return;
                    }
                    var host = (KineticScreenHost) mc.screen;
                    float expectedYaw = (float) (host.toVirtualX(dragX) - host.toVirtualX(startX)) * 0.8F;
                    float expectedPitch = (float) (host.toVirtualY(dragY) - host.toVirtualY(startY)) * 0.8F;
                    float turned = angle(view.yaw() - yawBefore), tilted = angle(view.pitch() - pitchBefore);
                    LOG.info("PREVIEW_DRAG_TURN structure={} yaw={} expected={} pitch={} expected={} eventMaxMs={}",
                            STRUCTURES[index], turned, expectedYaw, tilted, expectedPitch, Collections.max(eventNanos) / 1.0E6);
                    // The whole drag turns the view, not just its first moves.
                    if (Math.abs(turned - angle(expectedYaw)) > 1F || Math.abs(tilted - angle(expectedPitch)) > 1F) failures++;
                    report("drag", now - stageSince);
                    next(page, now);
                }
                default -> {
                }
            }
        } catch (Throwable error) {
            failures++;
            LOG.error("PREVIEW_DRAG_ERROR structure={} stage={}", index >= 0 && index < STRUCTURES.length ? STRUCTURES[index] : "-", stage, error);
            try {
                next(KineticGui.currentPage(), now);
            } catch (Throwable next) {
                finish();
            }
        }
    }

    private static void begin(int next, long now, String what) {
        stage = next;
        stageSince = now;
        doing = STRUCTURES[index] + " " + what;
        intervals.clear();
        eventNanos.clear();
        slowFrames = 0;
    }

    private static void report(String what, long millis) {
        List<Long> sorted = new ArrayList<>(intervals);
        Collections.sort(sorted);
        double avg = sorted.stream().mapToLong(Long::longValue).average().orElse(0D) / 1.0E6;
        double p95 = sorted.isEmpty() ? 0D : sorted.get(Math.min(sorted.size() - 1, (int) (sorted.size() * 0.95))) / 1.0E6;
        double max = sorted.isEmpty() ? 0D : sorted.get(sorted.size() - 1) / 1.0E6;
        LOG.info("PREVIEW_DRAG_FRAMES structure={} phase={} took={}ms frames={} avg={}ms p95={}ms max={}ms over100ms={}",
                STRUCTURES[index], what, millis, sorted.size(), Math.round(avg * 10) / 10D, Math.round(p95 * 10) / 10D,
                Math.round(max * 10) / 10D, slowFrames);
        // A frame of a quarter second or more is a visible hitch.
        if (max >= 250D) failures++;
    }

    private static void next(Object page, long now) throws Exception {
        watching = false;
        while (++index < STRUCTURES.length) {
            String id = STRUCTURES[index].trim();
            Object descriptor = null;
            for (Object candidate : (List<?>) field(page, "structureDescriptors").get(page)) {
                if (((dev.xyat.realmcontrol.worldgen.data.StructureRuleDescriptor) candidate).structureId().equals(id)) {
                    descriptor = candidate;
                    break;
                }
            }
            if (descriptor == null) {
                LOG.info("PREVIEW_DRAG_SKIP structure={} (not in this game)", id);
                continue;
            }
            invoke(page.getClass(), page, "selectStructure", descriptor);
            begin(1, now, "sample");
            lastFrame = 0L;
            watching = true;
            return;
        }
        finish();
    }

    private static void openPanel() throws Exception {
        var mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        mc.setScreen(null);
        // The panel takes the server's data, built as the in-game command builds it.
        var packet = server.submit(() -> {
            dev.xyat.realmcontrol.worldgen.config.WorldGenConfig.load();
            List<String> structures = new ArrayList<>();
            try {
                for (Object id : (List<?>) invoke(dev.xyat.realmcontrol.worldgen.network.WorldGenNetwork.class, null, "getAllStructureIds", server)) {
                    structures.add((String) id);
                }
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
            return new dev.xyat.realmcontrol.worldgen.network.WorldGenNetwork.OpenWorldGenGuiPacket(
                    dev.xyat.realmcontrol.worldgen.config.WorldGenConfig.enableStructureBlocking, structures,
                    dev.xyat.realmcontrol.worldgen.config.StructureGenerationControl.getDescriptors(server));
        }).join();
        KineticGui.openChild(new dev.xyat.realmcontrol.worldgen.client.gui.WorldGenPage(packet));
    }

    private static KineticStructureView view() {
        try {
            Object page = KineticGui.currentPage();
            return page == null ? null : (KineticStructureView) field(page, "previewView").get(page);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static void finish() {
        if (finished) return;
        watching = false;
        finished = true;
        LOG.info("REALM_DRAG_{} failures={}", failures == 0 ? "PASS" : "FAIL", failures);
        Minecraft.getInstance().setScreen(null);
        dev.xyat.kineticcore.api.runtime.KineticClientRuntime.stopClient();
    }

    private static float angle(float degrees) {
        float wrapped = degrees % 360F;
        if (wrapped > 180F) wrapped -= 360F;
        if (wrapped <= -180F) wrapped += 360F;
        return wrapped;
    }

    private static java.lang.reflect.Field field(Object target, String name) throws ReflectiveOperationException {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Object invoke(Class<?> type, Object target, String name, Object arg) throws Exception {
        for (var method : type.getDeclaredMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != 1) continue;
            method.setAccessible(true);
            return method.invoke(target, arg);
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
//?}
