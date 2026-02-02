
package com.pnmlviewer;

import com.pnmlviewer.io.PnmlParser;
import com.pnmlviewer.model.PnmlModel;
import com.pnmlviewer.ui.NetPanel;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.KeyEvent;
import java.io.File;
import java.util.prefs.Preferences;

public class PNMLViewerApp {

    private final JFrame frame;
    private final NetPanel panel;
    private final JLabel status;
    private final Preferences prefs = Preferences.userNodeForPackage(PNMLViewerApp.class);
    private static final String PREF_LAST_DIR = "lastDir";

    public PNMLViewerApp() {
        this.panel = new NetPanel();
        this.frame = new JFrame("PNML Viewer");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.setJMenuBar(buildMenuBar());
        frame.add(new JScrollPane(panel), BorderLayout.CENTER);
        this.status = new JLabel(" Zoom: 100% ");
        status.setBorder(BorderFactory.createEmptyBorder(4,10,4,10));
        frame.add(status, BorderLayout.SOUTH);
        frame.setSize(1100, 800);
        frame.setLocationByPlatform(true);
        panel.setZoomChangedCallback(() -> updateStatusZoom());
        installDragAndDrop();
    }

    private void installDragAndDrop() {
        TransferHandler handler = new TransferHandler() {
            @Override public boolean canImport(TransferSupport support) {
                return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
                        || support.isDataFlavorSupported(DataFlavor.stringFlavor);
            }
            @Override public boolean importData(TransferSupport support) {
                try {
                    if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        java.util.List<?> list = (java.util.List<?>) support.getTransferable()
                                .getTransferData(DataFlavor.javaFileListFlavor);
                        if (!list.isEmpty() && list.get(0) instanceof File) {
                            openFile((File) list.get(0));
                            return true;
                        }
                    }
                    if (support.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                        String s = ((String) support.getTransferable()
                                .getTransferData(DataFlavor.stringFlavor)).trim();
                        if (s.isEmpty()) return false;
                        if (s.startsWith("<")) {
                            PnmlModel model = PnmlParser.parseString(s);
                            panel.setModel(model);
                            frame.setTitle("PNML Viewer - (dropped text)");
                            return true;
                        }
                        File f = new File(s);
                        if (f.exists() && f.isFile()) { openFile(f); return true; }
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                    JOptionPane.showMessageDialog(frame, "Drop failed:\n" + ex.getMessage(),
                            "Drag & Drop", JOptionPane.ERROR_MESSAGE);
                }
                return false;
            }
        };
        frame.setTransferHandler(handler);
        panel.setTransferHandler(handler);
    }

    private void updateStatusZoom() {
        int pct = (int)Math.round(panel.getZoom() * 100.0);
        status.setText(" Zoom: " + pct + "% ");
    }

    private JMenuBar buildMenuBar() {
        JMenuBar mb = new JMenuBar();

        // File
        JMenu file = new JMenu("File");
        JMenuItem open = new JMenuItem("Open…");
        open.setAccelerator(KeyStroke.getKeyStroke('O', Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        open.addActionListener(e -> doOpen());
        file.add(open);
        file.addSeparator();
        JMenuItem saveLayout = new JMenuItem("Save Layout As…");
        saveLayout.addActionListener(e -> doSaveLayoutAs());
        file.add(saveLayout);
        file.addSeparator();
        JMenuItem exit = new JMenuItem("Exit");
        exit.addActionListener(e -> frame.dispose());
        file.add(exit);

        // Edit
        JMenu edit = new JMenu("Edit");
        JMenuItem paste = new JMenuItem("Paste");
        paste.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_V, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        paste.addActionListener(e -> doPaste());
        edit.add(paste);

        // View
        JMenu view = new JMenu("View");
        JMenuItem zin = new JMenuItem("Zoom In");
        zin.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        zin.addActionListener(e -> panel.zoomIn());
        JMenuItem zout = new JMenuItem("Zoom Out");
        zout.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        zout.addActionListener(e -> panel.zoomOut());
        JMenuItem zreset = new JMenuItem("Reset Zoom");
        zreset.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_0, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        zreset.addActionListener(e -> panel.zoomReset());
        view.add(zin); view.add(zout); view.add(zreset);

        // Arcs (curve mode selector)
        JMenu arcs = new JMenu("Arcs");
        ButtonGroup bg = new ButtonGroup();

        JRadioButtonMenuItem m1 = new JRadioButtonMenuItem("Curve T→P only (feedback)");
        m1.setSelected(true);
        m1.addActionListener(e -> panel.setCurveMode(com.pnmlviewer.ui.NetPanel.CurveMode.T_TO_P_ONLY));
        bg.add(m1); arcs.add(m1);

        JRadioButtonMenuItem m2 = new JRadioButtonMenuItem("Curve P→T only");
        m2.addActionListener(e -> panel.setCurveMode(com.pnmlviewer.ui.NetPanel.CurveMode.P_TO_T_ONLY));
        bg.add(m2); arcs.add(m2);

        JRadioButtonMenuItem m3 = new JRadioButtonMenuItem("Curve both (when antiparallel)");
        m3.addActionListener(e -> panel.setCurveMode(com.pnmlviewer.ui.NetPanel.CurveMode.BOTH_CURVED));
        bg.add(m3); arcs.add(m3);

        JRadioButtonMenuItem m4 = new JRadioButtonMenuItem("No curves");
        m4.addActionListener(e -> panel.setCurveMode(com.pnmlviewer.ui.NetPanel.CurveMode.NONE));
        bg.add(m4); arcs.add(m4);

        view.addSeparator();
        view.add(arcs);

        // Layout
        JMenu layout = new JMenu("Layout");
        JMenuItem lr = new JMenuItem("Left → Right (simple)");
        lr.addActionListener(e -> panel.layoutLeftToRight());
        layout.add(lr);

        // Help
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
                "About", JOptionPane.INFORMATION_MESSAGE
        ));
        help.add(about);

        mb.add(file);
        mb.add(edit);
        mb.add(view);
        mb.add(layout);
        mb.add(help);
        return mb;
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
            openFile(fc.getSelectedFile());
        }
    }

    private void openFile(File f) {
        try {
            PnmlModel model = PnmlParser.parseFile(f);
            panel.setModel(model);
            frame.setTitle("PNML Viewer - " + f.getName());
            prefs.put(PREF_LAST_DIR, f.getParent());
        } catch (Exception ex) {
            ex.printStackTrace();
            JOptionPane.showMessageDialog(frame, "Failed to open PNML:\n" + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void doSaveLayoutAs() {
        if (panel.getModel() == null) {
            JOptionPane.showMessageDialog(frame, "No model loaded.", "Save Layout", JOptionPane.WARNING_MESSAGE);
            return;
        }
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Save PNML with Layout");
        fc.setSelectedFile(new File("pnml-with-layout.xml"));
        int rv = fc.showSaveDialog(frame);
        if (rv == JFileChooser.APPROVE_OPTION) {
            try {
                com.pnmlviewer.io.PnmlParser.writeWithPositions(panel.getModel(), fc.getSelectedFile());
                JOptionPane.showMessageDialog(frame, "Saved: \n" + fc.getSelectedFile().getAbsolutePath(),
                        "Save Layout", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
                ex.printStackTrace();
                JOptionPane.showMessageDialog(frame, "Save failed:\n" + ex.getMessage(),
                        "Save Layout", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void doPaste() {
        try {
            Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
            Transferable t = cb.getContents(null);

            if (t != null && t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                java.util.List<?> list = (java.util.List<?>) t.getTransferData(DataFlavor.javaFileListFlavor);
                if (!list.isEmpty() && list.get(0) instanceof File) { openFile((File) list.get(0)); return; }
            }
            if (t != null && t.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                String s = ((String) t.getTransferData(DataFlavor.stringFlavor)).trim();
                if (s.isEmpty()) return;
                if (s.startsWith("<")) {
                    PnmlModel model = PnmlParser.parseString(s);
                    panel.setModel(model);
                    frame.setTitle("PNML Viewer - (pasted)");
                    return;
                }
                File f = new File(s);
                if (f.exists() && f.isFile()) { openFile(f); return; }
                JOptionPane.showMessageDialog(frame, "Clipboard text is not PNML and not a valid file path.",
                        "Paste", JOptionPane.WARNING_MESSAGE);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
            JOptionPane.showMessageDialog(frame, "Paste failed:\n" + ex.getMessage(),
                    "Paste", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void openInitial(String[] args) {
        if (args.length > 0) {
            File f = new File(args[0]);
            if (f.exists()) openFile(f);
            else JOptionPane.showMessageDialog(frame, "File not found:\n" + f.getAbsolutePath(),
                    "PNML Viewer", JOptionPane.ERROR_MESSAGE);
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            PNMLViewerApp app = new PNMLViewerApp();
            app.frame.setVisible(true);
            app.openInitial(args);
        });
    }
}
