package com.editor;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a Petri net element (Place, Transition, or Event Generator) on the canvas
 */
public class ProcessElement {
    public enum Type {
        PLACE,           // Circle
        TRANSITION,      // Rectangle
        EVENT_GENERATOR  // Rectangle with arrow (token source)
    }
    
    private Type type;
    private int x, y;
    private int width, height;
    private String label;
    private String id;
    private static int nextId = 1;
    
    // Label display options
    private int labelMaxWidth = 100;  // Max width before wrapping (0 = no wrap)
    private boolean labelCentered = true;
    
    // Additional attributes for Places
    private String service = "";
    private List<ServiceOperation> operations = new ArrayList<>();
    
    // Additional attributes for Transitions
    private String nodeType = "";
    private String nodeValue = "";
    private String transitionType = "Other";  // T_in, T_out, or Other
    private String buffer = "10";  // Buffer size for T_in transitions
    
    // Additional attributes for Event Generators
    private String generatorRate = "1000";  // Token generation rate in ms
    private String tokenVersion = "v001";   // Version prefix for generated tokens
    
    // Fork behavior for Event Generators (implicit fork to join)
    private boolean forkEnabled = false;
    private int forkChildCount = 0;
    private String forkJoinTarget = null;
    
    // Rotation angle for Transitions and Event Generators (in degrees)
    private double rotationAngle = 0;
    
    // Shape colors
    private Color fillColor = null;    // null = use default
    private Color borderColor = null;  // null = use default (BLACK)
    
    public ProcessElement(Type type, int x, int y) {
        this.type = type;
        this.x = x;
        this.y = y;
        
        if (type == Type.PLACE) {
            this.width = 50;
            this.height = 50;
            this.label = "P" + nextId;
            this.id = "P" + nextId;
        } else if (type == Type.EVENT_GENERATOR) {
            this.width = 24;   // Same as Transition
            this.height = 60;  // Same as Transition
            this.label = "EG" + nextId;
            this.id = "EG" + nextId;
            this.nodeType = "EventGenerator";
            this.nodeValue = "EVENT_GENERATOR";
        } else {
            this.width = 24;
            this.height = 60;
            this.label = "T" + nextId;
            this.id = "T" + nextId;
            this.nodeType = "EdgeNode";
            this.nodeValue = "EDGE_NODE";
        }
        nextId++;
    }
    
    public void draw(Graphics2D g2) {
        draw(g2, false);
    }
    
    public void draw(Graphics2D g2, boolean selected) {
        if (type == Type.PLACE) {
            // Draw selection glow first (if selected)
            if (selected) {
                g2.setColor(new Color(100, 150, 255, 80));
                g2.setStroke(new BasicStroke(6));
                g2.drawOval(x - 3, y - 3, width + 6, height + 6);
            }
            
            // Draw circle with custom or default color
            g2.setColor(fillColor != null ? fillColor : Color.WHITE);
            g2.fillOval(x, y, width, height);
            
            // Draw border
            g2.setColor(borderColor != null ? borderColor : Color.BLACK);
            g2.setStroke(new BasicStroke(1));
            g2.drawOval(x, y, width, height);
            g2.setStroke(new BasicStroke(1));
        } else if (type == Type.EVENT_GENERATOR) {
            // Draw Event Generator - rectangle with outgoing arrow
            Graphics2D g2d = (Graphics2D) g2.create();
            
            int cx = x + width / 2;
            int cy = y + height / 2;
            g2d.translate(cx, cy);
            g2d.rotate(Math.toRadians(rotationAngle));
            
            // Draw selection glow first (if selected)
            if (selected) {
                g2d.setColor(new Color(100, 150, 255, 80));
                g2d.setStroke(new BasicStroke(6));
                g2d.drawRect(-width / 2 - 3, -height / 2 - 3, width + 6, height + 6);
            }
            
            // Draw rectangle with custom or default fill color
            Color egDefaultFill = new Color(220, 255, 220);  // Light green
            g2d.setColor(fillColor != null ? fillColor : egDefaultFill);
            g2d.fillRect(-width / 2, -height / 2, width, height);
            
            // Draw border
            g2d.setColor(borderColor != null ? borderColor : Color.BLACK);
            g2d.setStroke(new BasicStroke(1));
            g2d.drawRect(-width / 2, -height / 2, width, height);
            
            // Draw outgoing arrow on the right side (pointing right from center)
            int arrowStartX = width / 2;
            int arrowEndX = width / 2 + 15;
            int arrowY = 0;
            
            g2d.setStroke(new BasicStroke(1.5f));
            g2d.drawLine(arrowStartX, arrowY, arrowEndX, arrowY);
            
            // Arrowhead
            int arrowSize = 6;
            g2d.drawLine(arrowEndX, arrowY, arrowEndX - arrowSize, arrowY - arrowSize/2);
            g2d.drawLine(arrowEndX, arrowY, arrowEndX - arrowSize, arrowY + arrowSize/2);
            
            g2d.setStroke(new BasicStroke(1));
            g2d.dispose();
            
            // Draw "EG" inside the box
            g2.setColor(Color.BLACK);
            Font smallFont = new Font("SansSerif", Font.PLAIN, 9);
            g2.setFont(smallFont);
            FontMetrics smallFm = g2.getFontMetrics();
            int prefixX = x + (width - smallFm.stringWidth("EG")) / 2;
            int prefixY = y + (height + smallFm.getAscent()) / 2;
            g2.drawString("EG", prefixX, prefixY);
            
            // Draw remainder of label below (if label is longer than just "EG")
            if (label.length() > 2) {
                String remainder = label.startsWith("EG_") ? label.substring(3) : 
                                   label.startsWith("EG") ? label.substring(2) : label;
                if (!remainder.isEmpty()) {
                    drawWrappedLabel(g2, remainder, x + width / 2, y + height + 3);
                }
            }
        } else {
            // Draw Transition rectangle with rotation
            Graphics2D g2d = (Graphics2D) g2.create();
            
            int cx = x + width / 2;
            int cy = y + height / 2;
            g2d.translate(cx, cy);
            g2d.rotate(Math.toRadians(rotationAngle));
            
            // Subtle rounded corners
            int arcSize = 6;
            
            // Draw selection glow first (if selected)
            if (selected) {
                g2d.setColor(new Color(100, 150, 255, 80));
                g2d.setStroke(new BasicStroke(6));
                g2d.drawRoundRect(-width / 2 - 3, -height / 2 - 3, width + 6, height + 6, arcSize + 2, arcSize + 2);
            }
            
            // Draw rectangle centered at origin with custom or default color
            g2d.setColor(fillColor != null ? fillColor : Color.WHITE);
            g2d.fillRoundRect(-width / 2, -height / 2, width, height, arcSize, arcSize);
            
            // Draw border
            g2d.setColor(borderColor != null ? borderColor : Color.BLACK);
            g2d.setStroke(new BasicStroke(1));
            g2d.drawRoundRect(-width / 2, -height / 2, width, height, arcSize, arcSize);
            g2d.setStroke(new BasicStroke(1));
            
            g2d.dispose();
            
            // Draw node type indicator at visual top (outside rotation context)
            String nodeIndicator = getNodeTypeIndicator();
            if (nodeIndicator != null && !nodeIndicator.isEmpty()) {
                g2.setColor(Color.BLACK);
                g2.setFont(new Font("SansSerif", Font.BOLD, 9));
                FontMetrics nfm = g2.getFontMetrics();
                
                // Calculate visual top of the rotated rectangle
                double rad = Math.toRadians(rotationAngle);
                double topOffset = (height / 2.0) * Math.abs(Math.cos(rad)) + 
                                  (width / 2.0) * Math.abs(Math.sin(rad));
                int visualTop = (int) (cy - topOffset);
                
                // Center the indicator horizontally, position just inside visual top
                int niX = cx - nfm.stringWidth(nodeIndicator) / 2;
                int niY = visualTop + nfm.getAscent() + 2;
                g2.drawString(nodeIndicator, niX, niY);
            }
        }
        
        // Draw label for Transitions (not Event Generators - they handle their own)
        if (type == Type.TRANSITION) {
            g2.setColor(Color.BLACK);
            g2.setFont(new Font("SansSerif", Font.PLAIN, 12));
            FontMetrics fm = g2.getFontMetrics();
            
            int cx = x + width / 2;
            int cy = y + height / 2;
            double rad = Math.toRadians(rotationAngle);
            
            double bottomOffset = (height / 2.0) * Math.abs(Math.cos(rad)) + 
                                 (width / 2.0) * Math.abs(Math.sin(rad));
            int bottomY = (int) (cy + bottomOffset);
            
            String prefix = null;
            String remainder = label;
            
            if (label.startsWith("T_out_")) {
                prefix = "T_out";
                remainder = label.substring(6);
            } else if (label.startsWith("T_in_")) {
                prefix = "T_in";
                remainder = label.substring(5);
            } else if (label.startsWith("T_")) {
                prefix = "T";
                remainder = label.substring(2);
            }
            
            if (prefix != null && !remainder.isEmpty()) {
                // Just draw prefix inside rectangle - no sub-text below
                // (the associated Place already shows the name)
                Font smallFont = g2.getFont().deriveFont(9.0f);
                g2.setFont(smallFont);
                FontMetrics smallFm = g2.getFontMetrics();
                int prefixX = x + (width - smallFm.stringWidth(prefix)) / 2;
                int prefixY = y + (height + smallFm.getAscent()) / 2;
                g2.drawString(prefix, prefixX, prefixY);
            } else {
                drawWrappedLabel(g2, label, cx, bottomY + 3);
            }
        } else if (type == Type.PLACE) {
            // Places: draw label below with wrapping
            g2.setColor(Color.BLACK);
            g2.setFont(new Font("SansSerif", Font.PLAIN, 12));
            drawWrappedLabel(g2, label, x + width / 2, y + height + 3);
        }
    }
    
    /**
     * Draw a label with wrapping and centering below a center point.
     */
    private void drawWrappedLabel(Graphics2D g2, String text, int centerX, int topY) {
        FontMetrics fm = g2.getFontMetrics();
        
        if (labelMaxWidth <= 0) {
            // No wrapping - draw single line centered
            int textX = centerX - fm.stringWidth(text) / 2;
            int textY = topY + fm.getAscent();
            g2.drawString(text, textX, textY);
        } else {
            // Wrap and center
            TextWrapper.WrappedText wrapped = TextWrapper.wrap(text, fm, labelMaxWidth);
            TextWrapper.drawCenteredBelow(g2, wrapped, centerX, topY);
        }
    }
    
    /**
     * Get the bounding rectangle of the label area (for hit testing)
     */
    public Rectangle getLabelBounds() {
        Font font = new Font("SansSerif", Font.PLAIN, 12);
        FontMetrics fm = new Canvas().getFontMetrics(font);
        
        int cx = x + width / 2;
        int labelTopY;
        String labelText = label;
        
        if (type == Type.PLACE) {
            labelTopY = y + height + 3;
        } else if (type == Type.TRANSITION || type == Type.EVENT_GENERATOR) {
            double rad = Math.toRadians(rotationAngle);
            double bottomOffset = (height / 2.0) * Math.abs(Math.cos(rad)) + 
                                 (width / 2.0) * Math.abs(Math.sin(rad));
            labelTopY = (int) (y + height / 2 + bottomOffset) + 3;
            
            // Adjust for prefix removal
            if (label.startsWith("T_out_")) {
                labelText = label.substring(6);
            } else if (label.startsWith("T_in_")) {
                labelText = label.substring(5);
            } else if (label.startsWith("T_")) {
                labelText = label.substring(2);
            } else if (label.startsWith("EG_")) {
                labelText = label.substring(3);
            } else if (label.startsWith("EG")) {
                labelText = label.substring(2);
            }
        } else {
            labelTopY = y + height + 3;
        }
        
        if (labelMaxWidth > 0) {
            TextWrapper.WrappedText wrapped = TextWrapper.wrap(labelText, fm, labelMaxWidth);
            int boxWidth = Math.max(wrapped.totalWidth, labelMaxWidth);
            return new Rectangle(cx - boxWidth / 2, labelTopY, boxWidth, wrapped.totalHeight);
        } else {
            int textWidth = fm.stringWidth(labelText);
            return new Rectangle(cx - textWidth / 2, labelTopY, textWidth, fm.getHeight());
        }
    }
    
    public void drawHandles(Graphics2D g2) {
        int cx = x + width / 2;
        int cy = y + height / 2;
        
        double rad = Math.toRadians(rotationAngle);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        
        int topX = (int)(cx + 0 * cos - (-height / 2) * sin);
        int topY = (int)(cy + 0 * sin + (-height / 2) * cos);
        
        int bottomX = (int)(cx + 0 * cos - (height / 2) * sin);
        int bottomY = (int)(cy + 0 * sin + (height / 2) * cos);
        
        g2.setColor(new Color(100, 100, 255));
        g2.fillRect(topX - 4, topY - 4, 8, 8);
        g2.fillRect(bottomX - 4, bottomY - 4, 8, 8);
        
        int handleDist = 60;
        int rotHandleX = (int)(cx + handleDist * cos - 0 * sin);
        int rotHandleY = (int)(cy + handleDist * sin + 0 * cos);
        
        g2.setColor(new Color(0, 200, 0));
        g2.drawLine(cx, cy, rotHandleX, rotHandleY);
        g2.fillOval(rotHandleX - 5, rotHandleY - 5, 10, 10);
    }
    
    public Point getTopHandle() {
        if (type == Type.PLACE) return null;
        int cx = x + width / 2;
        int cy = y + height / 2;
        double rad = Math.toRadians(rotationAngle);
        int topX = (int)(cx + 0 * Math.cos(rad) - (-height / 2) * Math.sin(rad));
        int topY = (int)(cy + 0 * Math.sin(rad) + (-height / 2) * Math.cos(rad));
        return new Point(topX, topY);
    }
    
    public Point getBottomHandle() {
        if (type == Type.PLACE) return null;
        int cx = x + width / 2;
        int cy = y + height / 2;
        double rad = Math.toRadians(rotationAngle);
        int bottomX = (int)(cx + 0 * Math.cos(rad) - (height / 2) * Math.sin(rad));
        int bottomY = (int)(cy + 0 * Math.sin(rad) + (height / 2) * Math.cos(rad));
        return new Point(bottomX, bottomY);
    }
    
    public Point getLeftResizeHandle() {
        // Returns TOP handle (on short edge) - for compatibility
        if (type == Type.PLACE) return null;
        return getTopHandle();
    }
    
    public Point getRightResizeHandle() {
        // Returns BOTTOM handle (on short edge) - for compatibility
        if (type == Type.PLACE) return null;
        return getBottomHandle();
    }
    
    public Point getRotationHandle() {
        if (type == Type.PLACE) return null;
        int cx = x + width / 2;
        int cy = y + height / 2;
        double rad = Math.toRadians(rotationAngle);
        int handleDist = 60;
        int rotHandleX = (int)(cx + handleDist * Math.cos(rad));
        int rotHandleY = (int)(cy + handleDist * Math.sin(rad));
        return new Point(rotHandleX, rotHandleY);
    }
    
    public boolean isNearHandle(Point handle, int px, int py) {
        if (handle == null) return false;
        return Math.abs(handle.x - px) <= 6 && Math.abs(handle.y - py) <= 6;
    }
    
    public void resizeFromLeft(int dx, int dy) {
        double rad = Math.toRadians(rotationAngle);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        
        double deltaHeight = -dx * sin - dy * cos;
        
        int newHeight = (int)(height - deltaHeight * 2);
        if (newHeight > 20) {
            int actualDelta = newHeight - height;
            height = newHeight;
            
            x = (int)(x - (actualDelta / 2.0) * sin);
            y = (int)(y - (actualDelta / 2.0) * cos);
        }
    }
    
    public void resizeFromRight(int dx, int dy) {
        double rad = Math.toRadians(rotationAngle);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        
        double deltaHeight = -dx * sin - dy * cos;
        
        int newHeight = (int)(height + deltaHeight * 2);
        if (newHeight > 20) {
            int actualDelta = newHeight - height;
            height = newHeight;
            
            x = (int)(x - (actualDelta / 2.0) * sin);
            y = (int)(y - (actualDelta / 2.0) * cos);
        }
    }
    
    public void rotateTowards(int mx, int my) {
        int cx = x + width / 2;
        int cy = y + height / 2;
        double angle = Math.toDegrees(Math.atan2(my - cy, mx - cx));
        angle = angle + 90;
        
        double[] snapAngles = {0, 90, 180, 270, 360, -90, -180, -270};
        double snapThreshold = 10.0;
        
        for (double snapAngle : snapAngles) {
            if (Math.abs(angle - snapAngle) < snapThreshold) {
                angle = snapAngle;
                break;
            }
        }
        
        while (angle < 0) angle += 360;
        while (angle >= 360) angle -= 360;
        
        rotationAngle = angle;
    }
    
    public boolean contains(int px, int py) {
        boolean inShape = false;
        if (type == Type.PLACE) {
            int cx = x + width / 2;
            int cy = y + height / 2;
            int dx = px - cx;
            int dy = py - cy;
            int radius = width / 2;
            inShape = (dx * dx + dy * dy) <= (radius * radius);
        } else {
            inShape = px >= x && px <= x + width && py >= y && py <= y + height;
        }
        
        // Also check label bounds
        Rectangle labelBounds = getLabelBounds();
        boolean inLabel = labelBounds != null && labelBounds.contains(px, py);
        
        return inShape || inLabel;
    }
    
    public Point getCenter() {
        return new Point(x + width / 2, y + height / 2);
    }
    
    public void move(int dx, int dy) {
        this.x += dx;
        this.y += dy;
    }
    
    public void setPosition(int x, int y) {
        this.x = x;
        this.y = y;
    }
    
    // Getters
    public Type getType() { return type; }
    public int getX() { return x; }
    public int getY() { return y; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public String getLabel() { return label; }
    public String getId() { return id; }
    public String getService() { return service; }
    public String getNodeType() { return nodeType; }
    public String getNodeValue() { return nodeValue; }
    public String getTransitionType() { return transitionType; }
    public String getBuffer() { return buffer; }
    public double getRotationAngle() { return rotationAngle; }
    public String getGeneratorRate() { return generatorRate; }
    public String getTokenVersion() { return tokenVersion; }
    public int getLabelMaxWidth() { return labelMaxWidth; }
    public boolean isLabelCentered() { return labelCentered; }
    public Color getFillColor() { return fillColor; }
    public Color getBorderColor() { return borderColor; }
    
    /**
     * Get a short indicator letter(s) for the node type.
     */
    public String getNodeTypeIndicator() {
        if (nodeType == null || nodeType.isEmpty()) {
            return "E";  // Default to EdgeNode
        }
        switch (nodeType) {
            case "EdgeNode":     return "E";
            case "XorNode":      return "X";
            case "JoinNode":     return "J";
            case "MergeNode":    return "M";
            case "XorMergeNode": return "XM";
            case "ForkNode":     return "F";
            case "GatewayNode":  return "G";
            case "TerminateNode": return "T";
            default:             return "E";
        }
    }
    
    // Fork behavior getters
    public boolean isForkEnabled() { return forkEnabled; }
    public int getForkChildCount() { return forkChildCount; }
    public String getForkJoinTarget() { return forkJoinTarget; }
    
    /**
     * Get the list of ServiceOperation objects for this element
     */
    public List<ServiceOperation> getServiceOperations() { 
        return operations; 
    }
    
    /**
     * Get operation names as a list of strings (for backward compatibility)
     * This returns just the operation names without arguments.
     */
    public List<String> getOperations() {
        List<String> names = new ArrayList<>();
        for (ServiceOperation op : operations) {
            names.add(op.getName());
        }
        return names;
    }
    
    public void setLabel(String label) {
        this.label = label;
        this.id = label;
    }
    
    public void setService(String service) {
        this.service = service;
    }
    
    /**
     * Set operations from a list of ServiceOperation objects
     */
    public void setServiceOperations(List<ServiceOperation> operations) {
        this.operations = operations != null ? new ArrayList<>(operations) : new ArrayList<>();
    }
    
    /**
     * Set operations from a list of operation name strings (for backward compatibility)
     * Each string becomes a ServiceOperation with no arguments.
     */
    public void setOperations(List<String> operationNames) {
        this.operations.clear();
        if (operationNames != null) {
            for (String name : operationNames) {
                if (name != null && !name.trim().isEmpty()) {
                    this.operations.add(new ServiceOperation(name.trim()));
                }
            }
        }
    }
    
    /**
     * Add an operation by name (for backward compatibility)
     * Creates a new ServiceOperation with no arguments.
     */
    public void addOperation(String operationName) {
        if (operationName != null && !operationName.trim().isEmpty()) {
            // Check if operation with this name already exists
            for (ServiceOperation op : operations) {
                if (op.getName().equals(operationName.trim())) {
                    return; // Already exists
                }
            }
            operations.add(new ServiceOperation(operationName.trim()));
        }
    }
    
    /**
     * Add a ServiceOperation object
     */
    public void addServiceOperation(ServiceOperation operation) {
        if (operation != null && !operation.getName().trim().isEmpty()) {
            // Check if operation with this name already exists
            for (ServiceOperation op : operations) {
                if (op.getName().equals(operation.getName())) {
                    return; // Already exists
                }
            }
            operations.add(operation);
        }
    }
    
    /**
     * Remove an operation by name
     */
    public void removeOperation(String operationName) {
        operations.removeIf(op -> op.getName().equals(operationName));
    }
    
    /**
     * Remove a ServiceOperation object
     */
    public void removeServiceOperation(ServiceOperation operation) {
        operations.remove(operation);
    }
    
    /**
     * Get a ServiceOperation by name
     */
    public ServiceOperation getServiceOperation(String operationName) {
        for (ServiceOperation op : operations) {
            if (op.getName().equals(operationName)) {
                return op;
            }
        }
        return null;
    }
    
    /**
     * Clear all operations
     */
    public void clearOperations() {
        operations.clear();
    }
    
    public void setNodeType(String nodeType) {
        this.nodeType = nodeType;
    }
    
    public void setNodeValue(String nodeValue) {
        this.nodeValue = nodeValue;
    }
    
    public void setTransitionType(String transitionType) {
        this.transitionType = transitionType;
    }
    
    public void setBuffer(String buffer) {
        this.buffer = buffer;
    }
    
    public void setGeneratorRate(String rate) {
        this.generatorRate = rate;
    }
    
    public void setTokenVersion(String version) {
        this.tokenVersion = version;
    }
    
    // Fork behavior setters
    public void setForkEnabled(boolean enabled) {
        this.forkEnabled = enabled;
    }
    
    public void setForkChildCount(int count) {
        this.forkChildCount = count;
    }
    
    public void setForkJoinTarget(String target) {
        this.forkJoinTarget = target;
    }
    
    /**
     * Infer transition type from label (T_in_, T_out_, or Other)
     */
    public String inferTransitionTypeFromLabel() {
        if (label == null) return "Other";
        if (label.startsWith("T_in_")) return "T_in";
        if (label.startsWith("T_out_")) return "T_out";
        return "Other";
    }
    
    public void setRotationAngle(double angle) {
        this.rotationAngle = angle;
    }
    
    public void setWidth(int width) {
        this.width = width;
    }
    
    public void setHeight(int height) {
        this.height = height;
    }
    
    public void setLabelMaxWidth(int maxWidth) {
        this.labelMaxWidth = maxWidth;
    }
    
    public void setLabelCentered(boolean centered) {
        this.labelCentered = centered;
    }
    
    public void setFillColor(Color color) {
        this.fillColor = color;
    }
    
    public void setBorderColor(Color color) {
        this.borderColor = color;
    }
    
    /**
     * Reset colors to defaults
     */
    public void resetColors() {
        this.fillColor = null;
        this.borderColor = null;
    }
}