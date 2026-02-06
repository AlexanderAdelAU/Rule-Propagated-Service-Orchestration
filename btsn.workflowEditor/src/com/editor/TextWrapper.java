package com.editor;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for wrapping and centering text.
 * Used by both ProcessElement (labels) and TextElement (annotations).
 * 
 * Line breaks are manual only - use | character to create new lines.
 */
public class TextWrapper {
    
    /**
     * Result of wrapping text - contains lines and dimensions
     */
    public static class WrappedText {
        public final List<String> lines;
        public final int totalWidth;   // Width of widest line
        public final int totalHeight;  // Total height of all lines
        public final int lineHeight;   // Height of single line
        
        public WrappedText(List<String> lines, int totalWidth, int totalHeight, int lineHeight) {
            this.lines = lines;
            this.totalWidth = totalWidth;
            this.totalHeight = totalHeight;
            this.lineHeight = lineHeight;
        }
    }
    
    /** 
     * Character used for explicit line breaks in labels.
     * Users can type "GATEWAY|NODE" to get two lines.
     */
    public static final String LINE_BREAK_CHAR = "|";
    
    /**
     * Wrap text using explicit line breaks only (| character).
     * No automatic wrapping is performed.
     * 
     * Examples:
     *   "GATEWAY|NODE" → two lines: "GATEWAY" and "NODE"
     *   "FEDERATED_RADIOLOGY_REQUEST" → single line (no auto-wrap)
     * 
     * @param text      The text to wrap
     * @param fm        FontMetrics for measuring text width
     * @param maxWidth  Ignored - kept for API compatibility
     * @return WrappedText containing lines and dimensions
     */
    public static WrappedText wrap(String text, FontMetrics fm, int maxWidth) {
        List<String> lines = new ArrayList<>();
        int lineHeight = fm.getHeight();
        
        if (text == null || text.isEmpty()) {
            lines.add("");
            return new WrappedText(lines, 0, lineHeight, lineHeight);
        }
        
        // Split on | character for explicit line breaks
        String[] explicitLines = text.split("\\|");
        int maxLineWidth = 0;
        for (String line : explicitLines) {
            lines.add(line);
            maxLineWidth = Math.max(maxLineWidth, fm.stringWidth(line));
        }
        return new WrappedText(lines, maxLineWidth, lines.size() * lineHeight, lineHeight);
    }
    
    /**
     * Draw wrapped text, optionally centered within a given width.
     * 
     * @param g2        Graphics context
     * @param wrapped   WrappedText result from wrap()
     * @param x         Left edge X coordinate (or center X if centered)
     * @param y         Baseline Y of first line
     * @param boxWidth  Width to center within (0 = left-aligned, use wrapped.totalWidth for auto)
     * @param centered  If true, center each line within boxWidth
     */
    public static void draw(Graphics2D g2, WrappedText wrapped, int x, int y, int boxWidth, boolean centered) {
        FontMetrics fm = g2.getFontMetrics();
        int currentY = y;
        
        for (String line : wrapped.lines) {
            int drawX = x;
            
            if (centered && boxWidth > 0) {
                int lineWidth = fm.stringWidth(line);
                drawX = x + (boxWidth - lineWidth) / 2;
            }
            
            g2.drawString(line, drawX, currentY);
            currentY += wrapped.lineHeight;
        }
    }
    
    /**
     * Draw wrapped text centered below a point (common for element labels).
     * 
     * @param g2        Graphics context
     * @param wrapped   WrappedText result from wrap()
     * @param centerX   Center X coordinate
     * @param topY      Top Y coordinate (first line baseline will be topY + ascent)
     */
    public static void drawCenteredBelow(Graphics2D g2, WrappedText wrapped, int centerX, int topY) {
        FontMetrics fm = g2.getFontMetrics();
        int currentY = topY + fm.getAscent();
        
        for (String line : wrapped.lines) {
            int lineWidth = fm.stringWidth(line);
            int drawX = centerX - lineWidth / 2;
            g2.drawString(line, drawX, currentY);
            currentY += wrapped.lineHeight;
        }
    }
    
    /**
     * Convenience method: wrap and get lines only
     */
    public static List<String> wrapToLines(String text, FontMetrics fm, int maxWidth) {
        return wrap(text, fm, maxWidth).lines;
    }
    
    /**
     * Calculate the bounding rectangle for wrapped text
     * 
     * @param wrapped   WrappedText result
     * @param x         Left edge X (or starting X for centered)
     * @param y         Baseline Y of first line
     * @param fm        FontMetrics
     * @param boxWidth  Box width for centering (0 = use totalWidth)
     * @return Rectangle bounding the text
     */
    public static Rectangle getBounds(WrappedText wrapped, int x, int y, FontMetrics fm, int boxWidth) {
        int width = boxWidth > 0 ? boxWidth : wrapped.totalWidth;
        int top = y - fm.getAscent();
        return new Rectangle(x, top, width, wrapped.totalHeight);
    }
}