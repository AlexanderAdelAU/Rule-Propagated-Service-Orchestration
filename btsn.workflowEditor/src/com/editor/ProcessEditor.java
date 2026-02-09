package com.editor;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

/**
 * Main application window for Petri Net Editor with icon-based palette
 */
public class ProcessEditor extends JFrame {
    private Canvas canvas;
    private EditorFrame attributesFrame;
    private DocumentationPanel documentationPanel;
    private JPanel docPanelContainer;  // Container with close button
    private JMenuItem toggleDocPanelItem;  // Menu item to show/hide
    private AnimationControlPanel animationPanel;
    private UndoRedoManager undoRedoManager;
    private JButton placeButton;
    private JButton transitionButton;
    private JButton eventGenButton;
    private JButton arrowButton;
    private JButton clickArrowButton;
    private JButton textButton;
    private JButton clearButton;
    private JButton validateButton;
    private JLabel statusLabel;
    
    // Process type selector
    private JComboBox<String> processTypeCombo;
    
    // Zoom label
    private JLabel zoomLabel;
    
    // Recent colors palette
    private JPanel colorPalettePanel;
    private List<ColorPair> recentColors = new ArrayList<>();
    private static final int MAX_RECENT_COLORS = 5;
    
    /**
     * Represents a fill/border color combination
     */
    public static class ColorPair {
        public final Color fillColor;
        public final Color borderColor;
        
        public ColorPair(Color fill, Color border) {
            this.fillColor = fill;
            this.borderColor = border;
        }
        
        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof ColorPair)) return false;
            ColorPair other = (ColorPair) obj;
            return colorsEqual(fillColor, other.fillColor) && 
                   colorsEqual(borderColor, other.borderColor);
        }
        
        private boolean colorsEqual(Color a, Color b) {
            if (a == null && b == null) return true;
            if (a == null || b == null) return false;
            return a.equals(b);
        }
        
        @Override
        public int hashCode() {
            int h1 = fillColor != null ? fillColor.hashCode() : 0;
            int h2 = borderColor != null ? borderColor.hashCode() : 0;
            return h1 * 31 + h2;
        }
    }
    
    // Track unsaved changes
    private boolean isDirty = false;
    
    // Remember current file for save operations
    private File currentFile = null;
    
    // Remember last directory for file dialogs
    private static File lastDirectory = null;
    
    // Preferences for persisting last directory across sessions
    private static final Preferences prefs = Preferences.userNodeForPackage(ProcessEditor.class);
    private static final String LAST_DIRECTORY_KEY = "lastDirectory";
    
    public ProcessEditor() {
        super("Petri Net Editor - Export to Graphviz");
        
        // Load last directory from preferences
        loadLastDirectory();
        
        initComponents();
        setupLayout();
        setupMenuBar();
        
        // Initialize status bar
        updateStatusBar();
        
        // Handle window closing with unsaved changes check
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (checkUnsavedChanges()) {
                    System.exit(0);
                }
            }
        });
        
        // Set window size using golden ratio (1:1.618)
        // Height based on 75% of screen height, width = height * 1.618
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        int height = (int)(screenSize.height * 0.75);
        int width = (int)(height * 1.618);
        setSize(width, height);
        setLocationRelativeTo(null);
    }
    
    /**
     * Check for unsaved changes before closing
     * @return true if OK to proceed, false to cancel
     */
    private boolean checkUnsavedChanges() {
        if (!isDirty) {
            return true; // No changes, OK to proceed
        }
        
        int result = JOptionPane.showConfirmDialog(this,
            "You have unsaved changes. Do you want to save before exiting?",
            "Unsaved Changes",
            JOptionPane.YES_NO_CANCEL_OPTION,
            JOptionPane.WARNING_MESSAGE);
        
        if (result == JOptionPane.YES_OPTION) {
            // Save and exit if save succeeds
            saveToJSON();
            return !isDirty; // Only exit if save cleared the dirty flag
        } else if (result == JOptionPane.NO_OPTION) {
            return true; // Exit without saving
        } else {
            return false; // Cancel - don't exit
        }
    }
    
    /**
     * Mark canvas as modified
     */
    private void setDirty(boolean dirty) {
        isDirty = dirty;
        updateTitle();
    }
    
    /**
     * Update window title to show dirty state
     */
    private void updateTitle() {
        String title = "Petri Net Editor - Export to Graphviz";
        if (isDirty) {
            title += " *";
        }
        setTitle(title);
    }
    
    /**
     * Update status bar to show current filename
     */
    private void updateStatusBar() {
        if (currentFile != null) {
            statusLabel.setText(" Ready - FileName: " + currentFile.getName());
        } else {
            statusLabel.setText(" Ready - No file loaded");
        }
    }
    
    /**
     * Load last directory from preferences
     */
    private void loadLastDirectory() {
        String lastDirPath = prefs.get(LAST_DIRECTORY_KEY, null);
        if (lastDirPath != null) {
            File dir = new File(lastDirPath);
            if (dir.exists() && dir.isDirectory()) {
                lastDirectory = dir;
            }
        }
    }
    
    /**
     * Save last directory to preferences
     */
    private void saveLastDirectory(File directory) {
        if (directory != null && directory.isDirectory()) {
            lastDirectory = directory;
            prefs.put(LAST_DIRECTORY_KEY, directory.getAbsolutePath());
        }
    }
    
    /**
     * Create an icon for a Place (circle)
     */
    private Icon createPlaceIcon() {
        return new Icon() {
            public int getIconWidth() { return 28; }
            public int getIconHeight() { return 28; }
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(220, 240, 255));
                g2.fillOval(x + 4, y + 4, 20, 20);
                g2.setColor(Color.BLACK);
                g2.setStroke(new BasicStroke(1.5f));
                g2.drawOval(x + 4, y + 4, 20, 20);
            }
        };
    }
    
    /**
     * Create an icon for a Transition (rectangle)
     */
    private Icon createTransitionIcon() {
        return new Icon() {
            public int getIconWidth() { return 28; }
            public int getIconHeight() { return 28; }
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(Color.BLACK);
                g2.setStroke(new BasicStroke(1.5f));
                g2.drawRect(x + 9, y + 4, 10, 20);
            }
        };
    }
    
    /**
     * Create an icon for Event Generator (rectangle with arrow) - same size as Transition
     */
    private Icon createEventGenIcon() {
        return new Icon() {
            public int getIconWidth() { return 28; }
            public int getIconHeight() { return 28; }
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                
                // Light green fill
                g2.setColor(new Color(220, 255, 220));
                g2.fillRect(x + 6, y + 4, 10, 20);
                
                // Black border
                g2.setColor(Color.BLACK);
                g2.setStroke(new BasicStroke(1.5f));
                g2.drawRect(x + 6, y + 4, 10, 20);
                
                // Arrow pointing right from the rectangle
                g2.setStroke(new BasicStroke(1.5f));
                g2.drawLine(x + 16, y + 14, x + 24, y + 14);
                g2.drawLine(x + 24, y + 14, x + 20, y + 10);
                g2.drawLine(x + 24, y + 14, x + 20, y + 18);
            }
        };
    }
    
    /**
     * Create an icon for Arrow connection
     */
    private Icon createArrowIcon() {
        return new Icon() {
            public int getIconWidth() { return 28; }
            public int getIconHeight() { return 28; }
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(Color.BLACK);
                g2.setStroke(new BasicStroke(1.5f));
                
                // Draw arrow line
                g2.drawLine(x + 4, y + 14, x + 22, y + 14);
                
                // Draw arrowhead
                g2.drawLine(x + 22, y + 14, x + 16, y + 9);
                g2.drawLine(x + 22, y + 14, x + 16, y + 19);
            }
        };
    }
    
    /**
     * Create an icon for click-to-connect Arrow button (with waypoint dots)
     */
    private Icon createClickArrowIcon() {
        return new Icon() {
            public int getIconWidth() { return 28; }
            public int getIconHeight() { return 28; }
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(Color.BLACK);
                g2.setStroke(new BasicStroke(1.5f));
                
                // Draw bent arrow line with waypoint
                g2.drawLine(x + 4, y + 22, x + 14, y + 10);  // First segment
                g2.drawLine(x + 14, y + 10, x + 24, y + 10); // Second segment
                
                // Draw waypoint dot
                g2.setColor(new Color(0, 100, 255));
                g2.fillOval(x + 11, y + 7, 6, 6);
                
                // Draw arrowhead
                g2.setColor(Color.BLACK);
                g2.drawLine(x + 24, y + 10, x + 19, y + 6);
                g2.drawLine(x + 24, y + 10, x + 19, y + 14);
            }
        };
    }
    
    /**
     * Create an icon for Text button
     */
    private Icon createTextIcon() {
        return new Icon() {
            public int getIconWidth() { return 28; }
            public int getIconHeight() { return 28; }
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(Color.BLACK);
                g2.setFont(new Font("Serif", Font.BOLD, 18));
                g2.drawString("T", x + 9, y + 20);
            }
        };
    }
    
    private void initComponents() {
        canvas = new Canvas();
        
        // Undo/Redo manager
        undoRedoManager = new UndoRedoManager(canvas);
        canvas.setUndoRedoManager(undoRedoManager);
        
        // Track changes for dirty flag
        canvas.setChangeListener(() -> setDirty(true));
        
        // Reset buttons when canvas cancels modes
        canvas.setCancelListener(() -> resetButtonColors());
        
        // Attributes panel
        attributesFrame = new EditorFrame(canvas);
        canvas.setSelectionListener(attributesFrame::updateSelection);
        
        // Listen for color changes from attributes panel
        attributesFrame.setColorChangeListener(this::addRecentColor);
        
        // Documentation panel with close button (hidden at startup)
        documentationPanel = new DocumentationPanel();
        docPanelContainer = createDocPanelContainer();
        docPanelContainer.setVisible(false);  // Hidden by default, show via View menu
        
        // Animation control panel
        animationPanel = new AnimationControlPanel(canvas);
        
        // Create icon-based palette buttons
        placeButton = createIconPaletteButton(createPlaceIcon(), "Place", 
            ProcessElement.Type.PLACE);
        transitionButton = createIconPaletteButton(createTransitionIcon(), "Transition", 
            ProcessElement.Type.TRANSITION);
        eventGenButton = createIconPaletteButton(createEventGenIcon(), "Event Generator", 
            ProcessElement.Type.EVENT_GENERATOR);
        arrowButton = createIconToolButton(createArrowIcon(), "Arrow (drag)", 
            e -> {
                canvas.setConnectMode(true);
                resetButtonColors();
                arrowButton.setBackground(new Color(255, 200, 200));
                arrowButton.setContentAreaFilled(true);
            });
        clickArrowButton = createIconToolButton(createClickArrowIcon(), "Arrow (click waypoints)", 
            e -> {
                canvas.setClickConnectMode(true);
                resetButtonColors();
                clickArrowButton.setBackground(new Color(255, 200, 200));
                clickArrowButton.setContentAreaFilled(true);
            });
        textButton = createIconToolButton(createTextIcon(), "Text", 
            e -> {
                canvas.setTextMode(true);
                resetButtonColors();
                textButton.setBackground(new Color(255, 200, 200));
                textButton.setContentAreaFilled(true);
            });
        validateButton = createToolButton("Validate", 
            e -> validateCanvas());
        clearButton = createToolButton("Clear All", 
            e -> clearCanvas());
        
        // Process type dropdown
        processTypeCombo = new JComboBox<>(new String[]{"", "PetriNet", "SOA"});
        processTypeCombo.setToolTipText("Process Type (PetriNet or SOA)");
        processTypeCombo.setPreferredSize(new Dimension(100, 28));
        processTypeCombo.setMinimumSize(new Dimension(100, 28));
        processTypeCombo.setMaximumSize(new Dimension(100, 28));
        processTypeCombo.addActionListener(e -> {
            String selectedType = (String) processTypeCombo.getSelectedItem();
            canvas.setProcessType(selectedType != null && !selectedType.isEmpty() ? selectedType : null);
        });
        
        // Zoom change listener to update zoom label
        canvas.setZoomChangeListener(() -> updateZoomLabel());
    }
    
    /**
     * Update the zoom label to show current zoom level
     */
    private void updateZoomLabel() {
        if (zoomLabel != null) {
            int zoomPercent = (int) Math.round(canvas.getZoomScale() * 100);
            zoomLabel.setText(zoomPercent + "%");
        }
    }
    
    private JButton createIconPaletteButton(Icon icon, String tooltip, ProcessElement.Type type) {
        JButton button = new JButton(icon);
        button.setToolTipText(tooltip);
        button.setFocusPainted(false);
        button.setBorderPainted(true);
        button.setContentAreaFilled(false);
        button.addActionListener(e -> {
            canvas.setSelectedType(type);
            resetButtonColors();
            button.setBackground(new Color(200, 220, 255));
            button.setContentAreaFilled(true);
        });
        return button;
    }
    
    private JButton createIconToolButton(Icon icon, String text, ActionListener listener) {
        JButton button;
        if (icon != null) {
            button = new JButton(icon);
            button.setToolTipText(text);
            button.setBorderPainted(true);
            button.setContentAreaFilled(false);
        } else {
            button = new JButton(text);
        }
        button.setFocusPainted(false);
        button.addActionListener(listener);
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
        placeButton.setContentAreaFilled(false);
        transitionButton.setBackground(null);
        transitionButton.setContentAreaFilled(false);
        eventGenButton.setBackground(null);
        eventGenButton.setContentAreaFilled(false);
        arrowButton.setBackground(null);
        arrowButton.setContentAreaFilled(false);
        clickArrowButton.setBackground(null);
        clickArrowButton.setContentAreaFilled(false);
        textButton.setBackground(null);
        textButton.setContentAreaFilled(false);
    }
    
    private void setupLayout() {
        setLayout(new BorderLayout(5, 5));
        
        // ============ TOP TOOLBAR ============
        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        toolbar.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, Color.LIGHT_GRAY),
            BorderFactory.createEmptyBorder(2, 5, 2, 5)
        ));
        
        // Drawing tools section
        toolbar.add(createToolbarButton(placeButton, "Place (P)", 32));
        toolbar.add(createToolbarButton(transitionButton, "Transition (T)", 32));
        toolbar.add(createToolbarButton(eventGenButton, "Event Generator", 32));
        
        toolbar.addSeparator(new Dimension(10, 32));
        
        // Connection tools
        toolbar.add(createToolbarButton(arrowButton, "Arrow (drag)", 32));
        toolbar.add(createToolbarButton(clickArrowButton, "Arrow (click waypoints)", 32));
        
        toolbar.addSeparator(new Dimension(10, 32));
        
        // Text tool
        toolbar.add(createToolbarButton(textButton, "Text annotation", 32));
        
        toolbar.addSeparator(new Dimension(20, 32));
        
        // Validate and Clear buttons (smaller)
        JButton smallValidateBtn = new JButton("Validate");
        smallValidateBtn.setToolTipText("Validate the diagram");
        smallValidateBtn.setFocusPainted(false);
        smallValidateBtn.addActionListener(e -> validateCanvas());
        toolbar.add(smallValidateBtn);
        
        toolbar.add(Box.createHorizontalStrut(5));
        
        JButton smallClearBtn = new JButton("Clear");
        smallClearBtn.setToolTipText("Clear all elements");
        smallClearBtn.setFocusPainted(false);
        smallClearBtn.addActionListener(e -> clearCanvas());
        toolbar.add(smallClearBtn);
        
        toolbar.addSeparator(new Dimension(20, 32));
        
        // Process Type selector
        JLabel typeLabel = new JLabel("Type:");
        toolbar.add(typeLabel);
        toolbar.add(Box.createHorizontalStrut(5));
        processTypeCombo.setMaximumSize(new Dimension(90, 26));
        toolbar.add(processTypeCombo);
        
        toolbar.addSeparator(new Dimension(20, 32));
        
        // Zoom controls
        JButton zoomOutBtn = new JButton("-");
        zoomOutBtn.setToolTipText("Zoom Out (Ctrl+-)");
        zoomOutBtn.setFocusPainted(false);
        zoomOutBtn.setPreferredSize(new Dimension(28, 26));
        zoomOutBtn.setMaximumSize(new Dimension(28, 26));
        zoomOutBtn.addActionListener(e -> canvas.zoomOut());
        toolbar.add(zoomOutBtn);
        
        zoomLabel = new JLabel("100%");
        zoomLabel.setPreferredSize(new Dimension(45, 26));
        zoomLabel.setHorizontalAlignment(SwingConstants.CENTER);
        zoomLabel.setToolTipText("Current zoom level (Ctrl+0 to reset)");
        toolbar.add(zoomLabel);
        
        JButton zoomInBtn = new JButton("+");
        zoomInBtn.setToolTipText("Zoom In (Ctrl++)");
        zoomInBtn.setFocusPainted(false);
        zoomInBtn.setPreferredSize(new Dimension(28, 26));
        zoomInBtn.setMaximumSize(new Dimension(28, 26));
        zoomInBtn.addActionListener(e -> canvas.zoomIn());
        toolbar.add(zoomInBtn);
        
        toolbar.add(Box.createHorizontalStrut(5));
        
        JButton zoomFitBtn = new JButton("Fit");
        zoomFitBtn.setToolTipText("Zoom to Fit (Ctrl+Shift+F)");
        zoomFitBtn.setFocusPainted(false);
        zoomFitBtn.addActionListener(e -> canvas.zoomToFit());
        toolbar.add(zoomFitBtn);
        
        toolbar.addSeparator(new Dimension(15, 32));
        
        // Recent colors palette
        JLabel colorsLabel = new JLabel("Colors:");
        colorsLabel.setToolTipText("Click a swatch to apply colors to selection");
        toolbar.add(colorsLabel);
        toolbar.add(Box.createHorizontalStrut(5));
        
        colorPalettePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        colorPalettePanel.setOpaque(false);
        toolbar.add(colorPalettePanel);
        
        // Push remaining items to the right
        toolbar.add(Box.createHorizontalGlue());
        
        add(toolbar, BorderLayout.NORTH);
        
        // ============ LEFT PANEL (Attributes only) ============
        JPanel leftPanel = new JPanel(new BorderLayout());
        leftPanel.add(attributesFrame, BorderLayout.CENTER);
        leftPanel.setMinimumSize(new Dimension(200, 300));
        leftPanel.setPreferredSize(new Dimension(260, 400));
        
        // ============ CANVAS (center) ============
        JScrollPane scrollPane = new JScrollPane(canvas);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_ALWAYS);
        scrollPane.setMinimumSize(new Dimension(400, 300));
        
        // Use JSplitPane to allow resizing between left panel and canvas
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, scrollPane);
        splitPane.setDividerLocation(260);
        splitPane.setDividerSize(6);
        splitPane.setOneTouchExpandable(true);
        splitPane.setContinuousLayout(true);
        
        add(splitPane, BorderLayout.CENTER);
        
        // ============ RIGHT PANEL (Documentation) ============
        add(docPanelContainer, BorderLayout.EAST);
        
        // ============ BOTTOM PANEL ============
        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.add(animationPanel, BorderLayout.CENTER);
        
        // Status bar
        statusLabel = new JLabel(" Ready - Draw your Petri net and export to Graphviz");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        bottomPanel.add(statusLabel, BorderLayout.SOUTH);
        
        add(bottomPanel, BorderLayout.SOUTH);
    }
    
    /**
     * Configure a button for the toolbar with consistent sizing
     */
    private JButton createToolbarButton(JButton button, String tooltip, int size) {
        button.setToolTipText(tooltip);
        button.setPreferredSize(new Dimension(size + 6, size + 6));
        button.setMaximumSize(new Dimension(size + 6, size + 6));
        button.setFocusPainted(false);
        return button;
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
        
        JMenuItem saveAsItem = new JMenuItem("Save As (.json)");
        saveAsItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, 
            InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        saveAsItem.addActionListener(e -> saveAsToJSON());
        fileMenu.add(saveAsItem);
        
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
        
        JMenuItem exportPNGItem = new JMenuItem("Export to PNG (.png)");
        exportPNGItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_P, 
            InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        exportPNGItem.addActionListener(e -> exportToPNG());
        fileMenu.add(exportPNGItem);
        
        fileMenu.addSeparator();
        
        JMenuItem exitItem = new JMenuItem("Exit");
        exitItem.addActionListener(e -> {
            if (checkUnsavedChanges()) {
                System.exit(0);
            }
        });
        fileMenu.add(exitItem);
        
        menuBar.add(fileMenu);
        
        // Edit menu
        JMenu editMenu = new JMenu("Edit");
        
        JMenuItem undoItem = new JMenuItem("Undo");
        undoItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, 
            InputEvent.CTRL_DOWN_MASK));
        undoItem.addActionListener(e -> {
            if (undoRedoManager.canUndo()) {
                undoRedoManager.undo();
            }
        });
        editMenu.add(undoItem);
        
        JMenuItem redoItem = new JMenuItem("Redo");
        redoItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Y, 
            InputEvent.CTRL_DOWN_MASK));
        redoItem.addActionListener(e -> {
            if (undoRedoManager.canRedo()) {
                undoRedoManager.redo();
            }
        });
        editMenu.add(redoItem);
        
        editMenu.addSeparator();
        
        JMenuItem copyItem = new JMenuItem("Copy");
        copyItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, 
            InputEvent.CTRL_DOWN_MASK));
        copyItem.addActionListener(e -> canvas.copySelection());
        editMenu.add(copyItem);
        
        JMenuItem pasteItem = new JMenuItem("Paste");
        pasteItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_V, 
            InputEvent.CTRL_DOWN_MASK));
        pasteItem.addActionListener(e -> canvas.pasteFromClipboard());
        editMenu.add(pasteItem);
        
        editMenu.addSeparator();
        
        JMenuItem validateItem = new JMenuItem("Validate");
        validateItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_V, 
            InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        validateItem.addActionListener(e -> validateCanvas());
        editMenu.add(validateItem);
        
        menuBar.add(editMenu);
        
        // View menu
        JMenu viewMenu = new JMenu("View");
        
        toggleDocPanelItem = new JMenuItem("Show Documentation Panel");
        toggleDocPanelItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_D, 
            Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() | KeyEvent.SHIFT_DOWN_MASK));
        toggleDocPanelItem.addActionListener(e -> toggleDocumentationPanel());
        viewMenu.add(toggleDocPanelItem);
        
        viewMenu.addSeparator();
        
        // Zoom controls
        JMenuItem zoomInItem = new JMenuItem("Zoom In");
        zoomInItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, 
            Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        zoomInItem.addActionListener(e -> canvas.zoomIn());
        viewMenu.add(zoomInItem);
        
        JMenuItem zoomOutItem = new JMenuItem("Zoom Out");
        zoomOutItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, 
            Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        zoomOutItem.addActionListener(e -> canvas.zoomOut());
        viewMenu.add(zoomOutItem);
        
        JMenuItem resetZoomItem = new JMenuItem("Reset Zoom (100%)");
        resetZoomItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_0, 
            Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        resetZoomItem.addActionListener(e -> canvas.resetZoom());
        viewMenu.add(resetZoomItem);
        
        JMenuItem zoomToFitItem = new JMenuItem("Zoom to Fit");
        zoomToFitItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F, 
            Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() | KeyEvent.SHIFT_DOWN_MASK));
        zoomToFitItem.addActionListener(e -> canvas.zoomToFit());
        viewMenu.add(zoomToFitItem);
        
        menuBar.add(viewMenu);
        
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
    
    /**
     * Create a container panel for the documentation panel with a header and close button
     */
    private JPanel createDocPanelContainer() {
        JPanel container = new JPanel(new BorderLayout());
        
        // Header panel with title and close button
        JPanel header = new JPanel(new BorderLayout());
        header.setBorder(BorderFactory.createEmptyBorder(2, 5, 2, 2));
        header.setBackground(new Color(240, 240, 240));
        
        JLabel titleLabel = new JLabel("Documentation");
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD));
        header.add(titleLabel, BorderLayout.CENTER);
        
        // Close button (X)
        JButton closeButton = new JButton("X");
        closeButton.setFont(new Font("SansSerif", Font.PLAIN, 12));
        closeButton.setMargin(new Insets(0, 4, 0, 4));
        closeButton.setFocusPainted(false);
        closeButton.setBorderPainted(false);
        closeButton.setContentAreaFilled(false);
        closeButton.setToolTipText("Close Documentation Panel (Ctrl+Shift+D to reopen)");
        closeButton.addActionListener(e -> toggleDocumentationPanel());
        closeButton.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent e) {
                closeButton.setForeground(Color.RED);
            }
            public void mouseExited(MouseEvent e) {
                closeButton.setForeground(Color.BLACK);
            }
        });
        header.add(closeButton, BorderLayout.EAST);
        
        container.add(header, BorderLayout.NORTH);
        container.add(documentationPanel, BorderLayout.CENTER);
        
        return container;
    }
    
    /**
     * Toggle the documentation panel visibility
     */
    private void toggleDocumentationPanel() {
        boolean isVisible = docPanelContainer.isVisible();
        docPanelContainer.setVisible(!isVisible);
        
        // Update menu item text
        if (isVisible) {
            toggleDocPanelItem.setText("Show Documentation Panel");
        } else {
            toggleDocPanelItem.setText("Hide Documentation Panel");
        }
        
        // Revalidate layout
        revalidate();
        repaint();
    }
    
    private void validateCanvas() {
        java.util.List<String> errors = canvas.validatePetriNet();
        
        // Check if processType is specified
        String processType = canvas.getProcessType();
        if (processType == null || processType.trim().isEmpty()) {
            errors.add(0, "Process Type must be specified (PetriNet or SOA)");
        }
        
        if (errors.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "Validation passed!\n\nNo duplicate labels or other issues found.",
                "Validation Successful",
                JOptionPane.INFORMATION_MESSAGE);
        } else {
            StringBuilder message = new StringBuilder("Validation found the following issues:\n\n");
            for (String error : errors) {
                message.append("- ").append(error).append("\n");
            }
            message.append("\nPlease fix these issues before saving.");
            JOptionPane.showMessageDialog(this,
                message.toString(),
                "Validation Errors",
                JOptionPane.ERROR_MESSAGE);
        }
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
        fileChooser.setDialogTitle("Export to Graphviz File");
        fileChooser.setSelectedFile(new File("petri_net.dot"));
        
        int result = fileChooser.showSaveDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();
            lastDirectory = file.getParentFile();
            try (FileWriter writer = new FileWriter(file)) {
                writer.write(canvas.exportToGraphviz());
                JOptionPane.showMessageDialog(this,
                    "Exported successfully!\n\n" +
                    "Use the command:\n" +
                    "dot -Tpng " + file.getName() + " -o output.png\n\n" +
                    "to generate an image.",
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
    
    private void exportToPNG() {
        File newDir = PNGExporter.showExportDialog(
            this,
            canvas.getElements(),
            canvas.getArrows(),
            canvas.getTextElements(),
            lastDirectory
        );
        
        if (newDir != null) {
            saveLastDirectory(newDir);
        }
    }
    
    private void saveToJSON() {
        if (currentFile != null) {
            // Save to existing file without prompting
            if (canvas.getElements().isEmpty()) {
                JOptionPane.showMessageDialog(this,
                    "Canvas is empty! Nothing to save.",
                    "Nothing to Save",
                    JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            
            // VALIDATE BEFORE SAVING
            java.util.List<String> errors = canvas.validatePetriNet();
            
            // Check if processType is specified
            String processType = canvas.getProcessType();
            if (processType == null || processType.trim().isEmpty()) {
                errors.add(0, "Process Type must be specified (PetriNet or SOA)");
            }
            
            if (!errors.isEmpty()) {
                StringBuilder message = new StringBuilder("Validation warnings found:\n\n");
                for (String error : errors) {
                    message.append("- ").append(error).append("\n");
                }
                message.append("\nYou can save now and fix these issues later, or cancel to fix them now.");
                
                Object[] options = {"Save Anyway", "Fix Now"};
                int result = JOptionPane.showOptionDialog(this,
                    message.toString(),
                    "Validation Warnings",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE,
                    null,
                    options,
                    options[1]); // Default to "Fix Now"
                
                if (result == JOptionPane.NO_OPTION || result == JOptionPane.CLOSED_OPTION) {
                    // User chose "Fix Now" or closed dialog - don't save
                    return;
                }
                // User chose "Save Anyway" - continue with save
            }
            
            try (FileWriter writer = new FileWriter(currentFile)) {
                writer.write(canvas.saveToJSON());
                setDirty(false); // Clear dirty flag after successful save
                updateStatusBar(); // Update status bar
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
        } else {
            // No current file, prompt for filename
            saveAsToJSON();
        }
    }
    
    private void saveAsToJSON() {
        if (canvas.getElements().isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "Canvas is empty! Nothing to save.",
                "Nothing to Save",
                JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        
        // VALIDATE BEFORE SAVING
        java.util.List<String> errors = canvas.validatePetriNet();
        
        // Check if processType is specified
        String processType = canvas.getProcessType();
        if (processType == null || processType.trim().isEmpty()) {
            errors.add(0, "Process Type must be specified (PetriNet or SOA)");
        }
        
        if (!errors.isEmpty()) {
            StringBuilder message = new StringBuilder("Validation warnings found:\n\n");
            for (String error : errors) {
                message.append("- ").append(error).append("\n");
            }
            message.append("\nYou can save now and fix these issues later, or cancel to fix them now.");
            
            Object[] options = {"Save Anyway", "Fix Now"};
            int result = JOptionPane.showOptionDialog(this,
                message.toString(),
                "Validation Warnings",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null,
                options,
                options[1]); // Default to "Fix Now"
            
            if (result == JOptionPane.NO_OPTION || result == JOptionPane.CLOSED_OPTION) {
                // User chose "Fix Now" or closed dialog - don't save
                return;
            }
            // User chose "Save Anyway" - continue with save
        }
        
        JFileChooser fileChooser = new JFileChooser();
        if (lastDirectory != null) {
            fileChooser.setCurrentDirectory(lastDirectory);
        }
        fileChooser.setDialogTitle("Save Petri Net");
        
        // Use current filename as default, or "petri_net.json" if new
        if (currentFile != null) {
            fileChooser.setSelectedFile(currentFile);
        } else {
            fileChooser.setSelectedFile(new File("petri_net.json"));
        }
        
        int result = fileChooser.showSaveDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();
            saveLastDirectory(file.getParentFile());
            try (FileWriter writer = new FileWriter(file)) {
                writer.write(canvas.saveToJSON());
                currentFile = file; // Remember this file for future saves
                setDirty(false); // Clear dirty flag after successful save
                updateStatusBar(); // Update status bar to show new filename
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
            saveLastDirectory(file.getParentFile());
            try {
                StringBuilder content = new StringBuilder();
                try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(file))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        content.append(line).append("\n");
                    }
                }
                canvas.loadFromJSON(content.toString());
                documentationPanel.loadFromJSON(content.toString());  // Load documentation
                undoRedoManager.clear();  // Clear undo/redo history after loading new file
                currentFile = file; // Remember this file for future saves
                setDirty(false); // Clear dirty flag after successful load
                updateStatusBar(); // Update status bar to show loaded filename
                
                // Update process type dropdown to match loaded file
                String loadedProcessType = canvas.getProcessType();
                if (loadedProcessType != null) {
                    processTypeCombo.setSelectedItem(loadedProcessType);
                } else {
                    processTypeCombo.setSelectedIndex(0);  // Empty/blank
                }
                
                // Extract unique colors from loaded elements into the palette
                populateColorsFromCanvas();
                
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
        // Check for unsaved changes first
        if (isDirty) {
            int saveResult = JOptionPane.showConfirmDialog(this,
                "You have unsaved changes. Do you want to save before clearing?",
                "Unsaved Changes",
                JOptionPane.YES_NO_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE);
            
            if (saveResult == JOptionPane.YES_OPTION) {
                // Save first
                saveToJSON();
                if (isDirty) {
                    // Save was cancelled or failed
                    return;
                }
            } else if (saveResult == JOptionPane.CANCEL_OPTION) {
                // Cancel the clear operation
                return;
            }
            // If NO, continue to clear without saving
        }
        
        // Final confirmation
        int result = JOptionPane.showConfirmDialog(this,
            "Clear all elements from the canvas?",
            "Confirm Clear",
            JOptionPane.YES_NO_OPTION);
        
        if (result == JOptionPane.YES_OPTION) {
            canvas.clear();
            documentationPanel.clear();  // Clear documentation
            resetButtonColors();
            processTypeCombo.setSelectedIndex(0);  // Reset process type dropdown
            currentFile = null; // Forget current file (starting fresh)
            updateStatusBar(); // Update status bar to show no file
            setDirty(false); // Clear dirty flag after clearing canvas
        }
    }
    
    private void showInstructions() {
        String instructions = 
            "How to Use Petri Net Editor:\n\n" +
            "1. Click a tool icon (circle or box) in the palette\n" +
            "2. Click on the canvas to add the shape\n" +
            "3. Click whitespace to cancel current mode\n" +
            "4. Double-click element to edit attributes\n" +
            "5. Click arrow icon then click source -> target\n" +
            "6. Double-click arrow to add control point (bend arrow)\n" +
            "7. Drag control points to reshape arrows\n" +
            "8. Right-click control point to delete it\n" +
            "9. Drag elements to reposition them\n" +
            "10. Right-click element/arrow for menu (Edit/Delete)\n" +
            "11. Click Validate to check for duplicate labels\n" +
            "12. Save your work (File > Save) as JSON\n" +
            "13. Load saved work (File > Load)\n" +
            "14. Export to Graphviz (File > Export) for rendering\n\n" +
            "Multi-Selection & Copy/Paste:\n" +
            "- Drag to select multiple elements (rubber band)\n" +
            "- Ctrl+Click to add/remove from selection\n" +
            "- Drag selected group to move together\n" +
            "- Ctrl+C to copy selection\n" +
            "- Ctrl+V to paste (with automatic offset)\n\n" +
            "Keyboard Shortcuts:\n" +
            "Ctrl+S - Save\n" +
            "Ctrl+O - Load\n" +
            "Ctrl+C - Copy selection\n" +
            "Ctrl+V - Paste\n" +
            "Ctrl+Shift+V - Validate\n" +
            "Ctrl+Z - Undo\n" +
            "Ctrl+Y - Redo\n" +
            "Ctrl+E - Export to Graphviz\n" +
            "Delete - Delete selected element\n" +
            "Escape - Cancel/Clear selection\n\n" +
            "Important: Each element must have a unique label!\n" +
            "The Validate button will check for duplicates.";
        
        JOptionPane.showMessageDialog(this,
            instructions,
            "Instructions",
            JOptionPane.INFORMATION_MESSAGE);
    }
    
    private void showAbout() {
        JOptionPane.showMessageDialog(this,
            "Petri Net Editor\n\n" +
            "A simple visual editor for Petri nets like Drawings\n" +
            "Export to Graphviz DOT format\n\n" +
            "Version 1.1 - Now with validation!",
            "About",
            JOptionPane.INFORMATION_MESSAGE);
    }
    
    /**
     * Extract unique color pairs from all canvas elements and populate the palette.
     * Called after loading a diagram so users can see and reuse existing colors.
     */
    private void populateColorsFromCanvas() {
        recentColors.clear();
        
        java.util.LinkedHashSet<ColorPair> uniqueColors = new java.util.LinkedHashSet<>();
        
        for (ProcessElement element : canvas.getElements()) {
            Color fill = element.getFillColor();
            Color border = element.getBorderColor();
            
            // Skip elements with no custom colors (both null = default)
            if (fill == null && border == null) {
                continue;
            }
            
            uniqueColors.add(new ColorPair(fill, border));
        }
        
        // Add unique colors to palette (up to max)
        int count = 0;
        for (ColorPair pair : uniqueColors) {
            if (count >= MAX_RECENT_COLORS) break;
            recentColors.add(pair);
            count++;
        }
        
        updateColorPalette();
    }
    
    /**
     * Add a color combination to the recent colors palette.
     * Called by EditorFrame when a color is changed.
     */
    public void addRecentColor(Color fillColor, Color borderColor) {
        // Skip if both are null/default
        if (fillColor == null && borderColor == null) {
            return;
        }
        
        ColorPair newPair = new ColorPair(fillColor, borderColor);
        
        // Remove if already exists (will re-add at front)
        recentColors.remove(newPair);
        
        // Add at front
        recentColors.add(0, newPair);
        
        // Trim to max size
        while (recentColors.size() > MAX_RECENT_COLORS) {
            recentColors.remove(recentColors.size() - 1);
        }
        
        // Refresh the palette UI
        updateColorPalette();
    }
    
    /**
     * Update the color palette panel to show current recent colors
     */
    private void updateColorPalette() {
        colorPalettePanel.removeAll();
        
        for (ColorPair pair : recentColors) {
            JButton swatch = createColorSwatch(pair);
            colorPalettePanel.add(swatch);
        }
        
        colorPalettePanel.revalidate();
        colorPalettePanel.repaint();
    }
    
    /**
     * Create a color swatch button showing fill and border colors
     */
    private JButton createColorSwatch(ColorPair pair) {
        JButton swatch = new JButton() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                
                int w = getWidth();
                int h = getHeight();
                int inset = 3;
                
                // Draw fill color (main area)
                Color fill = pair.fillColor != null ? pair.fillColor : Color.WHITE;
                g2.setColor(fill);
                g2.fillRect(inset, inset, w - 2*inset, h - 2*inset);
                
                // Draw border color (outline)
                Color border = pair.borderColor != null ? pair.borderColor : Color.BLACK;
                g2.setColor(border);
                g2.setStroke(new BasicStroke(2));
                g2.drawRect(inset, inset, w - 2*inset - 1, h - 2*inset - 1);
            }
        };
        
        swatch.setPreferredSize(new Dimension(26, 26));
        swatch.setMinimumSize(new Dimension(26, 26));
        swatch.setMaximumSize(new Dimension(26, 26));
        swatch.setToolTipText("Left-click to apply, Right-click to remove");
        swatch.setContentAreaFilled(false);
        swatch.setBorderPainted(true);
        swatch.setFocusPainted(false);
        
        // When clicked, apply colors to current selection
        swatch.addActionListener(e -> applyColorsToSelection(pair));
        
        // Right-click to remove from palette
        swatch.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showRemoveMenu(e, pair);
                }
            }
            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showRemoveMenu(e, pair);
                }
            }
        });
        
        return swatch;
    }
    
    /**
     * Show context menu to remove a color from the palette
     */
    private void showRemoveMenu(MouseEvent e, ColorPair pair) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem removeItem = new JMenuItem("Remove from palette");
        removeItem.addActionListener(ev -> {
            recentColors.remove(pair);
            updateColorPalette();
        });
        menu.add(removeItem);
        menu.show(e.getComponent(), e.getX(), e.getY());
    }
    
    /**
     * Apply colors to the currently selected element(s)
     */
    private void applyColorsToSelection(ColorPair pair) {
        java.util.List<ProcessElement> selected = canvas.getSelectedElements();
        
        if (selected.isEmpty()) {
            statusLabel.setText(" No element selected - select an element first");
            return;
        }
        
        for (ProcessElement element : selected) {
            element.setFillColor(pair.fillColor);
            element.setBorderColor(pair.borderColor);
        }
        
        canvas.repaint();
        
        // Refresh attributes panel if single selection
        if (selected.size() == 1) {
            attributesFrame.updateSelection(selected.get(0));
        }
        
        statusLabel.setText(" Applied colors to " + selected.size() + " element(s)");
    }
    
    /**
     * Convert color to display string
     */
    private String colorToString(Color c) {
        if (c == null) return "default";
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }
    
    public static void main(String[] args) {
        // Set look and feel
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            // Use default look and feel
        }
        
        SwingUtilities.invokeLater(() -> {
            ProcessEditor editor = new ProcessEditor();
            editor.setVisible(true);
        });
    }
}