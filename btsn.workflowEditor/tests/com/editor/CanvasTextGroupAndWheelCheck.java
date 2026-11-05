package com.editor;

import java.awt.Dimension;
import java.awt.Point;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.lang.reflect.Field;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

/**
 * Headless regression check for selecting and dragging text groups and
 * scrolling the ProcessEditor canvas with the mouse wheel.
 */
public final class CanvasTextGroupAndWheelCheck {
    public static void main(String[] args) throws Exception {
        Throwable[] failure = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                checkTextGrouping();
                checkWheelScrolling();
                checkPointerAnchoredZoom();
                checkEdgeAnchoredZoom();
            } catch (Throwable error) {
                failure[0] = error;
            }
        });
        if (failure[0] != null) throw new AssertionError("Canvas text group / wheel", failure[0]);
        System.out.println("PASS: text groups, wheel scrolling, and Ctrl+wheel zoom at canvas centre and edges");
    }

    private static void checkTextGrouping() {
        Canvas canvas = new Canvas();
        TextElement a = new TextElement(120, 100, "First line");
        TextElement b = new TextElement(120, 140, "Second line");
        TextElement c = new TextElement(120, 180, "Third line");
        canvas.getTextElements().add(a);
        canvas.getTextElements().add(b);
        canvas.getTextElements().add(c);

        // Ctrl+click all three, then drag one: each should retain its offset.
        ctrlClick(canvas, 125, 96);
        ctrlClick(canvas, 125, 136);
        ctrlClick(canvas, 125, 176);
        drag(canvas, 125, 96, 163, 121);
        checkPosition(a, 158, 125);
        checkPosition(b, 158, 165);
        checkPosition(c, 158, 205);

        // Multi-selection must also be honoured by Copy/Paste.
        canvas.copySelection();
        canvas.pasteFromClipboard();
        check(canvas.getTextElements().size() == 6, "Copy/Paste should duplicate all three annotations");

        // Clicking an unselected original text replaces the pasted group's selection.
        int bx = b.getX(), by = b.getY();
        int ax = a.getX(), ay = a.getY();
        drag(canvas, ax + 5, ay - 4, ax + 22, ay + 5);
        checkPosition(a, ax + 17, ay + 9);
        checkPosition(b, bx, by);

        // Start a fresh document and verify rubber-band-selected text can be dragged.
        canvas.clear();
        a = new TextElement(120, 100, "A");
        b = new TextElement(120, 140, "B");
        c = new TextElement(120, 180, "C");
        canvas.getTextElements().add(a);
        canvas.getTextElements().add(b);
        canvas.getTextElements().add(c);
        drag(canvas, 90, 60, 210, 210); // empty-space rubber band
        drag(canvas, 123, 96, 143, 109);
        checkPosition(a, 140, 113);
        checkPosition(b, 140, 153);
        checkPosition(c, 140, 193);
    }

    private static void checkWheelScrolling() {
        Canvas canvas = new Canvas();
        JScrollPane pane = new JScrollPane(canvas);
        canvas.setPreferredSize(new Dimension(1600, 1200));
        pane.setSize(420, 300);
        pane.doLayout();
        check(pane.getVerticalScrollBar().isVisible(), "Expected vertical scrollbar");
        check(pane.getHorizontalScrollBar().isVisible(), "Expected horizontal scrollbar");

        wheel(canvas, 0, 2);
        check(pane.getVerticalScrollBar().getValue() > 0, "Wheel did not scroll vertically");
        int vertical = pane.getVerticalScrollBar().getValue();
        wheel(canvas, InputEvent.SHIFT_DOWN_MASK, 2);
        check(pane.getHorizontalScrollBar().getValue() > 0, "Shift+wheel did not scroll horizontally");
        check(pane.getVerticalScrollBar().getValue() == vertical,
              "Shift+wheel should not change vertical scroll");

        double oldZoom = canvas.getZoomScale();
        wheel(canvas, InputEvent.CTRL_DOWN_MASK, -1);
        check(canvas.getZoomScale() > oldZoom, "Ctrl+wheel should still zoom in");
    }

    /**
     * Large diagrams must zoom around the pointer, even after scrolling far
     * from the origin. Repeated wheel events must not accumulate anchor drift.
     */
    private static void checkPointerAnchoredZoom() {
        Canvas canvas = largeCanvas();
        JScrollPane pane = scrollPane(canvas);
        pane.getViewport().setViewPosition(new Point(1200, 900));

        Point offset = new Point(350, 215); // deliberately not viewport centre
        checkAnchoredWheel(canvas, pane, offset, -1);
        checkAnchoredWheel(canvas, pane, offset, -1);
        checkAnchoredWheel(canvas, pane, offset, 1);
        checkAnchoredWheel(canvas, pane, offset, 1);
        check(Math.abs(canvas.getZoomScale() - 1.0) < 1e-8,
              "Zoom in/out should return to 100%");
    }

    private static void checkEdgeAnchoredZoom() {
        Canvas canvas = largeCanvas();
        JScrollPane pane = scrollPane(canvas);
        Point offset = new Point(350, 215);

        // At the far right/bottom, the old canvas-size calculation ran out
        // of scroll range when zooming out, moving content to the right.
        Dimension canvasSize = canvas.getPreferredSize();
        Dimension extent = pane.getViewport().getExtentSize();
        pane.getViewport().setViewPosition(new Point(
            Math.max(0, canvasSize.width - extent.width - 20),
            Math.max(0, canvasSize.height - extent.height - 20)));
        checkAnchoredWheel(canvas, pane, offset, 1);
        checkAnchoredWheel(canvas, pane, offset, 1);
        checkAnchoredWheel(canvas, pane, offset, -1);
        checkAnchoredWheel(canvas, pane, offset, -1);

        // At the left/top edge, panning into negative viewport coordinates
        // is impossible. View-only origin space must preserve the anchor.
        pane.getViewport().setViewPosition(new Point(0, 0));
        checkAnchoredWheel(canvas, pane, offset, 1);
        checkAnchoredWheel(canvas, pane, offset, 1);
        checkAnchoredWheel(canvas, pane, offset, -1);
        checkAnchoredWheel(canvas, pane, offset, -1);
    }

    private static Canvas largeCanvas() {
        Canvas canvas = new Canvas();
        canvas.getTextElements().add(new TextElement(3000, 2400, "Far corner"));
        return canvas;
    }

    private static JScrollPane scrollPane(Canvas canvas) {
        JScrollPane pane = new JScrollPane(canvas);
        pane.setSize(520, 360);
        pane.doLayout();
        canvas.setZoomScale(1.1);
        canvas.setZoomScale(1.0);
        pane.doLayout();
        return pane;
    }

    private static void checkAnchoredWheel(Canvas canvas, JScrollPane pane,
                                            Point pointerOffset, int rotation) {
        double[] before = modelPointAtCursor(canvas, pane, pointerOffset);
        Point view = pane.getViewport().getViewPosition();
        wheel(canvas, InputEvent.CTRL_DOWN_MASK, rotation,
              view.x + pointerOffset.x, view.y + pointerOffset.y);
        pane.doLayout();
        pane.getViewport().doLayout();
        double[] after = modelPointAtCursor(canvas, pane, pointerOffset);
        check(Math.abs(before[0] - after[0]) <= 1.1 &&
              Math.abs(before[1] - after[1]) <= 1.1,
              "Ctrl+wheel moved the model point under the cursor: before ("
                  + before[0] + ", " + before[1] + "), after ("
                  + after[0] + ", " + after[1] + ")");
    }

    private static double[] modelPointAtCursor(Canvas canvas, JScrollPane pane, Point offset) {
        try {
            Field originX = Canvas.class.getDeclaredField("viewOriginX");
            Field originY = Canvas.class.getDeclaredField("viewOriginY");
            originX.setAccessible(true);
            originY.setAccessible(true);
            Point view = pane.getViewport().getViewPosition();
            double zoom = canvas.getZoomScale();
            return new double[] {
                (view.x + offset.x - originX.getInt(canvas)) / zoom,
                (view.y + offset.y - originY.getInt(canvas)) / zoom
            };
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Could not inspect canvas view origin", error);
        }
    }

    private static void ctrlClick(Canvas canvas, int x, int y) {
        mouse(canvas, MouseEvent.MOUSE_PRESSED, x, y, InputEvent.CTRL_DOWN_MASK);
        mouse(canvas, MouseEvent.MOUSE_RELEASED, x, y, InputEvent.CTRL_DOWN_MASK);
    }

    private static void drag(Canvas canvas, int fromX, int fromY, int toX, int toY) {
        mouse(canvas, MouseEvent.MOUSE_PRESSED, fromX, fromY, 0);
        mouse(canvas, MouseEvent.MOUSE_DRAGGED, toX, toY, InputEvent.BUTTON1_DOWN_MASK);
        mouse(canvas, MouseEvent.MOUSE_RELEASED, toX, toY, 0);
    }

    private static void mouse(Canvas canvas, int id, int x, int y, int modifiers) {
        int button = id == MouseEvent.MOUSE_DRAGGED ? MouseEvent.NOBUTTON : MouseEvent.BUTTON1;
        canvas.dispatchEvent(new MouseEvent(canvas, id, System.currentTimeMillis(),
            modifiers, x, y, 1, false, button));
    }

    private static void wheel(Canvas canvas, int modifiers, int rotation) {
        wheel(canvas, modifiers, rotation, 150, 120);
    }

    private static void wheel(Canvas canvas, int modifiers, int rotation, int x, int y) {
        canvas.dispatchEvent(new MouseWheelEvent(canvas, MouseEvent.MOUSE_WHEEL,
            System.currentTimeMillis(), modifiers, x, y, 0, false,
            MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, rotation));
    }

    private static void checkPosition(TextElement text, int x, int y) {
        Point p = text.getPosition();
        check(p.x == x && p.y == y, text.getText() + ": expected (" + x + ","
            + y + "), got " + p);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
