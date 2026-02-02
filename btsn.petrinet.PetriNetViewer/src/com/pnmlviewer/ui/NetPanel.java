
package com.pnmlviewer.ui;

import com.pnmlviewer.layout.Layouts;
import com.pnmlviewer.model.PnmlModel;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.util.*;

public class NetPanel extends JPanel {

    private PnmlModel model = null;

    public PnmlModel getModel() { return model; }
    public void setModel(PnmlModel m) { this.model = m; repaint(); }

    // Zoom
    private double zoom = 1.0;
    private static final double ZOOM_MIN = 0.2;
    private static final double ZOOM_MAX = 3.0;
    private static final double ZOOM_STEP = 1.1;
    private Runnable zoomChanged = null;

    // Curve mode
    public enum CurveMode { T_TO_P_ONLY, P_TO_T_ONLY, BOTH_CURVED, NONE }
    private CurveMode curveMode = CurveMode.T_TO_P_ONLY;
    public void setCurveMode(CurveMode mode) { this.curveMode = (mode==null?CurveMode.T_TO_P_ONLY:mode); repaint(); }
    public CurveMode getCurveMode() { return curveMode; }

    public NetPanel() {
        setBackground(Color.white);

        // Ctrl + wheel zoom
        addMouseWheelListener(e -> {
            if ((e.getModifiersEx() & InputEvent.CTRL_DOWN_MASK) != 0) {
                if (e.getWheelRotation() < 0) zoomIn(); else zoomOut();
                e.consume();
            }
        });

        // Dragging support for nodes, arcs, and waypoints
        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (model == null) return;
                double invZoom = 1.0 / zoom;
                Point2D.Double modelPt = new Point2D.Double(e.getX() * invZoom, e.getY() * invZoom);
                
                // First, check if clicking on a waypoint of the selected arc
                if (selectedArc != null && selectedArc.waypoints != null) {
                    for (int i = 0; i < selectedArc.waypoints.size(); i++) {
                        Point2D.Double wp = selectedArc.waypoints.get(i);
                        double dist = Math.hypot(modelPt.x - wp.x, modelPt.y - wp.y);
                        if (dist <= WAYPOINT_RADIUS) {
                            selectedWaypointIndex = i;
                            waypointDragStart = new Point2D.Double(wp.x, wp.y);
                            dragStartScreen = e.getPoint(); // Need this for delta calculation
                            repaint();
                            return;
                        }
                    }
                }
                
                // Check if clicking on a node
                selectedId = findNodeAtPoint(e.getPoint());
                if (selectedId != null && SwingUtilities.isLeftMouseButton(e)) {
                    selectedArc = null;
                    selectedWaypointIndex = -1;
                    dragStartScreen = e.getPoint();
                    PnmlModel.Node n = model.nodes.get(selectedId);
                    dragStartModel = new Point2D.Double(n.pos.x, n.pos.y);
                    repaint();
                    return;
                }
                
                // Check if clicking on an arc
                PnmlModel.Arc clickedArc = findArcAtPoint(modelPt);
                if (clickedArc != null) {
                    selectedArc = clickedArc;
                    selectedId = null;
                    selectedWaypointIndex = -1;
                    dragStartScreen = null;
                    dragStartModel = null;
                    repaint();
                    return;
                }
                
                // Clicked on nothing - deselect
                selectedId = null;
                selectedArc = null;
                selectedWaypointIndex = -1;
                dragStartScreen = null;
                dragStartModel = null;
                repaint();
            }
            
            @Override public void mouseReleased(MouseEvent e) {
                dragStartScreen = null;
                dragStartModel = null;
                waypointDragStart = null;
                selectedWaypointIndex = -1;
            }
            
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && selectedArc != null) {
                    // Double-click on selected arc: add waypoint
                    double invZoom = 1.0 / zoom;
                    Point2D.Double modelPt = new Point2D.Double(e.getX() * invZoom, e.getY() * invZoom);
                    addWaypointToArc(selectedArc, modelPt);
                    repaint();
                }
            }
        });
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseDragged(MouseEvent e) {
                if (model == null) return;
                double invZoom = 1.0 / zoom;
                
                // Dragging a waypoint
                if (selectedWaypointIndex >= 0 && selectedArc != null && waypointDragStart != null) {
                    double dx = (e.getX() - dragStartScreen.x) * invZoom;
                    double dy = (e.getY() - dragStartScreen.y) * invZoom;
                    Point2D.Double wp = selectedArc.waypoints.get(selectedWaypointIndex);
                    wp.x = waypointDragStart.x + dx;
                    wp.y = waypointDragStart.y + dy;
                    repaint();
                    return;
                }
                
                // Dragging a node
                if (selectedId != null && dragStartScreen != null && dragStartModel != null) {
                    double dx = (e.getX() - dragStartScreen.x) * invZoom;
                    double dy = (e.getY() - dragStartScreen.y) * invZoom;
                    PnmlModel.Node n = model.nodes.get(selectedId);
                    n.pos.x = dragStartModel.x + dx;
                    n.pos.y = dragStartModel.y + dy;
                    repaint();
                }
            }
        });
        
        // Keyboard support
        addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_DELETE || e.getKeyCode() == KeyEvent.VK_BACK_SPACE) {
                    if (selectedArc != null && selectedArc.waypoints != null && !selectedArc.waypoints.isEmpty()) {
                        // Remove last waypoint, or reset to automatic if only one left
                        if (selectedArc.waypoints.size() == 1) {
                            selectedArc.waypoints = null;
                        } else {
                            selectedArc.waypoints.remove(selectedArc.waypoints.size() - 1);
                        }
                        repaint();
                    }
                } else if (e.getKeyCode() == KeyEvent.VK_R && selectedArc != null) {
                    // Reset to automatic routing
                    selectedArc.waypoints = null;
                    repaint();
                }
            }
        });
        setFocusable(true);
    }

    public void setZoomChangedCallback(Runnable r) { this.zoomChanged = r; }

    public double getZoom() { return zoom; }
    public void setZoom(double z) {
        double nz = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, z));
        if (Math.abs(nz - zoom) > 1e-6) {
            zoom = nz;
            if (zoomChanged != null) zoomChanged.run();
            revalidate(); repaint();
        }
    }
    public void zoomIn() { setZoom(zoom * ZOOM_STEP); }
    public void zoomOut() { setZoom(zoom / ZOOM_STEP); }
    public void zoomReset() { setZoom(1.0); }

    // Selection & dragging
    private String selectedId = null;
    private Point dragStartScreen = null;
    private Point2D.Double dragStartModel = null;
    
    // Arc editing
    private PnmlModel.Arc selectedArc = null;
    private int selectedWaypointIndex = -1;
    private Point2D.Double waypointDragStart = null;
    private static final double ARC_CLICK_TOLERANCE = 8.0; // pixels
    private static final double WAYPOINT_RADIUS = 6.0;

    private String findNodeAtPoint(Point pScreen) {
        if (model == null) return null;
        double invZoom = 1.0 / zoom;
        double mx = pScreen.x * invZoom;
        double my = pScreen.y * invZoom;
        for (PnmlModel.Node n : model.nodes.values()) {
            if (Double.isNaN(n.pos.x)) continue;
            double dx = mx - n.pos.x, dy = my - n.pos.y;
            if (n.place) {
                int R = P_RADIUS;
                if (dx*dx + dy*dy <= R*R) return n.id;
            } else {
                double halfW = T_W/2.0, halfH = T_H/2.0;
                if (Math.abs(dx) <= halfW && Math.abs(dy) <= halfH) return n.id;
            }
        }
        return null;
    }
    
    private PnmlModel.Arc findArcAtPoint(Point2D.Double pt) {
        if (model == null) return null;
        for (PnmlModel.Arc a : model.arcs) {
            PnmlModel.Node s = model.nodes.get(a.source);
            PnmlModel.Node t = model.nodes.get(a.target);
            if (s == null || t == null) continue;
            if (Double.isNaN(s.pos.x) || Double.isNaN(t.pos.x)) continue;
            
            // Check if point is near the arc path
            if (a.waypoints != null && !a.waypoints.isEmpty()) {
                // Custom routed arc - check all segments
                Point2D.Double prev = s.pos;
                for (Point2D.Double wp : a.waypoints) {
                    if (distanceToSegment(pt, prev, wp) < ARC_CLICK_TOLERANCE) return a;
                    prev = wp;
                }
                if (distanceToSegment(pt, prev, t.pos) < ARC_CLICK_TOLERANCE) return a;
            } else {
                // Automatic routing - check the rendered path
                // For simplicity, just check the main segment(s)
                boolean antiparallel = hasReverseArc(a);
                boolean thisIsTtoP = (!s.place && t.place);
                boolean thisIsPtoT = ( s.place && !t.place);
                boolean makeCurved;
                switch (curveMode) {
                    case NONE -> makeCurved = false;
                    case BOTH_CURVED -> makeCurved = antiparallel;
                    case T_TO_P_ONLY -> makeCurved = antiparallel && thisIsTtoP;
                    case P_TO_T_ONLY -> makeCurved = antiparallel && thisIsPtoT;
                    default -> makeCurved = false;
                }
                
                if (!makeCurved) {
                    if (distanceToSegment(pt, s.pos, t.pos) < ARC_CLICK_TOLERANCE) return a;
                } else {
                    // Dog-leg - check the segments
                    int sign = a.source.compareTo(a.target) < 0 ? +1 : -1;
                    double dx = t.pos.x - s.pos.x, dy = t.pos.y - s.pos.y;
                    double len = Math.hypot(dx, dy);
                    if (len < 1e-6) continue;
                    double SIDE_OFFSET = 45;
                    double nx = -dy / len, ny = dx / len;
                    Point2D.Double wp1 = new Point2D.Double(s.pos.x + sign * SIDE_OFFSET * nx, s.pos.y + sign * SIDE_OFFSET * ny);
                    Point2D.Double wp2 = new Point2D.Double(t.pos.x + sign * SIDE_OFFSET * nx, t.pos.y - 25);
                    
                    if (distanceToSegment(pt, s.pos, wp1) < ARC_CLICK_TOLERANCE ||
                        distanceToSegment(pt, wp1, wp2) < ARC_CLICK_TOLERANCE ||
                        distanceToSegment(pt, wp2, t.pos) < ARC_CLICK_TOLERANCE) {
                        return a;
                    }
                }
            }
        }
        return null;
    }
    
    private double distanceToSegment(Point2D.Double pt, Point2D.Double a, Point2D.Double b) {
        double dx = b.x - a.x, dy = b.y - a.y;
        double len2 = dx*dx + dy*dy;
        if (len2 < 1e-10) return Math.hypot(pt.x - a.x, pt.y - a.y);
        double t = Math.max(0, Math.min(1, ((pt.x - a.x) * dx + (pt.y - a.y) * dy) / len2));
        double projX = a.x + t * dx, projY = a.y + t * dy;
        return Math.hypot(pt.x - projX, pt.y - projY);
    }
    
    private void addWaypointToArc(PnmlModel.Arc arc, Point2D.Double pt) {
        if (arc.waypoints == null) {
            arc.waypoints = new ArrayList<>();
        }
        // Find best position to insert based on distance along path
        arc.waypoints.add(pt);
    }

    public void layoutLeftToRight() {
        if (model == null) return;
        Layouts.leftToRight(model);
        repaint();
    }

    static final int P_RADIUS = 22;
    static final int T_W = 36, T_H = 18;

    @Override public Dimension getPreferredSize() { return new Dimension(1100, 800); }

    @Override protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (model == null) return;

        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        boolean hasAnyPos = model.nodes.values().stream().anyMatch(n -> !Double.isNaN(n.pos.x));
        if (!hasAnyPos) Layouts.leftToRight(model);

        g2.scale(zoom, zoom);

        // --- Draw arcs per PNML: arrow always points toward TARGET ---
        g2.setStroke(new BasicStroke(1.3f));
        for (PnmlModel.Arc a : model.arcs) {
            PnmlModel.Node s = model.nodes.get(a.source), t = model.nodes.get(a.target);
            if (s == null || t == null) continue;
            Point2D.Double p = s.pos, q = t.pos;
            if (Double.isNaN(p.x) || Double.isNaN(q.x)) continue;
            
            // Highlight selected arc
            if (a == selectedArc) {
                g2.setColor(new Color(50, 140, 255));
                g2.setStroke(new BasicStroke(2.5f));
            } else {
                g2.setColor(Color.darkGray);
                g2.setStroke(new BasicStroke(1.3f));
            }

            // Custom waypoint routing takes precedence
            if (a.waypoints != null && !a.waypoints.isEmpty()) {
                // Draw custom routed arc
                Path2D path = new Path2D.Double();
                path.moveTo(p.x, p.y);
                Point2D.Double prev = p;
                for (Point2D.Double wp : a.waypoints) {
                    path.lineTo(wp.x, wp.y);
                    prev = wp;
                }
                // Calculate exact endpoint at target boundary
                ArrowEndpoint end = calculateArrowEndpoint(prev, q, t.place);
                path.lineTo(end.x, end.y);
                g2.draw(path);
                drawArrowHeadOnVector(g2, end.x, end.y, end.dx, end.dy);
                
                if (a.weight != 1) {
                    Point2D.Double mid = a.waypoints.get(a.waypoints.size() / 2);
                    drawLabel(g2, String.valueOf(a.weight), (int)mid.x, (int)mid.y - 4, new Color(0,0,0,170));
                }
                continue;
            }

            // Automatic routing
            boolean antiparallel = hasReverseArc(a);
            boolean thisIsTtoP = (!s.place && t.place);
            boolean thisIsPtoT = ( s.place && !t.place);

            boolean makeCurved;
            switch (curveMode) {
                case NONE -> makeCurved = false;
                case BOTH_CURVED -> makeCurved = antiparallel;
                case T_TO_P_ONLY -> makeCurved = antiparallel && thisIsTtoP;
                case P_TO_T_ONLY -> makeCurved = antiparallel && thisIsPtoT;
                default -> makeCurved = false;
            }

            if (!makeCurved) {
                // Straight arc - calculate exact intersection with target boundary
                ArrowEndpoint end = calculateArrowEndpoint(p, q, t.place);
                g2.draw(new Line2D.Double(p.x, p.y, end.x, end.y));
                drawArrowHeadOnVector(g2, end.x, end.y, end.dx, end.dy);

                if (a.weight != 1) {
                    String lbl = String.valueOf(a.weight);
                    int lx = (int) ((p.x + q.x) / 2);
                    int ly = (int) ((p.y + q.y) / 2) - 4;
                    drawLabel(g2, lbl, lx, ly, new Color(0,0,0,170));
                }
            } else {
                // Dog-leg routing for antiparallel arcs
                // Route around to approach target from top or side to avoid bottom labels
                int sign = a.source.compareTo(a.target) < 0 ? +1 : -1;
                double dx = q.x - p.x, dy = q.y - p.y;
                double len = Math.hypot(dx, dy);
                if (len < 1e-6) continue;
                
                double SIDE_OFFSET = 45; // how far to the side to route
                
                // Calculate perpendicular direction
                double nx = -dy / len;
                double ny =  dx / len;
                
                // First waypoint: offset perpendicular from source
                Point2D.Double wp1 = new Point2D.Double(
                    p.x + sign * SIDE_OFFSET * nx,
                    p.y + sign * SIDE_OFFSET * ny
                );
                
                // Second waypoint: positioned to approach target from above/side
                // We want to avoid the label area (below the target)
                Point2D.Double wp2 = new Point2D.Double(
                    q.x + sign * SIDE_OFFSET * nx,
                    q.y - 25  // approach from above to avoid label
                );
                
                // Calculate exact endpoint at target boundary
                ArrowEndpoint end = calculateArrowEndpoint(wp2, q, t.place);
                
                // Draw the dog-leg path with 3 segments
                Path2D path = new Path2D.Double();
                path.moveTo(p.x, p.y);
                path.lineTo(wp1.x, wp1.y);
                path.lineTo(wp2.x, wp2.y);
                path.lineTo(end.x, end.y);
                g2.draw(path);
                drawArrowHeadOnVector(g2, end.x, end.y, end.dx, end.dy);

                if (a.weight != 1) {
                    // Place label on the middle segment
                    int lx = (int)Math.round((wp1.x + wp2.x) / 2);
                    int ly = (int)Math.round((wp1.y + wp2.y) / 2) - 4;
                    drawLabel(g2, String.valueOf(a.weight), lx, ly, new Color(0,0,0,170));
                }
            }
        }

        // nodes
        for (PnmlModel.Node n : model.nodes.values()) {
            int x = (int) Math.round(n.pos.x), y = (int) Math.round(n.pos.y);
            if (n.place) {
                Shape circle = new Ellipse2D.Double(x - P_RADIUS, y - P_RADIUS, 2*P_RADIUS, 2*P_RADIUS);
                g2.setColor(new Color(245, 250, 255));
                g2.fill(circle);
                g2.setColor(new Color(30, 90, 160));
                g2.setStroke(new BasicStroke(2f));
                g2.draw(circle);
                String tok = String.valueOf(n.tokens);
                drawCentered(g2, tok, x, y + 5, new Font("SansSerif", Font.BOLD, 12), new Color(30,30,30));
            } else {
                Shape rect = new RoundRectangle2D.Double(x - T_W/2.0, y - T_H/2.0, T_W, T_H, 6, 6);
                g2.setColor(new Color(255, 248, 240));
                g2.fill(rect);
                g2.setColor(new Color(180, 90, 30));
                g2.setStroke(new BasicStroke(2f));
                g2.draw(rect);
            }
            String label = (n.name == null || n.name.isEmpty()) ? n.id : n.name;
            drawLabel(g2, label, x, y + (n.place ? P_RADIUS + 14 : T_H/2 + 16), new Color(0,0,0,180));
        }

        // selection cue
        if (selectedId != null) {
            PnmlModel.Node sel = model.nodes.get(selectedId);
            if (sel != null && !Double.isNaN(sel.pos.x)) {
                g2.setColor(new Color(50, 140, 255, 160));
                g2.setStroke(new BasicStroke(3f));
                if (sel.place) {
                    g2.draw(new Ellipse2D.Double(sel.pos.x - P_RADIUS - 4, sel.pos.y - P_RADIUS - 4,
                                                 2*P_RADIUS + 8, 2*P_RADIUS + 8));
                } else {
                    g2.draw(new RoundRectangle2D.Double(sel.pos.x - T_W/2.0 - 6, sel.pos.y - T_H/2.0 - 6,
                                                        T_W + 12, T_H + 12, 10, 10));
                }
            }
        }

        // Draw waypoints for selected arc
        if (selectedArc != null && selectedArc.waypoints != null) {
            g2.setColor(new Color(50, 140, 255));
            for (int i = 0; i < selectedArc.waypoints.size(); i++) {
                Point2D.Double wp = selectedArc.waypoints.get(i);
                double r = WAYPOINT_RADIUS;
                Shape circle = new Ellipse2D.Double(wp.x - r, wp.y - r, 2*r, 2*r);
                g2.fill(circle);
                g2.setColor(Color.WHITE);
                g2.setStroke(new BasicStroke(1.5f));
                g2.draw(circle);
                g2.setColor(new Color(50, 140, 255));
            }
        }

        g2.dispose();
    }

    // --- helpers for dog-leg routing, arrowheads, labels ---
    private static class ArrowEndpoint {
        final double x, y;    // endpoint coordinates at boundary
        final double dx, dy;  // direction vector for arrowhead
        ArrowEndpoint(double x, double y, double dx, double dy) {
            this.x = x; this.y = y; this.dx = dx; this.dy = dy;
        }
    }

    private boolean hasReverseArc(PnmlModel.Arc a) {
        if (model == null) return false;
        for (PnmlModel.Arc b : model.arcs) {
            if (a != b && a.source.equals(b.target) && a.target.equals(b.source)) return true;
        }
        return false;
    }

    /**
     * Calculate exact intersection of line from 'from' to 'to' with the boundary of target node.
     * Returns the intersection point and direction vector for the arrowhead.
     */
    private static ArrowEndpoint calculateArrowEndpoint(Point2D.Double from, Point2D.Double to, boolean targetIsPlace) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double len = Math.hypot(dx, dy);
        
        if (len < 1e-6) {
            return new ArrowEndpoint(to.x, to.y, 1, 0);
        }
        
        // Unit direction vector
        double ux = dx / len;
        double uy = dy / len;
        
        if (targetIsPlace) {
            // Target is a circle: find intersection of ray with circle
            // Circle: (x - to.x)^2 + (y - to.y)^2 = R^2
            // Ray: (from.x + t*ux, from.y + t*uy)
            // We want the intersection point on the circle boundary
            double radius = P_RADIUS;
            double ex = to.x - radius * ux;
            double ey = to.y - radius * uy;
            return new ArrowEndpoint(ex, ey, ux, uy);
        } else {
            // Target is a rectangle: find intersection with rectangle boundary
            double halfW = T_W / 2.0;
            double halfH = T_H / 2.0;
            
            // Check which edge of the rectangle the ray intersects
            double t = Double.POSITIVE_INFINITY;
            
            // Check intersection with each edge
            if (Math.abs(ux) > 1e-6) {
                double t_right = (to.x + halfW - from.x) / ux;
                double t_left = (to.x - halfW - from.x) / ux;
                if (t_right > 0 && t_right < t) {
                    double y = from.y + t_right * uy;
                    if (Math.abs(y - to.y) <= halfH) t = t_right;
                }
                if (t_left > 0 && t_left < t) {
                    double y = from.y + t_left * uy;
                    if (Math.abs(y - to.y) <= halfH) t = t_left;
                }
            }
            if (Math.abs(uy) > 1e-6) {
                double t_bottom = (to.y + halfH - from.y) / uy;
                double t_top = (to.y - halfH - from.y) / uy;
                if (t_bottom > 0 && t_bottom < t) {
                    double x = from.x + t_bottom * ux;
                    if (Math.abs(x - to.x) <= halfW) t = t_bottom;
                }
                if (t_top > 0 && t_top < t) {
                    double x = from.x + t_top * ux;
                    if (Math.abs(x - to.x) <= halfW) t = t_top;
                }
            }
            
            double ex = from.x + t * ux;
            double ey = from.y + t * uy;
            return new ArrowEndpoint(ex, ey, ux, uy);
        }
    }

    private static void drawArrowHeadOnVector(Graphics2D g2, double ex, double ey, double vx, double vy) {
        double ang = Math.atan2(vy, vx);
        int headLen = 10, headW = 6;
        double bx = ex - headLen * Math.cos(ang);
        double by = ey - headLen * Math.sin(ang);
        double ox = headW * Math.cos(ang + Math.PI/2);
        double oy = headW * Math.sin(ang + Math.PI/2);
        Polygon arrow = new Polygon();
        arrow.addPoint((int) Math.round(ex), (int) Math.round(ey));
        arrow.addPoint((int) Math.round(bx + ox), (int) Math.round(by + oy));
        arrow.addPoint((int) Math.round(bx - ox), (int) Math.round(by - oy));
        g2.fill(arrow);
    }

    private static void drawCentered(Graphics2D g2, String s, int x, int y, Font f, Color c) {
        Font old = g2.getFont(); Color oc = g2.getColor();
        g2.setFont(f); g2.setColor(c);
        FontMetrics fm = g2.getFontMetrics();
        int w = fm.stringWidth(s);
        g2.drawString(s, x - w/2, y);
        g2.setFont(old); g2.setColor(oc);
    }

    private static void drawLabel(Graphics2D g2, String s, int x, int y, Color c) {
        FontMetrics fm = g2.getFontMetrics();
        int w = fm.stringWidth(s), h = fm.getAscent();
        int pad = 3;
        Shape bubble = new RoundRectangle2D.Double(x - w/2.0 - pad - 2, y - h + 2 - pad,
                                                   w + 2*pad + 4, h + 2*pad, 8, 8);
        g2.setColor(new Color(255,255,255,220));
        g2.fill(bubble);
        g2.setColor(new Color(0,0,0,80));
        g2.draw(bubble);
        g2.setColor(c);
        g2.drawString(s, x - w/2, y + 1);
    }
}
