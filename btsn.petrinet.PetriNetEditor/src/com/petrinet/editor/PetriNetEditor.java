package com.petrinet.editor;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;

/**
 * Main application window for Petri Net Editor
 */
public class PetriNetEditor extends JFrame {
    private PetriNetCanvas canvas;
    private PetriNetEditorFrame attributesFrame;
    private UndoRedoManager undoRedoManager;
    private JButton placeButton;
    private JButton transitionButton;
    private JButton arrowButton;
    private JButton clearButton;
    private JButton cancelButton;
    
    // Remember last directory for file dialogs
    private static File lastDirectory = null;
    
    public PetriNetEditor() {
        super("Petri Net Editor - Export to Graphviz");
        
        initComponents();
        setupLayout();
        setupMenuBar();
        
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        pack();
        setLocationRelativeTo(null);
    }
    
    private void initComponents() {
        canvas = new PetriNetCanvas();
        
        // Undo/Redo manager
        undoRedoManager = new UndoRedoManager(canvas);
        canvas.setUndoRedoManager(undoRedoManager);
        
        // Attributes panel
        attributesFrame = new PetriNetEditorFrame(canvas);
        canvas.setSelectionListener(attributesFrame::updateSelection);
        
        // Palette buttons
        placeButton = createPaletteButton("Place (Circle)", 
            PetriNetElement.Type.PLACE);
        transitionButton = createPaletteButton("Transition (Box)", 
            PetriNetElement.Type.TRANSITION);
        arrowButton = createToolButton("Connect (Arrow)", 
            e -> {
                canvas.setConnectMode(true);
                resetButtonColors();
                arrowButton.setBackground(new Color(255, 200, 200));
            });
        cancelButton = createToolButton("Cancel / Normal Mode", 
            e -> {
                canvas.setConnectMode(false);
                canvas.setSelectedType(null);
                canvas.clearSelection();
                resetButtonColors();
            });
        clearButton = createToolButton("Clear All", 
            e -> clearCanvas());
    }
    
    private JButton createPaletteButton(String text, PetriNetElement.Type type) {
        JButton button = new JButton(text);
        button.setFocusPainted(false);
        button.addActionListener(e -> {
            canvas.setSelectedType(type);
            resetButtonColors();
            button.setBackground(new Color(200, 220, 255));
        });
        return button;
    }
    
    private JButton createToolButton(String text, ActionListener listener) {
        JButton button = new JButton(text);
        button.setFocusPainted(false);
        button.addActionListener(listener);
        return button;
    }
    
    private void resetButtonColors() {
        placeButton.setBackground(null);
        transitionButton.setBackground(null);
        arrowButton.setBackground(null);
        cancelButton.setBackground(null);
    }
    
    private void setupLayout() {
        setLayout(new BorderLayout(10, 10));
        
        // Palette panel on the left
        JPanel palettePanel = new JPanel();
        palettePanel.setLayout(new BoxLayout(palettePanel, BoxLayout.Y_AXIS));
        palettePanel.setBorder(BorderFactory.createTitledBorder("Palette"));
        palettePanel.setPreferredSize(new Dimension(180, 350));
        palettePanel.setMaximumSize(new Dimension(180, 350));
        
        // Add spacing and buttons
        palettePanel.add(Box.createVerticalStrut(10));
        
        JLabel instructionLabel = new JLabel("<html><center>Click a shape,<br>then click canvas</center></html>");
        instructionLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        palettePanel.add(instructionLabel);
        
        palettePanel.add(Box.createVerticalStrut(20));
        
        addButtonToPanel(palettePanel, placeButton);
        palettePanel.add(Box.createVerticalStrut(10));
        
        addButtonToPanel(palettePanel, transitionButton);
        palettePanel.add(Box.createVerticalStrut(10));
        
        addButtonToPanel(palettePanel, arrowButton);
        palettePanel.add(Box.createVerticalStrut(10));
        
        addButtonToPanel(palettePanel, cancelButton);
        palettePanel.add(Box.createVerticalStrut(20));
        
        JSeparator separator = new JSeparator();
        separator.setMaximumSize(new Dimension(160, 1));
        palettePanel.add(separator);
        palettePanel.add(Box.createVerticalStrut(10));
        
        addButtonToPanel(palettePanel, clearButton);
        
        // Create left panel with palette and attributes
        JPanel leftPanel = new JPanel(new BorderLayout());
        leftPanel.add(palettePanel, BorderLayout.NORTH);
        leftPanel.add(attributesFrame, BorderLayout.CENTER);
        
        add(leftPanel, BorderLayout.WEST);
        
        // Canvas in center
        JScrollPane scrollPane = new JScrollPane(canvas);
        add(scrollPane, BorderLayout.CENTER);
        
        // Status bar
        JLabel statusLabel = new JLabel(" Ready - Draw your Petri net and export to Graphviz");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        add(statusLabel, BorderLayout.SOUTH);
    }
    
    private void addButtonToPanel(JPanel panel, JButton button) {
        button.setAlignmentX(Component.CENTER_ALIGNMENT);
        button.setMaximumSize(new Dimension(160, 40));
        panel.add(button);
    }
    
    private void setupMenuBar() {
        JMenuBar menuBar = new JMenuBar();
        
        // File menu
        JMenu fileMenu = new JMenu("File");
        
        JMenuItem saveItem = new JMenuItem("Save (.json)");
        saveItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, 
            InputEvent.CTRL_DOWN_MASK));
        saveItem.addActionListener(e -> saveToJSON());
        fileMenu.add(saveItem);
        
        JMenuItem loadItem = new JMenuItem("Load (.json)");
        loadItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, 
            InputEvent.CTRL_DOWN_MASK));
        loadItem.addActionListener(e -> loadFromJSON());
        fileMenu.add(loadItem);
        
        fileMenu.addSeparator();
        
        JMenuItem exportItem = new JMenuItem("Export to Graphviz (.dot)");
        exportItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_E, 
            InputEvent.CTRL_DOWN_MASK));
        exportItem.addActionListener(e -> exportToGraphviz());
        fileMenu.add(exportItem);
        
        JMenuItem exportTikZItem = new JMenuItem("Export to TikZ (.tex)");
        exportTikZItem.addActionListener(e -> exportToTikZ());
        fileMenu.add(exportTikZItem);
        
        JMenuItem exportPDFItem = new JMenuItem("Export to PDF (.pdf)");
        exportPDFItem.addActionListener(e -> exportToPDF());
        fileMenu.add(exportPDFItem);
        
        fileMenu.addSeparator();
        
        JMenuItem exitItem = new JMenuItem("Exit");
        exitItem.addActionListener(e -> System.exit(0));
        fileMenu.add(exitItem);
        
        menuBar.add(fileMenu);
        
        // Edit menu
        JMenu editMenu = new JMenu("Edit");
        
        JMenuItem undoItem = new JMenuItem("Undo");
        undoItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, 
            InputEvent.CTRL_DOWN_MASK));
        undoItem.addActionListener(e -> undoRedoManager.undo());
        editMenu.add(undoItem);
        
        JMenuItem redoItem = new JMenuItem("Redo");
        redoItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Y, 
            InputEvent.CTRL_DOWN_MASK));
        redoItem.addActionListener(e -> undoRedoManager.redo());
        editMenu.add(redoItem);
        
        menuBar.add(editMenu);
        
        // Help menu
        JMenu helpMenu = new JMenu("Help");
        
        JMenuItem instructionsItem = new JMenuItem("Instructions");
        instructionsItem.addActionListener(e -> showInstructions());
        helpMenu.add(instructionsItem);
        
        JMenuItem aboutItem = new JMenuItem("About");
        aboutItem.addActionListener(e -> showAbout());
        helpMenu.add(aboutItem);
        
        menuBar.add(helpMenu);
        
        setJMenuBar(menuBar);
    }
    
    private void exportToGraphviz() {
        if (canvas.getElements().isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "Canvas is empty! Add some elements first.",
                "Nothing to Export",
                JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        
        JFileChooser fileChooser = new JFileChooser();
        if (lastDirectory != null) {
            fileChooser.setCurrentDirectory(lastDirectory);
        }
        fileChooser.setDialogTitle("Export to Graphviz DOT File");
        fileChooser.setSelectedFile(new File("petri_net.dot"));
        
        int result = fileChooser.showSaveDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();
            lastDirectory = file.getParentFile();
            try (FileWriter writer = new FileWriter(file)) {
                writer.write(canvas.exportToGraphviz());
                JOptionPane.showMessageDialog(this,
                    "Exported successfully!\n\nTo generate image, run:\n" +
                    "dot -Tpng " + file.getName() + " -o output.png",
                    "Export Successful",
                    JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this,
                    "Error saving file: " + ex.getMessage(),
                    "Export Error",
                    JOptionPane.ERROR_MESSAGE);
            }
        }
    }
    
    private void exportToTikZ() {
        if (canvas.getElements().isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "Canvas is empty! Add some elements first.",
                "Nothing to Export",
                JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        
        JFileChooser fileChooser = new JFileChooser();
        if (lastDirectory != null) {
            fileChooser.setCurrentDirectory(lastDirectory);
        }
        fileChooser.setDialogTitle("Export to TikZ File");
        fileChooser.setSelectedFile(new File("petri_net.tex"));
        
        int result = fileChooser.showSaveDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();
            lastDirectory = file.getParentFile();
            try (FileWriter writer = new FileWriter(file)) {
                String tikz = TikZExporter.export(canvas.getElements(), canvas.getArrows());
                writer.write(tikz);
                JOptionPane.showMessageDialog(this,
                    "Exported successfully!\n\n" +
                    "Include in your LaTeX document with:\n" +
                    "\\usepackage{tikz}\n" +
                    "\\input{" + file.getName() + "}",
                    "Export Successful",
                    JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this,
                    "Error saving file: " + ex.getMessage(),
                    "Export Error",
                    JOptionPane.ERROR_MESSAGE);
            }
        }
    }
    
    private void exportToPDF() {
        if (canvas.getElements().isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "Canvas is empty! Add some elements first.",
                "Nothing to Export",
                JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        
        JFileChooser fileChooser = new JFileChooser();
        if (lastDirectory != null) {
            fileChooser.setCurrentDirectory(lastDirectory);
        }
        fileChooser.setDialogTitle("Export to PDF");
        fileChooser.setSelectedFile(new File("petri_net.pdf"));
        
        int result = fileChooser.showSaveDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();
            lastDirectory = file.getParentFile();
            try {
                PDFExporter.export(canvas.getElements(), canvas.getArrows(), file);
                JOptionPane.showMessageDialog(this,
                    "Exported successfully to PDF!",
                    "Export Successful",
                    JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this,
                    "Error exporting to PDF: " + ex.getMessage() + "\n\n" +
                    "Note: PDF export requires iTextPDF library.\n" +
                    "If not installed, use TikZ or Graphviz export instead.",
                    "Export Error",
                    JOptionPane.ERROR_MESSAGE);
                ex.printStackTrace();
            }
        }
    }
    
    private void saveToJSON() {
        if (canvas.getElements().isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "Canvas is empty! Nothing to save.",
                "Nothing to Save",
                JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        
        JFileChooser fileChooser = new JFileChooser();
        if (lastDirectory != null) {
            fileChooser.setCurrentDirectory(lastDirectory);
        }
        fileChooser.setDialogTitle("Save Petri Net");
        fileChooser.setSelectedFile(new File("petri_net.json"));
        
        int result = fileChooser.showSaveDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();
            lastDirectory = file.getParentFile();
            try (FileWriter writer = new FileWriter(file)) {
                writer.write(canvas.saveToJSON());
                JOptionPane.showMessageDialog(this,
                    "Saved successfully!",
                    "Save Successful",
                    JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this,
                    "Error saving file: " + ex.getMessage(),
                    "Save Error",
                    JOptionPane.ERROR_MESSAGE);
            }
        }
    }
    
    private void loadFromJSON() {
        JFileChooser fileChooser = new JFileChooser();
        if (lastDirectory != null) {
            fileChooser.setCurrentDirectory(lastDirectory);
        }
        fileChooser.setDialogTitle("Load Petri Net");
        
        int result = fileChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();
            lastDirectory = file.getParentFile();
            try {
                StringBuilder content = new StringBuilder();
                try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(file))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        content.append(line).append("\n");
                    }
                }
                canvas.loadFromJSON(content.toString());
                undoRedoManager.clear();  // Clear undo/redo history after loading new file
                JOptionPane.showMessageDialog(this,
                    "Loaded successfully!",
                    "Load Successful",
                    JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this,
                    "Error loading file: " + ex.getMessage(),
                    "Load Error",
                    JOptionPane.ERROR_MESSAGE);
                ex.printStackTrace();
            }
        }
    }
    
    private void clearCanvas() {
        int result = JOptionPane.showConfirmDialog(this,
            "Clear all elements from the canvas?",
            "Confirm Clear",
            JOptionPane.YES_NO_OPTION);
        
        if (result == JOptionPane.YES_OPTION) {
            canvas.clear();
            resetButtonColors();
        }
    }
    
    private void showInstructions() {
        String instructions = 
            "How to Use Petri Net Editor:\n\n" +
            "1. Click 'Place (Circle)' or 'Transition (Box)' in the palette\n" +
            "2. Click on the canvas to add the shape\n" +
            "3. Double-click element to edit attributes\n" +
            "4. Click 'Connect (Arrow)' then click source->target\n" +
            "5. Double-click arrow to add control point (bend arrow)\n" +
            "6. Drag control points to reshape arrows\n" +
            "7. Right-click control point to delete it\n" +
            "8. Drag elements to reposition them\n" +
            "9. Right-click element/arrow for menu (Edit/Delete)\n" +
            "10. Save your work (File > Save) as JSON\n" +
            "11. Load saved work (File > Load)\n" +
            "12. Export to Graphviz (File > Export) for rendering\n\n" +
            "Keyboard Shortcuts:\n" +
            "Ctrl+S - Save\n" +
            "Ctrl+O - Load\n" +
            "Ctrl+E - Export to Graphviz\n" +
            "Delete - Delete selected element\n" +
            "Escape - Cancel/Clear selection";
        
        JOptionPane.showMessageDialog(this,
            instructions,
            "Instructions",
            JOptionPane.INFORMATION_MESSAGE);
    }
    
    private void showAbout() {
        JOptionPane.showMessageDialog(this,
            "Petri Net Editor\n\n" +
            "A simple visual editor for Petri nets\n" +
            "Export to Graphviz DOT format\n\n" +
            "Version 1.0",
            "About",
            JOptionPane.INFORMATION_MESSAGE);
    }
    
    public static void main(String[] args) {
        // Set look and feel
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            // Use default look and feel
        }
        
        SwingUtilities.invokeLater(() -> {
            PetriNetEditor editor = new PetriNetEditor();
            editor.setVisible(true);
        });
    }
}