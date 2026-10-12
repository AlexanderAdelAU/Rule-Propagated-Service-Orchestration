package com.editor;

import java.awt.*;
import java.io.File;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;
import java.util.prefs.Preferences;
import javax.swing.*;

/**
 * Runs a process's BuildAndRun launcher from the editor and hands the analysis to the replay.
 * Shows the launcher's output, which phase it is in, and lets the run be stopped at any point.
 */
public final class RunWindow extends JFrame {
    private static final String ANT_PREF = "antCommand";
    private static final int MAX_CONSOLE_CHARS = 400_000;
    private final Preferences prefs = Preferences.userNodeForPackage(RunWindow.class);
    private final File root;
    private final String processName;
    private final Consumer<File> onAnalysis;
    private final Runnable onPlay;
    /** What to do once a run has been analysed; shown when the run completes. */
    final JPanel nextSteps = new JPanel(new GridBagLayout());
    final JButton play = new JButton("\u25B6 Replay the recorded run"), timing = new JButton("Elapsed time and queue wait chart"),
        spatial = new JButton("Token spatial view"), report = new JButton("Open analysis report");
    private File lastAnalysis;
    /** The real host processes behind the run, from their own logs. */
    private RunHosts hosts;
    final HostModel hostModel = new HostModel();
    private final javax.swing.Timer hostTimer = new javax.swing.Timer(1000, e -> refreshHosts());
    private long runStartedAt;
    private JScrollPane hostPane;
    final JComboBox<File> launcher;
    final JCheckBox allHostsHere = new JCheckBox("Run every host on this computer (127.0.0.1)", true);
    /** The machines chosen in the editor (Machines on the toolbar), and whether they are more than one. */
    final String machines;
    final boolean severalMachines;
    final JTextField ant = new JTextField(28);
    final JTextArea console = new JTextArea(24, 110);
    final JButton start = new JButton("Run"), stop = new JButton("Stop"), close = new JButton("Close");
    private final JLabel[] steps;
    private final JLabel status = new JLabel(" ");
    private LauncherRun run;

    private static final String[] STEP_NAMES = {"Build and start hosts", "Initialise", "Deploy rules", "Commitments received", "Fire tokens", "Collect", "Settle", "Analyse", "Replay"};

    public RunWindow(Window owner, File root, String processName, List<File> launchers, Consumer<File> onAnalysis, Runnable onPlay) {
        super("Run on hosts: " + processName);
        this.root = root; this.processName = processName; this.onAnalysis = onAnalysis; this.onPlay = onPlay;
        machines = ServiceRegistry.selectedInfrastructure();
        java.util.List<ServiceRegistry.Node> nodes = ServiceRegistry.infrastructureNodes(new File(root, "btsn.common/ProcessDefinitionFolder"), machines);
        severalMachines = ServiceRegistry.severalMachines(nodes);
        if (severalMachines) {
            // 127.0.0.1 would put every host here; the hosts on other machines run from their packages instead.
            allHostsHere.setSelected(false);
            allHostsHere.setEnabled(false);
            allHostsHere.setText("Run every host on this computer (not with " + machines + ")");
        }
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() { @Override public void windowClosing(java.awt.event.WindowEvent e) { closeWindow(); } });

        launcher = new JComboBox<>(launchers.toArray(new File[0]));
        launcher.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof File) { File f = (File)value; setText(f.getParentFile().getName() + "/" + f.getName()); }
                return this;
            }
        });
        ant.setText(prefs.get(ANT_PREF, ""));
        ant.setToolTipText("Ant's folder. Leave empty to use ANT_HOME, 'ant' on the PATH, or the Ant inside Eclipse (plugins/org.apache.ant_*).");
        JButton chooseAnt = new JButton("Choose...");
        chooseAnt.addActionListener(e -> chooseAnt());

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 6, 3, 6); c.anchor = GridBagConstraints.WEST; c.fill = GridBagConstraints.HORIZONTAL;
        c.gridy = 0; c.gridx = 0; form.add(new JLabel("Launcher:"), c); c.gridx = 1; c.weightx = 1; c.gridwidth = 2; form.add(launcher, c);
        c.gridy = 1; c.gridx = 0; c.weightx = 0; c.gridwidth = 1; form.add(new JLabel("Hosts:"), c); c.gridx = 1; c.gridwidth = 2; form.add(allHostsHere, c);
        c.gridy = 2; c.gridx = 0; c.gridwidth = 1; c.weightx = 0; form.add(new JLabel("Machines:"), c);
        c.gridx = 1; c.weightx = 1; c.gridwidth = 2; form.add(new JLabel(describeMachines(machines, nodes)), c); c.gridwidth = 1;
        c.gridy = 3; c.gridx = 0; c.gridwidth = 1; form.add(new JLabel("Ant folder:"), c); c.gridx = 1; c.weightx = 1; form.add(ant, c); c.gridx = 2; c.weightx = 0; form.add(chooseAnt, c);
        c.gridy = 4; c.gridx = 0; form.add(new JLabel("Analysis:"), c);
        c.gridx = 1; c.gridwidth = 2;
        form.add(new JLabel(LauncherRun.analysisFileFor(root, processName).getAbsolutePath().replace(root.getAbsolutePath() + File.separator, "")), c);

        JPanel progress = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        steps = new JLabel[STEP_NAMES.length];
        for (int i = 0; i < steps.length; i++) {
            steps[i] = new JLabel(STEP_NAMES[i]);
            steps[i].setOpaque(true);
            steps[i].setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY), BorderFactory.createEmptyBorder(3, 8, 3, 8)));
            progress.add(steps[i]);
            if (i < steps.length - 1) progress.add(new JLabel("\u2192"));
        }
        resetSteps();

        console.setEditable(false);
        console.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        console.setRows(16);
        JScrollPane consolePane = new JScrollPane(console);
        consolePane.setBorder(BorderFactory.createTitledBorder("Output"));

        JTable hostTable = new JTable(hostModel);
        hostTable.setRowHeight(20);
        hostTable.setFillsViewportHeight(true);
        hostTable.getColumnModel().getColumn(0).setPreferredWidth(90);
        hostTable.getColumnModel().getColumn(1).setPreferredWidth(150);
        hostTable.getColumnModel().getColumn(2).setPreferredWidth(190);
        hostTable.getColumnModel().getColumn(3).setPreferredWidth(170);
        hostTable.getColumnModel().getColumn(4).setPreferredWidth(130);
        hostTable.getColumnModel().getColumn(5).setPreferredWidth(95);
        hostTable.setToolTipText("Each row is a real host process started for this run, read from its own log beside the launcher");
        hostPane = new JScrollPane(hostTable);
        hostPane.setBorder(BorderFactory.createTitledBorder("Hosts (real processes, from their own logs)"));
        launcher.addActionListener(e -> planHosts());
        allHostsHere.addActionListener(e -> planHosts());
        planHosts();

        start.addActionListener(e -> startRun());
        stop.addActionListener(e -> { if (run != null) { status.setText("Stopping..."); stop.setEnabled(false); new Thread(run::stop, "stop-run").start(); } });
        close.addActionListener(e -> closeWindow());
        stop.setEnabled(false);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(start); buttons.add(stop); buttons.add(close);
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(status, BorderLayout.CENTER); bottom.add(buttons, BorderLayout.EAST);
        status.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));

        nextSteps.setBorder(BorderFactory.createTitledBorder("Next steps"));
        addStep(0, play, "Watch the recorded tokens move through the design (events measured at the hosts)");
        addStep(1, timing, "Elapsed time and the longest queue wait per workflow, for each version");
        addStep(2, spatial, "Token activity at each place over time");
        addStep(3, report, "The full analyzer report");
        play.setFont(play.getFont().deriveFont(Font.BOLD));
        play.addActionListener(e -> { onPlay.run(); status.setText("Replaying in the editor."); });
        timing.addActionListener(e -> openViewer("chart-timing", "elapsed time chart"));
        spatial.addActionListener(e -> openViewer("chart-spatial", "spatial view"));
        report.addActionListener(e -> showReport());
        nextSteps.setVisible(false);
        JPanel south = new JPanel(new BorderLayout(6, 6));
        south.add(nextSteps, BorderLayout.NORTH); south.add(bottom, BorderLayout.SOUTH);

        JPanel north = new JPanel(new BorderLayout());
        north.add(form, BorderLayout.NORTH); north.add(progress, BorderLayout.CENTER); north.add(hostPane, BorderLayout.SOUTH);
        JPanel content = new JPanel(new BorderLayout(6, 6));
        content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        content.add(north, BorderLayout.NORTH); content.add(consolePane, BorderLayout.CENTER); content.add(south, BorderLayout.SOUTH);
        setContentPane(content);
        pack();
        setLocationRelativeTo(owner);
        status.setText("Runs the launcher, stops its hosts when collection is done, analyses the run and loads it into the replay.");
        if (severalMachines) status.setText(packagesReady()
            ? "Runs on " + machines + ". Start each host package on its machine with launch.bat, then Run."
            : "Runs on " + machines + ", but this launcher has no host packages yet: use Build > Build host packages first.");
    }

    public boolean isRunning() { return run != null && run.isRunning(); }

    /** Build made the packages for the hosts on other machines (any ZIP in the launcher's package folder). */
    private boolean packagesReady() {
        File selected = (File)launcher.getSelectedItem();
        File[] zips = selected == null ? null : LauncherRun.hostPackagesFolder(root, selected).listFiles((d, n) -> n.endsWith(".zip"));
        return zips != null && zips.length > 0;
    }

    /** "TwoHost: P1, P3, P5 on 192.168.1.82; P2, P4, P6 on 192.168.1.152". */
    static String describeMachines(String name, java.util.List<ServiceRegistry.Node> nodes) {
        java.util.Map<String, java.util.List<String>> byAddress = new java.util.LinkedHashMap<>();
        for (ServiceRegistry.Node node : nodes) byAddress.computeIfAbsent(node.address, a -> new java.util.ArrayList<>()).add(node.node);
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, java.util.List<String>> e : byAddress.entrySet()) parts.add(String.join(", ", e.getValue()) + " on " + e.getKey());
        return name + (parts.isEmpty() ? "" : ": " + String.join("; ", parts));
    }

    /** The Ant folder chosen in this window (empty: ANT_HOME, the PATH or Eclipse's Ant); Build uses it too. */
    static String antCommand() {
        return Preferences.userNodeForPackage(RunWindow.class).get(ANT_PREF, "");
    }

    void startRun() {
        File selected = (File)launcher.getSelectedItem();
        if (selected == null) return;
        try { LauncherRun.antHome(ant.getText()); }
        catch (java.io.IOException ex) { status.setText(ex.getMessage()); JOptionPane.showMessageDialog(this, ex.getMessage(), "Ant", JOptionPane.WARNING_MESSAGE); return; }
        prefs.put(ANT_PREF, ant.getText().trim());
        console.setText("");
        resetSteps();
        nextSteps.setVisible(false);
        planHosts();
        runStartedAt = System.currentTimeMillis();
        hosts.started(runStartedAt);
        hostTimer.start();
        start.setEnabled(false); stop.setEnabled(true); launcher.setEnabled(false); allHostsHere.setEnabled(false);
        status.setText("Running " + selected.getName() + (severalMachines ? " on " + machines : "") + "...");
        run = new LauncherRun(selected, root, LauncherRun.analysisFileFor(root, processName), allHostsHere.isSelected(), ant.getText(), new LauncherRun.Listener() {
            public void line(String text) { SwingUtilities.invokeLater(() -> { append(text); hostLine(text); }); }
            public void phase(LauncherRun.Phase phase, String detail) { SwingUtilities.invokeLater(() -> showPhase(phase)); }
            public void finished(LauncherRun.Phase outcome, File analysis, String message) { SwingUtilities.invokeLater(() -> done(outcome, analysis, message)); }
        }).infrastructure(machines);
        run.start();
    }

    private void append(String text) {
        console.append(text + "\n");
        if (console.getDocument().getLength() > MAX_CONSOLE_CHARS) console.replaceRange("", 0, console.getDocument().getLength() - MAX_CONSOLE_CHARS / 2);
        console.setCaretPosition(console.getDocument().getLength());
    }

    private void resetSteps() { for (JLabel step : steps) { step.setBackground(new Color(0xF2F2F2)); step.setForeground(Color.GRAY); } }

    private void showPhase(LauncherRun.Phase phase) {
        int index;
        switch (phase) {
            case BUILD: index = 0; break; case INITIALISE: index = 1; break; case DEPLOY: index = 2; break;
            case COMMIT: index = 3; break; case FIRE: index = 4; break; case COLLECT: index = 5; break;
            case SETTLE: index = 6; break; case ANALYSE: index = 7; break; case DONE: index = 8; break;
            default: return;
        }
        for (int i = 0; i < steps.length; i++) {
            boolean doneStep = i < index || phase == LauncherRun.Phase.DONE;
            steps[i].setBackground(doneStep ? new Color(0xD8EFD8) : i == index ? new Color(0xFFF1C2) : new Color(0xF2F2F2));
            steps[i].setForeground(i <= index ? Color.BLACK : Color.GRAY);
        }
    }

    private void done(LauncherRun.Phase outcome, File analysis, String message) {
        start.setEnabled(true); stop.setEnabled(false); launcher.setEnabled(true); allHostsHere.setEnabled(!severalMachines);
        hostTimer.stop();
        hosts.refresh(); hosts.stopped(); hostModel.fireTableDataChanged();
        java.text.SimpleDateFormat clock = new java.text.SimpleDateFormat("HH:mm:ss");
        long end = System.currentTimeMillis();
        if (outcome == LauncherRun.Phase.DONE)
            append("Ran " + clock.format(new Date(runStartedAt)) + " to " + clock.format(new Date(end)) + " (" + (end - runStartedAt) / 1000 + " s) on " + hosts.describe());
        if (outcome == LauncherRun.Phase.DONE && analysis != null) {
            onAnalysis.accept(analysis);
            lastAnalysis = analysis;
            nextSteps.setVisible(true);
            getContentPane().revalidate();
            play.requestFocusInWindow();
            getRootPane().setDefaultButton(play);
            status.setText("Run complete. " + message);
        } else {
            for (JLabel step : steps) if (step.getBackground().equals(new Color(0xFFF1C2))) step.setBackground(new Color(0xF6D0D0));
            status.setText(message);
        }
    }

    private void planHosts() {
        File selected = (File)launcher.getSelectedItem();
        if (selected == null || isRunning()) return;
        hosts = RunHosts.forLauncher(selected, root, allHostsHere.isSelected(), machines);
        hostModel.fireTableDataChanged();
        // Just tall enough for this launcher's hosts (up to six places and Monitor).
        hostPane.setPreferredSize(new Dimension(700, 20 * Math.min(7, hosts.hosts.size()) + 50));
        hostPane.revalidate();
    }

    private void refreshHosts() { if (hosts != null) { hosts.refresh(); hostModel.fireTableDataChanged(); } }

    private void hostLine(String text) {
        if (hosts == null) return;
        hosts.launcherLine(text);
        if (text.startsWith("[editor] Hosts stopped")) { hosts.refresh(); hosts.stopped(); hostModel.fireTableDataChanged(); }
    }

    /** Host | Endpoint | Process | State | Service calls */
    final class HostModel extends javax.swing.table.AbstractTableModel {
        private final String[] columns = {"Host", "Endpoint", "Process", "State", "Rules committed", "Service calls"};
        public int getRowCount() { return hosts == null ? 0 : hosts.hosts.size(); }
        public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int c) { return columns[c]; }
        public Object getValueAt(int row, int column) {
            RunHosts.Host h = hosts.hosts.get(row);
            switch (column) {
                case 0: return h.name;
                case 1: return h.endpoint;
                case 2: return h.process;
                case 3: return h.state;
                case 4: return String.join(", ", h.committed);
                default: return "Monitor".equals(h.name) ? "" : String.valueOf(h.calls);
            }
        }
    }

    private void addStep(int row, JButton button, String what) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row; c.insets = new Insets(2, 6, 2, 6); c.anchor = GridBagConstraints.WEST;
        c.gridx = 0; c.fill = GridBagConstraints.HORIZONTAL; nextSteps.add(button, c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.NONE; nextSteps.add(new JLabel(what), c);
    }

    /** The Monitor viewers run through the launcher's chart targets, like analyse. */
    private void openViewer(String target, String name) {
        if (run == null) return;
        append("[editor] Opening the " + name + " (ant " + target + ")...");
        status.setText("Opening the " + name + "; it appears in its own window.");
        run.openTarget(target);
    }

    private void showReport() {
        if (lastAnalysis == null) return;
        try {
            JTextArea text = new JTextArea(new String(java.nio.file.Files.readAllBytes(lastAnalysis.toPath()), java.nio.charset.StandardCharsets.UTF_8));
            text.setEditable(false); text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12)); text.setCaretPosition(0);
            JFrame frame = new JFrame(lastAnalysis.getName());
            frame.setContentPane(new JScrollPane(text));
            frame.setSize(900, 700); frame.setLocationRelativeTo(this); frame.setVisible(true);
        } catch (java.io.IOException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Analysis report", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void chooseAnt() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Choose Ant's folder (it contains bin and lib)");
        if (!ant.getText().trim().isEmpty()) chooser.setSelectedFile(new File(ant.getText().trim()));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) ant.setText(chooser.getSelectedFile().getAbsolutePath());
    }

    void closeWindow() {
        if (isRunning() && JOptionPane.showConfirmDialog(this, "A run is in progress. Stop it and close?", "Run",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
        if (isRunning()) run.stop();
        dispose();
    }
}
