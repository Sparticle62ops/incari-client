package incari.client.net;

import incari.client.platform.ClientPlatform;
import incari.client.ui.ControlList;
import incari.client.ui.EditedScreen;
import incari.client.ui.ListScreen;
import incari.client.ui.Theme;
import incari.client.ui.Ui;
import incari.net.ConnectionReport;
import incari.platform.api.Platform;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Connection Details, from the disconnection screen: how the last server connection went ({@link
 * ConnectionReport}), with Copy so a player can paste it into a report of a dropped connection.
 * Render thread.
 */
public final class ConnectionDetailsScreen extends ListScreen {

    private static final Component TITLE = Component.literal("Connection Details");
    private static final Component BUTTON = Component.literal("Connection Details");
    /** Room the label keeps beside its value. */
    private static final int LABEL_ROOM = 100;

    /** Taken once, when the screen opens: the connection is over, and Copy pastes what it shows. */
    private final List<ConnectionReport.Line> lines;

    private final String reason;
    private Component status = Component.empty();

    private ConnectionDetailsScreen(Screen parent, ConnectionReport report, String reason) {
        super(TITLE, parent);
        this.lines = report.lines(System.nanoTime());
        this.reason = reason;
    }

    /** {@code ScreenEdits}, after each layout and after {@link Reconnect}'s: the button under the others. */
    public static void edit(EditedScreen<DisconnectedScreen> edited) {
        List<AbstractWidget> widgets = edited.widgets();
        ConnectionReport report = ServerConnections.lastReport();
        if (widgets.isEmpty() || report == null) {
            return;
        }
        DisconnectedScreen screen = edited.screen();
        // One row more: the column moves up by half of it to stay centred.
        int row = Theme.CONTROL_HEIGHT + Theme.GAP;
        for (AbstractWidget widget : widgets) {
            widget.setY(widget.getY() - row / 2);
        }
        AbstractWidget last = widgets.getLast();
        String reason = "";
        for (AbstractWidget widget : widgets) {
            // The reason's widget: the screen keeps the reason itself private.
            if (widget instanceof MultiLineTextWidget text) {
                reason = text.getMessage().getString();
                break;
            }
        }
        String shownReason = reason;
        Button button = Ui.button(
                BUTTON,
                last.getWidth(),
                () -> Minecraft.getInstance().gui.setScreen(new ConnectionDetailsScreen(screen, report, shownReason)));
        button.setPosition(last.getX(), last.getY() + last.getHeight() + Theme.GAP);
        edited.add(button);
    }

    @Override
    protected Component caption() {
        return status.getString().isEmpty() ? Component.literal("Hover over a name to see what it means.") : status;
    }

    @Override
    protected List<ControlList.Row> rows(ControlList list) {
        int valueWidth = Math.max(60, list.getRowWidth() - LABEL_ROOM);
        List<ControlList.Row> rows = new ArrayList<>();
        for (ConnectionReport.Line line : lines) {
            String value = line.value();
            String shown = Ui.fit(this.font, value, valueWidth);
            StringWidget widget = new StringWidget(
                    this.font.width(shown),
                    Theme.CONTROL_HEIGHT,
                    Component.literal(shown).withColor(Theme.TEXT),
                    this.font);
            if (!shown.equals(value)) {
                widget.setTooltip(Ui.tooltip(value));
            }
            rows.add(list.row(Component.literal(line.label()), line.help(), List.of(widget), () -> {}));
        }
        return rows;
    }

    @Override
    protected void footer(LinearLayout footer) {
        footer.addChild(Ui.button(Component.literal("Copy"), Theme.SMALL_BUTTON_WIDTH * 2, this::copy));
        footer.addChild(Ui.button(CommonComponents.GUI_BACK, Theme.SMALL_BUTTON_WIDTH * 2, this::onClose));
    }

    /** Everything on the screen, and what a report needs besides: the game, the build, the message. */
    private String asText() {
        Platform platform = ClientPlatform.get();
        StringBuilder text = new StringBuilder("Incari connection details\n");
        text.append("Game: ").append(SharedConstants.getCurrentVersion().name()).append('\n');
        text.append("Build: ")
                .append(platform.buildKind())
                .append(", ")
                .append(platform.description())
                .append('\n');
        text.append("Message: ").append(reason).append('\n');
        for (ConnectionReport.Line line : lines) {
            text.append(line.label()).append(": ").append(line.value()).append('\n');
        }
        return text.toString();
    }

    private void copy() {
        this.minecraft.keyboardHandler.setClipboard(asText());
        status = Component.literal("Copied. Paste it into your report.").withColor(Theme.POSITIVE);
    }
}
