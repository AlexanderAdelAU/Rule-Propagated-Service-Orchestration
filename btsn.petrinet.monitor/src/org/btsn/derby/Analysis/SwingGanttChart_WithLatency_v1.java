package org.btsn.derby.Analysis;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.List;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.apache.derby.jdbc.EmbeddedDriver;

// For PDF export - requires Apache PDFBox library
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/**
 * SwingGanttChart - Database-driven Gantt chart with PDF and LaTeX export capabilities
 * Now includes queue time visualization as "ghost bars"
 */
public class SwingGanttChart_WithLatency_v1 extends JPanel {
    
    private static final String PROTOCOL = "jdbc:derby:";
    private static final String DB_NAME = "ServiceAnalysisDataBase";
    private static final String DB_URL = PROTOCOL + DB_NAME;
 // Display limit for truncation
    private int maxDisplayTasks = Integer.MAX_VALUE; // Show all by default
    
    // Configurable font sizes
    private int titleFontSize = 16;
    private int labelFontSize = 12;
    private int axisLabelFontSize = 10;
    private int tooltipFontSize = 12;
    private float fontScaleFactor = 1.0f;
    
    // Font objects (will be recreated when sizes change)
    private Font titleFont;
    private Font labelFont;
    private Font axisLabelFont;
    private Font tooltipFont;
    
    // Legend positioning options
    private boolean legendTextBelow = true;  // Place text below color boxes
    private boolean compactMode = true;      // Use compact legend layout
    
    // Queue time visualization options
    private boolean showQueueTime = true;    // Toggle queue time visualization
    private boolean colorCodeQueueTime = true; // Use color coding for queue severity
    
    // Version display feature
    private boolean displayByVersion = false;  // Toggle between service name and version display
    private Map<String, List<Task>> versionGroups = new HashMap<>();  // Group tasks by version
    private List<String> uniqueVersions = new ArrayList<>();  // List of unique versions
    private Map<String, Color> versionColors = new HashMap<>();  // Colors for versions
    private Map<String, Integer> versionLanes = new HashMap<>();  // Lane assignments for versions
    
    // Track maximum queue time for scaling
    private long maxQueueTime = 1;
    
    public static class Task {
        int id;
        String service;
        int sequenceId;
        long processingTime;
        long queueTime;
        
        public Task(int id, String service, int sequenceId, long processingTime) {
            this.id = id;
            this.service = service;
            this.sequenceId = sequenceId;
            this.processingTime = processingTime;
        }
    }
    
    protected List<Task> tasks = new ArrayList<>();
    private Map<String, Color> serviceColors = new HashMap<>();
    private Map<String, Integer> serviceLanes = new HashMap<>();
    protected List<String> uniqueServices = new ArrayList<>();
    private int maxId = 1;
    private long maxTime = 1;
    private Task hoveredTask = null;
    
    // Color palette for dynamic assignment
    private Color[] colorPalette = {
        new Color(46, 204, 113),   // Green
        new Color(243, 156, 18),   // Orange
        new Color(231, 76, 60),    // Red
        new Color(52, 152, 219),   // Blue
        new Color(155, 89, 182),   // Purple
        new Color(241, 196, 15),   // Yellow
        new Color(52, 73, 94),     // Dark Gray
        new Color(149, 165, 166)   // Light Gray
    };
    
    public SwingGanttChart_WithLatency_v1() {
        setBackground(Color.WHITE);
        setPreferredSize(new Dimension(900, 600));  // Wider default to compensate for smaller margins
        
        // Initialize fonts
        updateFonts();
        
        // Add mouse listener for tooltips
        addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                hoveredTask = getTaskAt(e.getX(), e.getY());
                repaint();
            }
        });
        
        // Load data from database
        loadDataFromDatabase();
    }
    
    /**
     * Export the chart to PDF format
     */
    public void exportToPDF(String filename) {
        try {
            // Get the actual current size of the panel as displayed
            revalidate();
            repaint();
            
            // Determine actual dimensions to export
            int exportWidth = getWidth();
            int exportHeight = getHeight();
            
            // If we're in a scroll pane, we need to handle this differently
            Container parent = getParent();
            if (parent instanceof JViewport) {
                JViewport viewport = (JViewport) parent;
                // Use the actual panel size, not the viewport size
                // The panel should resize to fill the viewport
                exportWidth = Math.max(viewport.getWidth(), getPreferredSize().width);
                exportHeight = Math.max(viewport.getHeight(), getPreferredSize().height);
                
                // Force the panel to be at least as large as the viewport
                setSize(exportWidth, exportHeight);
                revalidate();
            }
            
            // Create a buffered image with the actual current dimensions
            BufferedImage image = new BufferedImage(exportWidth, exportHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2 = image.createGraphics();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            
            // Set white background
            g2.setColor(Color.WHITE);
            g2.fillRect(0, 0, exportWidth, exportHeight);
            
            // Paint the entire chart to the image
            paint(g2);
            g2.dispose();
            
            // Create PDF document with the full image
            PDDocument document = new PDDocument();
            
            // Create page to fit the full image
            PDRectangle pageSize = new PDRectangle(exportWidth, exportHeight);
            PDPage page = new PDPage(pageSize);
            document.addPage(page);
            
            // Add image to PDF
            PDImageXObject pdImage = LosslessFactory.createFromImage(document, image);
            PDPageContentStream contentStream = new PDPageContentStream(document, page);
            
            // Draw the image at full size
            contentStream.drawImage(pdImage, 0, 0, exportWidth, exportHeight);
            
            contentStream.close();
            
            // Save the document
            document.save(filename);
            document.close();
            
            JOptionPane.showMessageDialog(this, 
                "PDF exported successfully to: " + filename + "\nDimensions: " + exportWidth + "x" + exportHeight, 
                "Export Success", 
                JOptionPane.INFORMATION_MESSAGE);
                
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, 
                "Error exporting to PDF: " + e.getMessage(), 
                "Export Error", 
                JOptionPane.ERROR_MESSAGE);
            e.printStackTrace();
        }
    }
    
    /**
     * Export the chart to LaTeX TikZ format
     */
    public void exportToLaTeX(String filename) {
        try (PrintWriter writer = new PrintWriter(new FileWriter(filename))) {
            
            // Determine which grouping to use
            List<String> displayGroups = displayByVersion ? uniqueVersions : uniqueServices;
            Map<String, Integer> displayLanes = displayByVersion ? versionLanes : serviceLanes;
            Map<String, Color> displayColors = displayByVersion ? versionColors : serviceColors;
            
            // Check if we have data
            if (tasks.isEmpty() || displayGroups.isEmpty()) {
                writer.println("% No data to export");
                writer.println("\\begin{figure}[htbp]");
                writer.println("\\centering");
                writer.println("\\textbf{No data available for Gantt chart}");
                writer.println("\\end{figure}");
                JOptionPane.showMessageDialog(this, 
                    "Warning: No data to export!", 
                    "Export Warning", 
                    JOptionPane.WARNING_MESSAGE);
                return;
            }
            
            // Ensure valid values for calculations
            int safeMaxId = Math.max(Math.min(tasks.size(), maxDisplayTasks), 10);  // At least 10 for scale
            long safeMaxTime = Math.max(maxTime, 1);     // Prevent division by zero
            
            // Write LaTeX document header
            writer.println("% Gantt Chart generated from SwingGanttChart");
            writer.println("% Add this to your LaTeX document preamble:");
            writer.println("% \\usepackage{tikz}");
            writer.println("% \\usepackage{pgfplots}");
            writer.println("% \\usetikzlibrary{patterns,shapes,arrows}");
            writer.println();
            writer.println("\\begin{figure}[htbp]");
            writer.println("\\centering");
            writer.println("\\begin{tikzpicture}[x=0.15cm, y=0.8cm]");
            writer.println();
            
            // Define colors in TikZ
            writer.println("% Define colors");
            int colorIndex = 0;
            Map<String, String> latexColors = new HashMap<>();
            for (String group : displayGroups) {
                Color c = displayColors.get(group);
                if (c == null) c = Color.GRAY;  // Fallback color
                String colorName = "color" + colorIndex;
                writer.printf("\\definecolor{%s}{RGB}{%d,%d,%d}\n", 
                    colorName, c.getRed(), c.getGreen(), c.getBlue());
                latexColors.put(group, colorName);
                colorIndex++;
            }
            
            // Define queue time colors if showing queue time
            if (showQueueTime) {
                writer.println("\\definecolor{queueGreen}{RGB}{0,200,0}");
                writer.println("\\definecolor{queueOrange}{RGB}{255,165,0}");
                writer.println("\\definecolor{queueRed}{RGB}{255,0,0}");
            }
            writer.println();
            
            // Draw axes with safe values
            writer.println("% Draw axes");
            writer.printf("\\draw[->] (0,0) -- (%d,0) node[right] {Execution Order};\n", safeMaxId + 2);
            writer.printf("\\draw[->] (0,0) -- (0,%d) node[above] {%s};\n", 
                displayGroups.size() + 1, displayByVersion ? "Versions" : "Services");
            writer.println();
            
            // Draw grid
            writer.println("% Draw grid");
            writer.printf("\\draw[gray!30, thin] (0,0) grid (%d,%d);\n", safeMaxId, displayGroups.size());
            writer.println();
            
            // Draw group labels
            writer.println("% Group labels");
            for (int i = 0; i < displayGroups.size(); i++) {
                String group = displayGroups.get(i);
                writer.printf("\\node[left] at (-0.5,%.1f) {%s};\n", i + 0.5, escapeLatex(group));
            }
            writer.println();
            
            // Draw x-axis labels
            writer.println("% X-axis labels");
            int step = Math.max(1, safeMaxId / 10);
            for (int i = 0; i <= safeMaxId; i += step) {
                writer.printf("\\node[below] at (%d,-0.3) {\\tiny %d};\n", i, i);
            }
            writer.println();
            
            // Draw queue time bars if enabled
            if (showQueueTime) {
                writer.println("% Queue time indicators");
                for (int i = 0; i < Math.min(tasks.size(), maxDisplayTasks); i++) {
                    Task task = tasks.get(i);
                    Integer laneIndex;
                    
                    if (displayByVersion) {
                        String version = deriveVersion(task.sequenceId);
                        laneIndex = versionLanes.get(version);
                    } else {
                        laneIndex = serviceLanes.get(task.service);
                    }
                    
                    if (laneIndex == null) continue;
                    
                    if (task.queueTime > 0) {
                        // Determine queue color
                        String queueColor;
                        if (colorCodeQueueTime) {
                            if (task.queueTime > 5000) {
                                queueColor = "queueRed";
                            } else if (task.queueTime > 2000) {
                                queueColor = "queueOrange";
                            } else {
                                queueColor = "queueGreen";
                            }
                        } else {
                            queueColor = "gray";
                        }
                        
                        double x = Math.max(0, task.id - 1);
                        double y = laneIndex + 0.1;
                        double queueWidth = Math.log(task.queueTime + 1) / Math.log(maxQueueTime + 1) * 1.5;
                        queueWidth = Math.min(1.5, Math.max(0.05, queueWidth));
                        
                        writer.printf("\\filldraw[fill=%s!30, draw=%s!50] (%.2f,%.2f) rectangle (%.2f,%.2f);\n",
                            queueColor, queueColor, x - queueWidth, y, x, y + 0.15);
                    }
                }
            }
            
            // Draw tasks as rectangles
            writer.println("% Tasks (processing bars)");
            for (int i = 0; i < Math.min(tasks.size(), maxDisplayTasks); i++) {
                Task task = tasks.get(i);
                Integer laneIndex;
                String group;
                
                if (displayByVersion) {
                    String version = deriveVersion(task.sequenceId);
                    laneIndex = versionLanes.get(version);
                    group = version;
                } else {
                    laneIndex = serviceLanes.get(task.service);
                    group = task.service;
                }
                
                if (laneIndex == null) continue;
                
                String color = latexColors.get(group);
                if (color == null) color = "gray";  // Fallback
                
                // Calculate positions with safety checks
                double x = Math.max(0, task.id - 1);  // Ensure non-negative
                double y = laneIndex + 0.3;
                
                // Safe width calculation
                double width;
                if (safeMaxTime > 0 && task.processingTime >= 0) {
                    width = Math.log(task.processingTime + 1) / Math.log(safeMaxTime + 1) * 2;
                    width = Math.min(2.0, Math.max(0.1, width));  // Clamp between 0.1 and 2.0
                } else {
                    width = 0.5;  // Default width
                }
                
                double height = 0.5;
                
                // Validate all values before writing
                if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(width) || Double.isNaN(height) ||
                    Double.isInfinite(x) || Double.isInfinite(y) || Double.isInfinite(width) || Double.isInfinite(height)) {
                    System.err.println("Warning: Invalid values for task " + task.id + 
                                     " (x=" + x + ", y=" + y + ", width=" + width + ")");
                    continue;  // Skip this task
                }
                
                writer.printf("\\filldraw[fill=%s!70, draw=%s!90] (%.2f,%.2f) rectangle (%.2f,%.2f);\n",
                    color, color, x, y, x + width, y + height);
                
                // Add task ID as small text for smaller datasets
                if (tasks.size() <= 30) {
                    writer.printf("\\node[font=\\tiny] at (%.2f,%.2f) {%d};\n", 
                        x + width/2, y + height/2, task.id);
                }
            }
            writer.println();
            
            // End TikZ picture
            writer.println();
            writer.println("\\end{tikzpicture}");
            String caption = displayByVersion ? 
                "Service Execution Timeline by Version" : 
                "Service Execution Timeline by Service";
            if (showQueueTime) caption += " (with Queue Time)";
            writer.println("\\caption{" + caption + "}");
            writer.println("\\label{fig:gantt-chart}");
            writer.println("\\end{figure}");
            
            System.out.println("Exported LaTeX with " + Math.min(tasks.size(), maxDisplayTasks) + " tasks");
            System.out.println("MaxId: " + maxId + ", MaxTime: " + maxTime);
            
            // Also create a standalone version
            writer.println();
            writer.println("% Standalone version (compile with pdflatex):");
            writer.println("% \\documentclass{standalone}");
            writer.println("% \\usepackage{tikz}");
            writer.println("% \\begin{document}");
            writer.println("% [Insert tikzpicture code here]");
            writer.println("% \\end{document}");
            
            JOptionPane.showMessageDialog(this, 
                "LaTeX/TikZ code exported successfully to: " + filename, 
                "Export Success", 
                JOptionPane.INFORMATION_MESSAGE);
                
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, 
                "Error exporting to LaTeX: " + e.getMessage(), 
                "Export Error", 
                JOptionPane.ERROR_MESSAGE);
            e.printStackTrace();
        }
    }
    
    /**
     * Export data to LaTeX table format
     */
    public void exportToLaTeXTable(String filename) {
        try (PrintWriter writer = new PrintWriter(new FileWriter(filename))) {
            
            writer.println("% Task data table for LaTeX");
            writer.println("% Add to preamble: \\usepackage{booktabs}");
            writer.println("% Add to preamble: \\usepackage{longtable} % for long tables");
            writer.println();
            writer.println("\\begin{longtable}{cccccc}");
            writer.println("\\toprule");
            writer.println("ID & Service & Version & Sequence ID & Processing Time (ms) & Queue Time (ms) \\\\");
            writer.println("\\midrule");
            writer.println("\\endhead");
            
            for (int i = 0; i < Math.min(tasks.size(), maxDisplayTasks); i++) {
                Task task = tasks.get(i);
                String version = deriveVersion(task.sequenceId);
                writer.printf("%d & %s & %s & %d & %d & %d \\\\\n",
                    task.id, 
                    escapeLatex(task.service),
                    escapeLatex(version),
                    task.sequenceId,
                    task.processingTime,
                    task.queueTime);
            }
            
            writer.println("\\bottomrule");
            writer.println("\\caption{Service Execution Data}");
            writer.println("\\label{tab:service-data}");
            writer.println("\\end{longtable}");
            
            JOptionPane.showMessageDialog(this, 
                "LaTeX table exported successfully to: " + filename, 
                "Export Success", 
                JOptionPane.INFORMATION_MESSAGE);
                
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, 
                "Error exporting LaTeX table: " + e.getMessage(), 
                "Export Error", 
                JOptionPane.ERROR_MESSAGE);
            e.printStackTrace();
        }
    }
    
    /**
     * Escape special LaTeX characters
     */
    private String escapeLatex(String text) {
        return text.replace("_", "\\_")
                   .replace("&", "\\&")
                   .replace("%", "\\%")
                   .replace("$", "\\$")
                   .replace("#", "\\#")
                   .replace("{", "\\{")
                   .replace("}", "\\}")
                   .replace("~", "\\textasciitilde{}")
                   .replace("^", "\\textasciicircum{}");
    }
    
    /**
     * Set the maximum number of tasks to display
     */
    public void setMaxDisplayTasks(int max) {
        this.maxDisplayTasks = max;
        
        // Update maxId to reflect the new, truncated range for scaling
        this.maxId = Math.min(tasks.size(), max) + 1;
        
        // Recalculate panel dimensions based on the new display range
        int width = Math.max(700, maxId * 10 + 200); // Adjust width based on displayed tasks
        
        // Calculate height based on current display mode
        List<String> displayGroups = displayByVersion ? uniqueVersions : uniqueServices;
        if (displayGroups.isEmpty() && displayByVersion) {
            groupTasksByVersion();
            displayGroups = uniqueVersions;
        }
        
        int topMargin = Math.round(80 * fontScaleFactor);
        int bottomPadding = Math.round(15 * fontScaleFactor);  // REDUCED FROM 22
        int numLanes = Math.max(1, displayGroups.size());
        int laneHeight = Math.round(45 * fontScaleFactor);
        int chartHeight = numLanes * laneHeight;
        int totalHeight = topMargin + chartHeight + bottomPadding;
        
        // Update the preferred size to match the new display range
        setPreferredSize(new Dimension(width, totalHeight));
        revalidate();
        repaint();
    }
    
    /**
     * Set whether to show queue time visualization
     */
    public void setShowQueueTime(boolean show) {
        this.showQueueTime = show;
        repaint();
    }
    
    /**
     * Set whether to use color coding for queue time severity
     */
    public void setColorCodeQueueTime(boolean colorCode) {
        this.colorCodeQueueTime = colorCode;
        repaint();
    }
    
    /**
     * Get queue time severity color
     */
    private Color getQueueTimeColor(long queueTime) {
        if (!colorCodeQueueTime) {
            // Use a neutral gray if color coding is disabled
            return new Color(128, 128, 128, 60);
        }
        
        if (queueTime > 5000) {
            // Red for severe delays (>5s)
            return new Color(255, 0, 0, 80);
        } else if (queueTime > 2000) {
            // Orange/Yellow for moderate delays (2-5s)
            return new Color(255, 165, 0, 80);
        } else {
            // Green for minimal delays (<2s)
            return new Color(0, 200, 0, 60);
        }
    }
    
    /**
     * Set whether to use compact legend mode
     */
    public void setCompactMode(boolean compact) {
        this.compactMode = compact;
        repaint();
    }
    
    /**
     * Set whether to place legend text below color boxes
     */
    public void setLegendTextBelow(boolean below) {
        this.legendTextBelow = below;
        repaint();
    }
    
    /**
     * Derive version number from sequenceID
     * Process V001: 1000-199000
     * Process V002: 200000-299000
     * Process V003: 300000-399000
     */
    private String deriveVersion(int sequenceId) {
        if (sequenceId >= 1000 && sequenceId < 200000) {
            return "Process V001";
        } else if (sequenceId >= 200000 && sequenceId < 300000) {
            return "Process V002";
        } else if (sequenceId >= 300000 && sequenceId < 400000) {
            return "Process V003";
        } else {
            return "Unknown";
        }
    }
    
    /**
     * Toggle between service name and version display
     */
    public void setDisplayByVersion(boolean byVersion) {
        this.displayByVersion = byVersion;
        
        if (byVersion) {
            // Group tasks by version
            groupTasksByVersion();
        }
        
        repaint();
    }
    
    /**
     * Group tasks by version instead of service
     */
    private void groupTasksByVersion() {
        versionGroups.clear();
        uniqueVersions.clear();
        versionLanes.clear();
        versionColors.clear();
        
        // Group tasks by their version
        for (Task task : tasks) {
            String version = deriveVersion(task.sequenceId);
            
            if (!versionGroups.containsKey(version)) {
                versionGroups.put(version, new ArrayList<>());
                uniqueVersions.add(version);
            }
            versionGroups.get(version).add(task);
        }
        
        // Sort versions
        Collections.sort(uniqueVersions);
        
        // Assign lanes and colors to versions
        for (int i = 0; i < uniqueVersions.size(); i++) {
            String version = uniqueVersions.get(i);
            versionLanes.put(version, i);
            versionColors.put(version, colorPalette[i % colorPalette.length]);
        }
        
        // Update preferred size when switching to version display
        int width = Math.max(1200, maxId * 10 + 150);
        int topMargin = Math.round(80 * fontScaleFactor);
        int bottomPadding = Math.round(15 * fontScaleFactor);  // REDUCED FROM 22
        int numLanes = uniqueVersions.size();
        int laneHeight = Math.round(45 * fontScaleFactor);
        int chartHeight = numLanes * laneHeight;
        int totalHeight = topMargin + chartHeight + bottomPadding;
        
        setPreferredSize(new Dimension(width, totalHeight));
        revalidate();
    }
    
    /**
     * Update all font objects based on current size settings
     */
    private void updateFonts() {
        titleFont = new Font("Arial", Font.BOLD, Math.round(titleFontSize * fontScaleFactor));
        labelFont = new Font("Arial", Font.PLAIN, Math.round(labelFontSize * fontScaleFactor));
        axisLabelFont = new Font("Arial", Font.PLAIN, Math.round(axisLabelFontSize * fontScaleFactor));
        tooltipFont = new Font("Arial", Font.PLAIN, Math.round(tooltipFontSize * fontScaleFactor));
    }
    
    /**
     * Set the font scale factor (1.0 = 100%, 1.5 = 150%, etc.)
     */
    public void setFontScaleFactor(float scaleFactor) {
        this.fontScaleFactor = scaleFactor;
        updateFonts();
        
        // Update preferred size if fonts are much larger
        if (scaleFactor > 1.5f) {
            int width = Math.max(900, maxId * 7 + 200);
            int height = Math.max(600, Math.round(400 * scaleFactor));
            setPreferredSize(new Dimension(width, height));
            revalidate();
        }
        
        repaint();
    }
    
    /**
     * Calculate the required left margin based on legend layout
     */
    private int calculateLeftMargin(Graphics2D g2, List<String> displayGroups) {
        if (compactMode && legendTextBelow) {
            // When text is below, we need enough width for the longest label
            FontMetrics fm = g2.getFontMetrics(new Font("Arial", Font.PLAIN, Math.round(10 * fontScaleFactor)));
            int maxLabelWidth = 0;
            for (String group : displayGroups) {
                maxLabelWidth = Math.max(maxLabelWidth, fm.stringWidth(group));
            }
            // Return enough space for the text plus some padding
            return Math.max(Math.round(80 * fontScaleFactor), maxLabelWidth + 25);
        } else if (compactMode) {
            // Find the longest label to determine minimum margin
            FontMetrics fm = g2.getFontMetrics(labelFont);
            int maxLabelWidth = 0;
            for (String group : displayGroups) {
                maxLabelWidth = Math.max(maxLabelWidth, fm.stringWidth(group));
            }
            // Add space for color box and padding
            return Math.round((maxLabelWidth + 40) * fontScaleFactor);
        } else {
            // Original spacing
            return Math.round(180 * fontScaleFactor);
        }
    }
    
    /**
     * Load ALL data from the Derby database dynamically
     */
    protected void loadDataFromDatabase() {
        Connection conn = null;
        Statement stmt = null;
        ResultSet rs = null;
        
        try {
            // Register Derby driver
            DriverManager.registerDriver(new EmbeddedDriver());
            
            // Connect to database
            conn = DriverManager.getConnection(DB_URL);
            stmt = conn.createStatement();
            
            // Clear existing data
            tasks.clear();
            uniqueServices.clear();
            serviceLanes.clear();
            serviceColors.clear();
            maxQueueTime = 1;
            
            // Try PROCESSMEASUREMENTS first
            boolean dataLoaded = false;
            try {
                String query = "SELECT id, serviceName, sequenceID, " +
                              "ELAPSEDTIME as processingTime, " +
                              "(tokenArrivalTime - WORKFLOWSTARTTIME) as queueTime " +
                              "FROM PROCESSMEASUREMENTS " +
                              "WHERE serviceName IS NOT NULL " +
                              "ORDER BY id " +
                              "FETCH FIRST 100 ROWS ONLY";
                
                System.out.println("Attempting to load from SERVICEMEASUREMENTS...");
                rs = stmt.executeQuery(query);
                
                while (rs.next()) {
                    int id = rs.getInt("id");
                    String serviceName = rs.getString("serviceName");
                    int sequenceId = rs.getInt("sequenceID");
                    long processingTime = Math.max(0, rs.getLong("processingTime"));
                    long queueTime = Math.max(0, rs.getLong("queueTime"));
                    
                    Task task = new Task(id, serviceName, sequenceId, processingTime);
                    task.queueTime = queueTime;
                    tasks.add(task);
                    
                    // Track unique services
                    if (!uniqueServices.contains(serviceName)) {
                        uniqueServices.add(serviceName);
                    }
                    
                    // Update maximums
                    if (id > maxId) maxId = id;
                    if (processingTime > maxTime) maxTime = processingTime;
                    if (queueTime > maxQueueTime) maxQueueTime = queueTime;
                    
                    dataLoaded = true;
                }
                rs.close();
            } catch (SQLException e) {
                System.out.println("SERVICEMEASUREMENTS not available: " + e.getMessage());
            }
            
            if (dataLoaded) {
                // Sort services alphabetically for consistent ordering
                Collections.sort(uniqueServices);
                
                // Assign lanes and colors dynamically
                for (int i = 0; i < uniqueServices.size(); i++) {
                    String service = uniqueServices.get(i);
                    serviceLanes.put(service, i);
                    serviceColors.put(service, colorPalette[i % colorPalette.length]);
                }
                
                System.out.println("Loaded " + tasks.size() + " tasks from database");
                System.out.println("Found " + uniqueServices.size() + " unique services: " + uniqueServices);
                System.out.println("Max queue time: " + maxQueueTime + " ms");
                
                // Set maxId to number of tasks + 1 for proper scaling
                this.maxId = Math.max(tasks.size() + 1, 1);
                
                // Ensure maxTime is valid
                if (this.maxTime <= 0) {
                    this.maxTime = 1000;
                    System.out.println("Warning: maxTime was invalid, setting to default 1000");
                }
                
                System.out.println("MaxId: " + maxId + ", MaxTime: " + maxTime);

                // Update preferred size based on data - wider to accommodate queue bars
                int width = Math.max(1200, maxId * 10 + 150); // Increased width for queue bars
                int height = Math.round(400 * fontScaleFactor);
                setPreferredSize(new Dimension(width, height));
                revalidate();
                repaint();
            } else {
                System.err.println("No data found in database!");
                JOptionPane.showMessageDialog(this, 
                    "No data found in database.\nPlease ensure the database is populated.", 
                    "Database Error", 
                    JOptionPane.ERROR_MESSAGE);
            }
            
        } catch (SQLException e) {
            System.err.println("Database connection error: " + e.getMessage());
            e.printStackTrace();
            JOptionPane.showMessageDialog(this, 
                "Database connection failed:\n" + e.getMessage(), 
                "Database Error", 
                JOptionPane.ERROR_MESSAGE);
        } finally {
            // Close resources
            try {
                if (rs != null) rs.close();
                if (stmt != null) stmt.close();
                if (conn != null) conn.close();
            } catch (SQLException e) {
                System.err.println("Error closing database resources: " + e.getMessage());
            }
        }
    }
    
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        if (tasks.isEmpty()) {
            g2.setFont(titleFont);
            g2.drawString("No data to display", getWidth() / 2 - 60, getHeight() / 2);
            return;
        }

        // Determine which grouping to use
        List<String> displayGroups = displayByVersion ? uniqueVersions : uniqueServices;
        Map<String, Integer> displayLanes = displayByVersion ? versionLanes : serviceLanes;
        Map<String, Color> displayColors = displayByVersion ? versionColors : serviceColors;

        if (displayGroups.isEmpty()) {
            if (displayByVersion) {
                groupTasksByVersion();
                displayGroups = uniqueVersions;
                displayLanes = versionLanes;
                displayColors = versionColors;
            }

            if (displayGroups.isEmpty()) {
                g2.setFont(titleFont);
                g2.drawString("No data to display", getWidth() / 2 - 60, getHeight() / 2);
                return;
            }
        }

        // Calculate dynamic left margin based on legend layout
        int leftMargin = calculateLeftMargin(g2, displayGroups);
        int topMargin = Math.round(80 * fontScaleFactor);
        int rightMargin = 50;

        int chartWidth = getWidth() - leftMargin - rightMargin;

        // Calculate dynamic lane and chart dimensions - REDUCED BOTTOM PADDING
        int numLanes = displayGroups.size();
        int fullLaneHeight = (getHeight() - topMargin - 30) / numLanes;  // REDUCED FROM 80 TO 30
        int laneHeight = (int) (fullLaneHeight * 0.80);
        int chartHeight = laneHeight * numLanes;

        // Draw title
        g2.setFont(titleFont);
        String title = displayByVersion ?
            "Service Execution Timeline by Version (with Queue Time)" :
            "Service Execution Timeline by Service (with Queue Time)";
        FontMetrics fm = g2.getFontMetrics(titleFont);
        int titleWidth = fm.stringWidth(title);
        g2.drawString(title, (getWidth() - titleWidth) / 2, Math.round(30 * fontScaleFactor));

        // Draw queue time legend
        if (showQueueTime) {
            g2.setFont(new Font("Arial", Font.PLAIN, Math.round(10 * fontScaleFactor)));
            int legendY = Math.round(50 * fontScaleFactor);
            int legendX = leftMargin;

            g2.setColor(Color.BLACK);
            g2.drawString("Queue Time: ", legendX, legendY);
            legendX += 70;

            // Show color coding legend if enabled
            if (colorCodeQueueTime) {
                // Minimal delay
                g2.setColor(new Color(0, 200, 0, 60));
                g2.fillRect(legendX, legendY - 10, 20, 10);
                g2.setColor(Color.BLACK);
                g2.drawString("<2s", legendX + 25, legendY);
                legendX += 60;

                // Moderate delay
                g2.setColor(new Color(255, 165, 0, 80));
                g2.fillRect(legendX, legendY - 10, 20, 10);
                g2.setColor(Color.BLACK);
                g2.drawString("2-5s", legendX + 25, legendY);
                legendX += 60;

                // Severe delay
                g2.setColor(new Color(255, 0, 0, 80));
                g2.fillRect(legendX, legendY - 10, 20, 10);
                g2.setColor(Color.BLACK);
                g2.drawString(">5s", legendX + 25, legendY);
            } else {
                g2.drawString("(shown as semi-transparent bars)", legendX, legendY);
            }
        }

        // Draw horizontal lane lines
        g2.setColor(Color.LIGHT_GRAY);
        for (int i = 0; i <= numLanes; i++) {
            int y = topMargin + i * laneHeight;
            g2.drawLine(leftMargin, y, leftMargin + chartWidth, y);  // Changed to use leftMargin + chartWidth
        }

        // Draw lane labels with improved positioning
        g2.setFont(labelFont);
        FontMetrics labelFm = g2.getFontMetrics(labelFont);

        if (legendTextBelow) {
            // Draw colored rectangles with text on the line below
            int legendX = 10;
            int boxSize = Math.round(15 * fontScaleFactor);
            Font smallFont = new Font("Arial", Font.PLAIN, Math.round(10 * fontScaleFactor));

            for (String group : displayGroups) {
                Integer laneIndex = displayLanes.get(group);
                if (laneIndex == null) continue;

                int centerY = topMargin + laneIndex * laneHeight + laneHeight / 2;

                // Draw colored rectangle centered
                Color groupColor = displayColors.get(group);
                g2.setColor(groupColor);
                g2.fillRect(legendX, centerY - boxSize / 2, boxSize, boxSize);
                g2.setColor(groupColor.darker());
                g2.drawRect(legendX, centerY - boxSize / 2, boxSize, boxSize);

                // Draw group name on the line BELOW the box
                g2.setColor(Color.BLACK);
                g2.setFont(smallFont);

                FontMetrics smallFm = g2.getFontMetrics(smallFont);
                String displayText = group;

                // Position text below the box
                int textX = legendX;
                int textY = centerY + boxSize / 2 + smallFm.getHeight() + 2;

                // Check if text needs truncation to fit in the left margin area
                int availableWidth = leftMargin - 10;

                if (smallFm.stringWidth(displayText) > availableWidth) {
                    // Truncate with ellipsis if needed
                    while (smallFm.stringWidth(displayText + "...") > availableWidth && displayText.length() > 1) {
                        displayText = displayText.substring(0, displayText.length() - 1);
                    }
                    if (displayText.length() > 1) {
                        displayText += "...";
                    }
                }

                g2.drawString(displayText, textX, textY);
                g2.setFont(labelFont); // Restore normal font
            }
        } else {
            // Original horizontal layout with colored rectangle and text side by side
            for (String group : displayGroups) {
                Integer laneIndex = displayLanes.get(group);
                if (laneIndex == null) continue;

                int y = topMargin + laneIndex * laneHeight + laneHeight / 2;

                // Draw colored rectangle for group
                Color groupColor = displayColors.get(group);
                g2.setColor(groupColor);
                int rectSize = Math.round(15 * fontScaleFactor);
                g2.fillRect(10, y - rectSize / 2, rectSize, rectSize);

                // Draw group name
                g2.setColor(Color.BLACK);
                g2.drawString(group, 30, y + 4);
            }
        }

        // Draw vertical grid lines
        g2.setColor(Color.LIGHT_GRAY);
        g2.setStroke(new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0, new float[] {
            2
        }, 0));

        int actualMaxId = Math.min(tasks.size(), maxDisplayTasks);
        int gridInterval = Math.max(1, actualMaxId / 10);

        g2.setFont(axisLabelFont);
        for (int i = 0; i <= actualMaxId + 1; i += gridInterval) {
            int x = leftMargin + (i * chartWidth / (actualMaxId + 1));
            int y = topMargin + chartHeight;
            g2.drawLine(x, topMargin, x, y);

            // X-axis labels (relative to chart bottom) - CLOSER TO CHART
            g2.setColor(Color.GRAY);
            String label = String.valueOf(i);
            FontMetrics axisfm = g2.getFontMetrics(axisLabelFont);
            int labelWidth = axisfm.stringWidth(label);
            g2.drawString(label, x - labelWidth / 2, y + Math.round(15 * fontScaleFactor));  // REDUCED FROM 20
            g2.setColor(Color.LIGHT_GRAY);
        }

        // Draw X-axis label (relative to chart bottom) - ADJUSTED POSITION
        g2.setColor(Color.BLACK);
        g2.setFont(new Font("Arial", Font.BOLD, Math.round(12 * fontScaleFactor)));
        String xLabel = "Execution Order (ID)";
        FontMetrics xfm = g2.getFontMetrics();
        int xLabelWidth = xfm.stringWidth(xLabel);
        g2.drawString(xLabel, (getWidth() - xLabelWidth) / 2, topMargin + chartHeight + Math.round(40 * fontScaleFactor)); // INCREASED FROM 25 TO 40

        // Reset stroke
        g2.setStroke(new BasicStroke(1));

        // First pass: Draw continuous delay heat strips for each lane
        if (showQueueTime) {
            for (int laneIdx = 0; laneIdx < displayGroups.size(); laneIdx++) {
                String group = displayGroups.get(laneIdx);

                // Calculate position for delay strip (top portion of lane)
                int stripY = topMargin + (laneIdx * laneHeight) + 5;
                int stripHeight = Math.max(10, laneHeight / 5);

                // Create a list of tasks in this lane, sorted by ID
                List<Task> laneTasks = new ArrayList<>();
                for (int i = 0; i < Math.min(tasks.size(), maxDisplayTasks); i++) {
                    Task task = tasks.get(i);
                    Integer taskLaneIndex;

                    if (displayByVersion) {
                        String version = deriveVersion(task.sequenceId);
                        taskLaneIndex = versionLanes.get(version);
                    } else {
                        taskLaneIndex = serviceLanes.get(task.service);
                    }

                    if (taskLaneIndex != null && taskLaneIndex == laneIdx) {
                        laneTasks.add(task);
                    }
                }

                // Sort by ID to ensure correct order
                laneTasks.sort((a, b) -> Integer.compare(a.id, b.id));

                // Draw continuous heat strip only where tasks exist
                if (!laneTasks.isEmpty()) {
                    // Find the first and last task positions
                    Task firstTask = laneTasks.get(0);
                    Task lastTask = laneTasks.get(laneTasks.size() - 1);
                    int firstX = leftMargin + ((firstTask.id - 1) * chartWidth / maxId);
                    int lastX = leftMargin + ((lastTask.id - 1) * chartWidth / maxId);

                    // Calculate the width to extend slightly beyond the last task
                    int lastTaskWidth = (int) (Math.log(lastTask.processingTime + 1) / Math.log(maxTime + 1) * (chartWidth / maxId) * 1.8);
                    lastTaskWidth = Math.max(6, Math.min(lastTaskWidth, chartWidth / maxId - 2));
                    int endX = Math.min(lastX + lastTaskWidth + 10, leftMargin + chartWidth);

                    // Draw the strip only between first and last task
                    for (int i = 0; i < laneTasks.size(); i++) {
                        Task task = laneTasks.get(i);
                        int taskX = leftMargin + ((task.id - 1) * chartWidth / maxId);

                        // Get color for this task's queue time
                        Color taskColor = getQueueTimeColor(task.queueTime);

                        // Calculate segment boundaries
                        int segmentStart = taskX;
                        int segmentEnd;

                        if (i < laneTasks.size() - 1) {
                            // Not the last task - extend to halfway to next task
                            Task nextTask = laneTasks.get(i + 1);
                            int nextX = leftMargin + ((nextTask.id - 1) * chartWidth / maxId);
                            segmentEnd = taskX + (nextX - taskX) / 2;

                            // Draw this segment
                            g2.setColor(taskColor);
                            g2.fillRect(segmentStart, stripY, segmentEnd - segmentStart, stripHeight);

                            // Draw gradient transition to next task
                            Color nextColor = getQueueTimeColor(nextTask.queueTime);
                            GradientPaint gradient = new GradientPaint(
                                segmentEnd, stripY, taskColor,
                                nextX, stripY, nextColor);
                            g2.setPaint(gradient);
                            g2.fillRect(segmentEnd, stripY, nextX - segmentEnd, stripHeight);
                        } else {
                            // Last task - just extend a bit beyond
                            segmentEnd = endX;
                            g2.setColor(taskColor);
                            g2.fillRect(segmentStart, stripY, segmentEnd - segmentStart, stripHeight);
                        }
                    }

                    // Draw a subtle border around the active strip segment only
                    g2.setColor(new Color(100, 100, 100, 100));
                    g2.drawRect(firstX, stripY, endX - firstX, stripHeight);
                    
                    // Add "Wait Time" label in the left margin area (Y-axis region)
                    g2.setColor(new Color(100, 100, 100));
                    g2.setFont(new Font("Arial", Font.ITALIC, 8));
                    FontMetrics queueLabelFm = g2.getFontMetrics();
                    String queueLabel = "Queue Time";
                    
                    // Position in the margin area, to the right of service labels
                    int labelX = leftMargin - queueLabelFm.stringWidth(queueLabel) - 10;
                    int labelY = stripY + (stripHeight / 2) + (queueLabelFm.getHeight() / 4);
                    
                    // Only draw once per lane (check if this is the first task in the lane)
                    if (laneTasks.get(0) == firstTask) {
                        g2.drawString(queueLabel, labelX, labelY);
                    }

                    // Draw labels for significant delays, ensuring they don't overlap
                    int lastLabelX = -100; // A value that ensures the first label is drawn
                    for (int i = 0; i < laneTasks.size(); i++) {
                        Task task = laneTasks.get(i);
                        int taskX = leftMargin + ((task.id - 1) * chartWidth / maxId);
                        int taskWidth = (int) (Math.log(task.processingTime + 1) / Math.log(maxTime + 1) * (chartWidth / maxId) * 1.8);
                        
                        // A queue time of 100ms is considered significant for labeling
                        // Also check if there's enough space for the label
                        if (task.queueTime > 100 && (taskX - lastLabelX) > 30) {
                            g2.setColor(Color.BLACK);
                            g2.setFont(new Font("Arial", Font.BOLD, 9));
                            String delayStr = String.format("%.1fs", task.queueTime / 1000.0);
                            FontMetrics delayFm = g2.getFontMetrics();
                            
                            // Calculate position to center the text vertically within the heat strip
                            int textY = stripY + (stripHeight / 2) + (delayFm.getHeight() / 4);
                            
                            g2.drawString(delayStr, taskX + 2, textY);
                            lastLabelX = taskX; // Update the position of the last drawn label
                        }
                    }
                }
            }
        }

        // Second pass: Draw processing bars below the delay strips
        for (int i = 0; i < Math.min(tasks.size(), maxDisplayTasks); i++) {
            Task task = tasks.get(i);
            Integer laneIndex;
            Color barColor;

            if (displayByVersion) {
                String version = deriveVersion(task.sequenceId);
                laneIndex = versionLanes.get(version);
                barColor = versionColors.get(version);
            } else {
                laneIndex = serviceLanes.get(task.service);
                barColor = serviceColors.get(task.service);
            }

            if (laneIndex == null) continue;

            int x = leftMargin + ((task.id - 1) * chartWidth / maxId);

            // Calculate processing bar dimensions
            int barWidth = (int) (Math.log(task.processingTime + 1) / Math.log(maxTime + 1) * (chartWidth / maxId) * 1.8);
            barWidth = Math.max(6, Math.min(barWidth, chartWidth / maxId - 2));

            // Position bars in the lower portion of the lane
            int barHeight = laneHeight * 2 / 5;
            int y = topMargin + (laneIndex * laneHeight) + (laneHeight - barHeight) - 5;

            // Draw processing bar
            g2.setColor(barColor);
            g2.fillRoundRect(x, y, barWidth, barHeight, 5, 5);

            // Draw border
            g2.setColor(barColor.darker());
            g2.drawRoundRect(x, y, barWidth, barHeight, 5, 5);

            // Add task ID for small datasets
            if (tasks.size() <= 30 && barWidth > 15) {
                g2.setColor(Color.WHITE);
                g2.setFont(new Font("Arial", Font.PLAIN, 9));
                String idStr = String.valueOf(task.id);
                FontMetrics idFm = g2.getFontMetrics();
                int textWidth = idFm.stringWidth(idStr);
                g2.drawString(idStr, x + (barWidth - textWidth) / 2, y + barHeight / 2 + 3);
            }
        }

        // Draw tooltip if hovering
        if (hoveredTask != null) {
            drawTooltip(g2, hoveredTask);
        }
    }
    
    private Task getTaskAt(int mouseX, int mouseY) {
        if (tasks.isEmpty()) return null;
        
        List<String> displayGroups = displayByVersion ? uniqueVersions : uniqueServices;
        Map<String, Integer> displayLanes = displayByVersion ? versionLanes : serviceLanes;
        
        if (displayGroups.isEmpty()) return null;
        
        // Calculate dynamic left margin
        Graphics2D g2 = (Graphics2D) getGraphics();
        int leftMargin = calculateLeftMargin(g2, displayGroups);
        int topMargin = Math.round(80 * fontScaleFactor);
        int rightMargin = 13;  // Reduced by 75% from 50
        int chartWidth = getWidth() - leftMargin - rightMargin;
        
        // Recalculate lane height based on the new logic - MATCH THE REDUCED VALUE
        int numLanes = displayGroups.size();
        int fullLaneHeight = (getHeight() - topMargin - 15) / numLanes;  // REDUCED FROM 30 TO 15
        int laneHeight = (int) (fullLaneHeight * 0.80);
        
        int barHeight = laneHeight * 2 / 5;
        
        for (int i = 0; i < Math.min(tasks.size(), maxDisplayTasks); i++) {
            Task task = tasks.get(i);
            Integer laneIndex;
            
            if (displayByVersion) {
                String version = deriveVersion(task.sequenceId);
                laneIndex = versionLanes.get(version);
            } else {
                laneIndex = serviceLanes.get(task.service);
            }
            
            if (laneIndex == null) continue;
            
            int x = leftMargin + ((task.id - 1) * chartWidth / maxId);
            
            // Calculate both processing and queue bar dimensions
            int barWidth = (int)(Math.log(task.processingTime + 1) / Math.log(maxTime + 1) * (chartWidth / maxId) * 1.8);
            barWidth = Math.max(6, Math.min(barWidth, chartWidth / maxId - 2));
            
            int queueBarWidth = 0;
            if (showQueueTime && task.queueTime > 0) {
                queueBarWidth = (int)(Math.log(task.queueTime + 1) / Math.log(maxQueueTime + 1) * (chartWidth / maxId) * 2.5);
                queueBarWidth = Math.max(2, queueBarWidth);
            }
            
            int y = topMargin + (laneIndex * laneHeight) + ((laneHeight - barHeight) / 2);
            
            // Check both processing bar and queue bar areas
            Rectangle processRect = new Rectangle(x, y, barWidth, barHeight);
            Rectangle queueRect = new Rectangle(x - queueBarWidth, y, queueBarWidth, barHeight);
            
            if (processRect.contains(mouseX, mouseY) || queueRect.contains(mouseX, mouseY)) {
                return task;
            }
        }
        return null;
    }
    
    private void drawTooltip(Graphics2D g2, Task task) {
        String version = deriveVersion(task.sequenceId);
        
        // Calculate wait ratio for additional insight
        double waitRatio = task.queueTime > 0 ? 
            (double)task.queueTime / (task.queueTime + task.processingTime) * 100 : 0;
        
        String[] lines = {
            task.service,
            "Version: " + version,
            "Seq ID: " + task.sequenceId,
            "Processing Time: " + task.processingTime + " ms",
            "Queue Time: " + task.queueTime + " ms",
            String.format("Wait Ratio: %.1f%%", waitRatio),
            "Execution: #" + task.id
        };
        
        g2.setFont(tooltipFont);
        int maxWidth = 0;
        FontMetrics fm = g2.getFontMetrics();
        for (String line : lines) {
            maxWidth = Math.max(maxWidth, fm.stringWidth(line));
        }
        
        int tooltipWidth = maxWidth + 20;
        int lineHeight = Math.round(20 * fontScaleFactor);
        int tooltipHeight = lines.length * lineHeight + 10;
        int x = getMousePosition() != null ? getMousePosition().x + 10 : 100;
        int y = getMousePosition() != null ? getMousePosition().y - tooltipHeight : 100;
        
        // Keep tooltip on screen
        if (x + tooltipWidth > getWidth()) x = getWidth() - tooltipWidth - 10;
        if (y < 0) y = 10;
        
        // Draw tooltip background
        g2.setColor(new Color(0, 0, 0, 230));
        g2.fillRoundRect(x, y, tooltipWidth, tooltipHeight, 5, 5);
        
        // Draw text with color coding for queue time
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith("Queue Time:")) {
                // Color code the queue time line
                if (task.queueTime > 5000) {
                    g2.setColor(new Color(255, 100, 100)); // Light red
                } else if (task.queueTime > 2000) {
                    g2.setColor(new Color(255, 200, 100)); // Light orange
                } else {
                    g2.setColor(new Color(100, 255, 100)); // Light green
                }
            } else {
                g2.setColor(Color.WHITE);
            }
            g2.drawString(lines[i], x + 10, y + lineHeight + i * lineHeight);
        }
    }
    
    public static void createAndShowGUI(SwingGanttChart_WithLatency_v1 chart) {
        JFrame frame = new JFrame("Service Performance Gantt Chart with Queue Time Analysis");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        
        // Create menu bar with export options
        JMenuBar menuBar = new JMenuBar();
        
        // File menu
        JMenu fileMenu = new JMenu("File");
        
        // Add export options
        JMenuItem exportPDFItem = new JMenuItem("Export to PDF...");
        exportPDFItem.addActionListener(e -> {
            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setSelectedFile(new File("gantt_chart.pdf"));
            fileChooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("PDF files", "pdf"));
            
            if (fileChooser.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) {
                File file = fileChooser.getSelectedFile();
                String filename = file.getAbsolutePath();
                if (!filename.endsWith(".pdf")) {
                    filename += ".pdf";
                }
                chart.exportToPDF(filename);
            }
        });
        
        JMenuItem exportLatexItem = new JMenuItem("Export to LaTeX (TikZ)...");
        exportLatexItem.addActionListener(e -> {
            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setSelectedFile(new File("gantt_chart.tex"));
            fileChooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("LaTeX files", "tex"));
            
            if (fileChooser.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) {
                File file = fileChooser.getSelectedFile();
                String filename = file.getAbsolutePath();
                if (!filename.endsWith(".tex")) {
                    filename += ".tex";
                }
                chart.exportToLaTeX(filename);
            }
        });
        
        JMenuItem exportLatexTableItem = new JMenuItem("Export to LaTeX Table...");
        exportLatexTableItem.addActionListener(e -> {
            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setSelectedFile(new File("gantt_data_table.tex"));
            fileChooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("LaTeX files", "tex"));
            
            if (fileChooser.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) {
                File file = fileChooser.getSelectedFile();
                String filename = file.getAbsolutePath();
                if (!filename.endsWith(".tex")) {
                    filename += ".tex";
                }
                chart.exportToLaTeXTable(filename);
            }
        });
        
        fileMenu.add(exportPDFItem);
        fileMenu.add(exportLatexItem);
        fileMenu.add(exportLatexTableItem);
        fileMenu.addSeparator();
        
        JMenuItem exitItem = new JMenuItem("Exit");
        exitItem.addActionListener(e -> System.exit(0));
        fileMenu.add(exitItem);
        
        // View menu with font size options and legend layout
        JMenu viewMenu = new JMenu("View");
        
        // Add Queue Time submenu
        JMenu queueMenu = new JMenu("Queue Time Display");
        
        JCheckBoxMenuItem showQueueItem = new JCheckBoxMenuItem("Show Queue Time");
        showQueueItem.setSelected(true);
        showQueueItem.addActionListener(e -> chart.setShowQueueTime(showQueueItem.isSelected()));
        queueMenu.add(showQueueItem);
        
        JCheckBoxMenuItem colorCodeItem = new JCheckBoxMenuItem("Color Code by Severity");
        colorCodeItem.setSelected(true);
        colorCodeItem.addActionListener(e -> chart.setColorCodeQueueTime(colorCodeItem.isSelected()));
        queueMenu.add(colorCodeItem);
        
        viewMenu.add(queueMenu);
        viewMenu.addSeparator();
        
        // Add Display Mode submenu
        JMenu displayModeMenu = new JMenu("Display Mode");
        ButtonGroup displayGroup = new ButtonGroup();
        
        JRadioButtonMenuItem serviceDisplayItem = new JRadioButtonMenuItem("Display by Service");
        serviceDisplayItem.setSelected(true);
        serviceDisplayItem.addActionListener(e -> chart.setDisplayByVersion(false));
        displayGroup.add(serviceDisplayItem);
        displayModeMenu.add(serviceDisplayItem);
        
        JRadioButtonMenuItem versionDisplayItem = new JRadioButtonMenuItem("Display by Version");
        versionDisplayItem.addActionListener(e -> chart.setDisplayByVersion(true));
        displayGroup.add(versionDisplayItem);
        displayModeMenu.add(versionDisplayItem);
        
        viewMenu.add(displayModeMenu);
        viewMenu.addSeparator();
        
        // Add Truncation submenu
        JMenu truncateMenu = new JMenu("Display Range");
        ButtonGroup truncateGroup = new ButtonGroup();
        
        // Truncation options
        int[] truncateOptions = {20, 40, 60, 80, 100, Integer.MAX_VALUE};
        String[] truncateLabels = {"First 20 Tasks", "First 40 Tasks", "First 60 Tasks", 
                                   "First 80 Tasks", "First 100 Tasks", "Show All"};
        
        for (int i = 0; i < truncateOptions.length; i++) {
            JRadioButtonMenuItem truncateItem = new JRadioButtonMenuItem(truncateLabels[i]);
            final int maxTasks = truncateOptions[i];
            
            // Set "Show All" as default
            if (maxTasks == Integer.MAX_VALUE) {
                truncateItem.setSelected(true);
            }
            
            truncateItem.addActionListener(e -> chart.setMaxDisplayTasks(maxTasks));
            truncateGroup.add(truncateItem);
            truncateMenu.add(truncateItem);
        }
        
        viewMenu.add(truncateMenu);
        viewMenu.addSeparator();
        
        // Add Legend Layout submenu
        JMenu legendMenu = new JMenu("Legend Layout");
        
        JCheckBoxMenuItem compactModeItem = new JCheckBoxMenuItem("Compact Mode");
        compactModeItem.setSelected(true);
        compactModeItem.addActionListener(e -> chart.setCompactMode(compactModeItem.isSelected()));
        legendMenu.add(compactModeItem);
        
        JCheckBoxMenuItem textBelowItem = new JCheckBoxMenuItem("Text Below Colors");
        textBelowItem.setSelected(true);
        textBelowItem.addActionListener(e -> chart.setLegendTextBelow(textBelowItem.isSelected()));
        legendMenu.add(textBelowItem);
        
        viewMenu.add(legendMenu);
        
        // Font menu
        JMenu fontMenu = new JMenu("Font");
        
        // Font size scaling submenu
        JMenu fontScaleMenu = new JMenu("Font Size");
        ButtonGroup fontScaleGroup = new ButtonGroup();
        
        // Font scale options
        float[] scaleOptions = {0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f};
        String[] scaleLabels = {"75%", "100% (Default)", "125%", "150%", "175%", "200%"};
        
        for (int i = 0; i < scaleOptions.length; i++) {
            JRadioButtonMenuItem scaleItem = new JRadioButtonMenuItem(scaleLabels[i]);
            final float scale = scaleOptions[i];
            
            // Set 100% as default
            if (scale == 1.0f) {
                scaleItem.setSelected(true);
            }
            
            scaleItem.addActionListener(e -> chart.setFontScaleFactor(scale));
            fontScaleGroup.add(scaleItem);
            fontScaleMenu.add(scaleItem);
        }
        
        fontMenu.add(fontScaleMenu);
        fontMenu.addSeparator();
        
        // Individual font size adjustments
        JMenu individualFontMenu = new JMenu("Individual Font Sizes");
        
        // Title font size
        JMenu titleFontMenu = new JMenu("Title Font");
        ButtonGroup titleGroup = new ButtonGroup();
        int[] titleSizes = {12, 14, 16, 18, 20, 24};
        for (int size : titleSizes) {
            JRadioButtonMenuItem sizeItem = new JRadioButtonMenuItem(size + " pt");
            final int fontSize = size;
            if (size == 16) sizeItem.setSelected(true); // Default
            sizeItem.addActionListener(e -> {
                chart.titleFontSize = fontSize;
                chart.updateFonts();
                chart.repaint();
            });
            titleGroup.add(sizeItem);
            titleFontMenu.add(sizeItem);
        }
        individualFontMenu.add(titleFontMenu);
        
        // Label font size
        JMenu labelFontMenu = new JMenu("Label Font");
        ButtonGroup labelGroup = new ButtonGroup();
        int[] labelSizes = {10, 11, 12, 13, 14, 16};
        for (int size : labelSizes) {
            JRadioButtonMenuItem sizeItem = new JRadioButtonMenuItem(size + " pt");
            final int fontSize = size;
            if (size == 12) sizeItem.setSelected(true); // Default
            sizeItem.addActionListener(e -> {
                chart.labelFontSize = fontSize;
                chart.updateFonts();
                chart.repaint();
            });
            labelGroup.add(sizeItem);
            labelFontMenu.add(sizeItem);
        }
        individualFontMenu.add(labelFontMenu);
        
        // Axis label font size
        JMenu axisFontMenu = new JMenu("Axis Label Font");
        ButtonGroup axisGroup = new ButtonGroup();
        int[] axisSizes = {8, 9, 10, 11, 12, 14};
        for (int size : axisSizes) {
            JRadioButtonMenuItem sizeItem = new JRadioButtonMenuItem(size + " pt");
            final int fontSize = size;
            if (size == 10) sizeItem.setSelected(true); // Default
            sizeItem.addActionListener(e -> {
                chart.axisLabelFontSize = fontSize;
                chart.updateFonts();
                chart.repaint();
            });
            axisGroup.add(sizeItem);
            axisFontMenu.add(sizeItem);
        }
        individualFontMenu.add(axisFontMenu);
        
        // Tooltip font size
        JMenu tooltipFontMenu = new JMenu("Tooltip Font");
        ButtonGroup tooltipGroup = new ButtonGroup();
        int[] tooltipSizes = {10, 11, 12, 13, 14, 16};
        for (int size : tooltipSizes) {
            JRadioButtonMenuItem sizeItem = new JRadioButtonMenuItem(size + " pt");
            final int fontSize = size;
            if (size == 12) sizeItem.setSelected(true); // Default
            sizeItem.addActionListener(e -> {
                chart.tooltipFontSize = fontSize;
                chart.updateFonts();
                chart.repaint();
            });
            tooltipGroup.add(sizeItem);
            tooltipFontMenu.add(sizeItem);
        }
        individualFontMenu.add(tooltipFontMenu);
        
        fontMenu.add(individualFontMenu);
        fontMenu.addSeparator();
        
        // Reset fonts to default
        JMenuItem resetFontsItem = new JMenuItem("Reset to Default");
        resetFontsItem.addActionListener(e -> {
            chart.titleFontSize = 16;
            chart.labelFontSize = 12;
            chart.axisLabelFontSize = 10;
            chart.tooltipFontSize = 12;
            chart.fontScaleFactor = 1.0f;
            chart.updateFonts();
            chart.repaint();
            
            // Reset radio buttons (need to update selected state)
            for (Component c : fontScaleMenu.getMenuComponents()) {
                if (c instanceof JRadioButtonMenuItem) {
                    JRadioButtonMenuItem item = (JRadioButtonMenuItem) c;
                    item.setSelected(item.getText().equals("100% (Default)"));
                }
            }
        });
        fontMenu.add(resetFontsItem);
        
        menuBar.add(fileMenu);
        menuBar.add(viewMenu);
        menuBar.add(fontMenu);
        frame.setJMenuBar(menuBar);
        
        // Add scroll pane
        JScrollPane scrollPane = new JScrollPane(chart);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        
        frame.add(scrollPane);
        frame.pack(); // Pack to preferred size first
        frame.setSize(Math.min(1400, frame.getWidth()), Math.min(500, frame.getHeight())); // Constrain height
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }
    
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            SwingGanttChart_WithLatency_v1 chart = new SwingGanttChart_WithLatency_v1();
            createAndShowGUI(chart);
        });
    }
}