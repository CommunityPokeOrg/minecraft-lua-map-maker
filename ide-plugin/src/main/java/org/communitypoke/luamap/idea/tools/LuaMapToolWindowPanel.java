package org.communitypoke.luamap.idea.tools;

import com.intellij.openapi.Disposable;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import org.communitypoke.luamap.idea.bridge.LuaBridgeClient;
import org.communitypoke.luamap.idea.bridge.LuaBridgeSession;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.List;

/**
 * Live LuaMap panel: connection bar (host/port/connect/state badge),
 * auto-refreshing NPC inspector, world/block query, and a console for
 * eval/run results. All socket work happens inside
 * {@link LuaBridgeSession}'s background thread; this class only ever runs
 * on the EDT (every callback arrives via {@code invokeLater}).
 */
final class LuaMapToolWindowPanel extends JPanel
        implements Disposable, LuaBridgeSession.Listener {

    private final LuaBridgeSession session;

    private final JTextField hostField = new JBTextField("127.0.0.1", 9);
    private final JTextField portField =
            new JBTextField(String.valueOf(LuaBridgeClient.DEFAULT_PORT), 5);
    private final JButton connectButton = new JButton("Connect");
    private final JButton disconnectButton = new JButton("Disconnect");
    private final JButton reconnectButton = new JButton("Reconnect");
    private final JCheckBox autoReconnectBox = new JCheckBox("Auto-reconnect", true);
    private final JCheckBox autoRefreshBox = new JCheckBox("Auto-refresh", true);
    private final JButton refreshButton = new JButton("Refresh");
    private final JBLabel statusBadge = new JBLabel("Disconnected");
    private final JBLabel statusDetail = new JBLabel("");

    private final DefaultTableModel npcModel =
            new DefaultTableModel(new String[]{"Name", "X", "Y", "Z"}, 0) {
                @Override
                public boolean isCellEditable(int row, int column) {
                    return false;
                }
            };
    private final JBTable npcTable = new JBTable(npcModel);

    private final JTextField blockX = new JBTextField("0", 4);
    private final JTextField blockY = new JBTextField("64", 4);
    private final JTextField blockZ = new JBTextField("0", 4);
    private final JButton blockQueryButton = new JButton("Query block");
    private final JBLabel blockResult = new JBLabel("");

    private final DefaultTableModel scriptModel =
            new DefaultTableModel(new String[]{"Script"}, 0) {
                @Override
                public boolean isCellEditable(int row, int column) {
                    return false;
                }
            };
    private final JBTable scriptTable = new JBTable(scriptModel);
    private final JButton runScriptButton = new JButton("Run");
    private final JButton reloadScriptsButton = new JButton("Reload list");

    private final JTextField evalField = new JBTextField(20);
    private final JButton evalButton = new JButton("Eval");
    private final JBTextArea console = new JBTextArea(8, 40);

    LuaMapToolWindowPanel() {
        super(new BorderLayout());
        session = new LuaBridgeSession(this, SwingUtilities::invokeLater);
        session.setAutoReconnect(true);
        // Each background poll refreshes the NPC table when auto-refresh is on.
        session.setPollListener(st -> {
            if (autoRefreshBox.isSelected() && session.isConnected()) {
                session.refresh(s -> statusDetail.setText(s.raw()), this::loadNpcs);
            }
        });

        statusBadge.setOpaque(true);
        statusBadge.setBorder(JBUI.Borders.empty(2, 8));
        console.setEditable(false);

        add(buildTopBar(), BorderLayout.NORTH);

        JBTabbedPane tabs = new JBTabbedPane();
        tabs.addTab("NPCs", buildNpcTab());
        tabs.addTab("World", buildWorldTab());
        tabs.addTab("Console", buildConsoleTab());
        add(tabs, BorderLayout.CENTER);

        wire();
        updateForState(LuaBridgeSession.State.DISCONNECTED, "not connected");
    }

    private JPanel buildTopBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        bar.add(new JBLabel("Host:"));
        bar.add(hostField);
        bar.add(new JBLabel("Port:"));
        bar.add(portField);
        bar.add(connectButton);
        bar.add(disconnectButton);
        bar.add(reconnectButton);
        bar.add(autoReconnectBox);
        bar.add(autoRefreshBox);
        bar.add(refreshButton);
        bar.add(statusBadge);
        bar.add(statusDetail);
        return bar;
    }

    private JPanel buildNpcTab() {
        JPanel p = new JPanel(new BorderLayout());
        npcTable.setAutoCreateRowSorter(true);
        p.add(new JBScrollPane(npcTable), BorderLayout.CENTER);
        return p;
    }

    private JPanel buildWorldTab() {
        JPanel p = new JPanel(new BorderLayout());
        JPanel query = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        query.add(new JBLabel("getblock("));
        query.add(blockX);
        query.add(new JBLabel(","));
        query.add(blockY);
        query.add(new JBLabel(","));
        query.add(blockZ);
        query.add(new JBLabel(")"));
        query.add(blockQueryButton);
        query.add(blockResult);
        p.add(query, BorderLayout.NORTH);

        JPanel scripts = new JPanel(new BorderLayout());
        scriptTable.setAutoCreateRowSorter(true);
        scripts.add(new JBLabel("Scripts (luamaps/)"), BorderLayout.NORTH);
        scripts.add(new JBScrollPane(scriptTable), BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        actions.add(runScriptButton);
        actions.add(reloadScriptsButton);
        scripts.add(actions, BorderLayout.SOUTH);
        p.add(scripts, BorderLayout.CENTER);
        return p;
    }

    private JPanel buildConsoleTab() {
        JPanel p = new JPanel(new BorderLayout());
        JPanel evalRow = new JPanel(new BorderLayout(4, 0));
        evalRow.add(new JBLabel("eval:"), BorderLayout.WEST);
        evalRow.add(evalField, BorderLayout.CENTER);
        evalRow.add(evalButton, BorderLayout.EAST);
        p.add(evalRow, BorderLayout.NORTH);
        p.add(new JBScrollPane(console), BorderLayout.CENTER);
        return p;
    }

    private void wire() {
        connectButton.addActionListener(e -> connectFromFields());
        reconnectButton.addActionListener(e -> {
            if (session.state() == LuaBridgeSession.State.DISCONNECTED) {
                connectFromFields();
            } else {
                session.reconnect();
            }
        });
        disconnectButton.addActionListener(e -> session.disconnect());
        autoReconnectBox.addActionListener(
                e -> session.setAutoReconnect(autoReconnectBox.isSelected()));
        refreshButton.addActionListener(e -> refresh());
        autoRefreshBox.addActionListener(e -> {
            if (autoRefreshBox.isSelected()) {
                refresh();
            }
        });
        blockQueryButton.addActionListener(e -> queryBlock());
        runScriptButton.addActionListener(e -> runSelectedScript());
        reloadScriptsButton.addActionListener(e -> loadScripts());
        evalButton.addActionListener(e -> evalField());
        evalField.addActionListener(e -> evalField());
    }

    private void connectFromFields() {
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
            if (port < 1 || port > 65535) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException ex) {
            updateForState(LuaBridgeSession.State.ERROR,
                    "invalid port '" + portField.getText().trim() + "'");
            return;
        }
        String host = hostField.getText().trim();
        if (host.isEmpty()) {
            host = "127.0.0.1";
            hostField.setText(host);
        }
        session.connect(host, port);
    }

    private void refresh() {
        if (!session.isConnected()) {
            return;
        }
        session.refresh(
                st -> statusDetail.setText(st.raw()),
                this::loadNpcs);
        loadScripts();
    }

    private void loadNpcs(List<LuaBridgeSession.NpcInfo> npcs) {
        npcModel.setRowCount(0);
        for (LuaBridgeSession.NpcInfo n : npcs) {
            npcModel.addRow(new Object[]{n.name(), n.x(), n.y(), n.z()});
        }
    }

    private void loadScripts() {
        session.listScripts(names -> {
            scriptModel.setRowCount(0);
            for (String n : names) {
                scriptModel.addRow(new Object[]{n});
            }
        });
    }

    private void queryBlock() {
        try {
            int x = Integer.parseInt(blockX.getText().trim());
            int y = Integer.parseInt(blockY.getText().trim());
            int z = Integer.parseInt(blockZ.getText().trim());
            blockResult.setText("…");
            session.queryBlock(x, y, z, blockResult::setText);
        } catch (NumberFormatException ex) {
            blockResult.setText("coordinates must be integers");
        }
    }

    private void runSelectedScript() {
        int row = scriptTable.getSelectedRow();
        if (row < 0) {
            log("select a script first");
            return;
        }
        String name = String.valueOf(
                scriptModel.getValueAt(scriptTable.convertRowIndexToModel(row), 0));
        log("/luamap run " + name);
        session.runScript(name, this::log);
    }

    private void evalField() {
        String code = evalField.getText().trim();
        if (code.isEmpty()) {
            return;
        }
        log("> " + code);
        session.eval(code, this::log);
    }

    private void log(String line) {
        console.append(line + "\n");
        console.setCaretPosition(console.getDocument().getLength());
    }

    @Override
    public void onStateChanged(LuaBridgeSession.State state, String detail) {
        updateForState(state, detail);
        if (state == LuaBridgeSession.State.CONNECTED) {
            refresh();
        }
    }

    private void updateForState(LuaBridgeSession.State state, String detail) {
        boolean connected = state == LuaBridgeSession.State.CONNECTED;
        boolean busy = state == LuaBridgeSession.State.CONNECTING;
        statusBadge.setText(switch (state) {
            case CONNECTED -> "Connected";
            case CONNECTING -> "Connecting…";
            case ERROR -> "Error";
            case DISCONNECTED -> "Disconnected";
        });
        statusDetail.setText(detail == null ? "" : detail);
        connectButton.setEnabled(!connected && !busy);
        disconnectButton.setEnabled(connected || busy);
        reconnectButton.setEnabled(!busy);
        refreshButton.setEnabled(connected);
        blockQueryButton.setEnabled(connected);
        evalButton.setEnabled(connected);
        runScriptButton.setEnabled(connected);
        reloadScriptsButton.setEnabled(connected);
        if (!connected) {
            npcModel.setRowCount(0);
        }
    }

    @Override
    public void dispose() {
        session.close();
    }
}
