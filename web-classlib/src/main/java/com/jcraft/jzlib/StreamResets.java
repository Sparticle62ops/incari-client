package com.jcraft.jzlib;

/**
 * Starts a jzlib stream over in place, keeping the buffers it allocated. jzlib can do this (zlib's
 * {@code inflateReset} and {@code deflateReset}), but only from its own package: its public API
 * offers only {@code init}, which allocates a new state (an inflate's 32 KB window and Huffman
 * tables, a deflate's 64 KB window, hash chains and pending buffer). The game resets its inflater
 * and deflater after every compressed packet, so without this each packet allocated and cleared
 * that state anew.
 *
 * <p>An Incari class (web-classlib's {@code Inflater} and {@code Deflater} substitutes call it),
 * placed in jzlib's package only to reach those two methods. jzlib's jar isn't sealed.
 */
public final class StreamResets {

    private StreamResets() {}

    /**
     * Resets an inflate stream to read a new stream of the same kind (zlib-wrapped or raw).
     *
     * @return false if it has no state to reset (ended, or never started)
     */
    public static boolean inflate(Inflater stream) {
        Inflate state = stream.istate;
        return state != null && state.inflateReset() == JZlib.Z_OK;
    }

    /**
     * Resets a deflate stream to write a new stream with the level and strategy it has now.
     *
     * @return false if it has no state to reset (ended, or never started)
     */
    public static boolean deflate(Deflater stream) {
        Deflate state = stream.dstate;
        return state != null && state.deflateReset() == JZlib.Z_OK;
    }
}
