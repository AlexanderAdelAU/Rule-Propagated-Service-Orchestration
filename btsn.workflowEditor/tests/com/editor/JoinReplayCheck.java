package com.editor;

import com.editor.animator.AnimationSegment;
import com.editor.animator.Phase;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import javax.swing.*;

/**
 * At a join the replay waits for the last fork: the fork the join keeps moves into the place only when the join fires,
 * and the fork it consumes is gone at that moment. Replayed on the recorded dining philosophers run, no fork is still
 * waiting in a philosopher's T_in while his other fork moves into the place or he eats, and every meal shows its move.
 */
public final class JoinReplayCheck {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath();
        Path process = root.resolve("btsn.common/ProcessDefinitionFolder/petrinet/Workflow/Dining_Philosophers.json");
        String run = Files.readString(root.resolve("btsn.common/AnalysisFolder/PetriNet/Analysis_Dining_Philosophers.txt"));
        final Throwable[] failure = new Throwable[1];
        final String[] summary = new String[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                Canvas canvas = new Canvas(); canvas.setDefinitionLocation(process.toFile()); canvas.loadFromJSON(Files.readString(process));
                TokenAnimator animator = new TokenAnimator(); animator.setCanvas(canvas);
                animator.parseOutput(run);
                Field f = TokenAnimator.class.getDeclaredField("segments"); f.setAccessible(true);
                List<AnimationSegment> segments = new ArrayList<>();
                for (Object o : (List<?>) f.get(animator)) segments.add((AnimationSegment) o);
                int moves = 0;
                for (AnimationSegment s : segments) {
                    if (s.phase != Phase.TRAVELING_TO_PLACE && s.phase != Phase.AT_PLACE) continue;
                    if (s.phase == Phase.TRAVELING_TO_PLACE) moves++;
                    long t = s.startTime + 1;
                    // A philosopher has only two forks, both used by this meal: none may still wait at his T_in.
                    for (AnimationSegment b : segments)
                        check(!(b.phase == Phase.BUFFERED_AT_TIN && Objects.equals(b.tInId, s.tInId) && !b.tokenId.equals(s.tokenId)
                                && b.startTime <= s.startTime && b.endTime > t), b.tokenId + " still waits at " + b.tInId + " during " + s);
                }
                check(moves == 58, "Expected a move into the place for each of the 58 meals, got " + moves);
                summary[0] = moves + " meals";
            } catch (Throwable t) { failure[0] = t; }
        });
        if (failure[0] != null) throw new AssertionError("Join replay", failure[0]);
        System.out.println("PASS: joins replay when they fire: over " + summary[0] + " of the dining philosophers, the kept fork moves into the place only once both forks are in the T_in, and the consumed fork is gone at that moment");
        System.exit(0);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
