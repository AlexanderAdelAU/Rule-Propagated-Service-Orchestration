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
public class SwingGanttChart_WithLatency_v1b extends JPanel {
    
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
    
    // Queue time visualization options
    private boolean showQueueTime = true;
    private boolean colorCodeQueueTime = true;
    
    // Version display feature
    private boolean displayByVersion = false;
    private Map<String, List<Task>> versionGroups = new HashMap<>();
    private List<String> uniqueVersions = new ArrayList<>();
    private Map<String, Color> versionColors = new HashMap<>();
    private Map<String, Integer> versionLanes = new HashMap<>();
    
    private long maxQueueTime = 1;
    
    public static class Task {
        int id;
        String service;
        int sequenceId;
        long processingTime;
        long queueTime;
        int serviceCount;  // Number of services in this workflow
        
        public Task(int id, String service, int sequenceId, long processingTime) {
            this.id = id;
            this.service = service;
            this.sequenceId = sequenceId;
            this.processingTime = processingTime;
            this.serviceCount = 0;
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
    
    public SwingGanttChart_WithLatency_v1b() {
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
        StringBuilder report = new StringBuilder();
        
        // Group tasks by base sequenceID
        Map<Integer, WorkflowSummary> summaryMap = new LinkedHashMap<>();
        
        for (Task task : tasks) {
            int baseSeqId = (task.sequenceId / 1000) * 1000;
            
            WorkflowSummary summary = summaryMap.get(baseSeqId);
            if (summary == null) {
                summary = new WorkflowSummary(baseSeqId);
                summaryMap.put(baseSeqId, summary);
            }
            
            summary.totalQueueTime = task.queueTime;
            summary.totalServiceTime = task.processingTime;
            summary.version = deriveVersion(task.sequenceId);
            summary.serviceCount = task.serviceCount;
        }
        
        // Calculate workflow time ranges from database
        Map<String, WorkflowTimeRange> versionTimeRanges = calculateVersionTimeRanges();
        
        // Generate report header
        report.append("WORKFLOW SUMMARY REPORT\n");
        report.append("Generated: ").append(new java.util.Date()).append("\n");
        report.append("=" .repeat(120)).append("\n\n");
        
        // Group by version
        Map<String, java.util.List<WorkflowSummary>> byVersion = new java.util.HashMap<>();
        for (WorkflowSummary summary : summaryMap.values()) {
            byVersion.computeIfAbsent(summary.version, k -> new java.util.ArrayList<>()).add(summary);
        }
        
        // Report for each version
        for (String version : byVersion.keySet()) {
            java.util.List<WorkflowSummary> workflows = byVersion.get(version);
            
            report.append("\n").append(version).append(" Workflows\n");
            
            // Add duration information
            WorkflowTimeRange timeRange = versionTimeRanges.get(version);
            if (timeRange != null) {
                report.append(String.format("Duration of Workflow Tokens: %d ms (from %d to %d)\n", 
                    timeRange.duration, timeRange.minTime, timeRange.maxTime));
            }
            
            report.append("-".repeat(120)).append("\n");
            report.append(String.format("%-15s %-12s %-20s %-20s %-15s %-15s%n",
                "Base SeqID", "Services", "Total Queue (ms)", "Total Service (ms)", "Total (ms)", "Queue Ratio"));
            report.append("-".repeat(120)).append("\n");
            
            long totalQueue = 0;
            long totalService = 0;
            
            for (WorkflowSummary summary : workflows) {
                long total = summary.totalQueueTime + summary.totalServiceTime;
                double ratio = total > 0 ? (double)summary.totalQueueTime / total * 100 : 0;
                
                report.append(String.format("%-15d %-12d %-20d %-20d %-15d %.1f%%%n",
                    summary.baseSequenceId,
                    summary.serviceCount,
                    summary.totalQueueTime,
                    summary.totalServiceTime,
                    total,
                    ratio));
                
                totalQueue += summary.totalQueueTime;
                totalService += summary.totalServiceTime;
            }
            
            report.append("-".repeat(120)).append("\n");
            long versionTotal = totalQueue + totalService;
            double versionRatio = versionTotal > 0 ? (double)totalQueue / versionTotal * 100 : 0;
            report.append(String.format("%-15s %-20d %-20d %-15d %.1f%%%n",
                version + " TOTAL:",
                totalQueue,
                totalService,
                versionTotal,
                versionRatio));
            report.append("\n");
        }
        
        return report.toString();
    }

    /**
     * Calculate time ranges for each version from database
     */
    private Map<String, WorkflowTimeRange> calculateVersionTimeRanges() {
        Map<String, WorkflowTimeRange> ranges = new HashMap<>();
        Connection conn = null;
        Statement stmt = null;
        ResultSet rs = null;
        
        try {
            conn = DriverManager.getConnection(DB_URL);
            stmt = conn.createStatement();
            
            String query = "SELECT sequenceID, WORKFLOWSTARTTIME " +
                          "FROM PROCESSMEASUREMENTS " +
                          "WHERE WORKFLOWSTARTTIME IS NOT NULL " +
                          "ORDER BY sequenceID";
            
            rs = stmt.executeQuery(query);
            
            while (rs.next()) {
                int sequenceId = rs.getInt("sequenceID");
                long workflowStartTime = rs.getLong("WORKFLOWSTARTTIME");
                
                String version = deriveVersion(sequenceId);
                
                WorkflowTimeRange range = ranges.get(version);
                if (range == null) {
                    range = new WorkflowTimeRange();
                    ranges.put(version, range);
                }
                
                range.update(workflowStartTime);
            }
            
        } catch (SQLException e) {
            System.err.println("Error calculating version time ranges: " + e.getMessage());
            e.printStackTrace();
        } finally {
            try {
                if (rs != null) rs.close();
                if (stmt != null) stmt.close();
                if (conn != null) conn.close();
            } catch (SQLException e) {
                System.err.println("Error closing database resources: " + e.getMessage());
            }
        }      
        return ranges;
    }

    /**
     * Helper class to track time range for a version
     */
    private static class WorkflowTimeRange {
        long minTime = Long.MAX_VALUE;
        long maxTime = Long.MIN_VALUE;
        long duration = 0;
        
        void update(long time) {
            if (time < minTime) minTime = time;
            if (time > maxTime) maxTime = time;
            duration = maxTime - minTime;
        }
    }
    
    /**
     * Helper class for workflow summary
     */
    private static class WorkflowSummary {
        int baseSequenceId;
        String version;
        int serviceCount = 0;
        long totalQueueTime = 0;
        long totalServiceTime = 0;
        
        WorkflowSummary(int baseSequenceId) {
            this.baseSequenceId = baseSequenceId;
        }
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
            
            if (showQueueTime) {
                writer.println("\\definecolor{queueGreen}{RGB}{0,200,0}");
                writer.println("\\definecolor{queueOrange}{RGB}{255,165,0}");
                writer.println("\\definecolor{queueRed}{RGB}{255,0,0}");
            }
            writer.println();
            
            writer.println("% Draw axes");
            writer.printf("\\draw[->] (0,0) -- (%d,0) node[right] {Execution Order};\n", safeMaxId + 2);
            writer.printf("\\draw[->] (0,0) -- (0,%d) node[above] {%s};\n", 
                displayGroups.size() + 1, displayByVersion ? "Versions" : "Services");
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
                
                double x = Math.max(0, task.id - 1);
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
                "Service Execution Timeline by Version" : 
                "Service Execution Timeline by Service";
            if (showQueueTime) caption += " (with Queue Time)";
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
     * Derive version using VersionConstants instead of hardcoded ranges
     */
    private String deriveVersion(int sequenceId) {
        return VersionConstants.getVersionFromSequenceId(sequenceId);
    }
    
    /**
     * Get queue time severity color
     */
    /**
     * Get queue time color based on percentage of total time
     */
    private Color getQueueTimeColor(long queueTime, long processingTime) {
        if (!colorCodeQueueTime) {
            return new Color(128, 128, 128, 60);
        }
        
        long totalTime = queueTime + processingTime;
        if (totalTime == 0) {
            return new Color(128, 128, 128, 60);
        }
        
        double queueRatio = (double)queueTime / totalTime * 100;
        
        if (queueRatio > 50) {
            return new Color(255, 0, 0, 80);      // Red: >50% queue
        } else if (queueRatio > 20) {
            return new Color(255, 165, 0, 80);    // Orange: 20-50% queue
        } else {
            return new Color(0, 200, 0, 60);      // Green: <20% queue
        }
    }
    
    public void setShowQueueTime(boolean show) {
        this.showQueueTime = show;
        repaint();
    }
    
    public void setColorCodeQueueTime(boolean colorCode) {
        this.colorCodeQueueTime = colorCode;
        repaint();
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
        
        for (int i = 0; i < uniqueVersions.size(); i++) {
            String version = uniqueVersions.get(i);
            versionLanes.put(version, i);
            versionColors.put(version, colorPalette[i % colorPalette.length]);
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
         * Calculate critical path assuming parallel execution of different forks
         */
        void calculateCriticalPath() {
            long maxForkQueue = 0;
            long maxForkService = 0;
            
            // For each fork group, find the maximum queue and service time
            for (List<ServiceTiming> forkServices : forkGroups.values()) {
                long forkQueue = 0;
                long forkService = 0;
                
                // Within a fork group, take the max (longest path in that parallel section)
                for (ServiceTiming timing : forkServices) {
                    if (timing.queueTime > forkQueue) {
                        forkQueue = timing.queueTime;
                    }
                    if (timing.serviceTime > forkService) {
                        forkService = timing.serviceTime;
                    }
                }
                
                // Across different forks (parallel sections), take max as critical path
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
                              "SERVICETIME " +
                              "FROM SERVICECONTRIBUTION";
                
                System.out.println("Loading SERVICECONTRIBUTION data...");
                rs = stmt.executeQuery(query);
                
                while (rs.next()) {
                    int sequenceId = rs.getInt("SEQUENCEID");
                    int workflowBase = rs.getInt("WORKFLOWBASE");
                    long queueTime = Math.max(0, rs.getLong("QUEUETIME"));
                    long serviceTime = Math.max(0, rs.getLong("SERVICETIME"));
                    
                    // Calculate base sequenceID
                    int baseSequenceId = (sequenceId / 1000) * 1000;
                    
                    // Get or create aggregate
                    WorkflowAggregate aggregate = contributionMap.get(baseSequenceId);
                    if (aggregate == null) {
                        aggregate = new WorkflowAggregate(baseSequenceId, workflowBase);
                        contributionMap.put(baseSequenceId, aggregate);
                    }
                    
                    aggregate.addService(sequenceId, queueTime, serviceTime);
                }
                rs.close();
                
                // Calculate critical path for each workflow
                for (WorkflowAggregate aggregate : contributionMap.values()) {
                    aggregate.calculateCriticalPath();
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
                              "ORDER BY id";
                
                System.out.println("Loading PROCESSMEASUREMENTS for arrival order...");
                rs = stmt.executeQuery(query);
                
                boolean hasAnyAggregates = !contributionMap.isEmpty();  // Check ONCE at start
                boolean shownWarning = false;  // Only show warning once
                
                while (rs.next()) {
                    int id = rs.getInt("id");
                    String serviceName = rs.getString("serviceName");
                    int sequenceId = rs.getInt("sequenceID");
                    
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
                    
                    Task task = new Task(id, version, sequenceId, processingTime);
                    task.queueTime = queueTime;
                    task.serviceCount = (aggregate != null) ? aggregate.serviceCount : 0;
                    tasks.add(task);
                    
                    if (!uniqueServices.contains(version)) {
                        uniqueServices.add(version);
                    }
                    
                    if (id > maxId) maxId = id;
                    if (processingTime > maxTime) maxTime = processingTime;
                    if (queueTime > maxQueueTime) maxQueueTime = queueTime;
                    
                    dataLoaded = true;
                }
                rs.close();
                
                System.out.println("Loaded " + tasks.size() + " tasks with real queue/service times");
                
            } catch (SQLException e) {
                System.out.println("Error loading PROCESSMEASUREMENTS: " + e.getMessage());
                e.printStackTrace();
            }
            
            // FALLBACK: If PROCESSMEASUREMENTS is empty but SERVICECONTRIBUTION has data,
            // create tasks directly from SERVICECONTRIBUTION (petrinet compatibility)
            if (!dataLoaded && !contributionMap.isEmpty()) {
                System.out.println("PROCESSMEASUREMENTS empty - using SERVICECONTRIBUTION fallback...");
                
                // Sort workflows by baseSequenceId for consistent ordering
                List<WorkflowAggregate> sortedWorkflows = new ArrayList<>(contributionMap.values());
                sortedWorkflows.sort((a, b) -> Integer.compare(a.baseSequenceId, b.baseSequenceId));
                
                int syntheticId = 1;
                for (WorkflowAggregate aggregate : sortedWorkflows) {
                    String version = VersionConstants.getVersionFromSequenceId(aggregate.baseSequenceId);
                    
                    Task task = new Task(syntheticId, version, aggregate.baseSequenceId, aggregate.totalServiceTime);
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
                    serviceColors.put(service, colorPalette[i % colorPalette.length]);
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
                    serviceColors.put(service, colorPalette[i % colorPalette.length]);
                }
                
                System.out.println("Loaded " + tasks.size() + " tasks from database");
                System.out.println("Found " + uniqueServices.size() + " unique services: " + uniqueServices);
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
            "Service Execution Timeline by Version (Linear Scale)" :
            "Service Execution Timeline by Service (Linear Scale)";
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

            if (colorCodeQueueTime) {
                g2.setColor(new Color(0, 200, 0, 60));
                g2.fillRect(legendX, legendY - 10, 20, 10);
                g2.setColor(Color.BLACK);
                g2.drawString("<20%", legendX + 25, legendY);
                legendX += 65;

                g2.setColor(new Color(255, 165, 0, 80));
                g2.fillRect(legendX, legendY - 10, 20, 10);
                g2.setColor(Color.BLACK);
                g2.drawString("20-50%", legendX + 25, legendY);
                legendX += 75;

                g2.setColor(new Color(255, 0, 0, 80));
                g2.fillRect(legendX, legendY - 10, 20, 10);
                g2.setColor(Color.BLACK);
                g2.drawString(">50%", legendX + 25, legendY);
            } else {
                g2.drawString("(shown as semi-transparent bars)", legendX, legendY);
            }
        }

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
        for (int i = 0; i <= actualMaxId + 1; i += gridInterval) {
            int x = leftMargin + (i * chartWidth / (actualMaxId + 1));
            int y = topMargin + chartHeight;
            g2.drawLine(x, topMargin, x, y);

            g2.setColor(Color.GRAY);
            String label = String.valueOf(i);
            FontMetrics axisfm = g2.getFontMetrics(axisLabelFont);
            int labelWidth = axisfm.stringWidth(label);
            g2.drawString(label, x - labelWidth / 2, y + Math.round(15 * fontScaleFactor));
            g2.setColor(Color.LIGHT_GRAY);
        }

        // Draw X-axis label
        g2.setColor(Color.BLACK);
        g2.setFont(new Font("Arial", Font.BOLD, Math.round(12 * fontScaleFactor)));
        String xLabel = "Execution Order (ID)";
        FontMetrics xfm = g2.getFontMetrics();
        int xLabelWidth = xfm.stringWidth(xLabel);
        g2.drawString(xLabel, (getWidth() - xLabelWidth) / 2, topMargin + chartHeight + Math.round(40 * fontScaleFactor));

        g2.setStroke(new BasicStroke(1));

        // Draw tasks with linear scaling (no logarithm, no heat strips)
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

            // FIX: Use task index (i) instead of database ID for X position
            // Database IDs can be non-sequential (221, 222, etc.) which causes bars to render off-screen
            int x = leftMargin + (i * chartWidth / Math.max(1, tasks.size()));

            // FIXED WIDTH - all bars same width for timeline visualization
            int barWidth = Math.max(8, (chartWidth / Math.max(1, tasks.size())) - 4);

        //    int barHeight = laneHeight * 2 / 5;
            int barHeight = (int) (laneHeight * 0.75);
            int y = topMargin + (laneIndex * laneHeight) + (
            		laneHeight - barHeight) - 5;

            // Calculate queue and execution ratio for vertical split
            long totalTime = task.queueTime + task.processingTime;
            double queueRatio = totalTime > 0 ? (double) task.queueTime / totalTime : 0;
            
            // Split bar vertically based on queue/execution ratio
            if (showQueueTime && task.queueTime > 0 && totalTime > 0) {
                int queueHeight = (int) (barHeight * queueRatio);
                int execHeight = barHeight - queueHeight;
                
                // Draw queue time portion (top part of bar)
                Color queueColor = getQueueTimeColor(task.queueTime, task.processingTime);
                g2.setColor(queueColor);
                g2.fillRoundRect(x, y, barWidth, queueHeight, 5, 5);
                
                // Draw execution time portion (bottom part of bar)
                g2.setColor(barColor);
                g2.fillRoundRect(x, y + queueHeight, barWidth, execHeight, 5, 5);
                
                // Draw border around entire bar
                g2.setColor(barColor.darker());
                g2.drawRoundRect(x, y, barWidth, barHeight, 5, 5);
                
                // Draw dividing line between queue and execution
                g2.setColor(Color.BLACK);
                g2.drawLine(x, y + queueHeight, x + barWidth, y + queueHeight);
            } else {
                // No queue time - draw solid execution bar
                g2.setColor(barColor);
                g2.fillRoundRect(x, y, barWidth, barHeight, 5, 5);

                g2.setColor(barColor.darker());
                g2.drawRoundRect(x, y, barWidth, barHeight, 5, 5);
            }

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
        
        Graphics2D g2 = (Graphics2D) getGraphics();
        int leftMargin = calculateLeftMargin(g2, displayGroups);
        int topMargin = Math.round(80 * fontScaleFactor);
        int rightMargin = 13;
        int chartWidth = getWidth() - leftMargin - rightMargin;
        
        int numLanes = displayGroups.size();
        int fullLaneHeight = (getHeight() - topMargin - 15) / numLanes;
        int laneHeight = (int) (fullLaneHeight * 0.80);
        
      //  int barHeight = laneHeight * 2 / 5;
        int barHeight = (int) (laneHeight * 0.75);
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
            
            // FIX: Use task index (i) instead of database ID for X position
            int x = leftMargin + (i * chartWidth / Math.max(1, tasks.size()));
            
            // FIXED WIDTH - all bars same width for timeline visualization
            int barWidth = Math.max(8, (chartWidth / Math.max(1, tasks.size())) - 4);
            
            int queueBarWidth = 0;
            if (showQueueTime && task.queueTime > 0) {
                queueBarWidth = barWidth / 2;
            }
            
            int y = topMargin + (laneIndex * laneHeight) + ((laneHeight - barHeight) / 2);
            
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
        
        if (x + tooltipWidth > getWidth()) x = getWidth() - tooltipWidth - 10;
        if (y < 0) y = 10;
        
        g2.setColor(new Color(0, 0, 0, 230));
        g2.fillRoundRect(x, y, tooltipWidth, tooltipHeight, 5, 5);
        
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith("Queue Time:")) {
                if (task.queueTime > 5000) {
                    g2.setColor(new Color(255, 100, 100));
                } else if (task.queueTime > 2000) {
                    g2.setColor(new Color(255, 200, 100));
                } else {
                    g2.setColor(new Color(100, 255, 100));
                }
            } else {
                g2.setColor(Color.WHITE);
            }
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
    
    public static void createAndShowGUI(SwingGanttChart_WithLatency_v1a chart) {
        JFrame frame = new JFrame("Service Performance Gantt Chart (Linear Scale)");
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
        
        int[] truncateOptions = {20, 40, 60, 80, 100, Integer.MAX_VALUE};
        String[] truncateLabels = {"First 20 Tasks", "First 40 Tasks", "First 60 Tasks", 
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
            SwingGanttChart_WithLatency_v1a chart = new SwingGanttChart_WithLatency_v1a();
            createAndShowGUI(chart);
        });
    }
}