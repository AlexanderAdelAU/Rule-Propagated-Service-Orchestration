package com.petrinet.editor;

import java.awt.*;

/**
 * Represents a Petri net element (Place or Transition) on the canvas
 */
public class PetriNetElement {
    public enum Type {
        PLACE,      // Circle
        TRANSITION  // Rectangle
    }
    
    private Type type;
    private int x, y;
    private int width, height;
    private String label;
    private String id;
    private static int nextId = 1;
    
    // Additional attributes for Places
    private String service = "";
    private String operation = "";
    
    // Additional attributes for Transitions
    private String nodeType = "";
    private String nodeValue = "";
    
    // Rotation angle for Transitions (in degrees)
    private double rotationAngle = 0;
    
    public PetriNetElement(Type type, int x, int y) {
        this.type = type;
        this.x = x;
        this.y = y;
        
        if (type == Type.PLACE) {
            this.width = 50;
            this.height = 50;
            this.label = "P" + nextId;
            this.id = "P" + nextId;  // Use simple ID matching label
        } else {
            this.width = 20;  // Narrow dimension
            this.height = 60; // Tall dimension - narrow edge faces upward
            this.label = "T" + nextId;
            this.id = "T" + nextId;  // Use simple ID matching label
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
                g2.setColor(new Color(100, 150, 255, 80)); // Light blue, transparent
                g2.setStroke(new BasicStroke(6));
                g2.drawOval(x - 3, y - 3, width + 6, height + 6);
            }
            
            // Draw circle
            g2.setColor(Color.WHITE); // White fill
            g2.fillOval(x, y, width, height);
            
            // Draw black border (always the same)
            g2.setColor(Color.BLACK);
            g2.setStroke(new BasicStroke(1));
            g2.drawOval(x, y, width, height);
            g2.setStroke(new BasicStroke(1));
        } else {
            // Draw rectangle with rotation
            Graphics2D g2d = (Graphics2D) g2.create();
            
            // Translate to center, rotate, then draw
            int cx = x + width / 2;
            int cy = y + height / 2;
            g2d.translate(cx, cy);
            g2d.rotate(Math.toRadians(rotationAngle));
            
            // Draw selection glow first (if selected)
            if (selected) {
                g2d.setColor(new Color(100, 150, 255, 80)); // Light blue, transparent
                g2d.setStroke(new BasicStroke(6));
                g2d.drawRect(-width / 2 - 3, -height / 2 - 3, width + 6, height + 6);
            }
            
            // Draw rectangle centered at origin
            g2d.setColor(Color.WHITE); // White fill
            g2d.fillRect(-width / 2, -height / 2, width, height);
            
            // Draw black border (always the same)
            g2d.setColor(Color.BLACK);
            g2d.setStroke(new BasicStroke(1));
            g2d.drawRect(-width / 2, -height / 2, width, height);
            g2d.setStroke(new BasicStroke(1));
            
            g2d.dispose();
        }
        
        // Draw label below the shape
        g2.setColor(Color.BLACK);
        FontMetrics fm = g2.getFontMetrics();
        int textX = x + (width - fm.stringWidth(label)) / 2;
        int textY = y + height + fm.getAscent() + 3; // Below shape with small gap
        g2.drawString(label, textX, textY);
    }
    
    public void drawHandles(Graphics2D g2) {
        int cx = x + width / 2;
        int cy = y + height / 2;
        
        // Calculate rotated corner points for resize handles
        double rad = Math.toRadians(rotationAngle);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        
        // Top handle (on short edge) - allows extending the long dimension
        int topX = (int)(cx + 0 * cos - (-height / 2) * sin);
        int topY = (int)(cy + 0 * sin + (-height / 2) * cos);
        
        // Bottom handle (on short edge) - allows extending the long dimension
        int bottomX = (int)(cx + 0 * cos - (height / 2) * sin);
        int bottomY = (int)(cy + 0 * sin + (height / 2) * cos);
        
        // Draw resize handles (small blue boxes)
        g2.setColor(new Color(100, 100, 255));
        g2.fillRect(topX - 4, topY - 4, 8, 8);
        g2.fillRect(bottomX - 4, bottomY - 4, 8, 8);
        
        // Draw rotation handle (green line with circle) - pointing to the right (perpendicular to long edge)
        int handleDist = 60;  // Changed from 30 to 60
        int rotHandleX = (int)(cx + handleDist * cos - 0 * sin);
        int rotHandleY = (int)(cy + handleDist * sin + 0 * cos);
        
        g2.setColor(new Color(0, 200, 0));
        g2.drawLine(cx, cy, rotHandleX, rotHandleY);
        g2.fillOval(rotHandleX - 5, rotHandleY - 5, 10, 10);
    }
    
    public Point getLeftResizeHandle() {
        // Returns TOP handle (on short edge)
        if (type != Type.TRANSITION) return null;
        int cx = x + width / 2;
        int cy = y + height / 2;
        double rad = Math.toRadians(rotationAngle);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        int topX = (int)(cx + 0 * cos - (-height / 2) * sin);
        int topY = (int)(cy + 0 * sin + (-height / 2) * cos);
        return new Point(topX, topY);
    }
    
    public Point getRightResizeHandle() {
        // Returns BOTTOM handle (on short edge)
        if (type != Type.TRANSITION) return null;
        int cx = x + width / 2;
        int cy = y + height / 2;
        double rad = Math.toRadians(rotationAngle);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        int bottomX = (int)(cx + 0 * cos - (height / 2) * sin);
        int bottomY = (int)(cy + 0 * sin + (height / 2) * cos);
        return new Point(bottomX, bottomY);
    }
    
    public Point getRotationHandle() {
        if (type != Type.TRANSITION) return null;
        int cx = x + width / 2;
        int cy = y + height / 2;
        double rad = Math.toRadians(rotationAngle);
        int handleDist = 60;  // Changed from 30 to 60
        int rotHandleX = (int)(cx + handleDist * Math.cos(rad));
        int rotHandleY = (int)(cy + handleDist * Math.sin(rad));
        return new Point(rotHandleX, rotHandleY);
    }
    
    public boolean isNearHandle(Point handle, int px, int py) {
        if (handle == null) return false;
        return Math.abs(handle.x - px) <= 6 && Math.abs(handle.y - py) <= 6;
    }
    
    public void resizeFromLeft(int dx, int dy) {
        // Resize from TOP handle - changes HEIGHT (long dimension)
        // Calculate how much to change height based on drag in rotated space
        double rad = Math.toRadians(rotationAngle);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        
        // Transform drag into local coordinates (along the height axis)
        double deltaHeight = -dx * sin - dy * cos;
        
        int newHeight = (int)(height - deltaHeight * 2);
        if (newHeight > 20) { // Minimum height
            // Calculate how much the height actually changed
            int actualDelta = newHeight - height;
            height = newHeight;
            
            // Move the top-left corner to keep center fixed
            // When height increases by actualDelta, move position by actualDelta/2 in the opposite direction
            x = (int)(x - (actualDelta / 2.0) * sin);
            y = (int)(y - (actualDelta / 2.0) * cos);
        }
    }
    
    public void resizeFromRight(int dx, int dy) {
        // Resize from BOTTOM handle - changes HEIGHT (long dimension)
        // Calculate how much to change height based on drag in rotated space
        double rad = Math.toRadians(rotationAngle);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        
        // Transform drag into local coordinates (along the height axis)
        double deltaHeight = -dx * sin - dy * cos;
        
        int newHeight = (int)(height + deltaHeight * 2);
        if (newHeight > 20) { // Minimum height
            // Calculate how much the height actually changed
            int actualDelta = newHeight - height;
            height = newHeight;
            
            // Move the top-left corner to keep center fixed
            // When height increases by actualDelta, move position by actualDelta/2 in the opposite direction
            x = (int)(x - (actualDelta / 2.0) * sin);
            y = (int)(y - (actualDelta / 2.0) * cos);
        }
    }
    
    public void rotateTowards(int mx, int my) {
        int cx = x + width / 2;
        int cy = y + height / 2;
        double angle = Math.toDegrees(Math.atan2(my - cy, mx - cx));
        angle = angle + 90; // Adjust for handle being on top
        
        // Snap to cardinal directions if within 10 degrees
        double[] snapAngles = {0, 90, 180, 270, 360, -90, -180, -270};
        double snapThreshold = 10.0;
        
        for (double snapAngle : snapAngles) {
            if (Math.abs(angle - snapAngle) < snapThreshold) {
                angle = snapAngle;
                break;
            }
        }
        
        // Normalize angle to 0-360 range
        while (angle < 0) angle += 360;
        while (angle >= 360) angle -= 360;
        
        rotationAngle = angle;
    }
    
    public boolean contains(int px, int py) {
        // Check if in main shape
        boolean inShape = false;
        if (type == Type.PLACE) {
            // Check if point is inside circle
            int cx = x + width / 2;
            int cy = y + height / 2;
            int dx = px - cx;
            int dy = py - cy;
            int radius = width / 2;
            inShape = (dx * dx + dy * dy) <= (radius * radius);
        } else {
            // Check if point is inside rectangle
            inShape = px >= x && px <= x + width && py >= y && py <= y + height;
        }
        
        // Also check if in label area below shape (approximate)
        boolean inLabel = px >= x && px <= x + width && py >= y + height && py <= y + height + 20;
        
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
    public String getOperation() { return operation; }
    public String getNodeType() { return nodeType; }
    public String getNodeValue() { return nodeValue; }
    public double getRotationAngle() { return rotationAngle; }
    
    public void setLabel(String label) {
        this.label = label;
        // Keep ID in sync with label
        this.id = label;
    }
    
    public void setService(String service) {
        this.service = service;
    }
    
    public void setOperation(String operation) {
        this.operation = operation;
    }
    
    public void setNodeType(String nodeType) {
        this.nodeType = nodeType;
    }
    
    public void setNodeValue(String nodeValue) {
        this.nodeValue = nodeValue;
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
}