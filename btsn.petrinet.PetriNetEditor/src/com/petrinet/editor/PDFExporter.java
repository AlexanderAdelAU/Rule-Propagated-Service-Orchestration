package com.petrinet.editor;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

import com.itextpdf.text.Document;
import com.itextpdf.text.pdf.PdfContentByte;
import com.itextpdf.text.pdf.PdfWriter;


/**
 * Exports Petri Net to PDF format using iText
 * Note: Requires iTextPDF library (version 5.x) in classpath
 */
public class PDFExporter {
    
    public static void export(List<PetriNetElement> elements, List<Arrow> arrows, File outputFile) throws Exception {
        // Calculate bounds
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        
        for (PetriNetElement element : elements) {
            minX = Math.min(minX, element.getX());
            minY = Math.min(minY, element.getY());
            maxX = Math.max(maxX, element.getX() + element.getWidth());
            maxY = Math.max(maxY, element.getY() + element.getHeight());
        }
        
        // Add padding
        int padding = 50;
        minX -= padding;
        minY -= padding;
        maxX += padding;
        maxY += padding;
        
        int width = maxX - minX;
        int height = maxY - minY;
        
        // Create PDF document
        Document document = new Document(new com.itextpdf.text.Rectangle(width, height));
        PdfWriter writer = PdfWriter.getInstance(document, new FileOutputStream(outputFile));
        document.open();
        
        PdfContentByte cb = writer.getDirectContent();
        Graphics2D g2 = cb.createGraphics(width, height);
        
        // Enable antialiasing
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        
        // Translate to start at origin
        g2.translate(-minX, -minY);
        
        // Draw white background
        g2.setColor(Color.WHITE);
        g2.fillRect(minX, minY, width, height);
        
        // Draw arrows first (behind elements)
        for (Arrow arrow : arrows) {
            arrow.draw(g2, false);
        }
        
        // Draw elements
        for (PetriNetElement element : elements) {
            element.draw(g2, false);
        }
        
        g2.dispose();
        document.close();
    }
}