package incari.platform.web;

import incari.platform.api.net.NetConnection;
import incari.platform.api.net.PlatformNetwork;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.ArrayBuffer;
import org.teavm.jso.typedarrays.Int8Array;

/**
 * Server connections in the browser: WebSockets only, since a page can't open a TCP socket. HTTP
 * GETs go through {@code fetch} ({@link WebFetch}).
 *
 * <p>The browser's socket callbacks never call Java: they queue what happened in a JavaScript
 * array on the socket, and {@link #deliver} (called by the platform on the game's thread) hands it
 * to the listeners. TeaVM compiles most of this program as coroutines, and a coroutine can't start
 * from a JavaScript callback. The platform delivers as each frame starts and ends, and when a
 * message ends the render thread's wait between frames, so the network's thread decodes it before
 * the next frame's packet handling.
 */
final class WebSocketNetwork implements PlatformNetwork {

    /** Connections with events still to deliver; the game's thread only. */
    private final List<Socket> sockets = new ArrayList<>();

    @Override
    public boolean canAcceptConnections() {
        return false;
    }

    @Override
    public boolean canOpenSockets() {
        return false;
    }

    @Override
    public NetConnection connect(String host, int port, NetConnection.Listener listener) {
        Socket socket = new Socket(host + ":" + port, listener, null);
        socket.failLater("Browsers can only join WebSocket servers. Use an address starting with ws:// or wss://");
        sockets.add(socket);
        return socket;
    }

    @Override
    public NetConnection openWebSocket(String url, NetConnection.Listener listener) {
        JSObject handle;
        try {
            handle = open(url);
        } catch (RuntimeException e) {
            // The constructor throws on a URL it won't open (a bad port, ws:// from an https page).
            Socket socket = new Socket(url, listener, null);
            socket.failLater("The browser refused to open " + url + ": " + e.getMessage());
            sockets.add(socket);
            return socket;
        }
        Socket socket = new Socket(url, listener, handle);
        sockets.add(socket);
        return socket;
    }

    @Override
    public byte[] fetch(String url, int maxBytes) throws IOException {
        return WebFetch.get(url, maxBytes);
    }

    /** Delivers every queued socket event to its listener. The game's thread, once per frame. */
    void deliver() {
        if (sockets.isEmpty()) {
            return;
        }
        // A listener may open another socket while this runs; that one waits for the next frame.
        List<Socket> current = new ArrayList<>(sockets);
        for (Socket socket : current) {
            socket.deliver();
            if (socket.finished) {
                sockets.remove(socket);
            }
        }
    }

    /** One connection, and the events its browser socket queued since the last delivery. */
    private static final class Socket implements NetConnection {

        private final String description;
        private final NetConnection.Listener listener;
        private final @Nullable JSObject handle;
        private @Nullable String pendingFailure;
        private boolean open;
        private boolean closedByUs;
        private boolean finished;

        // For details(), the game's thread only.
        /** The longest a browser event waited in the queue before the game read it, in ms. */
        private double longestQueueWait;

        private int closeCode = -1;
        private String closeReason = "";
        /** Bytes the browser still held unsent when the socket closed; -1 if it didn't close itself. */
        private double unsentAtClose = -1;

        private boolean onlineAtClose = true;

        Socket(String description, NetConnection.Listener listener, @Nullable JSObject handle) {
            this.description = description;
            this.listener = listener;
            this.handle = handle;
        }

        void failLater(String reason) {
            pendingFailure = reason;
        }

        @Override
        public void send(ByteBuffer data) {
            JSObject socket = handle;
            if (socket == null || closedByUs || finished) {
                return;
            }
            // The channel hands over a buffer that is exactly its array: copied to the page once.
            byte[] bytes;
            if (data.hasArray()
                    && data.arrayOffset() + data.position() == 0
                    && data.remaining() == data.array().length) {
                bytes = data.array();
                data.position(data.limit());
            } else {
                bytes = new byte[data.remaining()];
                data.get(bytes);
            }
            // Sent at once if open; queued by the page until the socket opens otherwise.
            WebSocketNetwork.send(socket, Int8Array.copyFromJavaArray(bytes));
        }

        @Override
        public void sendText(String text) {
            JSObject socket = handle;
            if (socket == null || closedByUs || finished) {
                return;
            }
            WebSocketNetwork.sendText(socket, text);
        }

        @Override
        public void close() {
            if (closedByUs || finished) {
                return;
            }
            closedByUs = true;
            open = false;
            JSObject socket = handle;
            if (socket != null) {
                WebSocketNetwork.close(socket);
            }
            // Reported at the next delivery, in order after anything already queued.
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public String remoteDescription() {
            return description;
        }

        void deliver() {
            if (finished) {
                return;
            }
            String failure = pendingFailure;
            if (failure != null) {
                finish(new IOException(failure));
                return;
            }
            JSObject socket = handle;
            if (socket == null) {
                return;
            }
            int count = queued(socket);
            double now = now();
            for (int i = 0; i < count && !finished; i++) {
                longestQueueWait = Math.max(longestQueueWait, now - queuedTime(socket, i));
                switch (queuedType(socket, i)) {
                    case "open" -> {
                        if (!closedByUs) {
                            open = true;
                            listener.connected();
                        }
                    }
                    case "message" -> {
                        if (!closedByUs) {
                            byte[] message = new Int8Array(queuedData(socket, i)).copyToJavaArray();
                            listener.received(ByteBuffer.wrap(message));
                        }
                    }
                    case "text" -> {
                        if (!closedByUs) {
                            listener.receivedText(queuedText(socket, i));
                        }
                    }
                    case "close" -> {
                        int code = queuedCode(socket, i);
                        String reason = queuedReason(socket, i);
                        closeCode = code;
                        closeReason = reason;
                        unsentAtClose = queuedUnsent(socket, i);
                        onlineAtClose = queuedOnline(socket, i);
                        boolean clean = code == 1000 || code == 1001 || closedByUs;
                        finish(
                                clean
                                        ? null
                                        : new IOException("Connection to " + description + " closed (" + code
                                                + (reason.isEmpty() ? "" : ": " + reason) + ")"));
                    }
                    default -> {
                        // "error" always comes with a "close", which reports it.
                    }
                }
            }
            // Listeners may suspend (coroutines), letting the page queue more meanwhile: drop only
            // what was delivered.
            dropQueued(socket, count);
            if (closedByUs && !finished) {
                finish(null);
            }
        }

        @Override
        public Map<String, String> details() {
            Map<String, String> details = new LinkedHashMap<>();
            if (closeCode >= 0) {
                details.put(
                        "Close code",
                        closeCode(closeCode) + (closeReason.isEmpty() ? "" : ", \"" + closeReason + "\""));
                details.put("Unsent at close", Math.round(unsentAtClose) + " B");
                details.put("Browser online", onlineAtClose ? "Yes" : "No, it had lost the network");
            } else if (closedByUs) {
                details.put("Close code", "Closed by this game");
            }
            if (handle != null) {
                details.put("Longest game stall", Math.round(longestQueueWait) + " ms");
            }
            return details;
        }

        private void finish(@Nullable Throwable cause) {
            finished = true;
            open = false;
            listener.closed(cause);
        }
    }

    /** A WebSocket close code with what it means. */
    static String closeCode(int code) {
        String meaning =
                switch (code) {
                    case 1000 -> "normal close";
                    case 1001 -> "server going away or page leaving";
                    case 1002 -> "protocol error";
                    case 1003 -> "unsupported data";
                    case 1005 -> "no code given";
                    case 1006 -> "dropped with no close message";
                    case 1007 -> "invalid text";
                    case 1008 -> "policy violation";
                    case 1009 -> "message too big";
                    case 1011 -> "server error";
                    case 1012 -> "server restarting";
                    case 1013 -> "try again later";
                    case 1015 -> "TLS failed";
                    default -> "";
                };
        return code + (meaning.isEmpty() ? "" : " (" + meaning + ")");
    }

    // --- The page side ---------------------------------------------------------------------

    /*
     * Each socket keeps a queue of what happened, as arrays: ["open"], ["message", ArrayBuffer],
     * ["text", string] (Eagler's query answers), ["close", code, reason, unsent bytes, online],
     * ["error"]. __incariTimes holds when each entry was queued (performance.now()), for details().
     */
    @JSBody(
            params = "url",
            script = "var ws = new WebSocket(url); ws.binaryType = 'arraybuffer'; var q = []; ws.__incariQueue = q;"
                    + "var t = []; ws.__incariTimes = t;"
                    // Ends the render thread's wait between frames, so the message is delivered now.
                    + "function add(e) { q.push(e); t.push(performance.now());"
                    + "  var wake = window.__incariNetWake; if (wake) wake(); }"
                    + "ws.onopen = function() { add(['open']); };"
                    + "ws.onmessage = function(e) {"
                    + "  add(typeof e.data === 'string' ? ['text', e.data] : ['message', e.data]); };"
                    + "ws.onerror = function() { add(['error']); };"
                    + "ws.onclose = function(e) { add(['close', e.code, e.reason || '', ws.bufferedAmount,"
                    + "  typeof navigator === 'undefined' || navigator.onLine !== false]); };"
                    + "return ws;")
    private static native JSObject open(String url);

    @JSBody(
            params = {"ws", "data"},
            script = "if (ws.readyState === 1) ws.send(data);"
                    + " else if (ws.readyState === 0) { var p = ws.__incariPending;"
                    + "  if (!p) { p = ws.__incariPending = [];"
                    + "   ws.addEventListener('open', function() { for (var i = 0; i < p.length; i++) ws.send(p[i]);"
                    + "    p.length = 0; }); }"
                    + "  p.push(data); }")
    private static native void send(JSObject ws, Int8Array data);

    // The same queueing as send: a text message is one more entry in the pending list.
    @JSBody(
            params = {"ws", "text"},
            script = "if (ws.readyState === 1) ws.send(text);"
                    + " else if (ws.readyState === 0) { var p = ws.__incariPending;"
                    + "  if (!p) { p = ws.__incariPending = [];"
                    + "   ws.addEventListener('open', function() { for (var i = 0; i < p.length; i++) ws.send(p[i]);"
                    + "    p.length = 0; }); }"
                    + "  p.push(text); }")
    private static native void sendText(JSObject ws, String text);

    @JSBody(params = "ws", script = "try { ws.close(1000); } catch (e) {}")
    private static native void close(JSObject ws);

    @JSBody(params = "ws", script = "return ws.__incariQueue.length;")
    private static native int queued(JSObject ws);

    @JSBody(
            params = {"ws", "index"},
            script = "return ws.__incariQueue[index][0];")
    private static native String queuedType(JSObject ws, int index);

    @JSBody(
            params = {"ws", "index"},
            script = "return ws.__incariQueue[index][1];")
    private static native ArrayBuffer queuedData(JSObject ws, int index);

    @JSBody(
            params = {"ws", "index"},
            script = "return String(ws.__incariQueue[index][1]);")
    private static native String queuedText(JSObject ws, int index);

    @JSBody(
            params = {"ws", "index"},
            script = "return ws.__incariQueue[index][1] | 0;")
    private static native int queuedCode(JSObject ws, int index);

    @JSBody(
            params = {"ws", "index"},
            script = "return String(ws.__incariQueue[index][2]);")
    private static native String queuedReason(JSObject ws, int index);

    @JSBody(
            params = {"ws", "index"},
            script = "return ws.__incariTimes[index];")
    private static native double queuedTime(JSObject ws, int index);

    @JSBody(
            params = {"ws", "index"},
            script = "return +ws.__incariQueue[index][3] || 0;")
    private static native double queuedUnsent(JSObject ws, int index);

    @JSBody(
            params = {"ws", "index"},
            script = "return ws.__incariQueue[index][4] !== false;")
    private static native boolean queuedOnline(JSObject ws, int index);

    @JSBody(script = "return performance.now();")
    private static native double now();

    @JSBody(
            params = {"ws", "count"},
            script = "ws.__incariQueue.splice(0, count); ws.__incariTimes.splice(0, count);")
    private static native void dropQueued(JSObject ws, int count);
}
