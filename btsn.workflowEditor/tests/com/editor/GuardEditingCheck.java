package com.editor;

import java.awt.*;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.swing.*;

/**
 * Guards can be set only on arcs leaving a T_out; elsewhere the fields are greyed out and a guard there can only be
 * cleared. "(none)" removes a guard: condition, decision value and a label that only repeated it.
 */
public final class GuardEditingCheck {
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]).getCanonicalFile();
        Path process = root.toPath().resolve("btsn.common/ProcessDefinitionFolder/credit/Workflow/Credit_Fraud_Rest_Workflow.json");
        Canvas canvas = new Canvas(); canvas.setDefinitionLocation(process.toFile()); canvas.loadFromJSON(Files.readString(process));
        Map<String, Arrow> arcs = new HashMap<>();
        for (Arrow a : canvas.getArrows()) arcs.put(a.getSource().getLabel() + ">" + a.getTarget().getLabel(), a);

        // Which arcs take a guard: those leaving a place's T_out.
        for (String arc : Arrays.asList("T_out_Fraud>T_in_Credit", "T_out_Fraud>Review", "T_out_Credit>Approved"))
            check(GuardEditing.applies(canvas, arcs.get(arc)), arc + " should take a guard");
        for (String arc : Arrays.asList("Fraud>T_out_Fraud", "T_in_Fraud>Fraud", "APPLICATIONS>T_in_Fraud", "Credit>T_out_Credit"))
            check(!GuardEditing.applies(canvas, arcs.get(arc)), arc + " should not take a guard");

        // The mistake from the fraud-screen run: a value on the arc into T_out, with no condition and no label.
        Arrow into = arcs.get("Fraud>T_out_Fraud");
        into.setDecisionValue("true");
        check(GuardEditing.guarded(into) && GuardEditing.shown(into).equals("DECISION_EQUAL_TO"), "A bare decision value not shown as a guard");
        EditorFrame panel = new EditorFrame(canvas); panel.updateSelection(into);
        JComboBox<?> condition = combo(panel, GuardEditing.NONE);
        check(!condition.isEnabled() && text(panel, GuardEditing.NOT_HERE), "Guard not greyed out on the arc into a T_out");
        JButton clear = button(panel, "Clear guard");
        check(clear != null, "No way to clear the guard on the arc into a T_out");
        clear.doClick();
        check(!GuardEditing.guarded(into) && button(panel, "Clear guard") == null, "Clear guard did not remove it");

        // On a T_out arc, (none) removes the guard and the label that showed it.
        Arrow review = arcs.get("T_out_Fraud>Review");
        check("review".equals(review.getLabel()) && "review".equals(review.getDecisionValue()), "Fixture changed: " + review.getLabel());
        panel.updateSelection(review);
        condition = combo(panel, GuardEditing.NONE);
        check(condition.isEnabled() && "DECISION_EQUAL_TO".equals(condition.getSelectedItem()), "T_out arc guard not editable: " + condition.getSelectedItem());
        condition.setSelectedItem(GuardEditing.NONE);
        check(!GuardEditing.guarded(review) && review.getLabel().isEmpty(), "(none) left a guard: " + review.getGuardCondition() + "/" + review.getDecisionValue() + "/" + review.getLabel());
        // A label of its own is kept.
        review.setLabel("to review"); review.setGuardCondition("DECISION_EQUAL_TO"); review.setDecisionValue("review");
        GuardEditing.choose(review, GuardEditing.NONE);
        check("to review".equals(review.getLabel()) && !GuardEditing.guarded(review), "A label of its own was removed");
        System.out.println("PASS: guards only on arcs leaving a T_out; elsewhere greyed out with Clear guard; (none) removes condition, value and the label that repeated it");
    }

    private static JComboBox<?> combo(Container parent, Object option) {
        for (Component c : parent.getComponents()) {
            if (c instanceof JComboBox) {
                JComboBox<?> box = (JComboBox<?>) c;
                for (int i = 0; i < box.getItemCount(); i++) if (option.equals(box.getItemAt(i))) return box;
            } else if (c instanceof Container) {
                JComboBox<?> found = combo((Container) c, option);
                if (found != null) return found;
            }
        }
        return null;
    }
    private static JButton button(Container parent, String text) {
        for (Component c : parent.getComponents()) {
            if (c instanceof JButton && text.equals(((JButton) c).getText())) return (JButton) c;
            if (c instanceof Container) { JButton found = button((Container) c, text); if (found != null) return found; }
        }
        return null;
    }
    private static boolean text(Container parent, String text) {
        for (Component c : parent.getComponents()) {
            if (c instanceof JLabel && text.equals(((JLabel) c).getText())) return true;
            if (c instanceof Container && text((Container) c, text)) return true;
        }
        return false;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
