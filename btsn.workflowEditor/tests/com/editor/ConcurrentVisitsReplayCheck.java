package com.editor;

import com.editor.animator.AnimationSegment;
import com.editor.animator.Phase;
import com.editor.animator.TokenAnimState;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import javax.swing.SwingUtilities;

/**
 * A host serves a place's tokens concurrently, so the replay shows visits when they were recorded, overlapping when they
 * overlapped. Replayed on a recorded run of the three concurrent healthcare processes (fixture), on the emergency
 * department drawing: 16 of the 20 patients end at Terminate and the 4 still in Treatment when the run stopped stay
 * there; no token that has ended is drawn anywhere afterwards; and Radiology, which served federated requests and
 * patients together, is shown with two tokens in it at once. (Visits used to be queued one at a time: the 4 unfinished
 * visits held every later patient at Treatment's T_in until the end, after they had already been shown at Terminate.)
 */
public final class ConcurrentVisitsReplayCheck {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath();
        Path process = root.resolve("btsn.common/ProcessDefinitionFolder/healthcare/Workflow/Emergency_Department_With_Federated_Radiology.json");
        String run = Files.readString(root.resolve("btsn.workflowEditor/tests/fixtures/replay/Analysis_Emergency_Department_Concurrent.txt"));
        Throwable[] failure = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                Canvas canvas = new Canvas(); canvas.setDefinitionLocation(process.toFile()); canvas.loadFromJSON(Files.readString(process));
                TokenAnimator animator = new TokenAnimator(); animator.setCanvas(canvas); animator.parseOutput(run);
                Field f = TokenAnimator.class.getDeclaredField("segments"); f.setAccessible(true);
                List<AnimationSegment> segments = new ArrayList<>();
                for (Object o : (List<?>) f.get(animator)) segments.add((AnimationSegment) o);

                // Once a token has ended it is not drawn anywhere else.
                Map<String, Long> ended = new HashMap<>();
                for (AnimationSegment s : segments)
                    if (s.phase == Phase.AT_TERMINATE) ended.merge(s.tokenId, s.startTime, Math::min);
                for (AnimationSegment s : segments) {
                    Long end = ended.get(s.tokenId);
                    check(end == null || s.phase == Phase.AT_TERMINATE || s.phase == Phase.CONSUMED || s.startTime < end,
                        s.tokenId + " is drawn after it ended: " + s);
                }

                // At the end of the run: the v003 patients at Terminate and in Treatment.
                long last = animator.getEndTime() - 1;
                int atTerminate = 0, inTreatment = 0;
                for (TokenAnimState t : animator.getTokenStatesAt(last).values()) {
                    if (!"v003".equals(t.version)) continue;
                    if (t.phase == Phase.AT_TERMINATE) atTerminate++;
                    if (t.phase == Phase.AT_PLACE && "Treatment".equals(label(canvas, t.currentPlaceId))) inTreatment++;
                }
                check(atTerminate == 16, "16 patients should end at Terminate, got " + atTerminate);
                check(inTreatment == 4, "4 patients should still be in Treatment, got " + inTreatment);

                // Radiology served two tokens at once, and is shown doing so.
                List<AnimationSegment> radiology = new ArrayList<>();
                for (AnimationSegment s : segments)
                    if (s.phase == Phase.AT_PLACE && "Radiology".equals(label(canvas, s.placeId))) radiology.add(s);
                boolean together = false;
                for (AnimationSegment a : radiology)
                    for (AnimationSegment b : radiology)
                        if (!a.tokenId.equals(b.tokenId) && b.startTime >= a.startTime && b.startTime < a.endTime) together = true;
                check(together, "Radiology is never shown with two tokens in it at once");
            } catch (Throwable t) { failure[0] = t; }
        });
        if (failure[0] != null) throw new AssertionError("Concurrent visits", failure[0]);
        System.out.println("PASS: visits replay as recorded, overlapping when they overlapped: in the concurrent healthcare run, "
            + "16 patients end at Terminate, the 4 still in Treatment stay there, no ended token is drawn again, and Radiology "
            + "is shown serving two tokens at once");
        System.exit(0);
    }

    private static String label(Canvas canvas, String id) {
        if (id == null) return null;
        for (ProcessElement e : canvas.getElements())
            if (id.equals(e.getId()) || id.equals(e.getLabel()) || id.equals(e.getService())) return e.getLabel();
        return id;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
