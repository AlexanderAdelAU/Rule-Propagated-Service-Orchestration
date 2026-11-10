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
    private final JTable capabilityTable = new JTable(capabilityModel) {
        @Override public javax.swing.table.TableCellEditor getCellEditor(int row, int column) {
            Capability cap = rows().get(convertRowIndexToModel(row));
            column = convertColumnIndexToModel(column);
            List<String> values = new ArrayList<>(); String current = String.valueOf(capabilityModel.getValueAt(convertRowIndexToModel(row), column));
            if (column == 0) { for (NodeNetwork node : nodes) values.add(node.node); return new DefaultCellEditor(hostNodeChoices(values, current, cap)); }
            else if (column == 1) values.addAll(serviceRegistry.services());
            else if (column == 2) values.addAll(serviceRegistry.operations(cap.service));
            else if (column == 6) { ServiceRegistry.Contract contract = serviceRegistry.contract(cap.service, cap.operation);
                if (contract != null && "boolean".equals(contract.resultType)) values.add(ServiceRegistry.BOOLEAN_TOKEN); else values.add(ServiceRegistry.REST); }
            else return super.getCellEditor(row, column);
            java.util.function.Function<String, String> describe = column == 1 ? serviceRegistry::describe
                : column == 2 ? (op -> serviceRegistry.describe(cap.service, op)) : null;
            return new DefaultCellEditor(ServiceRegistry.choices(values, current, describe));
        }
    };
    private final JTable argumentTable = new JTable(argumentModel);
    private JTextArea explanation;
    private JPanel root;
    private JSplitPane split;
    private JSplitPane lowerSplit;
    private JPanel capabilitiesPanel;
    private JPanel capButtons;
    private JButton addCapabilityButton;
    private JButton removeCapabilityButton;
    private final TitledBorder argumentsBorder = BorderFactory.createTitledBorder("Inputs - select a capability");
    private final JLabel inputsNote = new JLabel(" ");
    private JButton addInputButton;
    private JButton removeInputButton;
    private static final String PREF_DEFINITION_DIR = "infrastructureDefinitionDir";
    private final Preferences preferences = Preferences.userNodeForPackage(InfrastructureDefinitionFrame.class);
    private final JLabel statusLabel = new JLabel(" ");
    // The selected row's service endpoint and wait, in a full-width box below the table: a long address is hard to edit in a cell.
    private final JLabel endpointLabel = new JLabel("Service endpoint:");
    private final JTextField endpointField = new JTextField();
    private final JTextField timeoutField = new JTextField(6);
    private final JLabel endpointNote = new JLabel(" ");
    // The selected rest row's call mapping: how to call an API that was not written for RPSO. Empty: RPSO's own call.
    private final JTextArea callArea = new JTextArea(7, 60);
    private final JLabel callNote = new JLabel(" ");
    /** The selected row's last valid, non-empty mapping: what an invalid edit leaves in place. */
    private String lastValidCall = "";
    private final JButton callExample = new JButton("Start from an example");
    private final JButton tryCall = new JButton("Try a call...");
    /** True while the endpoint box is filled from the selected row or writes to it, so neither echoes the other. */
    private boolean endpointSync;
    private File currentFile;
    private String catalogueReference = "";
    private final ServiceRegistry serviceRegistry = new ServiceRegistry();
    /** JSON of the definition as last opened/saved; compared with toJson() to detect unsaved changes. */
    private String savedSnapshot;
    /** Process being deployed when opened with Deploy; null for a free-standing deployment window. */
    private Canvas processCanvas;
    private File suggestedFile;
    /** Rows that deploy a place of the process (one per place operation). */
    private final Map<Capability, ProcessElement> placeRows = new IdentityHashMap<>();
    private final Set<Capability> rowsAddedForProcess = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Runnable processListener = () -> SwingUtilities.invokeLater(this::refreshFromProcess);
    private boolean refreshing;

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

        root = new JPanel(new BorderLayout(6, 6));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        explanation = new JTextArea(
            deploymentEditor ?
            "Assign business service operations to nodes and fixed port slots from the shared infrastructure. " +
            "Slot 0 is the primary port; slot 1 is the second port, where defined. Network settings are read-only here. Instance identifies a deployment; gateway types remain in the process definition." :
            "Define the hosts (P1, P2, ...): each host's channel, address and fixed base ports. Places are deployed to hosts. " +
            "All service deployments reuse these settings. Comma-separated ports define slots 0, 1 and so on.");
        explanation.setEditable(false);
        explanation.setLineWrap(true);
        explanation.setWrapStyleWord(true);
        explanation.setOpaque(false);
        root.add(explanation, BorderLayout.NORTH);

        JPanel nodePanel = new JPanel(new BorderLayout(4, 4));
        nodePanel.setBorder(BorderFactory.createTitledBorder("Hosts"));
        nodePanel.add(new JScrollPane(nodeNetworkTable), BorderLayout.CENTER);
        JPanel nodeButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton addNode = new JButton("Add host");
        JButton removeNode = new JButton("Remove host");
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

        capabilitiesPanel = new JPanel(new BorderLayout(4, 4));
        capabilitiesPanel.setBorder(BorderFactory.createTitledBorder("Node capabilities"));
        capabilitiesPanel.add(new JScrollPane(capabilityTable), BorderLayout.CENTER);
        capButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton addCapability = new JButton("Add capability");
        JButton removeCapability = new JButton("Remove capability");
        addCapabilityButton = addCapability;
        removeCapabilityButton = removeCapability;
        capButtons.add(addCapability);
        capButtons.add(removeCapability);
        JButton reloadCatalogue = new JButton("Reload catalogue");
        reloadCatalogue.addActionListener(e -> { loadCatalogue(); capabilityTable.repaint(); });
        capButtons.add(reloadCatalogue);
        JButton chooseCatalogue = new JButton("Choose catalogue...");
        chooseCatalogue.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            File common = ServiceRegistry.findCommon(currentFile);
            if (common != null) chooser.setCurrentDirectory(new File(common, "BusinessServiceDefinitions"));
            if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
            try { serviceRegistry.loadCatalogue(chooser.getSelectedFile()); catalogueReference = serviceRegistry.catalogueReference(); capabilityTable.repaint(); }
            catch (Exception ex) { JOptionPane.showMessageDialog(this, ex.getMessage(), "Invalid catalogue", JOptionPane.ERROR_MESSAGE); }
        });
        capButtons.add(chooseCatalogue);
        JPanel capabilitiesSouth = new JPanel(new BorderLayout(4, 2));
        capabilitiesSouth.add(endpointPanel(), BorderLayout.NORTH);
        capabilitiesSouth.add(callPanel(), BorderLayout.CENTER);
        capabilitiesSouth.add(capButtons, BorderLayout.SOUTH);
        capabilitiesPanel.add(capabilitiesSouth, BorderLayout.SOUTH);

        JPanel argsPanel = new JPanel(new BorderLayout(4, 4));
        argsPanel.setBorder(argumentsBorder);
        argsPanel.add(new JScrollPane(argumentTable), BorderLayout.CENTER);
        JPanel argButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton addArgument = new JButton("Add input");
        JButton removeArgument = new JButton("Remove input");
        addInputButton = addArgument;
        removeInputButton = removeArgument;
        argButtons.add(addArgument);
        argButtons.add(removeArgument);
        JPanel argsSouth = new JPanel(new BorderLayout(4, 2));
        inputsNote.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
        argsSouth.add(inputsNote, BorderLayout.NORTH);
        argsSouth.add(argButtons, BorderLayout.SOUTH);
        argsPanel.add(argsSouth, BorderLayout.SOUTH);

        lowerSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, capabilitiesPanel, argsPanel);
        lowerSplit.setResizeWeight(0.58);
        lowerSplit.setDividerLocation(255);
        split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, nodePanel, lowerSplit);
        split.setResizeWeight(0.28);
        split.setDividerLocation(145);
        root.add(deploymentEditor ? split : nodePanel, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        // Launchers generate canonical bindings and network rules from the saved files, so saving is the only step.
        JButton save = new JButton(deploymentEditor ? "Save deployment..." : "Save...");
        JButton close = new JButton("Close");
        actions.add(save);
        actions.add(close);

        JPanel footer = new JPanel(new BorderLayout(6, 0));
        statusLabel.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
        footer.add(statusLabel, BorderLayout.CENTER);
        footer.add(actions, BorderLayout.EAST);
        root.add(footer, BorderLayout.SOUTH);

        setContentPane(root);

        capabilityTable.getTableHeader().setReorderingAllowed(false);
        capabilityTable.setDefaultRenderer(String.class, new javax.swing.table.DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus, int row, int column) {
                super.getTableCellRendererComponent(table, value, selected, focus, row, column);
                column = table.convertColumnIndexToModel(column);
                Capability cap = rows().get(table.convertRowIndexToModel(row));
                List<String> names = new ArrayList<>(); for (Argument argument : cap.arguments) names.add(argument.name);
                List<String> errors = serviceRegistry.validateEndpoint(cap.service, cap.operation, cap.returnAttribute, names, cap.adapter, cap.endpoint);
                String described = column == 1 ? serviceRegistry.describe(cap.service) : column == 2 ? serviceRegistry.describe(cap.service, cap.operation)
                    : column == 6 ? "Blank: the host calls the service's Java class. rest: the host calls the Endpoint over HTTP instead. boolean-token: a Boolean service as a token."
                    : column == 7 ? "For the rest adapter: the http(s) address the host POSTs each call to, e.g. http://127.0.0.1:8080/credit/assess. The service runs there; the place's host stays on its host node. Edit it in the Service endpoint box below the table."
                    : column == 0 && processCanvas != null ? "The node whose host runs this place: its queue, rules and routing. With the rest adapter the service itself runs at the Service endpoint."
                    : column == 9 ? "Where this place's host listens: the node's address and the port of the row's slot." : "";
                setToolTipText(!errors.isEmpty() ? String.join("; ", errors) : ServiceRegistry.tooltip(described));
                if (!selected) setForeground(errors.isEmpty() ? table.getForeground() : Color.RED);
                if ((column == 1 && !serviceRegistry.services().contains(cap.service)) || (column == 2 && !serviceRegistry.operations(cap.service).contains(cap.operation))) setText("Unresolved: " + value);
                return this;
            }
        });
        capabilityTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        capabilityTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int row = capabilityTable.getSelectedRow();
                Capability selected = row >= 0 ? rows().get(row) : null;
                argumentModel.setCapability(selected);
                updateArgumentsTitle(selected);
                showSelectedEndpoint();
            }
        });
        capabilityModel.addTableModelListener(e -> SwingUtilities.invokeLater(this::showSelectedEndpoint));
        // A cell being edited is kept when the endpoint box (or anything else) is clicked.
        capabilityTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        sizeCapabilityColumns();

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
                JOptionPane.showMessageDialog(this, "Define a host first.");
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
                capabilities.remove(rows().get(row));
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
            if (!aliasing(c)) { JOptionPane.showMessageDialog(this, "Catalogue inputs are fixed. Select the boolean-token adapter to define transport aliases."); return; }
            c.arguments.add(new Argument());
            argumentModel.fireTableDataChanged();
        });

        removeArgument.addActionListener(e -> {
            Capability c = argumentModel.capability;
            int row = argumentTable.getSelectedRow();
            if (c != null && row >= 0 && aliasing(c)) {
                c.arguments.remove(row);
                argumentModel.fireTableDataChanged();
            }
        });

        save.addActionListener(e -> saveDefinition());
        close.addActionListener(e -> closeWindow());

        // Any table change may alter the definition - refresh the "*" marker in the title
        javax.swing.event.TableModelListener changeListener = e -> updateTitle();
        nodeNetworkModel.addTableModelListener(changeListener);
        capabilityModel.addTableModelListener(changeListener);
        argumentModel.addTableModelListener(changeListener);

        if (deploymentEditor) {
            // Preview the machines chosen in the editor (Machines on the toolbar); the deployment itself names only nodes.
            File folder = findRepositoryInfrastructureDirectory();
            File physical = folder == null ? null : new File(folder, ServiceRegistry.selectedInfrastructure() + ".json");
            if (physical == null || !physical.isFile()) physical = folder == null ? null : new File(folder, ServiceRegistry.DEFAULT_INFRASTRUCTURE + ".json");
            if (physical != null && physical.isFile()) {
                try {
                    loadPhysicalNodes(new String(Files.readAllBytes(physical.toPath()), StandardCharsets.UTF_8));
                    nodeNetworkModel.fireTableDataChanged();
                    nodeNetworkTable.setRowSelectionInterval(0, 0);
                    setStatus("Machines: " + physical.getName() + " (preview)", "The deployment names nodes; the run's infrastructure gives them their machines.");
                }
                catch (Exception ex) { setStatus("Could not load " + physical.getName(), ex.getMessage()); }
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
            argumentsBorder.setTitle(processCanvas != null ? "Inputs - select a place" : "Inputs - select a capability");
        } else if (placeRows.containsKey(c)) {
            argumentsBorder.setTitle("Inputs of " + placeLabel(c) + " (" + c.service + "." + c.operation + ")");
        } else {
            String node = blank(c.node) ? "<node>" : c.node;
            String service = blank(c.service) ? "<service>" : c.service;
            String operation = blank(c.operation) ? "<operation>" : c.operation;
            argumentsBorder.setTitle("Inputs of " + node + " / " + service + " / " + operation);
        }
        // Only a boolean-token placement names its own inputs; any other takes them from the catalogue, unchanged.
        boolean own = c != null && aliasing(c);
        inputsNote.setText(c == null ? " "
            : own ? "boolean-token: the names the Boolean service's data arrives under. Add, remove or rename them here."
            : "Set by the catalogue: the service receives each input by name from the arriving token. Change them in the catalogue, not here.");
        if (addInputButton != null) { addInputButton.setEnabled(own); removeInputButton.setEnabled(own); }
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
        // The first free port from 4000 + number (P7 gets 4009 when P4 and P6 already use 4007 and 4008).
        Set<Integer> taken = new HashSet<>();
        for (NodeNetwork n : nodes) if (n.channel.equals(node.channel)) taken.addAll(n.basePorts);
        int port = 4000 + number;
        while (taken.contains(port) || reservedPort(port)) port++;
        node.basePorts.add(port);
        return node;
    }

    /** Ports hosts keep for their own administrative services: Pn's collector on 4100+n-1, its initialiser on 4300+n-1. */
    static boolean reservedPort(int port) {
        return (port >= 4100 && port <= 4198) || (port >= 4300 && port <= 4398);
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
            String machines = ServiceRegistry.useInfrastructureFile(file);
            setStatus(machines != null ? "Machines: " + machines + " - Runs on, Deploy to and Run on hosts now use it" : "Infrastructure preview: " + file.getAbsolutePath(),
                machines != null ? file.getAbsolutePath() : "Save it in InfrastructureDefinitionFolder for runs to use it.");
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
        File proposed = currentFile != null ? currentFile : suggestedFile;
        if (proposed != null && proposed.getParentFile() != null && proposed.getParentFile().isDirectory()) chooser.setCurrentDirectory(proposed.getParentFile());
        chooser.setSelectedFile(proposed != null ? proposed : new File(chooser.getCurrentDirectory(), deploymentEditor ? "ServiceDeployment.json" : "SingleHost.json"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return false;
        File file = chooser.getSelectedFile();
        if (!file.getName().toLowerCase(Locale.ROOT).endsWith(".json")) {
            file = new File(file.getParentFile(), file.getName() + ".json");
        }
        if (file.exists() && !file.equals(currentFile) && JOptionPane.showConfirmDialog(this,
                file.getName() + " already exists. Replace it?", "Replace " + definitionTitle(),
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return false;
        try {
            Files.write(file.toPath(), toJson().getBytes(StandardCharsets.UTF_8));
            // A process that moved to another catalogue is deployed again under the same name: its Build profile follows.
            if (deploymentEditor) ServiceRegistry.alignProfiles(file, catalogueReference);
            currentFile = file;
            if (deploymentEditor) loadCatalogue();
            markSaved();
            rememberDirectory(preferenceKey(), file.getParentFile());
            setStatus("Definition saved: " + file.getAbsolutePath(), file.getAbsolutePath());
            if (!deploymentEditor) useForEditor(file, "saved");
            if (processCanvas != null) linkProcess(file);
            return true;
        } catch (Exception ex) {
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

    /**
     * Deploy: a service deployment window beside the process editor, filled from the process.
     * Each place operation gets a row; the Place column shows which place a row deploys.
     */
    public static InfrastructureDefinitionFrame openForProcess(Window owner, Canvas canvas, File processFile) {
        InfrastructureDefinitionFrame frame = new InfrastructureDefinitionFrame(true);
        frame.attachProcess(canvas, processFile);
        frame.placeBeside(owner);
        frame.setVisible(true);
        return frame;
    }

    private void attachProcess(Canvas canvas, File processFile) {
        processCanvas = canvas;
        ServiceRegistry processRegistry = canvas.getServiceRegistry();
        if (processRegistry.hasDeployment()) {
            openFile(this, processRegistry.deploymentFile());
        } else {
            try { serviceRegistry.loadCatalogue(processRegistry.catalogueFile()); catalogueReference = serviceRegistry.catalogueReference(); }
            catch (Exception ex) { setStatus("Unresolved catalogue: " + ex.getMessage(), ex.getMessage()); }
            suggestedFile = suggestedDeploymentFile(processFile);
        }
        capabilityModel.fireTableStructureChanged();
        capabilityTable.moveColumn(8, 0);   // Place first
        capabilityTable.moveColumn(9, 2);   // Address beside Host node
        sizeCapabilityColumns();
        usePlaceFirstLayout(processFile);
        refreshFromProcess();
        // Start on the first place, so its endpoint and arguments show without a click.
        if (capabilityTable.getRowCount() > 0 && capabilityTable.getSelectedRow() < 0) capabilityTable.setRowSelectionInterval(0, 0);
        markSaved();
        // Rows filled from the process are new work until saved.
        if (rowsAddedForProcess.size() > 0 || processHasPendingNodes()) savedSnapshot = "";
        updateTitle();
        canvas.addDeploymentListener(processListener);
        String name = processFile == null ? "unsaved process" : processFile.getName();
        int others = capabilities.size() - rows().size();
        setStatus("Deploying " + name + ": choose a host node for each place, then Save."
                + (others > 0 ? " The deployment also holds " + others + " row" + (others == 1 ? "" : "s") + " for places not in this process; they are kept." : ""),
            "Rows come from the process diagram: add or remove places there." + (others > 0 ? "\n" + otherRowsSummary() : "")
                + "\nSaving writes the deployment and links the process to it.");
    }

    /**
     * Deploy from a process starts from its places: the network is not shown as a table to choose from,
     * rows cannot be added or removed here, and the node of each place is picked from a list that gives its address.
     */
    private void usePlaceFirstLayout(File processFile) {
        String name = processFile == null ? "this process" : processFile.getName().replaceFirst("(?i)\\.json$", "");
        explanation.setText("Each place of " + name + " is listed below; choose the host node it runs on. The host node runs the place's host: "
            + "its queue, rules and routing. With the rest adapter, the host calls the service at its Service endpoint, which may be anywhere. "
            + "Places come from the process diagram: add or remove them there.");
        capabilitiesPanel.setBorder(BorderFactory.createTitledBorder("Places in " + name));
        addCapabilityButton.setVisible(false);
        removeCapabilityButton.setVisible(false);
        JButton chooseInfrastructure = new JButton("Choose infrastructure...");
        chooseInfrastructure.addActionListener(e -> { chooseInfrastructure(); capabilityModel.fireTableDataChanged(); });
        capButtons.add(chooseInfrastructure, 0);
        root.remove(split);
        split.setBottomComponent(null);
        root.add(lowerSplit, BorderLayout.CENTER);
        lowerSplit.setDividerLocation(430);   // the places get the room; a place's inputs are a line or two
        updateArgumentsTitle(null);
        root.revalidate();
    }

    /** Rows shown in the table: when deploying a process, only the rows for its places. */
    private List<Capability> rows() {
        if (processCanvas == null) return capabilities;
        List<Capability> shown = new ArrayList<>();
        for (Capability c : capabilities) if (placeRows.containsKey(c)) shown.add(c);
        return shown;
    }

    private String otherRowsSummary() {
        List<String> lines = new ArrayList<>();
        for (Capability c : capabilities)
            if (!placeRows.containsKey(c)) lines.add(c.node + ": " + c.service + "." + c.operation + (blank(c.instance) ? "" : " (" + c.instance + ")"));
        return "Kept for other processes or earlier versions:\n" + String.join("\n", lines);
    }

    /** Address and port of a row's host: the node's address and the base port of the row's slot. */
    private String hostAddress(Capability c) {
        NodeNetwork n = findNode(c.node);
        if (n == null) return "";
        String port = c.portSlot >= 0 && c.portSlot < n.basePorts.size() ? ":" + n.basePorts.get(c.portSlot) : "";
        return n.address + port;
    }

    /** Host nodes with their addresses; a node another place of the process already runs on is marked. */
    private JComboBox<String> hostNodeChoices(List<String> values, String current, Capability row) {
        JComboBox<String> combo = new JComboBox<String>() {
            // The open list is as wide as its entries, not the column, so each node's address is readable.
            private boolean layingOut;
            @Override public void doLayout() { layingOut = true; try { super.doLayout(); } finally { layingOut = false; } }
            @Override public Dimension getSize() {
                Dimension size = super.getSize();
                if (!layingOut) size.width = Math.max(size.width, 320);
                return size;
            }
        };
        combo.addItem("");
        for (String value : values) combo.addItem(value);
        if (!blank(current) && !values.contains(current)) combo.addItem(current);
        combo.setSelectedItem(current == null ? "" : current);
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                String node = value == null ? "" : value.toString();
                NodeNetwork n = findNode(node);
                if (n == null) { setText(node.isEmpty() ? " " : "Unresolved: " + node); return this; }
                if (index < 0) { setText(node); return this; }   // the closed cell shows the node; the list gives its address
                StringBuilder text = new StringBuilder(node).append("  \u2014  ").append(n.address);
                if (!n.basePorts.isEmpty()) text.append(", ports ").append(n.basePorts.toString().replaceAll("[\\[\\]]", ""));
                List<String> users = new ArrayList<>();
                for (Map.Entry<Capability, ProcessElement> e : placeRows.entrySet())
                    if (e.getKey() != row && node.equals(e.getKey().node) && !users.contains(e.getValue().getLabel())) users.add(e.getValue().getLabel());
                if (!users.isEmpty()) { text.append("  (used by ").append(String.join(", ", users)).append(")"); if (!selected) setForeground(Color.GRAY); }
                setText(text.toString());
                return this;
            }
        });
        return combo;
    }

    private boolean processHasPendingNodes() {
        for (ProcessElement place : processCanvas.getPlaces()) if (processCanvas.hasPendingNode(place)) return true;
        return false;
    }

    /** ServiceDeploymentFolder/<domain>/<process>.json for a process saved under ProcessDefinitionFolder/<domain>. */
    private File suggestedDeploymentFile(File processFile) {
        File folder = definitionDirectory();
        if (folder == null) return null;
        String domain = "", name = "ServiceDeployment";
        if (processFile != null) {
            name = processFile.getName().replaceFirst("(?i)\\.json$", "");
            for (File cursor = processFile.getParentFile(); cursor != null && cursor.getParentFile() != null; cursor = cursor.getParentFile())
                if ("ProcessDefinitionFolder".equals(cursor.getParentFile().getName())) { domain = cursor.getName(); break; }
        }
        File target = domain.isEmpty() ? folder : new File(folder, domain);
        return new File(target, name + ".json");
    }

    private void placeBeside(Window owner) {
        if (owner == null) return;
        Rectangle screen = owner.getGraphicsConfiguration().getBounds();
        int width = Math.min(getWidth(), Math.max(700, screen.width / 2));
        setSize(width, Math.min(getHeight(), screen.height));
        int x = owner.getX() + owner.getWidth();
        if (x + width > screen.x + screen.width) x = screen.x + screen.width - width;
        setLocation(Math.max(screen.x, x), Math.max(screen.y, owner.getY()));
    }

    @Override public void dispose() {
        if (processCanvas != null) processCanvas.removeDeploymentListener(processListener);
        super.dispose();
    }

    /** Bring rows in line with the process: a row for every place operation, nodes chosen on the canvas applied. */
    public void refreshFromProcess() {
        if (processCanvas == null || refreshing) return;
        refreshing = true;
        try {
            stopTableEditing();
            List<ProcessElement> places = processCanvas.getPlaces();
            // Rows for places that were removed from the process and never saved are dropped.
            for (Iterator<Map.Entry<Capability, ProcessElement>> it = placeRows.entrySet().iterator(); it.hasNext();) {
                Map.Entry<Capability, ProcessElement> entry = it.next();
                if (places.contains(entry.getValue())) continue;
                if (rowsAddedForProcess.remove(entry.getKey())) capabilities.remove(entry.getKey());
                it.remove();
            }
            Set<String> taken = new HashSet<>();
            for (Capability c : capabilities) taken.add(blank(c.instance) ? c.service : c.instance);
            for (ProcessElement place : places) {
                if (blank(place.getService())) continue;
                for (ServiceOperation operation : place.getServiceOperations()) {
                    Capability row = rowFor(place, operation.getName());
                    String chosen = processCanvas.nodeFor(place);
                    if (row == null) {
                        row = new Capability();
                        row.service = place.getService();
                        row.operation = operation.getName();
                        String identity = ServiceRegistry.identity(place);
                        row.instance = !place.getServiceInstance().isEmpty() && !taken.contains(identity) ? identity : ServiceRegistry.instanceNameFor(place.getLabel(), taken);
                        taken.add(row.instance);
                        row.returnAttribute = operation.getReturnAttribute();
                        for (ServiceArgument input : operation.getArguments()) {
                            Argument a = new Argument(); a.name = input.getName();
                            if (!blank(input.getType())) a.type = input.getType();
                            if (input.getValue() != null) a.value = input.getValue();
                            a.required = input.isRequired(); row.arguments.add(a);
                        }
                        ServiceRegistry.Contract contract = serviceRegistry.contract(row.service, row.operation);
                        if (contract != null && "boolean".equals(contract.resultType)) row.adapter = "boolean-token";
                        row.node = blank(chosen) ? suggestNode(row.operation) : chosen;
                        NodeNetwork n = findNode(row.node);
                        row.portSlot = n == null ? 0 : Math.max(0, allocateSlot(n));
                        capabilities.add(row);
                        rowsAddedForProcess.add(row);
                    } else if (!blank(chosen) && !chosen.equals(row.node)) {
                        row.node = chosen;
                        row.portSlot = -1;
                        NodeNetwork n = findNode(chosen);
                        row.portSlot = n == null ? 0 : Math.max(0, allocateSlot(n));
                    }
                    placeRows.put(row, place);
                }
            }
            int selected = capabilityTable.getSelectedRow();
            capabilityModel.fireTableDataChanged();
            if (selected >= 0 && selected < rows().size()) capabilityTable.setRowSelectionInterval(selected, selected);
            updateTitle();
        } finally { refreshing = false; }
    }

    private Capability rowFor(ProcessElement place, String operation) {
        for (Map.Entry<Capability, ProcessElement> entry : placeRows.entrySet())
            if (entry.getValue() == place && operation.equals(entry.getKey().operation)) return entry.getKey();
        if (place.getServiceInstance().isEmpty() && !processCanvas.getServiceRegistry().isDeployed(place)) return null;
        String identity = ServiceRegistry.identity(place);
        for (Capability c : capabilities)
            if (!placeRows.containsKey(c) && place.getService().equals(c.service) && operation.equals(c.operation) && identity.equals(blank(c.instance) ? c.service : c.instance)) return c;
        return null;
    }

    /** First node that does not already run this operation and still has a free port slot. */
    private String suggestNode(String operation) {
        for (NodeNetwork n : nodes) {
            boolean used = false;
            for (Capability c : capabilities) if (n.node.equals(c.node) && operation.equals(c.operation)) used = true;
            if (!used && allocateSlot(n) >= 0) return n.node;
        }
        return nodes.isEmpty() ? "" : nodes.get(0).node;
    }

    /** The Service endpoint box: the selected row's address across the panel's width, and how long the host waits for it. */
    private JPanel endpointPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 4, 2, 4); c.anchor = GridBagConstraints.WEST;
        c.gridy = 0; c.gridx = 0; p.add(endpointLabel, c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; p.add(endpointField, c);
        c.gridx = 2; c.weightx = 0; c.fill = GridBagConstraints.NONE; p.add(new JLabel("Wait up to"), c);
        c.gridx = 3; p.add(timeoutField, c);
        c.gridx = 4; p.add(new JLabel("ms"), c);
        c.gridy = 1; c.gridx = 1; c.gridwidth = 4; c.fill = GridBagConstraints.HORIZONTAL; p.add(endpointNote, c);
        endpointField.setToolTipText("The http(s) address the host POSTs each call to, e.g. https://example.org/payment/authorise");
        timeoutField.setToolTipText("How long the host waits for an answer before the call fails (timeoutMs). Blank: 10 seconds.");
        endpointField.getDocument().addDocumentListener(onChange(this::endpointEdited));
        // The wait is milliseconds: anything but digits is dropped as it is typed or pasted.
        ((javax.swing.text.AbstractDocument)timeoutField.getDocument()).setDocumentFilter(new javax.swing.text.DocumentFilter() {
            @Override public void insertString(FilterBypass fb, int offset, String text, javax.swing.text.AttributeSet attributes) throws javax.swing.text.BadLocationException {
                replace(fb, offset, 0, text, attributes);
            }
            @Override public void replace(FilterBypass fb, int offset, int length, String text, javax.swing.text.AttributeSet attributes) throws javax.swing.text.BadLocationException {
                String digits = text == null ? "" : text.replaceAll("[^0-9]", "");
                if (fb.getDocument().getLength() - length + digits.length() <= 7) super.replace(fb, offset, length, digits, attributes);
            }
        });
        timeoutField.getDocument().addDocumentListener(onChange(this::timeoutEdited));
        showSelectedEndpoint();
        return p;
    }

    private static javax.swing.event.DocumentListener onChange(Runnable action) {
        return new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { action.run(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { action.run(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { action.run(); }
        };
    }

    private Capability selectedCapability() {
        int row = capabilityTable.getSelectedRow();
        if (row < 0) return null;
        int model = capabilityTable.convertRowIndexToModel(row);
        List<Capability> shown = rows();
        return model < shown.size() ? shown.get(model) : null;
    }

    /** Fill the endpoint box from the selected row. */
    private void showSelectedEndpoint() {
        if (endpointSync) return;
        Capability cap = selectedCapability();
        boolean rest = cap != null && ServiceRegistry.REST.equals(cap.adapter);
        endpointSync = true;
        try {
            String name = cap == null ? "" : processCanvas != null && !placeLabel(cap).isEmpty() ? placeLabel(cap) : cap.service + "." + cap.operation;
            endpointLabel.setText(name.isEmpty() ? "Service endpoint:" : "Service endpoint of " + name + ":");
            String endpoint = rest ? cap.endpoint : "";
            if (!endpointField.getText().trim().equals(endpoint)) { endpointField.setText(endpoint); endpointField.setCaretPosition(0); }
            String timeout = rest && cap.timeoutMs > 0 ? String.valueOf(cap.timeoutMs) : "";
            if (!timeoutField.getText().trim().equals(timeout)) timeoutField.setText(timeout);
            endpointField.setEnabled(rest);
            timeoutField.setEnabled(rest);
            String call = rest ? cap.call : "";
            lastValidCall = call;
            if (!sameJson(callArea.getText(), call)) { callArea.setText(prettyCall(call)); callArea.setCaretPosition(0); }
            callArea.setEnabled(rest); callExample.setEnabled(rest); tryCall.setEnabled(rest);
        } finally { endpointSync = false; }
        updateEndpointNote(cap);
        updateCallNote(cap);
    }

    private void endpointEdited() {
        if (endpointSync) return;
        Capability cap = selectedCapability();
        if (cap == null || !ServiceRegistry.REST.equals(cap.adapter)) return;
        cap.endpoint = endpointField.getText().trim();
        rowChanged(cap);
    }

    private void timeoutEdited() {
        if (endpointSync) return;
        Capability cap = selectedCapability();
        if (cap == null || !ServiceRegistry.REST.equals(cap.adapter)) return;
        String text = timeoutField.getText().trim();
        cap.timeoutMs = text.isEmpty() ? 0 : Integer.parseInt(text);
        rowChanged(cap);
    }

    /** The Call mapping box: for a rest row, how to call an API that was not written for RPSO, and a trial call. */
    private JPanel callPanel() {
        JPanel p = new JPanel(new BorderLayout(4, 2));
        p.setBorder(BorderFactory.createTitledBorder("Call mapping (only for an API that was not written for RPSO; leave empty otherwise)"));
        callArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        callArea.setToolTipText("<html>How the host calls this API, as JSON: method, headers, body, result, route, statusRoutes.<br>"
            + "${data.amount} is a field of the token's data; ${env:NAME} is an environment variable on the host, for API keys.</html>");
        p.add(new JScrollPane(callArea), BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout(6, 0));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        buttons.add(callExample); buttons.add(tryCall);
        south.add(callNote, BorderLayout.CENTER); south.add(buttons, BorderLayout.EAST);
        p.add(south, BorderLayout.SOUTH);
        callArea.getDocument().addDocumentListener(onChange(this::callEdited));
        callExample.setToolTipText("Fill the box with a mapping for a typical payments API, to change into yours");
        callExample.addActionListener(e -> {
            if (!callArea.getText().trim().isEmpty() && JOptionPane.showConfirmDialog(this, "Replace the call mapping with the example?",
                    "Call mapping", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
            callArea.setText(CALL_EXAMPLE); callArea.setCaretPosition(0);
        });
        tryCall.setToolTipText("Send one call to the endpoint now, exactly as a host would, and see the request, the answer and the route");
        tryCall.addActionListener(e -> tryCallDialog());
        return p;
    }

    static final String CALL_EXAMPLE = "{\n"
        + "  \"method\": \"POST\",\n"
        + "  \"headers\": {\"Authorization\": \"Bearer ${env:PAYMENT_KEY}\"},\n"
        + "  \"body\": {\"amount\": \"${data.amount}\", \"currency\": \"${data.currency}\"},\n"
        + "  \"route\": {\"field\": \"status\", \"values\": {\"succeeded\": \"paid\"}, \"otherwise\": \"declined\"},\n"
        + "  \"statusRoutes\": {\"402\": \"declined\"}\n"
        + "}";

    /** The mapping as typed is kept on the row only while it is a valid mapping, so a half-typed one is never saved. */
    private void callEdited() {
        if (endpointSync) return;
        Capability cap = selectedCapability();
        if (cap == null || !ServiceRegistry.REST.equals(cap.adapter)) return;
        String text = callArea.getText().trim();
        // Replacing the text empties the box for a moment, so an invalid text falls back to the last valid mapping, not to none.
        String next = callProblem(text) == null ? text : lastValidCall;
        if (callProblem(text) == null && !text.isEmpty()) lastValidCall = text;
        if (!sameJson(cap.call, next) || cap.call.isEmpty() != next.isEmpty()) { cap.call = next; rowChanged(cap); }
        updateCallNote(cap);
    }

    /** Why the text is not a usable call mapping, or null when it is (or is empty). */
    static String callProblem(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        Object parsed;
        try { parsed = new org.json.simple.parser.JSONParser().parse(text); }
        catch (Exception | Error e) { return "Not valid JSON yet"; }
        try { org.btsn.invocation.CallMapping.parse(parsed); return null; }
        catch (IllegalArgumentException e) { return e.getMessage(); }
    }

    private static boolean sameJson(String a, String b) {
        String x = a == null ? "" : a.trim(), y = b == null ? "" : b.trim();
        if (x.equals(y)) return true;
        try { return new org.json.simple.parser.JSONParser().parse(x).equals(new org.json.simple.parser.JSONParser().parse(y)); }
        catch (Exception | Error e) { return false; }
    }

    /** A saved mapping, re-indented for the box: the deployment file indents it under its placement. */
    private static String prettyCall(String call) {
        if (call == null || call.trim().isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (String line : call.trim().split("\\r?\\n")) {
            String stripped = line.startsWith("      ") ? line.substring(6) : line.trim().equals("}") ? "}" : line;
            out.append(stripped).append('\n');
        }
        return out.toString().trim();
    }

    private void updateCallNote(Capability cap) {
        boolean rest = cap != null && ServiceRegistry.REST.equals(cap.adapter);
        String problem = rest ? callProblem(callArea.getText()) : null;
        String note;
        if (!rest) note = "For a rest placement.";
        else if (problem != null) note = problem + " (the mapping is saved once it is valid)";
        else if (callArea.getText().trim().isEmpty()) note = "Empty: the host sends RPSO's own request and expects RPSO's own answer.";
        else note = "The host calls the API as this says. Try a call to see the request, the answer and the route.";
        callNote.setText(note);
        callNote.setForeground(problem != null ? Color.RED : Color.DARK_GRAY);
    }

    /** One sample token for a place: the token format of its service when there is one, otherwise empty data. */
    @SuppressWarnings("unchecked")
    static String sampleToken(File commonDirectory, String service) {
        org.json.simple.JSONObject data = new org.json.simple.JSONObject();
        try {
            if (commonDirectory == null) throw new IllegalStateException("no common directory");
            File format = new File(commonDirectory.getParentFile(), "btsn.common.eventgenerators/TokenFormats/" + service + ".json");
            if (format.isFile()) {
                String text = new String(java.nio.file.Files.readAllBytes(format.toPath()), java.nio.charset.StandardCharsets.UTF_8)
                    .replace("${n}", "1").replace("${sequenceId}", "1000000");
                org.json.simple.JSONObject json = (org.json.simple.JSONObject) new org.json.simple.parser.JSONParser().parse(text);
                Object structure = json.get("tokenStructure");
                if (structure instanceof org.json.simple.JSONObject && ((org.json.simple.JSONObject) structure).get("data") instanceof org.json.simple.JSONObject)
                    data.putAll((org.json.simple.JSONObject) ((org.json.simple.JSONObject) structure).get("data"));
                Object variants = json.get("dataVariants"), mix = json.get("caseMix");
                if (variants instanceof org.json.simple.JSONObject && mix instanceof org.json.simple.JSONArray && !((org.json.simple.JSONArray) mix).isEmpty()) {
                    Object first = ((org.json.simple.JSONObject) variants).get(((org.json.simple.JSONArray) mix).get(0));
                    if (first instanceof org.json.simple.JSONObject) data.putAll((org.json.simple.JSONObject) first);
                }
            }
        } catch (Exception | Error ignored) { }
        org.json.simple.JSONObject token = new org.json.simple.JSONObject();
        token.put("tokenId", "1000000"); token.put("version", "v001"); token.put("data", data);
        return token.toJSONString();
    }

    /** Makes one call for the selected row as a host would; the report is what the Try a call window shows. */
    static org.btsn.invocation.RestAdapter.Trial trial(String endpoint, int timeoutMs, String call, String service, String operation,
                                                         List<String> inputNames, String firstInput, String returnAttribute) {
        Object mapping = null;
        try { if (call != null && !call.trim().isEmpty()) mapping = new org.json.simple.parser.JSONParser().parse(call); }
        catch (Exception | Error e) {
            org.btsn.invocation.RestAdapter.Trial failed = new org.btsn.invocation.RestAdapter.Trial();
            failed.failure = "The call mapping is not valid JSON"; return failed;
        }
        List<String> values = new ArrayList<>();
        for (int i = 0; i < inputNames.size(); i++) values.add(i == 0 ? firstInput : "{}");
        return org.btsn.invocation.RestAdapter.trial(endpoint, timeoutMs, mapping, service, operation, "1000000", "v001", inputNames, values, returnAttribute);
    }

    static String trialReport(org.btsn.invocation.RestAdapter.Trial t) {
        StringBuilder out = new StringBuilder();
        out.append("REQUEST SENT\n").append(t.request.isEmpty() ? "(none: the request could not be built)" : t.request).append("\n\n");
        if (t.failure != null) out.append("THE CALL FAILED\n").append(t.failure).append("\n\nIn a run, this token would stop at the place with that reason.");
        else out.append("RESULT PUBLISHED\n").append(t.published).append("\n\nROUTE\n")
                .append(t.route == null ? "(none named: fine for a place whose T_out is not a gateway)" : t.route);
        return out.toString();
    }

    private void tryCallDialog() {
        Capability cap = selectedCapability();
        if (cap == null || !ServiceRegistry.REST.equals(cap.adapter)) return;
        String problem = callProblem(callArea.getText());
        if (problem != null) { JOptionPane.showMessageDialog(this, "Fix the call mapping first: " + problem, "Try a call", JOptionPane.WARNING_MESSAGE); return; }
        if (!ServiceRegistry.validRestEndpoint(cap.endpoint)) { JOptionPane.showMessageDialog(this, "Give the service endpoint first.", "Try a call", JOptionPane.WARNING_MESSAGE); return; }
        List<String> names = new ArrayList<>(); for (Argument argument : cap.arguments) names.add(argument.name);
        JTextArea input = new JTextArea(sampleToken(serviceRegistry.common() != null ? serviceRegistry.common() : ServiceRegistry.findCommon(currentFile), cap.service), 5, 70);
        input.setLineWrap(true);
        JTextArea report = new JTextArea(18, 70);
        report.setEditable(false); report.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        report.setText("Press Send to make one call to\n" + cap.endpoint + "\nexactly as a host would. This is a real call: use a test key or a test copy of the service.");
        JButton send = new JButton("Send");
        JPanel top = new JPanel(new BorderLayout(4, 4));
        top.add(new JLabel("The token that arrives" + (names.isEmpty() ? "" : " (input \"" + names.get(0) + "\")") + ":"), BorderLayout.NORTH);
        top.add(new JScrollPane(input), BorderLayout.CENTER);
        top.add(send, BorderLayout.EAST);
        JPanel content = new JPanel(new BorderLayout(6, 6));
        content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        content.add(top, BorderLayout.NORTH); content.add(new JScrollPane(report), BorderLayout.CENTER);
        JDialog dialog = new JDialog(this, "Try a call: " + cap.service + "." + cap.operation, true);
        final String endpoint = cap.endpoint, call = callArea.getText().trim(), service = cap.service, operation = cap.operation, returns = cap.returnAttribute;
        final int wait = cap.timeoutMs;
        send.addActionListener(e -> {
            send.setEnabled(false); report.setText("Calling...");
            final String token = input.getText();
            new SwingWorker<String, Void>() {
                @Override protected String doInBackground() { return trialReport(trial(endpoint, wait, call, service, operation, names, token, returns)); }
                @Override protected void done() {
                    try { report.setText(get()); } catch (Exception ex) { report.setText("The call could not be made: " + ex); }
                    report.setCaretPosition(0); send.setEnabled(true);
                }
            }.execute();
        });
        dialog.setContentPane(content); dialog.pack(); dialog.setLocationRelativeTo(this); dialog.setVisible(true);
    }

    /** A row changed from the endpoint box: redraw it and mark the deployment changed, without refilling the box. */
    private void rowChanged(Capability cap) {
        int row = rows().indexOf(cap);
        endpointSync = true;
        try { if (row >= 0) capabilityModel.fireTableRowsUpdated(row, row); } finally { endpointSync = false; }
        updateEndpointNote(cap);
        updateTitle();
    }

    private void updateEndpointNote(Capability cap) {
        String note; boolean problem = false;
        if (cap == null) note = "Select a row to see or change its service endpoint.";
        else if (!ServiceRegistry.REST.equals(cap.adapter)) note = "Set Invocation Adapter to rest to call this service at an endpoint.";
        else {
            List<String> names = new ArrayList<>(); for (Argument argument : cap.arguments) names.add(argument.name);
            List<String> errors = serviceRegistry.validateEndpoint(cap.service, cap.operation, cap.returnAttribute, names, cap.adapter, cap.endpoint);
            if (!errors.isEmpty()) { note = String.join("; ", errors); problem = true; }
            else note = (cap.call.isEmpty() ? "The host POSTs each call here" : "The host calls this address as the call mapping says") + " and waits up to " + (cap.timeoutMs > 0 ? cap.timeoutMs + " ms" : "10 seconds (leave the wait blank)")
                + "; a later answer fails the call.";
        }
        endpointNote.setText(note);
        endpointNote.setForeground(problem ? Color.RED : Color.DARK_GRAY);
    }

    /** Give the Service endpoint column room for an address; narrow the columns that hold a word or a number. */
    private void sizeCapabilityColumns() {
        int[] widths = {70, 130, 90, 110, 55, 90, 105, 320, 100, 140};
        javax.swing.table.TableColumnModel columns = capabilityTable.getColumnModel();
        for (int i = 0; i < columns.getColumnCount(); i++) {
            int model = columns.getColumn(i).getModelIndex();
            if (model < widths.length) columns.getColumn(i).setPreferredWidth(widths[model]);
        }
    }

    private String placeLabel(Capability c) {
        ProcessElement place = placeRows.get(c);
        return place == null ? "" : place.getLabel();
    }

    /** After saving: the process links to this deployment and each place uses its row's instance. */
    private void linkProcess(File file) throws Exception {
        Map<ProcessElement, String> instances = new LinkedHashMap<>();
        for (Map.Entry<Capability, ProcessElement> entry : placeRows.entrySet())
            instances.put(entry.getValue(), blank(entry.getKey().instance) ? entry.getKey().service : entry.getKey().instance);
        processCanvas.linkDeployment(file, instances);
        rowsAddedForProcess.clear();
        setStatus("Deployment saved and linked to the process. Save the process to keep the link.", file.getAbsolutePath());
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
            currentFile = file;
            if (deploymentEditor) loadCatalogue();
            parseJson(json);
            markSaved();
            rememberDirectory(preferenceKey(), file.getParentFile());
            if (!deploymentEditor || serviceRegistry.problem() == null)
                setStatus("Definition opened: " + file.getAbsolutePath(), file.getAbsolutePath());
            if (!deploymentEditor) useForEditor(file, "opened");
            return true;
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(dialogParent, "Could not open definition:\n" + ex.getMessage(),
                "Error", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    /** An infrastructure definition opened or saved here becomes the editor's machines (when runs can find it). */
    private void useForEditor(File file, String how) {
        String machines = ServiceRegistry.useInfrastructureFile(file);
        if (machines != null) setStatus("Definition " + how + "; the editor now uses " + machines + " (Machines)",
            "Runs on, Deploy to and Run on hosts use " + file.getAbsolutePath());
        else setStatus("Definition " + how + ": " + file.getAbsolutePath(),
            "Runs look for infrastructure in InfrastructureDefinitionFolder; save it there to use it.");
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
        b.append("{\n  \"definitionType\": \"ServiceDeployment\",\n");
        if (!catalogueReference.isEmpty()) b.append("  \"catalog\": \"").append(escape(catalogueReference)).append("\",\n");
        b.append("  \"capabilities\": [\n");
        for (int i = 0; i < capabilities.size(); i++) {
            Capability c = capabilities.get(i);
            b.append("    {\n");
            field(b, "node", c.node, true);
            field(b, "service", c.service, true);
            if (!blank(c.instance)) field(b, "instance", c.instance, true);
            if (!blank(c.adapter)) field(b, "invocationAdapter", c.adapter, true);
            if (ServiceRegistry.REST.equals(c.adapter)) field(b, "endpoint", c.endpoint, true);
            if (ServiceRegistry.REST.equals(c.adapter) && c.timeoutMs > 0) b.append("      \"timeoutMs\": ").append(c.timeoutMs).append(",\n");
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
            b.append("]");
            // A REST call mapping (an API not written for RPSO) is kept exactly as it was written.
            if (ServiceRegistry.REST.equals(c.adapter) && !c.call.isEmpty()) b.append(",\n      \"call\": ").append(c.call.trim().replace("\n", "\n      "));
            b.append("\n    }");
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
        catalogueReference = stringValue(json, "catalog");
        if (!deploymentEditor) {
            loadPhysicalNodes(json);
            nodeNetworkModel.fireTableDataChanged();
            return;
        }
        String body = arrayBody(json, "capabilities");
        for (String block : objectBlocks(body)) {
            Capability c = new Capability();
            c.call = objectValue(block, "call");
            if (!c.call.isEmpty()) block = block.replace(c.call, "{}");   // its keys are not the placement's
            c.call = prettyCall(c.call);
            c.node = stringValue(block, "node");
            c.service = stringValue(block, "service");
            c.instance = stringValue(block, "instance");
            c.adapter = stringValue(block, "invocationAdapter");
            c.endpoint = stringValue(block, "endpoint");
            c.timeoutMs = intValue(block, "timeoutMs");
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
        if (!rows().isEmpty()) capabilityTable.setRowSelectionInterval(0, 0);
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

    /** The JSON object under key, as written ("{...}"), or "" when there is none. */
    private String objectValue(String text, String key) {
        int keyPos = text.indexOf("\"" + key + "\"");
        if (keyPos < 0) return "";
        int colon = text.indexOf(':', keyPos);
        int start = colon < 0 ? -1 : text.indexOf('{', colon);
        if (start < 0 || !text.substring(colon + 1, start).trim().isEmpty()) return "";
        List<String> blocks = objectBlocks(text.substring(start));
        return blocks.isEmpty() ? "" : blocks.get(0);
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

    private void loadCatalogue() {
        if (currentFile == null) return;
        try { serviceRegistry.loadDeployment(currentFile); catalogueReference = serviceRegistry.catalogueReference(); }
        catch (Exception ex) { setStatus("Unresolved catalogue: " + ex.getMessage(), ex.getMessage()); }
    }

    private void useCatalogueContract(Capability cap) {
        ServiceRegistry.Contract contract = serviceRegistry.contract(cap.service, cap.operation);
        if (contract == null || aliasing(cap)) return;
        cap.returnAttribute = contract.output;
        cap.arguments.clear();
        for (String input : contract.inputs) { Argument argument = new Argument(); argument.name = input; argument.type = "String"; cap.arguments.add(argument); }
        argumentModel.setCapability(cap);
    }

    private List<String> validateDefinition() {
        List<String> errors = new ArrayList<>();
        if (nodes.isEmpty()) errors.add("No hosts have been defined.");
        if (deploymentEditor && capabilities.isEmpty()) errors.add("No capabilities have been defined.");
        if (processCanvas != null) for (ProcessElement place : processCanvas.getPlaces())
            if (!placeRows.containsValue(place)) errors.add("Place " + place.getLabel() + " has no service and operation to deploy.");

        if (deploymentEditor) {
            String associationError = serviceRegistry.catalogueAssociationError(currentFile);
            if (associationError != null) errors.add(associationError);
            if (serviceRegistry.problem() != null) errors.add(serviceRegistry.problem());
            else for (Capability cap : capabilities) {
                List<String> names = new ArrayList<>(); for (Argument input : cap.arguments) names.add(input.name);
                for (String error : serviceRegistry.validateEndpoint(cap.service, cap.operation, cap.returnAttribute, names, cap.adapter, cap.endpoint))
                    errors.add(cap.node + " / " + cap.service + "." + cap.operation + ": " + error);
            }
        }
        Set<String> nodeNames = new HashSet<>();
        Map<String, String> channelAddresses = new HashMap<>();
        for (NodeNetwork n : nodes) {
            String identity = blank(n.node) ? "<node>" : n.node;
            if (blank(n.node)) errors.add(identity + ": Node is required.");
            else if (!nodeNames.add(n.node)) errors.add(identity + ": Node is duplicated.");
            if (blank(n.channel)) errors.add(identity + ": Channel is required.");
            else if (!deploymentEditor && !n.channel.matches("ip\\d+"))
                errors.add(identity + ": Channel must be ip0, ip1, ... (one per machine; the number shifts the ports).");
            if (blank(n.address)) errors.add(identity + ": Address is required.");
            if (n.basePorts.isEmpty()) errors.add(identity + ": At least one fixed base port is required.");
            for (int port : n.basePorts) {
                if (port <= 0 || port > 45535) errors.add(identity + ": Base ports must be between 1 and 45535.");
                else if (!deploymentEditor && reservedPort(port))
                    errors.add(identity + ": Port " + port + " is kept for the hosts' own services (4100-4198 and 4300-4398); choose another.");
            }
            if (!deploymentEditor && !blank(n.node) && n.node.matches("P\\d+") && !n.node.matches("P[1-9]\\d?"))
                errors.add(identity + ": Host nodes are named P1 to P99.");
            if (!blank(n.channel) && !blank(n.address)) {
                String existing = channelAddresses.put(n.channel, n.address);
                if (existing != null && !existing.equals(n.address)) {
                    errors.add(identity + ": Channel " + n.channel + " is already mapped to " + existing + ".");
                }
            }
        }

        if (!deploymentEditor && !nodes.isEmpty() && !channelAddresses.containsKey("ip0"))
            errors.add("Channel ip0 is required: it is the machine that runs the launcher and Monitor.");
        Set<String> physicalSockets = new HashSet<>();
        for (NodeNetwork n : nodes) for (int port : n.basePorts) {
            if (!physicalSockets.add(n.address + ":" + n.channel + ":" + port))
                errors.add("Physical port " + port + " is duplicated on " + n.address + " / " + n.channel + ".");
        }
        Set<String> allocatedSockets = new HashSet<>();
        Set<String> runtimeOperations = new HashSet<>();
        Set<String> instances = new HashSet<>();
        for (int i = 0; i < capabilities.size(); i++) {
            Capability cap = capabilities.get(i);
            String identity = (blank(cap.node) ? "<node>" : cap.node) + " / " +
                              (blank(cap.service) ? "<service>" : cap.service) + " / " +
                              (blank(cap.operation) ? "<operation>" : cap.operation) + ": ";
            if (blank(cap.node)) errors.add(identity + "Node is required.");
            if (blank(cap.service)) errors.add(identity + "Service is required.");
            if (blank(cap.operation)) errors.add(identity + "Operation is required.");
            String instance = blank(cap.instance) ? cap.service : cap.instance;
            if (!instance.matches("[A-Za-z][A-Za-z0-9]*")) errors.add(identity + "Instance must be an alphanumeric identifier starting with a letter.");
            if (!instances.add(instance + "\u0000" + cap.operation)) errors.add(identity + "Instance operation is duplicated.");
            if (!blank(cap.adapter) && !ServiceRegistry.BOOLEAN_TOKEN.equals(cap.adapter) && !ServiceRegistry.REST.equals(cap.adapter)) errors.add(identity + "Unknown invocation adapter.");
            if (blank(cap.returnAttribute)) errors.add(identity + "Return Attribute is required.");

            NodeNetwork node = findNode(cap.node);
            if (node == null && !blank(cap.node)) {
                errors.add(identity + "Host is not defined in the infrastructure's hosts.");
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

    private String runtimeServiceForNode(String node) {
        if (node != null && node.matches("P\\d+")) return node + "_Place";
        return node == null ? "" : node;
    }

    private JFileChooser createRememberingChooser(String preferenceKey, File preferredDirectory) {
        File directory = rememberedDirectory(preferenceKey);
        if (directory == null || wrongRepositoryFolder(directory, preferredDirectory)) {
            directory = preferredDirectory;
        }
        if (directory != null && directory.isDirectory()) {
            return new JFileChooser(directory);
        }
        return new JFileChooser();
    }

    /**
     * A remembered folder elsewhere in the same btsn.common (BusinessServiceDefinitions, ProcessDefinitionFolder, ...)
     * is the wrong place to look for this kind of definition: start in the right folder instead. A folder inside the
     * right one, or outside btsn.common altogether, is kept.
     */
    static boolean wrongRepositoryFolder(File remembered, File preferred) {
        if (remembered == null || preferred == null || !preferred.isDirectory() || preferred.getParentFile() == null) return false;
        try {
            java.nio.file.Path folder = remembered.getCanonicalFile().toPath();
            java.nio.file.Path right = preferred.getCanonicalFile().toPath();
            return folder.startsWith(right.getParent()) && !folder.startsWith(right);
        } catch (java.io.IOException ex) {
            return false;
        }
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
        private final String[] columns = {"Host", "Channel", "Address", "Fixed Base Ports"};
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
        private final String[] columns = {"Node", "Service", "Operation", "Return Attribute", "Port Slot", "Instance", "Invocation Adapter", "Endpoint", "Place", "Address"};
        public int getRowCount() { return rows().size(); }
        public int getColumnCount() { return processCanvas == null ? columns.length - 2 : columns.length; }
        public String getColumnName(int c) {
            if (processCanvas != null && c == 0) return "Host node";
            if (processCanvas != null && c == 7) return "Service endpoint";
            return columns[c];
        }
        public Class<?> getColumnClass(int c) { return c == 4 ? Integer.class : String.class; }
        public boolean isCellEditable(int r, int c) {
            Capability x = rows().get(r);
            return c != 8 && c != 9 && (c != 3 || aliasing(x)) && (c != 7 || ServiceRegistry.REST.equals(x.adapter));
        }
        public Object getValueAt(int r, int c) {
            Capability x = rows().get(r);
            switch (c) {
                case 9: return hostAddress(x);
                case 8: return placeLabel(x);
                case 7: return x.endpoint;
                case 0: return x.node;
                case 1: return x.service;
                case 2: return x.operation;
                case 3: return x.returnAttribute;
                case 4: return x.portSlot;
                case 5: return x.instance;
                case 6: return x.adapter;
                default: return "";
            }
        }
        public void setValueAt(Object v, int r, int c) {
            Capability x = rows().get(r);
            String s = v == null ? "" : String.valueOf(v).trim();
            if (c == 0) {
                if (s.equals(x.node)) return;
                x.node = s;
                x.portSlot = -1;
                NodeNetwork n = findNode(s);
                if (n != null) x.portSlot = allocateSlot(n);
                fireTableRowsUpdated(r, r);
                ProcessElement place = placeRows.get(x);
                if (processCanvas != null && place != null && !refreshing) { refreshing = true; try { processCanvas.assignNode(place, s); } finally { refreshing = false; } }
                return;
            } else if (c == 1) {
                if (s.equals(x.service) || !serviceRegistry.services().contains(s)) return;
                x.service = s; x.adapter = "";
                List<String> operations = serviceRegistry.operations(s);
                x.operation = operations.size() == 1 ? operations.get(0) : "";
                useCatalogueContract(x); fireTableRowsUpdated(r, r); return;
            } else if (c == 2) {
                if (s.equals(x.operation) || !serviceRegistry.operations(x.service).contains(s)) return;
                x.operation = s; useCatalogueContract(x); fireTableRowsUpdated(r, r); return;
            }
            else if (c == 3) x.returnAttribute = s;
            else if (c == 4) x.portSlot = parseTableInt(v);
            else if (c == 5) x.instance = s;
            else if (c == 6) {
                if (s.equals(x.adapter)) return;
                x.adapter = s;
                if (!ServiceRegistry.REST.equals(s)) { x.endpoint = ""; x.timeoutMs = 0; x.call = ""; }
                useCatalogueContract(x); fireTableRowsUpdated(r, r); return;
            }
            else if (c == 7) x.endpoint = s;
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
        // The file also keeps each input's value and required flag; nothing reads them, so they are not shown.
        private final String[] columns = {"Input", "Type"};
        private Capability capability;
        void setCapability(Capability c) { capability = c; fireTableDataChanged(); }
        public int getRowCount() { return capability == null ? 0 : capability.arguments.size(); }
        public int getColumnCount() { return columns.length; }
        public String getColumnName(int c) { return columns[c]; }
        public Class<?> getColumnClass(int c) { return String.class; }
        public boolean isCellEditable(int r, int c) { return c == 0 && capability != null && aliasing(capability); }
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
        String instance = "";
        String adapter = "";
        String endpoint = "";
        /** How long the host waits for a rest call, in ms; 0 for the adapter's default. */
        int timeoutMs;
        /** A rest placement's call mapping, as JSON text; empty for RPSO's own request and answer. */
        String call = "";
        String operation = "";
        String returnAttribute = "";
        int portSlot;
        final List<Argument> arguments = new ArrayList<>();
    }

    /** Only the boolean-token adapter renames inputs; a rest placement keeps the catalogue's contract. */
    private static boolean aliasing(Capability c) { return ServiceRegistry.BOOLEAN_TOKEN.equals(c.adapter); }

    private static final class Argument {
        String name = "";
        String type = "String";
        String value = "String";
        boolean required;
    }
}