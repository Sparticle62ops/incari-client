import java.nio.ByteBuffer;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.ArrayBuffer;
import org.teavm.jso.typedarrays.Int8Array;

/**
 * Measures what delivering one WebSocket message from the page's queue to Java costs in the
 * WebAssembly GC build (platform-web's {@code WebSocketNetwork}), for a server's mix of packet
 * sizes: the route as it was (each entry's type read as a string, its time, its buffer wrapped in a
 * new Int8Array, then copied) against an entry's type as a number and the copy made from a view the
 * page built when the message came. Prints microseconds per message and "probe passed" when all
 * routes read the same bytes.
 */
public final class SocketDeliveryProbe {

    private SocketDeliveryProbe() {}

    public static void main(String[] args) {
        int messages = 20_000;
        JSObject queue = fill(messages);
        long expected = 0;
        for (int round = 0; round < 3; round++) {
            expected = asBefore(queue, messages);
            numbered(queue, messages);
        }
        int rounds = 10;
        double start = now();
        long before = 0;
        for (int i = 0; i < rounds; i++) {
            before = asBefore(queue, messages);
        }
        double beforeMicros = (now() - start) * 1000 / rounds / messages;
        start = now();
        long after = 0;
        for (int i = 0; i < rounds; i++) {
            after = numbered(queue, messages);
        }
        double afterMicros = (now() - start) * 1000 / rounds / messages;
        start = now();
        long copyOnly = 0;
        for (int i = 0; i < rounds; i++) {
            copyOnly = copyOnly(queue, messages);
        }
        double copyMicros = (now() - start) * 1000 / rounds / messages;
        System.out.println("SocketDeliveryProbe: per message (mean " + meanSize(queue, messages) + " B): as before "
                + beforeMicros + " us, numbered types and kept views " + afterMicros + " us, the copy alone "
                + copyMicros + " us");
        boolean same = expected == before && before == after && after == copyOnly;
        System.out.println(same ? "probe passed" : "probe failed: the routes read different bytes");
    }

    private static long asBefore(JSObject queue, int count) {
        long sum = 0;
        double now = now();
        double longest = 0;
        for (int i = 0; i < count; i++) {
            longest = Math.max(longest, now - queuedTime(queue, i));
            switch (queuedType(queue, i)) {
                case "message" -> {
                    byte[] message = new Int8Array(queuedData(queue, i)).copyToJavaArray();
                    sum += checksum(ByteBuffer.wrap(message));
                }
                default -> {}
            }
        }
        return sum + (longest < 0 ? 1 : 0);
    }

    private static long numbered(JSObject queue, int count) {
        long sum = 0;
        double now = now();
        double longest = 0;
        for (int i = 0; i < count; i++) {
            longest = Math.max(longest, now - queuedTime(queue, i));
            if (kind(queue, i) == 1) {
                byte[] message = view(queue, i).copyToJavaArray();
                sum += checksum(ByteBuffer.wrap(message));
            }
        }
        return sum + (longest < 0 ? 1 : 0);
    }

    private static long copyOnly(JSObject queue, int count) {
        long sum = 0;
        for (int i = 0; i < count; i++) {
            byte[] message = view(queue, i).copyToJavaArray();
            sum += checksum(ByteBuffer.wrap(message));
        }
        return sum;
    }

    private static long checksum(ByteBuffer buffer) {
        int n = buffer.remaining();
        return n == 0 ? 0 : n * 31L + buffer.get(0) + buffer.get(n - 1);
    }

    private static int meanSize(JSObject queue, int count) {
        long total = 0;
        for (int i = 0; i < count; i++) {
            total += view(queue, i).getLength();
        }
        return (int) (total / count);
    }

    // A server's mix: mostly small (movement, entity motion), some medium, a few chunks.
    @JSBody(
            params = "count",
            script = "var q = [], t = [], n = [], v = []; var seed = 26;"
                    + "function rnd() { seed = (seed * 1103515245 + 12345) & 0x7fffffff; return seed / 0x7fffffff; }"
                    + "for (var i = 0; i < count; i++) { var r = rnd();"
                    + "  var size = r < 0.8 ? 8 + (rnd() * 40 | 0) : r < 0.97 ? 100 + (rnd() * 900 | 0) : 4000 + (rnd() * 20000 | 0);"
                    + "  var b = new ArrayBuffer(size); var a = new Int8Array(b);"
                    + "  for (var j = 0; j < size; j += 7) a[j] = (i + j) & 127;"
                    + "  q.push(['message', b]); t.push(performance.now()); n.push(1); v.push(a); }"
                    + "return {q: q, t: t, n: n, v: v};")
    private static native JSObject fill(int count);

    @JSBody(params = {"s", "i"}, script = "return s.q[i][0];")
    private static native String queuedType(JSObject s, int i);

    @JSBody(params = {"s", "i"}, script = "return s.q[i][1];")
    private static native ArrayBuffer queuedData(JSObject s, int i);

    @JSBody(params = {"s", "i"}, script = "return s.t[i];")
    private static native double queuedTime(JSObject s, int i);

    @JSBody(params = {"s", "i"}, script = "return s.n[i];")
    private static native int kind(JSObject s, int i);

    @JSBody(params = {"s", "i"}, script = "return s.v[i];")
    private static native Int8Array view(JSObject s, int i);

    @JSBody(script = "return performance.now();")
    private static native double now();
}
