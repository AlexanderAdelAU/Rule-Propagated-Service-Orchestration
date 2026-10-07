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
 * Edits reusable physical infrastructure or a separate service deployment.
 * Service deployments select fixed port slots from the physical definition. The process definition remains
 * business-only.
 */
public class InfrastructureDefinitionFrame extends JFrame {
    private final boolean deploymentEditor;
    private final List<NodeNetwork> nodes = new ArrayList<>();
    private final List<Capability> capabilities = new ArrayList<>();
    private final NodeNetworkModel nodeNetworkModel = new NodeNetworkModel();
    private final CapabilityModel capabilityModel = new CapabilityModel();
    private final ArgumentModel argumentModel = new ArgumentModel();
    private final JTable nodeNetworkTable = new JTable(nodeNetworkModel);
    private final JTable capabilityTable = new JTable(capabilityModel);
    private final JTable argumentTable = new JTable(argumentModel);
    private final TitledBorder argumentsBorder = BorderFactory.createTitledBorder("Arguments - select a capability");
    private static final String PREF_DEFINITION_DIR = "infrastructureDefinitionDir";
    private final Preferences preferences = Preferences.userNodeForPackage(InfrastructureDefinitionFrame.class);
    private final JLabel statusLabel = new JLabel(" ");
    private File currentFile;
    /** JSON of the definition as last opened/saved; compared with toJson() to detect unsaved changes. */
    private String savedSnapshot;

    public InfrastructureDefinitionFrame() {
        this(false);
    }

    public InfrastructureDefinitionFrame(boolean serviceDeployment) {
        super(serviceDeployment ? "Service Deployment" : "Infrastructure Definition");
        deploymentEditor = serviceDeployment;
        // Closing is routed through closeWindow() so unsaved changes can be offered for saving
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                closeWindow();
            }
        });
        setSize(1050, 720);
        setMinimumSize(new Dimension(900, 620));
        setLocationByPlatform(true);

        JPanel root = new JPanel(new BorderLayout(6, 6));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JTextArea explanation = new JTextArea(
            deploymentEditor ?
            "Assign business service operations to nodes and fixed port slots from the shared infrastructure. " +
            "Slot 0 is the primary port; slot 1 is the second port, where defined. Network settings are read-only here." :
            "Define reusable physical nodes, channels, addresses and fixed base ports. " +
            "All service deployments reuse these settings. Comma-separated ports define slots 0, 1 and so on.");
        explanation.setEditable(false);
        explanation.setLineWrap(true);
        explanation.setWrapStyleWord(true);
        explanation.setOpaque(false);
        root.add(explanation, BorderLayout.NORTH);

        JPanel nodePanel = new JPanel(new BorderLayout(4, 4));
        nodePanel.setBorder(BorderFactory.createTitledBorder("Physical node network"));
        nodePanel.add(new JScrollPane(nodeNetworkTable), BorderLayout.CENTER);
        JPanel nodeButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton addNode = new JButton("Add node");
        JButton removeNode = new JButton("Remove node");
        nodeButtons.add(addNode);
        nodeButtons.add(removeNode);
        if (!deploymentEditor) {
            nodePanel.add(nodeButtons, BorderLayout.SOUTH);
        } else {
            JButton chooseInfrastructure = new JButton("Choose infrastructure...");
            chooseInfrastructure.addActionListener(e -> chooseInfrastructure());
            JPanel selection = new JPanel(new FlowLayout(FlowLayout.LEFT));
            selection.add(chooseInfrastructure);
            nodePanel.add(selection, BorderLayout.SOUTH);
        }

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

        JSplitPane lowerSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, capabilitiesPanel, argsPanel);
        lowerSplit.setResizeWeight(0.58);
        lowerSplit.setDividerLocation(255);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, nodePanel, lowerSplit);
        split.setResizeWeight(0.28);
        split.setDividerLocation(145);
        root.add(deploymentEditor ? split : nodePanel, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton save = new JButton("Save...");
        JButton generate = new JButton("Generate Bindings");
        JButton close = new JButton("Close");
        actions.add(save);
        if (deploymentEditor) actions.add(generate);
        actions.add(close);

        JPanel footer = new JPanel(new BorderLayout(6, 0));
        statusLabel.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
        footer.add(statusLabel, BorderLayout.CENTER);
        footer.add(actions, BorderLayout.EAST);
        root.add(footer, BorderLayout.SOUTH);

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

        addNode.addActionListener(e -> {
            NodeNetwork n = createNextNode();
            nodes.add(n);
            nodeNetworkModel.fireTableDataChanged();
            int row = nodes.size() - 1;
            nodeNetworkTable.setRowSelectionInterval(row, row);
        });

        removeNode.addActionListener(e -> {
            int row = nodeNetworkTable.getSelectedRow();
            if (row < 0) return;
            NodeNetwork n = nodes.get(row);
            for (Capability cap : capabilities) {
                if (n.node.equals(cap.node)) {
                    JOptionPane.showMessageDialog(this,
                        "Remove or move capabilities assigned to " + n.node + " first.");
                    return;
                }
            }
            nodes.remove(row);
            nodeNetworkModel.fireTableDataChanged();
        });

        addCapability.addActionListener(e -> {
            if (nodes.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Define a physical node first.");
                return;
            }
            Capability c = new Capability();
            int selectedNodeRow = nodeNetworkTable.getSelectedRow();
            NodeNetwork node = selectedNodeRow >= 0 ? nodes.get(selectedNodeRow) : nodes.get(0);
            c.node = node.node;
            c.portSlot = allocateSlot(node);
            if (c.portSlot < 0) {
                JOptionPane.showMessageDialog(this,
                    "No unused fixed port slots remain for " + node.node + ".");
                return;
            }
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
        generate.addActionListener(e -> generateBindings());
        close.addActionListener(e -> closeWindow());

        // Any table change may alter the definition - refresh the "*" marker in the title
        javax.swing.event.TableModelListener changeListener = e -> updateTitle();
        nodeNetworkModel.addTableModelListener(changeListener);
        capabilityModel.addTableModelListener(changeListener);
        argumentModel.addTableModelListener(changeListener);

        if (deploymentEditor) {
            File folder = findRepositoryInfrastructureDirectory();
            File physical = folder == null ? null : new File(folder, "SingleHost.json");
            if (physical != null && physical.isFile()) {
                try {
                    loadPhysicalNodes(new String(Files.readAllBytes(physical.toPath()), StandardCharsets.UTF_8));
                    nodeNetworkModel.fireTableDataChanged();
                    nodeNetworkTable.setRowSelectionInterval(0, 0);
                }
                catch (Exception ex) { setStatus("Could not load SingleHost.json", ex.getMessage()); }
            }
        }
        markSaved();
    }

    // ==================== Unsaved-change tracking / Close ====================

    private void markSaved() {
        savedSnapshot = toJson();
        updateTitle();
    }

    private boolean isDirty() {
        return !toJson().equals(savedSnapshot);
    }

    private void updateTitle() {
        String title = definitionTitle();
        if (currentFile != null) title += " - " + currentFile.getName();
        if (isDirty()) title += " *";
        setTitle(title);
    }

    /** Commit any cell still being edited so it counts as a change. */
    private void stopTableEditing() {
        for (JTable table : new JTable[] {nodeNetworkTable, capabilityTable, argumentTable}) {
            if (table.isEditing() && !table.getCellEditor().stopCellEditing()) {
                table.getCellEditor().cancelCellEditing();
            }
        }
    }

    /**
     * Offer to save unsaved changes before this window goes away.
     * @return true if OK to close, false if the user cancelled (or the save failed)
     */
    public boolean confirmClose() {
        stopTableEditing();
        if (!isDirty()) return true;

        toFront();
        String name = currentFile != null ? currentFile.getName() : "this " + definitionTitle();
        int result = JOptionPane.showConfirmDialog(this,
            "You have unsaved changes to " + name + ". Do you want to save before closing?",
            "Unsaved Changes",
            JOptionPane.YES_NO_CANCEL_OPTION,
            JOptionPane.WARNING_MESSAGE);
        if (result == JOptionPane.YES_OPTION) return saveDefinition();
        return result == JOptionPane.NO_OPTION;
    }

    private void closeWindow() {
        if (confirmClose()) dispose();
    }

    /**
     * Ask every open infrastructure/service deployment window to confirm closing.
     * Used by ProcessEditor before the application exits.
     * @return false if the user cancelled for any window
     */
    public static boolean confirmCloseAll() {
        for (Frame f : Frame.getFrames()) {
            if (f instanceof InfrastructureDefinitionFrame && f.isDisplayable()) {
                if (!((InfrastructureDefinitionFrame) f).confirmClose()) return false;
            }
        }
        return true;
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

    private NodeNetwork createNextNode() {
        Set<String> used = new HashSet<>();
        for (NodeNetwork n : nodes) used.add(n.node);
        int number = 1;
        while (used.contains("P" + number)) number++;

        NodeNetwork node = new NodeNetwork();
        node.node = "P" + number;

        if (!nodes.isEmpty()) {
            node.channel = nodes.get(0).channel;
            node.address = nodes.get(0).address;
        } else {
            node.channel = "ip0";
        }
        node.basePorts.add(4000 + number);
        return node;
    }

    private int allocateSlot(NodeNetwork node) {
        for (int slot = 0; slot < node.basePorts.size(); slot++) {
            boolean used = false;
            for (Capability c : capabilities) if (node.node.equals(c.node) && c.portSlot == slot) used = true;
            if (!used) return slot;
        }
        return -1;
    }

    private String definitionTitle() {
        return deploymentEditor ? "Service Deployment" : "Infrastructure Definition";
    }

    private String preferenceKey() {
        return deploymentEditor ? "serviceDeploymentDir" : PREF_DEFINITION_DIR;
    }

    private File definitionDirectory() {
        File bindings = findRepositoryBindingsDirectory();
        return bindings == null ? null : new File(bindings.getParentFile(),
            deploymentEditor ? "ServiceDeploymentFolder" : "InfrastructureDefinitionFolder");
    }

    private void chooseInfrastructure() {
        JFileChooser chooser = createRememberingChooser(PREF_DEFINITION_DIR, findRepositoryInfrastructureDirectory());
        chooser.setDialogTitle("Choose physical infrastructure for service placement");
        chooser.setFileFilter(createJsonFilter());
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            File file = chooser.getSelectedFile();
            loadPhysicalNodes(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            rememberDirectory(PREF_DEFINITION_DIR, file.getParentFile());
            nodeNetworkModel.fireTableDataChanged();
            setStatus("Infrastructure preview: " + file.getAbsolutePath(), "Network settings belong to this physical definition.");
        } catch (Exception ex) { showError("Could not load infrastructure", ex); }
    }

    private NodeNetwork findNode(String nodeName) {
        for (NodeNetwork n : nodes) {
            if (n.node.equals(nodeName)) return n;
        }
        return null;
    }

    private boolean sameHost(NodeNetwork a, NodeNetwork b) {
        if (a == null || b == null) return false;
        if (!blank(a.address) && !blank(b.address)) return a.address.equals(b.address);
        return a.channel.equals(b.channel);
    }

    /** @return true if the definition was written to disk */
    private boolean saveDefinition() {
        stopTableEditing();
        List<String> errors = validateDefinition();
        if (!errors.isEmpty()) {
            JOptionPane.showMessageDialog(this, String.join("\n", errors),
                "Invalid " + definitionTitle(), JOptionPane.ERROR_MESSAGE);
            return false;
        }

        JFileChooser chooser = createRememberingChooser(preferenceKey(), definitionDirectory());
        chooser.setDialogTitle("Save " + definitionTitle());
        chooser.setFileFilter(createJsonFilter());
        chooser.setSelectedFile(currentFile != null ? currentFile : new File(chooser.getCurrentDirectory(), deploymentEditor ? "ServiceDeployment.json" : "SingleHost.json"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return false;
        File file = chooser.getSelectedFile();
        if (!file.getName().toLowerCase(Locale.ROOT).endsWith(".json")) {
            file = new File(file.getParentFile(), file.getName() + ".json");
        }
        try {
            Files.write(file.toPath(), toJson().getBytes(StandardCharsets.UTF_8));
            currentFile = file;
            markSaved();
            rememberDirectory(preferenceKey(), file.getParentFile());
            setStatus("Definition saved: " + file.getAbsolutePath(), file.getAbsolutePath());
            return true;
        } catch (IOException ex) {
            showError("Could not save definition", ex);
            return false;
        }
    }

    // ==================== Open (shared with ProcessEditor File > Open) ====================

    /**
     * True if the JSON was written by this editor (carries "definitionType": "Infrastructure").
     * Process definitions never carry this marker, so it cleanly separates the two file types.
     */
    public static boolean isInfrastructureDefinition(String json) {
        return json != null &&
            json.matches("(?s).*\"definitionType\"\\s*:\\s*\"Infrastructure\".*");
    }

    /**
     * File > Open > Infrastructure Definition: choose a file, and only show a
     * new window if a valid infrastructure definition was actually opened.
     */
    public static void openInNewWindow(Component parent) {
        InfrastructureDefinitionFrame frame = new InfrastructureDefinitionFrame();
        if (frame.promptAndOpen(parent)) {
            frame.setVisible(true);
        } else {
            frame.dispose();
        }
    }

    public static boolean isServiceDeploymentDefinition(String json) {
        return json != null && json.matches("(?s).*\"definitionType\"\\s*:\\s*\"ServiceDeployment\".*");
    }

    public static void openServiceDeploymentInNewWindow(Component parent) {
        InfrastructureDefinitionFrame frame = new InfrastructureDefinitionFrame(true);
        if (frame.promptAndOpen(parent)) frame.setVisible(true);
        else frame.dispose();
    }

    /** Open a known file in a new window (used when ProcessEditor detects an infrastructure file). */
    public static void openFileInNewWindow(Component parent, File file) {
        boolean deployment = false;
        try { deployment = isServiceDeploymentDefinition(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)); }
        catch (IOException ex) { JOptionPane.showMessageDialog(parent, ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE); return; }
        InfrastructureDefinitionFrame frame = new InfrastructureDefinitionFrame(deployment);
        if (frame.openFile(parent, file)) {
            frame.setVisible(true);
        } else {
            frame.dispose();
        }
    }

    /** Show the open dialog and load the chosen file. Returns true if a definition was loaded. */
    private boolean promptAndOpen(Component dialogParent) {
        JFileChooser chooser = createRememberingChooser(preferenceKey(), definitionDirectory());
        chooser.setDialogTitle("Open " + definitionTitle());
        chooser.setFileFilter(createJsonFilter());
        if (chooser.showOpenDialog(dialogParent) != JFileChooser.APPROVE_OPTION) return false;
        return openFile(dialogParent, chooser.getSelectedFile());
    }

    private boolean openFile(Component dialogParent, File file) {
        try {
            String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            if (deploymentEditor ? !isServiceDeploymentDefinition(json) : !isInfrastructureDefinition(json)) {
                JOptionPane.showMessageDialog(dialogParent,
                    file.getName() + " is not a " + definitionTitle() + ".\n" +
                    "Use File > Open > Process Definition for process files.",
                    "Wrong Definition Type", JOptionPane.WARNING_MESSAGE);
                return false;
            }
            parseJson(json);
            currentFile = file;
            markSaved();
            rememberDirectory(preferenceKey(), file.getParentFile());
            setStatus("Definition opened: " + file.getAbsolutePath(), file.getAbsolutePath());
            return true;
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(dialogParent, "Could not open definition:\n" + ex.getMessage(),
                "Error", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    private static javax.swing.filechooser.FileNameExtensionFilter createJsonFilter() {
        return new javax.swing.filechooser.FileNameExtensionFilter("JSON files (*.json)", "json");
    }

    private String toJson() {
        StringBuilder b = new StringBuilder();
        if (!deploymentEditor) {
            b.append("{\n  \"definitionType\": \"Infrastructure\",\n  \"nodes\": [\n");
            for (int i = 0; i < nodes.size(); i++) {
                NodeNetwork n = nodes.get(i);
                b.append("    {\n");
                field(b, "node", n.node, true);
                field(b, "channel", n.channel, true);
                field(b, "address", n.address, true);
                b.append("      \"basePorts\": ").append(n.basePorts).append("\n    }");
                if (i < nodes.size() - 1) b.append(",");
                b.append("\n");
            }
            return b.append("  ]\n}\n").toString();
        }
        b.append("{\n  \"definitionType\": \"ServiceDeployment\",\n  \"capabilities\": [\n");
        for (int i = 0; i < capabilities.size(); i++) {
            Capability c = capabilities.get(i);
            b.append("    {\n");
            field(b, "node", c.node, true);
            field(b, "service", c.service, true);
            field(b, "operation", c.operation, true);
            field(b, "returnAttribute", c.returnAttribute, true);
            b.append("      \"portSlot\": ").append(c.portSlot).append(",\n");
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
        if (!deploymentEditor) {
            loadPhysicalNodes(json);
            nodeNetworkModel.fireTableDataChanged();
            return;
        }
        String body = arrayBody(json, "capabilities");
        for (String block : objectBlocks(body)) {
            Capability c = new Capability();
            c.node = stringValue(block, "node");
            c.service = stringValue(block, "service");
            c.operation = stringValue(block, "operation");
            c.returnAttribute = stringValue(block, "returnAttribute");
            c.portSlot = intValue(block, "portSlot");
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
        nodeNetworkModel.fireTableDataChanged();
        capabilityModel.fireTableDataChanged();
        argumentModel.setCapability(null);
        if (!nodes.isEmpty()) nodeNetworkTable.setRowSelectionInterval(0, 0);
        if (!capabilities.isEmpty()) capabilityTable.setRowSelectionInterval(0, 0);
    }

    private void loadPhysicalNodes(String json) {
        if (!isInfrastructureDefinition(json) || json.contains("\"capabilities\""))
            throw new IllegalArgumentException("Choose a physical Infrastructure definition without service capabilities.");
        List<NodeNetwork> loaded = new ArrayList<>();
        for (String block : objectBlocks(arrayBody(json, "nodes"))) {
            NodeNetwork n = new NodeNetwork();
            n.node = stringValue(block, "node");
            n.channel = stringValue(block, "channel");
            n.address = stringValue(block, "address");
            n.basePorts.addAll(parsePorts(arrayBody(block, "basePorts")));
            loaded.add(n);
        }
        if (loaded.isEmpty()) throw new IllegalArgumentException("No physical nodes found.");
        nodes.clear();
        nodes.addAll(loaded);
    }

    private List<Integer> parsePorts(String value) {
        List<Integer> ports = new ArrayList<>();
        if (!value.trim().isEmpty()) for (String port : value.split(",")) ports.add(Integer.parseInt(port.trim()));
        return ports;
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

    private int intValue(String block, String key) {
        int p = block.indexOf("\"" + key + "\"");
        if (p < 0) return 0;
        int colon = block.indexOf(':', p);
        if (colon < 0) return 0;
        int start = colon + 1;
        while (start < block.length() && Character.isWhitespace(block.charAt(start))) start++;
        int end = start;
        if (end < block.length() && block.charAt(end) == '-') end++;
        while (end < block.length() && Character.isDigit(block.charAt(end))) end++;
        if (end <= start) return 0;
        try {
            return Integer.parseInt(block.substring(start, end));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private void generateBindings() {
        List<String> errors = validateDefinition();
        if (!errors.isEmpty()) {
            JOptionPane.showMessageDialog(this, String.join("\n", errors),
                "Incomplete service deployment", JOptionPane.ERROR_MESSAGE);
            return;
        }

        File base = findRepositoryBindingsDirectory();
        if (base == null || !base.isDirectory()) {
            JOptionPane.showMessageDialog(this,
                "Could not locate btsn.common/ServiceAttributeBindings automatically.",
                "Configuration output directory not found", JOptionPane.ERROR_MESSAGE);
            return;
        }

        try {
            Map<String, List<Capability>> byService = new LinkedHashMap<>();
            for (Capability cap : capabilities) {
                byService.computeIfAbsent(cap.service, k -> new ArrayList<>()).add(cap);
            }

            List<File> generatedFiles = new ArrayList<>();
            for (Map.Entry<String, List<Capability>> entry : byService.entrySet()) {
                generatedFiles.add(writeBindingFile(base, entry.getKey(), entry.getValue()));
            }

            StringBuilder details = new StringBuilder();
            details.append("Canonical bindings:");
            for (File generated : generatedFiles) {
                details.append("\n").append(generated.getAbsolutePath());
            }
            details.append("\nRuntime preparation generates deployment rules from the selected physical infrastructure and service deployment.");

            setStatus("Configuration generated automatically under " +
                base.getParentFile().getAbsolutePath(), details.toString());
        } catch (IOException ex) {
            showError("Could not generate service bindings", ex);
        }
    }
    private List<String> validateDefinition() {
        List<String> errors = new ArrayList<>();
        if (nodes.isEmpty()) errors.add("No physical nodes have been defined.");
        if (deploymentEditor && capabilities.isEmpty()) errors.add("No capabilities have been defined.");

        Set<String> nodeNames = new HashSet<>();
        Map<String, String> channelAddresses = new HashMap<>();
        for (NodeNetwork n : nodes) {
            String identity = blank(n.node) ? "<node>" : n.node;
            if (blank(n.node)) errors.add(identity + ": Node is required.");
            else if (!nodeNames.add(n.node)) errors.add(identity + ": Node is duplicated.");
            if (blank(n.channel)) errors.add(identity + ": Channel is required.");
            if (blank(n.address)) errors.add(identity + ": Address is required.");
            if (n.basePorts.isEmpty()) errors.add(identity + ": At least one fixed base port is required.");
            for (int port : n.basePorts) {
                if (port <= 0 || port > 45535) errors.add(identity + ": Base ports must be between 1 and 45535.");
            }
            if (!blank(n.channel) && !blank(n.address)) {
                String existing = channelAddresses.put(n.channel, n.address);
                if (existing != null && !existing.equals(n.address)) {
                    errors.add(identity + ": Channel " + n.channel + " is already mapped to " + existing + ".");
                }
            }
        }

        Set<String> physicalSockets = new HashSet<>();
        for (NodeNetwork n : nodes) for (int port : n.basePorts) {
            if (!physicalSockets.add(n.address + ":" + n.channel + ":" + port))
                errors.add("Physical port " + port + " is duplicated on " + n.address + " / " + n.channel + ".");
        }
        Set<String> allocatedSockets = new HashSet<>();
        Set<String> runtimeOperations = new HashSet<>();
        for (int i = 0; i < capabilities.size(); i++) {
            Capability cap = capabilities.get(i);
            String identity = (blank(cap.node) ? "<node>" : cap.node) + " / " +
                              (blank(cap.service) ? "<service>" : cap.service) + " / " +
                              (blank(cap.operation) ? "<operation>" : cap.operation) + ": ";
            if (blank(cap.node)) errors.add(identity + "Node is required.");
            if (blank(cap.service)) errors.add(identity + "Service is required.");
            if (blank(cap.operation)) errors.add(identity + "Operation is required.");
            if (blank(cap.returnAttribute)) errors.add(identity + "Return Attribute is required.");

            NodeNetwork node = findNode(cap.node);
            if (node == null && !blank(cap.node)) {
                errors.add(identity + "Node is not defined in Physical node network.");
            } else if (node != null) {
                if (cap.portSlot < 0 || cap.portSlot >= node.basePorts.size()) {
                    errors.add(identity + "Port slot is not defined on " + node.node + ".");
                } else {
                    String socketKey = node.address + ":" + node.channel + ":" + node.basePorts.get(cap.portSlot);
                    if (!allocatedSockets.add(socketKey)) errors.add(identity + "Fixed port slot is already selected by another operation.");
                }
                String runtimeKey = runtimeServiceForNode(cap.node) + "\u0000" + cap.operation;
                if (!runtimeOperations.add(runtimeKey)) {
                    errors.add(identity + "Runtime operation is duplicated.");
                }
            }

            for (int a = 0; a < cap.arguments.size(); a++) {
                if (blank(cap.arguments.get(a).name)) {
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

    private String runtimeServiceForNode(String node) {
        if (node != null && node.matches("P\\d+")) return node + "_Place";
        return node == null ? "" : node;
    }

    private JFileChooser createRememberingChooser(String preferenceKey, File preferredDirectory) {
        File directory = rememberedDirectory(preferenceKey);
        if (directory == null || (preferredDirectory != null &&
                "ProcessDefinitionFolder".equals(directory.getName()) &&
                directory.getParentFile().equals(preferredDirectory.getParentFile()))) {
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

    private File findRepositoryInfrastructureDirectory() {
        File bindings = findRepositoryBindingsDirectory();
        return bindings == null ? null : new File(bindings.getParentFile(), "InfrastructureDefinitionFolder");
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

    private void setStatus(String text, String details) {
        statusLabel.setText(text);
        statusLabel.setToolTipText("<html>" + xml(details).replace("\n", "<br>") + "</html>");
    }

    private void showError(String message, Exception ex) {
        JOptionPane.showMessageDialog(this, message + ":\n" + ex.getMessage(),
            "Error", JOptionPane.ERROR_MESSAGE);
    }

    private final class NodeNetworkModel extends AbstractTableModel {
        private final String[] columns = {"Node", "Channel", "Address", "Fixed Base Ports"};
        public int getRowCount() { return nodes.size(); }
        public int getColumnCount() { return columns.length; }
        public String getColumnName(int c) { return columns[c]; }
        public Class<?> getColumnClass(int c) { return String.class; }
        public boolean isCellEditable(int r, int c) { return !deploymentEditor; }
        public Object getValueAt(int r, int c) {
            NodeNetwork n = nodes.get(r);
            switch (c) {
                case 0: return n.node;
                case 1: return n.channel;
                case 2: return n.address;
                case 3: return n.basePorts.toString().replace("[", "").replace("]", "");
                default: return "";
            }
        }
        public void setValueAt(Object v, int r, int c) {
            NodeNetwork n = nodes.get(r);
            if (c == 0) {
                String oldNode = n.node;
                String newNode = v == null ? "" : String.valueOf(v).trim();
                n.node = newNode;
                for (Capability cap : capabilities) {
                    if (oldNode.equals(cap.node)) cap.node = newNode;
                }
                capabilityModel.fireTableDataChanged();
            } else if (c == 1) {
                n.channel = v == null ? "" : String.valueOf(v).trim();
            } else if (c == 2) {
                String address = v == null ? "" : String.valueOf(v).trim();
                for (NodeNetwork other : nodes) if (other.channel.equals(n.channel)) other.address = address;
                fireTableDataChanged();
            } else if (c == 3) {
                try {
                    List<Integer> ports = parsePorts(String.valueOf(v));
                    n.basePorts.clear();
                    n.basePorts.addAll(ports);
                } catch (NumberFormatException ex) {
                    JOptionPane.showMessageDialog(InfrastructureDefinitionFrame.this, "Enter comma-separated integer ports.");
                }
            }
            fireTableCellUpdated(r, c);
        }
    }

    private final class CapabilityModel extends AbstractTableModel {
        private final String[] columns = {"Node", "Service", "Operation", "Return Attribute", "Port Slot"};
        public int getRowCount() { return capabilities.size(); }
        public int getColumnCount() { return columns.length; }
        public String getColumnName(int c) { return columns[c]; }
        public Class<?> getColumnClass(int c) { return c == 4 ? Integer.class : String.class; }
        public boolean isCellEditable(int r, int c) { return true; }
        public Object getValueAt(int r, int c) {
            Capability x = capabilities.get(r);
            switch (c) {
                case 0: return x.node;
                case 1: return x.service;
                case 2: return x.operation;
                case 3: return x.returnAttribute;
                case 4: return x.portSlot;
                default: return "";
            }
        }
        public void setValueAt(Object v, int r, int c) {
            Capability x = capabilities.get(r);
            String s = v == null ? "" : String.valueOf(v).trim();
            if (c == 0) {
                x.node = s;
                x.portSlot = -1;
                NodeNetwork n = findNode(s);
                if (n != null) x.portSlot = allocateSlot(n);
                fireTableRowsUpdated(r, r);
                return;
            } else if (c == 1) x.service = s;
            else if (c == 2) x.operation = s;
            else if (c == 3) x.returnAttribute = s;
            else if (c == 4) x.portSlot = parseTableInt(v);
            fireTableCellUpdated(r, c);
        }
    }

    private int parseTableInt(Object value) {
        if (value == null) return 0;
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return 0;
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

    private static final class NodeNetwork {
        String node = "";
        String channel = "";
        String address = "";
        final List<Integer> basePorts = new ArrayList<>();
    }

    private static final class Capability {
        String node = "";
        String service = "";
        String operation = "";
        String returnAttribute = "";
        int portSlot;
        final List<Argument> arguments = new ArrayList<>();
    }

    private static final class Argument {
        String name = "";
        String type = "String";
        String value = "String";
        boolean required;
    }
}