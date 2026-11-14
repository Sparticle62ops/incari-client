package incari.net;

import incari.net.wisp.WispAddress;
import incari.platform.api.net.NetConnection;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * How one {@link NetConnectionChannel} went: when it opened and closed, which side closed it, the
 * traffic each way and the longest wait for the server, plus what the platform knows ({@link
 * NetConnection#details}). The disconnect screen shows it, so a player can report a dropped
 * connection with more than its last error.
 *
 * <p>Written on the channel's event loop only (one writer); read from any thread. Times are {@link
 * System#nanoTime} values, passed in so tests choose them.
 */
public final class ConnectionReport {

    /** One row: a label, its value, and what it means for a player. */
    public record Line(String label, String value, String help) {}

    private enum ClosedBy {
        CLIENT,
        REMOTE
    }

    private final String server;
    private final String transport;
    private final boolean messages;

    private volatile @Nullable NetConnection connection;
    private volatile long connectedAt = -1;
    private volatile long closedAt = -1;
    private volatile long closedAtMillis;
    private volatile @Nullable ClosedBy closedBy;
    private volatile @Nullable String error;
    private volatile long lastIn = -1;
    private volatile long lastOut = -1;
    private volatile long longestWaitIn;
    private volatile long countIn;
    private volatile long bytesIn;
    private volatile long countOut;
    private volatile long bytesOut;

    ConnectionReport(InetSocketAddress address) {
        if (address instanceof WebSocketAddress webSocket) {
            this.server = webSocket.url().toString();
            this.transport = "WebSocket";
            this.messages = true;
        } else if (address instanceof WispAddress wisp) {
            this.server = wisp.getHostString() + ":" + wisp.getPort();
            this.transport = "Wisp proxy " + wisp.proxy();
            this.messages = true;
        } else {
            this.server = address.getHostString() + ":" + address.getPort();
            this.transport = address.getClass() == InetSocketAddress.class ? "TCP" : "Other";
            this.messages = false;
        }
    }

    void opening(NetConnection connection) {
        this.connection = connection;
    }

    void connected(long now) {
        connectedAt = now;
    }

    @SuppressWarnings("NonAtomicVolatileUpdate") // one writer: the channel's event loop
    void received(long now, int bytes) {
        long previous = lastIn >= 0 ? lastIn : connectedAt;
        if (previous >= 0) {
            longestWaitIn = Math.max(longestWaitIn, now - previous);
        }
        lastIn = now;
        countIn++;
        bytesIn += bytes;
    }

    @SuppressWarnings("NonAtomicVolatileUpdate") // one writer: the channel's event loop
    void sent(long now, int bytes) {
        lastOut = now;
        countOut++;
        bytesOut += bytes;
    }

    /** This client closed the channel; nothing if the other side already had. */
    void closing(long now) {
        end(now, ClosedBy.CLIENT, null);
    }

    /** The platform says the connection closed, from the far side or the network. */
    void closedRemotely(long now, @Nullable Throwable cause) {
        end(now, ClosedBy.REMOTE, cause);
    }

    private void end(long now, ClosedBy by, @Nullable Throwable cause) {
        if (closedBy != null) {
            return;
        }
        closedAt = now;
        closedAtMillis = System.currentTimeMillis();
        if (cause != null) {
            String message = cause.getMessage();
            error = cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
        }
        closedBy = by;
    }

    /** The rows, as of {@code now} if the connection is still open. */
    public List<Line> lines(long now) {
        List<Line> lines = new ArrayList<>();
        long end = closedAt >= 0 ? closedAt : now;
        boolean closed = closedBy != null;
        lines.add(new Line("Server", server, "The address this connection went to."));
        lines.add(new Line("Connection", transport, "How the game reached the server."));
        lines.add(new Line(
                "Closed at",
                closed ? Instant.ofEpochMilli(closedAtMillis).truncatedTo(ChronoUnit.SECONDS) + " (UTC)" : "Still open",
                "Match this against the server's logs."));
        lines.add(new Line(
                "Connected for",
                connectedAt >= 0 ? duration(end - connectedAt) : "Never connected",
                "From the connection opening to it closing."));
        ClosedBy by = closedBy;
        lines.add(new Line(
                "Closed by",
                by == null ? "Still open" : by == ClosedBy.CLIENT ? "This game" : "The server or the network",
                "Which side ended the connection. \"The server or the network\" covers anything between you"
                        + " and the server: a proxy, a filter, Wi-Fi."));
        String failure = error;
        lines.add(new Line(
                "Error", failure != null ? failure : "None", "What the browser or system said when it closed."));
        lines.add(new Line(
                "Last received",
                ago(lastIn, end, closed),
                "How long before the close the server last sent something. Long means it went quiet first."));
        lines.add(new Line(
                "Last sent", ago(lastOut, end, closed), "How long before the close this game last sent something."));
        lines.add(new Line(
                "Longest wait",
                countIn > 0 ? duration(longestWaitIn) : "None",
                "The longest gap between two things from the server. Several seconds means lag spikes or"
                        + " the game freezing."));
        lines.add(new Line("Received", traffic(bytesIn, countIn), "Everything the server sent."));
        lines.add(new Line("Sent", traffic(bytesOut, countOut), "Everything this game sent."));
        NetConnection current = connection;
        if (current != null) {
            for (Map.Entry<String, String> detail : current.details().entrySet()) {
                lines.add(new Line(detail.getKey(), detail.getValue(), ""));
            }
        }
        return lines;
    }

    /** The rows as text to paste into a report. */
    public String asText(long now) {
        StringBuilder text = new StringBuilder();
        for (Line line : lines(now)) {
            text.append(line.label()).append(": ").append(line.value()).append('\n');
        }
        return text.toString();
    }

    private static String ago(long at, long end, boolean closed) {
        if (at < 0) {
            return "Never";
        }
        return duration(end - at) + (closed ? " before it closed" : " ago");
    }

    private String traffic(long bytes, long count) {
        return messages ? bytes(bytes) + " in " + count + (count == 1 ? " message" : " messages") : bytes(bytes);
    }

    static String duration(long nanos) {
        long millis = TimeUnit.NANOSECONDS.toMillis(Math.max(0, nanos));
        if (millis < 1000) {
            return millis + " ms";
        }
        if (millis < 60_000) {
            return (millis / 1000) + "." + (millis % 1000 / 100) + " s";
        }
        long seconds = millis / 1000;
        if (seconds < 3600) {
            return (seconds / 60) + " min " + (seconds % 60) + " s";
        }
        return (seconds / 3600) + " h " + (seconds % 3600 / 60) + " min";
    }

    static String bytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kib = bytes / 1024.0;
        if (kib < 1024) {
            return oneDecimal(kib) + " KB";
        }
        return oneDecimal(kib / 1024) + " MB";
    }

    private static String oneDecimal(double value) {
        long tenths = Math.round(value * 10);
        return (tenths / 10) + "." + (tenths % 10);
    }
}
