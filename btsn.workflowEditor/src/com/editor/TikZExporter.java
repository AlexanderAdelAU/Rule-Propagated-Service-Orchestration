package com.editor;

import java.util.List;

/**
 * Exports Petri Net to TikZ format for LaTeX
 */
public class TikZExporter {
    
    public static String export(List<ProcessElement> elements, List<Arrow> arrows) {
        StringBuilder tikz = new StringBuilder();
        
        // TikZ header
        tikz.append("\\begin{tikzpicture}[scale=0.05]\n");
        tikz.append("  % Petri Net exported from Petri Net Editor\n\n");
        
        // Define styles
        tikz.append("  % Styles\n");
        tikz.append("  \\tikzstyle{place}=[circle, draw=black, fill=white, minimum size=50pt, thick]\n");
        tikz.append("  \\tikzstyle{transition}=[rectangle, draw=black, fill=white, minimum width=20pt, minimum height=60pt, thick]\n");
        tikz.append("  \\tikzstyle{arrow}=[->, thick]\n\n");
        
        // Export places
        tikz.append("  % Places\n");
        for (ProcessElement element : elements) {
            if (element.getType() == ProcessElement.Type.PLACE) {
                tikz.append(String.format("  \\node[place] (%s) at (%d,%d) {%s};\n",
                    sanitizeId(element.getId()),
                    element.getX() + element.getWidth() / 2,
                    -(element.getY() + element.getHeight() / 2), // Flip Y axis
                    sanitizeLabel(element.getLabel())));
            }
        }
        
        tikz.append("\n  % Transitions\n");
        for (ProcessElement element : elements) {
            if (element.getType() == ProcessElement.Type.TRANSITION) {
                double rotation = element.getRotationAngle();
                tikz.append(String.format("  \\node[transition, rotate=%.1f] (%s) at (%d,%d) {%s};\n",
                    rotation,
                    sanitizeId(element.getId()),
                    element.getX() + element.getWidth() / 2,
                    -(element.getY() + element.getHeight() / 2), // Flip Y axis
                    sanitizeLabel(element.getLabel())));
            }
        }
        
        // Export arrows
        tikz.append("\n  % Arrows\n");
        for (Arrow arrow : arrows) {
            String sourceId = sanitizeId(arrow.getSource().getId());
            String targetId = sanitizeId(arrow.getTarget().getId());
            String label = arrow.getLabel();
            
            if (label != null && !label.isEmpty()) {
                tikz.append(String.format("  \\draw[arrow] (%s) -- node[midway, above, sloped] {%s} (%s);\n",
                    sourceId, sanitizeLabel(label), targetId));
            } else {
                tikz.append(String.format("  \\draw[arrow] (%s) -- (%s);\n",
                    sourceId, targetId));
            }
        }
        
        tikz.append("\\end{tikzpicture}\n");
        
        return tikz.toString();
    }
    
    private static String sanitizeId(String id) {
        // Replace spaces and special characters with underscores for node IDs
        return id.replaceAll("[^a-zA-Z0-9]", "_");
    }
    
    private static String sanitizeLabel(String label) {
        // Escape special LaTeX characters
        return label.replace("\\", "\\textbackslash")
                   .replace("_", "\\_")
                   .replace("&", "\\&")
                   .replace("%", "\\%")
                   .replace("$", "\\$")
                   .replace("#", "\\#")
                   .replace("{", "\\{")
                   .replace("}", "\\}")
                   .replace("~", "\\textasciitilde")
                   .replace("^", "\\textasciicircum");
    }
}