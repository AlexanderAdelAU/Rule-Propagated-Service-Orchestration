package com.editor;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOInvalidTreeException;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;

/**
 * Exports canvas content to PNG format with transparent background and configurable DPI.
 */
public class PNGExporter {
    
    // Standard DPI options
    public static final int DPI_72 = 72;    // Screen resolution
    public static final int DPI_150 = 150;  // Medium quality
    public static final int DPI_300 = 300;  // Print quality
    public static final int DPI_600 = 600;  // High quality print
    
    private static final int MARGIN = 20;   // Margin around content in pixels
    
    /**
     * Export canvas to PNG with default 300 DPI and transparent background.
     */
    public static void export(List<ProcessElement> elements, List<Arrow> arrows, 
                              List<TextElement> textElements, File outputFile) throws IOException {
        export(elements, arrows, textElements, outputFile, DPI_300, true);
    }
    
    /**
     * Export canvas to PNG with specified DPI and transparency option.
     * 
     * @param elements      List of process elements to render
     * @param arrows        List of arrows to render
     * @param textElements  List of text elements to render
     * @param outputFile    Output PNG file
     * @param dpi           DPI for output (72, 150, 300, or 600 recommended)
     * @param transparent   true for transparent background, false for white
     */
    public static void export(List<ProcessElement> elements, List<Arrow> arrows,
                              List<TextElement> textElements, File outputFile, 
                              int dpi, boolean transparent) throws IOException {
        
        if (elements.isEmpty() && textElements.isEmpty()) {
            throw new IllegalArgumentException("Nothing to export - canvas is empty");
        }
        
        // Calculate bounding box of all content
        Rectangle bounds = calculateBounds(elements, arrows, textElements);
        
        // Calculate scale factor based on DPI (base is 72 DPI)
        double scale = dpi / 72.0;
        
        // Create image with scaled dimensions
        int imageWidth = (int) Math.ceil((bounds.width + 2 * MARGIN) * scale);
        int imageHeight = (int) Math.ceil((bounds.height + 2 * MARGIN) * scale);
        
        // Use ARGB for transparency support
        int imageType = transparent ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage image = new BufferedImage(imageWidth, imageHeight, imageType);
        
        Graphics2D g2 = image.createGraphics();
        
        try {
            // Set up high-quality rendering
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            
            // Fill background (transparent or white)
            if (!transparent) {
                g2.setColor(Color.WHITE);
                g2.fillRect(0, 0, imageWidth, imageHeight);
            }
            // For transparent, leave background clear (default for ARGB)
            
            // Apply scale transformation for DPI
            g2.scale(scale, scale);
            
            // Translate to account for margin and content offset
            g2.translate(MARGIN - bounds.x, MARGIN - bounds.y);
            
            // Draw arrows first (behind elements)
            for (Arrow arrow : arrows) {
                arrow.draw(g2, false);
            }
            
            // Draw elements
            for (ProcessElement element : elements) {
                element.draw(g2, false);
            }
            
            // Draw text elements
            for (TextElement textElement : textElements) {
                textElement.draw(g2, false);
            }
            
        } finally {
            g2.dispose();
        }
        
        // Write PNG with DPI metadata
        writePNGWithDPI(image, outputFile, dpi);
    }
    
    /**
     * Calculate the bounding box that contains all elements, arrows, and text.
     */
    private static Rectangle calculateBounds(List<ProcessElement> elements, 
                                              List<Arrow> arrows,
                                              List<TextElement> textElements) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        
        // Include element bounds
        for (ProcessElement element : elements) {
            minX = Math.min(minX, element.getX());
            minY = Math.min(minY, element.getY());
            maxX = Math.max(maxX, element.getX() + element.getWidth());
            maxY = Math.max(maxY, element.getY() + element.getHeight());
        }
        
        // Include arrow waypoints
        for (Arrow arrow : arrows) {
            for (Point wp : arrow.getWaypoints()) {
                minX = Math.min(minX, wp.x - 10);
                minY = Math.min(minY, wp.y - 10);
                maxX = Math.max(maxX, wp.x + 10);
                maxY = Math.max(maxY, wp.y + 10);
            }
            // Include arrow labels if they have custom offsets
            Point labelOffset = arrow.getLabelOffset();
            if (labelOffset != null) {
                Point mid = getArrowMidpoint(arrow);
                minX = Math.min(minX, mid.x + labelOffset.x - 50);
                minY = Math.min(minY, mid.y + labelOffset.y - 20);
                maxX = Math.max(maxX, mid.x + labelOffset.x + 50);
                maxY = Math.max(maxY, mid.y + labelOffset.y + 20);
            }
        }
        
        // Include text element bounds
        for (TextElement text : textElements) {
            minX = Math.min(minX, text.getX() - 5);
            minY = Math.min(minY, text.getY() - 20);
            // Estimate text width (rough approximation)
            int textWidth = text.getText().length() * 8;
            maxX = Math.max(maxX, text.getX() + textWidth);
            maxY = Math.max(maxY, text.getY() + 10);
        }
        
        // Handle empty case
        if (minX == Integer.MAX_VALUE) {
            return new Rectangle(0, 0, 100, 100);
        }
        
        return new Rectangle(minX, minY, maxX - minX, maxY - minY);
    }
    
    /**
     * Get the midpoint of an arrow (for label positioning estimation).
     */
    private static Point getArrowMidpoint(Arrow arrow) {
        Point source = arrow.getSource().getCenter();
        Point target = arrow.getTarget().getCenter();
        
        List<Point> waypoints = arrow.getWaypoints();
        if (waypoints.isEmpty()) {
            return new Point((source.x + target.x) / 2, (source.y + target.y) / 2);
        } else {
            // Use middle waypoint
            Point mid = waypoints.get(waypoints.size() / 2);
            return new Point(mid.x, mid.y);
        }
    }
    
    /**
     * Write PNG image with DPI metadata embedded in the file.
     */
    private static void writePNGWithDPI(BufferedImage image, File outputFile, int dpi) 
            throws IOException {
        
        ImageWriter writer = ImageIO.getImageWritersByFormatName("png").next();
        ImageWriteParam writeParam = writer.getDefaultWriteParam();
        
        ImageTypeSpecifier typeSpecifier = 
            ImageTypeSpecifier.createFromBufferedImageType(image.getType());
        
        IIOMetadata metadata = writer.getDefaultImageMetadata(typeSpecifier, writeParam);
        
        // Set DPI in metadata
        setDPI(metadata, dpi);
        
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(outputFile)) {
            writer.setOutput(stream);
            writer.write(metadata, new IIOImage(image, null, metadata), writeParam);
        } finally {
            writer.dispose();
        }
    }
    
    /**
     * Set DPI in PNG metadata (pHYs chunk).
     */
    private static void setDPI(IIOMetadata metadata, int dpi) throws IIOInvalidTreeException {
        // Convert DPI to dots per meter (PNG uses meters)
        double dotsPerMeter = dpi / 0.0254;
        
        String metaFormat = "javax_imageio_png_1.0";
        IIOMetadataNode root = new IIOMetadataNode(metaFormat);
        
        IIOMetadataNode pHYs = new IIOMetadataNode("pHYs");
        pHYs.setAttribute("pixelsPerUnitXAxis", String.valueOf((int) dotsPerMeter));
        pHYs.setAttribute("pixelsPerUnitYAxis", String.valueOf((int) dotsPerMeter));
        pHYs.setAttribute("unitSpecifier", "meter");
        
        root.appendChild(pHYs);
        
        metadata.mergeTree(metaFormat, root);
    }
    
    /**
     * Show export dialog and handle export.
     * Returns the selected file if export was successful, null otherwise.
     */
    public static File showExportDialog(java.awt.Component parent,
                                         List<ProcessElement> elements,
                                         List<Arrow> arrows,
                                         List<TextElement> textElements,
                                         File lastDirectory) {
        
        if (elements.isEmpty() && textElements.isEmpty()) {
            javax.swing.JOptionPane.showMessageDialog(parent,
                "Canvas is empty! Add some elements first.",
                "Nothing to Export",
                javax.swing.JOptionPane.INFORMATION_MESSAGE);
            return null;
        }
        
        // Create options panel
        javax.swing.JPanel optionsPanel = new javax.swing.JPanel(new java.awt.GridLayout(0, 2, 5, 5));
        
        // DPI selection
        optionsPanel.add(new javax.swing.JLabel("Resolution (DPI):"));
        String[] dpiOptions = {"72 (Screen)", "150 (Medium)", "300 (Print)", "600 (High Quality)"};
        javax.swing.JComboBox<String> dpiCombo = new javax.swing.JComboBox<>(dpiOptions);
        dpiCombo.setSelectedIndex(2); // Default to 300 DPI
        optionsPanel.add(dpiCombo);
        
        // Transparency option
        optionsPanel.add(new javax.swing.JLabel("Background:"));
        String[] bgOptions = {"Transparent", "White"};
        javax.swing.JComboBox<String> bgCombo = new javax.swing.JComboBox<>(bgOptions);
        bgCombo.setSelectedIndex(0); // Default to transparent
        optionsPanel.add(bgCombo);
        
        // Show options dialog
        int result = javax.swing.JOptionPane.showConfirmDialog(parent, optionsPanel,
            "PNG Export Options", javax.swing.JOptionPane.OK_CANCEL_OPTION,
            javax.swing.JOptionPane.PLAIN_MESSAGE);
        
        if (result != javax.swing.JOptionPane.OK_OPTION) {
            return null;
        }
        
        // Parse selected options
        int dpi;
        switch (dpiCombo.getSelectedIndex()) {
            case 0: dpi = DPI_72; break;
            case 1: dpi = DPI_150; break;
            case 2: dpi = DPI_300; break;
            case 3: dpi = DPI_600; break;
            default: dpi = DPI_300;
        }
        
        boolean transparent = bgCombo.getSelectedIndex() == 0;
        
        // File chooser
        javax.swing.JFileChooser fileChooser = new javax.swing.JFileChooser();
        if (lastDirectory != null) {
            fileChooser.setCurrentDirectory(lastDirectory);
        }
        fileChooser.setDialogTitle("Export to PNG");
        fileChooser.setSelectedFile(new File("diagram.png"));
        fileChooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
            "PNG Images (*.png)", "png"));
        
        int saveResult = fileChooser.showSaveDialog(parent);
        if (saveResult != javax.swing.JFileChooser.APPROVE_OPTION) {
            return null;
        }
        
        File file = fileChooser.getSelectedFile();
        
        // Ensure .png extension
        if (!file.getName().toLowerCase().endsWith(".png")) {
            file = new File(file.getAbsolutePath() + ".png");
        }
        
        // Confirm overwrite if file exists
        if (file.exists()) {
            int overwrite = javax.swing.JOptionPane.showConfirmDialog(parent,
                "File already exists. Overwrite?",
                "Confirm Overwrite",
                javax.swing.JOptionPane.YES_NO_OPTION);
            if (overwrite != javax.swing.JOptionPane.YES_OPTION) {
                return null;
            }
        }
        
        // Perform export
        try {
            export(elements, arrows, textElements, file, dpi, transparent);
            
            // Calculate output size for info message
            double scale = dpi / 72.0;
            Rectangle bounds = calculateBounds(elements, arrows, textElements);
            int width = (int) Math.ceil((bounds.width + 2 * MARGIN) * scale);
            int height = (int) Math.ceil((bounds.height + 2 * MARGIN) * scale);
            
            javax.swing.JOptionPane.showMessageDialog(parent,
                "Exported successfully!\n\n" +
                "File: " + file.getName() + "\n" +
                "Size: " + width + " x " + height + " pixels\n" +
                "DPI: " + dpi + "\n" +
                "Background: " + (transparent ? "Transparent" : "White"),
                "Export Successful",
                javax.swing.JOptionPane.INFORMATION_MESSAGE);
            
            return file.getParentFile();
            
        } catch (IOException ex) {
            javax.swing.JOptionPane.showMessageDialog(parent,
                "Error exporting to PNG: " + ex.getMessage(),
                "Export Error",
                javax.swing.JOptionPane.ERROR_MESSAGE);
            return null;
        }
    }
}