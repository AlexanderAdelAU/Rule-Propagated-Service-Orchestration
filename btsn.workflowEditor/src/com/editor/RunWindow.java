package com.editor;

import java.awt.*;
import java.io.File;
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
    final JComboBox<File> launcher;
    final JCheckBox allHostsHere = new JCheckBox("Run every host on this computer (127.0.0.1)", true);
    final JTextField ant = new JTextField(28);
    final JTextArea console = new JTextArea(24, 110);
    final JButton start = new JButton("Run"), stop = new JButton("Stop"), close = new JButton("Close");
    private final JLabel[] steps;
    private final JLabel status = new JLabel(" ");
    private LauncherRun run;

    private static final String[] STEP_NAMES = {"Build and start", "1 Initialise", "2 Run", "3 Collect", "Settle", "Analyse", "Replay"};

    public RunWindow(Window owner, File root, String processName, List<File> launchers, Consumer<File> onAnalysis) {
        super("Run " + processName);
        this.root = root; this.processName = processName; this.onAnalysis = onAnalysis;
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
        c.gridy = 2; c.gridx = 0; c.gridwidth = 1; form.add(new JLabel("Ant folder:"), c); c.gridx = 1; c.weightx = 1; form.add(ant, c); c.gridx = 2; c.weightx = 0; form.add(chooseAnt, c);
        c.gridy = 3; c.gridx = 0; form.add(new JLabel("Analysis:"), c);
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
        JScrollPane consolePane = new JScrollPane(console);
        consolePane.setBorder(BorderFactory.createTitledBorder("Output"));

        start.addActionListener(e -> startRun());
        stop.addActionListener(e -> { if (run != null) { status.setText("Stopping..."); stop.setEnabled(false); new Thread(run::stop, "stop-run").start(); } });
        close.addActionListener(e -> closeWindow());
        stop.setEnabled(false);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(start); buttons.add(stop); buttons.add(close);
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(status, BorderLayout.CENTER); bottom.add(buttons, BorderLayout.EAST);
        status.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));

        JPanel north = new JPanel(new BorderLayout());
        north.add(form, BorderLayout.NORTH); north.add(progress, BorderLayout.SOUTH);
        JPanel content = new JPanel(new BorderLayout(6, 6));
        content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        content.add(north, BorderLayout.NORTH); content.add(consolePane, BorderLayout.CENTER); content.add(bottom, BorderLayout.SOUTH);
        setContentPane(content);
        pack();
        setLocationRelativeTo(owner);
        status.setText("Runs the launcher, stops its hosts when collection is done, analyses the run and loads it into the replay.");
    }

    public boolean isRunning() { return run != null && run.isRunning(); }

    void startRun() {
        File selected = (File)launcher.getSelectedItem();
        if (selected == null) return;
        try { LauncherRun.antHome(ant.getText()); }
        catch (java.io.IOException ex) { status.setText(ex.getMessage()); JOptionPane.showMessageDialog(this, ex.getMessage(), "Ant", JOptionPane.WARNING_MESSAGE); return; }
        prefs.put(ANT_PREF, ant.getText().trim());
        console.setText("");
        resetSteps();
        start.setEnabled(false); stop.setEnabled(true); launcher.setEnabled(false); allHostsHere.setEnabled(false);
        status.setText("Running " + selected.getName() + "...");
        run = new LauncherRun(selected, root, LauncherRun.analysisFileFor(root, processName), allHostsHere.isSelected(), ant.getText(), new LauncherRun.Listener() {
            public void line(String text) { SwingUtilities.invokeLater(() -> append(text)); }
            public void phase(LauncherRun.Phase phase, String detail) { SwingUtilities.invokeLater(() -> showPhase(phase)); }
            public void finished(LauncherRun.Phase outcome, File analysis, String message) { SwingUtilities.invokeLater(() -> done(outcome, analysis, message)); }
        });
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
            case BUILD: index = 0; break; case INITIALISE: index = 1; break; case RUN: index = 2; break;
            case COLLECT: index = 3; break; case SETTLE: index = 4; break; case ANALYSE: index = 5; break;
            case DONE: index = 6; break;
            default: return;
        }
        for (int i = 0; i < steps.length; i++) {
            boolean doneStep = i < index || phase == LauncherRun.Phase.DONE;
            steps[i].setBackground(doneStep ? new Color(0xD8EFD8) : i == index ? new Color(0xFFF1C2) : new Color(0xF2F2F2));
            steps[i].setForeground(i <= index ? Color.BLACK : Color.GRAY);
        }
    }

    private void done(LauncherRun.Phase outcome, File analysis, String message) {
        start.setEnabled(true); stop.setEnabled(false); launcher.setEnabled(true); allHostsHere.setEnabled(true);
        if (outcome == LauncherRun.Phase.DONE && analysis != null) {
            onAnalysis.accept(analysis);
            status.setText(message + " Loaded into the replay: press Play in the editor.");
        } else {
            for (JLabel step : steps) if (step.getBackground().equals(new Color(0xFFF1C2))) step.setBackground(new Color(0xF6D0D0));
            status.setText(message);
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
