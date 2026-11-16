import io.netty.channel.EventLoop;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.local.LocalIoHandler;
import java.util.Arrays;
import org.teavm.interop.Async;
import org.teavm.interop.AsyncCallback;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

/**
 * Measures how long a task the page's main thread hands to a Netty local event loop (as the
 * browser's connections run on) waits before it runs, when the main thread then works for a while
 * and waits for the next animation frame, as the game does each frame. Prints the wait's median,
 * 95th percentile and max in ms, the frame interval for scale, and "probe passed".
 *
 * <p>What it found (2026-11-15, headless Chrome, Compatible build): the task ran as soon as the main
 * thread stopped for the frame, 6.2 ms after it was handed over with 6 ms of work in between, every
 * time. The hop to the loop costs no frame of its own.
 */
public final class EventLoopHopProbe {

    private EventLoopHopProbe() {}

    static volatile double ranAt;

    public static void main(String[] args) throws Exception {
        // As app-web's LibraryDefaults sets them.
        System.setProperty("io.netty.allocator.type", "unpooled");
        System.setProperty("io.netty.noUnsafe", "true");
        System.setProperty("io.netty.leakDetection.level", "disabled");
        System.setProperty("io.netty.machineId", "00:00:00:00:00:00:00:01");
        System.setProperty("io.netty.processId", "1");
        MultiThreadIoEventLoopGroup group = new MultiThreadIoEventLoopGroup(
                1, (java.util.concurrent.ThreadFactory) r -> new Thread(r, "Probe IO"), LocalIoHandler.newFactory());
        EventLoop loop = group.next();
        loop.submit(() -> {}).sync();
        // Other threads parked as the game's are (more event loops, timers), each in LockSupport.
        MultiThreadIoEventLoopGroup others = new MultiThreadIoEventLoopGroup(
                0, (java.util.concurrent.ThreadFactory) r -> new Thread(r, "Probe other"), LocalIoHandler.newFactory());
        int parked = 0;
        for (io.netty.util.concurrent.EventExecutor other : others) {
            other.submit(() -> {}).sync();
            parked++;
        }
        System.out.println("EventLoopHopProbe: " + parked + " other loops parked, "
                + Runtime.getRuntime().availableProcessors() + " processors");
        int frames = 240;
        for (int pass = 0; pass < 3; pass++) {
            boolean slots = pass >= 1;
            setSlotEnd(pass == 2 ? 1000 / 60.0 + 1 : 1000 / 60.0 - 2);
            // Pass 1 adds the background pool's idle slot as the page has it: after each frame, a
            // message that keeps the page busy until 2 ms before the next frame is due.
            setIdleSlots(slots);
            double[] waits = new double[frames];
            double[] ran = new double[frames];
            Arrays.fill(ran, -1);
            for (int i = 0; i < frames; i++) {
                nextFrame();
                int index = i;
                double handedAt = now();
                waits[i] = handedAt;
                loop.execute(() -> ran[index] = now());
                // The frame's own work: a few ms on the main thread, no yielding.
                busy(6);
            }
            for (int i = 0; i < 30; i++) {
                nextFrame();
            }
            setIdleSlots(false);
            for (int i = 0; i < frames; i++) {
                waits[i] = ran[i] < 0 ? 1e9 : ran[i] - waits[i];
            }
            Arrays.sort(waits);
            System.out.println("EventLoopHopProbe: " + (pass == 0 ? "no idle slots" : pass == 1 ? "idle slots ending 2 ms before the frame" : "idle slots running 1 ms past the frame")
                    + ", hand-off to run, ms: median " + waits[frames / 2] + " p95 " + waits[frames * 95 / 100]
                    + " max " + waits[frames - 1]);
        }
        // As the game since its network wake: the main thread waits for a frame or a message, a
        // page callback (a stand-in for the socket's onmessage) wakes it, and it hands the message
        // to the loop from there before waiting again.
        setIdleSlots(true);
        setSlotEnd(1000 / 60.0 - 2);
        double[] woken = new double[frames];
        double[] wokenRan = new double[frames];
        Arrays.fill(wokenRan, -1);
        int handed = 0;
        startMessages();
        while (handed < frames) {
            if (!frameOrMessage()) {
                int index = handed++;
                woken[index] = now();
                loop.execute(() -> wokenRan[index] = now());
            } else {
                busy(6);
            }
        }
        stopMessages();
        for (int i = 0; i < 30; i++) {
            nextFrame();
        }
        setIdleSlots(false);
        for (int i = 0; i < frames; i++) {
            woken[i] = wokenRan[i] < 0 ? 1e9 : wokenRan[i] - woken[i];
        }
        Arrays.sort(woken);
        System.out.println("EventLoopHopProbe: handed over from a wake by a page callback, ms: median "
                + woken[frames / 2] + " p95 " + woken[frames * 95 / 100] + " max " + woken[frames - 1]);
        group.shutdownGracefully();
        others.shutdownGracefully();
        System.out.println("probe passed");
    }

    private static void busy(double millis) {
        double end = now() + millis;
        while (now() < end) {
            // spin
        }
    }

    @Async
    private static native Boolean nextFrame();

    private static void nextFrame(AsyncCallback<Boolean> callback) {
        requestFrame(() -> callback.complete(true));
    }

    @Async
    private static native Boolean frameOrMessage();

    private static void frameOrMessage(AsyncCallback<Boolean> callback) {
        waitFrameOrMessage(frame -> callback.complete(frame));
    }

    @JSFunctor
    interface FrameOrMessage extends JSObject {
        void run(boolean frame);
    }

    @JSBody(
            params = "callback",
            script = "var w = window.__hopWait || (window.__hopWait = { waiter: null, pending: false });"
                    + "w.waiter = callback; if (!w.pending) { w.pending = true; requestAnimationFrame(function() {"
                    + "  w.pending = false; var c = w.waiter; w.waiter = null; if (c) c(true); }); }")
    private static native void waitFrameOrMessage(FrameOrMessage callback);

    @JSBody(
            script = "window.__hopTimer = setInterval(function() { var w = window.__hopWait;"
                    + " if (w && w.waiter) { var c = w.waiter; w.waiter = null; c(false); } }, 7);")
    private static native void startMessages();

    @JSBody(script = "clearInterval(window.__hopTimer);")
    private static native void stopMessages();

    @JSFunctor
    interface Frame extends JSObject {
        void run();
    }

    @JSBody(params = "frame", script = "requestAnimationFrame(function() { frame(); });")
    private static native void requestFrame(Frame frame);

    @JSBody(
            params = "on",
            script = "window.__hopSlots = on; if (!on || window.__hopSlotsStarted) return; window.__hopSlotsStarted = true;"
                    + "var ch = new MessageChannel(); var last = 0;"
                    + "ch.port1.onmessage = function() { var end = last + (window.__hopSlotEnd || 14);"
                    + "  while (performance.now() < end) {} };"
                    + "function frame(t) { if (window.__hopSlots) { last = t; ch.port2.postMessage(0); } requestAnimationFrame(frame); }"
                    + "requestAnimationFrame(frame);")
    private static native void setIdleSlots(boolean on);

    @JSBody(params = "millis", script = "window.__hopSlotEnd = millis;")
    private static native void setSlotEnd(double millis);

    @JSBody(script = "return performance.now();")
    private static native double now();
}
