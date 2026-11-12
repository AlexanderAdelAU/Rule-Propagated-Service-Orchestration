package com.editor;

import java.awt.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import javax.swing.*;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

/**
 * Opens a token data file (TokenFormats/<name>.json) to read or change, with a preview of the data the first
 * tokens would carry. The example file is shown read-only; New makes an editable copy of it.
 */
final class TokenDataFileDialog {
    private TokenDataFileDialog() {}

    static final String NAME_RULE = "[A-Za-z][A-Za-z0-9_]*";

    /** A new file, started from the example: its name (without .json), or null when cancelled. */
    static String create(Component parent, File folder) {
        while (true) {
            String name = (String) JOptionPane.showInputDialog(parent,
                "Name of the new token data file (letters, digits and _; e.g. MyService).\nIt is started from ExampleTokenDataFile.json, for you to change.",
                "New token data file", JOptionPane.PLAIN_MESSAGE, null, null, "");
            if (name == null) return null;
            name = name.trim().replaceFirst("(?i)\\.json$", "");
            if (!name.matches(NAME_RULE)) { JOptionPane.showMessageDialog(parent, "Use letters, digits and _, starting with a letter.", "New token data file", JOptionPane.WARNING_MESSAGE); continue; }
            File file = new File(folder, name + ".json");
            if (file.exists()) {
                if (JOptionPane.showConfirmDialog(parent, name + ".json already exists. Open it?", "New token data file", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION)
                    return open(parent, file) ? name : name;
                continue;
            }
            try {
                Files.write(file.toPath(), TokenDataExample.copyText().getBytes(StandardCharsets.UTF_8));
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(parent, "Could not create " + file + ": " + ex.getMessage(), "New token data file", JOptionPane.ERROR_MESSAGE);
                return null;
            }
            open(parent, file);
            return name;
        }
    }

    /** Shows the file to read or change; true when it was saved. */
    static boolean open(Component parent, File file) {
        String text;
        try { text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); }
        catch (Exception ex) { JOptionPane.showMessageDialog(parent, "Could not read " + file + ": " + ex.getMessage(), "Token data file", JOptionPane.ERROR_MESSAGE); return false; }
        boolean readOnly = TokenDataExample.NAME.equals(file.getName()) || !file.canWrite();

        JDialog dialog = new JDialog(parent == null ? null : SwingUtilities.getWindowAncestor(parent), "Token data file: " + file.getName(), Dialog.ModalityType.APPLICATION_MODAL);
        JTextArea area = new JTextArea(text, 24, 90);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setEditable(!readOnly);
        area.setCaretPosition(0);
        JTextArea preview = new JTextArea(5, 90);
        preview.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        preview.setEditable(false);
        preview.setBackground(new Color(0xF4F4F4));
        JLabel note = new JLabel(" ");
        Runnable check = () -> {
            String problem = problem(area.getText());
            if (problem != null) { note.setText(problem); note.setForeground(new Color(0xB00020)); preview.setText(""); return; }
            note.setText(readOnly ? "This example is read-only. Click New... beside Token Data File Name to make your own copy."
                : "Valid. Each token's data, as the event generator will send it:");
            note.setForeground(readOnly ? Color.DARK_GRAY : new Color(0x1B5E20));
            preview.setText(preview(area.getText(), 5));
            preview.setCaretPosition(0);
        };
        area.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { check.run(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { check.run(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { check.run(); }
        });
        check.run();

        JLabel where = new JLabel("<html>" + file.getAbsolutePath().replace("&", "&amp;").replace("<", "&lt;") + "</html>");
        where.setForeground(Color.DARK_GRAY);
        JPanel top = new JPanel(new BorderLayout(4, 4));
        top.add(where, BorderLayout.NORTH);
        top.add(new JScrollPane(area), BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout(4, 4));
        bottom.add(note, BorderLayout.NORTH);
        bottom.add(new JScrollPane(preview), BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        final boolean[] saved = {false};
        JButton save = new JButton("Save");
        JButton close = new JButton(readOnly ? "Close" : "Cancel");
        save.addActionListener(e -> {
            String problem = problem(area.getText());
            if (problem != null) { JOptionPane.showMessageDialog(dialog, problem, "Token data file", JOptionPane.WARNING_MESSAGE); return; }
            try { Files.write(file.toPath(), area.getText().getBytes(StandardCharsets.UTF_8)); saved[0] = true; dialog.dispose(); }
            catch (Exception ex) { JOptionPane.showMessageDialog(dialog, "Could not save: " + ex.getMessage(), "Token data file", JOptionPane.ERROR_MESSAGE); }
        });
        close.addActionListener(e -> dialog.dispose());
        if (!readOnly) buttons.add(save);
        buttons.add(close);
        bottom.add(buttons, BorderLayout.SOUTH);
        JPanel content = new JPanel(new BorderLayout(6, 6));
        content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        content.add(top, BorderLayout.CENTER);
        content.add(bottom, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
        return saved[0];
    }

    /** Why the text is not a usable token data file, or null. */
    static String problem(String text) {
        Object parsed;
        try { parsed = new JSONParser().parse(text); } catch (Exception | Error e) { return "Not valid JSON yet: " + e.getMessage(); }
        if (!(parsed instanceof JSONObject)) return "The file must be one JSON object { ... }.";
        JSONObject json = (JSONObject) parsed;
        Object structure = json.get("tokenStructure");
        if (!(structure instanceof JSONObject) || !(((JSONObject) structure).get("data") instanceof JSONObject))
            return "It needs \"tokenStructure\": {\"data\": { ... }}: the fields every token carries.";
        Object mix = json.get("caseMix"), variants = json.get("dataVariants");
        if (mix != null && !(mix instanceof JSONArray)) return "\"caseMix\" must be a list of variant names.";
        if (mix != null && !(variants instanceof JSONObject)) return "\"caseMix\" names variants, so the file needs \"dataVariants\".";
        if (mix != null) for (Object name : (JSONArray) mix)
            if (!((JSONObject) variants).containsKey(String.valueOf(name))) return "\"caseMix\" names " + name + ", which is not in \"dataVariants\".";
        return null;
    }

    /** The data of tokens 1..count, as the event generator builds it (TokenFormatData). */
    @SuppressWarnings("unchecked")
    static String preview(String text, int count) {
        try {
            JSONObject json = (JSONObject) new JSONParser().parse(text);
            JSONObject base = (JSONObject) ((JSONObject) json.get("tokenStructure")).get("data");
            JSONArray mix = (JSONArray) json.get("caseMix");
            JSONObject variants = (JSONObject) json.get("dataVariants");
            StringBuilder out = new StringBuilder();
            for (int n = 1; n <= count; n++) {
                JSONObject data = new JSONObject();
                copy(base, data, n);
                if (mix != null && !mix.isEmpty() && variants != null) {
                    Object variant = variants.get(String.valueOf(mix.get((n - 1) % mix.size())));
                    if (variant instanceof JSONObject) copy((JSONObject) variant, data, n);
                }
                out.append("token ").append(n).append(": ").append(data.toJSONString()).append('\n');
            }
            return out.toString();
        } catch (Exception | Error e) { return ""; }
    }

    @SuppressWarnings("unchecked")
    private static void copy(JSONObject from, JSONObject to, int n) {
        for (Object key : from.keySet()) {
            if (String.valueOf(key).startsWith("_")) continue;
            Object value = from.get(key);
            to.put(key, value instanceof String ? ((String) value).replace("${n}", String.valueOf(n)) : value);
        }
    }
}
