package org.btsn.healthcare.simulator;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.io.File;
import java.util.*;
import java.util.prefs.Preferences;
import javax.xml.parsers.*;
import org.w3c.dom.*;
import javax.xml.namespace.NamespaceContext;
import javax.xml.xpath.*;
import javax.xml.XMLConstants;

/**
 * PNML Viewer (PT nets, PNML 2009) with File menu, Zoom, and Last-Directory memory.
 *
 * Features:
 *  - Places (circles) with initial marking shown as a number
 *  - Transitions (rounded rectangles)
 *  - Arcs with weight labels (from <inscription><text>)
 *  - Uses PNML <graphics>/<position> if present; otherwise simple circle layout
 *  - File → Open… to choose a PNML file at runtime (plus drag & drop support)
 *  - File → Paste PNML to load PNML content from clipboard (Ctrl+V)
 *  - View → Zoom In / Zoom Out / Reset (Ctrl + + / Ctrl + - / Ctrl + 0)
 *  - Ctrl + Mouse Wheel = zoom
 *  - Remembers last-opened directory (per-user) using java.util.prefs
 *
 * Compile (Java 15+):
 *   javac PNMLViewer.java
 * Run:
 *   java PNMLViewer                # start empty, use File → Open…
 *   java PNMLViewer path	oile.pnml  # start with a file
 */
public class PNMLViewer {

    // -------------------- Model panel --------------------
    static class NetPanel extends JPanel {
        static class Node {
            String id, name;
            boolean place; // true=place, false=transition
            int tokens = 0;
            Point2D.Double pos = new Point2D.Double(Double.NaN, Double.NaN);
        }
        static class ArcE {
            String id, source, target;
            int weight = 1;
        }

        final Map<String, Node> nodes = new LinkedHashMap<>();
        final java.util.List<ArcE> arcs = new ArrayList<>();

        static final int P_RADIUS = 22;
        static final int T_W = 36, T_H = 18;

        // Zoom state
        private double zoom = 1.0;
        private static final double ZOOM_MIN = 0.2;
        private static final double ZOOM_MAX = 3.0;
        private static final double ZOOM_STEP = 1.1; // 10% per step

        NetPanel() {
            setBackground(Color.white);
            setTransferHandler(new FileDropHandler(this::loadFromFile));

            // Ctrl + mouse wheel zoom
            addMouseWheelListener(e -> {
                if ((e.getModifiersEx() & InputEvent.CTRL_DOWN_MASK) != 0) {
                    if (e.getWheelRotation() < 0) zoomIn(); else zoomOut();
                    e.consume();
                }
            });
        }

        double getZoom() { return zoom; }
        void setZoom(double z) {
            double nz = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, z));
            if (Math.abs(nz - zoom) > 1e-6) {
                zoom = nz;
                revalidate();
                repaint();
            }
        }
        void zoomIn() { setZoom(zoom * ZOOM_STEP); }
        void zoomOut() { setZoom(zoom / ZOOM_STEP); }
        void zoomReset() { setZoom(1.0); }

        void clear() {
            nodes.clear();
            arcs.clear();
        }

        void loadFromFile(File f) {
            if (f == null) return;
            try {
                DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
                dbf.setNamespaceAware(true);
                DocumentBuilder db = dbf.newDocumentBuilder();
                Document doc = db.parse(f);
                setModelFromDocument(doc);
                revalidate(); repaint();
            } catch (Exception ex) {
                ex.printStackTrace();
                JOptionPane.showMessageDialog(this, "Failed to open PNML:" + ex.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
            }
        }

        void setModelFromDocument(Document doc) {
            clear();
            try {
                XPathFactory xpf = XPathFactory.newInstance();
                XPath xp = xpf.newXPath();
                xp.setNamespaceContext(new NamespaceContext() {
                    public String getNamespaceURI(String prefix) {
                        if ("pnml".equals(prefix)) return "http://www.pnml.org/version-2009/grammar/pnml";
                        return XMLConstants.NULL_NS_URI;
                    }
                    public String getPrefix(String uri) { return null; }
                    public Iterator<String> getPrefixes(String uri) { return null; }
                });

                // places
                NodeList placeNodes = (NodeList) xp.evaluate("//pnml:place", doc, XPathConstants.NODESET);
                for (int i = 0; i < placeNodes.getLength(); i++) {
                    Element e = (Element) placeNodes.item(i);
                    Node n = new Node();
                    n.id = e.getAttribute("id");
                    n.place = true;
                    n.name = textOrEmpty(xp, "./pnml:name/pnml:text", e);
                    String mark = textOrEmpty(xp, "./pnml:initialMarking/pnml:text", e).trim();
                    if (!mark.isEmpty()) {
                        try { n.tokens = Integer.parseInt(mark); } catch (NumberFormatException ex) { n.tokens = 0; }
                    }
                    Point2D.Double pos = readPosition(xp, e);
                    if (pos != null) n.pos = pos;
                    nodes.put(n.id, n);
                }

                // transitions
                NodeList transNodes = (NodeList) xp.evaluate("//pnml:transition", doc, XPathConstants.NODESET);
                for (int i = 0; i < transNodes.getLength(); i++) {
                    Element e = (Element) transNodes.item(i);
                    Node n = new Node();
                    n.id = e.getAttribute("id");
                    n.place = false;
                    n.name = textOrEmpty(xp, "./pnml:name/pnml:text", e);
                    Point2D.Double pos = readPosition(xp, e);
                    if (pos != null) n.pos = pos;
                    nodes.put(n.id, n);
                }

                // arcs
                NodeList arcNodes = (NodeList) xp.evaluate("//pnml:arc", doc, XPathConstants.NODESET);
                for (int i = 0; i < arcNodes.getLength(); i++) {
                    Element e = (Element) arcNodes.item(i);
                    ArcE a = new ArcE();
                    a.id = e.getAttribute("id");
                    a.source = e.getAttribute("source");
                    a.target = e.getAttribute("target");
                    String w = textOrEmpty(xp, "./pnml:inscription/pnml:text", e).trim();
                    if (!w.isEmpty()) {
                        try { a.weight = Integer.parseInt(w); } catch (NumberFormatException ex) { a.weight = 1; }
                    }
                    arcs.add(a);
                }

                // fallback layout if no coordinates
                boolean hasAnyPos = nodes.values().stream().anyMatch(n -> !Double.isNaN(n.pos.x));
                if (!hasAnyPos) autoCircleLayout(getWidth() > 0 ? getWidth() : 1100,
                                                 getHeight() > 0 ? getHeight() : 800);

            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }

        private static String textOrEmpty(XPath xp, String expr, Element ctx) {
            try {
                String s = (String) xp.evaluate(expr, ctx, XPathConstants.STRING);
                return s == null ? "" : s;
            } catch (Exception e) { return ""; }
        }

        private static Point2D.Double readPosition(XPath xp, Element e) {
            String[] tries = new String[] {
                    ".//pnml:position",
                    ".//pnml:offset",
                    ".//pnml:graphics/pnml:position",
            };
            for (String t : tries) {
                try {
                    NodeList nl = (NodeList) xp.evaluate(t, e, XPathConstants.NODESET);
                    if (nl.getLength() > 0 && nl.item(0) instanceof Element) {
                        Element pos = (Element) nl.item(0);
                        String xs = pos.getAttribute("x"), ys = pos.getAttribute("y");
                        if (!xs.isEmpty() && !ys.isEmpty()) {
                            double x = Double.parseDouble(xs), y = Double.parseDouble(ys);
                            return new Point2D.Double(x, y);
                        }
                    }
                } catch (Exception ignore) {}
            }
            return null;
        }

        private void autoCircleLayout(int w, int h) {
            int n = nodes.size();
            if (n == 0) return;
            double R = Math.min(w, h) * 0.38;
            double cx = w * 0.5, cy = h * 0.5;

            java.util.List<Node> places = new ArrayList<>();
            java.util.List<Node> trans = new ArrayList<>();
            for (Node nd : nodes.values()) (nd.place ? places : trans).add(nd);

            java.util.List<Node> order = new ArrayList<>();
            int i = 0;
            while (i < places.size() || i < trans.size()) {
                if (i < places.size()) order.add(places.get(i));
                if (i < trans.size()) order.add(trans.get(i));
                i++;
            }

            int k = 0;
            for (Node nd : order) {
                double a = (2 * Math.PI * k) / n;
                nd.pos = new Point2D.Double(cx + R * Math.cos(a), cy + R * Math.sin(a));
                k++;
            }
        }

        @Override public Dimension getPreferredSize() { return new Dimension(1100, 800); }

        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            boolean needsAuto = nodes.values().stream().anyMatch(n -> Double.isNaN(n.pos.x));
            if (needsAuto) autoCircleLayout(getWidth(), getHeight());

            // Apply zoom
            g2.scale(zoom, zoom);

            // Draw arcs
            g2.setStroke(new BasicStroke(1.3f));
            g2.setColor(Color.darkGray);
            for (ArcE a : arcs) {
                Node s = nodes.get(a.source), t = nodes.get(a.target);
                if (s == null || t == null) continue;
                Point2D.Double p = s.pos, q = t.pos;
                if (Double.isNaN(p.x) || Double.isNaN(q.x)) continue;

                g2.draw(new Line2D.Double(p.x, p.y, q.x, q.y));
                drawArrowHead(g2, p.x, p.y, q.x, q.y);

                if (a.weight != 1) {
                    String lbl = String.valueOf(a.weight);
                    int lx = (int)((p.x + q.x)/2), ly = (int)((p.y + q.y)/2);
                    drawLabel(g2, lbl, lx, ly - 4, new Color(0,0,0,170));
                }
            }

            // Draw nodes
            for (Node n : nodes.values()) {
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

            g2.dispose();
        }

        private static void drawArrowHead(Graphics2D g2, double x1, double y1, double x2, double y2) {
            double dx = x2 - x1, dy = y2 - y1;
            double ang = Math.atan2(dy, dx);
            double len = Math.hypot(dx, dy);

            // How far to shorten the line (approx radius/half-height of target)
            double shorten = 20; // adjust if arrows still overlap
            double ux = Math.cos(ang), uy = Math.sin(ang);

            // Endpoint moved backward so arrowhead sits just before the shape
            double ex = x2 - shorten * ux;
            double ey = y2 - shorten * uy;

            // Draw shaft
            g2.draw(new Line2D.Double(x1, y1, ex, ey));

            // Arrowhead
            int headLen = 10;
            int headWidth = 6;
            double bx = ex - headLen * ux;
            double by = ey - headLen * uy;
            double ox = headWidth * Math.cos(ang + Math.PI / 2);
            double oy = headWidth * Math.sin(ang + Math.PI / 2);

            Polygon arrow = new Polygon();
            arrow.addPoint((int) ex, (int) ey);
            arrow.addPoint((int) (bx + ox), (int) (by + oy));
            arrow.addPoint((int) (bx - ox), (int) (by - oy));
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
            Shape bubble = new RoundRectangle2D.Double(x - w/2.0 - pad - 2, y - h + 2 - pad, w + 2*pad + 4, h + 2*pad, 8, 8);
            g2.setColor(new Color(255,255,255,220));
            g2.fill(bubble);
            g2.setColor(new Color(0,0,0,80));
            g2.draw(bubble);
            g2.setColor(c);
            g2.drawString(s, x - w/2, y + 1);
        }
    }

    // -------------------- Drag & Drop Handler --------------------
    static class FileDropHandler extends TransferHandler {
        private final java.util.function.Consumer<File> onFile;
        FileDropHandler(java.util.function.Consumer<File> onFile) { this.onFile = onFile; }
        @Override public boolean canImport(TransferSupport support) {
            return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
        }
        @Override public boolean importData(TransferSupport support) {
            try {
                java.util.List<?> list = (java.util.List<?>) support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                if (!list.isEmpty()) {
                    Object o = list.get(0);
                    if (o instanceof File) { onFile.accept((File)o); return true; }
                }
            } catch (Exception ignored) {}
            return false;
        }
    }

    // -------------------- App Frame --------------------
    private final JFrame frame;
    private final NetPanel panel;
    private final JLabel status;
    private final Preferences prefs = Preferences.userNodeForPackage(PNMLViewer.class);
    private static final String PREF_LAST_DIR = "lastDir";

    private PNMLViewer() {
        panel = new NetPanel();
        frame = new JFrame("PNML Viewer");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.setJMenuBar(buildMenuBar());
        frame.add(new JScrollPane(panel), BorderLayout.CENTER);
        status = new JLabel(" Zoom: 100% ");
        status.setBorder(BorderFactory.createEmptyBorder(4,10,4,10));
        frame.add(status, BorderLayout.SOUTH);
        frame.setSize(1100, 800);
        frame.setLocationByPlatform(true);

        // Update status on Ctrl+wheel zoom
        panel.addMouseWheelListener(e -> {
            if ((e.getModifiersEx() & InputEvent.CTRL_DOWN_MASK) != 0) updateStatusZoom();
        });
    }

   
    private JMenuBar buildMenuBar() {
        JMenuBar mb = new JMenuBar();

        // ---------- File ----------
        JMenu file = new JMenu("File");

        JMenuItem open = new JMenuItem("Open…");
        open.setAccelerator(KeyStroke.getKeyStroke('O',
                Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        open.addActionListener(e -> doOpen());
        file.add(open);

        file.addSeparator();

        JMenuItem exit = new JMenuItem("Exit");
        exit.addActionListener(e -> frame.dispose());
        file.add(exit);

        // ---------- Edit ----------
        JMenu edit = new JMenu("Edit");

        JMenuItem paste = new JMenuItem("Paste");
        paste.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_V,
                Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        paste.addActionListener(e -> doPaste());   // <-- requires doPaste() in PNMLViewer
        edit.add(paste);

        // ---------- View ----------
        JMenu view = new JMenu("View");

        JMenuItem zin = new JMenuItem("Zoom In");
        zin.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS,
                Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        zin.addActionListener(e -> { panel.zoomIn(); updateStatusZoom(); });
        view.add(zin);

        JMenuItem zout = new JMenuItem("Zoom Out");
        zout.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_MINUS,
                Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        zout.addActionListener(e -> { panel.zoomOut(); updateStatusZoom(); });
        view.add(zout);

        JMenuItem zreset = new JMenuItem("Reset Zoom");
        zreset.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_0,
                Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        zreset.addActionListener(e -> { panel.zoomReset(); updateStatusZoom(); });
        view.add(zreset);

        // ---------- Help ----------
        JMenu help = new JMenu("Help");

        JMenuItem about = new JMenuItem("About");
        about.addActionListener(e -> JOptionPane.showMessageDialog(
                frame,
                """
                PNML Viewer
                PT nets (PNML 2009)
                Open a .pnml or .xml via File → Open…
                Drag & drop supported.
                Zoom: Ctrl + Mouse Wheel / View menu.
                Paste: Ctrl + V (PNML text or a file path)
                """,
                "About",
                JOptionPane.INFORMATION_MESSAGE
        ));
        help.add(about);

        // ---------- Add menus ----------
        mb.add(file);
        mb.add(edit);
        mb.add(view);
        mb.add(help);
        return mb;
    }


    
    private void updateStatusZoom() {
        int pct = (int) Math.round(panel.getZoom() * 100.0);
        status.setText(" Zoom: " + pct + "% ");
    }

    private void doOpen() {
        JFileChooser fc = new JFileChooser();
        String last = prefs.get(PREF_LAST_DIR, null);
        if (last != null) {
            File d = new File(last);
            if (d.exists() && d.isDirectory()) fc.setCurrentDirectory(d);
        }
        fc.setFileFilter(new FileNameExtensionFilter("PNML / XML", "pnml", "xml"));
        int rv = fc.showOpenDialog(frame);
        if (rv == JFileChooser.APPROVE_OPTION) {
            File f = fc.getSelectedFile();
            panel.loadFromFile(f);
            frame.setTitle("PNML Viewer - " + f.getName());
            prefs.put(PREF_LAST_DIR, f.getParent());
        }
    }

    private void doPaste() {
        try {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            Transferable contents = clipboard.getContents(null);
            
            if (contents != null && contents.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                String pnmlContent = (String) contents.getTransferData(DataFlavor.stringFlavor);
                
                // Parse the PNML content
                DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
                dbf.setNamespaceAware(true);
                DocumentBuilder db = dbf.newDocumentBuilder();
                
                // Convert string to InputStream for parsing
                java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream(
                    pnmlContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                Document doc = db.parse(bis);
                
                // Load the document into the panel
                panel.setModelFromDocument(doc);
                panel.revalidate();
                panel.repaint();
                frame.setTitle("PNML Viewer - [From Clipboard]");
                
                JOptionPane.showMessageDialog(frame, 
                    "PNML content loaded successfully from clipboard!", 
                    "Paste Success", 
                    JOptionPane.INFORMATION_MESSAGE);
                    
            } else {
                JOptionPane.showMessageDialog(frame, 
                    "No text content found in clipboard.", 
                    "Paste Failed", 
                    JOptionPane.WARNING_MESSAGE);
            }
            
        } catch (Exception ex) {
            ex.printStackTrace();
            JOptionPane.showMessageDialog(frame, 
                "Failed to parse PNML from clipboard:\n" + ex.getMessage(), 
                "Paste Error", 
                JOptionPane.ERROR_MESSAGE);
        }
    }

    private void openInitial(String[] args) {
        if (args.length > 0) {
            File f = new File(args[0]);
            if (f.exists()) {
                panel.loadFromFile(f);
                frame.setTitle("PNML Viewer - " + f.getName());
                prefs.put(PREF_LAST_DIR, f.getParent());
            } else {
                JOptionPane.showMessageDialog(frame, "File not found:" + f.getAbsolutePath(),
                        "PNML Viewer", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            PNMLViewer app = new PNMLViewer();
            app.frame.setVisible(true);
            app.openInitial(args);
        });
    }
}