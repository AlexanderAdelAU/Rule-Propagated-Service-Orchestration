package com.petrinet.editor;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;

/**
 * Attributes panel that displays and edits properties of selected Petri net elements
 */
public class PetriNetEditorFrame extends JPanel {
    private PetriNetCanvas canvas;
    
    // UI Components
    private JPanel attributesPanel;
    private JLabel titleLabel;
    
    private Object currentSelection;
    
    public PetriNetEditorFrame(PetriNetCanvas canvas) {
        this.canvas = canvas;
        
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createTitledBorder("Attributes"));
        setPreferredSize(new Dimension(200, 300));
        setMaximumSize(new Dimension(200, 400));
        
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
    
    public void updateSelection(Object selection) {
        currentSelection = selection;
        
        if (selection == null) {
            showEmptyState();
        } else if (selection instanceof PetriNetElement) {
            showElementAttributes((PetriNetElement) selection);
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
    
    private void showElementAttributes(PetriNetElement element) {
        attributesPanel.removeAll();
        
        if (element.getType() == PetriNetElement.Type.PLACE) {
            titleLabel.setText("Place");
            
            // Create fresh text fields for each selection
            JTextField freshLabelField = new JTextField(15);
            JTextField freshServiceField = new JTextField(15);
            JTextField freshOperationField = new JTextField(15);
            
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
            
            // Operation
            addField("Operation:", freshOperationField, element.getOperation());
            freshOperationField.getDocument().addDocumentListener(new SimpleDocumentListener(() -> {
                element.setOperation(freshOperationField.getText());
            }));
            
        } else {
            titleLabel.setText("Transition");
            
            // Create fresh text fields for each selection
            JTextField freshLabelField = new JTextField(15);
            JTextField freshNodeTypeField = new JTextField(15);
            JTextField freshNodeValueField = new JTextField(15);
            
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
            
            // Node Type
            addField("Node Type:", freshNodeTypeField, element.getNodeType());
            freshNodeTypeField.getDocument().addDocumentListener(new SimpleDocumentListener(() -> {
                element.setNodeType(freshNodeTypeField.getText());
            }));
            
            // Node Value
            addField("Node Value:", freshNodeValueField, element.getNodeValue());
            freshNodeValueField.getDocument().addDocumentListener(new SimpleDocumentListener(() -> {
                element.setNodeValue(freshNodeValueField.getText());
            }));
        }
        
        attributesPanel.revalidate();
        attributesPanel.repaint();
    }
    
    private void showArrowAttributes(Arrow arrow) {
        titleLabel.setText("Arrow");
        attributesPanel.removeAll();
        
        // Create fresh text fields for each selection to avoid listener accumulation
        JTextField freshArrowLabelField = new JTextField(15);
        JTextField freshConditionField = new JTextField(15);
        JTextField freshDecisionField = new JTextField(15);
        
        // Label
        addField("Label:", freshArrowLabelField, arrow.getLabel());
        freshArrowLabelField.getDocument().addDocumentListener(new SimpleDocumentListener(() -> {
            arrow.setLabel(freshArrowLabelField.getText());
            canvas.repaint();
        }));
        
        // Condition
        addField("Condition:", freshConditionField, arrow.getCondition());
        freshConditionField.getDocument().addDocumentListener(new SimpleDocumentListener(() -> {
            arrow.setCondition(freshConditionField.getText());
        }));
        
        // Decision Value
        addField("Decision Value:", freshDecisionField, arrow.getDecisionValue());
        freshDecisionField.getDocument().addDocumentListener(new SimpleDocumentListener(() -> {
            arrow.setDecisionValue(freshDecisionField.getText());
        }));
        
        // Source (read-only)
        addLabel("Source:", arrow.getSource().getLabel());
        
        // Target (read-only)
        addLabel("Target:", arrow.getTarget().getLabel());
        
        attributesPanel.revalidate();
        attributesPanel.repaint();
    }
    
    private void addField(String labelText, JTextField field, String value) {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setMaximumSize(new Dimension(180, 60));
        panel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        
        JLabel label = new JLabel(labelText);
        label.setPreferredSize(new Dimension(180, 20));
        panel.add(label, BorderLayout.NORTH);
        
        field.setText(value);
        panel.add(field, BorderLayout.CENTER);
        
        attributesPanel.add(panel);
    }
    
    private void addLabel(String labelText, String value) {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setMaximumSize(new Dimension(180, 60));
        panel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        
        JLabel label = new JLabel(labelText);
        label.setPreferredSize(new Dimension(180, 20));
        panel.add(label, BorderLayout.NORTH);
        
        JLabel valueLabel = new JLabel(value);
        valueLabel.setBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY));
        valueLabel.setOpaque(true);
        valueLabel.setBackground(Color.WHITE);
        valueLabel.setPreferredSize(new Dimension(180, 25));
        panel.add(valueLabel, BorderLayout.CENTER);
        
        attributesPanel.add(panel);
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