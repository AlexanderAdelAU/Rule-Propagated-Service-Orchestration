package org.btsn.derby.Analysis;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.*;
import java.util.List;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.btsn.constants.VersionConstants;

// For PDF export - requires Apache PDFBox library
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;

/**
 * Recorded service invocation timings, with an optional workflow elapsed view.
 * Local host measurements work without process completion or a running Monitor.
 */
public class SwingGanttChart_WithLatency_v1d extends JPanel {
    private static final double BAR_SLOT_CENTER = 0.345;
    private static final double BAR_SLOT_WIDTH = 0.225;
    private boolean wideBars = true;
    private double barSlotWidth() { return wideBars ? 0.60 : BAR_SLOT_WIDTH; }
    
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
    private boolean displayByVersion = true;
    private boolean independentLaneScales = true;
    private boolean lightQueueBars = false;
    private Map<String, List<Task>> versionGroups = new HashMap<>();
    private List<String> uniqueVersions = new ArrayList<>();
    private Map<String, Color> versionColors = new HashMap<>();
    private Map<String, Integer> versionLanes = new HashMap<>();

    
    public static class Task {
        int id;
        String service;
        int sequenceId;
        long processingTime;
        long recordedArrival;
        long queueTime;
        String businessServices = "Unresolved";
        String process = WorkflowProcessNames.UNKNOWN;
        int serviceCount;  // Number of services in this workflow
        long elapsedTime;  // Bar value: recorded invocation time, or canonical elapsed in workflow mode.
        boolean hasElapsedTime, hasQueueTime, canonical;
        int invalidQueueVisits;
        
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
    
    private boolean serviceVisitMode;
    private static List<String> startupDatabases = Collections.singletonList(System.getProperty("btsn.analysis.database", "ServiceAnalysisDataBase"));
    private List<String> databasePaths = new ArrayList<>(startupDatabases);
    public SwingGanttChart_WithLatency_v1d() { this(false); }
    public SwingGanttChart_WithLatency_v1d(boolean serviceVisits) {
        serviceVisitMode = serviceVisits;
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
    public String generateWorkflowSummaryReport() {
        StringBuilder report = new StringBuilder(chartTitle().toUpperCase(Locale.ROOT) + "\n");
        report.append(processDescription(false)).append('\n');
        report.append(workflowCaption()).append('\n');
        report.append(observationDescription()).append('\n');
        report.append(scaleDescription()).append("\nX = unavailable " + barMeasurement() + "; ").append(missingQueueLabel())
            .append(" = unavailable queue measurement.\n");
        for(String group:displayByVersion?uniqueVersions:uniqueServices)
            report.append(group).append(" axis: 0 to ").append(axisLabel(axisMaximum(group))).append(" ms\n");
        report.append(exceptionDescription()).append('\n');
        report.append(orderingCaption()).append("\n\n");
        report.append(serviceVisitMode ? "Arrival | Token | Version | Service ms | Queue ms | Visits | Invalid visits | Service/place | Process\n" : "Arrival | Root sequence | Version | Elapsed ms | Max observed queue ms | Valid queue visits | Invalid/conflicting visits | Services | Process\n");
        for (int i=0; i<tasks.size(); i++) {
            Task t=tasks.get(i);
            report.append(i+1).append(" | ").append(t.sequenceId).append(" | ")
                .append(deriveVersion(t.sequenceId)).append(" | ").append(elapsedLabel(t))
                .append(" | ").append(queueLabel(t)).append(" | ").append(t.serviceCount)
                .append(" | ").append(t.invalidQueueVisits).append(" | ").append(t.businessServices)
                .append(" | ").append(t.process).append('\n');
        }
        return report.toString();
    }
    private static String elapsedLabel(Task t) { return t.hasElapsedTime?Long.toString(t.elapsedTime):"N/A"; }
    private static String queueLabel(Task t) { return t.hasQueueTime?Long.toString(t.queueTime):"N/A"; }

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
        exportText(filename, generateLaTeXFigure());
    }
    public void exportToLaTeXTable(String filename) {
        exportText(filename, generateLaTeXTable());
    }
    private void exportText(String filename, String content) {
        try {
            Files.writeString(Paths.get(filename), content);
            if (!GraphicsEnvironment.isHeadless()) JOptionPane.showMessageDialog(this,
                "Exported successfully to: " + filename, "Export Success", JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException e) {
            if (!GraphicsEnvironment.isHeadless()) JOptionPane.showMessageDialog(this,
                "Export failed: " + e.getMessage(), "Export Error", JOptionPane.ERROR_MESSAGE);
            throw new UncheckedIOException(e);
        }
    }
    /** Vector publication export uses the same values, order, scale and glyphs as the panel. */
    public String generateLaTeXFigure() {
        StringWriter text=new StringWriter();
        PrintWriter w=new PrintWriter(text);
        List<String> groups=displayByVersion?uniqueVersions:uniqueServices;
        Map<String,Integer> lanes=displayByVersion?versionLanes:serviceLanes;
        Map<String,Color> colors=displayByVersion?versionColors:serviceColors;
        int count=Math.min(tasks.size(),maxDisplayTasks);
        w.println("% Preamble: \\usepackage{tikz}. "+scaleDescription());
        w.println("\\begin{figure}[htbp]\n\\centering");
        w.printf(Locale.ROOT,"\\begin{tikzpicture}[x=%.5fcm,y=1.9cm,font=\\scriptsize]%n",14.0/Math.max(1,count));
        for(int lane=0;lane<groups.size();lane++) {
            String group=groups.get(lane);
            double maximum=axisMaximum(group);
            Color c=colors.getOrDefault(group,Color.GRAY);
            w.printf("\\definecolor{lane%d}{RGB}{%d,%d,%d}%n",lane,c.getRed(),c.getGreen(),c.getBlue());
            if(lightQueueBars) {
                Color q=queueBarColor(c);
                w.printf("\\definecolor{queue%d}{RGB}{%d,%d,%d}%n",lane,q.getRed(),q.getGreen(),q.getBlue());
            }
            double baseline=groups.size()-1-lane;
            if(queueOnlyScale(group)) w.printf(Locale.ROOT,"\\node[anchor=east] at ([xshift=-1.15cm]0,%.3f) {queue only};%n",baseline+0.25);
            w.printf(Locale.ROOT,"\\node[anchor=east,text=lane%d] at ([xshift=-1.15cm]0,%.3f) {%s};%n",lane,baseline+0.45,escapeLatex(group));
            for(int tick=0;tick<=2;tick++) {
                double y=baseline+tick*0.4;
                w.printf(Locale.ROOT,"\\draw[gray!30] (0,%.3f)--(%d,%.3f);%n",y,count,y);
                w.printf(Locale.ROOT,"\\node[anchor=east] at ([xshift=-0.08cm]0,%.3f) {%s};%n",y,axisLabel(maximum*tick/2));
            }
        }
        for(int i=0;i<count;i++) {
            Task t=tasks.get(i);
            Integer lane=lanes.get(displayByVersion?deriveVersion(t.sequenceId):t.service);
            if(lane==null) continue;
            double base=groups.size()-1-lane;
            double maximum=axisMaximum(groups.get(lane));
            double barLeft=i+BAR_SLOT_CENTER-barSlotWidth()/2;
            double barRight=i+BAR_SLOT_CENTER+barSlotWidth()/2;
            if(t.hasElapsedTime) {
                double top=base+0.8*t.elapsedTime/maximum;
                if(t.elapsedTime==0) w.printf(Locale.ROOT,"\\draw[lane%d,thick] (%.3f,%.3f)--(%.3f,%.3f);%n",lane,barLeft,base,barRight,base);
                else w.printf(Locale.ROOT,"\\filldraw[fill=lane%d,draw=lane%d] (%.3f,%.3f) rectangle (%.3f,%.3f);%n",lane,lane,barLeft,base,barRight,top);
            } else w.printf(Locale.ROOT,"\\node at (%.3f,%.3f) {$\\times$};%n",i+BAR_SLOT_CENTER,base);
            if(t.hasQueueTime) {
                long shown=queueDisplayValue(t,maximum);
                double y=base+0.8*shown/maximum;
                if(lightQueueBars) {
                    if(shown==0) w.printf(Locale.ROOT,"\\draw[queue%d,thick] (%.3f,%.3f)--(%.3f,%.3f);%n",lane,barLeft,base,barRight,base);
                    else if(t.hasElapsedTime) w.printf(Locale.ROOT,"\\fill[queue%d] (%.3f,%.3f) rectangle (%.3f,%.3f);%n",lane,barLeft,base,barRight,y);
                    else w.printf(Locale.ROOT,"\\draw[queue%d,dashed] (%.3f,%.3f) rectangle (%.3f,%.3f);%n",lane,barLeft,base,barRight,y);
                    if(t.hasElapsedTime) w.printf(Locale.ROOT,"\\draw[lane%d] (%.3f,%.3f) rectangle (%.3f,%.3f);%n",lane,barLeft,base,barRight,base+0.8*t.elapsedTime/maximum);
                } else {
                    double x=i+0.82;
                    // Explicit diamond, with equal physical half-width/height across display ranges.
                    double radius=Math.min(0.07,0.23*14.0/Math.max(1,count));
                    double dx=radius*count/14.0,dy=radius/1.9;
                    w.printf(Locale.ROOT,"\\fill[black] (%.4f,%.4f)--(%.4f,%.4f)--(%.4f,%.4f)--(%.4f,%.4f)--cycle;%n",x-dx,y,x,y+dy,x+dx,y,x,y-dy);
                }
                if(t.queueTime>shown) w.printf(Locale.ROOT,"\\node[anchor=south] at (%.3f,%.3f) {$\\uparrow$};%n",i+(lightQueueBars?BAR_SLOT_CENTER:0.82),y);
            }
        }
        int step=Math.max(1,count/10);
        for(int i=0;i<count;i+=step) w.printf(Locale.ROOT,"\\node[below] at (%.3f,-0.08) {%d};%n",i+BAR_SLOT_CENTER,i+1);
        w.printf(Locale.ROOT,"\\node[below] at (%.3f,-0.25) {%s};%n",count/2.0,arrivalCaption());
        w.println("\\end{tikzpicture}");
        w.println("\\caption{"+escapeLatex(processDescription(false))+". "+workflowCaption()+" "+scaleDescription()+" "+observationDescription()+" "+exceptionDescription()+" "+orderingCaption()+"}");
        w.println("\\label{fig:workflow-elapsed-queue}\n\\end{figure}");
        w.flush(); return text.toString();
    }
    public String generateLaTeXTable() {
        StringWriter text=new StringWriter(); PrintWriter w=new PrintWriter(text);
        w.println("% Preamble: \\usepackage{booktabs,longtable}. N/A is not zero.");
        w.println("\\begin{longtable}{rrrrrrrp{5cm}}\n\\toprule");
        w.println((serviceVisitMode ? "Arrival & Token & Version & Service (ms) & Queue (ms) & Visits & Invalid visits & Process " : "Arrival & Root & Version & Elapsed (ms) & Max queue (ms) & Valid visits & Invalid visits & Process ") + "\\\\");
        w.println("\\midrule\n\\endhead");
        for(int i=0;i<Math.min(tasks.size(),maxDisplayTasks);i++) {
            Task t=tasks.get(i);
            w.printf("%d & %d & %s & %s & %s & %d & %d & %s \\\\%n",i+1,t.sequenceId,
                escapeLatex(deriveVersion(t.sequenceId)),elapsedLabel(t),queueLabel(t),t.serviceCount,t.invalidQueueVisits,escapeLatex(t.process));
        }
        w.println("\\bottomrule\n\\caption{"+escapeLatex(processDescription(false))+". "+workflowCaption()+"}\n\\label{tab:workflow-elapsed-queue}\n\\end{longtable}");
        w.flush(); return text.toString();
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
        int topMargin = plotTop(width);
        int bottomPadding = Math.round(105 * fontScaleFactor);
        int numLanes = uniqueVersions.size();
        int laneHeight = Math.round(110 * fontScaleFactor);
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
        
        int width=Math.round(Math.max(1200,maxId*18+180)*fontScaleFactor);
        setPreferredSize(new Dimension(width,plotTop(width)+Math.round((105+Math.max(1,uniqueVersions.size())*110)*fontScaleFactor)));
        revalidate();

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
    
    
    protected void loadDataFromDatabase() {
        tasks.clear(); uniqueServices.clear(); serviceColors.clear(); serviceLanes.clear();
        try {
            Class.forName("org.apache.derby.jdbc.EmbeddedDriver");
            int id=1;
            for (String databasePath : databasePaths) try (Connection c=DriverManager.getConnection("jdbc:derby:" + databasePath)) {
                if (serviceVisitMode) {
                    WorkflowProcessNames processes = WorkflowProcessNames.load(c);
                    ServiceDisplayNames names = ServiceDisplayNames.load(c);
                    for (RecordedServiceTimings.Visit visit : RecordedServiceTimings.load(c)) {
                        Task t = new Task(id++, deriveVersion((int)visit.token), (int)visit.token, visit.serviceMs);
                        t.recordedArrival = visit.arrival;
                        t.hasElapsedTime = visit.hasService; t.elapsedTime = visit.serviceMs;
                        t.hasQueueTime = visit.hasQueue; t.queueTime = visit.queueMs;
                        t.serviceCount = 1; t.invalidQueueVisits = visit.hasQueue ? 0 : 1;
                        String logical = names.visitLabel(visit.token / 1000000 * 1000000, visit.token, visit.place, visit.arrival);
                        t.businessServices = logical.equals(visit.place) ? visit.place : logical + " (" + visit.place + ")";
                        t.process = processes.forRoot((int)visit.token, visit.arrival);
                        tasks.add(t);
                        if (!uniqueServices.contains(t.service)) uniqueServices.add(t.service);
                    }
                } else for (CombinedWorkflowMetrics.Workflow row:CombinedWorkflowMetrics.load(c)) {
                    Task t=new Task(id++,deriveVersion(row.rootTokenId),row.rootTokenId,0);
                    t.hasElapsedTime=row.hasDuration(); t.hasQueueTime=row.hasQueueMaximum();
                    t.canonical=row.canonical;
                    t.elapsedTime=t.hasElapsedTime?row.durationMs():0;
                    t.queueTime=row.maxQueueMs; t.serviceCount=row.validQueueVisits;
                    t.invalidQueueVisits=row.invalidQueueVisits; t.businessServices=row.services;
                    t.process=row.process;
                    tasks.add(t);
                    if(!uniqueServices.contains(t.service)) uniqueServices.add(t.service);
                }
            }
            if (serviceVisitMode) {
                tasks.sort(Comparator.comparingLong((Task t) -> t.recordedArrival).thenComparingInt(t -> t.sequenceId));
                for (int i=0; i<tasks.size(); i++) tasks.get(i).id=i+1;
            }
            Collections.sort(uniqueServices);
            for(int i=0;i<uniqueServices.size();i++) {
                String version=uniqueServices.get(i);
                serviceLanes.put(version,i); serviceColors.put(version,getVersionColor(version,i));
            }
            maxId=tasks.size()+1;
            groupTasksByVersion();
            int width=Math.max(1200,maxId*18+180);
            setPreferredSize(new Dimension(width,plotTop(width)+Math.round((105+Math.max(1,uniqueServices.size())*110)*fontScaleFactor)));
            System.out.println("Loaded " + tasks.size() + (serviceVisitMode ? " recorded service visits" : " root workflows for elapsed / queue view"));
        } catch(Exception e) {
            throw new IllegalStateException("Unable to load measured workflow metrics",e);
        }
    }
    public void setServiceVisitMode(boolean serviceVisits) {
        serviceVisitMode = serviceVisits;
        loadDataFromDatabase(); repaint();
    }
    public void setDatabasePath(String path) {
        setDatabasePaths(Collections.singletonList(path));
    }
    public void setDatabasePaths(List<String> paths) {
        if (paths.isEmpty()) throw new IllegalArgumentException("Select at least one database");
        databasePaths = new ArrayList<>(new LinkedHashSet<>(paths)); loadDataFromDatabase(); repaint();
    }
    private String chartTitle() { return serviceVisitMode ? "Service Invocation Time and Queue Wait" : "Workflow Elapsed Time and Queue Wait"; }
    private String barMeasurement() { return serviceVisitMode ? "service invocation time" : "workflow elapsed time"; }
    private String arrivalCaption() { return serviceVisitMode ? "Service invocation arrival rank" : "Workflow arrival rank"; }
    private String orderingCaption() { return serviceVisitMode ? "Arrival order uses recorded service arrival timestamps. No workflow completion or Monitor acknowledgement is required." : "Arrival order uses GENERATED timestamps, or recorded workflow starts for legacy rows. N/A is not zero."; }
    public void setLightQueueBars(boolean lightBars) {
        lightQueueBars=lightBars;
        repaint();
    }
    public void setWideBars(boolean wide) {
        wideBars=wide;
        hoveredTask=null;
        repaint();
    }
    private String workflowCaption() {
        if (serviceVisitMode) return "Bars show recorded service invocation time; queue wait is measured separately for each visit. Circulating tokens appear on every recorded invocation. No process completion or Monitor acknowledgement is required.";
        return lightQueueBars
            ? "Bar height shows measured workflow elapsed time; lighter lower shading marks maximum observed service-visit queue wait, including fork branches. Shading is an overlay, not total workflow waiting time or a decomposition into waiting and service time."
            : CombinedWorkflowMetrics.CAPTION;
    }
    private String missingQueueLabel() { return lightQueueBars?"no lighter shading":"no diamond"; }
    public String processDescription(boolean abbreviated) {
        Map<String,Set<String>> byVersion=new TreeMap<>();
        for(Task task:tasks) byVersion.computeIfAbsent(deriveVersion(task.sequenceId),k->new TreeSet<>())
            .add(abbreviated?WorkflowProcessNames.shortName(task.process):task.process);
        List<String> labels=new ArrayList<>();
        for(Map.Entry<String,Set<String>> entry:byVersion.entrySet()) labels.add(entry.getKey()+": "+String.join(" / ",entry.getValue()));
        return labels.isEmpty()?WorkflowProcessNames.UNKNOWN:"Processes: "+String.join("; ",labels);
    }
    private List<String> processHeaderLines(int width) {
        return WorkflowProcessNames.wrap(processDescription(true),getFontMetrics(axisLabelFont),Math.max(40,width-40));
    }
    private int processHeaderHeight(int width) {
        return processHeaderLines(width).size()*(getFontMetrics(axisLabelFont).getHeight()+Math.round(2*fontScaleFactor));
    }
    private int plotTop(int width) { return Math.round(112*fontScaleFactor)+processHeaderHeight(width); }
    static Color queueBarColor(Color primary) {
        return new Color((primary.getRed()+255)/2,(primary.getGreen()+255)/2,(primary.getBlue()+255)/2);
    }
    public void setIndependentLaneScales(boolean independent) {
        independentLaneScales=independent;
        repaint();
    }
    private String scaleDescription() {
        return independentLaneScales
            ? "Each version scales to its maximum measured " + (serviceVisitMode ? "service invocation time" : "workflow duration") + "; compare ms values across versions."
            : "All versions share the maximum measured " + (serviceVisitMode ? "service invocation time" : "workflow duration") + " on one millisecond scale.";
    }
    // Scale from measured workflow durations; preserve the range when truncating arrival rows.
    double axisMaximum() { return maximumForGroup(null); }
    double axisMaximum(String group) {
        return maximumForGroup(independentLaneScales?group:null);
    }
    private double maximumForGroup(String group) {
        double elapsedMax=0,queueMax=0;
        boolean measured=false;
        for(Task t:tasks) {
            String taskGroup=displayByVersion?deriveVersion(t.sequenceId):t.service;
            if(group!=null&&!group.equals(taskGroup)) continue;
            if(t.hasElapsedTime) { elapsedMax=Math.max(elapsedMax,t.elapsedTime); measured=true; }
            if(t.hasQueueTime) queueMax=Math.max(queueMax,t.queueTime);
        }
        // Without any measured duration, an explicitly labelled queue-only range remains useful.
        return Math.max(1, serviceVisitMode ? Math.max(elapsedMax, queueMax) : measured?elapsedMax:queueMax);
    }
    private boolean hasMeasuredDuration(String group) {
        for(Task t:tasks) if(t.hasElapsedTime&&(group==null||group.equals(displayByVersion?deriveVersion(t.sequenceId):t.service))) return true;
        return false;
    }
    private boolean queueOnlyScale(String group) {
        return !hasMeasuredDuration(independentLaneScales?group:null);
    }
    private long queueDisplayValue(Task t,double maximum) {
        double limit=!serviceVisitMode&&lightQueueBars&&t.hasElapsedTime?Math.min(maximum,t.elapsedTime):maximum;
        return (long)Math.min(t.queueTime,limit);
    }
    private String observationDescription() {
        if (serviceVisitMode) return "Showing " + Math.min(tasks.size(), maxDisplayTasks) + " of " + tasks.size() + " recorded service invocations, including repeated visits by circulating tokens.";
        return "Showing "+Math.min(tasks.size(),maxDisplayTasks)+" of "+tasks.size()+
            " observed root workflows. Fork children contribute to their family's queue maximum.";
    }
    private String exceptionDescription() {
        int queueOnly=0,missingElapsed=0,missingQueue=0;
        boolean overflow=false;
        Map<String,Double> maxima=new HashMap<>();
        for(int i=0;i<Math.min(tasks.size(),maxDisplayTasks);i++) {
            Task t=tasks.get(i);
            if(!t.hasElapsedTime) { missingElapsed++; if(t.hasQueueTime) queueOnly++; }
            if(!t.hasQueueTime) missingQueue++;
            String group=displayByVersion?deriveVersion(t.sequenceId):t.service;
            double maximum=maxima.computeIfAbsent(group,this::axisMaximum);
            if(t.hasQueueTime&&t.queueTime>queueDisplayValue(t,maximum)) overflow=true;
        }
        List<String> notes=new ArrayList<>();
        if(missingElapsed>0) notes.add("X: " + (serviceVisitMode ? "service invocation time" : "elapsed interval") + " unavailable ("+missingElapsed+")");
        if(missingQueue>0) notes.add(missingQueueLabel()+": queue measurement unavailable ("+missingQueue+")");
        if(lightQueueBars&&queueOnly>0) notes.add("Dashed outlines: queue wait measured, " + (serviceVisitMode ? "service invocation time" : "elapsed interval") + " unavailable ("+queueOnly+")");
        if(overflow) notes.add("Arrow: queue exceeds drawn range");
        if(missingElapsed>0||missingQueue>0) notes.add("Missing measurements are not zero");
        return notes.isEmpty()? (serviceVisitMode ? "All shown invocations have measured service and queue values." : "All shown workflows have measured elapsed and queue values."):String.join("; ",notes)+".";
    }
    private String axisLabel(double value) {
        return value==Math.rint(value)?String.format(Locale.ROOT,"%.0f",value):String.format(Locale.ROOT,"%.1f",value);
    }
    private final class Plot {
        final List<String> groups=displayByVersion?uniqueVersions:uniqueServices;
        final Map<String,Integer> lanes=displayByVersion?versionLanes:serviceLanes;
        final Map<String,Color> colors=displayByVersion?versionColors:serviceColors;
        final int count=Math.min(tasks.size(),maxDisplayTasks);
        final int left,top=plotTop(getWidth()),width,laneHeight,plotHeight;
        final double slot;
        final double[] maxima;
        Plot(Graphics2D g) {
            maxima=new double[groups.size()];
            for(int lane=0;lane<groups.size();lane++) maxima[lane]=axisMaximum(groups.get(lane));
            left=calculateLeftMargin(g,groups)+Math.round(55*fontScaleFactor);
            width=Math.max(1,getWidth()-left-35);
            laneHeight=Math.max(1,(getHeight()-top-Math.round(105*fontScaleFactor))/Math.max(1,groups.size()));
            plotHeight=Math.max(1,(int)(laneHeight*0.78)); slot=width/(double)Math.max(1,count);
        }
        int lane(Task t) { return lanes.getOrDefault(displayByVersion?deriveVersion(t.sequenceId):t.service,0); }
        int baseline(int lane) { return top+(lane+1)*laneHeight-Math.round(15*fontScaleFactor); }
        int valueY(int lane,long value) { return baseline(lane)-(int)Math.round(value*plotHeight/maxima[lane]); }
        int barX(int i) { return left+(int)Math.round((i+BAR_SLOT_CENTER)*slot)-barWidth()/2; }
        int barWidth() { return Math.max(1,(int)(slot*barSlotWidth())); }
        int queueBarX(int i) { return barX(i); }
        int queueBarWidth() { return barWidth(); }
        int diamondX(int i) { return left+(int)Math.round((i+0.82)*slot); }
        int diamondRadius() { return Math.max(2,Math.min(Math.round(4*fontScaleFactor),(int)(slot*0.13))); }
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g=(Graphics2D)graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.BLACK); g.setFont(titleFont);
            String title=chartTitle();
            g.drawString(title,(getWidth()-g.getFontMetrics().stringWidth(title))/2,Math.round(30*fontScaleFactor));
            g.setFont(axisLabelFont);
            int processY=Math.round(49*fontScaleFactor);
            for(String line:processHeaderLines(getWidth())) {
                g.drawString(line,20,processY);
                processY+=g.getFontMetrics().getHeight()+Math.round(2*fontScaleFactor);
            }
            if(tasks.isEmpty()) { g.drawString("No observed workflows",25,100); return; }
            Plot p=new Plot(g);
            g.setFont(axisLabelFont);
            g.drawString("Solid bar: measured " + barMeasurement() + "     "+(lightQueueBars?"Lighter lower shading":"Black diamond")
                +(serviceVisitMode ? ": this invocation queue wait" : ": maximum observed service-visit queue wait"),
                20,Math.round(52*fontScaleFactor)+processHeaderHeight(getWidth()));
            g.drawString(scaleDescription(),20,Math.round(70*fontScaleFactor)+processHeaderHeight(getWidth()));
            g.drawString("Queue wait is a separate measurement, not total workflow waiting time or a service-time decomposition.",
                20,Math.round(88*fontScaleFactor)+processHeaderHeight(getWidth()));
            for(int lane=0;lane<p.groups.size();lane++) {
                String group=p.groups.get(lane);
                for(int tick=0;tick<=2;tick++) {
                    int y=p.baseline(lane)-(int)Math.round(tick*p.plotHeight/2.0);
                    g.setColor(tick==0?Color.GRAY:new Color(225,228,232));
                    g.drawLine(p.left,y,p.left+p.width,y);
                    g.setColor(Color.DARK_GRAY); g.setFont(axisLabelFont);
                    String label=axisLabel(p.maxima[lane]*tick/2)+" ms";
                    g.drawString(label,p.left-g.getFontMetrics().stringWidth(label)-8,y+4);
                }
                Color c=p.colors.getOrDefault(group,Color.GRAY);
                int cy=p.baseline(lane)-p.plotHeight/2;
                g.setColor(c); g.fillRect(10,cy-7,12,12);
                g.setColor(Color.BLACK); g.setFont(labelFont);
                g.drawString(group,legendTextBelow?10:28,legendTextBelow?cy+22:cy+4);
                if(queueOnlyScale(group)) { g.setFont(axisLabelFont); g.drawString("queue only",10,cy+38); }
            }
            int gridInterval=Math.max(1,p.count/10);
            for(int i=0;i<p.count;i+=gridInterval) {
                int x=p.barX(i)+p.barWidth()/2;
                g.setColor(new Color(238,238,238));
                g.drawLine(x,p.top,x,p.baseline(p.groups.size()-1));
                g.setColor(Color.DARK_GRAY); g.setFont(axisLabelFont);
                String label=Integer.toString(i+1);
                g.drawString(label,x-g.getFontMetrics().stringWidth(label)/2,p.baseline(p.groups.size()-1)+22);
            }
            for(int i=0;i<p.count;i++) {
                Task t=tasks.get(i); int lane=p.lane(t),x=p.barX(i),base=p.baseline(lane);
                Color c=p.colors.getOrDefault(p.groups.get(lane),Color.GRAY);
                g.setColor(c);
                if(t.hasElapsedTime) {
                    int y=p.valueY(lane,t.elapsedTime),height=base-y;
                    if(height==0) { g.setStroke(new BasicStroke(2)); g.drawLine(x,base,x+p.barWidth(),base); g.setStroke(new BasicStroke(1)); }
                    else { g.fillRect(x,y,p.barWidth(),height); g.setColor(c.darker()); g.drawRect(x,y,p.barWidth(),height); }
                } else {
                    g.setColor(Color.GRAY); int cx=x+p.barWidth()/2;
                    g.drawLine(cx-3,base-3,cx+3,base+3); g.drawLine(cx-3,base+3,cx+3,base-3);
                }
                if(t.hasQueueTime) {
                    long shown=queueDisplayValue(t,p.maxima[lane]);
                    int y=p.valueY(lane,shown);
                    if(lightQueueBars) {
                        int height=base-y;
                        g.setColor(queueBarColor(c));
                        if(height==0) {
                            g.setStroke(new BasicStroke(2)); g.drawLine(x,base,x+p.barWidth(),base); g.setStroke(new BasicStroke(1));
                        } else if(t.hasElapsedTime) {
                            g.fillRect(x,y,p.barWidth(),height);
                        } else {
                            g.setStroke(new BasicStroke(1, BasicStroke.CAP_BUTT,BasicStroke.JOIN_MITER,10,new float[]{3,2},0));
                            g.drawRect(x,y,p.barWidth(),height); g.setStroke(new BasicStroke(1));
                        }
                        g.setColor(c.darker());
                        if(t.hasElapsedTime&&t.elapsedTime>0) {
                            g.drawLine(x,y,x+p.barWidth(),y);
                            int elapsedY=p.valueY(lane,t.elapsedTime);
                            g.drawRect(x,elapsedY,p.barWidth(),base-elapsedY);
                        }
                    } else {
                        int dx=p.diamondX(i),r=p.diamondRadius();
                        g.setColor(Color.BLACK);
                        g.fillPolygon(new int[]{dx-r,dx,dx+r,dx},new int[]{y,y-r,y,y+r},4);
                    }
                    if(t.queueTime>shown) {
                        int cx=lightQueueBars?x+p.barWidth()/2:p.diamondX(i);
                        g.setColor(Color.BLACK);
                        g.drawLine(cx,y-3,cx,y-10); g.drawLine(cx,y-10,cx-3,y-7); g.drawLine(cx,y-10,cx+3,y-7);
                    }
                }
            }
            g.setColor(Color.BLACK); g.setFont(labelFont);
            int bottom=p.baseline(p.groups.size()-1)+43;
            String xlabel=arrivalCaption();
            g.drawString(xlabel,(getWidth()-g.getFontMetrics().stringWidth(xlabel))/2,bottom);
            g.setFont(axisLabelFont);
            g.drawString(observationDescription(),20,bottom+22);
            int noteY=bottom+40;
            for(String line:WorkflowProcessNames.wrap(exceptionDescription(),g.getFontMetrics(),Math.max(40,getWidth()-40))) {
                g.drawString(line,20,noteY);
                noteY+=g.getFontMetrics().getHeight();
            }
            if(hoveredTask!=null) drawTooltip(g,hoveredTask);
        } finally { g.dispose(); }
    }
    Task getTaskAt(int mouseX,int mouseY) {
        if(tasks.isEmpty()) return null;
        Graphics2D g=new BufferedImage(1,1,BufferedImage.TYPE_INT_RGB).createGraphics();
        Plot p=new Plot(g); g.dispose();
        for(int i=0;i<p.count;i++) {
            Task t=tasks.get(i); int lane=p.lane(t),base=p.baseline(lane);
            int top=t.hasElapsedTime?p.valueY(lane,t.elapsedTime):base;
            Rectangle bar=new Rectangle(p.barX(i)-3,top-4,p.barWidth()+6,base-top+8);
            int r=p.diamondRadius()+3;
            int queueY=p.valueY(lane,queueDisplayValue(t,p.maxima[lane]));
            Rectangle marker=lightQueueBars
                ? new Rectangle(p.queueBarX(i)-3,queueY-4,p.queueBarWidth()+6,base-queueY+8)
                : new Rectangle(p.diamondX(i)-r,queueY-r,2*r,2*r);
            if(bar.contains(mouseX,mouseY)||(t.hasQueueTime&&marker.contains(mouseX,mouseY))) return t;
        }
        return null;
    }

    private void drawTooltip(Graphics2D g2, Task task) {
        String version = deriveVersion(task.sequenceId);
        
        String[] lines = {
            "Process: " + task.process,
            "Business services: " + task.businessServices,
            "Workflow version: " + version,
            (serviceVisitMode ? "Token: " : "Root sequence: ") + task.sequenceId,
            "Measured " + barMeasurement() + ": " + elapsedLabel(task) + " ms",
            "Maximum observed queue wait: " + queueLabel(task) + " ms",
            "Valid queue visits: " + task.serviceCount + "; invalid/conflicting: " + task.invalidQueueVisits,
            serviceVisitMode ? "Recorded service invocation; independent of process completion" : task.canonical ? "Queue maximum includes fork branches" : "Legacy row: GENERATED/completion interval unavailable",
            "Queue measurement is not total workflow waiting time",
            task.hasQueueTime&&task.queueTime>queueDisplayValue(task,axisMaximum(displayByVersion?deriveVersion(task.sequenceId):task.service))
                ? "Queue value exceeds drawn range; measured value shown above" : "Shading does not identify measured service time"
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
        this.maxDisplayTasks = Math.max(1,max);
        this.maxId = Math.min(tasks.size(), max) + 1;
        
        int width = Math.max(1200, maxId * 18 + 180);
        
        List<String> displayGroups = displayByVersion ? uniqueVersions : uniqueServices;
        if (displayGroups.isEmpty() && displayByVersion) {
            groupTasksByVersion();
            displayGroups = uniqueVersions;
        }
        
        int topMargin = plotTop(width);
        int bottomPadding = Math.round(105 * fontScaleFactor);
        int numLanes = Math.max(1, displayGroups.size());
        int laneHeight = Math.round(110 * fontScaleFactor);
        int chartHeight = numLanes * laneHeight;
        int totalHeight = topMargin + chartHeight + bottomPadding;
        
        setPreferredSize(new Dimension(width, totalHeight));
        revalidate();
        repaint();
    }
    
    public static void createAndShowGUI(SwingGanttChart_WithLatency_v1d chart) {
        JFrame frame = new JFrame(chart.chartTitle());
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
        JCheckBoxMenuItem visitsItem = new JCheckBoxMenuItem("Recorded Service Invocations", chart.serviceVisitMode);
        visitsItem.addActionListener(e -> { chart.setServiceVisitMode(visitsItem.isSelected()); frame.setTitle(chart.chartTitle()); });
        viewMenu.add(visitsItem);
        viewMenu.addSeparator();
        JMenuItem measuredTimelineItem = new JMenuItem("Measured Workflow Timeline...");
        measuredTimelineItem.addActionListener(e -> MeasuredWorkflowTimeline.showWindow());
        viewMenu.add(measuredTimelineItem);
        viewMenu.addSeparator();
        
        JMenuItem queueTimingItem = new JMenuItem("Service Queue Timings...");
        queueTimingItem.addActionListener(e -> ServiceQueueTimingView.showWindow());
        viewMenu.add(queueTimingItem);
        viewMenu.addSeparator();

        JMenu queueDisplayMenu = new JMenu("Queue Display");
        ButtonGroup queueDisplayGroup = new ButtonGroup();
        JRadioButtonMenuItem diamondItem = new JRadioButtonMenuItem("Diamonds", !chart.lightQueueBars);
        diamondItem.addActionListener(e -> chart.setLightQueueBars(false));
        queueDisplayGroup.add(diamondItem); queueDisplayMenu.add(diamondItem);
        JRadioButtonMenuItem lightBarItem = new JRadioButtonMenuItem("Lower Queue Shading", chart.lightQueueBars);
        lightBarItem.addActionListener(e -> chart.setLightQueueBars(true));
        queueDisplayGroup.add(lightBarItem); queueDisplayMenu.add(lightBarItem);
        viewMenu.add(queueDisplayMenu);
        viewMenu.addSeparator();

        JMenu barWidthMenu = new JMenu("Bar Width");
        ButtonGroup barWidthGroup = new ButtonGroup();
        JRadioButtonMenuItem wideItem = new JRadioButtonMenuItem("Wide (Publication)", chart.wideBars);
        wideItem.addActionListener(e -> chart.setWideBars(true));
        barWidthGroup.add(wideItem); barWidthMenu.add(wideItem);
        JRadioButtonMenuItem narrowItem = new JRadioButtonMenuItem("Narrow", !chart.wideBars);
        narrowItem.addActionListener(e -> chart.setWideBars(false));
        barWidthGroup.add(narrowItem); barWidthMenu.add(narrowItem);
        viewMenu.add(barWidthMenu);
        viewMenu.addSeparator();

        JMenu yScaleMenu = new JMenu("Y-Axis Scale");
        ButtonGroup yScaleGroup = new ButtonGroup();
        JRadioButtonMenuItem independentScaleItem = new JRadioButtonMenuItem("Scale Each Version", chart.independentLaneScales);
        independentScaleItem.addActionListener(e -> chart.setIndependentLaneScales(true));
        yScaleGroup.add(independentScaleItem); yScaleMenu.add(independentScaleItem);
        JRadioButtonMenuItem sharedScaleItem = new JRadioButtonMenuItem("Shared Milliseconds", !chart.independentLaneScales);
        sharedScaleItem.addActionListener(e -> chart.setIndependentLaneScales(false));
        yScaleGroup.add(sharedScaleItem); yScaleMenu.add(sharedScaleItem);
        viewMenu.add(yScaleMenu);
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
        frame.setSize(Math.min(1400, frame.getWidth()), Math.min(720, frame.getHeight()));
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }
    public static void main(String[] args) {
        if (args.length != 0) {
            List<String> paths = new ArrayList<>();
            for (int i=0; i<args.length; i+=2) {
                if (i+1>=args.length || !"-db".equals(args[i]))
                    throw new IllegalArgumentException("Usage: SwingGanttChart_WithLatency_v1d [-db stopped-host-database-path]...");
                paths.add(args[i+1]);
            }
            startupDatabases = paths;
        }
        SwingUtilities.invokeLater(() -> {
            SwingGanttChart_WithLatency_v1d chart = new SwingGanttChart_WithLatency_v1d();
            createAndShowGUI(chart);
        });
    }
}
