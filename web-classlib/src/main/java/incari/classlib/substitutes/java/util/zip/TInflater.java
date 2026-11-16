package incari.classlib.substitutes.java.util.zip;

import com.jcraft.jzlib.JZlib;
import com.jcraft.jzlib.StreamResets;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.zip.DataFormatException;

/**
 * {@code java.util.zip.Inflater} over jzlib, replacing TeaVM's. The game inflates every compressed
 * packet with one inflater and resets it after each, and TeaVM's reset started a new jzlib stream:
 * a 32 KB window and Huffman tables allocated and cleared per packet. Here {@link #reset()} starts
 * over in place ({@link StreamResets}).
 *
 * <p>The buffer forms, which the game's packet decoder uses, read and write an array-backed buffer's
 * own array; a direct buffer goes through a scratch array this inflater keeps. As the JDK's, input
 * is held, not copied, until it's taken in.
 */
public class TInflater implements AutoCloseable {

    private static final int WINDOW_BITS = 15;
    private static final byte[] NO_INPUT = new byte[0];
    /** Largest scratch array kept between calls, for direct buffers; bigger copies go in parts. */
    private static final int SCRATCH_BYTES = 64 * 1024;

    private com.jcraft.jzlib.@org.jspecify.annotations.Nullable Inflater zlib;
    private byte @org.jspecify.annotations.Nullable [] inputScratch;
    private byte @org.jspecify.annotations.Nullable [] outputScratch;
    private boolean finished;
    private boolean needsDictionary;
    private boolean ended;

    public TInflater(boolean nowrap) {
        com.jcraft.jzlib.Inflater stream = new com.jcraft.jzlib.Inflater();
        int result = stream.init(WINDOW_BITS, nowrap);
        if (result != JZlib.Z_OK) {
            throw new InternalError("zlib couldn't start an inflate stream: " + result);
        }
        stream.setInput(NO_INPUT, 0, 0, false);
        this.zlib = stream;
    }

    public TInflater() {
        this(false);
    }

    private com.jcraft.jzlib.Inflater stream() {
        com.jcraft.jzlib.Inflater stream = zlib;
        if (stream == null) {
            throw new NullPointerException("Inflater has been closed");
        }
        return stream;
    }

    public void setInput(byte[] input, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, input.length);
        stream().setInput(input, offset, length, false);
    }

    public void setInput(byte[] input) {
        setInput(input, 0, input.length);
    }

    /**
     * Takes what remains of the buffer as input; the buffer is used up at once (the JDK moves its
     * position as input is taken in). An array-backed buffer's array is held, not copied.
     */
    public void setInput(ByteBuffer input) {
        int length = input.remaining();
        if (input.hasArray()) {
            int offset = input.arrayOffset() + input.position();
            input.position(input.limit());
            setInput(input.array(), offset, length);
            return;
        }
        // Held until taken in, so it isn't the output side's scratch.
        byte[] scratch = length > SCRATCH_BYTES ? new byte[length] : inputScratch;
        if (scratch == null) {
            scratch = new byte[SCRATCH_BYTES];
            inputScratch = scratch;
        }
        input.get(scratch, 0, length);
        setInput(scratch, 0, length);
    }

    public void setDictionary(byte[] dictionary, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, dictionary.length);
        byte[] bytes = offset == 0 ? dictionary : java.util.Arrays.copyOfRange(dictionary, offset, offset + length);
        int result = stream().setDictionary(bytes, length);
        if (result != JZlib.Z_OK) {
            throw new IllegalArgumentException("zlib refused the dictionary: " + result);
        }
        needsDictionary = false;
    }

    public void setDictionary(byte[] dictionary) {
        setDictionary(dictionary, 0, dictionary.length);
    }

    public void setDictionary(ByteBuffer dictionary) {
        byte[] bytes = new byte[dictionary.remaining()];
        dictionary.get(bytes);
        setDictionary(bytes, 0, bytes.length);
    }

    /** The input bytes not yet taken in. */
    public int getRemaining() {
        return stream().avail_in;
    }

    public boolean needsInput() {
        return stream().avail_in <= 0;
    }

    public boolean needsDictionary() {
        return needsDictionary;
    }

    public boolean finished() {
        return finished;
    }

    public int inflate(byte[] output) throws DataFormatException {
        return inflate(output, 0, output.length);
    }

    /**
     * Decompresses into the array as far as the input allows.
     *
     * @return the bytes written; 0 when more input or a dictionary is needed, or the stream ended
     */
    public int inflate(byte[] output, int offset, int length) throws DataFormatException {
        Objects.checkFromIndexSize(offset, length, output.length);
        com.jcraft.jzlib.Inflater stream = stream();
        if (finished || needsDictionary || length == 0) {
            return 0;
        }
        long before = stream.total_out;
        stream.setOutput(output, offset, length);
        int result = stream.inflate(JZlib.Z_NO_FLUSH);
        switch (result) {
            case JZlib.Z_STREAM_END -> finished = true;
            case JZlib.Z_NEED_DICT -> needsDictionary = true;
            case JZlib.Z_OK, JZlib.Z_BUF_ERROR -> {
                // Progress, or none possible (input or room used up): the caller's to go on from.
            }
            case JZlib.Z_DATA_ERROR ->
                throw new DataFormatException(stream.msg != null ? stream.msg : "invalid compressed data");
            default -> throw new InternalError("zlib failed to inflate: " + result);
        }
        return (int) (stream.total_out - before);
    }

    /**
     * Decompresses into the buffer's remaining space, advancing its position: straight into an
     * array-backed buffer's array, through a kept scratch array into a direct one.
     */
    public int inflate(ByteBuffer output) throws DataFormatException {
        if (output.isReadOnly()) {
            throw new java.nio.ReadOnlyBufferException();
        }
        if (output.hasArray()) {
            int written = inflate(output.array(), output.arrayOffset() + output.position(), output.remaining());
            output.position(output.position() + written);
            return written;
        }
        int total = 0;
        while (output.hasRemaining()) {
            byte[] scratch = outputScratch;
            if (scratch == null) {
                scratch = new byte[SCRATCH_BYTES];
                outputScratch = scratch;
            }
            int written = inflate(scratch, 0, Math.min(scratch.length, output.remaining()));
            output.put(scratch, 0, written);
            total += written;
            if (written == 0 || finished || needsDictionary) {
                break;
            }
        }
        return total;
    }

    public int getAdler() {
        return (int) stream().getAdler();
    }

    /** The input taken in so far, modulo 2^32: {@link #getBytesRead()} is the whole count. */
    public int getTotalIn() {
        return (int) getBytesRead();
    }

    public long getBytesRead() {
        return stream().total_in;
    }

    /** The output written so far, modulo 2^32: {@link #getBytesWritten()} is the whole count. */
    public int getTotalOut() {
        return (int) getBytesWritten();
    }

    public long getBytesWritten() {
        return stream().total_out;
    }

    /** Starts over for a new stream, keeping the window and tables (hot path: allocates nothing). */
    public void reset() {
        com.jcraft.jzlib.Inflater stream = stream();
        if (!StreamResets.inflate(stream)) {
            throw new InternalError("zlib couldn't reset the inflate stream");
        }
        stream.setInput(NO_INPUT, 0, 0, false);
        finished = false;
        needsDictionary = false;
    }

    public void end() {
        if (!ended) {
            ended = true;
            com.jcraft.jzlib.Inflater stream = zlib;
            zlib = null;
            inputScratch = null;
            outputScratch = null;
            if (stream != null) {
                stream.end();
            }
        }
    }

    @Override
    public void close() {
        end();
    }
}
