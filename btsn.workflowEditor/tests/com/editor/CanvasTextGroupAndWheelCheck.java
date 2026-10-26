package com.editor;

import java.awt.Dimension;
import java.awt.Point;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
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
            } catch (Throwable error) {
                failure[0] = error;
            }
        });
        if (failure[0] != null) throw new AssertionError("Canvas text group / wheel", failure[0]);
        System.out.println("PASS: Ctrl+click and rubber-band text groups, copy/paste, and wheel scrolling");
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
        canvas.dispatchEvent(new MouseWheelEvent(canvas, MouseEvent.MOUSE_WHEEL,
            System.currentTimeMillis(), modifiers, 150, 120, 0, false,
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
