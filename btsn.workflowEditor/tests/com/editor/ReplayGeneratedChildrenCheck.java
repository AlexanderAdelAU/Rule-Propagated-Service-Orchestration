package com.editor;

import com.editor.animator.AnimationSegment;
import com.editor.animator.Phase;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;

/**
 * Two EG entry branches with children +01/+02, BUFFERED->ENTER->TERMINATE.
 * A generated fork child is not an input JoinNode; the ordinary arrival must
 * reach its actual place promptly rather than buffer until the last generator event.
 */
public final class ReplayGeneratedChildrenCheck {
    private static ProcessElement element(Canvas canvas, ProcessElement.Type type, String label,
                                          int x, int y, String transition, String node) {
        ProcessElement e = new ProcessElement(type, x, y);
        e.setLabel(label);
        if (transition != null) e.setTransitionType(transition);
        if (node != null) e.setNodeType(node);
        canvas.getElements().add(e);
        return e;
    }

    private static void link(Canvas canvas, ProcessElement from, ProcessElement to) {
        canvas.getArrows().add(new Arrow(from, to));
    }

    private static String event(long time, int token, String place, String transition, String type) {
        return "Time=" + time + " Token=" + token + " Place=" + place
            + " Marking=0 Buffer=0 ToPlace=" + place + " TransitionId="
            + transition + " EventType=" + type + "\n";
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Throwable[] error = {null};
        SwingUtilities.invokeAndWait(() -> {
            try {
                Canvas canvas = new Canvas();
                ProcessElement eg = element(canvas, ProcessElement.Type.EVENT_GENERATOR, "EG1", 10, 70, null, null);
                // Even if an old workflow contains obsolete fork_behavior metadata, it
                // cannot turn an ordinary entry T_in or TerminateNode into a JoinNode.
                eg.setForkEnabled(true);
                eg.setForkChildCount(2);
                eg.setForkJoinTarget("T5");
                ProcessElement t2 = element(canvas, ProcessElement.Type.TRANSITION, "T2", 100, 0, "T_in", "EdgeNode");
                ProcessElement t7 = element(canvas, ProcessElement.Type.TRANSITION, "T7", 100, 120, "T_in", "EdgeNode");
                ProcessElement p1 = element(canvas, ProcessElement.Type.PLACE, "P1_Place", 200, 0, null, null);
                ProcessElement p2 = element(canvas, ProcessElement.Type.PLACE, "P2_Place", 200, 120, null, null);
                ProcessElement t4 = element(canvas, ProcessElement.Type.TRANSITION, "T4", 300, 0, "T_out", "EdgeNode");
                ProcessElement t8 = element(canvas, ProcessElement.Type.TRANSITION, "T8", 300, 120, "T_out", "EdgeNode");
                ProcessElement t5 = element(canvas, ProcessElement.Type.TRANSITION, "T5", 400, 60, "Other", "TerminateNode");
                link(canvas, eg, t2); link(canvas, eg, t7);
                link(canvas, t2, p1); link(canvas, t7, p2);
                link(canvas, p1, t4); link(canvas, p2, t8);
                link(canvas, t4, t5); link(canvas, t8, t5);

                long start = 1792750368134L;
                StringBuilder run = new StringBuilder("=== MARKING EVOLUTION ===\n");
                for (int parent = 0; parent < 10; parent++) {
                    int parentId = 1000000 + 10000 * parent;
                    long t = start + parent * 1000;
                    // The same pattern as the supplied Petri analysis: child 01 to P1,
                    // child 02 to P2, both terminate, no actual join.
                    run.append(event(t, parentId + 1, "P1_Place", "EG1", "GENERATED"));
                    run.append(event(t + 2, parentId + 2, "P2_Place", "EG1", "GENERATED"));
                    run.append(event(t + 80, parentId + 1, "P1_Place", "T_in_P1", "BUFFERED"));
                    run.append(event(t + 93, parentId + 1, "P1_Place", "T_in_P1", "ENTER"));
                    run.append(event(t + 155, parentId + 1, "P1_Place", "T_out_P1", "TERMINATE"));
                    run.append(event(t + 105, parentId + 2, "P2_Place", "T_in_P2", "BUFFERED"));
                    run.append(event(t + 115, parentId + 2, "P2_Place", "T_in_P2", "ENTER"));
                    run.append(event(t + 190, parentId + 2, "P2_Place", "T_out_P2", "TERMINATE"));
                }
                TokenAnimator animator = new TokenAnimator();
                animator.setCanvas(canvas);
                animator.parseOutput(run.toString());
                check(!animator.isJoin("T2") && !animator.isJoin("T7")
                    && !animator.isJoin("T5"), "Obsolete fork metadata falsely declared a join");
                Field field = TokenAnimator.class.getDeclaredField("segments");
                field.setAccessible(true);
                List<AnimationSegment> all = (List<AnimationSegment>) field.get(animator);
                for (int branch = 1; branch <= 2; branch++) {
                    String child = String.valueOf(1000000 + branch);
                    String entry = branch == 1 ? "T2" : "T7";
                    String place = branch == 1 ? "P1_Place" : "P2_Place";
                    long enter = start + (branch == 1 ? 93 : 115);
                    List<AnimationSegment> own = new ArrayList<>();
                    for (AnimationSegment segment : all) if (child.equals(segment.tokenId)) own.add(segment);
                    check(own.stream().anyMatch(s -> s.phase == Phase.TRAVELING_TO_TIN
                            && "EG1".equals(s.fromElement) && entry.equals(s.toElement)),
                        "Child " + child + " did not use its own outgoing arc: " + own);
                    check(own.stream().anyMatch(s -> s.phase == Phase.TRAVELING_TO_PLACE
                            && entry.equals(s.fromElement) && place.equals(s.toElement)),
                        "Child " + child + " did not reach own place: " + own);
                    check(own.stream().anyMatch(s -> s.phase == Phase.AT_PLACE
                            && s.startTime >= enter && s.startTime < start + 250),
                        "Child " + child + " does not enter its place on time: " + own);
                    check(own.stream().noneMatch(s -> s.phase == Phase.BUFFERED_AT_TIN
                            && s.endTime > enter),
                        "Child " + child + " falsely waits for a later parent or join: " + own);
                }
            } catch (Throwable ex) { error[0] = ex; }
        });
        if (error[0] != null) throw new AssertionError("Replay generated children", error[0]);
        System.out.println("PASS: 10 two-branch EG forks replay as ordinary entries, no artificial join wait");
    }
}
