package com.editor;

import java.awt.*;
import java.io.File;
import javax.swing.*;

/**
 * Build for a combined process (&lt;name&gt;_LiveChange.json): an amalgam of independent processes that its launcher of the same
 * name deploys and runs, each under its own version. With remote services you build, deploy, then run: this dialog builds the
 * host packages; copying them to their machines is manual; Run on hosts runs the launcher.
 */
public final class CombinedBuildDialog extends JDialog {
    final JTextArea output = new JTextArea(11, 92);
    private final JButton packages = new JButton("Build host packages");
    private final JButton close = new JButton("Cancel");

    public CombinedBuildDialog(Window owner, File root, String processName, File launcher) {
        super(owner, "Build", ModalityType.APPLICATION_MODAL);
        String machines = ServiceRegistry.selectedInfrastructure();

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 6, 4, 6); c.anchor = GridBagConstraints.WEST; c.fill = GridBagConstraints.HORIZONTAL;
        row(form, c, 0, "Process:", new JLabel(processName));
        row(form, c, 1, "Launcher:", new JLabel(launcher.getName()));
        row(form, c, 2, "Machines:", new JLabel(RunWindow.describeMachines(machines, ServiceRegistry.infrastructureNodes(launcher, machines))));

        JTextArea note = new JTextArea(
            "This is a combined process: an amalgam of independent processes, each deployed and run under its own version.\n"
            + "Its launcher runs them in one launch:\n  " + String.join("\n  ", BuildAndRunGenerator.combinedSummary(launcher)) + "\n"
            + "With remote services you build, deploy, then run: Build makes the host packages; copy each to its machine and start it; then Run on hosts.");
        note.setEditable(false); note.setOpaque(false); note.setFont(UIManager.getFont("Label.font"));
        note.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));

        output.setEditable(false);
        output.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane outputPane = new JScrollPane(output);
        outputPane.setBorder(BorderFactory.createTitledBorder("Build"));

        packages.setToolTipText("Build a self-contained package for each other machine: unzip it there and run launch.bat");
        packages.addActionListener(e -> build(root, processName, launcher, machines));
        close.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(packages); buttons.add(close);

        JPanel top = new JPanel(new BorderLayout(6, 6));
        top.add(form, BorderLayout.NORTH);
        top.add(note, BorderLayout.CENTER);
        JPanel content = new JPanel(new BorderLayout(6, 6));
        content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        content.add(top, BorderLayout.NORTH);
        content.add(outputPane, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().setDefaultButton(packages);
        pack();
        setLocationRelativeTo(owner);
    }

    private void build(File root, String processName, File launcher, String machines) {
        packages.setEnabled(false);
        output.setText("Building the host packages for " + launcher.getName() + " on " + machines + "...\n");
        new LauncherRun(launcher, root, LauncherRun.analysisFileFor(root, processName), false, RunWindow.antCommand(),
            new LauncherRun.Listener() {
                public void line(String text) { SwingUtilities.invokeLater(() -> { output.append(text + "\n"); output.setCaretPosition(output.getDocument().getLength()); }); }
                public void phase(LauncherRun.Phase phase, String detail) { }
                public void finished(LauncherRun.Phase outcome, File analysis, String message) { SwingUtilities.invokeLater(() -> {
                    output.append("\n" + message.replace("; ", "\n") + "\n");
                    output.setCaretPosition(output.getDocument().getLength());
                    close.setText("Close");
                    if (outcome != LauncherRun.Phase.DONE) { packages.setEnabled(true); return; }
                    output.append("\nDeploy: copy each ZIP to its machine, unzip it and run launch.bat. Then click Run on hosts.\n");
                    File folder = LauncherRun.hostPackagesFolder(root, launcher);
                    if (folder.isDirectory() && Desktop.isDesktopSupported()) {
                        try { Desktop.getDesktop().open(folder); } catch (Exception ignored) { }
                    }
                }); }
            }).infrastructure(machines).packagesOnly().start();
    }

    private static void row(JPanel form, GridBagConstraints c, int y, String label, JComponent field) {
        c.gridy = y; c.gridx = 0; c.weightx = 0; form.add(new JLabel(label), c);
        c.gridx = 1; c.weightx = 1; form.add(field, c);
    }
}
