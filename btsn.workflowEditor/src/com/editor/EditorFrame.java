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
        {"TerminateNode", "TERMINATE_NODE"}
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
            JTextField freshServiceField = new JTextField(15);
            
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
            
            // Service
            addField("Service:", freshServiceField, element.getService());
            freshServiceField.getDocument().addDocumentListener(new SimpleDocumentListener(() -> {
                element.setService(freshServiceField.getText());
            }));
            
            // Operations with arguments
            addOperationsField("Operations:", element);
            
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
            
            // If current node type is not valid, default to EdgeNode
            if (!nodeTypeValid) {
                currentNodeType = "EdgeNode";
                element.setNodeType("EdgeNode");
                element.setNodeValue("EDGE_NODE");
            }
            
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
        if ("T_in".equals(transitionType)) {
            return NODE_TYPES_T_IN;
        } else if ("T_out".equals(transitionType)) {
            return NODE_TYPES_T_OUT;
        } else {
            return NODE_TYPES_ALL;
        }
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
    private void addOperationsField(String labelText, ProcessElement element) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createTitledBorder(labelText));
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 400));
        
        // Add new operation panel - NOW AT THE TOP
        JPanel addPanel = new JPanel(new BorderLayout(5, 0));
        addPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        
        JComboBox<String> operationCombo = new JComboBox<>();
        operationCombo.setEditable(true);
        operationCombo.setSelectedItem(""); // Empty default
        
        // Set placeholder text in the editor component
        Component editorComponent = operationCombo.getEditor().getEditorComponent();
        if (editorComponent instanceof JTextField) {
            JTextField tf = (JTextField) editorComponent;
            tf.putClientProperty("JTextField.placeholderText", "Enter operation name...");
            // Custom placeholder rendering
            tf.setForeground(Color.BLACK);
        }
        
        addPanel.add(operationCombo, BorderLayout.CENTER);
        
        // Operations list panel - will be refreshed
        JPanel operationsListPanel = new JPanel();
        operationsListPanel.setLayout(new BoxLayout(operationsListPanel, BoxLayout.Y_AXIS));
        
        // Placeholder label for empty state
        JLabel emptyLabel = new JLabel("No operations defined");
        emptyLabel.setFont(emptyLabel.getFont().deriveFont(Font.ITALIC));
        emptyLabel.setForeground(Color.GRAY);
        emptyLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        emptyLabel.setBorder(BorderFactory.createEmptyBorder(20, 0, 20, 0));
        
        // Refresh operations list - use array trick to allow self-reference
        final Runnable[] refreshList = new Runnable[1];
        refreshList[0] = () -> {
            operationsListPanel.removeAll();
            
            List<ServiceOperation> serviceOps = element.getServiceOperations();
            if (serviceOps.isEmpty()) {
                // Show placeholder when empty
                operationsListPanel.add(Box.createVerticalGlue());
                operationsListPanel.add(emptyLabel);
                operationsListPanel.add(Box.createVerticalGlue());
            } else {
                for (ServiceOperation op : serviceOps) {
                    JPanel opPanel = createOperationPanel(op, element, refreshList[0]);
                    operationsListPanel.add(opPanel);
                    operationsListPanel.add(Box.createVerticalStrut(2));
                }
            }
            
            operationsListPanel.revalidate();
            operationsListPanel.repaint();
        };
        
        JButton addBtn = new JButton("+");
        addBtn.setPreferredSize(new Dimension(30, 25));
        addBtn.setToolTipText("Add operation");
        addBtn.addActionListener(e -> {
            String newOp = (String) operationCombo.getSelectedItem();
            if (newOp != null) {
                newOp = newOp.trim();
                if (!newOp.isEmpty()) {
                    element.addOperation(newOp);
                    
                    // Add to combo box if not already there
                    boolean found = false;
                    for (int i = 0; i < operationCombo.getItemCount(); i++) {
                        if (operationCombo.getItemAt(i).equals(newOp)) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        operationCombo.addItem(newOp);
                    }
                    
                    operationCombo.setSelectedItem(""); // Reset to empty
                    refreshList[0].run();
                    canvas.repaint();
                }
            }
        });
        addPanel.add(addBtn, BorderLayout.EAST);
        
        // Add components in new order: add panel first, then list
        panel.add(addPanel);
        panel.add(Box.createVerticalStrut(5));
        
        // Initial population
        refreshList[0].run();
        
        JScrollPane scrollPane = new JScrollPane(operationsListPanel);
        scrollPane.setPreferredSize(new Dimension(230, 180));
        scrollPane.setMaximumSize(new Dimension(Integer.MAX_VALUE, 180));
        panel.add(scrollPane);
        
        attributesPanel.add(panel);
    }
    
    /**
     * Create a panel for a single operation with expand/collapse and arguments
     */
    private JPanel createOperationPanel(ServiceOperation op, ProcessElement element, Runnable refreshCallback) {
        JPanel opPanel = new JPanel();
        opPanel.setLayout(new BoxLayout(opPanel, BoxLayout.Y_AXIS));
        opPanel.setBorder(BorderFactory.createLineBorder(new Color(200, 200, 200)));
        opPanel.setBackground(new Color(250, 250, 250));
        
        // Header row with expand/collapse, name, and remove button
        JPanel headerPanel = new JPanel(new BorderLayout(2, 0));
        headerPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 25));
        headerPanel.setBackground(new Color(240, 240, 240));
        
        // Check if this operation is expanded
        String opKey = element.getLabel() + ":" + op.getName();
        boolean isExpanded = operationExpandedStates.getOrDefault(opKey, false);
        
        // Expand/collapse button
        JButton expandBtn = new JButton(isExpanded ? "[-]" : "[+]");
        expandBtn.setPreferredSize(new Dimension(35, 20));
        expandBtn.setMargin(new Insets(0, 2, 0, 2));
        expandBtn.setFont(expandBtn.getFont().deriveFont(Font.BOLD, 10.0f));
        expandBtn.setToolTipText(isExpanded ? "Collapse arguments" : "Expand arguments");
        expandBtn.addActionListener(e -> {
            boolean currentState = operationExpandedStates.getOrDefault(opKey, false);
            operationExpandedStates.put(opKey, !currentState);
            refreshCallback.run();
        });
        headerPanel.add(expandBtn, BorderLayout.WEST);
        
        // Operation name label
        JLabel opLabel = new JLabel(op.getName());
        opLabel.setBorder(BorderFactory.createEmptyBorder(2, 5, 2, 5));
        String argCount = op.hasArguments() ? " (" + op.getArgumentCount() + " args)" : "";
        opLabel.setText(op.getName() + argCount);
        headerPanel.add(opLabel, BorderLayout.CENTER);
        
        // Remove operation button
        JButton removeBtn = new JButton("X");
        removeBtn.setPreferredSize(new Dimension(25, 20));
        removeBtn.setMargin(new Insets(0, 0, 0, 0));
        removeBtn.setFont(removeBtn.getFont().deriveFont(Font.BOLD, 12.0f));
        removeBtn.setToolTipText("Remove operation");
        removeBtn.addActionListener(e -> {
            element.removeServiceOperation(op);
            operationExpandedStates.remove(opKey);
            refreshCallback.run();
            canvas.repaint();
        });
        headerPanel.add(removeBtn, BorderLayout.EAST);
        
        opPanel.add(headerPanel);
        
        // Arguments panel (shown when expanded)
        if (isExpanded) {
            JPanel argsPanel = createArgumentsPanel(op, refreshCallback);
            opPanel.add(argsPanel);
        }
        
        return opPanel;
    }
    
    /**
     * Create the arguments panel for an operation
     */
    private JPanel createArgumentsPanel(ServiceOperation op, Runnable refreshCallback) {
        JPanel argsPanel = new JPanel();
        argsPanel.setLayout(new BoxLayout(argsPanel, BoxLayout.Y_AXIS));
        argsPanel.setBorder(BorderFactory.createEmptyBorder(3, 15, 3, 3));
        argsPanel.setBackground(new Color(250, 250, 250));
        
        // List existing arguments
        for (ServiceArgument arg : op.getArguments()) {
            JPanel argRow = createArgumentRow(arg, op, refreshCallback);
            argsPanel.add(argRow);
            argsPanel.add(Box.createVerticalStrut(2));
        }
        
        // Add new argument row
        JPanel addArgPanel = new JPanel(new BorderLayout(2, 0));
        addArgPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        addArgPanel.setBackground(new Color(250, 250, 250));
        addArgPanel.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        
        JTextField newArgNameField = new JTextField();
        newArgNameField.setToolTipText("Argument name");
        
        JPanel centerPanel = new JPanel(new BorderLayout(2, 0));
        centerPanel.setBackground(new Color(250, 250, 250));
        centerPanel.add(new JLabel(" = "), BorderLayout.WEST);
        
        JTextField newArgValueField = new JTextField();
        newArgValueField.setToolTipText("Default value");
        centerPanel.add(newArgValueField, BorderLayout.CENTER);
        
        JButton addArgBtn = new JButton("+");
        addArgBtn.setPreferredSize(new Dimension(28, 20));
        addArgBtn.setMargin(new Insets(0, 0, 0, 0));
        addArgBtn.setToolTipText("Add argument");
        addArgBtn.addActionListener(e -> {
            String argName = newArgNameField.getText().trim();
            if (!argName.isEmpty()) {
                String argValue = newArgValueField.getText().trim();
                op.addArgument(argName, argValue);
                refreshCallback.run();
                canvas.repaint();
            }
        });
        
        // Use GridLayout for equal width name/value fields
        JPanel fieldsPanel = new JPanel(new GridLayout(1, 2, 2, 0));
        fieldsPanel.setBackground(new Color(250, 250, 250));
        fieldsPanel.add(newArgNameField);
        fieldsPanel.add(centerPanel);
        
        addArgPanel.add(fieldsPanel, BorderLayout.CENTER);
        addArgPanel.add(addArgBtn, BorderLayout.EAST);
        
        argsPanel.add(addArgPanel);
        
        return argsPanel;
    }
    
    /**
     * Create a row for displaying/editing a single argument
     */
    private JPanel createArgumentRow(ServiceArgument arg, ServiceOperation op, Runnable refreshCallback) {
        JPanel argRow = new JPanel(new BorderLayout(2, 0));
        argRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        argRow.setBackground(new Color(250, 250, 250));
        argRow.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        
        // Left side: name field
        JTextField nameField = new JTextField(arg.getName());
        nameField.setToolTipText("Argument name");
        nameField.getDocument().addDocumentListener(new SimpleDocumentListener(() -> {
            arg.setName(nameField.getText().trim());
        }));
        
        // Center: "=" label and value field
        JPanel centerPanel = new JPanel(new BorderLayout(2, 0));
        centerPanel.setBackground(new Color(250, 250, 250));
        centerPanel.add(new JLabel(" = "), BorderLayout.WEST);
        
        JTextField valueField = new JTextField(arg.getValue());
        valueField.setToolTipText("Value");
        valueField.getDocument().addDocumentListener(new SimpleDocumentListener(() -> {
            arg.setValue(valueField.getText());
        }));
        centerPanel.add(valueField, BorderLayout.CENTER);
        
        // Right side: required checkbox and remove button
        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        rightPanel.setBackground(new Color(250, 250, 250));
        
        JCheckBox reqCheck = new JCheckBox("R", arg.isRequired());
        reqCheck.setToolTipText("Required");
        reqCheck.setBackground(new Color(250, 250, 250));
        reqCheck.addActionListener(e -> {
            arg.setRequired(reqCheck.isSelected());
        });
        rightPanel.add(reqCheck);
        
        JButton removeArgBtn = new JButton("x");
        removeArgBtn.setPreferredSize(new Dimension(20, 18));
        removeArgBtn.setMargin(new Insets(0, 0, 0, 0));
        removeArgBtn.setFont(removeArgBtn.getFont().deriveFont(10.0f));
        removeArgBtn.setToolTipText("Remove argument");
        removeArgBtn.addActionListener(e -> {
            op.removeArgument(arg);
            refreshCallback.run();
            canvas.repaint();
        });
        rightPanel.add(removeArgBtn);
        
        // Use a split between name and value - roughly 40% name, 60% value
        JPanel fieldsPanel = new JPanel(new GridLayout(1, 2, 2, 0));
        fieldsPanel.setBackground(new Color(250, 250, 250));
        fieldsPanel.add(nameField);
        fieldsPanel.add(centerPanel);
        
        argRow.add(fieldsPanel, BorderLayout.CENTER);
        argRow.add(rightPanel, BorderLayout.EAST);
        
        return argRow;
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