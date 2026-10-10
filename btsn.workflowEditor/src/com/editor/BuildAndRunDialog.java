package com.editor;

import java.awt.*;
import java.io.File;
import java.util.List;
import javax.swing.*;

/** "Create Build and Run": a few run choices, a summary of the files involved, then the launcher. */
public final class BuildAndRunDialog extends JDialog {
    private final BuildAndRunGenerator.Plan plan;
    final JComboBox<String> generator;
    final JComboBox<String> version = new JComboBox<>(BuildAndRunGenerator.VERSIONS.toArray(new String[0]));
    final JSpinner tokens = new JSpinner(new SpinnerNumberModel(10, 1, 100000, 1));
    final JSpinner interval = new JSpinner(new SpinnerNumberModel(1000, 1, 3600000, 100));
    final JSpinner completion = new JSpinner(new SpinnerNumberModel(10, 1, 3600, 1));
    final JTextField name = new JTextField(28);
    final JComboBox<File> folder;
    final JTextArea summary = new JTextArea(8, 92);
    File created;

    public BuildAndRunDialog(Window owner, BuildAndRunGenerator.Plan plan) {
        super(owner, "Create Build and Run", ModalityType.APPLICATION_MODAL);
        this.plan = plan;
        BuildAndRunGenerator.Options d = plan.defaults;
        generator = new JComboBox<>(plan.targets.keySet().toArray(new String[0]));
        generator.setSelectedItem(d.generatorId);
        version.setSelectedItem(d.version);
        tokens.setValue(d.tokens);
        interval.setValue(d.intervalMs);
        completion.setValue(d.completionSeconds);
        name.setText(d.launcherName);
        folder = new JComboBox<>(plan.folders.toArray(new File[0]));
        folder.setSelectedItem(d.folder);
        folder.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof File) setText(((File)value).getName());
                return this;
            }
        });

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 6, 4, 6); c.anchor = GridBagConstraints.WEST; c.fill = GridBagConstraints.HORIZONTAL;
        row(form, c, 0, "Process:", new JLabel(plan.processName));
        row(form, c, 1, "Event generator:", generator);
        row(form, c, 2, "Rule version:", version);
        row(form, c, 3, "Number of tokens:", tokens);
        row(form, c, 4, "Interval between tokens (ms):", interval);
        row(form, c, 5, "Wait after last token (s):", completion);
        row(form, c, 6, "Launcher name:", name);
        row(form, c, 7, "Folder:", folder);

        summary.setEditable(false);
        summary.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane summaryPane = new JScrollPane(summary);
        summaryPane.setBorder(BorderFactory.createTitledBorder("Files and run"));

        JButton create = new JButton("Create");
        JButton cancel = new JButton("Cancel");
        create.addActionListener(e -> create());
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(create); buttons.add(cancel);

        JPanel root = new JPanel(new BorderLayout(6, 6));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        root.add(form, BorderLayout.NORTH);
        root.add(summaryPane, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        setContentPane(root);
        getRootPane().setDefaultButton(create);

        generator.addActionListener(e -> {
            String id = (String)generator.getSelectedItem();
            if (id != null) { interval.setValue(plan.rates.get(id)); version.setSelectedItem(plan.versions.get(id)); }
            refresh();
        });
        for (JComboBox<?> combo : new JComboBox<?>[] {version, folder}) combo.addActionListener(e -> refresh());
        for (JSpinner spinner : new JSpinner[] {tokens, interval, completion}) spinner.addChangeListener(e -> refresh());
        name.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { refresh(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { refresh(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { refresh(); }
        });
        refresh();
        pack();
        setLocationRelativeTo(owner);
    }

    private static void row(JPanel form, GridBagConstraints c, int y, String label, JComponent field) {
        c.gridy = y; c.gridx = 0; c.weightx = 0; form.add(new JLabel(label), c);
        c.gridx = 1; c.weightx = 1; form.add(field, c);
    }

    BuildAndRunGenerator.Options options() {
        BuildAndRunGenerator.Options o = new BuildAndRunGenerator.Options();
        o.generatorId = (String)generator.getSelectedItem();
        o.version = (String)version.getSelectedItem();
        o.tokens = (Integer)tokens.getValue();
        o.intervalMs = (Integer)interval.getValue();
        o.completionSeconds = (Integer)completion.getValue();
        o.launcherName = name.getText().trim();
        o.folder = (File)folder.getSelectedItem();
        return o;
    }

    private void refresh() {
        BuildAndRunGenerator.Options o = options();
        if (o.launcherName.isEmpty() || o.folder == null) { summary.setText("Enter a launcher name."); return; }
        summary.setText(String.join("\n", BuildAndRunGenerator.summary(plan, o)));
        summary.setCaretPosition(0);
    }

    /** Write the files; also used by checks without showing the dialog. */
    File createFiles() throws Exception {
        BuildAndRunGenerator.Options o = options();
        if (!o.launcherName.matches("[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("Use letters, digits, '_', '-' or '.' in the launcher name.");
        created = BuildAndRunGenerator.write(plan, o);
        return created;
    }

    private void create() {
        BuildAndRunGenerator.Options o = options();
        File launcher = plan.launcher(o);
        if (launcher.exists() && JOptionPane.showConfirmDialog(this, launcher.getName() + " already exists. Replace it?",
                "Create Build and Run", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
        try {
            createFiles();
            List<String> lines = BuildAndRunGenerator.summary(plan, o);
            JOptionPane.showMessageDialog(this,
                "Created " + launcher.getName() + " in " + o.folder.getName() + ".\n\n"
                + "Run it from Eclipse (right-click > Run As > Ant Build) or with:\n"
                + "    ant -f " + launcher.getName() + "\n"
                + "Add -Dhost.address=127.0.0.1 to run every host on this machine.\n"
                + "Afterwards:  ant -f " + launcher.getName() + " analyse\n\n"
                + String.join("\n", lines),
                "Build and Run created", JOptionPane.INFORMATION_MESSAGE);
            dispose();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Could not create Build and Run", JOptionPane.ERROR_MESSAGE);
        }
    }
}
