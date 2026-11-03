package com.editor;

import com.editor.animator.MarkingEvent;
import com.editor.animator.TokenAnimState;
import java.nio.file.*;
import java.util.*;
import javax.swing.SwingUtilities;

/**
 * Where the editor's replay shows each token, for the runs the documentation links to (docs/replay/runs.txt): at 49
 * moments spread over each run's recorded time, one line per token, "moment, token, where". replay-page-agreement.js
 * replays the same runs with the web page's own code and compares.
 *
 * args: repository root, output directory
 */
public final class ReplayPageStates {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath(), out = Path.of(args[1]);
        Files.createDirectories(out);
        List<String[]> runs = new ArrayList<>();
        for (String line : Files.readAllLines(root.resolve("docs/replay/runs.txt"))) {
            line = line.trim();
            if (!line.isEmpty() && !line.startsWith("#")) runs.add(line.split("\\s+"));
        }
        Throwable[] failure = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                for (String[] run : runs) {
                    Path model = root.resolve(run[1]);
                    Canvas canvas = new Canvas(); canvas.setDefinitionLocation(model.toFile()); canvas.loadFromJSON(Files.readString(model));
                    TokenAnimator animator = new TokenAnimator(); animator.setCanvas(canvas); animator.parseOutput(Files.readString(root.resolve(run[2])));
                    long first = Long.MAX_VALUE, last = Long.MIN_VALUE;
                    for (MarkingEvent e : animator.getEvents()) { first = Math.min(first, e.timestamp); last = Math.max(last, e.timestamp); }
                    StringBuilder sb = new StringBuilder();
                    for (int k = 1; k < 50; k++) {
                        // The replay moment showing recorded time first + k/50 of the run.
                        long real = first + (last - first) * k / 50, lo = animator.getStartTime(), hi = animator.getEndTime();
                        while (hi - lo > 1) { long m = (lo + hi) / 2; if (animator.realTimeAt(m) < real) lo = m; else hi = m; }
                        for (TokenAnimState t : animator.getTokenStatesAt(hi).values())
                            sb.append(k).append('\t').append(t.tokenId).append('\t').append(where(canvas, t)).append('\n');
                    }
                    Files.writeString(out.resolve(run[0] + ".tsv"), sb.toString());
                }
            } catch (Throwable t) { failure[0] = t; }
        });
        if (failure[0] != null) throw new AssertionError("Replay states", failure[0]);
        System.out.println("Editor replay states written for " + runs.size() + " runs to " + out);
        System.exit(0);
    }

    static String where(Canvas c, TokenAnimState s) {
        switch (s.phase) {
            case AT_TERMINATE: return "end " + label(c, s.terminateNodeId);
            case BUFFERED_AT_TIN: return "wait " + label(c, s.tInId);
            case AT_PLACE: return "at " + label(c, s.currentPlaceId);
            case AT_EVENT_GENERATOR: return "eg " + label(c, s.eventGenId);
            default: return "move " + label(c, s.fromElementId) + ">" + label(c, s.toElementId);
        }
    }

    static String label(Canvas c, String id) {
        if (id == null) return "";
        for (ProcessElement e : c.getElements())
            if (id.equals(e.getId()) || id.equals(e.getLabel()) || id.equals(e.getService())) return e.getLabel() == null ? e.getId() : e.getLabel();
        return "?" + id;
    }
}
