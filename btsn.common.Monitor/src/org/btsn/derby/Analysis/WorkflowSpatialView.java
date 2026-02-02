package org.btsn.derby.Analysis;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.sql.*;
import java.util.*;
import java.util.List;
import java.io.*;
import org.apache.derby.jdbc.EmbeddedDriver;
import org.btsn.constants.VersionConstants;

// For PDF export
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;

/**
 * PetriNetTokenFlowChart - Spatial visualization of token flow through Petri Net places
 * 
 * Shows tokens as horizontal bars at each place (Y-axis) over time (X-axis)
 * with connecting lines showing token movement and fork relationships.
 * 
 * Features:
 * - Token residence time bars at each place
 * - Fork visualization (parent → children)
 * - Color coding by workflow version or token family
 * - Hover tooltips with token details
 * - PDF and LaTeX export
 */
public class WorkflowSpatialView extends JPanel {

    private static final String PROTOCOL = "jdbc:derby:";
    private static final String DB_NAME = "ServiceAnalysisDataBase";
    private static final String DB_URL = PROTOCOL + DB_NAME;

    // Data structures
    private List<TokenPath> tokenPaths = new ArrayList<>();
    private List<TokenGenealogy> genealogy = new ArrayList<>();
    private Map<Integer, List<TokenPath>> tokenPathsById = new HashMap<>();
    private Map<Long, Color> workflowColors = new HashMap<>();
    private Map<Integer, Color> tokenFamilyColors = new HashMap<>();
    
    // Place ordering (Y-axis)
    private List<String> placeOrder = new ArrayList<>();
    private Map<String, Integer> placeLanes = new HashMap<>();
    
    // Time range
    private long minTime = Long.MAX_VALUE;
    private long maxTime = Long.MIN_VALUE;
    
    // Display settings
    private int leftMargin = 120;
    private int rightMargin = 50;
    private int topMargin = 60;
    private int bottomMargin = 80;
    private int laneHeight = 60;
    private int barHeight = 20;
    
    // Interaction
    private TokenPath hoveredPath = null;
    private Point mousePosition = null;
    
    // View options
    private boolean showFlowLines = true;
    private boolean showForkLines = true;
    private boolean colorByWorkflow = true;  // false = color by token family
    private boolean showTokenIds = false;
    private long selectedWorkflowBase = -1;  // -1 = show all
    private Set<String> hiddenPlaces = new HashSet<>();  // Places to hide from display
    private boolean showExitIndicators = true;  // Show grayed exit arrows to hidden places
    
    // Exit tracking - maps token's last visible path to its exit destination
    private Map<Integer, String> tokenExitDestinations = new HashMap<>();
    
    // Zoom settings
    private double zoomFactor = 1.0;
    private static final double ZOOM_MIN = 0.25;
    private static final double ZOOM_MAX = 4.0;
    private static final double ZOOM_STEP = 0.1;
    
    // Font settings
    private float fontScaleFactor = 1.0f;
    private Font titleFont;
    private Font labelFont;
    private Font axisFont;
    private Font tooltipFont;
    
    // Color palettes
    private Color[] workflowPalette = {
        new Color(52, 152, 219),   // Blue
        new Color(231, 76, 60),    // Red
        new Color(46, 204, 113),   // Green
        new Color(155, 89, 182),   // Purple
        new Color(243, 156, 18),   // Orange
        new Color(26, 188, 156),   // Teal
        new Color(241, 196, 15),   // Yellow
        new Color(52, 73, 94)      // Dark Gray
    };
    
    // Fixed service version colors: v001=Red, v002=Blue, v003=Green, etc.
    private Map<Integer, Color> serviceVersionColors = new HashMap<>() {{
        put(1, new Color(231, 76, 60));    // v001 = Red
        put(2, new Color(52, 152, 219));   // v002 = Blue
        put(3, new Color(46, 204, 113));   // v003 = Green
        put(4, new Color(155, 89, 182));   // v004 = Purple
        put(5, new Color(243, 156, 18));   // v005 = Orange
        put(6, new Color(26, 188, 156));   // v006 = Teal
        put(7, new Color(241, 196, 15));   // v007 = Yellow
        put(8, new Color(52, 73, 94));     // v008 = Dark Gray
    }};
    
    // Track which service versions are present in the data
    private Set<Integer> presentServiceVersions = new TreeSet<>();

    /**
     * Token path data structure
     */
    public static class TokenPath {
        int tokenId;
        long workflowBase;
        String placeName;
        long entryTime;
        long exitTime;
        long residenceTime;
        int entryBufferSize;
        int exitBufferSize;
        
        // Computed display coordinates
        int x, y, width;
        
        public TokenPath(int tokenId, long workflowBase, String placeName,
                        long entryTime, long exitTime, long residenceTime) {
            this.tokenId = tokenId;
            this.workflowBase = workflowBase;
            this.placeName = placeName;
            this.entryTime = entryTime;
            this.exitTime = exitTime;
            this.residenceTime = residenceTime;
        }
        
        public int getTokenFamily() {
            // NEW ENCODING: Extract parent token (workflow base)
            // childTokenId = parentTokenId + branchNumber (branch 1-99)
            // Example: 1000001 -> 1000000, 1000002 -> 1000000
            return tokenId - (tokenId % 100);
        }
        
        /**
         * Get service version from tokenId using VersionConstants
         */
        public int getServiceVersion() {
            return tokenId / VersionConstants.VERSION_BLOCK_SIZE;
        }
        
        public String getServiceVersionLabel() {
            return VersionConstants.getVersionFromSequenceId(tokenId);
        }
    }
    
    /**
     * Token genealogy (fork relationship)
     */
    public static class TokenGenealogy {
        int parentTokenId;
        int childTokenId;
        String forkTransitionId;
        long forkTimestamp;
        long workflowBase;
        
        public TokenGenealogy(int parentId, int childId, String transition, 
                             long timestamp, long workflowBase) {
            this.parentTokenId = parentId;
            this.childTokenId = childId;
            this.forkTransitionId = transition;
            this.forkTimestamp = timestamp;
            this.workflowBase = workflowBase;
        }
    }

    public WorkflowSpatialView() {
        setBackground(Color.WHITE);
        updateFonts();
        
        addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                mousePosition = e.getPoint();
                hoveredPath = getPathAt(e.getX(), e.getY());
                repaint();
            }
        });
        
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (hoveredPath != null && e.getClickCount() == 2) {
                    // Double-click to filter by workflow
                    if (selectedWorkflowBase == hoveredPath.workflowBase) {
                        selectedWorkflowBase = -1;  // Toggle off
                    } else {
                        selectedWorkflowBase = hoveredPath.workflowBase;
                    }
                    repaint();
                }
            }
        });
        
        // Ctrl+mouse wheel for zoom
        addMouseWheelListener(e -> {
            if (e.isControlDown()) {
                int rotation = e.getWheelRotation();
                double oldZoom = zoomFactor;
                
                if (rotation < 0) {
                    // Scroll up = zoom in
                    zoomFactor = Math.min(ZOOM_MAX, zoomFactor + ZOOM_STEP);
                } else {
                    // Scroll down = zoom out
                    zoomFactor = Math.max(ZOOM_MIN, zoomFactor - ZOOM_STEP);
                }
                
                if (oldZoom != zoomFactor) {
                    calculatePreferredSize();
                    revalidate();
                    repaint();
                }
                
                e.consume();
            }
        });
        
        // Keyboard shortcuts for zoom
        setFocusable(true);
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.isControlDown()) {
                    switch (e.getKeyCode()) {
                        case KeyEvent.VK_EQUALS:  // Ctrl++ (= key, often with shift for +)
                        case KeyEvent.VK_PLUS:
                        case KeyEvent.VK_ADD:     // Numpad +
                            zoomIn();
                            e.consume();
                            break;
                        case KeyEvent.VK_MINUS:
                        case KeyEvent.VK_SUBTRACT: // Numpad -
                            zoomOut();
                            e.consume();
                            break;
                        case KeyEvent.VK_0:
                        case KeyEvent.VK_NUMPAD0:
                            resetZoom();
                            e.consume();
                            break;
                    }
                }
            }
        });
        
        loadDataFromDatabase();
        calculatePreferredSize();
    }
    
    private void updateFonts() {
        titleFont = new Font("Arial", Font.BOLD, Math.round(16 * fontScaleFactor));
        labelFont = new Font("Arial", Font.PLAIN, Math.round(12 * fontScaleFactor));
        axisFont = new Font("Arial", Font.PLAIN, Math.round(10 * fontScaleFactor));
        tooltipFont = new Font("Arial", Font.PLAIN, Math.round(11 * fontScaleFactor));
    }
    
    private void calculatePreferredSize() {
        int visiblePlaceCount = placeOrder.size() - hiddenPlaces.size();
        int baseWidth = leftMargin + rightMargin + 800;  // Minimum width
        int baseHeight = topMargin + bottomMargin + (visiblePlaceCount * laneHeight);
        
        // Apply zoom factor to preferred size
        int width = (int)(Math.max(900, baseWidth) * zoomFactor);
        int height = (int)(Math.max(400, baseHeight) * zoomFactor);
        setPreferredSize(new Dimension(width, height));
    }

    /**
     * Load token path and genealogy data from database
     */
    private void loadDataFromDatabase() {
        Connection conn = null;
        Statement stmt = null;
        ResultSet rs = null;
        
        try {
            DriverManager.registerDriver(new EmbeddedDriver());
            conn = DriverManager.getConnection(DB_URL);
            stmt = conn.createStatement();
            
            tokenPaths.clear();
            genealogy.clear();
            placeOrder.clear();
            placeLanes.clear();
            tokenPathsById.clear();
            presentServiceVersions.clear();
            
            // Load token paths from CONSOLIDATED_TOKEN_PATHS
            String pathQuery = 
                "SELECT workflowBase, tokenId, placeName, entryTime, exitTime, " +
                "       residenceTime, entryBufferSize, exitBufferSize " +
                "FROM CONSOLIDATED_TOKEN_PATHS " +
                "ORDER BY workflowBase, entryTime";
            
            System.out.println("Loading token paths from CONSOLIDATED_TOKEN_PATHS...");
            rs = stmt.executeQuery(pathQuery);
            
            Set<String> places = new LinkedHashSet<>();
            Set<Long> workflows = new HashSet<>();
            
            while (rs.next()) {
                TokenPath path = new TokenPath(
                    rs.getInt("tokenId"),
                    rs.getLong("workflowBase"),
                    rs.getString("placeName"),
                    rs.getLong("entryTime"),
                    rs.getLong("exitTime"),
                    rs.getLong("residenceTime")
                );
                path.entryBufferSize = rs.getInt("entryBufferSize");
                path.exitBufferSize = rs.getInt("exitBufferSize");
                
                tokenPaths.add(path);
                places.add(path.placeName);
                workflows.add(path.workflowBase);
                presentServiceVersions.add(path.getServiceVersion());
                
                // Track time range
                if (path.entryTime < minTime) minTime = path.entryTime;
                if (path.exitTime > maxTime) maxTime = path.exitTime;
                
                // Group by token ID for flow line drawing
                tokenPathsById.computeIfAbsent(path.tokenId, k -> new ArrayList<>()).add(path);
            }
            rs.close();
            
            System.out.println("Loaded " + tokenPaths.size() + " token paths");
            System.out.println("Places found: " + places);
            System.out.println("Workflows found: " + workflows);
            
            // Order places logically (P1 → P2 → P3 → P4 → Monitor)
            String[] preferredOrder = {"P1_Place", "P2_Place", "P3_Place", "P4_Place", "MonitorService", "TERMINATE"};
            for (String p : preferredOrder) {
                if (places.contains(p)) {
                    placeOrder.add(p);
                    places.remove(p);
                }
            }
            // Add any remaining places
            placeOrder.addAll(places);
            
            // Assign lane indices
            for (int i = 0; i < placeOrder.size(); i++) {
                placeLanes.put(placeOrder.get(i), i);
            }
            
            // Assign workflow colors
            int colorIdx = 0;
            for (Long wf : workflows) {
                workflowColors.put(wf, workflowPalette[colorIdx % workflowPalette.length]);
                colorIdx++;
            }
            
            // Load genealogy from CONSOLIDATED_TOKEN_GENEALOGY
            String genealogyQuery = 
                "SELECT parentTokenId, childTokenId, forkTransitionId, forkTimestamp, workflowBase " +
                "FROM CONSOLIDATED_TOKEN_GENEALOGY " +
                "ORDER BY forkTimestamp";
            
            System.out.println("Loading genealogy from CONSOLIDATED_TOKEN_GENEALOGY...");
            rs = stmt.executeQuery(genealogyQuery);
            
            while (rs.next()) {
                TokenGenealogy gen = new TokenGenealogy(
                    rs.getInt("parentTokenId"),
                    rs.getInt("childTokenId"),
                    rs.getString("forkTransitionId"),
                    rs.getLong("forkTimestamp"),
                    rs.getLong("workflowBase")
                );
                genealogy.add(gen);
            }
            rs.close();
            
            System.out.println("Loaded " + genealogy.size() + " genealogy records");
            
            // Load exit destinations from CONSOLIDATED_TRANSITION_FIRINGS (tokens exiting to hidden places)
            tokenExitDestinations.clear();
            String exitQuery = 
                "SELECT tokenId, toPlace FROM CONSOLIDATED_TRANSITION_FIRINGS " +
                "WHERE eventType = 'EXIT' AND toPlace IN ('MonitorService', 'TERMINATE') " +
                "ORDER BY timestamp DESC";
            
            try {
                rs = stmt.executeQuery(exitQuery);
                while (rs.next()) {
                    int tokenId = rs.getInt("tokenId");
                    String dest = rs.getString("toPlace");
                    // Only store if not already tracked (we want the last exit)
                    tokenExitDestinations.putIfAbsent(tokenId, dest);
                }
                rs.close();
                System.out.println("Loaded " + tokenExitDestinations.size() + " exit destinations");
            } catch (SQLException exitEx) {
                // Table might not exist or be empty - fall back to deriving from paths
                System.out.println("CONSOLIDATED_TRANSITION_FIRINGS not available, deriving exit destinations from paths");
                deriveExitDestinations();
                System.out.println("Derived " + tokenExitDestinations.size() + " exit destinations");
            }
            
        } catch (SQLException e) {
            System.err.println("Error loading data: " + e.getMessage());
            e.printStackTrace();
        } finally {
            try {
                if (rs != null) rs.close();
                if (stmt != null) stmt.close();
                if (conn != null) conn.close();
            } catch (SQLException e) {
                // Ignore
            }
        }
    }
    
    /**
     * Derive exit destinations from token paths.
     * Tokens that end at P6_Place (or any terminal visible place) are assumed to exit to MonitorService.
     * This avoids needing a separate MARKING_EVOLUTION table.
     */
    private void deriveExitDestinations() {
        // Find the "terminal" places - places that have outgoing edges to hidden places
        // For now, assume P6_Place → MonitorService and any place → TERMINATE
        Set<String> terminalPlaces = new HashSet<>();
        terminalPlaces.add("P6_Place");  // Known terminal place for this workflow
        
        // For each token, find their last path and check if it's at a terminal place
        for (Map.Entry<Integer, List<TokenPath>> entry : tokenPathsById.entrySet()) {
            int tokenId = entry.getKey();
            List<TokenPath> paths = entry.getValue();
            if (paths.isEmpty()) continue;
            
            // Sort by exit time to find the last path
            paths.sort(Comparator.comparingLong(p -> p.exitTime));
            TokenPath lastPath = paths.get(paths.size() - 1);
            
            // Check if this is a terminal place
            if (terminalPlaces.contains(lastPath.placeName)) {
                tokenExitDestinations.put(tokenId, "MonitorService");
            }
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        
        // Apply zoom transform
        g2.scale(zoomFactor, zoomFactor);
        
        if (tokenPaths.isEmpty()) {
            drawNoDataMessage(g2);
            return;
        }
        
        // Use unscaled dimensions for chart layout calculations
        int chartWidth = (int)(getWidth() / zoomFactor) - leftMargin - rightMargin;
        int chartHeight = placeOrder.size() * laneHeight;
        
        // Draw title
        g2.setFont(titleFont);
        g2.setColor(Color.BLACK);
        String title = "Token Time in Service - Spatial View";
        if (selectedWorkflowBase > 0) {
            title += " (Workflow " + selectedWorkflowBase + ")";
        }
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(title, (getWidth() - fm.stringWidth(title)) / 2, 30);
        
        // Draw place labels (Y-axis) - only visible places
        g2.setFont(labelFont);
        int visibleLaneIdx = 0;
        for (int i = 0; i < placeOrder.size(); i++) {
            String place = placeOrder.get(i);
            if (hiddenPlaces.contains(place)) continue;
            
            int y = topMargin + (visibleLaneIdx * laneHeight) + (laneHeight / 2);
            
            // Lane background (alternating)
            if (visibleLaneIdx % 2 == 0) {
                g2.setColor(new Color(248, 248, 248));
                g2.fillRect(leftMargin, topMargin + (visibleLaneIdx * laneHeight), chartWidth, laneHeight);
            }
            
            // Place label
            g2.setColor(Color.BLACK);
            fm = g2.getFontMetrics();
            g2.drawString(place, leftMargin - fm.stringWidth(place) - 10, y + fm.getAscent()/2);
            
            // Lane separator
            g2.setColor(new Color(220, 220, 220));
            g2.drawLine(leftMargin, topMargin + ((visibleLaneIdx+1) * laneHeight), 
                       leftMargin + chartWidth, topMargin + ((visibleLaneIdx+1) * laneHeight));
            
            visibleLaneIdx++;
        }
        
        // Draw time axis
        drawTimeAxis(g2, chartWidth);
        
        // Calculate token bar positions
        long timeRange = maxTime - minTime;
        if (timeRange <= 0) timeRange = 1;
        
        for (TokenPath path : tokenPaths) {
            if (selectedWorkflowBase > 0 && path.workflowBase != selectedWorkflowBase) {
                continue;
            }
            
            Integer laneIdx = placeLanes.get(path.placeName);
            if (laneIdx == null) continue;
            
            // Calculate X position based on time
            double xRatio = (double)(path.entryTime - minTime) / timeRange;
            double widthRatio = (double)(path.exitTime - path.entryTime) / timeRange;
            
            path.x = leftMargin + (int)(xRatio * chartWidth);
            path.width = Math.max(3, (int)(widthRatio * chartWidth));
            path.y = topMargin + (laneIdx * laneHeight) + (laneHeight - barHeight) / 2;
        }
        
        // Draw flow lines (connecting same token across places)
        if (showFlowLines) {
            drawFlowLines(g2);
        }
        
        // Draw fork lines
        if (showForkLines) {
            drawForkLines(g2);
            drawJoinLines(g2);  // Join lines shown together with fork lines
        }
        
        // Draw token bars
        for (TokenPath path : tokenPaths) {
            if (selectedWorkflowBase > 0 && path.workflowBase != selectedWorkflowBase) {
                continue;
            }
            drawTokenBar(g2, path);
        }
        
        // Draw exit indicators to hidden places (MonitorService, TERMINATE)
        drawExitIndicators(g2, chartWidth);
        
        // Draw tooltip
        if (hoveredPath != null && mousePosition != null) {
            drawTooltip(g2, hoveredPath);
        }
        
        // Draw legend
        drawLegend(g2);
    }
    
    private void drawNoDataMessage(Graphics2D g2) {
        g2.setFont(titleFont);
        g2.setColor(Color.GRAY);
        String msg = "No token path data available";
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(msg, (getWidth() - fm.stringWidth(msg)) / 2, getHeight() / 2);
        
        g2.setFont(labelFont);
        String hint = "Run a workflow and ensure collectors execute successfully";
        fm = g2.getFontMetrics();
        g2.drawString(hint, (getWidth() - fm.stringWidth(hint)) / 2, getHeight() / 2 + 25);
    }
    
    private void drawTimeAxis(Graphics2D g2, int chartWidth) {
        g2.setFont(axisFont);
        g2.setColor(Color.BLACK);
        
        int axisY = topMargin + (placeOrder.size() * laneHeight) + 20;
        
        // Axis line
        g2.drawLine(leftMargin, axisY, leftMargin + chartWidth, axisY);
        
        // Time labels (relative to start)
        long timeRange = maxTime - minTime;
        int numTicks = 10;
        
        for (int i = 0; i <= numTicks; i++) {
            int x = leftMargin + (i * chartWidth / numTicks);
            long time = minTime + (i * timeRange / numTicks);
            long relativeMs = time - minTime;
            
            // Tick mark
            g2.drawLine(x, axisY, x, axisY + 5);
            
            // Label
            String label;
            if (relativeMs < 1000) {
                label = relativeMs + "ms";
            } else {
                label = String.format("%.1fs", relativeMs / 1000.0);
            }
            
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(label, x - fm.stringWidth(label)/2, axisY + 18);
        }
        
        // Axis title
        g2.setFont(labelFont);
        String axisTitle = "Time (relative to workflow start)";
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(axisTitle, leftMargin + (chartWidth - fm.stringWidth(axisTitle))/2, axisY + 40);
    }
    
    private void drawTokenBar(Graphics2D g2, TokenPath path) {
        Color barColor;
        if (colorByWorkflow) {
            // Color by service version (derived from sequenceId prefix)
            int serviceVersion = path.getServiceVersion();
            barColor = serviceVersionColors.getOrDefault(serviceVersion, Color.GRAY);
        } else {
            int family = path.getTokenFamily();
            barColor = tokenFamilyColors.computeIfAbsent(family, 
                k -> workflowPalette[Math.abs(k.hashCode()) % workflowPalette.length]);
        }
        
        // Highlight if hovered
        if (path == hoveredPath) {
            g2.setColor(barColor.brighter());
            g2.fillRoundRect(path.x - 2, path.y - 2, path.width + 4, barHeight + 4, 6, 6);
            g2.setColor(Color.BLACK);
            g2.setStroke(new BasicStroke(2));
            g2.drawRoundRect(path.x - 2, path.y - 2, path.width + 4, barHeight + 4, 6, 6);
        }
        
        // Draw bar
        g2.setColor(barColor);
        g2.fillRoundRect(path.x, path.y, path.width, barHeight, 4, 4);
        
        // Border
        g2.setColor(barColor.darker());
        g2.setStroke(new BasicStroke(1));
        g2.drawRoundRect(path.x, path.y, path.width, barHeight, 4, 4);
        
        // Token ID label (if enabled and bar is wide enough)
        if (showTokenIds && path.width > 30) {
            g2.setColor(Color.WHITE);
            g2.setFont(new Font("Arial", Font.PLAIN, 9));
            String idStr = String.valueOf(path.tokenId);
            FontMetrics fm = g2.getFontMetrics();
            if (fm.stringWidth(idStr) < path.width - 4) {
                g2.drawString(idStr, path.x + 3, path.y + barHeight - 4);
            }
        }
    }
    
    private void drawFlowLines(Graphics2D g2) {
        g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        
        // Build a set of parent token IDs that have forks (for detecting fork gaps)
        Set<Integer> parentTokensWithForks = new HashSet<>();
        Map<Integer, Long> parentForkTimes = new HashMap<>();
        for (TokenGenealogy gen : genealogy) {
            parentTokensWithForks.add(gen.parentTokenId);
            // Track earliest fork time for each parent
            parentForkTimes.merge(gen.parentTokenId, gen.forkTimestamp, Math::min);
        }
        
        for (Map.Entry<Integer, List<TokenPath>> entry : tokenPathsById.entrySet()) {
            int tokenId = entry.getKey();
            List<TokenPath> paths = entry.getValue();
            if (paths.size() < 2) continue;
            
            // Sort by entry time
            paths.sort(Comparator.comparingLong(p -> p.entryTime));
            
            // Check if filtered out
            if (selectedWorkflowBase > 0 && paths.get(0).workflowBase != selectedWorkflowBase) {
                continue;
            }
            
            // Use service version color
            int serviceVersion = paths.get(0).getServiceVersion();
            Color lineColor = serviceVersionColors.getOrDefault(serviceVersion, Color.GRAY);
            g2.setColor(new Color(lineColor.getRed(), lineColor.getGreen(), lineColor.getBlue(), 100));
            
            for (int i = 0; i < paths.size() - 1; i++) {
                TokenPath from = paths.get(i);
                TokenPath to = paths.get(i + 1);
                
                // Skip flow line if this parent token has a fork between these two paths
                // (the fork lines will show the connection instead)
                if (parentTokensWithForks.contains(tokenId)) {
                    Long forkTime = parentForkTimes.get(tokenId);
                    if (forkTime != null && from.exitTime <= forkTime && forkTime < to.entryTime) {
                        // There's a fork between 'from' and 'to' - skip this flow line
                        continue;
                    }
                }
                
                // Draw straight line from end of one bar (middle) to corner of next bar
                int x1 = from.x + from.width;
                int y1 = from.y + barHeight / 2;
                int x2 = to.x;
                // Use bottom-left for upward arrows, top-left for downward arrows
                int y2 = (to.y < from.y) ? to.y + barHeight : to.y;
                
                // Draw straight line
                g2.drawLine(x1, y1, x2, y2);
                
                // Arrow head
                drawArrowHead(g2, x2, y2, x1, y1);
            }
        }
    }
    
    private void drawForkLines(Graphics2D g2) {
        g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        
        for (TokenGenealogy gen : genealogy) {
            if (selectedWorkflowBase > 0 && gen.workflowBase != selectedWorkflowBase) {
                continue;
            }
            
            // Find parent's path at the fork point (where the fork happened)
            // This is the path whose exitTime is closest to forkTimestamp
            TokenPath parentPath = findParentPathAtFork(gen.parentTokenId, gen.forkTimestamp);
            TokenPath childPath = findPathForToken(gen.childTokenId, false);
            
            if (parentPath == null || childPath == null) continue;
            
            // Use service version color from parent path
            int serviceVersion = parentPath.getServiceVersion();
            Color lineColor = serviceVersionColors.getOrDefault(serviceVersion, Color.GRAY);
            g2.setColor(new Color(lineColor.getRed(), lineColor.getGreen(), lineColor.getBlue(), 150));
            
            // Draw fork line - straight line from parent exit to corner of child bar
            int x1 = parentPath.x + parentPath.width;
            int y1 = parentPath.y + barHeight / 2;
            int x2 = childPath.x;
            // Use bottom-left for upward arrows, top-left for downward arrows
            int y2 = (childPath.y < parentPath.y) ? childPath.y + barHeight : childPath.y;
            
            // Dashed line for forks
            float[] dash = {5f, 5f};
            g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 
                                         1f, dash, 0f));
            g2.drawLine(x1, y1, x2, y2);
            
            // Fork symbol at split point
            g2.setStroke(new BasicStroke(1.5f));
            g2.fillOval(x1 - 4, y1 - 4, 8, 8);
            
            // Arrow head at destination
            drawArrowHead(g2, x2, y2, x1, y1);
        }
    }
    
    /**
     * Find the parent's path at the fork point (where fork happened).
     * This is the path whose exitTime is closest to and before the forkTimestamp.
     */
    private TokenPath findParentPathAtFork(int parentTokenId, long forkTimestamp) {
        List<TokenPath> paths = tokenPathsById.get(parentTokenId);
        if (paths == null || paths.isEmpty()) return null;
        
        TokenPath best = null;
        long bestDiff = Long.MAX_VALUE;
        
        for (TokenPath p : paths) {
            // Fork happens at or after parent exits a place
            // Find the path whose exitTime is closest to (but <= ) forkTimestamp
            if (p.exitTime <= forkTimestamp) {
                long diff = forkTimestamp - p.exitTime;
                if (diff < bestDiff) {
                    bestDiff = diff;
                    best = p;
                }
            }
        }
        
        // Fallback: if no path exits before fork, use the first path
        if (best == null && !paths.isEmpty()) {
            paths.sort(Comparator.comparingLong(p -> p.entryTime));
            best = paths.get(0);
        }
        
        return best;
    }
    
    /**
     * Draw join lines - from child tokens' last path to parent's reconstitution at join point
     */
    private void drawJoinLines(Graphics2D g2) {
        g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        
        // Group children by parent
        Map<Integer, List<TokenGenealogy>> childrenByParent = new HashMap<>();
        for (TokenGenealogy gen : genealogy) {
            childrenByParent.computeIfAbsent(gen.parentTokenId, k -> new ArrayList<>()).add(gen);
        }
        
        for (Map.Entry<Integer, List<TokenGenealogy>> entry : childrenByParent.entrySet()) {
            int parentId = entry.getKey();
            List<TokenGenealogy> children = entry.getValue();
            
            if (selectedWorkflowBase > 0) {
                if (children.isEmpty() || children.get(0).workflowBase != selectedWorkflowBase) {
                    continue;
                }
            }
            
            // Find parent's path at join point (after fork, at P4)
            // This is the path that comes AFTER the fork
            TokenPath parentAtJoin = findParentPathAtJoin(parentId, children.get(0).forkTimestamp);
            if (parentAtJoin == null) continue;
            
            for (TokenGenealogy gen : children) {
                // Find child's last path (before being consumed at join)
                TokenPath childPath = findPathForToken(gen.childTokenId, true);  // lastPath=true
                if (childPath == null) continue;
                
                // Use service version color from child path
                int serviceVersion = childPath.getServiceVersion();
                Color lineColor = serviceVersionColors.getOrDefault(serviceVersion, Color.GRAY);
                g2.setColor(new Color(lineColor.getRed(), lineColor.getGreen(), lineColor.getBlue(), 150));
                
                // Draw join line - from child's exit to parent's entry at join
                int x1 = childPath.x + childPath.width;
                int y1 = childPath.y + barHeight / 2;
                int x2 = parentAtJoin.x;
                // Use bottom-left for upward arrows, top-left for downward arrows
                int y2 = (parentAtJoin.y < childPath.y) ? parentAtJoin.y + barHeight : parentAtJoin.y;
                
                // Dotted line for joins (different from fork dashes)
                float[] dot = {2f, 4f};
                g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 
                                             1f, dot, 0f));
                g2.drawLine(x1, y1, x2, y2);
                
                // Arrow head at destination (parent at join)
                drawArrowHead(g2, x2, y2, x1, y1);
            }
            
            // Draw join symbol at the join point
            g2.setStroke(new BasicStroke(1.5f));
            g2.setColor(new Color(100, 100, 100));
            int jx = parentAtJoin.x - 4;
            int jy = parentAtJoin.y + barHeight / 2 - 4;
            g2.drawOval(jx, jy, 8, 8);  // Empty circle for join (vs filled for fork)
        }
    }
    
    /**
     * Find the parent's path at the join point (where parent reconstitutes after fork).
     * This is the first path whose entryTime is after the forkTimestamp.
     */
    private TokenPath findParentPathAtJoin(int parentTokenId, long forkTimestamp) {
        List<TokenPath> paths = tokenPathsById.get(parentTokenId);
        if (paths == null || paths.isEmpty()) return null;
        
        TokenPath best = null;
        long bestTime = Long.MAX_VALUE;
        
        for (TokenPath p : paths) {
            // Find the first path that starts after the fork
            if (p.entryTime > forkTimestamp && p.entryTime < bestTime) {
                bestTime = p.entryTime;
                best = p;
            }
        }
        
        return best;
    }
    
    private TokenPath findPathForToken(int tokenId, boolean lastPath) {
        List<TokenPath> paths = tokenPathsById.get(tokenId);
        if (paths == null || paths.isEmpty()) return null;
        
        paths.sort(Comparator.comparingLong(p -> p.entryTime));
        return lastPath ? paths.get(paths.size() - 1) : paths.get(0);
    }
    
    private void drawArrowHead(Graphics2D g2, int x, int y, int fromX, int fromY) {
        double angle = Math.atan2(y - fromY, x - fromX);
        int arrowSize = 6;
        
        int x1 = (int)(x - arrowSize * Math.cos(angle - Math.PI/6));
        int y1 = (int)(y - arrowSize * Math.sin(angle - Math.PI/6));
        int x2 = (int)(x - arrowSize * Math.cos(angle + Math.PI/6));
        int y2 = (int)(y - arrowSize * Math.sin(angle + Math.PI/6));
        
        g2.fillPolygon(new int[]{x, x1, x2}, new int[]{y, y1, y2}, 3);
    }
    
    /**
     * Draw exit indicators showing tokens leaving to hidden places (MonitorService, TERMINATE)
     * Renders as grayed-out arrows with destination label
     */
    private void drawExitIndicators(Graphics2D g2, int chartWidth) {
        if (!showExitIndicators || tokenExitDestinations.isEmpty()) return;
        
        // Group exits by destination for cleaner display
        Map<String, List<TokenPath>> exitsByDestination = new HashMap<>();
        
        for (Map.Entry<Integer, String> exit : tokenExitDestinations.entrySet()) {
            int tokenId = exit.getKey();
            String destination = exit.getValue();
            
            // Only show exits to hidden places
            if (!hiddenPlaces.contains(destination)) continue;
            
            // Find this token's last visible path
            TokenPath lastPath = findPathForToken(tokenId, true);
            if (lastPath == null) continue;
            
            // Check workflow filter
            if (selectedWorkflowBase > 0 && lastPath.workflowBase != selectedWorkflowBase) {
                continue;
            }
            
            // Skip if the last path's place is also hidden
            if (hiddenPlaces.contains(lastPath.placeName)) continue;
            
            exitsByDestination.computeIfAbsent(destination, k -> new ArrayList<>()).add(lastPath);
        }
        
        // Draw exit indicators for each destination
        Color exitColor = new Color(150, 150, 150, 120);  // Grayed out
        g2.setFont(new Font("Arial", Font.ITALIC, 10));
        
        for (Map.Entry<String, List<TokenPath>> entry : exitsByDestination.entrySet()) {
            String destination = entry.getKey();
            List<TokenPath> paths = entry.getValue();
            
            for (TokenPath path : paths) {
                // Draw grayed arrow from end of bar going right
                int x1 = path.x + path.width;
                int y1 = path.y + barHeight / 2;
                int x2 = x1 + 40;  // Short arrow
                int y2 = y1;
                
                g2.setColor(exitColor);
                g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.drawLine(x1, y1, x2, y2);
                
                // Arrow head
                drawArrowHead(g2, x2, y2, x1, y1);
            }
        }
        
        // Draw destination labels in the margin (once per destination)
        int labelY = topMargin + 15;
        g2.setColor(new Color(100, 100, 100));
        g2.setFont(new Font("Arial", Font.ITALIC, 9));
        
        for (String destination : exitsByDestination.keySet()) {
            int count = exitsByDestination.get(destination).size();
            String label = "→ " + destination + " (" + count + ")";
            
            // Draw in bottom right corner
            FontMetrics fm = g2.getFontMetrics();
            int labelX = leftMargin + chartWidth - fm.stringWidth(label) - 10;
            g2.drawString(label, labelX, labelY);
            labelY += 12;
        }
    }
    
    public void setShowExitIndicators(boolean show) {
        this.showExitIndicators = show;
        repaint();
    }
    
    private void drawTooltip(Graphics2D g2, TokenPath path) {
        g2.setFont(tooltipFont);
        FontMetrics fm = g2.getFontMetrics();
        
        String[] lines = {
            "Token: " + path.tokenId,
            "Service: " + path.getServiceVersionLabel(),
            "Place: " + path.placeName,
            "Residence: " + path.residenceTime + "ms",
            "Buffer: " + path.entryBufferSize + " → " + path.exitBufferSize,
            String.format("Zoom: %.0f%%", zoomFactor * 100)
        };
        
        int maxWidth = 0;
        for (String line : lines) {
            maxWidth = Math.max(maxWidth, fm.stringWidth(line));
        }
        
        int tooltipWidth = maxWidth + 16;
        int tooltipHeight = lines.length * (fm.getHeight() + 2) + 10;
        
        // Adjust mouse position for zoom (since g2 is already scaled)
        int tx = (int)(mousePosition.x / zoomFactor) + 15;
        int ty = (int)(mousePosition.y / zoomFactor) + 15;
        
        // Keep on screen (using unscaled dimensions)
        int unscaledWidth = (int)(getWidth() / zoomFactor);
        int unscaledHeight = (int)(getHeight() / zoomFactor);
        if (tx + tooltipWidth > unscaledWidth) tx = (int)(mousePosition.x / zoomFactor) - tooltipWidth - 5;
        if (ty + tooltipHeight > unscaledHeight) ty = (int)(mousePosition.y / zoomFactor) - tooltipHeight - 5;
        
        // Background
        g2.setColor(new Color(255, 255, 220, 240));
        g2.fillRoundRect(tx, ty, tooltipWidth, tooltipHeight, 8, 8);
        g2.setColor(Color.DARK_GRAY);
        g2.drawRoundRect(tx, ty, tooltipWidth, tooltipHeight, 8, 8);
        
        // Text
        g2.setColor(Color.BLACK);
        int textY = ty + fm.getAscent() + 5;
        for (String line : lines) {
            g2.drawString(line, tx + 8, textY);
            textY += fm.getHeight() + 2;
        }
    }
    
    private void drawLegend(Graphics2D g2) {
        int legendX = leftMargin;
        int legendY = getHeight() - 35;
        
        g2.setFont(axisFont);
        FontMetrics fm = g2.getFontMetrics();
        
        int x = legendX;
        // Show legend for each service version present in the data
        for (Integer serviceVersion : presentServiceVersions) {
            Color c = serviceVersionColors.getOrDefault(serviceVersion, Color.GRAY);
            g2.setColor(c);
            g2.fillRect(x, legendY, 15, 12);
            g2.setColor(Color.BLACK);
            g2.drawRect(x, legendY, 15, 12);
            
            String label = String.format("v%03d", serviceVersion);  // e.g., 1 → v001
            g2.drawString(label, x + 18, legendY + 10);
            
            x += fm.stringWidth(label) + 35;
        }
        
        // Instructions
        g2.setColor(Color.GRAY);
        String hint = "Double-click bar to filter by workflow. Double-click again to show all.";
        g2.drawString(hint, getWidth() - fm.stringWidth(hint) - 20, legendY + 10);
    }
    
    private TokenPath getPathAt(int x, int y) {
        // Adjust mouse coordinates for zoom
        int adjustedX = (int)(x / zoomFactor);
        int adjustedY = (int)(y / zoomFactor);
        
        for (TokenPath path : tokenPaths) {
            if (selectedWorkflowBase > 0 && path.workflowBase != selectedWorkflowBase) {
                continue;
            }
            if (adjustedX >= path.x && adjustedX <= path.x + path.width &&
                adjustedY >= path.y && adjustedY <= path.y + barHeight) {
                return path;
            }
        }
        return null;
    }

    // =========================================================================
    // VIEW CONTROL METHODS
    // =========================================================================
    
    public void setShowFlowLines(boolean show) {
        this.showFlowLines = show;
        repaint();
    }
    
    public void setShowForkLines(boolean show) {
        this.showForkLines = show;
        repaint();
    }
    
    public void setColorByWorkflow(boolean byWorkflow) {
        this.colorByWorkflow = byWorkflow;
        repaint();
    }
    
    public void setShowTokenIds(boolean show) {
        this.showTokenIds = show;
        repaint();
    }
    
    public void setPlaceVisible(String placeName, boolean visible) {
        if (visible) {
            hiddenPlaces.remove(placeName);
        } else {
            hiddenPlaces.add(placeName);
        }
        rebuildPlaceLanes();
        calculatePreferredSize();
        revalidate();
        repaint();
    }
    
    public boolean isPlaceVisible(String placeName) {
        return !hiddenPlaces.contains(placeName);
    }
    
    public Set<String> getAllPlaces() {
        return new LinkedHashSet<>(placeOrder);
    }
    
    private void rebuildPlaceLanes() {
        placeLanes.clear();
        int laneIdx = 0;
        for (String place : placeOrder) {
            if (!hiddenPlaces.contains(place)) {
                placeLanes.put(place, laneIdx++);
            }
        }
    }
    
    public void setFontScaleFactor(float scale) {
        this.fontScaleFactor = scale;
        this.barHeight = Math.round(20 * scale);
        this.laneHeight = Math.round(60 * scale);
        updateFonts();
        calculatePreferredSize();
        revalidate();
        repaint();
    }
    
    public void setZoomFactor(double zoom) {
        this.zoomFactor = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, zoom));
        calculatePreferredSize();
        revalidate();
        repaint();
    }
    
    public double getZoomFactor() {
        return zoomFactor;
    }
    
    public void zoomIn() {
        setZoomFactor(zoomFactor + ZOOM_STEP);
    }
    
    public void zoomOut() {
        setZoomFactor(zoomFactor - ZOOM_STEP);
    }
    
    public void resetZoom() {
        setZoomFactor(1.0);
    }
    
    public void filterByWorkflow(long workflowBase) {
        this.selectedWorkflowBase = workflowBase;
        repaint();
    }
    
    public void showAllWorkflows() {
        this.selectedWorkflowBase = -1;
        repaint();
    }
    
    public void refresh() {
        loadDataFromDatabase();
        calculatePreferredSize();
        revalidate();
        repaint();
    }

    // =========================================================================
    // EXPORT METHODS
    // =========================================================================
    
    public void exportToPDF(String filename) {
        try {
            int exportWidth = Math.max(getWidth(), getPreferredSize().width);
            int exportHeight = Math.max(getHeight(), getPreferredSize().height);
            
            BufferedImage image = new BufferedImage(exportWidth, exportHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2 = image.createGraphics();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(Color.WHITE);
            g2.fillRect(0, 0, exportWidth, exportHeight);
            
            // Temporarily hide hover
            TokenPath savedHover = hoveredPath;
            hoveredPath = null;
            paint(g2);
            hoveredPath = savedHover;
            
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
                "PDF exported successfully to: " + filename,
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
            int baseWidth = Math.max(getWidth(), getPreferredSize().width);
            int baseHeight = Math.max(getHeight(), getPreferredSize().height);
            
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
            
            // Temporarily hide hover
            TokenPath savedHover = hoveredPath;
            hoveredPath = null;
            
            // Paint the component
            paint(g2);
            
            // Restore hover state
            hoveredPath = savedHover;
            
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
    
    public String generateSummaryReport() {
        StringBuilder report = new StringBuilder();
        report.append("TOKEN TIME IN SERVICE SUMMARY\n");
        report.append("Generated: ").append(new java.util.Date()).append("\n");
        report.append("=".repeat(80)).append("\n\n");
        
        // Group by workflow
        Map<Long, List<TokenPath>> byWorkflow = new HashMap<>();
        for (TokenPath path : tokenPaths) {
            byWorkflow.computeIfAbsent(path.workflowBase, k -> new ArrayList<>()).add(path);
        }
        
        for (Map.Entry<Long, List<TokenPath>> entry : byWorkflow.entrySet()) {
            long wfBase = entry.getKey();
            List<TokenPath> paths = entry.getValue();
            
            report.append("Workflow Base: ").append(wfBase).append("\n");
            report.append("-".repeat(40)).append("\n");
            
            // Group by place
            Map<String, List<TokenPath>> byPlace = new HashMap<>();
            for (TokenPath p : paths) {
                byPlace.computeIfAbsent(p.placeName, k -> new ArrayList<>()).add(p);
            }
            
            for (String place : placeOrder) {
                List<TokenPath> placePaths = byPlace.get(place);
                if (placePaths == null || placePaths.isEmpty()) continue;
                
                long totalResidence = 0;
                long minRes = Long.MAX_VALUE;
                long maxRes = Long.MIN_VALUE;
                
                for (TokenPath p : placePaths) {
                    totalResidence += p.residenceTime;
                    if (p.residenceTime < minRes) minRes = p.residenceTime;
                    if (p.residenceTime > maxRes) maxRes = p.residenceTime;
                }
                
                double avgRes = (double) totalResidence / placePaths.size();
                
                report.append(String.format("  %-15s: %d tokens, avg=%.1fms, min=%dms, max=%dms\n",
                    place, placePaths.size(), avgRes, minRes, maxRes));
            }
            report.append("\n");
        }
        
        // Fork statistics
        if (!genealogy.isEmpty()) {
            report.append("Fork Events:\n");
            report.append("-".repeat(40)).append("\n");
            
            Map<Integer, List<TokenGenealogy>> byParent = new HashMap<>();
            for (TokenGenealogy gen : genealogy) {
                byParent.computeIfAbsent(gen.parentTokenId, k -> new ArrayList<>()).add(gen);
            }
            
            for (Map.Entry<Integer, List<TokenGenealogy>> e : byParent.entrySet()) {
                report.append(String.format("  Token %d forked into %d children: ",
                    e.getKey(), e.getValue().size()));
                for (TokenGenealogy gen : e.getValue()) {
                    report.append(gen.childTokenId).append(" ");
                }
                report.append("\n");
            }
        }
        
        return report.toString();
    }

    // =========================================================================
    // MAIN & GUI
    // =========================================================================
    
    public static void createAndShowGUI(WorkflowSpatialView chart) {
        JFrame frame = new JFrame("Token Time in Service Visualization");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        
        // Menu bar
        JMenuBar menuBar = new JMenuBar();
        
        // File menu
        JMenu fileMenu = new JMenu("File");
        
        JMenuItem refreshItem = new JMenuItem("Refresh Data");
        refreshItem.addActionListener(e -> chart.refresh());
        fileMenu.add(refreshItem);
        
        JMenuItem summaryItem = new JMenuItem("View Summary Report");
        summaryItem.addActionListener(e -> {
            String summary = chart.generateSummaryReport();
            JTextArea textArea = new JTextArea(summary);
            textArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
            textArea.setEditable(false);
            JScrollPane scrollPane = new JScrollPane(textArea);
            scrollPane.setPreferredSize(new Dimension(600, 400));
            JOptionPane.showMessageDialog(frame, scrollPane, "Token Flow Summary", 
                                         JOptionPane.INFORMATION_MESSAGE);
        });
        fileMenu.add(summaryItem);
        fileMenu.addSeparator();
        
        JMenuItem exportPDFItem = new JMenuItem("Export to PDF...");
        exportPDFItem.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setSelectedFile(new File("token_flow_chart.pdf"));
            if (fc.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) {
                String filename = fc.getSelectedFile().getAbsolutePath();
                if (!filename.endsWith(".pdf")) filename += ".pdf";
                chart.exportToPDF(filename);
            }
        });
        fileMenu.add(exportPDFItem);
        
        // PNG Export submenu with DPI options
        JMenu exportPNGMenu = new JMenu("Export to PNG");
        
        int[] dpiOptions = {72, 150, 300, 600};
        String[] dpiLabels = {"72 DPI (Screen)", "150 DPI (Draft)", "300 DPI (Print Quality)", "600 DPI (High Quality)"};
        
        for (int i = 0; i < dpiOptions.length; i++) {
            final int dpi = dpiOptions[i];
            JMenuItem pngItem = new JMenuItem(dpiLabels[i] + "...");
            pngItem.addActionListener(ev -> {
                JFileChooser fc = new JFileChooser();
                fc.setSelectedFile(new File("token_flow_chart_" + dpi + "dpi.png"));
                fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("PNG files", "png"));
                
                if (fc.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) {
                    String filename = fc.getSelectedFile().getAbsolutePath();
                    if (!filename.endsWith(".png")) {
                        filename += ".png";
                    }
                    chart.exportToPNG(filename, dpi);
                }
            });
            exportPNGMenu.add(pngItem);
        }
        fileMenu.add(exportPNGMenu);
        fileMenu.addSeparator();
        
        JMenuItem exitItem = new JMenuItem("Exit");
        exitItem.addActionListener(e -> System.exit(0));
        fileMenu.add(exitItem);
        
        // View menu
        JMenu viewMenu = new JMenu("View");
        
        JCheckBoxMenuItem flowLinesItem = new JCheckBoxMenuItem("Show Flow Lines", true);
        flowLinesItem.addActionListener(e -> chart.setShowFlowLines(flowLinesItem.isSelected()));
        viewMenu.add(flowLinesItem);
        
        JCheckBoxMenuItem forkLinesItem = new JCheckBoxMenuItem("Show Fork Lines", true);
        forkLinesItem.addActionListener(e -> chart.setShowForkLines(forkLinesItem.isSelected()));
        viewMenu.add(forkLinesItem);
        
        JCheckBoxMenuItem tokenIdsItem = new JCheckBoxMenuItem("Show Token IDs", false);
        tokenIdsItem.addActionListener(e -> chart.setShowTokenIds(tokenIdsItem.isSelected()));
        viewMenu.add(tokenIdsItem);
        
        JCheckBoxMenuItem exitIndicatorsItem = new JCheckBoxMenuItem("Show Exit Indicators", true);
        exitIndicatorsItem.addActionListener(e -> chart.setShowExitIndicators(exitIndicatorsItem.isSelected()));
        viewMenu.add(exitIndicatorsItem);
        
        viewMenu.addSeparator();
        
        // Places visibility submenu
        JMenu placesMenu = new JMenu("Show/Hide Places");
        // Default: hide MonitorService and TERMINATE
        chart.setPlaceVisible("MonitorService", false);
        chart.setPlaceVisible("TERMINATE", false);
        
        for (String place : chart.getAllPlaces()) {
            boolean defaultVisible = !place.equals("MonitorService") && !place.equals("TERMINATE");
            JCheckBoxMenuItem placeItem = new JCheckBoxMenuItem(place, defaultVisible);
            placeItem.addActionListener(e -> chart.setPlaceVisible(place, placeItem.isSelected()));
            placesMenu.add(placeItem);
        }
        viewMenu.add(placesMenu);
        
        viewMenu.addSeparator();
        
        JMenuItem showAllItem = new JMenuItem("Show All Workflows");
        showAllItem.addActionListener(e -> chart.showAllWorkflows());
        viewMenu.add(showAllItem);
        
        viewMenu.addSeparator();
        
        // Font scale submenu
        JMenu fontMenu = new JMenu("Font Size");
        ButtonGroup fontGroup = new ButtonGroup();
        float[] scales = {0.75f, 1.0f, 1.25f, 1.5f, 2.0f};
        String[] scaleLabels = {"75%", "100%", "125%", "150%", "200%"};
        for (int i = 0; i < scales.length; i++) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(scaleLabels[i]);
            final float scale = scales[i];
            if (scale == 1.0f) item.setSelected(true);
            item.addActionListener(e -> chart.setFontScaleFactor(scale));
            fontGroup.add(item);
            fontMenu.add(item);
        }
        viewMenu.add(fontMenu);
        
        viewMenu.addSeparator();
        
        // Zoom submenu
        JMenu zoomMenu = new JMenu("Zoom");
        
        JMenuItem zoomInItem = new JMenuItem("Zoom In (Ctrl++)");
        zoomInItem.addActionListener(e -> chart.zoomIn());
        zoomMenu.add(zoomInItem);
        
        JMenuItem zoomOutItem = new JMenuItem("Zoom Out (Ctrl+-)");
        zoomOutItem.addActionListener(e -> chart.zoomOut());
        zoomMenu.add(zoomOutItem);
        
        JMenuItem zoomResetItem = new JMenuItem("Reset Zoom (Ctrl+0)");
        zoomResetItem.addActionListener(e -> chart.resetZoom());
        zoomMenu.add(zoomResetItem);
        
        zoomMenu.addSeparator();
        
        // Preset zoom levels
        double[] zoomLevels = {0.25, 0.5, 0.75, 1.0, 1.5, 2.0, 3.0, 4.0};
        String[] zoomLabels = {"25%", "50%", "75%", "100%", "150%", "200%", "300%", "400%"};
        for (int i = 0; i < zoomLevels.length; i++) {
            JMenuItem item = new JMenuItem(zoomLabels[i]);
            final double zoom = zoomLevels[i];
            item.addActionListener(e -> chart.setZoomFactor(zoom));
            zoomMenu.add(item);
        }
        viewMenu.add(zoomMenu);
        
        menuBar.add(fileMenu);
        menuBar.add(viewMenu);
        frame.setJMenuBar(menuBar);
        
        // Scroll pane
        JScrollPane scrollPane = new JScrollPane(chart);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        
        frame.add(scrollPane);
        frame.pack();
        frame.setSize(Math.min(1200, frame.getWidth()), Math.min(600, frame.getHeight()));
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }
    
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            WorkflowSpatialView chart = new WorkflowSpatialView();
            createAndShowGUI(chart);
        });
    }
}