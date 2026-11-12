package com.editor;

import javax.swing.JLabel;

/** A dialog message that wraps at a readable width, so a long line does not stretch the dialog across the screen. */
final class WrappedMessage {
    private WrappedMessage() {}

    static JLabel of(String text) {
        String html = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
        return new JLabel("<html><div style='width:520px'>" + html + "</div></html>");
    }
}
