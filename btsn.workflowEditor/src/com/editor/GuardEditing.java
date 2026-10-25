package com.editor;

/**
 * Guards on arcs. A host tests guards only on the arcs leaving a place's T_out (against what the place's service
 * returned), so only those arcs can be given one; any other arc that carries one can only have it cleared.
 * "(none)" is the explicit choice of no guard, and clears the decision value with the condition. "OTHERWISE" is the arc
 * taken when no other guard on the T_out matches; it has no decision value.
 */
public final class GuardEditing {
    public static final String NONE = "(none)";
    public static final String OTHERWISE = "OTHERWISE";
    public static final String[] CONDITIONS = {NONE, "DECISION_EQUAL_TO", "DECISION_NOT_EQUAL", "DECISION_GREATER_THAN", "DECISION_LESS_THAN", OTHERWISE};
    public static final String NOT_HERE = "Guards are tested only on arcs leaving a T_out";

    private GuardEditing() {}

    /** True for an arc leaving a transition that a place feeds (its T_out), the only arcs whose guards are tested. */
    public static boolean applies(Canvas canvas, Arrow arrow) {
        ProcessElement from = arrow.getSource();
        if (from == null || from.getType() != ProcessElement.Type.TRANSITION) return false;
        for (Arrow in : canvas.getArrows())
            if (in.getTarget() == from && in.getSource() != null && in.getSource().getType() == ProcessElement.Type.PLACE) return true;
        return false;
    }

    /** The arc taken when no other guard on its T_out matches. */
    public static boolean otherwise(Arrow arrow) { return OTHERWISE.equals(arrow.getGuardCondition()); }

    /** Whether the condition takes a decision value: not (none), and not Otherwise. */
    public static boolean takesValue(Object condition) { return condition != null && !NONE.equals(condition) && !OTHERWISE.equals(condition); }

    /** Whether the arc carries a guard: a condition or a decision value. */
    public static boolean guarded(Arrow arrow) {
        return !blank(arrow.getGuardCondition()) || !blank(arrow.getDecisionValue());
    }

    /** The condition as the dropdown shows it: a decision value without a condition is tested for equality. */
    public static String shown(Arrow arrow) {
        if (!blank(arrow.getGuardCondition())) return arrow.getGuardCondition();
        return blank(arrow.getDecisionValue()) ? NONE : "DECISION_EQUAL_TO";
    }

    /** Applies a dropdown choice: (none) removes the guard altogether. */
    public static void choose(Arrow arrow, String choice) {
        if (choice == null || NONE.equals(choice)) clear(arrow);
        else if (OTHERWISE.equals(choice)) {
            clear(arrow);
            arrow.setGuardCondition(OTHERWISE);
            if (blank(arrow.getLabel())) arrow.setLabel("otherwise");
        }
        else arrow.setGuardCondition(choice);
    }

    /** Removes the guard: condition and decision value, and a label that only repeated the value. */
    public static void clear(Arrow arrow) {
        String value = arrow.getDecisionValue();
        if (!blank(value) && value.trim().equals(arrow.getLabel() == null ? null : arrow.getLabel().trim())) arrow.setLabel("");
        arrow.setGuardCondition("");
        arrow.setDecisionValue("");
    }

    private static boolean blank(String s) { return s == null || s.trim().isEmpty(); }
}
