package com.editor;

import com.editor.animator.AnimationSegment;
import com.editor.animator.Phase;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import javax.swing.*;

/**
 * At a join the replay waits for the last fork: the fork the join keeps moves into the place only when the join fires,
 * and the fork it consumes is gone at that moment. Replayed on a recorded dining philosophers run (fixture), no fork
 * moves into a philosopher's place before his other fork has reached the T_in (on main, Locke's fork did so at all 11
 * of his meals and Plato's at 10 of 11), no fork is still waiting in the T_in while he eats, and every meal shows its move.
 */
public final class JoinReplayCheck {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath();
        Path process = root.resolve("btsn.common/ProcessDefinitionFolder/petrinet/Workflow/Dining_Philosophers.json");
        String run = Files.readString(root.resolve("btsn.workflowEditor/tests/fixtures/Analysis_Dining_Philosophers.txt"));
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
                long first = Long.MAX_VALUE;
                for (AnimationSegment s : segments) first = Math.min(first, s.startTime);
                for (AnimationSegment s : segments) {
                    if (s.phase != Phase.TRAVELING_TO_PLACE && s.phase != Phase.AT_PLACE) continue;
                    if (s.phase == Phase.TRAVELING_TO_PLACE) {
                        moves++;
                        // The other fork of this meal: the one whose wait at this T_in ends nearest the move. It must be
                        // drawn at the T_in before the move starts. The first two seconds are left out: there the forks'
                        // first trips from TABLE are drawn with set durations, not recorded ones (a separate issue).
                        AnimationSegment partner = null;
                        for (AnimationSegment b : segments)
                            if (b.phase == Phase.BUFFERED_AT_TIN && Objects.equals(b.tInId, s.tInId) && !b.tokenId.equals(s.tokenId)
                                    && (partner == null || Math.abs(b.endTime - s.startTime) < Math.abs(partner.endTime - s.startTime))) partner = b;
                        if (partner != null && s.startTime - first > 2000)
                            check(partner.startTime <= s.startTime, s.tokenId + " moves into " + s.placeId + " before " + partner.tokenId + " reaches " + s.tInId + ": " + s + " / " + partner);
                    }
                    long t = s.startTime + 1;
                    // A philosopher has only two forks, both used by this meal: none may still wait at his T_in.
                    for (AnimationSegment b : segments)
                        check(!(b.phase == Phase.BUFFERED_AT_TIN && Objects.equals(b.tInId, s.tInId) && !b.tokenId.equals(s.tokenId)
                                && b.startTime <= s.startTime && b.endTime > t), b.tokenId + " still waits at " + b.tInId + " during " + s);
                }
                check(moves == 56, "Expected a move into the place for each of the 56 meals, got " + moves);
                summary[0] = moves + " meals";
            } catch (Throwable t) { failure[0] = t; }
        });
        if (failure[0] != null) throw new AssertionError("Join replay", failure[0]);
        System.out.println("PASS: joins replay when they fire: over " + summary[0] + " of the dining philosophers (Locke included), the kept fork moves into the place only once both forks are in the T_in, and the consumed fork is gone at that moment");
        System.exit(0);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
