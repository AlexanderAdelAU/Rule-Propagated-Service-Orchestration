package com.editor;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;

/** Launch the actual editor in a graphical display and verify Graphviz export is gone. */
public final class EditorGraphvizRemovalCheck {
    public static void main(String[] args) throws Exception {
        final Throwable[] failure = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> {
            ProcessEditor editor = null;
            try {
                editor = new ProcessEditor();
                editor.setVisible(true);
                check(editor.isShowing(), "Editor window did not become visible");
                check("Petri Net Editor".equals(editor.getTitle()), "Wrong editor title: " + editor.getTitle());

                JMenuBar menuBar = editor.getJMenuBar();
                check(menuBar != null, "Missing editor menu bar");
                JMenu fileMenu = menuBar.getMenu(0);
                check(fileMenu != null && "File".equals(fileMenu.getText()), "Missing File menu");
                boolean tikz = false, pdf = false, png = false;
                for (int i = 0; i < fileMenu.getItemCount(); i++) {
                    JMenuItem item = fileMenu.getItem(i);
                    if (item == null) continue;
                    String name = item.getText();
                    check(name == null || !name.toLowerCase().contains("graphviz"),
                          "Obsolete Graphviz File menu item still present: " + name);
                    if (item.getAccelerator() != null) {
                        check(item.getAccelerator().getKeyCode() != java.awt.event.KeyEvent.VK_E,
                              "Obsolete Ctrl+E export shortcut still registered");
                    }
                    tikz |= "Export to TikZ (.tex)".equals(name);
                    pdf |= "Export to PDF (.pdf)".equals(name);
                    png |= "Export to PNG (.png)".equals(name);
                }
                check(tikz && pdf && png, "TikZ/PDF/PNG exports must remain");

                // Verify title changes for an unsaved edit, too (the title is updated dynamically).
                Method setDirty = ProcessEditor.class.getDeclaredMethod("setDirty", boolean.class);
                setDirty.setAccessible(true);
                setDirty.invoke(editor, true);
                check("Petri Net Editor *".equals(editor.getTitle()),
                      "Dirty title incorrectly mentions an export: " + editor.getTitle());
                setDirty.invoke(editor, false);

                // Render the actual visible editor to an image for a human smoke-check.
                if (args.length > 0) {
                    Path output = Path.of(args[0]).toAbsolutePath();
                    Files.createDirectories(output.getParent());
                    BufferedImage picture = new BufferedImage(
                            editor.getWidth(), editor.getHeight(), BufferedImage.TYPE_INT_RGB);
                    Graphics2D graphics = picture.createGraphics();
                    try { editor.paint(graphics); } finally { graphics.dispose(); }
                    check(ImageIO.write(picture, "png", output.toFile()),
                          "Could not write editor screenshot");
                }
            } catch (Throwable e) {
                failure[0] = e;
            } finally {
                if (editor != null) editor.dispose();
            }
        });
        if (failure[0] != null) throw new AssertionError("Editor GUI launch and Graphviz removal", failure[0]);
        System.out.println("PASS: launched the real editor GUI; title and dirty title clean; Graphviz item/shortcut removed; TikZ, PDF and PNG remain");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
