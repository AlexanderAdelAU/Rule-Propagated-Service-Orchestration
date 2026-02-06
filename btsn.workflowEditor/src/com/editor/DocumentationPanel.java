package com.editor;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * Panel that displays workflow metadata and documentation from JSON.
 * Shows title, summary, topology, tokens, places, mutual exclusion info, etc.
 */
public class DocumentationPanel extends JPanel {
    
    // Stored documentation data
    private Map<String, String> metadata = new LinkedHashMap<>();
    private String title = "";
    private String summary = "";
    private String topologyDescription = "";
    private String topologyFlow = "";
    private String topologyLayout = "";
    private java.util.List<Map<String, String>> tokens = new java.util.ArrayList<>();
    private java.util.List<Map<String, String>> places = new java.util.ArrayList<>();
    private String mutexMechanism = "";
    private String mutexDescription = "";
    private java.util.List<String> mutexGuarantees = new java.util.ArrayList<>();
    private java.util.List<String> useCases = new java.util.ArrayList<>();
    
    // UI Components
    private JTextArea documentationArea;
    private JPanel contentPanel;
    private boolean hasDocumentation = false;
    
    // Edit mode components
    private boolean editMode = false;
    private JTextArea editTextArea;
    private JScrollPane editScrollPane;
    private JScrollPane displayScrollPane;
    private JButton editButton;
    private String editableText = "";
    
    public DocumentationPanel() {
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createTitledBorder("Documentation"));
        setPreferredSize(new Dimension(280, 400));
        
        // Create scrollable content area for display mode
        contentPanel = new JPanel();
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        contentPanel.setBackground(Color.WHITE);
        
        displayScrollPane = new JScrollPane(contentPanel);
        displayScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        displayScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        displayScrollPane.getVerticalScrollBar().setUnitIncrement(16);
        
        // Create edit text area for edit mode
        editTextArea = new JTextArea();
        editTextArea.setFont(new Font("Monospaced", Font.PLAIN, 11));
        editTextArea.setLineWrap(true);
        editTextArea.setWrapStyleWord(true);
        editTextArea.setMargin(new Insets(5, 5, 5, 5));
        
        editScrollPane = new JScrollPane(editTextArea);
        editScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        editScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        
        // Edit/Done button
        editButton = new JButton("Edit");
        editButton.setFont(new Font("SansSerif", Font.PLAIN, 11));
        editButton.setMargin(new Insets(2, 8, 2, 8));
        editButton.addActionListener(e -> toggleEditMode());
        
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 2));
        buttonPanel.add(editButton);
        
        add(displayScrollPane, BorderLayout.CENTER);
        add(buttonPanel, BorderLayout.SOUTH);
        
        // Show empty state initially
        showEmptyState();
    }
    
    /**
     * Toggle between edit and display modes
     */
    private void toggleEditMode() {
        if (editMode) {
            // Switch from edit to display mode
            editableText = editTextArea.getText();
            remove(editScrollPane);
            add(displayScrollPane, BorderLayout.CENTER);
            editButton.setText("Edit");
            editMode = false;
            
            // Re-render the display with edited text
            rebuildDisplayFromEditedText();
        } else {
            // Switch from display to edit mode
            editTextArea.setText(getEditableText());
            remove(displayScrollPane);
            add(editScrollPane, BorderLayout.CENTER);
            editButton.setText("Done");
            editMode = true;
            editTextArea.setCaretPosition(0);
        }
        revalidate();
        repaint();
    }
    
    /**
     * Rebuild the display from the user-edited text
     */
    private void rebuildDisplayFromEditedText() {
        contentPanel.removeAll();
        contentPanel.setBackground(Color.WHITE);
        
        if (editableText.isEmpty()) {
            showEmptyState();
            return;
        }
        
        String[] lines = editableText.split("\n");
        StringBuilder currentBlock = new StringBuilder();
        
        for (String line : lines) {
            if (line.startsWith("# ")) {
                // Flush current block
                if (currentBlock.length() > 0) {
                    addTextBlock(currentBlock.toString().trim());
                    currentBlock = new StringBuilder();
                }
                // Main header
                addSectionHeader(line.substring(2).trim());
            } else if (line.startsWith("## ")) {
                // Flush current block
                if (currentBlock.length() > 0) {
                    addTextBlock(currentBlock.toString().trim());
                    currentBlock = new StringBuilder();
                }
                // Sub header
                addSubHeader(line.substring(3).trim());
            } else {
                // Regular text - accumulate
                currentBlock.append(line).append("\n");
            }
        }
        
        // Flush final block
        if (currentBlock.length() > 0) {
            addTextBlock(currentBlock.toString().trim());
        }
        
        // Add some padding at the bottom
        contentPanel.add(Box.createVerticalStrut(20));
        
        contentPanel.revalidate();
        contentPanel.repaint();
    }
    
    /**
     * Get the current editable text (user-edited or generated from structured data)
     */
    public String getEditableText() {
        if (!editableText.isEmpty()) {
            return editableText;
        }
        return generateEditableText();
    }
    
    /**
     * Generate editable text from structured documentation data
     */
    private String generateEditableText() {
        StringBuilder sb = new StringBuilder();
        
        if (!title.isEmpty()) {
            sb.append("# ").append(title).append("\n\n");
        } else if (metadata.containsKey("name")) {
            sb.append("# ").append(metadata.get("name")).append("\n\n");
        }
        
        if (!metadata.isEmpty()) {
            sb.append("## Metadata\n");
            for (Map.Entry<String, String> entry : metadata.entrySet()) {
                if (!entry.getKey().equals("name")) {
                    sb.append("- ").append(capitalize(entry.getKey())).append(": ")
                      .append(entry.getValue()).append("\n");
                }
            }
            sb.append("\n");
        }
        
        if (!summary.isEmpty()) {
            sb.append("## Summary\n").append(summary).append("\n\n");
        }
        
        if (!topologyDescription.isEmpty() || !topologyFlow.isEmpty()) {
            sb.append("## Topology\n");
            if (!topologyDescription.isEmpty()) {
                sb.append(topologyDescription).append("\n");
            }
            if (!topologyFlow.isEmpty()) {
                sb.append("Flow: ").append(topologyFlow).append("\n");
            }
            if (!topologyLayout.isEmpty()) {
                sb.append("Layout: ").append(topologyLayout).append("\n");
            }
            sb.append("\n");
        }
        
        if (!tokens.isEmpty()) {
            sb.append("## Tokens\n");
            for (Map<String, String> token : tokens) {
                String id = token.get("id");
                String name = token.get("name");
                String priority = token.get("priority");
                String desc = token.get("description");
                sb.append("- ").append(id != null ? id : "?");
                if (name != null) sb.append(" (").append(name).append(")");
                if (priority != null) sb.append(" [").append(priority).append("]");
                sb.append("\n");
                if (desc != null && !desc.isEmpty()) {
                    sb.append("  ").append(desc).append("\n");
                }
            }
            sb.append("\n");
        }
        
        if (!places.isEmpty()) {
            sb.append("## Places\n");
            for (Map<String, String> place : places) {
                String id = place.get("id");
                String state = place.get("state");
                String desc = place.get("description");
                sb.append("- ").append(id != null ? id : "?");
                if (state != null) sb.append(" [").append(state).append("]");
                sb.append("\n");
                if (desc != null && !desc.isEmpty()) {
                    sb.append("  ").append(desc).append("\n");
                }
            }
            sb.append("\n");
        }
        
        if (!mutexMechanism.isEmpty() || !mutexDescription.isEmpty()) {
            sb.append("## Mutual Exclusion\n");
            if (!mutexMechanism.isEmpty()) {
                sb.append("Mechanism: ").append(mutexMechanism).append("\n");
            }
            if (!mutexDescription.isEmpty()) {
                sb.append(mutexDescription).append("\n");
            }
            if (!mutexGuarantees.isEmpty()) {
                sb.append("Guarantees:\n");
                for (String g : mutexGuarantees) {
                    sb.append("- ").append(g).append("\n");
                }
            }
            sb.append("\n");
        }
        
        if (!useCases.isEmpty()) {
            sb.append("## Use Cases\n");
            for (String uc : useCases) {
                sb.append("- ").append(uc).append("\n");
            }
            sb.append("\n");
        }
        
        return sb.toString();
    }
    
    /**
     * Clear all documentation data
     */
    public void clear() {
        metadata.clear();
        title = "";
        summary = "";
        topologyDescription = "";
        topologyFlow = "";
        topologyLayout = "";
        tokens.clear();
        places.clear();
        mutexMechanism = "";
        mutexDescription = "";
        mutexGuarantees.clear();
        useCases.clear();
        hasDocumentation = false;
        editableText = "";
        
        // Reset to display mode if in edit mode
        if (editMode) {
            remove(editScrollPane);
            add(displayScrollPane, BorderLayout.CENTER);
            editButton.setText("Edit");
            editMode = false;
        }
        
        showEmptyState();
    }
    
    /**
     * Show empty state when no documentation is loaded
     */
    private void showEmptyState() {
        contentPanel.removeAll();
        
        JLabel emptyLabel = new JLabel("<html><center>No documentation available.<br><br>Load a workflow with metadata<br>and documentation sections.</center></html>");
        emptyLabel.setForeground(Color.GRAY);
        emptyLabel.setHorizontalAlignment(SwingConstants.CENTER);
        emptyLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        emptyLabel.setBorder(BorderFactory.createEmptyBorder(20, 10, 20, 10));
        
        contentPanel.add(Box.createVerticalGlue());
        contentPanel.add(emptyLabel);
        contentPanel.add(Box.createVerticalGlue());
        
        contentPanel.revalidate();
        contentPanel.repaint();
    }
    
    /**
     * Parse and display documentation from JSON string
     */
    public void loadFromJSON(String json) {
        clear();
        
        // Check if metadata section exists
        if (json.contains("\"metadata\"")) {
            parseMetadata(json);
        }
        
        // Check if documentation section exists
        if (json.contains("\"documentation\"")) {
            parseDocumentation(json);
            hasDocumentation = true;
        }
        
        if (hasDocumentation || !metadata.isEmpty()) {
            rebuildDisplay();
        } else {
            showEmptyState();
        }
    }
    
    /**
     * Parse metadata section from JSON
     */
    private void parseMetadata(String json) {
        try {
            String metadataSection = extractSection(json, "\"metadata\"");
            if (metadataSection == null) return;
            
            // Extract common metadata fields
            String[] fields = {"name", "version", "author", "created", "type"};
            for (String field : fields) {
                String value = extractValue(metadataSection, field);
                if (value != null && !value.isEmpty()) {
                    metadata.put(field, value);
                }
            }
        } catch (Exception e) {
            System.err.println("Error parsing metadata: " + e.getMessage());
        }
    }
    
    /**
     * Parse documentation section from JSON
     */
    private void parseDocumentation(String json) {
        try {
            String docSection = extractSection(json, "\"documentation\"");
            if (docSection == null) return;
            
            // Extract simple fields
            title = extractValue(docSection, "title");
            if (title == null) title = "";
            
            summary = extractValue(docSection, "summary");
            if (summary == null) summary = "";
            
            // Extract topology
            String topologySection = extractSection(docSection, "\"topology\"");
            if (topologySection != null) {
                topologyDescription = extractValue(topologySection, "description");
                topologyFlow = extractValue(topologySection, "flow");
                topologyLayout = extractValue(topologySection, "layout");
                if (topologyDescription == null) topologyDescription = "";
                if (topologyFlow == null) topologyFlow = "";
                if (topologyLayout == null) topologyLayout = "";
            }
            
            // Extract tokens array
            String tokensSection = extractArraySection(docSection, "tokens");
            if (tokensSection != null) {
                String[] tokenBlocks = splitJSONObjects(tokensSection);
                for (String block : tokenBlocks) {
                    if (block.trim().isEmpty()) continue;
                    Map<String, String> token = new LinkedHashMap<>();
                    token.put("id", extractValue(block, "id"));
                    token.put("name", extractValue(block, "name"));
                    token.put("priority", extractValue(block, "priority"));
                    token.put("sequenceBase", extractValue(block, "sequenceBase"));
                    token.put("description", extractValue(block, "description"));
                    tokens.add(token);
                }
            }
            
            // Extract places array
            String placesSection = extractArraySection(docSection, "places");
            if (placesSection != null) {
                String[] placeBlocks = splitJSONObjects(placesSection);
                for (String block : placeBlocks) {
                    if (block.trim().isEmpty()) continue;
                    Map<String, String> place = new LinkedHashMap<>();
                    place.put("id", extractValue(block, "id"));
                    place.put("state", extractValue(block, "state"));
                    place.put("description", extractValue(block, "description"));
                    places.add(place);
                }
            }
            
            // Extract mutual exclusion
            String mutexSection = extractSection(docSection, "\"mutualExclusion\"");
            if (mutexSection != null) {
                mutexMechanism = extractValue(mutexSection, "mechanism");
                mutexDescription = extractValue(mutexSection, "description");
                if (mutexMechanism == null) mutexMechanism = "";
                if (mutexDescription == null) mutexDescription = "";
                
                String guaranteesSection = extractArraySection(mutexSection, "guarantees");
                if (guaranteesSection != null) {
                    String[] guaranteeValues = splitArrayValues(guaranteesSection);
                    for (String g : guaranteeValues) {
                        if (g != null && !g.trim().isEmpty()) {
                            mutexGuarantees.add(g.trim());
                        }
                    }
                }
            }
            
            // Extract use cases
            String useCasesSection = extractArraySection(docSection, "useCases");
            if (useCasesSection != null) {
                String[] useCaseValues = splitArrayValues(useCasesSection);
                for (String uc : useCaseValues) {
                    if (uc != null && !uc.trim().isEmpty()) {
                        useCases.add(uc.trim());
                    }
                }
            }
            
        } catch (Exception e) {
            System.err.println("Error parsing documentation: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Rebuild the display with parsed documentation
     */
    private void rebuildDisplay() {
        contentPanel.removeAll();
        contentPanel.setBackground(Color.WHITE);
        
        // Title
        if (!title.isEmpty()) {
            addSectionHeader(title);
        } else if (metadata.containsKey("name")) {
            addSectionHeader(metadata.get("name"));
        }
        
        // Metadata
        if (!metadata.isEmpty()) {
            addSubHeader("Metadata");
            StringBuilder metaText = new StringBuilder();
            for (Map.Entry<String, String> entry : metadata.entrySet()) {
                if (!entry.getKey().equals("name")) { // Skip name, used as title
                    metaText.append("* ").append(capitalize(entry.getKey())).append(": ")
                            .append(entry.getValue()).append("\n");
                }
            }
            addTextBlock(metaText.toString().trim());
        }
        
        // Summary
        if (!summary.isEmpty()) {
            addSubHeader("Summary");
            addTextBlock(summary);
        }
        
        // Topology
        if (!topologyDescription.isEmpty() || !topologyFlow.isEmpty()) {
            addSubHeader("Topology");
            StringBuilder topoText = new StringBuilder();
            if (!topologyDescription.isEmpty()) {
                topoText.append(topologyDescription).append("\n\n");
            }
            if (!topologyFlow.isEmpty()) {
                topoText.append("Flow: ").append(topologyFlow).append("\n");
            }
            if (!topologyLayout.isEmpty()) {
                topoText.append("Layout: ").append(topologyLayout);
            }
            addTextBlock(topoText.toString().trim());
        }
        
        // Tokens
        if (!tokens.isEmpty()) {
            addSubHeader("Tokens");
            for (Map<String, String> token : tokens) {
                StringBuilder tokenText = new StringBuilder();
                String id = token.get("id");
                String name = token.get("name");
                String priority = token.get("priority");
                String desc = token.get("description");
                
                tokenText.append("> ").append(id != null ? id : "?");
                if (name != null) tokenText.append(" - ").append(name);
                if (priority != null) tokenText.append(" [").append(priority).append("]");
                tokenText.append("\n");
                if (desc != null) tokenText.append("   ").append(desc);
                
                addTextBlock(tokenText.toString().trim());
            }
        }
        
        // Places
        if (!places.isEmpty()) {
            addSubHeader("Place States");
            for (Map<String, String> place : places) {
                StringBuilder placeText = new StringBuilder();
                String id = place.get("id");
                String state = place.get("state");
                String desc = place.get("description");
                
                placeText.append("o ").append(id != null ? id : "?");
                if (state != null) placeText.append(": ").append(state);
                placeText.append("\n");
                if (desc != null) placeText.append("   ").append(desc);
                
                addTextBlock(placeText.toString().trim());
            }
        }
        
        // Mutual Exclusion
        if (!mutexMechanism.isEmpty() || !mutexDescription.isEmpty()) {
            addSubHeader("Mutual Exclusion");
            StringBuilder mutexText = new StringBuilder();
            if (!mutexMechanism.isEmpty()) {
                mutexText.append("Mechanism: ").append(mutexMechanism).append("\n\n");
            }
            if (!mutexDescription.isEmpty()) {
                mutexText.append(mutexDescription).append("\n");
            }
            if (!mutexGuarantees.isEmpty()) {
                mutexText.append("\nGuarantees:\n");
                for (String g : mutexGuarantees) {
                    mutexText.append("  [OK] ").append(g).append("\n");
                }
            }
            addTextBlock(mutexText.toString().trim());
        }
        
        // Use Cases
        if (!useCases.isEmpty()) {
            addSubHeader("Use Cases");
            StringBuilder ucText = new StringBuilder();
            for (String uc : useCases) {
                ucText.append("* ").append(uc).append("\n");
            }
            addTextBlock(ucText.toString().trim());
        }
        
        // Add some padding at the bottom
        contentPanel.add(Box.createVerticalStrut(20));
        
        contentPanel.revalidate();
        contentPanel.repaint();
    }
    
    /**
     * Add a main section header
     */
    private void addSectionHeader(String text) {
        JLabel label = new JLabel(text);
        label.setFont(new Font("SansSerif", Font.BOLD, 14));
        label.setForeground(new Color(0, 100, 150));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setBorder(BorderFactory.createEmptyBorder(10, 10, 5, 10));
        contentPanel.add(label);
        
        // Add separator line
        JSeparator separator = new JSeparator();
        separator.setMaximumSize(new Dimension(Integer.MAX_VALUE, 2));
        separator.setAlignmentX(Component.LEFT_ALIGNMENT);
        contentPanel.add(separator);
    }
    
    /**
     * Add a sub-section header
     */
    private void addSubHeader(String text) {
        JLabel label = new JLabel(text);
        label.setFont(new Font("SansSerif", Font.BOLD, 11));
        label.setForeground(new Color(80, 80, 80));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setBorder(BorderFactory.createEmptyBorder(12, 10, 3, 10));
        contentPanel.add(label);
    }
    
    /**
     * Add a text block
     */
    private void addTextBlock(String text) {
        JTextArea textArea = new JTextArea(text);
        textArea.setFont(new Font("SansSerif", Font.PLAIN, 11));
        textArea.setEditable(false);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        textArea.setBackground(Color.WHITE);
        textArea.setBorder(BorderFactory.createEmptyBorder(2, 15, 5, 10));
        textArea.setAlignmentX(Component.LEFT_ALIGNMENT);
        
        // Calculate preferred height based on content
        textArea.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        
        contentPanel.add(textArea);
    }
    
    /**
     * Capitalize first letter of a string
     */
    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }
    
    /**
     * Check if documentation is available
     */
    public boolean hasDocumentation() {
        return hasDocumentation || !metadata.isEmpty() || !editableText.isEmpty();
    }
    
    /**
     * Get the raw documentation as formatted text (for export)
     * Returns user-edited text if available, otherwise generates from structured data
     */
    public String getFormattedText() {
        // If user has edited the text, return that
        if (!editableText.isEmpty()) {
            return editableText;
        }
        
        // Otherwise generate from structured data
        StringBuilder sb = new StringBuilder();
        
        if (!title.isEmpty()) {
            sb.append("=== ").append(title).append(" ===\n\n");
        }
        
        if (!summary.isEmpty()) {
            sb.append(summary).append("\n\n");
        }
        
        if (!topologyFlow.isEmpty()) {
            sb.append("Topology: ").append(topologyFlow).append("\n\n");
        }
        
        if (!tokens.isEmpty()) {
            sb.append("Tokens:\n");
            for (Map<String, String> token : tokens) {
                sb.append("  - ").append(token.get("id")).append(": ")
                  .append(token.get("name")).append(" (").append(token.get("priority")).append(")\n");
            }
            sb.append("\n");
        }
        
        return sb.toString();
    }
    
    /**
     * Set the documentation text directly (for loading saved edits)
     */
    public void setEditableText(String text) {
        this.editableText = text != null ? text : "";
    }
    
    // ========== JSON Parsing Helpers (duplicated from Canvas for standalone use) ==========
    
    private String extractSection(String json, String key) {
        int start = json.indexOf(key);
        if (start == -1) return null;
        
        start = json.indexOf("{", start);
        if (start == -1) return null;
        
        int braceCount = 0;
        int end = start;
        for (int i = start; i < json.length(); i++) {
            if (json.charAt(i) == '{') {
                braceCount++;
            } else if (json.charAt(i) == '}') {
                braceCount--;
                if (braceCount == 0) {
                    end = i;
                    break;
                }
            }
        }
        
        return json.substring(start, end + 1);
    }
    
    private String extractArraySection(String block, String key) {
        String pattern = "\"" + key + "\"";
        int start = block.indexOf(pattern);
        if (start == -1) return null;
        
        start = block.indexOf("[", start);
        if (start == -1) return null;
        
        int bracketCount = 0;
        int end = start;
        for (int i = start; i < block.length(); i++) {
            if (block.charAt(i) == '[') {
                bracketCount++;
            } else if (block.charAt(i) == ']') {
                bracketCount--;
                if (bracketCount == 0) {
                    end = i;
                    break;
                }
            }
        }
        
        return block.substring(start + 1, end);
    }
    
    private String[] splitJSONObjects(String section) {
        java.util.List<String> objects = new java.util.ArrayList<>();
        int braceCount = 0;
        int start = 0;
        
        for (int i = 0; i < section.length(); i++) {
            char c = section.charAt(i);
            if (c == '{') {
                if (braceCount == 0) start = i;
                braceCount++;
            } else if (c == '}') {
                braceCount--;
                if (braceCount == 0) {
                    objects.add(section.substring(start, i + 1));
                }
            }
        }
        
        return objects.toArray(new String[0]);
    }
    
    private String extractValue(String block, String key) {
        String pattern = "\"" + key + "\"";
        int start = block.indexOf(pattern);
        if (start == -1) return null;
        
        start = block.indexOf(":", start) + 1;
        while (start < block.length() && Character.isWhitespace(block.charAt(start))) start++;
        
        if (start >= block.length()) return null;
        
        if (block.charAt(start) == '"') {
            // String value
            start++;
            int end = start;
            while (end < block.length()) {
                if (block.charAt(end) == '"' && block.charAt(end - 1) != '\\') break;
                end++;
            }
            return block.substring(start, end)
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
        } else {
            // Number or other value
            int end = start;
            while (end < block.length() && 
                   (Character.isDigit(block.charAt(end)) || 
                    block.charAt(end) == '-' || 
                    block.charAt(end) == '.' ||
                    Character.isLetter(block.charAt(end)))) {
                end++;
            }
            return block.substring(start, end).trim();
        }
    }
    
    private String[] splitArrayValues(String arrayContent) {
        java.util.List<String> values = new java.util.ArrayList<>();
        boolean inQuotes = false;
        int start = 0;
        
        for (int i = 0; i < arrayContent.length(); i++) {
            char c = arrayContent.charAt(i);
            if (c == '"' && (i == 0 || arrayContent.charAt(i - 1) != '\\')) {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                String value = arrayContent.substring(start, i).trim();
                if (value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                value = value.replace("\\n", "\n").replace("\\\"", "\"");
                if (!value.isEmpty()) {
                    values.add(value);
                }
                start = i + 1;
            }
        }
        
        // Add last value
        if (start < arrayContent.length()) {
            String value = arrayContent.substring(start).trim();
            if (value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            value = value.replace("\\n", "\n").replace("\\\"", "\"");
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        
        return values.toArray(new String[0]);
    }
}