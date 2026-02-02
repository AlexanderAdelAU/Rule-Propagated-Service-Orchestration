package com.petrinet.editor;

import java.awt.*;
import java.awt.geom.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents an arrow (arc) connecting two Petri net elements
 */
public class Arrow {
    private PetriNetElement source;
    private PetriNetElement target;
    private String label;
    private String condition;
    private String decisionValue;
    private List<Point> waypoints;  // Control points for bending
    
    public Arrow(PetriNetElement source, PetriNetElement target) {
        this.source = source;
        this.target = target;
        this.label = "";
        this.condition = "";
        this.decisionValue = "";
        this.waypoints = new ArrayList<>();
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
        
        // Draw smooth curve through control points
        g2.setColor(Color.BLACK);
        g2.setStroke(new BasicStroke(1, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        
        Point labelPosition = null;
        if (controlPoints.size() == 2) {
            // Simple straight line
            Point start = controlPoints.get(0);
            Point end = controlPoints.get(1);
            g2.drawLine(start.x, start.y, end.x, end.y);
            labelPosition = new Point((start.x + end.x) / 2, (start.y + end.y) / 2);
        } else {
            // Draw smooth Catmull-Rom spline through points and track midpoint
            labelPosition = drawSmoothCurveWithMidpoint(g2, controlPoints);
        }
        
        // Draw arrowhead at the end
        Point lastSegStart = controlPoints.get(controlPoints.size() - 2);
        Point lastSegEnd = controlPoints.get(controlPoints.size() - 1);
        drawArrowhead(g2, lastSegStart.x, lastSegStart.y, lastSegEnd.x, lastSegEnd.y);
        
        // Draw waypoint handles only if selected
        if (selected) {
            g2.setColor(new Color(100, 100, 255));
            for (Point wp : waypoints) {
                g2.fillOval(wp.x - 4, wp.y - 4, 8, 8);
            }
        }
        
        // Draw label if present (at actual center of curve)
        if (label != null && !label.isEmpty() && labelPosition != null) {
            g2.setColor(Color.BLACK);
            g2.setFont(new Font("SansSerif", Font.PLAIN, 10));
            FontMetrics fm = g2.getFontMetrics();
            int textWidth = fm.stringWidth(label);
            g2.setColor(Color.WHITE);
            g2.fillRect(labelPosition.x - textWidth/2 - 2, labelPosition.y - fm.getHeight()/2, textWidth + 4, fm.getHeight());
            g2.setColor(Color.BLACK);
            g2.drawString(label, labelPosition.x - textWidth/2, labelPosition.y + fm.getAscent()/2);
        }
        
        g2.setStroke(new BasicStroke(1));
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
    private Point getEdgePoint(PetriNetElement element, Point center, Point otherCenter) {
        double angle = Math.atan2(otherCenter.y - center.y, otherCenter.x - center.x);
        
        if (element.getType() == PetriNetElement.Type.PLACE) {
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
    
    public PetriNetElement getSource() { return source; }
    public PetriNetElement getTarget() { return target; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getCondition() { return condition; }
    public void setCondition(String condition) { this.condition = condition; }
    public String getDecisionValue() { return decisionValue; }
    public void setDecisionValue(String decisionValue) { this.decisionValue = decisionValue; }
    
    public List<Point> getWaypoints() { return waypoints; }
    
    public void addWaypoint(int x, int y) {
        // Insert waypoint at the best position in the path
        Point p1 = source.getCenter();
        Point p2 = target.getCenter();
        
        if (waypoints.isEmpty()) {
            waypoints.add(new Point(x, y));
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
                waypoints.add(new Point(x, y));
            } else {
                waypoints.add(bestIndex, new Point(x, y));
            }
        }
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
}