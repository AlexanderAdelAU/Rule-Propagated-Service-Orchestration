package com.editor;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Attributes panel that displays and edits properties of selected Petri net elements
 */
public class EditorFrame extends JPanel {
    private Canvas canvas;
    
    // UI Components
    private JPanel attributesPanel;
    private JLabel titleLabel;
    
    // Color change listener (notifies ProcessEditor of color changes)
    private java.util.function.BiConsumer<Color, Color> colorChangeListener;
    
    
    // References to combo boxes for live updates
    private JComboBox<String> currentTransitionTypeCombo;
    private JComboBox<String> currentNodeTypeCombo;
    private Object currentSelection;
    
    // Track expanded/collapsed state of operations
    private Map<String, Boolean> operationExpandedStates = new HashMap<>();
    
    // Node type definitions - mapping from display name to node type and value
    private static final String[][] NODE_TYPES_ALL = {
        {"EdgeNode", "EDGE_NODE"},
        {"XorNode", "XOR_NODE"},
        {"JoinNode", "JOIN_NODE"},
        {"MergeNode", "MERGE_NODE"},
        {"XorMergeNode", "XOR_MERGE_NODE"},
        {"ForkNode", "FORK_NODE"},
        {"GatewayNode", "GATEWAY_NODE"},
        {"TerminateNode", "TERMINATE_NODE"},
        {"DecisionNode", "DECISION_NODE"},
        {"FeedFwdNode", "FEEDFWD_NODE"},
        {"MonitorNode", "MONITOR_NODE"}
    };
    
    // Node types valid for T_in transitions - includes merge/join semantics
    // T_in transitions receive tokens and pass them to a Place.
    // Join/Merge node types define how multiple incoming flows are synchronized.
    private static final String[][] NODE_TYPES_T_IN = {
        {"EdgeNode", "EDGE_NODE"},
        {"JoinNode", "JOIN_NODE"},
        {"MergeNode", "MERGE_NODE"},
        {"XorMergeNode", "XOR_MERGE_NODE"}
    };
    
    // Node types valid for T_out transitions (split/fork outgoing flows)
    private static final String[][] NODE_TYPES_T_OUT = {
        {"EdgeNode", "EDGE_NODE"},
        {"XorNode", "XOR_NODE"},
        {"ForkNode", "FORK_NODE"},
        {"GatewayNode", "GATEWAY_NODE"},
        {"TerminateNode", "TERMINATE_NODE"}
    };
    
    public EditorFrame(Canvas canvas) {
        this.canvas = canvas;
        
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createTitledBorder("Attributes"));
        setPreferredSize(new Dimension(260, 450));
        setMinimumSize(new Dimension(200, 300));
        // No max size - allow panel to grow when user drags splitter
        
        // Title
        titleLabel = new JLabel("No selection");
        titleLabel.setHorizontalAlignment(SwingConstants.CENTER);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD));
        titleLabel.setBorder(BorderFactory.createEmptyBorder(5, 5, 10, 5));
        add(titleLabel, BorderLayout.NORTH);
        
        // Attributes panel
        attributesPanel = new JPanel();
        attributesPanel.setLayout(new BoxLayout(attributesPanel, BoxLayout.Y_AXIS));
        JScrollPane scrollPane = new JScrollPane(attributesPanel);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        add(scrollPane, BorderLayout.CENTER);
        
        // Show empty state initially
        showEmptyState();
    }
    
    /**
     * Set the listener to be notified when colors are changed.
     * The listener receives (fillColor, borderColor) - either may be null for default.
     */
    public void setColorChangeListener(java.util.function.BiConsumer<Color, Color> listener) {
        this.colorChangeListener = listener;
    }
    
    /**
     * Notify the color change listener
     */
    private void notifyColorChange(Color fillColor, Color borderColor) {
        if (colorChangeListener != null) {
            colorChangeListener.accept(fillColor, borderColor);
        }
    }
    
    public void updateSelection(Object selection) {
        currentSelection = selection;
        
        if (selection == null) {
            showEmptyState();
        } else if (selection instanceof ProcessElement) {
            showElementAttributes((ProcessElement) selection);
        } else if (selection instanceof Arrow) {
            showArrowAttributes((Arrow) selection);
        }
    }
    
    private void showEmptyState() {
        titleLabel.setText("No selection");
        attributesPanel.removeAll();
        attributesPanel.revalidate();
        attributesPanel.repaint();
    }
    
    private void showElementAttributes(ProcessElement element) {
        attributesPanel.removeAll();
        
        if (element.getType() == ProcessElement.Type.PLACE) {
            titleLabel.setText("Place");
            
            // Create fresh text fields for each selection
            JTextField freshLabelField = new JTextField(15);
            
            // Label with validation
            addField("Label:", freshLabelField, element.getLabel());
            freshLabelField.getDocument().addDocumentListener(new SimpleDocumentListener(() -> {
                String newLabel = freshLabelField.getText().trim();
                if (newLabel.isEmpty()) {
                    freshLabelField.setBackground(new Color(255, 200, 200)); // Light red
                    freshLabelField.repaint();
                    return;
                }
                if (canvas.isLabelDuplicate(newLabel, element)) {
                    freshLabelField.setBackground(new Color(255, 200, 200)); // Light red - duplicate
                    freshLabelField.repaint();
                } else {
                    freshLabelField.setBackground(Color.WHITE);
                    freshLabelField.repaint();
                    element.setLabel(newLabel);
                    canvas.repaint();
                }
            }));
            
            addServiceSelection(element);

            // Colors section
            addColorField("Fill Color:", element.getFillColor(), Color.WHITE, 
                color -> {
                    element.setFillColor(color);
                    notifyColorChange(element.getFillColor(), element.getBorderColor());
                });
            addColorField("Border Color:", element.getBorderColor(), Color.BLACK, 
                color -> {
                    element.setBorderColor(color);
                    notifyColorChange(element.getFillColor(), element.getBorderColor());
                });
            
        } else if (element.getType() == ProcessElement.Type.EVENT_GENERATOR) {
            titleLabel.setText("Event Generator");
            
            // Create fresh text fields
            JTextField freshLabelField = new JTextField(15);
            JTextField freshRateField = new JTextField(15);
            JTextField freshVersionField = new JTextField(15);
            
            // Label with validation - this is the EVENT_GENERATOR identity
            // (e.g., TRIAGE_EVENTGENERATOR) used for instrumentation tracking
            addField("Label:", freshLabelField, element.getLabel());
            freshLabelField.getDocument().addDocumentListener(new SimpleDocumentListener(() -> {
                String newLabel = freshLabelField.getText().trim();
                if (newLabel.isEmpty()) {
                    freshLabelField.setBackground(new Color(255, 200, 200));
                    freshLabelField.repaint();
                    return;
                }
                if (canvas.isLabelDuplicate(newLabel, element)) {
                    freshLabelField.setBackground(new Color(255, 200, 200));
                    freshLabelField.repaint();
                } else {
                    freshLabelField.setBackground(Color.WHITE);
                    freshLabelField.repaint();
                    element.setLabel(newLabel);
                    canvas.repaint();
                }
            }));
            
            // Generator Rate (ms between tokens) - informational, used by external generator
            addField("Rate (ms):", freshRateField, element.getGeneratorRate());
            freshRateField.getDocument().addDocumentListener(new SimpleDocumentListener(() -> {
                String rate = freshRateField.getText().trim();
                // Validate it's a positive number
                try {
                    int rateVal = Integer.parseInt(rate);
                    if (rateVal > 0) {
                        freshRateField.setBackground(Color.WHITE);
                        element.setGeneratorRate(rate);
                    } else {
                        freshRateField.setBackground(new Color(255, 200, 200));
                    }
                } catch (NumberFormatException e) {
                    freshRateField.setBackground(new Color(255, 200, 200));
                }
                freshRateField.repaint();
            }));
            
            // Token Version prefix - informational, used by external generator
            addField("Version:", freshVersionField, element.getTokenVersion());
            freshVersionField.getDocument().addDocumentListener(new SimpleDocumentListener(() -> {
                element.setTokenVersion(freshVersionField.getText().trim());
            }));
            
            // Fork Children - number of child tokens this generator creates
            // When >= 2, enables implicit fork behavior for animation
            JSpinner forkSpinner = new JSpinner(new SpinnerNumberModel(
                element.getForkChildCount(), 0, 10, 1));
            forkSpinner.setMaximumSize(new Dimension(Integer.MAX_VALUE, 25));
            addSpinnerField("Fork Children:", forkSpinner, element.getForkChildCount());
            forkSpinner.addChangeListener(e -> {
                int childCount = (Integer) forkSpinner.getValue();
                element.setForkChildCount(childCount);
                
                if (childCount >= 2) {
                    element.setForkEnabled(true);
                    // Auto-detect join target from outgoing arrow
                    String joinTarget = detectJoinTarget(element);
                    element.setForkJoinTarget(joinTarget);
                } else {
                    element.setForkEnabled(false);
                    element.setForkJoinTarget(null);
                }
                canvas.repaint();
            });
            
            // Show current join target (read-only, auto-detected)
            if (element.isForkEnabled() && element.getForkJoinTarget() != null) {
                JTextField joinTargetField = new JTextField(15);
                joinTargetField.setEditable(false);
                joinTargetField.setBackground(new Color(240, 240, 240));
                addField("Join Target:", joinTargetField, element.getForkJoinTarget());
            }
            
            // Colors section
            Color defaultEGFill = new Color(220, 255, 220);  // Light green
            addColorField("Fill Color:", element.getFillColor(), defaultEGFill, 
                color -> {
                    element.setFillColor(color);
                    notifyColorChange(element.getFillColor(), element.getBorderColor());
                });
            addColorField("Border Color:", element.getBorderColor(), Color.BLACK, 
                color -> {
                    element.setBorderColor(color);
                    notifyColorChange(element.getFillColor(), element.getBorderColor());
                });
            
            // Add network connectivity
            addNetworkConnectivity(element);
            
        } else {
            titleLabel.setText("Transition");
            
            // Create fresh text field for label
            JTextField freshLabelField = new JTextField(15);
            
            // Label with validation
            addField("Label:", freshLabelField, element.getLabel());
            freshLabelField.getDocument().addDocumentListener(new SimpleDocumentListener(() -> {
                String newLabel = freshLabelField.getText().trim();
                if (newLabel.isEmpty()) {
                    freshLabelField.setBackground(new Color(255, 200, 200)); // Light red
                    freshLabelField.repaint();
                    return;
                }
                if (canvas.isLabelDuplicate(newLabel, element)) {
                    freshLabelField.setBackground(new Color(255, 200, 200)); // Light red - duplicate
                    freshLabelField.repaint();
                } else {
                    freshLabelField.setBackground(Color.WHITE);
                    freshLabelField.repaint();
                    element.setLabel(newLabel);
                    
                    // Only auto-update transition type if label matches pattern AND it's different
                    String currentTransType = element.getTransitionType();
                    String newTransType = null;
                    
                    if (newLabel.startsWith("T_in_")) {
                        newTransType = "T_in";
                    } else if (newLabel.startsWith("T_out_")) {
                        newTransType = "T_out";
                    }
                    
                    // Only update transition type and dropdown if label matches pattern AND it's different
                    if (newTransType != null && !newTransType.equals(currentTransType)) {
                        element.setTransitionType(newTransType);
                        canvas.repaint();
                        // Update the dropdown to reflect the new type without destroying the text field
                        if (currentTransitionTypeCombo != null) {
                            currentTransitionTypeCombo.setSelectedItem(newTransType);
                        }
                    } else {
                        // Just repaint, don't refresh attributes panel
                        canvas.repaint();
                    }
                }
            }));
            
            // Transition Type dropdown (T_in, T_out, Other)
            currentTransitionTypeCombo = new JComboBox<>(new String[]{"T_in", "T_out", "Other"});
            String currentTransitionType = element.getTransitionType();
            if (currentTransitionType == null || currentTransitionType.isEmpty()) {
                currentTransitionType = element.inferTransitionTypeFromLabel();
                element.setTransitionType(currentTransitionType);
            }
            currentTransitionTypeCombo.setSelectedItem(currentTransitionType);
            addComboField("Transition Type:", currentTransitionTypeCombo, currentTransitionType);
            
            // Get filtered node types based on transition type
            String[][] validNodeTypes = getNodeTypesForTransitionType(currentTransitionType);
            
            // Node Type dropdown (filtered based on Transition Type)
            currentNodeTypeCombo = new JComboBox<>();
            for (String[] nodeType : validNodeTypes) {
                currentNodeTypeCombo.addItem(nodeType[0]); // Add display name
            }
            
            // Set current value - and UPDATE the element if it's empty!
            String currentNodeType = element.getNodeType();
            if (currentNodeType == null || currentNodeType.isEmpty()) {
                currentNodeType = "EdgeNode";
                element.setNodeType("EdgeNode");
                element.setNodeValue("EDGE_NODE");
            }
            
            // Check if current node type is valid for this transition type
            boolean nodeTypeValid = false;
            for (String[] nodeType : validNodeTypes) {
                if (nodeType[0].equals(currentNodeType)) {
                    nodeTypeValid = true;
                    break;
                }
            }
            
            // Preserve unknown legacy types visibly; inspecting a node must never rewrite its routing.
            if (!nodeTypeValid) currentNodeTypeCombo.addItem(currentNodeType);

            currentNodeTypeCombo.setSelectedItem(currentNodeType);
            addComboField("Node Type:", currentNodeTypeCombo, currentNodeType);
            
            // When transition type changes, refresh with filtered node types
            currentTransitionTypeCombo.addActionListener(e -> {
                String selectedTransType = (String) currentTransitionTypeCombo.getSelectedItem();
                if (selectedTransType != null) {
                    element.setTransitionType(selectedTransType);
                    canvas.repaint();
                    // Refresh the attributes panel to show filtered node types
                    showElementAttributes(element);
                }
            });
            
            // When node type changes, update both node_type and node_value
            currentNodeTypeCombo.addActionListener(e -> {
                String selectedType = (String) currentNodeTypeCombo.getSelectedItem();
                if (selectedType != null) {
                    // Find the corresponding node value in ALL node types
                    for (String[] nodeType : NODE_TYPES_ALL) {
                        if (nodeType[0].equals(selectedType)) {
                            element.setNodeType(nodeType[0]);
                            element.setNodeValue(nodeType[1]);
                            break;
                        }
                    }
                    canvas.repaint();
                    // Refresh the attributes panel to show updated Node Value
                    showElementAttributes(element);
                }
            });
            
            // Display different fields based on transition type
            if ("T_in".equals(currentTransitionType)) {
                // For T_in transitions, show buffer field (editable)
                JTextField freshBufferField = new JTextField(15);
                String currentBuffer = element.getBuffer();
                if (currentBuffer == null || currentBuffer.isEmpty()) {
                    currentBuffer = "10";
                    element.setBuffer(currentBuffer);
                }
                addField("Buffer:", freshBufferField, currentBuffer);
                freshBufferField.getDocument().addDocumentListener(new SimpleDocumentListener(() -> {
                    element.setBuffer(freshBufferField.getText());
                }));
            } else if ("T_out".equals(currentTransitionType)) {
                // For T_out transitions, don't show Node Value (kept internally)
                // No additional fields needed
            } else {
                // For "Other" transition types, display Node Value as read-only label
                String currentNodeValue = element.getNodeValue();
                if (currentNodeValue == null || currentNodeValue.isEmpty()) {
                    currentNodeValue = "EDGE_NODE";
                }
                addLabel("Node Value:", currentNodeValue);
            }
            
            // Colors section for Transitions
            addColorField("Fill Color:", element.getFillColor(), Color.WHITE, 
                color -> {
                    element.setFillColor(color);
                    notifyColorChange(element.getFillColor(), element.getBorderColor());
                });
            addColorField("Border Color:", element.getBorderColor(), Color.BLACK, 
                color -> {
                    element.setBorderColor(color);
                    notifyColorChange(element.getFillColor(), element.getBorderColor());
                });
        }
        
        // Add network connectivity section at the bottom
        addNetworkConnectivity(element);
        
        attributesPanel.revalidate();
        attributesPanel.repaint();
    }
    
    /**
     * Get valid node types for a given transition type
     */
    private String[][] getNodeTypesForTransitionType(String transitionType) {
        return NODE_TYPES_ALL;
    }
    
    private void showArrowAttributes(Arrow arrow) {
        titleLabel.setText("Arrow");
        attributesPanel.removeAll();
        
        // Create fresh text fields for each selection to avoid listener accumulation
        JTextField freshArrowLabelField = new JTextField(15);
        JTextField freshDecisionField = new JTextField(15);
        JTextField freshEndpointField = new JTextField(15);
        
        // Label
        addField("Label:", freshArrowLabelField, arrow.getLabel());
        freshArrowLabelField.getDocument().addDocumentListener(new SimpleDocumentListener(() -> {
            arrow.setLabel(freshArrowLabelField.getText());
            canvas.repaint();
        }));
        
        // Connection Type checkbox
        JCheckBox networkCheckbox = new JCheckBox("Network Connection");
        networkCheckbox.setSelected(arrow.isNetworkConnection());
        networkCheckbox.setToolTipText("Network connections are shown as dashed lines");
        networkCheckbox.addActionListener(e -> {
            arrow.setNetworkConnection(networkCheckbox.isSelected());
            canvas.repaint();
            // Refresh to show/hide availability field
            showArrowAttributes(arrow);
        });
        addCheckboxField("Connection Type:", networkCheckbox);
        
        // Availability field (only shown for network connections)
        if (arrow.isNetworkConnection()) {
            JPanel availPanel = new JPanel(new BorderLayout(5, 5));
            availPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 60));
            availPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
            
            JLabel availLabel = new JLabel("Availability (%):");
            availLabel.setPreferredSize(new Dimension(Integer.MAX_VALUE, 20));
            availPanel.add(availLabel, BorderLayout.NORTH);
            
            // Slider for availability
            JPanel sliderPanel = new JPanel(new BorderLayout(5, 0));
            JSlider availSlider = new JSlider(0, 100, (int)(arrow.getAvailability() * 100));
            availSlider.setMajorTickSpacing(25);
            availSlider.setMinorTickSpacing(5);
            availSlider.setPaintTicks(true);
            
            JLabel valueLabel = new JLabel(arrow.getAvailabilityPercent());
            valueLabel.setPreferredSize(new Dimension(50, 20));
            
            availSlider.addChangeListener(e -> {
                double percent = availSlider.getValue();
                arrow.setAvailabilityPercent(percent);
                valueLabel.setText(arrow.getAvailabilityPercent());
                canvas.repaint();
            });
            
            sliderPanel.add(availSlider, BorderLayout.CENTER);
            sliderPanel.add(valueLabel, BorderLayout.EAST);
            availPanel.add(sliderPanel, BorderLayout.CENTER);
            
            attributesPanel.add(availPanel);
        }
        
        // Guard Condition - DROPDOWN instead of text field
        String[] guardOptions = {
            "",
            "DECISION_EQUAL_TO",
            "DECISION_NOT_EQUAL",
            "DECISION_GREATER_THAN",
            "DECISION_LESS_THAN"
        };
        JComboBox<String> guardConditionCombo = new JComboBox<>(guardOptions);
        guardConditionCombo.setSelectedItem(arrow.getGuardCondition());
        guardConditionCombo.addActionListener(e -> {
            arrow.setGuardCondition((String) guardConditionCombo.getSelectedItem());
        });
        addComboField("Guard Condition:", guardConditionCombo, arrow.getGuardCondition());
        
        // Decision Value
        addField("Decision Value:", freshDecisionField, arrow.getDecisionValue());
        freshDecisionField.getDocument().addDocumentListener(new SimpleDocumentListener(() -> {
            arrow.setDecisionValue(freshDecisionField.getText());
        }));
        
        // Endpoint - which operation to invoke for multi-operation services
        addField("Endpoint:", freshEndpointField, arrow.getEndpoint());
        freshEndpointField.getDocument().addDocumentListener(new SimpleDocumentListener(() -> {
            arrow.setEndpoint(freshEndpointField.getText());
        }));
        
        // Source (read-only)
        addLabel("Source:", arrow.getSource().getLabel());
        
        // Target (read-only)
        addLabel("Target:", arrow.getTarget().getLabel());
        
        attributesPanel.revalidate();
        attributesPanel.repaint();
    }
    
    /**
     * Add a checkbox field
     */
    private void addCheckboxField(String labelText, JCheckBox checkbox) {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 50));
        panel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        
        JLabel label = new JLabel(labelText);
        label.setPreferredSize(new Dimension(Integer.MAX_VALUE, 20));
        panel.add(label, BorderLayout.NORTH);
        
        panel.add(checkbox, BorderLayout.CENTER);
        
        attributesPanel.add(panel);
    }
    
    private void addField(String labelText, JTextField field, String value) {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 60));
        panel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        
        JLabel label = new JLabel(labelText);
        label.setPreferredSize(new Dimension(Integer.MAX_VALUE, 20));
        panel.add(label, BorderLayout.NORTH);
        
        field.setText(value);
        panel.add(field, BorderLayout.CENTER);
        
        attributesPanel.add(panel);
    }
    
    private void addLabel(String labelText, String value) {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 60));
        panel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        
        JLabel label = new JLabel(labelText);
        label.setPreferredSize(new Dimension(Integer.MAX_VALUE, 20));
        panel.add(label, BorderLayout.NORTH);
        
        JLabel valueLabel = new JLabel(value);
        valueLabel.setBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY));
        valueLabel.setOpaque(true);
        valueLabel.setBackground(Color.WHITE);
        valueLabel.setPreferredSize(new Dimension(Integer.MAX_VALUE, 25));
        panel.add(valueLabel, BorderLayout.CENTER);
        
        attributesPanel.add(panel);
    }
    
    private void addComboField(String labelText, JComboBox<String> combo, String value) {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 60));
        panel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        
        JLabel label = new JLabel(labelText);
        label.setPreferredSize(new Dimension(Integer.MAX_VALUE, 20));
        panel.add(label, BorderLayout.NORTH);
        
        combo.setSelectedItem(value);
        panel.add(combo, BorderLayout.CENTER);
        
        attributesPanel.add(panel);
    }
    
    private void addSpinnerField(String labelText, JSpinner spinner, int value) {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 60));
        panel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        
        JLabel label = new JLabel(labelText);
        label.setPreferredSize(new Dimension(Integer.MAX_VALUE, 20));
        panel.add(label, BorderLayout.NORTH);
        
        spinner.setValue(value);
        panel.add(spinner, BorderLayout.CENTER);
        
        attributesPanel.add(panel);
    }
    
    /**
     * Add a color picker field with a button that shows the current color
     * and opens a JColorChooser when clicked.
     */
    private void addColorField(String labelText, Color currentColor, Color defaultColor, 
                               java.util.function.Consumer<Color> colorSetter) {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 60));
        panel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        
        JLabel label = new JLabel(labelText);
        label.setPreferredSize(new Dimension(Integer.MAX_VALUE, 20));
        panel.add(label, BorderLayout.NORTH);
        
        // Panel to hold color button and reset button
        JPanel buttonPanel = new JPanel(new BorderLayout(5, 0));
        
        // Color preview button
        JButton colorButton = new JButton();
        Color displayColor = currentColor != null ? currentColor : defaultColor;
        colorButton.setBackground(displayColor);
        colorButton.setOpaque(true);
        colorButton.setBorderPainted(true);
        colorButton.setPreferredSize(new Dimension(80, 25));
        
        // Show hex code as text
        colorButton.setText(colorToHex(displayColor));
        colorButton.setForeground(getContrastColor(displayColor));
        
        colorButton.addActionListener(e -> {
            Color chosen = JColorChooser.showDialog(
                this, 
                "Choose " + labelText.replace(":", ""), 
                colorButton.getBackground()
            );
            if (chosen != null) {
                colorButton.setBackground(chosen);
                colorButton.setText(colorToHex(chosen));
                colorButton.setForeground(getContrastColor(chosen));
                colorSetter.accept(chosen);
                canvas.repaint();
            }
        });
        
        // Reset button to clear custom color
        JButton resetButton = new JButton("↺");
        resetButton.setToolTipText("Reset to default");
        resetButton.setPreferredSize(new Dimension(30, 25));
        resetButton.setMargin(new Insets(0, 0, 0, 0));
        resetButton.addActionListener(e -> {
            colorButton.setBackground(defaultColor);
            colorButton.setText(colorToHex(defaultColor));
            colorButton.setForeground(getContrastColor(defaultColor));
            colorSetter.accept(null);  // null means use default
            canvas.repaint();
        });
        
        buttonPanel.add(colorButton, BorderLayout.CENTER);
        buttonPanel.add(resetButton, BorderLayout.EAST);
        
        panel.add(buttonPanel, BorderLayout.CENTER);
        attributesPanel.add(panel);
    }
    
    /**
     * Convert a Color to hex string (e.g., "#FF5500")
     */
    private String colorToHex(Color c) {
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }
    
    /**
     * Get a contrasting color (black or white) for text on a given background
     */
    private Color getContrastColor(Color bg) {
        // Calculate luminance
        double luminance = (0.299 * bg.getRed() + 0.587 * bg.getGreen() + 0.114 * bg.getBlue()) / 255;
        return luminance > 0.5 ? Color.BLACK : Color.WHITE;
    }
    
    /**
     * Detect the join target T_in transition from an EVENT_GENERATOR's outgoing arrow
     */
    private String detectJoinTarget(ProcessElement eventGen) {
        List<Arrow> allArrows = canvas.getArrows();
        for (Arrow arrow : allArrows) {
            if (arrow.getSource() == eventGen) {
                ProcessElement target = arrow.getTarget();
                if (target != null && target.getType() == ProcessElement.Type.TRANSITION) {
                    String label = target.getLabel();
                    if (label != null && label.startsWith("T_in_")) {
                        return label;
                    }
                }
            }
        }
        return null;
    }
    
    /**
     * Add the operations field with collapsible arguments support
     */
    private void addServiceSelection(ProcessElement element) {
        ServiceRegistry registry = canvas.getServiceRegistry();
        JButton choose = new JButton("Choose service deployment...");
        choose.addActionListener(e -> { canvas.chooseServiceDeployment(this); updateSelection(element); });
        attributesPanel.add(choose);
        JTextArea source = new JTextArea(registry.description());
        source.setEditable(false); source.setOpaque(false); source.setLineWrap(true); source.setWrapStyleWord(true);
        source.setPreferredSize(new Dimension(220, 40)); source.setMinimumSize(new Dimension(0, 40)); source.setMaximumSize(new Dimension(Integer.MAX_VALUE, 55));
        source.setToolTipText(registry.description()); attributesPanel.add(source);
        JComboBox<String> services = ServiceRegistry.choices(registry.services(), element.getService());
        addComboField("Service:", services, element.getService());
        services.addActionListener(e -> {
            String value = (String)services.getSelectedItem();
            if (!registry.services().contains(value) || value.equals(element.getService())) return;
            element.setService(value); element.setServiceInstance(""); element.setServiceOperations(new ArrayList<>());
            updateSelection(element); canvas.serviceContractChanged();
        });
        String op = element.getOperations().isEmpty() ? "" : element.getOperations().get(0);
        JComboBox<String> operations = ServiceRegistry.choices(registry.operations(element.getService()), op);
        addComboField("Operation:", operations, op);
        JComboBox<String> instances = ServiceRegistry.choices(registry.instances(element.getService(), op.isEmpty() ? null : op), ServiceRegistry.identity(element));
        addComboField("Deployment instance:", instances, ServiceRegistry.identity(element));
        operations.addActionListener(e -> {
            String value = (String)operations.getSelectedItem();
            if (!registry.operations(element.getService()).contains(value)) return;
            List<String> valid = registry.instances(element.getService(), value);
            String identity = ServiceRegistry.identity(element);
            if (valid.contains(identity)) registry.apply(element, value, identity);
            else {
                element.setServiceOperations(java.util.Arrays.asList(new ServiceOperation(value)));
                element.setServiceInstance("");
                if (valid.size() == 1) registry.apply(element, value, valid.get(0));
            }
            updateSelection(element); canvas.serviceContractChanged();
        });
        instances.addActionListener(e -> {
            String identity = (String)instances.getSelectedItem();
            if (registry.endpoint(element.getService(), op, identity) == null) return;
            registry.apply(element, op, identity); updateSelection(element); canvas.serviceContractChanged();
        });
        for (ServiceOperation operation : element.getServiceOperations()) {
            StringBuilder text = new StringBuilder(operation.getName()).append("(");
            for (ServiceArgument argument : operation.getArguments()) { if (text.charAt(text.length()-1) != '(') text.append(", "); text.append(argument.getName()); }
            text.append(") → ").append(operation.getReturnAttribute());
            JTextArea detail = new JTextArea(text.toString()); detail.setEditable(false); detail.setLineWrap(true); detail.setWrapStyleWord(true);
            detail.setBorder(BorderFactory.createTitledBorder("Deployment contract"));
            detail.setPreferredSize(new Dimension(220, 70)); detail.setMinimumSize(new Dimension(0, 70)); detail.setMaximumSize(new Dimension(Integer.MAX_VALUE, 100));
            attributesPanel.add(detail);
        }
        List<String> errors = registry.validatePlace(element);
        if (!errors.isEmpty()) {
            JTextArea warning = new JTextArea(String.join("\n", errors)); warning.setEditable(false); warning.setLineWrap(true); warning.setWrapStyleWord(true); warning.setForeground(Color.RED);
            warning.setBorder(BorderFactory.createTitledBorder("Unresolved service contract")); attributesPanel.add(warning);
        }
    }

    /**
     * Add network connectivity information showing incoming and outgoing arrows
     */
    private void addNetworkConnectivity(ProcessElement element) {
        JPanel networkPanel = new JPanel();
        networkPanel.setLayout(new BoxLayout(networkPanel, BoxLayout.Y_AXIS));
        networkPanel.setBorder(BorderFactory.createTitledBorder("Network"));
        networkPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 200));
        
        JTextArea networkText = new JTextArea();
        networkText.setEditable(false);
        networkText.setFont(new Font("Monospaced", Font.PLAIN, 10));
        networkText.setBackground(new Color(245, 245, 245));
        
        StringBuilder sb = new StringBuilder();
        
        // Get all arrows from canvas
        List<Arrow> allArrows = canvas.getArrows();
        
        // Find incoming arrows (arrows targeting this element)
        List<String> incoming = new ArrayList<>();
        for (Arrow arrow : allArrows) {
            if (arrow.getTarget() == element) {
                incoming.add(arrow.getSource().getLabel() + " -> " + element.getLabel());
            }
        }
        
        // Find outgoing arrows (arrows from this element)
        List<String> outgoing = new ArrayList<>();
        for (Arrow arrow : allArrows) {
            if (arrow.getSource() == element) {
                outgoing.add(element.getLabel() + " -> " + arrow.getTarget().getLabel());
            }
        }
        
        // Build display text
        if (!incoming.isEmpty()) {
            sb.append("Incoming:\n");
            for (String conn : incoming) {
                sb.append("  ").append(conn).append("\n");
            }
        }
        
        if (!outgoing.isEmpty()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("Outgoing:\n");
            for (String conn : outgoing) {
                sb.append("  ").append(conn).append("\n");
            }
        }
        
        if (sb.length() == 0) {
            sb.append("No connections");
        }
        
        networkText.setText(sb.toString());
        networkText.setCaretPosition(0); // Scroll to top
        
        JScrollPane scrollPane = new JScrollPane(networkText);
        scrollPane.setPreferredSize(new Dimension(230, 100));
        scrollPane.setMaximumSize(new Dimension(230, 100));
        
        networkPanel.add(scrollPane);
        attributesPanel.add(networkPanel);
    }
    
    /**
     * Simple document listener helper class
     */
    private static class SimpleDocumentListener implements javax.swing.event.DocumentListener {
        private Runnable action;
        
        public SimpleDocumentListener(Runnable action) {
            this.action = action;
        }
        
        public void insertUpdate(javax.swing.event.DocumentEvent e) { action.run(); }
        public void removeUpdate(javax.swing.event.DocumentEvent e) { action.run(); }
        public void changedUpdate(javax.swing.event.DocumentEvent e) { action.run(); }
    }
}