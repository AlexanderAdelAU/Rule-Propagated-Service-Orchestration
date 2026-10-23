package com.editor;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.UIManager;

/**
 * Read-only, collapsible legend for the catalogue that owns each place's selected service.
 * Kept as a child of Canvas in screen pixels, not workflow/zoom coordinates. It never
 * changes the process, catalogue or deployment.
 */
final class CatalogueStatusOverlay extends JComponent {
    static final int WIDTH = 300;
    static final int HEADER = 34;
    static final int SUBTITLE = 27;
    static final int ROW_HEIGHT = 25;
    static final int FOOTER = 6;
    private static final Color BORDER = new Color(190, 199, 209);
    private static final Color TEXT = new Color(35, 48, 61);
    private static final Color MUTED = new Color(91, 105, 121);

    static final class Entry {
        final String place;
        final String service;
        final String origin;
        final boolean standard;
        final boolean draft;
        final boolean unresolved;

        Entry(String place, String service, String origin, boolean standard, boolean draft, boolean unresolved) {
            this.place = place;
            this.service = service;
            this.origin = origin;
            this.standard = standard;
            this.draft = draft;
            this.unresolved = unresolved;
        }
    }

    /** Calculate from the live places and loaded contracts, never from a cached or copied catalogue. */
    static List<Entry> entries(Canvas canvas) {
        ServiceRegistry registry = canvas.getServiceRegistry();
        List<Entry> result = new ArrayList<>();
        String own = registry.catalogueFile() == null ? "Catalogue"
            : registry.catalogueFile().getName().replaceFirst("(?i)\\.json$", "");
        for (ProcessElement place : canvas.getPlaces()) {
            String service = place.getService() == null ? "" : place.getService().trim();
            List<String> operations = place.getOperations();
            boolean resolved = !service.isEmpty() && operations != null && !operations.isEmpty();
            if (resolved) {
                for (String operation : operations) {
                    if (registry.contract(service, operation) == null) {
                        resolved = false;
                        break;
                    }
                }
            }
            boolean standard = resolved && registry.isStandard(service);
            boolean draft = resolved && registry.isDraft(service);
            String origin = !resolved ? "Unresolved"
                : standard ? "Standard Service"
                : own + (draft ? " (draft)" : "");
            String label = place.getLabel();
            if (label == null || label.trim().isEmpty()) label = place.getId();
            result.add(new Entry(label, service, origin, standard, draft, !resolved));
        }
        return result;
    }

    private final Canvas canvas;
    private boolean collapsed;
    private int firstRow;

    CatalogueStatusOverlay(Canvas canvas) {
        this.canvas = canvas;
        setOpaque(false);
        setFocusable(true);
        setToolTipText("Click the heading to collapse or expand. Scroll here to see more places.");
        setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
        addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1 && e.getY() < HEADER) toggle();
            }
        });
        addMouseWheelListener(this::scroll);
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "toggle");
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "toggle");
        getActionMap().put("toggle", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { toggle(); }
        });
    }

    boolean isCollapsed() { return collapsed; }

    void setCollapsed(boolean value) {
        if (collapsed == value) return;
        collapsed = value;
        firstRow = 0;
        canvas.positionCatalogueStatus();
        repaint();
    }

    private void toggle() {
        requestFocusInWindow();
        setCollapsed(!collapsed);
    }

    private int visibleRows() {
        return Math.max(0, (getHeight() - HEADER - SUBTITLE - FOOTER) / ROW_HEIGHT);
    }

    private int maxFirstRow(int count) {
        return Math.max(0, count - visibleRows());
    }

    private void scroll(MouseWheelEvent event) {
        if (collapsed) return;
        int old = firstRow;
        firstRow = Math.max(0, Math.min(maxFirstRow(entries(canvas).size()),
            firstRow + event.getWheelRotation()));
        if (old != firstRow) repaint();
        event.consume();
    }

    @Override public String getToolTipText(MouseEvent event) {
        if (event.getY() < HEADER) return "Click to " + (collapsed ? "expand" : "collapse") + " catalogue status";
        if (collapsed || event.getY() < HEADER + SUBTITLE) return null;
        int index = firstRow + (event.getY() - HEADER - SUBTITLE) / ROW_HEIGHT;
        List<Entry> rows = entries(canvas);
        if (index < 0 || index >= rows.size() || index >= firstRow + visibleRows()) return null;
        Entry row = rows.get(index);
        return row.place + " — " + (row.service.isEmpty() ? "(no service selected)" : row.service)
            + " — " + row.origin;
    }

    private static String fit(FontMetrics metrics, String value, int width) {
        if (metrics.stringWidth(value) <= width) return value;
        String result = value;
        while (!result.isEmpty() && metrics.stringWidth(result + "…") > width)
            result = result.substring(0, result.length() - 1);
        return result + "…";
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int width = getWidth(), height = getHeight();
            g.setColor(new Color(255, 255, 255, 245));
            g.fillRoundRect(0, 0, width - 1, height - 1, 10, 10);
            g.setColor(BORDER);
            g.drawRoundRect(0, 0, width - 1, height - 1, 10, 10);

            Font base = UIManager.getFont("Label.font");
            if (base == null) base = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
            g.setFont(base.deriveFont(Font.BOLD, 12f));
            g.setColor(TEXT);
            g.drawString("Catalogue Status", 12, 22);
            g.drawString(collapsed ? "▸" : "▾", width - 22, 22);
            if (collapsed) return;

            ServiceRegistry registry = canvas.getServiceRegistry();
            String catalogue = registry.catalogueFile() == null ? "Not selected"
                : registry.catalogueFile().getName();
            g.setFont(base.deriveFont(Font.PLAIN, 11f));
            FontMetrics fm = g.getFontMetrics();
            g.setColor(MUTED);
            g.drawString(fit(fm, "Selected: " + catalogue, width - 24), 12, HEADER + 17);
            g.setColor(BORDER);
            g.drawLine(10, HEADER + SUBTITLE - 1, width - 10, HEADER + SUBTITLE - 1);

            List<Entry> rows = entries(canvas);
            firstRow = Math.max(0, Math.min(firstRow, maxFirstRow(rows.size())));
            if (rows.isEmpty()) {
                g.setColor(MUTED);
                g.drawString("No places in this workflow", 12, HEADER + SUBTITLE + 17);
                return;
            }
            int count = Math.min(visibleRows(), rows.size() - firstRow);
            for (int i = 0; i < count; i++) {
                Entry entry = rows.get(firstRow + i);
                int y = HEADER + SUBTITLE + i * ROW_HEIGHT;
                if ((i & 1) != 0) {
                    g.setColor(new Color(246, 248, 251));
                    g.fillRect(2, y, width - 4, ROW_HEIGHT);
                }
                g.setColor(entry.unresolved ? new Color(183, 107, 36)
                    : entry.standard ? new Color(101, 123, 148)
                    : entry.draft ? new Color(183, 107, 36) : new Color(55, 137, 140));
                g.fillOval(12, y + 9, 7, 7);

                g.setFont(base.deriveFont(Font.BOLD, 11f));
                FontMetrics labelMetrics = g.getFontMetrics();
                int labelWidth = Math.min(100, Math.max(48, (width - 40) / 3));
                g.setColor(TEXT);
                g.drawString(fit(labelMetrics, entry.place, labelWidth - 5), 25, y + 17);
                g.setFont(base.deriveFont(Font.PLAIN, 11f));
                g.drawString(fit(g.getFontMetrics(), "→ " + entry.origin, width - 30 - labelWidth),
                    25 + labelWidth, y + 17);
            }
            if (rows.size() > visibleRows()) {
                g.setColor(MUTED);
                g.setFont(base.deriveFont(Font.PLAIN, 9f));
                g.drawString((firstRow + 1) + "–" + (firstRow + count) + " of " + rows.size(),
                    width - 70, height - 3);
            }
        } finally {
            g.dispose();
        }
    }
}
