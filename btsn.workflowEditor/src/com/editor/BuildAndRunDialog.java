package com.editor;

import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import javax.swing.table.AbstractTableModel;

/** "Create Build and Run": a few run choices, a summary of the files involved, then the launcher. */
public final class BuildAndRunDialog extends JDialog {
    private final BuildAndRunGenerator.Plan plan;
    /** One row per event generator; each runs under its own rule version. */
    final List<BuildAndRunGenerator.Run> runs = new ArrayList<>();
    final GeneratorModel generators = new GeneratorModel();
    final JSpinner tokens = new JSpinner(new SpinnerNumberModel(10, 1, 100000, 1));
    final JSpinner completion = new JSpinner(new SpinnerNumberModel(10, 1, 3600, 1));
    final JTextField name = new JTextField(28);
    final JComboBox<File> folder;
    final JTextArea summary = new JTextArea(11, 92);
    private final JButton create = new JButton("Create");
    File created;

    public BuildAndRunDialog(Window owner, BuildAndRunGenerator.Plan plan) {
        super(owner, "Create Build and Run", ModalityType.APPLICATION_MODAL);
        this.plan = plan;
        BuildAndRunGenerator.Options d = plan.defaults;
        for (BuildAndRunGenerator.Run run : d.runs) runs.add(run.copy());
        tokens.setValue(d.tokens);
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

        JTable table = new JTable(generators);
        table.setRowHeight(22);
        table.getColumnModel().getColumn(0).setMaxWidth(45);
        table.getColumnModel().getColumn(3).setCellEditor(new DefaultCellEditor(new JComboBox<>(BuildAndRunGenerator.VERSIONS.toArray(new String[0]))));
        table.setPreferredScrollableViewportSize(new Dimension(620, Math.min(4, Math.max(1, runs.size())) * 22 + 4));
        table.setToolTipText("Every ticked generator fires; give each its own rule version so their tokens stay apart.");
        JScrollPane tablePane = new JScrollPane(table);
        tablePane.setBorder(BorderFactory.createTitledBorder("Event generators (each runs under its own version)"));

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 6, 4, 6); c.anchor = GridBagConstraints.WEST; c.fill = GridBagConstraints.HORIZONTAL;
        row(form, c, 0, "Process:", new JLabel(plan.processName));
        row(form, c, 1, "Tokens per generator:", tokens);
        row(form, c, 2, "Wait after last token (s):", completion);
        row(form, c, 3, "Launcher name:", name);
        row(form, c, 4, "Folder:", folder);

        summary.setEditable(false);
        summary.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane summaryPane = new JScrollPane(summary);
        summaryPane.setBorder(BorderFactory.createTitledBorder("Files and run"));

        JButton cancel = new JButton("Cancel");
        create.addActionListener(e -> create());
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(create); buttons.add(cancel);

        JPanel top = new JPanel(new BorderLayout(6, 6));
        top.add(form, BorderLayout.NORTH);
        top.add(tablePane, BorderLayout.CENTER);
        JPanel root = new JPanel(new BorderLayout(6, 6));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        root.add(top, BorderLayout.NORTH);
        root.add(summaryPane, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        setContentPane(root);
        getRootPane().setDefaultButton(create);

        folder.addActionListener(e -> refresh());
        for (JSpinner spinner : new JSpinner[] {tokens, completion}) spinner.addChangeListener(e -> refresh());
        name.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { refresh(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { refresh(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { refresh(); }
        });
        refresh();
        pack();
        setLocationRelativeTo(owner);
    }

    /** Run | Event generator | Feeds | Version | Interval (ms) */
    final class GeneratorModel extends AbstractTableModel {
        private final String[] columns = {"Run", "Event generator", "Feeds", "Version", "Interval (ms)"};
        public int getRowCount() { return runs.size(); }
        public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }
        @Override public Class<?> getColumnClass(int column) { return column == 0 ? Boolean.class : column == 4 ? Integer.class : String.class; }
        @Override public boolean isCellEditable(int row, int column) { return column == 0 || column == 3 || column == 4; }
        public Object getValueAt(int row, int column) {
            BuildAndRunGenerator.Run run = runs.get(row);
            switch (column) {
                case 0: return run.enabled;
                case 1: return run.generatorId;
                case 2: return plan.targets.get(run.generatorId) + "." + plan.operations.get(run.generatorId);
                case 3: return run.version;
                default: return run.intervalMs;
            }
        }
        @Override public void setValueAt(Object value, int row, int column) {
            BuildAndRunGenerator.Run run = runs.get(row);
            if (column == 0) run.enabled = Boolean.TRUE.equals(value);
            else if (column == 3 && value != null) run.version = value.toString();
            else if (column == 4 && value instanceof Integer && (Integer)value > 0) run.intervalMs = (Integer)value;
            fireTableRowsUpdated(row, row);
            refresh();
        }
    }

    private static void row(JPanel form, GridBagConstraints c, int y, String label, JComponent field) {
        c.gridy = y; c.gridx = 0; c.weightx = 0; form.add(new JLabel(label), c);
        c.gridx = 1; c.weightx = 1; form.add(field, c);
    }

    BuildAndRunGenerator.Options options() {
        BuildAndRunGenerator.Options o = new BuildAndRunGenerator.Options();
        for (BuildAndRunGenerator.Run run : runs) o.runs.add(run.copy());
        o.tokens = (Integer)tokens.getValue();
        o.completionSeconds = (Integer)completion.getValue();
        o.launcherName = name.getText().trim();
        o.folder = (File)folder.getSelectedItem();
        return o;
    }

    private void refresh() {
        BuildAndRunGenerator.Options o = options();
        if (o.launcherName.isEmpty() || o.folder == null) { summary.setText("Enter a launcher name."); create.setEnabled(false); return; }
        create.setEnabled(BuildAndRunGenerator.problems(plan, o).isEmpty());
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
