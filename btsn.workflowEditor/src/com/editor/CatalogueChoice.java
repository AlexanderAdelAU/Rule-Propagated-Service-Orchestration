package com.editor;

import java.awt.*;
import java.io.File;
import java.util.List;
import javax.swing.*;

/**
 * Choosing the catalogue that holds a process's own services: an existing domain's catalogue, which the processes of
 * that domain share, or a new one for a new domain. Asked the first time a process needs services of its own.
 */
public final class CatalogueChoice {
    public static final String BEFORE_WE_BEGIN = "Before we begin, we need a catalogue to hold the services you will be creating or importing.";
    public static final String EXPLAIN = "A catalogue belongs to a domain (credit, claims, ...): the processes of that domain share it, "
        + "so a service is written once and used by each of them. Processes of the domain are saved under ProcessDefinitionFolder/<domain>. "
        + "The standard Petri-net services stay available beside it, unchanged.";

    private CatalogueChoice() {}

    /** The domain a process lies in: its folder under ProcessDefinitionFolder, or "" when unsaved or elsewhere. */
    public static String processDomain(File common, File process) {
        if (common == null || process == null) return "";
        try {
            java.nio.file.Path processes = new File(common, "ProcessDefinitionFolder").getCanonicalFile().toPath();
            java.nio.file.Path location = process.getCanonicalFile().toPath();
            if (!location.startsWith(processes) || location.equals(processes)) return "";
            return processes.relativize(location).getName(0).toString();
        } catch (java.io.IOException ex) { return ""; }
    }

    /** The existing catalogue chosen, or a new one created for the domain named. */
    public static File resolve(File common, File existing, String newDomain) throws Exception {
        if (newDomain == null) {
            if (existing == null) throw new java.io.IOException("Choose a catalogue.");
            return existing;
        }
        return CatalogueWriter.create(common, newDomain.trim());
    }

    /**
     * Asks which catalogue the process's services go into; returns it (created if new), or null when cancelled.
     * A process saved under ProcessDefinitionFolder/<domain> starts on that domain: its catalogue, or a new one.
     */
    public static File ask(Component parent, File common, File process, String why, boolean deployed) {
        List<File> catalogues = ServiceRegistry.domainCatalogues(common);
        String domain = processDomain(common, process);
        JRadioButton useExisting = new JRadioButton("Select an existing catalogue:");
        JComboBox<File> existing = new JComboBox<>(catalogues.toArray(new File[0]));
        existing.setSelectedIndex(-1);   // nothing chosen until the list is opened
        existing.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                File file = (File)value;
                return super.getListCellRendererComponent(list, file == null ? "" : file.getParentFile().getName() + "  —  " + file.getName(), index, selected, focus);
            }
        });
        JRadioButton createNew = new JRadioButton("Create a catalogue for a new domain:");
        JTextField newDomain = new JTextField(16);
        newDomain.setToolTipText("Lower case, e.g. claims: creates BusinessServiceDefinitions/claims/Claims.json");
        ButtonGroup group = new ButtonGroup(); group.add(useExisting); group.add(createNew);
        File own = null;
        for (File c : catalogues) if (c.getParentFile().getName().equals(domain)) own = c;
        if (own != null) { useExisting.setSelected(true); existing.setSelectedItem(own); }
        else if (!domain.isEmpty() && !"petrinet".equals(domain)) { createNew.setSelected(true); newDomain.setText(domain); }
        else createNew.setSelected(true);   // a new process: no other domain's catalogue is offered as if it were its own
        useExisting.setEnabled(!catalogues.isEmpty());
        Runnable enable = () -> { existing.setEnabled(useExisting.isSelected()); newDomain.setEnabled(createNew.isSelected()); };
        useExisting.addActionListener(e -> {
            enable.run();
            if (existing.getSelectedIndex() < 0) SwingUtilities.invokeLater(() -> { if (existing.isShowing()) existing.showPopup(); });
        });
        createNew.addActionListener(e -> { enable.run(); newDomain.requestFocusInWindow(); });
        enable.run();
        JLabel creates = new JLabel(" ");
        creates.setForeground(Color.GRAY);
        Runnable preview = () -> {
            String d = newDomain.getText().trim();
            creates.setText(d.isEmpty() ? " " : CatalogueWriter.validDomain(d)
                ? "Creates BusinessServiceDefinitions/" + d + "/" + CatalogueWriter.catalogueName(d) + " and lists it for packaging"
                : "Use lower case letters, digits and _, starting with a letter");
        };
        newDomain.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { preview.run(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { preview.run(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { preview.run(); }
        });
        preview.run();

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.gridy = 0; c.gridwidth = 2; c.anchor = GridBagConstraints.WEST; c.insets = new Insets(2, 2, 2, 2);
        panel.add(text("<b>" + why + "</b><br>" + EXPLAIN.replace("<", "&lt;").replace(">", "&gt;")
            + (deployed ? "<br><br>The process's service deployment is set aside: deploy it again once its services are ready." : "")), c);
        c.gridy++; c.gridwidth = 1; c.insets = new Insets(10, 2, 2, 6); panel.add(useExisting, c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; panel.add(existing, c);
        c.gridx = 0; c.gridy++; c.fill = GridBagConstraints.NONE; c.insets = new Insets(4, 2, 2, 6); panel.add(createNew, c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; panel.add(newDomain, c);
        // Below both columns, so a long domain name does not squeeze the choices as it is typed.
        c.gridx = 0; c.gridy++; c.gridwidth = 2; c.insets = new Insets(0, 2, 2, 2); panel.add(creates, c);
        while (true) {
            int answer = JOptionPane.showConfirmDialog(parent, panel, "Catalogue for this process's services", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (answer != JOptionPane.OK_OPTION) return null;
            try {
                return resolve(common, useExisting.isSelected() ? (File)existing.getSelectedItem() : null, createNew.isSelected() ? newDomain.getText() : null);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(parent, ex.getMessage(), "Catalogue", JOptionPane.WARNING_MESSAGE);
            }
        }
    }

    private static JLabel text(String html) {
        return new JLabel("<html><div style='width:540px'>" + html + "</div></html>");
    }
}
