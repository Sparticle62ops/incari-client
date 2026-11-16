package incari.classlib.substitutes.java.util.zip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Test;

class TInflaterTest {

    @Test
    void streamsOfAnyShapeComeBackWholeFedInRuns() throws DataFormatException {
        Random random = new Random(26);
        for (int trial = 0; trial < 40; trial++) {
            boolean raw = trial % 2 == 1;
            byte[] data = sample(random, 1 + random.nextInt(200_000));
            byte[] compressed = deflate(data, raw, null);
            TInflater inflater = new TInflater(raw);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[1 + random.nextInt(5000)];
            int position = 0;
            while (!inflater.finished()) {
                if (inflater.needsInput()) {
                    int length = Math.min(compressed.length - position, 1 + random.nextInt(4000));
                    inflater.setInput(compressed, position, length);
                    position += length;
                }
                out.write(buffer, 0, inflater.inflate(buffer));
            }

            assertThat(out.toByteArray()).isEqualTo(data);
            assertThat(inflater.getBytesRead()).isEqualTo(compressed.length);
            assertThat(inflater.getBytesWritten()).isEqualTo(data.length);
            assertThat(inflater.getRemaining()).isZero();
        }
    }

    @Test
    void resetReadsPacketAfterPacketAsTheGameDoes() throws DataFormatException {
        // The game's decoder: one inflater, input as a buffer, output into a sized buffer, reset.
        Random random = new Random(4);
        for (boolean direct : new boolean[] {false, true}) {
            TInflater inflater = new TInflater();
            for (int packet = 0; packet < 200; packet++) {
                byte[] data = sample(random, 256 + random.nextInt(packet % 20 == 0 ? 300_000 : 3000));
                byte[] compressed = deflate(data, false, null);
                inflater.setInput(direct ? directCopy(compressed) : ByteBuffer.wrap(compressed));
                ByteBuffer output = direct ? ByteBuffer.allocateDirect(data.length) : ByteBuffer.allocate(data.length);

                assertThat(inflater.inflate(output)).isEqualTo(data.length);
                assertThat(output.position()).isEqualTo(data.length);
                assertThat(inflater.finished()).isTrue();
                assertThat(bytes(output.flip())).isEqualTo(data);
                inflater.reset();
                assertThat(inflater.finished()).isFalse();
                assertThat(inflater.getBytesRead()).isZero();
            }
        }
    }

    @Test
    void aBrokenChecksumAfterAResetStillFails() {
        // The reset must start the checksum over too, or a good stream after it would fail.
        TInflater inflater = new TInflater();
        byte[] good = deflate(sample(new Random(1), 4000), false, null);
        byte[] broken = good.clone();
        broken[broken.length - 1] ^= 1;
        assertThatThrownBy(() -> {
                    inflater.setInput(broken);
                    inflater.inflate(new byte[8000]);
                })
                .isInstanceOf(DataFormatException.class);
        inflater.reset();
        inflater.setInput(good);
        assertThat(inflateFully(inflater, 4000)).hasSize(4000);
    }

    @Test
    void arrayBackedBuffersAreUsedInPlaceAtTheirOffsets() throws DataFormatException {
        byte[] data = sample(new Random(7), 10_000);
        byte[] compressed = deflate(data, false, null);
        byte[] padded = new byte[compressed.length + 20];
        System.arraycopy(compressed, 0, padded, 13, compressed.length);
        ByteBuffer input =
                ByteBuffer.wrap(padded, 10, compressed.length + 3).slice().position(3);
        TInflater inflater = new TInflater();
        inflater.setInput(input);

        assertThat(input.hasRemaining()).isFalse();
        byte[] backing = new byte[data.length + 9];
        ByteBuffer output = ByteBuffer.wrap(backing, 4, data.length + 5).slice().position(5);
        assertThat(inflater.inflate(output)).isEqualTo(data.length);
        assertThat(Arrays.copyOfRange(backing, 9, backing.length)).isEqualTo(data);
        assertThat(backing[8]).isZero();
    }

    @Test
    void aDirectOutputBiggerThanTheScratchFillsInParts() throws DataFormatException {
        byte[] data = sample(new Random(8), 500_000);
        TInflater inflater = new TInflater();
        inflater.setInput(deflate(data, false, null));
        ByteBuffer output = ByteBuffer.allocateDirect(data.length + 100);

        assertThat(inflater.inflate(output)).isEqualTo(data.length);
        assertThat(inflater.finished()).isTrue();
        assertThat(bytes(output.flip())).isEqualTo(data);
    }

    @Test
    void aDictionaryIsAskedForThenUsed() throws DataFormatException {
        byte[] dictionary =
                "the quick brown fox jumps over the lazy dog".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] data = sample(new Random(9), 3000);
        byte[] compressed = deflate(data, false, dictionary);
        TInflater inflater = new TInflater();
        inflater.setInput(compressed);

        assertThat(inflater.inflate(new byte[4000])).isZero();
        assertThat(inflater.needsDictionary()).isTrue();
        java.util.zip.Adler32 adler = new java.util.zip.Adler32();
        adler.update(dictionary);
        assertThat(inflater.getAdler()).isEqualTo((int) adler.getValue());
        inflater.setDictionary(dictionary);
        assertThat(inflateFully(inflater, data.length)).isEqualTo(data);
    }

    @Test
    void garbageIsADataFormatException() {
        TInflater inflater = new TInflater();
        inflater.setInput(new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
        assertThatThrownBy(() -> inflater.inflate(new byte[100])).isInstanceOf(DataFormatException.class);
    }

    @Test
    void anEndedInflaterRefusesWork() {
        TInflater inflater = new TInflater();
        inflater.close();
        inflater.end();
        assertThatThrownBy(() -> inflater.setInput(new byte[1])).isInstanceOf(NullPointerException.class);
    }

    @Test
    void deflaterResetKeepsTheLevelAndStartsANewStream() throws DataFormatException {
        TDeflater deflater = new TDeflater(TDeflater.BEST_SPEED);
        Random random = new Random(10);
        byte[] buffer = new byte[8192];
        for (int packet = 0; packet < 100; packet++) {
            if (packet == 50) {
                deflater.setLevel(TDeflater.BEST_COMPRESSION);
            }
            byte[] data = sample(random, 256 + random.nextInt(20_000));
            deflater.setInput(data);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer));
            }
            deflater.reset();
            assertThat(deflater.getBytesRead()).isZero();

            TInflater inflater = new TInflater();
            inflater.setInput(out.toByteArray());
            assertThat(inflateFully(inflater, data.length)).isEqualTo(data);
        }
    }

    private static byte[] inflateFully(TInflater inflater, int length) {
        try {
            byte[] out = new byte[length];
            int written = 0;
            while (!inflater.finished() && written < length) {
                int n = inflater.inflate(out, written, length - written);
                if (n == 0 && inflater.needsInput()) {
                    break;
                }
                written += n;
            }
            return Arrays.copyOf(out, written);
        } catch (DataFormatException e) {
            throw new AssertionError(e);
        }
    }

    /** The JDK's own deflater writes the reference streams. */
    private static byte[] deflate(byte[] data, boolean raw, byte @org.jspecify.annotations.Nullable [] dictionary) {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, raw);
        if (dictionary != null) {
            deflater.setDictionary(dictionary);
        }
        deflater.setInput(data);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        return out.toByteArray();
    }

    private static ByteBuffer directCopy(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length);
        buffer.put(bytes).flip();
        return buffer;
    }

    private static byte[] bytes(ByteBuffer buffer) {
        byte[] out = new byte[buffer.remaining()];
        buffer.get(out);
        return out;
    }

    /** Compressible but not trivial, like packets: runs, small values and some noise. */
    private static byte[] sample(Random random, int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            int kind = (i / 97) % 3;
            data[i] = (byte) (kind == 0 ? 0 : kind == 1 ? (i & 15) : random.nextInt(256));
        }
        return data;
    }
}
