package com.editor;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.List;
import java.util.prefs.Preferences;

/**
 * Defines workflow-specific infrastructure capabilities.
 * Networking remains defined by the existing runtime configuration.
 */
public class InfrastructureDefinitionFrame extends JFrame {
    private final List<Capability> capabilities = new ArrayList<>();
    private final CapabilityModel capabilityModel = new CapabilityModel();
    private final ArgumentModel argumentModel = new ArgumentModel();
    private final JTable capabilityTable = new JTable(capabilityModel);
    private final JTable argumentTable = new JTable(argumentModel);
    private final TitledBorder argumentsBorder = BorderFactory.createTitledBorder("Arguments - select a capability");
    private static final String PREF_DEFINITION_DIR = "infrastructureDefinitionDir";
    private static final String PREF_BINDINGS_DIR = "canonicalBindingsDir";
    private final Preferences preferences = Preferences.userNodeForPackage(InfrastructureDefinitionFrame.class);
    private File currentFile;

    public InfrastructureDefinitionFrame() {
        super("Infrastructure Definition");
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setSize(950, 600);
        setMinimumSize(new Dimension(820, 500));
        setLocationByPlatform(true);

        JPanel root = new JPanel(new BorderLayout(6, 6));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JTextArea explanation = new JTextArea(
            "Define which business service operations each infrastructure node can host. " +
            "Arguments and return attributes are the canonical binding contract. " +
            "Networking is unchanged and is not defined here.");
        explanation.setEditable(false);
        explanation.setLineWrap(true);
        explanation.setWrapStyleWord(true);
        explanation.setOpaque(false);
        root.add(explanation, BorderLayout.NORTH);

        JPanel capabilitiesPanel = new JPanel(new BorderLayout(4, 4));
        capabilitiesPanel.setBorder(BorderFactory.createTitledBorder("Node capabilities"));
        capabilitiesPanel.add(new JScrollPane(capabilityTable), BorderLayout.CENTER);
        JPanel capButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton addCapability = new JButton("Add capability");
        JButton removeCapability = new JButton("Remove capability");
        capButtons.add(addCapability);
        capButtons.add(removeCapability);
        capabilitiesPanel.add(capButtons, BorderLayout.SOUTH);

        JPanel argsPanel = new JPanel(new BorderLayout(4, 4));
        argsPanel.setBorder(argumentsBorder);
        argsPanel.add(new JScrollPane(argumentTable), BorderLayout.CENTER);
        JPanel argButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton addArgument = new JButton("Add argument");
        JButton removeArgument = new JButton("Remove argument");
        argButtons.add(addArgument);
        argButtons.add(removeArgument);
        argsPanel.add(argButtons, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, capabilitiesPanel, argsPanel);
        split.setResizeWeight(0.60);
        split.setDividerLocation(310);
        root.add(split, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton load = new JButton("Load...");
        JButton save = new JButton("Save...");
        JButton generate = new JButton("Generate Canonical Bindings...");
        actions.add(load);
        actions.add(save);
        actions.add(generate);
        root.add(actions, BorderLayout.SOUTH);

        setContentPane(root);

        capabilityTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        capabilityTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int row = capabilityTable.getSelectedRow();
                Capability selected = row >= 0 ? capabilities.get(row) : null;
                argumentModel.setCapability(selected);
                updateArgumentsTitle(selected);
            }
        });

        addCapability.addActionListener(e -> {
            Capability c = new Capability();
            c.node = nextNodeName();
            capabilities.add(c);
            capabilityModel.fireTableDataChanged();
            int row = capabilities.size() - 1;
            capabilityTable.setRowSelectionInterval(row, row);
        });

        removeCapability.addActionListener(e -> {
            int row = capabilityTable.getSelectedRow();
            if (row >= 0) {
                capabilities.remove(row);
                capabilityModel.fireTableDataChanged();
                argumentModel.setCapability(null);
                updateArgumentsTitle(null);
            }
        });

        addArgument.addActionListener(e -> {
            Capability c = argumentModel.capability;
            if (c == null) {
                JOptionPane.showMessageDialog(this, "Select a capability first.");
                return;
            }
            c.arguments.add(new Argument());
            argumentModel.fireTableDataChanged();
        });

        removeArgument.addActionListener(e -> {
            Capability c = argumentModel.capability;
            int row = argumentTable.getSelectedRow();
            if (c != null && row >= 0) {
                c.arguments.remove(row);
                argumentModel.fireTableDataChanged();
            }
        });

        save.addActionListener(e -> saveDefinition());
        load.addActionListener(e -> loadDefinition());
        generate.addActionListener(e -> generateBindings());
    }

    private void updateArgumentsTitle(Capability c) {
        if (c == null) {
            argumentsBorder.setTitle("Arguments - select a capability");
        } else {
            String node = blank(c.node) ? "<node>" : c.node;
            String service = blank(c.service) ? "<service>" : c.service;
            String operation = blank(c.operation) ? "<operation>" : c.operation;
            argumentsBorder.setTitle("Arguments for " + node + " / " + service + " / " + operation);
        }
        repaint();
    }

    private String nextNodeName() {
        Set<String> used = new HashSet<>();
        for (Capability c : capabilities) used.add(c.node);
        int n = 1;
        while (used.contains("P" + n)) n++;
        return "P" + n;
    }

    private void saveDefinition() {
        JFileChooser chooser = createRememberingChooser(PREF_DEFINITION_DIR, null);
        chooser.setDialogTitle("Save infrastructure definition");
        chooser.setSelectedFile(currentFile != null ? currentFile : new File(chooser.getCurrentDirectory(), "InfrastructureDefinition.json"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File file = chooser.getSelectedFile();
        if (!file.getName().toLowerCase(Locale.ROOT).endsWith(".json")) {
            file = new File(file.getParentFile(), file.getName() + ".json");
        }
        try {
            Files.write(file.toPath(), toJson().getBytes(StandardCharsets.UTF_8));
            currentFile = file;
            rememberDirectory(PREF_DEFINITION_DIR, file.getParentFile());
            JOptionPane.showMessageDialog(this, "Saved infrastructure definition to:\n" + file.getAbsolutePath());
        } catch (IOException ex) {
            showError("Could not save definition", ex);
        }
    }

    private void loadDefinition() {
        JFileChooser chooser = createRememberingChooser(PREF_DEFINITION_DIR, null);
        chooser.setDialogTitle("Load infrastructure definition");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            File file = chooser.getSelectedFile();
            String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            parseJson(json);
            currentFile = file;
            rememberDirectory(PREF_DEFINITION_DIR, file.getParentFile());
            JOptionPane.showMessageDialog(this, "Loaded infrastructure definition from:\n" + file.getAbsolutePath());
        } catch (Exception ex) {
            showError("Could not load definition", ex);
        }
    }

    private String toJson() {
        StringBuilder b = new StringBuilder();
        b.append("{\n  \"definitionType\": \"Infrastructure\",\n  \"capabilities\": [\n");
        for (int i = 0; i < capabilities.size(); i++) {
            Capability c = capabilities.get(i);
            b.append("    {\n");
            field(b, "node", c.node, true);
            field(b, "service", c.service, true);
            field(b, "operation", c.operation, true);
            field(b, "returnAttribute", c.returnAttribute, true);
            b.append("      \"arguments\": [");
            for (int a = 0; a < c.arguments.size(); a++) {
                Argument arg = c.arguments.get(a);
                if (a > 0) b.append(",");
                b.append("\n        {\"name\": \"").append(escape(arg.name))
                 .append("\", \"type\": \"").append(escape(arg.type))
                 .append("\", \"value\": \"").append(escape(arg.value))
                 .append("\", \"required\": ").append(arg.required).append("}");
            }
            if (!c.arguments.isEmpty()) b.append("\n      ");
            b.append("]\n    }");
            if (i < capabilities.size() - 1) b.append(",");
            b.append("\n");
        }
        b.append("  ]\n}\n");
        return b.toString();
    }

    private void field(StringBuilder b, String name, String value, boolean comma) {
        b.append("      \"").append(name).append("\": \"").append(escape(value)).append("\"");
        if (comma) b.append(",");
        b.append("\n");
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /*
     * Loader is deliberately restricted to files produced by this editor.
     * It avoids introducing another JSON dependency into the existing editor project.
     */
    private void parseJson(String json) {
        capabilities.clear();
        String body = arrayBody(json, "capabilities");
        for (String block : objectBlocks(body)) {
            Capability c = new Capability();
            c.node = stringValue(block, "node");
            c.service = stringValue(block, "service");
            c.operation = stringValue(block, "operation");
            c.returnAttribute = stringValue(block, "returnAttribute");
            String args = arrayBody(block, "arguments");
            for (String argBlock : objectBlocks(args)) {
                Argument a = new Argument();
                a.name = stringValue(argBlock, "name");
                a.type = stringValue(argBlock, "type");
                a.value = stringValue(argBlock, "value");
                a.required = booleanValue(argBlock, "required");
                c.arguments.add(a);
            }
            capabilities.add(c);
        }
        capabilityModel.fireTableDataChanged();
        argumentModel.setCapability(null);
        if (!capabilities.isEmpty()) capabilityTable.setRowSelectionInterval(0, 0);
    }

    private String arrayBody(String text, String key) {
        int keyPos = text.indexOf("\"" + key + "\"");
        if (keyPos < 0) return "";
        int start = text.indexOf('[', keyPos);
        if (start < 0) return "";
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '"' && (i == 0 || text.charAt(i - 1) != '\\')) inString = !inString;
            if (inString) continue;
            if (ch == '[') depth++;
            else if (ch == ']' && --depth == 0) return text.substring(start + 1, i);
        }
        return "";
    }

    private List<String> objectBlocks(String text) {
        List<String> blocks = new ArrayList<>();
        int depth = 0, start = -1;
        boolean inString = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '"' && (i == 0 || text.charAt(i - 1) != '\\')) inString = !inString;
            if (inString) continue;
            if (ch == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (ch == '}' && depth > 0) {
                depth--;
                if (depth == 0 && start >= 0) blocks.add(text.substring(start, i + 1));
            }
        }
        return blocks;
    }

    private String stringValue(String block, String key) {
        String needle = "\"" + key + "\"";
        int p = block.indexOf(needle);
        if (p < 0) return "";
        int colon = block.indexOf(':', p + needle.length());
        int q1 = block.indexOf('"', colon + 1);
        int q2 = q1 + 1;
        while (q2 > q1 && q2 < block.length()) {
            if (block.charAt(q2) == '"' && block.charAt(q2 - 1) != '\\') break;
            q2++;
        }
        if (q1 < 0 || q2 >= block.length()) return "";
        return block.substring(q1 + 1, q2).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private boolean booleanValue(String block, String key) {
        int p = block.indexOf("\"" + key + "\"");
        if (p < 0) return false;
        int colon = block.indexOf(':', p);
        return colon >= 0 && block.substring(colon + 1).trim().startsWith("true");
    }

    private void generateBindings() {
        List<String> errors = validateDefinition();
        if (!errors.isEmpty()) {
            JOptionPane.showMessageDialog(this, String.join("\n", errors),
                "Incomplete infrastructure definition", JOptionPane.ERROR_MESSAGE);
            return;
        }

        File defaultBindingsDir = rememberedDirectory(PREF_BINDINGS_DIR);
        if (defaultBindingsDir == null) {
            defaultBindingsDir = findRepositoryBindingsDirectory();
        }

        JFileChooser chooser = createRememberingChooser(PREF_BINDINGS_DIR, defaultBindingsDir);
        chooser.setDialogTitle("Select ServiceAttributeBindings directory");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (defaultBindingsDir != null && defaultBindingsDir.isDirectory()) {
            chooser.setSelectedFile(defaultBindingsDir);
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        try {
            File base = chooser.getSelectedFile();
            rememberDirectory(PREF_BINDINGS_DIR, base);
            Map<String, List<Capability>> byService = new LinkedHashMap<>();
            for (Capability c : capabilities) {
                byService.computeIfAbsent(c.service, k -> new ArrayList<>()).add(c);
            }
            List<File> generatedFiles = new ArrayList<>();
            for (Map.Entry<String, List<Capability>> entry : byService.entrySet()) {
                generatedFiles.add(writeBindingFile(base, entry.getKey(), entry.getValue()));
            }
            StringBuilder message = new StringBuilder();
            message.append("Generated ").append(generatedFiles.size()).append(" canonical binding file(s):");
            for (File generated : generatedFiles) {
                message.append("\n\n").append(generated.getAbsolutePath());
            }
            JOptionPane.showMessageDialog(this, message.toString());
        } catch (IOException ex) {
            showError("Could not generate canonical bindings", ex);
        }
    }

    private List<String> validateDefinition() {
        List<String> errors = new ArrayList<>();
        if (capabilities.isEmpty()) errors.add("No capabilities have been defined.");
        for (int i = 0; i < capabilities.size(); i++) {
            Capability c = capabilities.get(i);
            String identity = (blank(c.node) ? "<node>" : c.node) + " / " +
                              (blank(c.service) ? "<service>" : c.service) + " / " +
                              (blank(c.operation) ? "<operation>" : c.operation) + ": ";
            if (blank(c.node)) errors.add(identity + "Node is required.");
            if (blank(c.service)) errors.add(identity + "Service is required.");
            if (blank(c.operation)) errors.add(identity + "Operation is required.");
            if (blank(c.returnAttribute)) errors.add(identity + "Return Attribute is required.");
            for (int a = 0; a < c.arguments.size(); a++) {
                if (blank(c.arguments.get(a).name)) {
                    errors.add(identity + "Argument " + (a + 1) + " needs a name.");
                }
            }
        }
        return errors;
    }

    private File writeBindingFile(File base, String service, List<Capability> caps) throws IOException {
        String folder = service.contains("_") ? service.substring(0, service.indexOf('_')) : service;
        File dir = new File(base, folder);
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        File out = new File(dir, service + "-CanonicalBindings.ruleml.xml");

        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(out), StandardCharsets.UTF_8))) {
            pw.println("<!-- Canonical Binding for " + xml(service) + " -->");
            pw.println("<!-- Auto-generated by Infrastructure Definition Editor - DO NOT EDIT -->");
            pw.println();
            pw.println("<Atom>");
            pw.println("\t<Rel>localDefined</Rel>");
            pw.println("\t<Ind>" + xml(service) + "</Ind>");
            pw.println("</Atom>");

            Set<String> emitted = new HashSet<>();
            for (Capability c : caps) {
                String key = c.operation + "\u0000" + c.returnAttribute;
                if (!emitted.add(key)) continue;
                for (Argument a : c.arguments) {
                    pw.println("<Atom>");
                    pw.println("\t<Rel>canonicalBinding</Rel>");
                    pw.println("\t<Ind>" + xml(c.operation) + "</Ind>");
                    pw.println("\t<Ind>" + xml(c.returnAttribute) + "</Ind>");
                    pw.println("\t<Ind>" + xml(a.name) + "</Ind>");
                    pw.println("</Atom>");
                }
            }
        }
        return out;
    }

    private JFileChooser createRememberingChooser(String preferenceKey, File preferredDirectory) {
        File directory = rememberedDirectory(preferenceKey);
        if (directory == null) {
            directory = preferredDirectory;
        }
        if (directory != null && directory.isDirectory()) {
            return new JFileChooser(directory);
        }
        return new JFileChooser();
    }

    private void rememberDirectory(String preferenceKey, File directory) {
        if (directory != null && directory.isDirectory()) {
            preferences.put(preferenceKey, directory.getAbsolutePath());
        }
    }

    private File rememberedDirectory(String preferenceKey) {
        String path = preferences.get(preferenceKey, null);
        if (path == null || path.trim().isEmpty()) return null;
        File directory = new File(path);
        return directory.isDirectory() ? directory : null;
    }

    private File findRepositoryBindingsDirectory() {
        File current = new File(System.getProperty("user.dir", ".")).getAbsoluteFile();
        File cursor = current;
        for (int depth = 0; cursor != null && depth < 8; depth++, cursor = cursor.getParentFile()) {
            File direct = new File(cursor, "btsn.common" + File.separator + "ServiceAttributeBindings");
            if (direct.isDirectory()) return direct;

            File sibling = new File(cursor, ".." + File.separator + "btsn.common" +
                File.separator + "ServiceAttributeBindings");
            try {
                sibling = sibling.getCanonicalFile();
            } catch (IOException ignored) {
                sibling = sibling.getAbsoluteFile();
            }
            if (sibling.isDirectory()) return sibling;
        }
        return null;
    }

    private String xml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private void showError(String message, Exception ex) {
        JOptionPane.showMessageDialog(this, message + ":\n" + ex.getMessage(),
            "Error", JOptionPane.ERROR_MESSAGE);
    }

    private final class CapabilityModel extends AbstractTableModel {
        private final String[] columns = {"Node", "Service", "Operation", "Return Attribute"};
        public int getRowCount() { return capabilities.size(); }
        public int getColumnCount() { return columns.length; }
        public String getColumnName(int c) { return columns[c]; }
        public boolean isCellEditable(int r, int c) { return true; }
        public Object getValueAt(int r, int c) {
            Capability x = capabilities.get(r);
            switch (c) {
                case 0: return x.node;
                case 1: return x.service;
                case 2: return x.operation;
                case 3: return x.returnAttribute;
                default: return "";
            }
        }
        public void setValueAt(Object v, int r, int c) {
            Capability x = capabilities.get(r);
            String s = v == null ? "" : String.valueOf(v).trim();
            if (c == 0) x.node = s;
            else if (c == 1) x.service = s;
            else if (c == 2) x.operation = s;
            else if (c == 3) x.returnAttribute = s;
            fireTableCellUpdated(r, c);
        }
    }

    private final class ArgumentModel extends AbstractTableModel {
        private final String[] columns = {"Name", "Type", "Value", "Required"};
        private Capability capability;
        void setCapability(Capability c) { capability = c; fireTableDataChanged(); }
        public int getRowCount() { return capability == null ? 0 : capability.arguments.size(); }
        public int getColumnCount() { return columns.length; }
        public String getColumnName(int c) { return columns[c]; }
        public Class<?> getColumnClass(int c) { return c == 3 ? Boolean.class : String.class; }
        public boolean isCellEditable(int r, int c) { return true; }
        public Object getValueAt(int r, int c) {
            Argument a = capability.arguments.get(r);
            switch (c) {
                case 0: return a.name;
                case 1: return a.type;
                case 2: return a.value;
                case 3: return a.required;
                default: return "";
            }
        }
        public void setValueAt(Object v, int r, int c) {
            Argument a = capability.arguments.get(r);
            if (c == 0) a.name = v == null ? "" : String.valueOf(v).trim();
            else if (c == 1) a.type = v == null ? "" : String.valueOf(v).trim();
            else if (c == 2) a.value = v == null ? "" : String.valueOf(v);
            else if (c == 3) a.required = Boolean.TRUE.equals(v);
            fireTableCellUpdated(r, c);
        }
    }

    private static final class Capability {
        String node = "";
        String service = "";
        String operation = "";
        String returnAttribute = "";
        final List<Argument> arguments = new ArrayList<>();
    }

    private static final class Argument {
        String name = "";
        String type = "String";
        String value = "String";
        boolean required;
    }
}
