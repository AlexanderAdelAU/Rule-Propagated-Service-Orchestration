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
import org.btsn.constants.VersionConstants;

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
 * Refactored to use linear scaling and VersionConstants
 */
public class SwingGanttChart_WithLatency_v1d extends JPanel {
    
    private static final String PROTOCOL = "jdbc:derby:";
    private static final String DB_NAME = "ServiceAnalysisDataBase";
    private static final String DB_URL = PROTOCOL + DB_NAME;
    
    private int maxDisplayTasks = Integer.MAX_VALUE;
    
    // Configurable font sizes
    private int titleFontSize = 16;
    private int labelFontSize = 12;
    private int axisLabelFontSize = 10;
    private int tooltipFontSize = 12;
    private float fontScaleFactor = 1.0f;
    
    private Font titleFont;
    private Font labelFont;
    private Font axisLabelFont;
    private Font tooltipFont;
    
    // Legend positioning options
    private boolean legendTextBelow = true;
    private boolean compactMode = true;
    
    
    // Version display feature
    private boolean displayByVersion = false;
    private Map<String, List<Task>> versionGroups = new HashMap<>();
    private List<String> uniqueVersions = new ArrayList<>();
    private Map<String, Color> versionColors = new HashMap<>();
    private Map<String, Integer> versionLanes = new HashMap<>();
    
    private long maxQueueTime = 1;
    private long maxElapsedTime = 1;
    
    
    public static class Task {
        int id;
        String service;
        int sequenceId;
        long processingTime;
        long queueTime;
        String businessServices = "Unresolved";
        int serviceCount;  // Number of services in this workflow
        long elapsedTime;  // Total workflow duration from PROCESSMEASUREMENTS
        
        public Task(int id, String service, int sequenceId, long processingTime) {
            this.id = id;
            this.service = service;
            this.sequenceId = sequenceId;
            this.processingTime = processingTime;
            this.serviceCount = 0;
            this.elapsedTime = 0;
        }
    }
    
    protected List<Task> tasks = new ArrayList<>();
    private Map<String, Color> serviceColors = new HashMap<>();
    private Map<String, Integer> serviceLanes = new HashMap<>();
    protected List<String> uniqueServices = new ArrayList<>();
    private int maxId = 1;
    private long maxTime = 1;
    private Task hoveredTask = null;
    
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
    
    public SwingGanttChart_WithLatency_v1d() {
        setBackground(Color.WHITE);
        setPreferredSize(new Dimension(900, 600));
        
        updateFonts();
        
        addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                hoveredTask = getTaskAt(e.getX(), e.getY());
                repaint();
            }
        });
        
        loadDataFromDatabase();
    }
    
    /**
     * Generate workflow summary report
     */
    /**
     * Generate workflow summary report
     */
    public String generateWorkflowSummaryReport() {
        StringBuilder report = new StringBuilder("CONCURRENT WORKFLOW OVERVIEW\n");
        report.append("One bar per observed root workflow, in arrival order. Bar size does not encode duration.\n");
        report.append("Measured waits: View > Service Queue Timings. Elapsed intervals: View > Measured Workflow Timeline.\n\n");
        Map<String, Integer> counts = new TreeMap<>();
        for (Task task : tasks) counts.merge(deriveVersion(task.sequenceId), 1, Integer::sum);
        for (Map.Entry<String, Integer> entry : counts.entrySet())
            report.append(entry.getKey()).append(": ").append(entry.getValue()).append(" workflows\n");
        report.append("\nArrival position | Root sequence | Version | Recorded service visits | Business services\n");
        for (int i = 0; i < tasks.size(); i++) {
            Task task = tasks.get(i);
            report.append(i + 1).append(" | ").append(task.sequenceId).append(" | ")
                .append(deriveVersion(task.sequenceId)).append(" | ").append(task.serviceCount)
                .append(" | ").append(task.businessServices).append('\n');
        }
        return report.toString();
    }

    /**
     * Export workflow summary to text file
     */
    public void exportWorkflowSummary(String filename) {
        try (PrintWriter writer = new PrintWriter(new FileWriter(filename))) {
            writer.print(generateWorkflowSummaryReport());
            
            JOptionPane.showMessageDialog(this,
                "Workflow summary exported successfully to: " + filename,
                "Export Success",
                JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this,
                "Error exporting workflow summary: " + e.getMessage(),
                "Export Error",
                JOptionPane.ERROR_MESSAGE);
            e.printStackTrace();
        }
    }
    
    /**
     * Export the chart to PDF format
     */
    public void exportToPDF(String filename) {
        try {
            revalidate();
            repaint();
            
            int exportWidth = getWidth();
            int exportHeight = getHeight();
            
            Container parent = getParent();
            if (parent instanceof JViewport) {
                JViewport viewport = (JViewport) parent;
                exportWidth = Math.max(viewport.getWidth(), getPreferredSize().width);
                exportHeight = Math.max(viewport.getHeight(), getPreferredSize().height);
                
                setSize(exportWidth, exportHeight);
                revalidate();
            }
            
            BufferedImage image = new BufferedImage(exportWidth, exportHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2 = image.createGraphics();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            
            g2.setColor(Color.WHITE);
            g2.fillRect(0, 0, exportWidth, exportHeight);
            
            paint(g2);
            g2.dispose();
            
            PDDocument document = new PDDocument();
            
            PDRectangle pageSize = new PDRectangle(exportWidth, exportHeight);
            PDPage page = new PDPage(pageSize);
            document.addPage(page);
            
            PDImageXObject pdImage = LosslessFactory.createFromImage(document, image);
            PDPageContentStream contentStream = new PDPageContentStream(document, page);
            
            contentStream.drawImage(pdImage, 0, 0, exportWidth, exportHeight);
            
            contentStream.close();
            
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
     * Export the chart to PNG format at specified DPI
     * @param filename The output filename
     * @param dpi The target DPI (e.g., 300 for print quality)
     */
    public void exportToPNG(String filename, int dpi) {
        try {
            revalidate();
            repaint();
            
            int baseWidth = getWidth();
            int baseHeight = getHeight();
            
            Container parent = getParent();
            if (parent instanceof JViewport) {
                JViewport viewport = (JViewport) parent;
                baseWidth = Math.max(viewport.getWidth(), getPreferredSize().width);
                baseHeight = Math.max(viewport.getHeight(), getPreferredSize().height);
            }
            
            // Calculate scale factor for target DPI (assuming 72 DPI base)
            double scaleFactor = dpi / 72.0;
            
            int exportWidth = (int) (baseWidth * scaleFactor);
            int exportHeight = (int) (baseHeight * scaleFactor);
            
            // Create high-resolution image
            BufferedImage image = new BufferedImage(exportWidth, exportHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2 = image.createGraphics();
            
            // Set high-quality rendering hints
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            
            // Fill background
            g2.setColor(Color.WHITE);
            g2.fillRect(0, 0, exportWidth, exportHeight);
            
            // Scale the graphics context
            g2.scale(scaleFactor, scaleFactor);
            
            // Temporarily resize for painting
            Dimension originalSize = getSize();
            setSize(baseWidth, baseHeight);
            
            // Paint the component
            paint(g2);
            
            // Restore original size
            setSize(originalSize);
            
            g2.dispose();
            
            // Write PNG with DPI metadata
            File outputFile = new File(filename);
            
            // Use ImageIO with metadata for DPI
            Iterator<javax.imageio.ImageWriter> writers = javax.imageio.ImageIO.getImageWritersByFormatName("png");
            if (writers.hasNext()) {
                javax.imageio.ImageWriter writer = writers.next();
                javax.imageio.stream.ImageOutputStream ios = javax.imageio.ImageIO.createImageOutputStream(outputFile);
                writer.setOutput(ios);
                
                // Set DPI metadata
                javax.imageio.metadata.IIOMetadata metadata = writer.getDefaultImageMetadata(
                    new javax.imageio.ImageTypeSpecifier(image), null);
                
                try {
                    // PNG metadata for DPI (pixels per meter)
                    int dotsPerMeter = (int) (dpi / 0.0254);
                    
                    org.w3c.dom.Node root = metadata.getAsTree("javax_imageio_1.0");
                    org.w3c.dom.NodeList children = root.getChildNodes();
                    
                    // Find or create Dimension node
                    org.w3c.dom.Node dimensionNode = null;
                    for (int i = 0; i < children.getLength(); i++) {
                        if (children.item(i).getNodeName().equals("Dimension")) {
                            dimensionNode = children.item(i);
                            break;
                        }
                    }
                    
                    if (dimensionNode == null) {
                        // Use IIOMetadataNode to create new Dimension node
                        dimensionNode = new javax.imageio.metadata.IIOMetadataNode("Dimension");
                        root.appendChild(dimensionNode);
                    }
                    
                    // Create HorizontalPixelSize element (in millimeters)
                    double mmPerPixel = 25.4 / dpi;
                    javax.imageio.metadata.IIOMetadataNode horzNode = new javax.imageio.metadata.IIOMetadataNode("HorizontalPixelSize");
                    horzNode.setAttribute("value", String.valueOf(mmPerPixel));
                    dimensionNode.appendChild(horzNode);
                    
                    javax.imageio.metadata.IIOMetadataNode vertNode = new javax.imageio.metadata.IIOMetadataNode("VerticalPixelSize");
                    vertNode.setAttribute("value", String.valueOf(mmPerPixel));
                    dimensionNode.appendChild(vertNode);
                    
                    metadata.mergeTree("javax_imageio_1.0", root);
                } catch (Exception metaEx) {
                    // If metadata fails, continue without it
                    System.out.println("Note: Could not set DPI metadata, continuing with export");
                }
                
                writer.write(null, new javax.imageio.IIOImage(image, null, metadata), null);
                ios.close();
                writer.dispose();
            } else {
                // Fallback to simple ImageIO write
                javax.imageio.ImageIO.write(image, "PNG", outputFile);
            }
            
            JOptionPane.showMessageDialog(this, 
                "PNG exported successfully to: " + filename + 
                "\nDimensions: " + exportWidth + "x" + exportHeight + " pixels" +
                "\nResolution: " + dpi + " DPI" +
                "\nPrint size at " + dpi + " DPI: " + 
                String.format("%.1f\" x %.1f\"", baseWidth / 72.0, baseHeight / 72.0), 
                "Export Success", 
                JOptionPane.INFORMATION_MESSAGE);
                
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, 
                "Error exporting to PNG: " + e.getMessage(), 
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
            
            List<String> displayGroups = displayByVersion ? uniqueVersions : uniqueServices;
            Map<String, Integer> displayLanes = displayByVersion ? versionLanes : serviceLanes;
            Map<String, Color> displayColors = displayByVersion ? versionColors : serviceColors;
            
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
            
            int safeMaxId = Math.max(Math.min(tasks.size(), maxDisplayTasks), 10);
            long safeMaxTime = Math.max(maxTime, 1);
            
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
            
            writer.println("% Define colors");
            int colorIndex = 0;
            Map<String, String> latexColors = new HashMap<>();
            for (String group : displayGroups) {
                Color c = displayColors.get(group);
                if (c == null) c = Color.GRAY;
                String colorName = "color" + colorIndex;
                writer.printf("\\definecolor{%s}{RGB}{%d,%d,%d}\n", 
                    colorName, c.getRed(), c.getGreen(), c.getBlue());
                latexColors.put(group, colorName);
                colorIndex++;
            }
            
            writer.println();
            
            writer.println("% Draw axes");
            writer.printf("\\draw[->] (0,0) -- (%d,0) node[right] {Arrival Position};\n", safeMaxId + 2);
            writer.printf("\\draw[->] (0,0) -- (0,%d) node[above] {%s};\n", 
                displayGroups.size() + 1, "Workflow versions");
            writer.println();
            
            writer.println("% Draw grid");
            writer.printf("\\draw[gray!30, thin] (0,0) grid (%d,%d);\n", safeMaxId, displayGroups.size());
            writer.println();
            
            writer.println("% Group labels");
            for (int i = 0; i < displayGroups.size(); i++) {
                String group = displayGroups.get(i);
                writer.printf("\\node[left] at (-0.5,%.1f) {%s};\n", i + 0.5, escapeLatex(group));
            }
            writer.println();
            
            writer.println("% X-axis labels");
            int step = Math.max(1, safeMaxId / 10);
            for (int i = 0; i <= safeMaxId; i += step) {
                writer.printf("\\node[below] at (%d,-0.3) {\\tiny %d};\n", i, i);
            }
            writer.println();
            
            writer.println("% Tasks (bars)");
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
                if (color == null) color = "gray";
                
                double x = i;
                double y = laneIndex + 0.3;
                double width = 0.5;
                double height = 0.5;
                
                if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(width) || Double.isNaN(height) ||
                    Double.isInfinite(x) || Double.isInfinite(y) || Double.isInfinite(width) || Double.isInfinite(height)) {
                    System.err.println("Warning: Invalid values for task " + task.id);
                    continue;
                }
                
                writer.printf("\\filldraw[fill=%s!70, draw=%s!90] (%.2f,%.2f) rectangle (%.2f,%.2f);\n",
                    color, color, x, y, x + width, y + height);
                
                if (tasks.size() <= 30) {
                    writer.printf("\\node[font=\\tiny] at (%.2f,%.2f) {%d};\n", 
                        x + width/2, y + height/2, task.id);
                }
            }
            writer.println();
            
            writer.println();
            writer.println("\\end{tikzpicture}");
            String caption = displayByVersion ? 
                "Concurrent workflow overview by version (arrival order)" :
                "Concurrent workflow overview (arrival order)";
            writer.println("\\caption{" + caption + "}");
            writer.println("\\label{fig:gantt-chart}");
            writer.println("\\end{figure}");
            
            System.out.println("Exported LaTeX with " + Math.min(tasks.size(), maxDisplayTasks) + " tasks");
            
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
            
            writer.println("% Workflow arrival overview. Bar size does not encode duration.");
            writer.println("% Add to preamble: \\usepackage{booktabs}");
            writer.println("% Add to preamble: \\usepackage{longtable} % for long tables");
            writer.println();
            writer.println("\\begin{longtable}{cccccc}");
            writer.println("\\toprule");
            writer.println("Database ID & Lane & Version & Sequence ID & Arrival Position & Recorded Visits \\\\");
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
                    i + 1,
                    task.serviceCount);
            }
            
            writer.println("\\bottomrule");
            writer.println("\\caption{Workflow Arrival Overview}");
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
     * Derive version using VersionConstants instead of hardcoded ranges
     */
    private String deriveVersion(int sequenceId) {
        return VersionConstants.getVersionFromSequenceId(sequenceId);
    }
    
    /**
     * Get color for version string (v001=Red, v002=Blue, v003=Green)
     */
    private Color getVersionColor(String version, int fallbackIndex) {
        switch (version.toLowerCase()) {
            case "v001": return new Color(231, 76, 60);    // Red
            case "v002": return new Color(52, 152, 219);   // Blue
            case "v003": return new Color(46, 204, 113);   // Green
            case "v004": return new Color(155, 89, 182);   // Purple
            case "v005": return new Color(243, 156, 18);   // Orange
            default:     return colorPalette[fallbackIndex % colorPalette.length];
        }
    }
    
    public void setCompactMode(boolean compact) {
        this.compactMode = compact;
        repaint();
    }
    
    public void setLegendTextBelow(boolean below) {
        this.legendTextBelow = below;
        repaint();
    }
    
    public void setDisplayByVersion(boolean byVersion) {
        this.displayByVersion = byVersion;
        
        if (byVersion) {
            groupTasksByVersion();
        }
        
        repaint();
    }
    
    private void groupTasksByVersion() {
        versionGroups.clear();
        uniqueVersions.clear();
        versionLanes.clear();
        versionColors.clear();
        
        for (Task task : tasks) {
            String version = deriveVersion(task.sequenceId);
            
            if (!versionGroups.containsKey(version)) {
                versionGroups.put(version, new ArrayList<>());
                uniqueVersions.add(version);
            }
            versionGroups.get(version).add(task);
        }
        
        Collections.sort(uniqueVersions);
        
        // Assign consistent colors: v001=Red, v002=Blue, v003=Green
        for (int i = 0; i < uniqueVersions.size(); i++) {
            String version = uniqueVersions.get(i);
            versionLanes.put(version, i);
            versionColors.put(version, getVersionColor(version, i));
        }
        
        int width = Math.max(1200, maxId * 10 + 150);
        int topMargin = Math.round(80 * fontScaleFactor);
        int bottomPadding = Math.round(15 * fontScaleFactor);
        int numLanes = uniqueVersions.size();
        int laneHeight = Math.round(45 * fontScaleFactor);
        int chartHeight = numLanes * laneHeight;
        int totalHeight = topMargin + chartHeight + bottomPadding;
        
        setPreferredSize(new Dimension(width, totalHeight));
        revalidate();
    }
    
    private void updateFonts() {
        titleFont = new Font("Arial", Font.BOLD, Math.round(titleFontSize * fontScaleFactor));
        labelFont = new Font("Arial", Font.PLAIN, Math.round(labelFontSize * fontScaleFactor));
        axisLabelFont = new Font("Arial", Font.PLAIN, Math.round(axisLabelFontSize * fontScaleFactor));
        tooltipFont = new Font("Arial", Font.PLAIN, Math.round(tooltipFontSize * fontScaleFactor));
    }
    
    public void setFontScaleFactor(float scaleFactor) {
        this.fontScaleFactor = scaleFactor;
        updateFonts();
        
        if (scaleFactor > 1.5f) {
            int width = Math.max(900, maxId * 7 + 200);
            int height = Math.max(600, Math.round(400 * scaleFactor));
            setPreferredSize(new Dimension(width, height));
            revalidate();
        }
        
        repaint();
    }
    
    private int calculateLeftMargin(Graphics2D g2, List<String> displayGroups) {
        if (compactMode && legendTextBelow) {
            FontMetrics fm = g2.getFontMetrics(new Font("Arial", Font.PLAIN, Math.round(10 * fontScaleFactor)));
            int maxLabelWidth = 0;
            for (String group : displayGroups) {
                maxLabelWidth = Math.max(maxLabelWidth, fm.stringWidth(group));
            }
            return Math.max(Math.round(80 * fontScaleFactor), maxLabelWidth + 25);
        } else if (compactMode) {
            FontMetrics fm = g2.getFontMetrics(labelFont);
            int maxLabelWidth = 0;
            for (String group : displayGroups) {
                maxLabelWidth = Math.max(maxLabelWidth, fm.stringWidth(group));
            }
            return Math.round((maxLabelWidth + 40) * fontScaleFactor);
        } else {
            return Math.round(180 * fontScaleFactor);
        }
    }
    
    
    /**
     * Helper class to aggregate service times per workflow
     */
    private static class WorkflowAggregate {
        int baseSequenceId;
        int workflowBase;
        long totalQueueTime = 0;
        long totalServiceTime = 0;
        int serviceCount = 0;
        // Earliest real workflow start seen in SERVICECONTRIBUTION.
        // Used to preserve chronology when PROCESSMEASUREMENTS is empty.
        long workflowStartTime = Long.MAX_VALUE;
        
        // Track services by fork number for parallel execution analysis
        Map<Integer, List<ServiceTiming>> forkGroups = new HashMap<>();
        
        WorkflowAggregate(int baseSequenceId, int workflowBase) {
            this.baseSequenceId = baseSequenceId;
            this.workflowBase = workflowBase;
        }
        
        void addService(int sequenceId, long queueTime, long serviceTime) {
            // Extract fork number (last 2 digits)
            int forkNumber = sequenceId % 1000;
            
            List<ServiceTiming> forkServices = forkGroups.get(forkNumber);
            if (forkServices == null) {
                forkServices = new ArrayList<>();
                forkGroups.put(forkNumber, forkServices);
            }
            
            forkServices.add(new ServiceTiming(queueTime, serviceTime));
            serviceCount++;
        }
        
        /**
         * Maximum observed queue and service duration across individual visits.
         * These independent maxima do not constitute a workflow critical path.
         */
        void calculateVisitMaxima() {
            long maxForkQueue = 0;
            long maxForkService = 0;
            
            // For each fork group, find the maximum queue and service time
            for (List<ServiceTiming> forkServices : forkGroups.values()) {
                long forkQueue = 0;
                long forkService = 0;
                
                // Retain each independent visit maximum within this fork group.
                for (ServiceTiming timing : forkServices) {
                    if (timing.queueTime > forkQueue) {
                        forkQueue = timing.queueTime;
                    }
                    if (timing.serviceTime > forkService) {
                        forkService = timing.serviceTime;
                    }
                }
                
                // Independent per-visit maxima; no path reconstruction is implied.
                if (forkQueue > maxForkQueue) {
                    maxForkQueue = forkQueue;
                }
                if (forkService > maxForkService) {
                    maxForkService = forkService;
                }
            }
            
            totalQueueTime = maxForkQueue;
            totalServiceTime = maxForkService;
        }
    }
    
    private static class ServiceTiming {
        long queueTime;
        long serviceTime;
        
        ServiceTiming(long queueTime, long serviceTime) {
            this.queueTime = queueTime;
            this.serviceTime = serviceTime;
        }
    }
    
    protected void loadDataFromDatabase() {
        Connection conn = null;
        Statement stmt = null;
        ResultSet rs = null;
        
        try {
            DriverManager.registerDriver(new EmbeddedDriver());
            conn = DriverManager.getConnection(DB_URL);
            stmt = conn.createStatement();
            ServiceDisplayNames names = ServiceDisplayNames.load(conn);
            
            tasks.clear();
            uniqueServices.clear();
            serviceLanes.clear();
            serviceColors.clear();
            maxQueueTime = 1;
            
            boolean dataLoaded = false;
            
            // First, aggregate SERVICECONTRIBUTION data by base sequenceID
            Map<Integer, WorkflowAggregate> contributionMap = new HashMap<>();
            try {
                String query = "SELECT " +
                              "WORKFLOWBASE, " +
                              "SEQUENCEID, " +
                              "QUEUETIME, " +
                              "SERVICETIME, " +
                              "WORKFLOWSTARTTIME " +
                              "FROM SERVICECONTRIBUTION";
                
                System.out.println("Loading SERVICECONTRIBUTION data...");
                rs = stmt.executeQuery(query);
                
                while (rs.next()) {
                    int sequenceId = rs.getInt("SEQUENCEID");
                    if (sequenceId / 1000000 == 999) continue;
                    int workflowBase = rs.getInt("WORKFLOWBASE");
                    long queueTime = Math.max(0, rs.getLong("QUEUETIME"));
                    long serviceTime = Math.max(0, rs.getLong("SERVICETIME"));
                    long workflowStartTime = rs.getLong("WORKFLOWSTARTTIME");
                    
                    // Calculate base sequenceID
                    int baseSequenceId = (sequenceId / 1000) * 1000;
                    
                    // Get or create aggregate
                    WorkflowAggregate aggregate = contributionMap.get(baseSequenceId);
                    if (aggregate == null) {
                        aggregate = new WorkflowAggregate(baseSequenceId, workflowBase);
                        contributionMap.put(baseSequenceId, aggregate);
                    }

                    if (workflowStartTime > 0 && workflowStartTime < aggregate.workflowStartTime) {
                        aggregate.workflowStartTime = workflowStartTime;
                    }
                    
                    aggregate.addService(sequenceId, queueTime, serviceTime);
                }
                rs.close();
                
                // Calculate diagnostic visit maxima, not workflow elapsed durations.
                for (WorkflowAggregate aggregate : contributionMap.values()) {
                    aggregate.calculateVisitMaxima();
                }
                
                System.out.println("Aggregated " + contributionMap.size() + " workflows from SERVICECONTRIBUTION");
                
            } catch (SQLException e) {
                System.out.println("Error loading SERVICECONTRIBUTION: " + e.getMessage());
                e.printStackTrace();
            }
            
            // Now load PROCESSMEASUREMENTS to get arrival order and IDs
         // Now load PROCESSMEASUREMENTS to get arrival order and IDs
            try {
                String query = "SELECT id, serviceName, sequenceID, " +
                              "TOKENARRIVALTIME, WORKFLOWSTARTTIME, ELAPSEDTIME " +
                              "FROM PROCESSMEASUREMENTS " +
                              "WHERE serviceName IS NOT NULL " +
                              "ORDER BY WORKFLOWSTARTTIME, TOKENARRIVALTIME, id";
                
                System.out.println("Loading PROCESSMEASUREMENTS for arrival order...");
                rs = stmt.executeQuery(query);
                
                boolean hasAnyAggregates = !contributionMap.isEmpty();  // Check ONCE at start
                boolean shownWarning = false;  // Only show warning once
                
                while (rs.next()) {
                    int id = rs.getInt("id");
                    String serviceName = rs.getString("serviceName");
                    int sequenceId = rs.getInt("sequenceID");
                    if (sequenceId / 1000000 == 999) continue;
                    
                    // Calculate base sequenceID
                    int baseSequenceId = (sequenceId / 1000) * 1000;
                    
                    // Look up the aggregated data from SERVICECONTRIBUTION
                    WorkflowAggregate aggregate = contributionMap.get(baseSequenceId);
                    
                    long queueTime;
                    long processingTime;
                    
                    if (aggregate != null) {
                        // Use real aggregated values from SERVICECONTRIBUTION
                        queueTime = aggregate.totalQueueTime;
                        processingTime = aggregate.totalServiceTime;
                    } else {
                        // No aggregate for this workflow - use fallback
                        if (!shownWarning && !hasAnyAggregates) {
                            // Only warn if SERVICECONTRIBUTION is completely empty
                            System.err.println("=====================================================");
                            System.err.println("WARNING: SERVICECONTRIBUTION table is EMPTY");
                            System.err.println("Displaying spatial timeline only with solid bars.");
                            System.err.println("Queue/execution time ratios are NOT available.");
                            System.err.println("=====================================================");
                            shownWarning = true;
                        }
                        queueTime = 0;  // Force solid bars (no queue/execution split)
                        processingTime = rs.getLong("ELAPSEDTIME");
                    }
                    
                    String version = VersionConstants.getVersionFromSequenceId(sequenceId);
                    
                    long elapsedTime = rs.getLong("ELAPSEDTIME");
                    
                    Task task = new Task(id, version, sequenceId, processingTime);
                    task.businessServices = names.familyServices(sequenceId);
                    task.queueTime = queueTime;
                    task.serviceCount = (aggregate != null) ? aggregate.serviceCount : 0;
                    task.elapsedTime = elapsedTime;
                    tasks.add(task);
                    
                    if (!uniqueServices.contains(version)) {
                        uniqueServices.add(version);
                    }
                    
                    if (id > maxId) maxId = id;
                    if (processingTime > maxTime) maxTime = processingTime;
                    if (queueTime > maxQueueTime) maxQueueTime = queueTime;
                    if (elapsedTime > maxElapsedTime) maxElapsedTime = elapsedTime;
                    
                    dataLoaded = true;
                }
                rs.close();
                
                System.out.println("Loaded " + tasks.size() + " tasks with real queue/service times");
                
            } catch (SQLException e) {
                System.out.println("Error loading PROCESSMEASUREMENTS: " + e.getMessage());
                e.printStackTrace();
            }
            
            // FALLBACK: Only use SERVICECONTRIBUTION if PROCESSMEASUREMENTS is completely empty
            // Previously this compared tasks.size() < contributionMap.size() which was wrong because
            // PROCESSMEASUREMENTS has one row per workflow completion, while contributionMap has entries
            // for every service visited. The fallback should only trigger when we have NO data from PROCESSMEASUREMENTS.
            if (!dataLoaded && !contributionMap.isEmpty()) {
                System.out.println("Using SERVICECONTRIBUTION fallback (PROCESSMEASUREMENTS empty, " + 
                                  "SERVICECONTRIBUTION workflows: " + contributionMap.size() + ")...");
                
                // Clear any partial data from PROCESSMEASUREMENTS
                tasks.clear();
                uniqueServices.clear();
                
                // PROCESSMEASUREMENTS may be empty after unified collection. In that
                // case SERVICECONTRIBUTION still carries the real workflow start time.
                // Preserve chronology instead of sorting by sequence ID (which would
                // always group v001 before v002 regardless of when they ran).
                List<WorkflowAggregate> sortedWorkflows = new ArrayList<>(contributionMap.values());
                sortedWorkflows.sort((a, b) -> {
                    int timeOrder = Long.compare(a.workflowStartTime, b.workflowStartTime);
                    return timeOrder != 0 ? timeOrder : Integer.compare(a.baseSequenceId, b.baseSequenceId);
                });
                
                int syntheticId = 1;
                for (WorkflowAggregate aggregate : sortedWorkflows) {
                    String version = VersionConstants.getVersionFromSequenceId(aggregate.baseSequenceId);
                    
                    Task task = new Task(syntheticId, version, aggregate.baseSequenceId, aggregate.totalServiceTime);
                    task.businessServices = names.familyServices(aggregate.baseSequenceId);
                    task.queueTime = aggregate.totalQueueTime;
                    task.serviceCount = aggregate.serviceCount;
                    tasks.add(task);
                    
                    if (!uniqueServices.contains(version)) {
                        uniqueServices.add(version);
                    }
                    
                    if (syntheticId > maxId) maxId = syntheticId;
                    if (aggregate.totalServiceTime > maxTime) maxTime = aggregate.totalServiceTime;
                    if (aggregate.totalQueueTime > maxQueueTime) maxQueueTime = aggregate.totalQueueTime;
                    
                    syntheticId++;
                }
                
                dataLoaded = true;
                System.out.println("Created " + tasks.size() + " tasks from SERVICECONTRIBUTION fallback");
            }
            
            if (dataLoaded) {
                Collections.sort(uniqueServices);
                
                for (int i = 0; i < uniqueServices.size(); i++) {
                    String service = uniqueServices.get(i);
                    serviceLanes.put(service, i);
                    serviceColors.put(service, getVersionColor(service, i));
                }
                
                System.out.println("Found " + uniqueServices.size() + " unique versions: " + uniqueServices);
                System.out.println("Max queue time: " + maxQueueTime + " ms");
                System.out.println("Max service time: " + maxTime + " ms");
                System.out.println("MaxId: " + maxId);

                int width = Math.max(1200, maxId * 10 + 150);
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
            
            if (dataLoaded) {
                Collections.sort(uniqueServices);
                
                for (int i = 0; i < uniqueServices.size(); i++) {
                    String service = uniqueServices.get(i);
                    serviceLanes.put(service, i);
                    serviceColors.put(service, getVersionColor(service, i));
                }
                
                System.out.println("Loaded " + tasks.size() + " tasks from database");
                System.out.println("Found " + uniqueServices.size() + " workflow versions: " + uniqueServices);
                System.out.println("Max queue time: " + maxQueueTime + " ms");
                
                this.maxId = Math.max(tasks.size() + 1, 1);
                
                if (this.maxTime <= 0) {
                    this.maxTime = 1000;
                    System.out.println("Warning: maxTime was invalid, setting to default 1000");
                }
                
                System.out.println("MaxId: " + maxId + ", MaxTime: " + maxTime);

                int width = Math.max(1200, maxId * 10 + 150);
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

        int leftMargin = calculateLeftMargin(g2, displayGroups);
        int topMargin = Math.round(80 * fontScaleFactor);
        int rightMargin = 50;
        int chartWidth = getWidth() - leftMargin - rightMargin;

        int numLanes = displayGroups.size();
        int fullLaneHeight = (getHeight() - topMargin - 30) / numLanes;
        int laneHeight = (int) (fullLaneHeight * 0.80);
        int chartHeight = laneHeight * numLanes;

        // Draw title
        g2.setFont(titleFont);
        String title = displayByVersion ?
            "Concurrent Workflow Overview by Version" :
            "Concurrent Workflow Overview";
        FontMetrics fm = g2.getFontMetrics(titleFont);
        int titleWidth = fm.stringWidth(title);
        g2.drawString(title, (getWidth() - titleWidth) / 2, Math.round(30 * fontScaleFactor));

        g2.setColor(Color.DARK_GRAY);
        g2.setFont(new Font("Arial", Font.PLAIN, Math.round(10 * fontScaleFactor)));
        g2.drawString("One bar per workflow; position shows arrival order. Bar size does not encode duration.",
                     leftMargin, Math.round(50 * fontScaleFactor));

        // Draw horizontal lane lines
        g2.setColor(Color.LIGHT_GRAY);
        for (int i = 0; i <= numLanes; i++) {
            int y = topMargin + i * laneHeight;
            g2.drawLine(leftMargin, y, leftMargin + chartWidth, y);
        }

        // Draw lane labels
        g2.setFont(labelFont);
        FontMetrics labelFm = g2.getFontMetrics(labelFont);

        if (legendTextBelow) {
            int legendX = 10;
            int boxSize = Math.round(15 * fontScaleFactor);
            Font smallFont = new Font("Arial", Font.PLAIN, Math.round(10 * fontScaleFactor));

            for (String group : displayGroups) {
                Integer laneIndex = displayLanes.get(group);
                if (laneIndex == null) continue;

                int centerY = topMargin + laneIndex * laneHeight + laneHeight / 2;

                Color groupColor = displayColors.get(group);
                g2.setColor(groupColor);
                g2.fillRect(legendX, centerY - boxSize / 2, boxSize, boxSize);
                g2.setColor(groupColor.darker());
                g2.drawRect(legendX, centerY - boxSize / 2, boxSize, boxSize);

                g2.setColor(Color.BLACK);
                g2.setFont(smallFont);

                FontMetrics smallFm = g2.getFontMetrics(smallFont);
                String displayText = group;

                int textX = legendX;
                int textY = centerY + boxSize / 2 + smallFm.getHeight() + 2;

                int availableWidth = leftMargin - 10;

                if (smallFm.stringWidth(displayText) > availableWidth) {
                    while (smallFm.stringWidth(displayText + "...") > availableWidth && displayText.length() > 1) {
                        displayText = displayText.substring(0, displayText.length() - 1);
                    }
                    if (displayText.length() > 1) {
                        displayText += "...";
                    }
                }

                g2.drawString(displayText, textX, textY);
                g2.setFont(labelFont);
            }
        } else {
            for (String group : displayGroups) {
                Integer laneIndex = displayLanes.get(group);
                if (laneIndex == null) continue;

                int y = topMargin + laneIndex * laneHeight + laneHeight / 2;

                Color groupColor = displayColors.get(group);
                g2.setColor(groupColor);
                int rectSize = Math.round(15 * fontScaleFactor);
                g2.fillRect(10, y - rectSize / 2, rectSize, rectSize);

                g2.setColor(Color.BLACK);
                g2.drawString(group, 30, y + 4);
            }
        }

        // Draw vertical grid lines
        g2.setColor(Color.LIGHT_GRAY);
        g2.setStroke(new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0, new float[]{2}, 0));

        int actualMaxId = Math.min(tasks.size(), maxDisplayTasks);
        int gridInterval = Math.max(1, actualMaxId / 10);

        g2.setFont(axisLabelFont);
        for (int i = 0; i < actualMaxId; i += gridInterval) {
            int x = leftMargin + (i * chartWidth / Math.max(1, actualMaxId));
            int y = topMargin + chartHeight;
            g2.drawLine(x, topMargin, x, y);

            g2.setColor(Color.GRAY);
            String label = String.valueOf(i + 1);
            FontMetrics axisfm = g2.getFontMetrics(axisLabelFont);
            int labelWidth = axisfm.stringWidth(label);
            g2.drawString(label, x - labelWidth / 2, y + Math.round(15 * fontScaleFactor));
            g2.setColor(Color.LIGHT_GRAY);
        }

        // Draw X-axis label
        g2.setColor(Color.BLACK);
        g2.setFont(new Font("Arial", Font.BOLD, Math.round(12 * fontScaleFactor)));
        String xLabel = "Workflow Arrival Order (chronological)";
        FontMetrics xfm = g2.getFontMetrics();
        int xLabelWidth = xfm.stringWidth(xLabel);
        g2.drawString(xLabel, (getWidth() - xLabelWidth) / 2, topMargin + chartHeight + Math.round(40 * fontScaleFactor));

        g2.setStroke(new BasicStroke(1));

        List<Task> displayTasks = tasks;

        // Draw tasks with linear scaling (no logarithm, no heat strips)
        // Use actual display count to properly scale X-axis when Display Range is limited
        int actualDisplayCount = Math.min(displayTasks.size(), maxDisplayTasks);
        
        for (int i = 0; i < actualDisplayCount; i++) {
            Task task = displayTasks.get(i);
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

            // FIX: Use task index (i) instead of database ID for X position
            // Database IDs can be non-sequential (221, 222, etc.) which causes bars to render off-screen
            // Use actualDisplayCount to scale properly when maxDisplayTasks is set
            int x = leftMargin + (i * chartWidth / Math.max(1, actualDisplayCount));

            // FIXED WIDTH - all bars same width for timeline visualization
            // Use actualDisplayCount to scale properly when maxDisplayTasks is set
            int barWidth = Math.max(8, (chartWidth / Math.max(1, actualDisplayCount)) - 4);

        //    int barHeight = laneHeight * 2 / 5;
            int barHeight = (int) (laneHeight * 0.75);
            int y = topMargin + (laneIndex * laneHeight) + (
            		laneHeight - barHeight) - 5;

            g2.setColor(barColor);
            g2.fillRoundRect(x, y, barWidth, barHeight, 5, 5);
            g2.setColor(barColor.darker());
            g2.drawRoundRect(x, y, barWidth, barHeight, 5, 5);

            // Add task ID for small datasets
            if (displayTasks.size() <= 30 && barWidth > 15) {
                g2.setColor(Color.WHITE);
                g2.setFont(new Font("Arial", Font.PLAIN, 9));
                String idStr = String.valueOf(task.sequenceId / 100);  // Token ID without last 2 digits
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
        
        Graphics2D g2 = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics();
        int leftMargin = calculateLeftMargin(g2, displayGroups);
        g2.dispose();
        int topMargin = Math.round(80 * fontScaleFactor);
        int rightMargin = 50;
        int chartWidth = getWidth() - leftMargin - rightMargin;
        
        int numLanes = displayGroups.size();
        int fullLaneHeight = (getHeight() - topMargin - 30) / numLanes;
        int laneHeight = (int) (fullLaneHeight * 0.80);
        
      //  int barHeight = laneHeight * 2 / 5;
        int barHeight = (int) (laneHeight * 0.75);
        
        // Use actual display count to match paintComponent scaling
        int actualDisplayCount = Math.min(tasks.size(), maxDisplayTasks);
        
        for (int i = 0; i < actualDisplayCount; i++) {
            Task task = tasks.get(i);
            Integer laneIndex;
            
            if (displayByVersion) {
                String version = deriveVersion(task.sequenceId);
                laneIndex = versionLanes.get(version);
            } else {
                laneIndex = serviceLanes.get(task.service);
            }
            
            if (laneIndex == null) continue;
            
            // FIX: Use task index (i) instead of database ID for X position
            // Use actualDisplayCount to match paintComponent scaling
            int x = leftMargin + (i * chartWidth / Math.max(1, actualDisplayCount));
            
            // FIXED WIDTH - all bars same width for timeline visualization
            // Use actualDisplayCount to match paintComponent scaling
            int barWidth = Math.max(8, (chartWidth / Math.max(1, actualDisplayCount)) - 4);
            
            int y = topMargin + (laneIndex * laneHeight) + (laneHeight - barHeight) - 5;
            if (new Rectangle(x, y, barWidth, barHeight).contains(mouseX, mouseY)) return task;
        }
        return null;
    }
    
    private void drawTooltip(Graphics2D g2, Task task) {
        String version = deriveVersion(task.sequenceId);
        
        String[] lines = {
            "Business services: " + task.businessServices,
            "Workflow version: " + version,
            "Root sequence: " + task.sequenceId,
            "Recorded service visits: " + task.serviceCount,
            "Bar size does not encode duration",
            "Queue waits: View > Service Queue Timings",
            "Elapsed time: View > Measured Workflow Timeline"
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
        
        if (x + tooltipWidth > getWidth()) x = getWidth() - tooltipWidth - 10;
        if (y < 0) y = 10;
        
        g2.setColor(new Color(0, 0, 0, 230));
        g2.fillRoundRect(x, y, tooltipWidth, tooltipHeight, 5, 5);
        
        for (int i = 0; i < lines.length; i++) {
            g2.setColor(Color.WHITE);
            g2.drawString(lines[i], x + 10, y + lineHeight + i * lineHeight);
        }
    }
    
    public void setMaxDisplayTasks(int max) {
        this.maxDisplayTasks = max;
        this.maxId = Math.min(tasks.size(), max) + 1;
        
        int width = Math.max(700, maxId * 10 + 200);
        
        List<String> displayGroups = displayByVersion ? uniqueVersions : uniqueServices;
        if (displayGroups.isEmpty() && displayByVersion) {
            groupTasksByVersion();
            displayGroups = uniqueVersions;
        }
        
        int topMargin = Math.round(80 * fontScaleFactor);
        int bottomPadding = Math.round(15 * fontScaleFactor);
        int numLanes = Math.max(1, displayGroups.size());
        int laneHeight = Math.round(45 * fontScaleFactor);
        int chartHeight = numLanes * laneHeight;
        int totalHeight = topMargin + chartHeight + bottomPadding;
        
        setPreferredSize(new Dimension(width, totalHeight));
        revalidate();
        repaint();
    }
    
    // Export methods removed for brevity - can be added back with linear scaling
    
    public static void createAndShowGUI(SwingGanttChart_WithLatency_v1d chart) {
        JFrame frame = new JFrame("Concurrent Workflow Overview");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        
        JMenuBar menuBar = new JMenuBar();
        
        // ===== COMPLETE FILE MENU =====
        JMenu fileMenu = new JMenu("File");
        
        // Workflow summary exports
        JMenuItem exportSummaryItem = new JMenuItem("Export Workflow Summary...");
        exportSummaryItem.addActionListener(e -> {
            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setSelectedFile(new File("workflow_summary.txt"));
            fileChooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Text files", "txt"));
            
            if (fileChooser.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) {
                File file = fileChooser.getSelectedFile();
                String filename = file.getAbsolutePath();
                if (!filename.endsWith(".txt")) {
                    filename += ".txt";
                }
                chart.exportWorkflowSummary(filename);
            }
        });
        fileMenu.add(exportSummaryItem);
        
        JMenuItem viewSummaryItem = new JMenuItem("View Workflow Summary");
        viewSummaryItem.addActionListener(e -> {
            String summary = chart.generateWorkflowSummaryReport();
            JTextArea textArea = new JTextArea(summary);
            textArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
            textArea.setEditable(false);
            JScrollPane scrollPane = new JScrollPane(textArea);
            scrollPane.setPreferredSize(new Dimension(800, 600));
            JOptionPane.showMessageDialog(frame, scrollPane, "Workflow Summary Report", JOptionPane.INFORMATION_MESSAGE);
        });
        fileMenu.add(viewSummaryItem);
        fileMenu.addSeparator();
        
        // Chart export functions
        JMenuItem exportPDFItem = new JMenuItem("Export Chart to PDF...");
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
        fileMenu.add(exportPDFItem);
        
        // PNG Export submenu with DPI options
        JMenu exportPNGMenu = new JMenu("Export Chart to PNG");
        
        int[] dpiOptions = {72, 150, 300, 600};
        String[] dpiLabels = {"72 DPI (Screen)", "150 DPI (Draft)", "300 DPI (Print Quality)", "600 DPI (High Quality)"};
        
        for (int i = 0; i < dpiOptions.length; i++) {
            final int dpi = dpiOptions[i];
            JMenuItem pngItem = new JMenuItem(dpiLabels[i] + "...");
            pngItem.addActionListener(e -> {
                JFileChooser fileChooser = new JFileChooser();
                fileChooser.setSelectedFile(new File("gantt_chart_" + dpi + "dpi.png"));
                fileChooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("PNG files", "png"));
                
                if (fileChooser.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) {
                    File file = fileChooser.getSelectedFile();
                    String filename = file.getAbsolutePath();
                    if (!filename.endsWith(".png")) {
                        filename += ".png";
                    }
                    chart.exportToPNG(filename, dpi);
                }
            });
            exportPNGMenu.add(pngItem);
        }
        fileMenu.add(exportPNGMenu);
        
        JMenuItem exportLatexItem = new JMenuItem("Export Chart to LaTeX (TikZ)...");
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
        fileMenu.add(exportLatexItem);
        
        JMenuItem exportLatexTableItem = new JMenuItem("Export Data to LaTeX Table...");
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
        fileMenu.add(exportLatexTableItem);
        fileMenu.addSeparator();
        
        JMenuItem exitItem = new JMenuItem("Exit");
        exitItem.addActionListener(e -> System.exit(0));
        fileMenu.add(exitItem);
        
        // ===== COMPLETE VIEW MENU =====
        JMenu viewMenu = new JMenu("View");
        JMenuItem measuredTimelineItem = new JMenuItem("Measured Workflow Timeline...");
        measuredTimelineItem.addActionListener(e -> MeasuredWorkflowTimeline.showWindow());
        viewMenu.add(measuredTimelineItem);
        viewMenu.addSeparator();
        
        JMenuItem queueTimingItem = new JMenuItem("Service Queue Timings...");
        queueTimingItem.addActionListener(e -> ServiceQueueTimingView.showWindow());
        viewMenu.add(queueTimingItem);
        viewMenu.addSeparator();

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
        
        JMenu truncateMenu = new JMenu("Display Range");
        ButtonGroup truncateGroup = new ButtonGroup();
        
        int[] truncateOptions = {20, 30, 40, 60, 80, 100, Integer.MAX_VALUE};
        String[] truncateLabels = {"First 20 Tasks", "First 30 Tasks", "First 40 Tasks", "First 60 Tasks", 
                                   "First 80 Tasks", "First 100 Tasks", "Show All"};
        
        for (int i = 0; i < truncateOptions.length; i++) {
            JRadioButtonMenuItem truncateItem = new JRadioButtonMenuItem(truncateLabels[i]);
            final int maxTasks = truncateOptions[i];
            
            if (maxTasks == Integer.MAX_VALUE) {
                truncateItem.setSelected(true);
            }
            
            truncateItem.addActionListener(e -> chart.setMaxDisplayTasks(maxTasks));
            truncateGroup.add(truncateItem);
            truncateMenu.add(truncateItem);
        }
        
        viewMenu.add(truncateMenu);
        viewMenu.addSeparator();
        
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
        
        // ===== COMPLETE FONT MENU =====
        JMenu fontMenu = new JMenu("Font");
        JMenu fontScaleMenu = new JMenu("Font Size");
        ButtonGroup fontScaleGroup = new ButtonGroup();
        
        float[] scaleOptions = {0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f};
        String[] scaleLabels = {"75%", "100% (Default)", "125%", "150%", "175%", "200%"};
        
        for (int i = 0; i < scaleOptions.length; i++) {
            JRadioButtonMenuItem scaleItem = new JRadioButtonMenuItem(scaleLabels[i]);
            final float scale = scaleOptions[i];
            
            if (scale == 1.0f) {
                scaleItem.setSelected(true);
            }
            
            scaleItem.addActionListener(e -> chart.setFontScaleFactor(scale));
            fontScaleGroup.add(scaleItem);
            fontScaleMenu.add(scaleItem);
        }
        
        fontMenu.add(fontScaleMenu);
        
        // Add menus to menubar
        menuBar.add(fileMenu);
        menuBar.add(viewMenu);
        menuBar.add(fontMenu);
        frame.setJMenuBar(menuBar);
        
        // Add chart to frame
        JScrollPane scrollPane = new JScrollPane(chart);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        
        frame.add(scrollPane);
        frame.pack();
        frame.setSize(Math.min(1400, frame.getWidth()), Math.min(500, frame.getHeight()));
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            SwingGanttChart_WithLatency_v1d chart = new SwingGanttChart_WithLatency_v1d();
            createAndShowGUI(chart);
        });
    }
}
