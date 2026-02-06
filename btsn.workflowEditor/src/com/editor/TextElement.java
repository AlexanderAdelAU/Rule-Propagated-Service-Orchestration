package com.editor;

import java.awt.*;
import javax.swing.JLabel;

/**
 * Represents a text annotation on the canvas with support for 
 * text wrapping and centering.
 */
public class TextElement {
    private int x, y;
    private String text;
    private Font font;
    private Color color;
    private int maxWidth = 0;  // 0 = no wrapping, > 0 = wrap at this width
    private boolean centerText = false;
    private static int nextId = 1;
    
    // Cached wrapped text for performance
    private TextWrapper.WrappedText cachedWrapped = null;
    private String lastText = null;
    private int lastMaxWidth = -1;
    private Font lastFont = null;
    
    public TextElement(int x, int y) {
        this.x = x;
        this.y = y;
        this.text = "Text " + nextId;
        this.font = new Font("SansSerif", Font.PLAIN, 12);
        this.color = Color.BLACK;
        nextId++;
    }
    
    public TextElement(int x, int y, String text) {
        this.x = x;
        this.y = y;
        this.text = text;
        this.font = new Font("SansSerif", Font.PLAIN, 12);
        this.color = Color.BLACK;
    }
    
    /**
     * Constructor with wrapping options
     */
    public TextElement(int x, int y, String text, int maxWidth, boolean centerText) {
        this.x = x;
        this.y = y;
        this.text = text;
        this.font = new Font("SansSerif", Font.PLAIN, 12);
        this.color = Color.BLACK;
        this.maxWidth = maxWidth;
        this.centerText = centerText;
    }
    
    public void draw(Graphics2D g2) {
        draw(g2, false);
    }
    
    public void draw(Graphics2D g2, boolean selected) {
        g2.setFont(font);
        FontMetrics fm = g2.getFontMetrics();
        
        // Get wrapped text (uses cache if available)
        TextWrapper.WrappedText wrapped = getWrappedText(fm);
        
        // Calculate box width
        int boxWidth = maxWidth > 0 ? Math.max(wrapped.totalWidth, maxWidth) : wrapped.totalWidth;
        
        // Draw selection box if selected
        if (selected) {
            g2.setColor(new Color(100, 150, 255, 80)); // Light blue, transparent
            g2.setStroke(new BasicStroke(2));
            g2.drawRect(x - 2, y - fm.getAscent() - 2, boxWidth + 4, wrapped.totalHeight + 4);
            g2.setStroke(new BasicStroke(1));
        }
        
        // Draw text
        g2.setColor(color);
        if (centerText && maxWidth > 0) {
            // Draw centered within box
            TextWrapper.draw(g2, wrapped, x, y, boxWidth, true);
        } else {
            // Draw left-aligned
            TextWrapper.draw(g2, wrapped, x, y, 0, false);
        }
    }
    
    /**
     * Get the bounding rectangle of this text element
     */
    public Rectangle getBounds() {
        FontMetrics fm = new JLabel().getFontMetrics(font);
        TextWrapper.WrappedText wrapped = getWrappedText(fm);
        int boxWidth = maxWidth > 0 ? Math.max(wrapped.totalWidth, maxWidth) : wrapped.totalWidth;
        
        return new Rectangle(x - 2, y - fm.getAscent() - 2, boxWidth + 4, wrapped.totalHeight + 4);
    }
    
    public boolean contains(int px, int py) {
        Rectangle bounds = getBounds();
        return bounds.contains(px, py);
    }
    
    /**
     * Get wrapped text, using cache if available
     */
    private TextWrapper.WrappedText getWrappedText(FontMetrics fm) {
        // Return cached if nothing changed
        if (cachedWrapped != null && 
            text.equals(lastText) && 
            maxWidth == lastMaxWidth &&
            font.equals(lastFont)) {
            return cachedWrapped;
        }
        
        // Recompute
        cachedWrapped = TextWrapper.wrap(text, fm, maxWidth);
        
        // Update cache keys
        lastText = text;
        lastMaxWidth = maxWidth;
        lastFont = font;
        
        return cachedWrapped;
    }
    
    /**
     * Invalidate the cache (call when text/font/width changes)
     */
    private void invalidateCache() {
        cachedWrapped = null;
    }
    
    public void move(int dx, int dy) {
        this.x += dx;
        this.y += dy;
    }
    
    public void setPosition(int x, int y) {
        this.x = x;
        this.y = y;
    }
    
    public Point getPosition() {
        return new Point(x, y);
    }
    
    // Getters and Setters
    public int getX() { return x; }
    public int getY() { return y; }
    
    public String getText() { return text; }
    public void setText(String text) { 
        this.text = text; 
        invalidateCache();
    }
    
    public Font getFont() { return font; }
    public void setFont(Font font) { 
        this.font = font;
        invalidateCache();
    }
    
    public Color getColor() { return color; }
    public void setColor(Color color) { this.color = color; }
    
    public int getMaxWidth() { return maxWidth; }
    public void setMaxWidth(int maxWidth) { 
        this.maxWidth = maxWidth;
        invalidateCache();
    }
    
    public boolean isCenterText() { return centerText; }
    public void setCenterText(boolean centerText) { this.centerText = centerText; }
    
    public void setFontSize(int size) {
        this.font = font.deriveFont((float) size);
        invalidateCache();
    }
    
    public void setFontStyle(int style) {
        this.font = font.deriveFont(style);
        invalidateCache();
    }
    
    /**
     * Convenience method to enable wrapping with centering
     */
    public void enableWrapping(int maxWidth) {
        setMaxWidth(maxWidth);
        setCenterText(true);
    }
    
    /**
     * Disable wrapping (single line mode)
     */
    public void disableWrapping() {
        setMaxWidth(0);
        setCenterText(false);
    }
}