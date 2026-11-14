package incari.net;

import static org.assertj.core.api.Assertions.assertThat;

import incari.platform.api.net.NetConnection;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ConnectionReportTest {

    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);

    private static String value(List<ConnectionReport.Line> lines, String label) {
        for (ConnectionReport.Line line : lines) {
            if (line.label().equals(label)) {
                return line.value();
            }
        }
        throw new AssertionError("No line " + label + " in " + lines);
    }

    private static ConnectionReport webSocket() {
        return new ConnectionReport(new WebSocketAddress(WebSocketUrl.parse("wss://mc.example.com/")));
    }

    @Test
    void aDropFromTheFarSideSaysSoWithItsTiming() {
        ConnectionReport report = webSocket();
        report.connected(0);
        report.received(2 * SECOND, 100);
        report.sent(3 * SECOND, 40);
        report.received(10 * SECOND, 924);
        report.closedRemotely(12 * SECOND, new IOException("Connection to wss://mc.example.com/ closed (1006)"));
        // The game closes the channel in answer: still the far side's close.
        report.closing(13 * SECOND);

        List<ConnectionReport.Line> lines = report.lines(99 * SECOND);

        assertThat(value(lines, "Server")).isEqualTo("wss://mc.example.com/");
        assertThat(value(lines, "Connection")).isEqualTo("WebSocket");
        assertThat(value(lines, "Connected for")).isEqualTo("12.0 s");
        assertThat(value(lines, "Closed by")).isEqualTo("The server or the network");
        assertThat(value(lines, "Error")).isEqualTo("IOException: Connection to wss://mc.example.com/ closed (1006)");
        assertThat(value(lines, "Last received")).isEqualTo("2.0 s before it closed");
        assertThat(value(lines, "Last sent")).isEqualTo("9.0 s before it closed");
        assertThat(value(lines, "Longest wait")).isEqualTo("8.0 s");
        assertThat(value(lines, "Received")).isEqualTo("1.0 KB in 2 messages");
        assertThat(value(lines, "Sent")).isEqualTo("40 B in 1 message");
        assertThat(value(lines, "Closed at")).endsWith("Z (UTC)");
    }

    @Test
    void aCloseByThisGameIsNotBlamedOnTheServer() {
        ConnectionReport report = new ConnectionReport(InetSocketAddress.createUnresolved("127.0.0.1", 25565));
        report.connected(0);
        report.closing(SECOND);
        report.closedRemotely(2 * SECOND, null);

        List<ConnectionReport.Line> lines = report.lines(5 * SECOND);

        assertThat(value(lines, "Closed by")).isEqualTo("This game");
        assertThat(value(lines, "Error")).isEqualTo("None");
        assertThat(value(lines, "Connection")).isEqualTo("TCP");
        assertThat(value(lines, "Received")).isEqualTo("0 B");
        assertThat(value(lines, "Last received")).isEqualTo("Never");
    }

    @Test
    void aConnectionStillOpenCountsToNow() {
        ConnectionReport report = webSocket();
        report.connected(0);
        report.received(SECOND, 1);

        List<ConnectionReport.Line> lines = report.lines(75 * SECOND);

        assertThat(value(lines, "Closed by")).isEqualTo("Still open");
        assertThat(value(lines, "Connected for")).isEqualTo("1 min 15 s");
        assertThat(value(lines, "Last received")).isEqualTo("1 min 14 s ago");
    }

    @Test
    void theConnectionsOwnDetailsComeLast() {
        ConnectionReport report = webSocket();
        Map<String, String> details = new LinkedHashMap<>();
        details.put("Close code", "1006 (dropped with no close message)");
        report.opening(new Detailed(details));

        List<ConnectionReport.Line> lines = report.lines(0);

        assertThat(lines.getLast().label()).isEqualTo("Close code");
        assertThat(report.asText(0)).contains("Close code: 1006 (dropped with no close message)\n");
        assertThat(value(lines, "Connected for")).isEqualTo("Never connected");
    }

    @Test
    void formatsSizesAndDurations() {
        assertThat(ConnectionReport.duration(TimeUnit.MILLISECONDS.toNanos(340)))
                .isEqualTo("340 ms");
        assertThat(ConnectionReport.duration(TimeUnit.MINUTES.toNanos(61))).isEqualTo("1 h 1 min");
        assertThat(ConnectionReport.bytes(1023)).isEqualTo("1023 B");
        assertThat(ConnectionReport.bytes(5 * 1024 * 1024 + 300 * 1024)).isEqualTo("5.3 MB");
    }

    private record Detailed(Map<String, String> details) implements NetConnection {

        @Override
        public void send(ByteBuffer data) {}

        @Override
        public void close() {}

        @Override
        public boolean isOpen() {
            return false;
        }

        @Override
        public String remoteDescription() {
            return "test";
        }
    }
}
