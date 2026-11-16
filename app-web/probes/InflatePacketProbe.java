import java.nio.ByteBuffer;
import java.util.Random;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Measures inflating packets the way the game's decoder does (one inflater, input and output as
 * buffers, a reset after each) in the browser build, against the route before web-classlib's
 * Inflater: a new zlib stream per packet (what TeaVM's reset did) and new input and output arrays
 * per call (InflaterAdditions). A server's mix of sizes over the compression threshold. Prints
 * microseconds per packet and "probe passed" when both routes read the packets back whole.
 *
 * <p>What it found (2026-11-15, headless Chrome, Compatible build, best of six alternated
 * rounds): inflating, 14.2 us a packet with a new stream and arrays each, 11.2 us reset in place;
 * deflating, 74.5 us and 60.4 us.
 */
public final class InflatePacketProbe {

    private InflatePacketProbe() {}

    public static void main(String[] args) throws DataFormatException {
        Random random = new Random(26);
        int packets = 2000;
        byte[][] plain = new byte[packets][];
        byte[][] compressed = new byte[packets][];
        Deflater deflater = new Deflater();
        byte[] buffer = new byte[8192];
        for (int i = 0; i < packets; i++) {
            // Mostly entity and block updates just over the threshold, some chunks.
            int size = i % 25 == 0 ? 20_000 + random.nextInt(60_000) : 256 + random.nextInt(1500);
            byte[] data = new byte[size];
            for (int j = 0; j < size; j++) {
                data[j] = (byte) ((j / 37) % 4 == 0 ? random.nextInt(256) : (j & 7));
            }
            plain[i] = data;
            deflater.setInput(data);
            deflater.finish();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer));
            }
            deflater.reset();
            compressed[i] = out.toByteArray();
        }
        boolean same = true;
        for (int round = 0; round < 2; round++) {
            same &= asBefore(plain, compressed) && asNow(plain, compressed);
        }
        // Alternated, best of each, so neither route gains from running second.
        double before = Double.MAX_VALUE;
        double after = Double.MAX_VALUE;
        double deflateNew = Double.MAX_VALUE;
        double deflateReset = Double.MAX_VALUE;
        for (int round = 0; round < 6; round++) {
            double start = now();
            same &= asBefore(plain, compressed);
            before = Math.min(before, (now() - start) * 1000 / packets);
            start = now();
            same &= asNow(plain, compressed);
            after = Math.min(after, (now() - start) * 1000 / packets);
            start = now();
            deflateEach(plain, true);
            deflateNew = Math.min(deflateNew, (now() - start) * 1000 / packets);
            start = now();
            deflateEach(plain, false);
            deflateReset = Math.min(deflateReset, (now() - start) * 1000 / packets);
        }
        System.out.println("InflatePacketProbe: inflate per packet: a new stream and arrays each " + before
                + " us, reset in place " + after + " us; deflate per packet: a new stream each " + deflateNew
                + " us, reset in place " + deflateReset + " us");
        System.out.println(same ? "probe passed" : "probe failed: a packet didn't read back");
    }

    /** As before: a new stream per packet, input and output copied through new arrays. */
    private static boolean asBefore(byte[][] plain, byte[][] compressed) throws DataFormatException {
        boolean same = true;
        for (int i = 0; i < plain.length; i++) {
            Inflater inflater = new Inflater();
            ByteBuffer input = ByteBuffer.wrap(compressed[i]);
            byte[] in = new byte[input.remaining()];
            input.get(in);
            inflater.setInput(in);
            ByteBuffer output = ByteBuffer.allocateDirect(plain[i].length);
            byte[] out = new byte[output.remaining()];
            int written = inflater.inflate(out);
            output.put(out, 0, written);
            inflater.end();
            same &= written == plain[i].length && check(output, plain[i]);
        }
        return same;
    }

    /** As the game's decoder now runs: one inflater, the buffer forms, a reset after each. */
    private static boolean asNow(byte[][] plain, byte[][] compressed) throws DataFormatException {
        boolean same = true;
        Inflater inflater = new Inflater();
        for (int i = 0; i < plain.length; i++) {
            inflater.setInput(ByteBuffer.wrap(compressed[i]));
            ByteBuffer output = ByteBuffer.allocateDirect(plain[i].length);
            int written = inflater.inflate(output);
            inflater.reset();
            same &= written == plain[i].length && check(output, plain[i]);
        }
        inflater.end();
        return same;
    }

    /** As the game's encoder: input array, a kept output array, then a reset (or a new stream, as before). */
    private static void deflateEach(byte[][] plain, boolean newEach) {
        byte[] buffer = new byte[8192];
        Deflater deflater = new Deflater();
        for (byte[] data : plain) {
            if (newEach) {
                deflater.end();
                deflater = new Deflater();
            }
            deflater.setInput(data, 0, data.length);
            deflater.finish();
            while (!deflater.finished()) {
                deflater.deflate(buffer);
            }
            deflater.reset();
        }
        deflater.end();
    }

    private static boolean check(ByteBuffer output, byte[] expected) {
        int n = expected.length;
        return output.get(0) == expected[0] && output.get(n / 2) == expected[n / 2] && output.get(n - 1) == expected[n - 1];
    }

    @org.teavm.jso.JSBody(script = "return performance.now();")
    private static native double now();
}
