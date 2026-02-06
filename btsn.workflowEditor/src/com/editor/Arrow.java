package com.editor;

import java.awt.*;
import java.awt.geom.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents an arrow (arc) connecting two Petri net elements
 */
public class Arrow {
    /**
     * Connection type for arrows
     */
    public enum ConnectionType {
        LAN,      // Local/OS connection (solid line)
        NETWORK   // Network connection (dashed line)
    }
    
    private ProcessElement source;
    private ProcessElement target;
    private String label;
    private String guardCondition;
    private String decisionValue;
    private String endpoint;  // Specifies which operation to invoke for multi-operation services
    private List<Point> waypoints;  // Control points for bending
    private Point newlyCreatedWaypoint;  // Track the just-created waypoint (shown in blue)
    
    // Connection type and availability
    private ConnectionType connectionType = ConnectionType.LAN;  // Default to LAN
    private double availability = 1.0;  // 0.0 to 1.0, default 100%
    
    // Label offset - displacement from the arrow's midpoint for the label
    // When null or (0,0), label is drawn directly on the arrow
    private Point labelOffset;
    private static final int LABEL_HIT_MARGIN = 4;  // Margin for clicking on label
    
    // Store last computed label anchor point (on the arrow line) for hit testing
    private transient Point lastLabelAnchor;
    private transient Rectangle lastLabelBounds;
    
    public Arrow(ProcessElement source, ProcessElement target) {
        this.source = source;
        this.target = target;
        this.label = "";
        this.guardCondition = "";
        this.decisionValue = "";
        this.endpoint = "";
        this.waypoints = new ArrayList<>();
        this.newlyCreatedWaypoint = null;
        this.labelOffset = null;  // No offset by default - label on arrow
        this.lastLabelAnchor = null;
        this.lastLabelBounds = null;
    }
    
    public void draw(Graphics2D g2) {
        draw(g2, false);
    }
    
    public void draw(Graphics2D g2, boolean selected) {
        Point sourceCenter = source.getCenter();
        Point targetCenter = target.getCenter();
        
        // Build complete path with waypoints
        List<Point> controlPoints = new ArrayList<>();
        
        // Calculate start edge point based on direction to first waypoint or target
        Point startDirection = waypoints.isEmpty() ? targetCenter : waypoints.get(0);
        controlPoints.add(getEdgePoint(source, sourceCenter, startDirection));
        
        controlPoints.addAll(waypoints);
        
        // Calculate end edge point based on direction from last waypoint or source
        Point endDirection = waypoints.isEmpty() ? sourceCenter : waypoints.get(waypoints.size() - 1);
        controlPoints.add(getEdgePoint(target, targetCenter, endDirection));
        
        // Draw selection halo first (behind the arrow) if selected
        if (selected) {
            drawSelectionHalo(g2, controlPoints);
        }
        
        // Determine line style based on connection type
        Color lineColor = Color.BLACK;
        Stroke lineStroke;
        
        if (connectionType == ConnectionType.NETWORK) {
            // Network connection - dashed line with availability-based pattern
            float[] dashPattern = getDashPatternForAvailability(availability);
            lineColor = getColorForAvailability(availability);
            lineStroke = new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 
                                         0, dashPattern, 0);
        } else {
            // LAN connection - solid line
            lineStroke = new BasicStroke(1, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
        }
        
        // Draw path with sharp elbows (slightly rounded corners) through control points
        g2.setColor(lineColor);
        g2.setStroke(lineStroke);
        
        Point labelPosition = null;
        if (controlPoints.size() == 2) {
            // Simple straight line
            Point start = controlPoints.get(0);
            Point end = controlPoints.get(1);
            g2.drawLine(start.x, start.y, end.x, end.y);
            labelPosition = new Point((start.x + end.x) / 2, (start.y + end.y) / 2);
        } else {
            // Draw lines with rounded corners at waypoints
            labelPosition = drawSharpElbowPath(g2, controlPoints);
        }
        
        // Draw arrowhead at the end (solid, same color as line)
        g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Point lastSegStart = controlPoints.get(controlPoints.size() - 2);
        Point lastSegEnd = controlPoints.get(controlPoints.size() - 1);
        drawArrowhead(g2, lastSegStart.x, lastSegStart.y, lastSegEnd.x, lastSegEnd.y);
        
        // Draw oblique line with operation count if target is a Place with multiple operations
        if (target.getType() == ProcessElement.Type.PLACE && target.getOperations().size() > 1) {
            drawOperationCountMarker(g2, lastSegStart.x, lastSegStart.y, lastSegEnd.x, lastSegEnd.y, 
                                     target.getOperations().size());
        }
        
        // Draw waypoint handles only if selected
        if (selected) {
            for (Point wp : waypoints) {
                if (wp == newlyCreatedWaypoint) {
                    // Newly created waypoint - blue filled circle
                    g2.setColor(new Color(100, 100, 255));
                    g2.fillOval(wp.x - 4, wp.y - 4, 8, 8);
                } else {
                    // Existing waypoints - white box with black border
                    g2.setColor(Color.WHITE);
                    g2.fillRect(wp.x - 4, wp.y - 4, 8, 8);
                    g2.setColor(Color.BLACK);
                    g2.drawRect(wp.x - 4, wp.y - 4, 8, 8);
                }
            }
            
            // Draw label drag handle if label has an offset
            if (label != null && !label.isEmpty() && labelOffset != null && 
                (labelOffset.x != 0 || labelOffset.y != 0) && labelPosition != null) {
                Point labelDrawPos = new Point(labelPosition.x + labelOffset.x, labelPosition.y + labelOffset.y);
                // Draw small diamond at label position to indicate it's draggable
                g2.setColor(new Color(100, 150, 255));
                int[] xPoints = {labelDrawPos.x, labelDrawPos.x + 4, labelDrawPos.x, labelDrawPos.x - 4};
                int[] yPoints = {labelDrawPos.y - 4, labelDrawPos.y, labelDrawPos.y + 4, labelDrawPos.y};
                g2.fillPolygon(xPoints, yPoints, 4);
            }
        }
        
        // Draw label if present (with offset support)
        if (label != null && !label.isEmpty() && labelPosition != null) {
            // Store the anchor point (on the arrow line)
            lastLabelAnchor = new Point(labelPosition.x, labelPosition.y);
            
            g2.setFont(new Font("SansSerif", Font.PLAIN, 10));
            FontMetrics fm = g2.getFontMetrics();
            int textWidth = fm.stringWidth(label);
            int textHeight = fm.getHeight();
            
            // Calculate actual label draw position (anchor + offset)
            int labelX, labelY;
            if (labelOffset != null && (labelOffset.x != 0 || labelOffset.y != 0)) {
                labelX = labelPosition.x + labelOffset.x;
                labelY = labelPosition.y + labelOffset.y;
                
                // Draw leader line from anchor to label
                g2.setColor(new Color(128, 128, 128));  // Gray leader line
                g2.setStroke(new BasicStroke(1, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 
                                             0, new float[]{3, 3}, 0));  // Dashed line
                g2.drawLine(labelPosition.x, labelPosition.y, labelX, labelY);
                g2.setStroke(new BasicStroke(1));
                
                // Draw small circle at anchor point
                g2.setColor(new Color(128, 128, 128));
                g2.fillOval(labelPosition.x - 2, labelPosition.y - 2, 4, 4);
            } else {
                labelX = labelPosition.x;
                labelY = labelPosition.y;
            }
            
            // Draw label background and text
            int bgX = labelX - textWidth/2 - 2;
            int bgY = labelY - textHeight/2;
            g2.setColor(Color.WHITE);
            g2.fillRect(bgX, bgY, textWidth + 4, textHeight);
            g2.setColor(Color.BLACK);
            g2.drawString(label, labelX - textWidth/2, labelY + fm.getAscent()/2);
            
            // Store label bounds for hit testing
            lastLabelBounds = new Rectangle(bgX - LABEL_HIT_MARGIN, bgY - LABEL_HIT_MARGIN, 
                                           textWidth + 4 + 2*LABEL_HIT_MARGIN, 
                                           textHeight + 2*LABEL_HIT_MARGIN);
        } else {
            lastLabelBounds = null;
        }
        
        g2.setStroke(new BasicStroke(1));
    }
    
    /**
     * Draw a selection halo around the arrow path
     */
    private void drawSelectionHalo(Graphics2D g2, List<Point> controlPoints) {
        g2.setColor(new Color(100, 150, 255, 80)); // Light blue, transparent
        g2.setStroke(new BasicStroke(8, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        
        if (controlPoints.size() == 2) {
            // Simple straight line
            Point start = controlPoints.get(0);
            Point end = controlPoints.get(1);
            g2.drawLine(start.x, start.y, end.x, end.y);
        } else {
            // Draw halo following the sharp elbow path
            drawSharpElbowPathHalo(g2, controlPoints);
        }
        
        g2.setStroke(new BasicStroke(1));
    }
    
    /**
     * Draw halo path with sharp elbows matching the arrow path
     */
    private void drawSharpElbowPathHalo(Graphics2D g2, List<Point> points) {
        int cornerRadius = 8;
        
        // Work with a copy to avoid modifying original
        List<Point> pts = new ArrayList<>();
        for (Point p : points) {
            pts.add(new Point(p.x, p.y));
        }
        
        Point lastDrawnPoint = pts.get(0);
        
        for (int i = 1; i < pts.size() - 1; i++) {
            Point prev = lastDrawnPoint;
            Point current = pts.get(i);
            Point next = pts.get(i + 1);
            
            double distToCurrent = Math.sqrt(Math.pow(current.x - prev.x, 2) + Math.pow(current.y - prev.y, 2));
            double distToNext = Math.sqrt(Math.pow(next.x - current.x, 2) + Math.pow(next.y - current.y, 2));
            
            int effectiveRadius = (int) Math.min(cornerRadius, Math.min(distToCurrent / 2, distToNext / 2));
            
            if (effectiveRadius < 2 || distToCurrent < 4) {
                g2.drawLine(prev.x, prev.y, current.x, current.y);
                lastDrawnPoint = current;
            } else {
                double ratio1 = effectiveRadius / distToCurrent;
                double ratio2 = effectiveRadius / distToNext;
                
                int beforeCornerX = (int) (current.x - ratio1 * (current.x - prev.x));
                int beforeCornerY = (int) (current.y - ratio1 * (current.y - prev.y));
                int afterCornerX = (int) (current.x + ratio2 * (next.x - current.x));
                int afterCornerY = (int) (current.y + ratio2 * (next.y - current.y));
                
                g2.drawLine(prev.x, prev.y, beforeCornerX, beforeCornerY);
                drawRoundedCornerHalo(g2, beforeCornerX, beforeCornerY, current.x, current.y, afterCornerX, afterCornerY);
                
                lastDrawnPoint = new Point(afterCornerX, afterCornerY);
            }
        }
        
        // Draw final segment
        Point lastPoint = pts.get(pts.size() - 1);
        g2.drawLine(lastDrawnPoint.x, lastDrawnPoint.y, lastPoint.x, lastPoint.y);
    }
    
    /**
     * Draw rounded corner for halo
     */
    private void drawRoundedCornerHalo(Graphics2D g2, int x1, int y1, int cx, int cy, int x2, int y2) {
        int steps = 8;
        int prevX = x1, prevY = y1;
        
        for (int t = 1; t <= steps; t++) {
            float u = t / (float) steps;
            float oneMinusU = 1 - u;
            
            int px = (int) (oneMinusU * oneMinusU * x1 + 2 * oneMinusU * u * cx + u * u * x2);
            int py = (int) (oneMinusU * oneMinusU * y1 + 2 * oneMinusU * u * cy + u * u * y2);
            
            g2.drawLine(prevX, prevY, px, py);
            prevX = px;
            prevY = py;
        }
    }
    
    /**
     * Draw path with sharp elbows - straight lines with small rounded corners at waypoints
     */
    private Point drawSharpElbowPath(Graphics2D g2, List<Point> points) {
        List<Point> allPoints = new ArrayList<>();
        int cornerRadius = 8; // Small radius for slight rounding
        
        // Work with a copy to avoid modifying original
        List<Point> pts = new ArrayList<>();
        for (Point p : points) {
            pts.add(new Point(p.x, p.y));
        }
        
        Point lastDrawnPoint = pts.get(0);
        allPoints.add(lastDrawnPoint);
        
        for (int i = 1; i < pts.size() - 1; i++) {
            Point prev = lastDrawnPoint;
            Point current = pts.get(i);
            Point next = pts.get(i + 1);
            
            // Calculate distances to determine corner radius
            double distToCurrent = Math.sqrt(Math.pow(current.x - prev.x, 2) + Math.pow(current.y - prev.y, 2));
            double distToNext = Math.sqrt(Math.pow(next.x - current.x, 2) + Math.pow(next.y - current.y, 2));
            
            // Limit corner radius to half the shortest segment
            int effectiveRadius = (int) Math.min(cornerRadius, Math.min(distToCurrent / 2, distToNext / 2));
            
            if (effectiveRadius < 2 || distToCurrent < 4) {
                // Segments too short - just draw straight lines
                g2.drawLine(prev.x, prev.y, current.x, current.y);
                allPoints.add(current);
                lastDrawnPoint = current;
            } else {
                // Calculate points just before and after the corner
                double ratio1 = effectiveRadius / distToCurrent;
                double ratio2 = effectiveRadius / distToNext;
                
                int beforeCornerX = (int) (current.x - ratio1 * (current.x - prev.x));
                int beforeCornerY = (int) (current.y - ratio1 * (current.y - prev.y));
                int afterCornerX = (int) (current.x + ratio2 * (next.x - current.x));
                int afterCornerY = (int) (current.y + ratio2 * (next.y - current.y));
                
                // Draw line to just before corner
                g2.drawLine(prev.x, prev.y, beforeCornerX, beforeCornerY);
                allPoints.add(new Point(beforeCornerX, beforeCornerY));
                
                // Draw rounded corner using quadratic curve
                drawRoundedCorner(g2, beforeCornerX, beforeCornerY, current.x, current.y, afterCornerX, afterCornerY, allPoints);
                
                lastDrawnPoint = new Point(afterCornerX, afterCornerY);
            }
        }
        
        // Draw final segment to last point
        Point lastPoint = pts.get(pts.size() - 1);
        g2.drawLine(lastDrawnPoint.x, lastDrawnPoint.y, lastPoint.x, lastPoint.y);
        allPoints.add(lastPoint);
        
        // Return midpoint for label placement
        if (allPoints.isEmpty()) {
            return points.get(points.size() / 2);
        }
        return allPoints.get(allPoints.size() / 2);
    }
    
    /**
     * Draw a small rounded corner using a quadratic Bezier curve
     */
    private void drawRoundedCorner(Graphics2D g2, int x1, int y1, int cx, int cy, int x2, int y2, List<Point> allPoints) {
        // Quadratic Bezier from (x1,y1) through control point (cx,cy) to (x2,y2)
        int steps = 8;
        int prevX = x1, prevY = y1;
        
        for (int t = 1; t <= steps; t++) {
            float u = t / (float) steps;
            float oneMinusU = 1 - u;
            
            // Quadratic Bezier formula
            int px = (int) (oneMinusU * oneMinusU * x1 + 2 * oneMinusU * u * cx + u * u * x2);
            int py = (int) (oneMinusU * oneMinusU * y1 + 2 * oneMinusU * u * cy + u * u * y2);
            
            g2.drawLine(prevX, prevY, px, py);
            allPoints.add(new Point(px, py));
            prevX = px;
            prevY = py;
        }
    }
    
    private Point drawSmoothCurveWithMidpoint(Graphics2D g2, List<Point> points) {
        // Draw Catmull-Rom spline for smooth curves and return midpoint
        List<Point> allPoints = new ArrayList<>();
        
        for (int i = 0; i < points.size() - 1; i++) {
            Point p0 = (i == 0) ? points.get(0) : points.get(i - 1);
            Point p1 = points.get(i);
            Point p2 = points.get(i + 1);
            Point p3 = (i + 2 < points.size()) ? points.get(i + 2) : points.get(i + 1);
            
            // Draw curve segment with many small line segments
            int steps = 20;
            Point prev = p1;
            for (int t = 1; t <= steps; t++) {
                float u = t / (float) steps;
                Point current = catmullRom(p0, p1, p2, p3, u);
                g2.drawLine(prev.x, prev.y, current.x, current.y);
                allPoints.add(current);
                prev = current;
            }
        }
        
        // Return midpoint
        if (allPoints.isEmpty()) {
            return points.get(points.size() / 2);
        }
        return allPoints.get(allPoints.size() / 2);
    }
    
    private void drawSmoothCurve(Graphics2D g2, List<Point> points) {
        // Draw Catmull-Rom spline for smooth curves
        for (int i = 0; i < points.size() - 1; i++) {
            Point p0 = (i == 0) ? points.get(0) : points.get(i - 1);
            Point p1 = points.get(i);
            Point p2 = points.get(i + 1);
            Point p3 = (i + 2 < points.size()) ? points.get(i + 2) : points.get(i + 1);
            
            // Draw curve segment with many small line segments
            int steps = 20;
            Point prev = p1;
            for (int t = 1; t <= steps; t++) {
                float u = t / (float) steps;
                Point current = catmullRom(p0, p1, p2, p3, u);
                g2.drawLine(prev.x, prev.y, current.x, current.y);
                prev = current;
            }
        }
    }
    
    private Point catmullRom(Point p0, Point p1, Point p2, Point p3, float t) {
        // Catmull-Rom spline interpolation
        float t2 = t * t;
        float t3 = t2 * t;
        
        float x = 0.5f * ((2 * p1.x) +
                         (-p0.x + p2.x) * t +
                         (2 * p0.x - 5 * p1.x + 4 * p2.x - p3.x) * t2 +
                         (-p0.x + 3 * p1.x - 3 * p2.x + p3.x) * t3);
        
        float y = 0.5f * ((2 * p1.y) +
                         (-p0.y + p2.y) * t +
                         (2 * p0.y - 5 * p1.y + 4 * p2.y - p3.y) * t2 +
                         (-p0.y + 3 * p1.y - 3 * p2.y + p3.y) * t3);
        
        return new Point((int) x, (int) y);
    }
    
    /**
     * Calculate the point where the arrow should start/end at the edge of a shape
     */
    private Point getEdgePoint(ProcessElement element, Point center, Point otherCenter) {
        double angle = Math.atan2(otherCenter.y - center.y, otherCenter.x - center.x);
        
        if (element.getType() == ProcessElement.Type.PLACE) {
            // Circle - use radius
            int radius = element.getWidth() / 2;
            int edgeX = center.x + (int)(radius * Math.cos(angle));
            int edgeY = center.y + (int)(radius * Math.sin(angle));
            return new Point(edgeX, edgeY);
        } else {
            // Rotated Rectangle - find intersection with edges
            double rotation = Math.toRadians(element.getRotationAngle());
            
            // Transform the direction to local coordinates
            double localAngle = angle - rotation;
            
            int halfWidth = element.getWidth() / 2;
            int halfHeight = element.getHeight() / 2;
            
            // Calculate direction in local space
            double dx = Math.cos(localAngle);
            double dy = Math.sin(localAngle);
            
            // Find intersection with rectangle edges in local space
            double t;
            if (Math.abs(dx) < 0.001) {
                // Nearly vertical in local space
                t = halfHeight / Math.abs(dy);
            } else if (Math.abs(dy) < 0.001) {
                // Nearly horizontal in local space
                t = halfWidth / Math.abs(dx);
            } else {
                // Find which edge we'll hit first
                double tX = halfWidth / Math.abs(dx);
                double tY = halfHeight / Math.abs(dy);
                t = Math.min(tX, tY);
            }
            
            // Local coordinates of edge point
            double localX = t * dx;
            double localY = t * dy;
            
            // Transform back to world coordinates
            int edgeX = center.x + (int)(localX * Math.cos(rotation) - localY * Math.sin(rotation));
            int edgeY = center.y + (int)(localX * Math.sin(rotation) + localY * Math.cos(rotation));
            
            return new Point(edgeX, edgeY);
        }
    }
    
    private void drawArrowhead(Graphics2D g2, int x1, int y1, int x2, int y2) {
        double angle = Math.atan2(y2 - y1, x2 - x1);
        
        int arrowSize = 12;
        double arrowAngle = Math.PI / 6; // 30 degrees
        
        g2.setColor(Color.BLACK);
        g2.setStroke(new BasicStroke(1));
        
        // Draw first line of arrowhead
        int x1a = (int) (x2 - arrowSize * Math.cos(angle - arrowAngle));
        int y1a = (int) (y2 - arrowSize * Math.sin(angle - arrowAngle));
        g2.drawLine(x2, y2, x1a, y1a);
        
        // Draw second line of arrowhead
        int x2a = (int) (x2 - arrowSize * Math.cos(angle + arrowAngle));
        int y2a = (int) (y2 - arrowSize * Math.sin(angle + arrowAngle));
        g2.drawLine(x2, y2, x2a, y2a);
        
        g2.setStroke(new BasicStroke(1));
    }
    
    /**
     * Draw an oblique line with a number near the arrowhead to indicate operation count
     */
    private void drawOperationCountMarker(Graphics2D g2, int x1, int y1, int x2, int y2, int count) {
        // Calculate the angle of the arrow
        double angle = Math.atan2(y2 - y1, x2 - x1);
        
        // Position the oblique line back from the arrowhead (about 20 pixels back)
        int distanceFromHead = 20;
        int markerX = (int) (x2 - distanceFromHead * Math.cos(angle));
        int markerY = (int) (y2 - distanceFromHead * Math.sin(angle));
        
        // Draw oblique line perpendicular to the arrow (at 60 degrees)
        int lineLength = 12;
        double obliqueAngle = angle + Math.PI / 3; // 60 degrees offset
        
        int x1Line = (int) (markerX - (lineLength / 2) * Math.cos(obliqueAngle));
        int y1Line = (int) (markerY - (lineLength / 2) * Math.sin(obliqueAngle));
        int x2Line = (int) (markerX + (lineLength / 2) * Math.cos(obliqueAngle));
        int y2Line = (int) (markerY + (lineLength / 2) * Math.sin(obliqueAngle));
        
        g2.setColor(Color.BLACK);
        g2.setStroke(new BasicStroke(2));
        g2.drawLine(x1Line, y1Line, x2Line, y2Line);
        
        // Draw the count number next to the oblique line
        g2.setFont(new Font("SansSerif", Font.BOLD, 11));
        FontMetrics fm = g2.getFontMetrics();
        String countStr = String.valueOf(count);
        int textWidth = fm.stringWidth(countStr);
        
        // Position text slightly offset from the oblique line
        int textOffsetDist = 8;
        int textX = (int) (markerX + textOffsetDist * Math.cos(obliqueAngle + Math.PI / 2));
        int textY = (int) (markerY + textOffsetDist * Math.sin(obliqueAngle + Math.PI / 2));
        
        // Draw white background for number
        g2.setColor(Color.WHITE);
        g2.fillRect(textX - 2, textY - fm.getAscent(), textWidth + 4, fm.getHeight());
        
        // Draw the number
        g2.setColor(Color.BLACK);
        g2.drawString(countStr, textX, textY);
        
        g2.setStroke(new BasicStroke(1));
    }
    
    public ProcessElement getSource() { return source; }
    public ProcessElement getTarget() { return target; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getGuardCondition() { return guardCondition; }
    public void setGuardCondition(String guardCondition) { this.guardCondition = guardCondition; }
    public String getDecisionValue() { return decisionValue; }
    public void setDecisionValue(String decisionValue) { this.decisionValue = decisionValue; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    
    // Label offset methods
    public Point getLabelOffset() { return labelOffset; }
    public void setLabelOffset(Point offset) { this.labelOffset = offset; }
    public void setLabelOffset(int dx, int dy) { 
        this.labelOffset = new Point(dx, dy); 
    }
    
    /**
     * Clear the label offset, returning the label to its default position on the arrow
     */
    public void clearLabelOffset() { 
        this.labelOffset = null; 
    }
    
    /**
     * Check if the given point is over the label
     * @return true if the point is within the label bounds
     */
    public boolean isPointOnLabel(int x, int y) {
        return lastLabelBounds != null && lastLabelBounds.contains(x, y);
    }
    
    /**
     * Get the current label anchor point (the point on the arrow line where the leader line attaches)
     */
    public Point getLabelAnchor() {
        return lastLabelAnchor;
    }
    
    /**
     * Move the label offset by the given delta
     */
    public void moveLabelOffset(int dx, int dy) {
        if (labelOffset == null) {
            labelOffset = new Point(dx, dy);
        } else {
            labelOffset.x += dx;
            labelOffset.y += dy;
        }
    }
    
    /**
     * Check if the label has a non-zero offset
     */
    public boolean hasLabelOffset() {
        return labelOffset != null && (labelOffset.x != 0 || labelOffset.y != 0);
    }
    
    public List<Point> getWaypoints() { return waypoints; }
    
    public void addWaypoint(int x, int y) {
        // Insert waypoint at the best position in the path
        Point p1 = source.getCenter();
        Point p2 = target.getCenter();
        Point newWaypoint = new Point(x, y);
        
        if (waypoints.isEmpty()) {
            waypoints.add(newWaypoint);
        } else {
            // Find best segment to insert into
            double minDist = Double.MAX_VALUE;
            int bestIndex = 0;
            
            Point prev = p1;
            for (int i = 0; i < waypoints.size(); i++) {
                double dist = distanceToSegment(x, y, prev.x, prev.y, waypoints.get(i).x, waypoints.get(i).y);
                if (dist < minDist) {
                    minDist = dist;
                    bestIndex = i;
                }
                prev = waypoints.get(i);
            }
            
            double distToLast = distanceToSegment(x, y, prev.x, prev.y, p2.x, p2.y);
            if (distToLast < minDist) {
                waypoints.add(newWaypoint);
            } else {
                waypoints.add(bestIndex, newWaypoint);
            }
        }
        
        // Mark as newly created (will show blue until moved or reselected)
        newlyCreatedWaypoint = newWaypoint;
    }
    
    /**
     * Clear the newly created waypoint marker (call when waypoint is moved or arrow reselected)
     */
    public void clearNewlyCreatedWaypoint() {
        newlyCreatedWaypoint = null;
    }
    
    public Point findWaypointAt(int x, int y) {
        for (Point wp : waypoints) {
            if (Math.abs(wp.x - x) <= 6 && Math.abs(wp.y - y) <= 6) {
                return wp;
            }
        }
        return null;
    }
    
    public void removeWaypoint(Point wp) {
        waypoints.remove(wp);
    }
    
    private double distanceToSegment(int px, int py, int x1, int y1, int x2, int y2) {
        double A = px - x1;
        double B = py - y1;
        double C = x2 - x1;
        double D = y2 - y1;
        
        double dot = A * C + B * D;
        double lenSq = C * C + D * D;
        double param = -1;
        
        if (lenSq != 0) param = dot / lenSq;
        
        double xx, yy;
        
        if (param < 0) {
            xx = x1;
            yy = y1;
        } else if (param > 1) {
            xx = x2;
            yy = y2;
        } else {
            xx = x1 + param * C;
            yy = y1 + param * D;
        }
        
        double dx = px - xx;
        double dy = py - yy;
        return Math.sqrt(dx * dx + dy * dy);
    }
    
    // ============ Connection Type and Availability ============
    
    /**
     * Get dash pattern based on availability level.
     * Higher availability = longer dashes, shorter gaps (more solid)
     * Lower availability = shorter dashes, longer gaps (more broken)
     */
    private float[] getDashPatternForAvailability(double availability) {
        if (availability >= 0.95) {
            return new float[]{8, 4};      // 95-100%: long dash, short gap
        } else if (availability >= 0.80) {
            return new float[]{6, 6};      // 80-95%: equal dash/gap
        } else if (availability >= 0.60) {
            return new float[]{4, 8};      // 60-80%: short dash, long gap
        } else {
            return new float[]{2, 10};     // <60%: dotted
        }
    }
    
    /**
     * Get line color based on availability level.
     * Blue = healthy, Orange = degraded, Red = critical
     */
    private Color getColorForAvailability(double availability) {
        if (availability >= 0.80) {
            return new Color(0, 102, 204);   // Blue - healthy
        } else if (availability >= 0.60) {
            return new Color(204, 102, 0);   // Orange - degraded
        } else {
            return new Color(204, 0, 0);     // Red - critical
        }
    }
    
    // Connection type getters/setters
    public ConnectionType getConnectionType() { return connectionType; }
    public void setConnectionType(ConnectionType type) { this.connectionType = type; }
    
    /**
     * Check if this is a network connection
     */
    public boolean isNetworkConnection() { 
        return connectionType == ConnectionType.NETWORK; 
    }
    
    /**
     * Set as network connection
     */
    public void setNetworkConnection(boolean isNetwork) {
        this.connectionType = isNetwork ? ConnectionType.NETWORK : ConnectionType.LAN;
    }
    
    // Availability getters/setters
    public double getAvailability() { return availability; }
    public void setAvailability(double availability) { 
        this.availability = Math.max(0.0, Math.min(1.0, availability)); 
    }
    
    /**
     * Get availability as percentage string (e.g., "99.5%")
     */
    public String getAvailabilityPercent() {
        return String.format("%.1f%%", availability * 100);
    }
    
    /**
     * Set availability from percentage (0-100)
     */
    public void setAvailabilityPercent(double percent) {
        setAvailability(percent / 100.0);
    }
    
    /**
     * Auto-detect if this should be a network connection based on source/target types.
     * Network = both source and target are Transitions
     */
    public boolean shouldBeNetworkConnection() {
        return source.getType() == ProcessElement.Type.TRANSITION && 
               target.getType() == ProcessElement.Type.TRANSITION;
    }
}