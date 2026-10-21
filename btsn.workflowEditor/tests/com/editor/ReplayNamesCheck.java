package com.editor;

import com.editor.animator.AnimationSegment;
import com.editor.animator.Phase;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import javax.swing.*;

/**
 * A design whose transitions are not named T_in_/T_out_ (here T1 and T4, around place P3 on node P3) replays on its
 * own elements: the hosts' T_in_P3/T_out_P3 map to T1/T4, the token travels EG2 -> T1 -> P3 -> T4 -> Terminate, and
 * the controls are enabled. A run that cannot be mapped is never played with the controls disabled.
 */
public final class ReplayNamesCheck {
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]).getCanonicalFile();
        Path fixtures = root.toPath().resolve("btsn.workflowEditor/tests/fixtures/replay_names");
        Path common = Files.createTempDirectory("replay-names-").resolve("btsn.common");
        Path process = common.resolve("ProcessDefinitionFolder/test/test.json");
        copy(fixtures.resolve("test.json"), process);
        copy(fixtures.resolve("Test.json"), common.resolve("BusinessServiceDefinitions/test/Test.json"));
        copy(fixtures.resolve("test_deployment.json"), common.resolve("ServiceDeploymentFolder/test/test.json"));
        copy(root.toPath().resolve("btsn.common").resolve(ServiceRegistry.STANDARD_CATALOGUE), common.resolve(ServiceRegistry.STANDARD_CATALOGUE));
        String run = Files.readString(fixtures.resolve("Analysis_test.txt"));

        final Throwable[] failure = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                Canvas canvas = new Canvas(); canvas.setDefinitionLocation(process.toFile()); canvas.loadFromJSON(Files.readString(process));
                TokenAnimator animator = new TokenAnimator(); animator.setCanvas(canvas);
                animator.parseOutput(run);
                check(animator.hasTopology(), "No topology for a design with transitions T1 and T4");
                Field f = TokenAnimator.class.getDeclaredField("segments"); f.setAccessible(true);
                List<String> path = new ArrayList<>();
                for (Object o : (List<?>) f.get(animator)) {
                    AnimationSegment s = (AnimationSegment) o;
                    if (!s.tokenId.equals("1000000")) continue;
                    if (s.phase == Phase.TRAVELING_TO_TIN || s.phase == Phase.TRAVELING_TO_PLACE || s.phase == Phase.TRAVELING_TO_TOUT || s.phase == Phase.TRAVELING_TO_TERMINATE)
                        path.add(s.fromElement + "->" + s.toElement);
                    if (s.placeId != null) check(s.placeId.equals("P3"), "Segment at a runtime name: " + s);
                }
                check(path.equals(Arrays.asList("EG2->T1", "T1->P3", "P3->T4", "T4->Terminate")), "Token path: " + path);

                // Types follow the arcs: a wrong type in the file is corrected on load and reported until saved;
                // a transition both fed by and feeding a place is a contradiction that stops Deploy and Run.
                ProcessElement t1 = null, p3 = null;
                for (ProcessElement e : canvas.getElements()) { if ("T1".equals(e.getLabel())) t1 = e; if ("P3".equals(e.getLabel())) p3 = e; }
                check("T_in".equals(canvas.requiredTransitionType(t1)) && canvas.transitionTypeConflicts().isEmpty(), "T1 not a T_in by its arcs");
                t1.setTransitionType("Other");
                check(canvas.fixTransitionTypes().equals(Arrays.asList("T1: Other -> T_in")) && "T_in".equals(t1.getTransitionType()), "Type not corrected from the arcs");
                String saved = canvas.saveToJSON().replace("\"transition_type\": \"T_in\"", "\"transition_type\": \"Other\"");
                Canvas reopened = new Canvas(); reopened.setDefinitionLocation(process.toFile()); reopened.loadFromJSON(saved);
                check(reopened.serviceNotes().stream().anyMatch(n -> n.startsWith("Transition types corrected from their arcs (T1: Other -> T_in)")), "Load correction not reported: " + reopened.serviceNotes());
                reopened.markSaved();
                check(reopened.serviceNotes().stream().noneMatch(n -> n.startsWith("Transition types corrected")), "Correction still reported after saving");
                Arrow back = new Arrow(p3, t1); canvas.getArrows().add(back);
                check("both".equals(canvas.requiredTransitionType(t1)) && canvas.serviceBlockers().stream().anyMatch(n -> n.startsWith("Transition 'T1' both feeds a place and is fed by one")),
                    "Contradiction not reported: " + canvas.serviceBlockers());
                canvas.getArrows().remove(back);

                // The control panel: the run loads with its controls enabled, so it can be paused and reset.
                AnimationControlPanel panel = new AnimationControlPanel(canvas);
                panel.loadAnalysisText(run);
                panel.startPlayback();
                check(panel.isPlaying(), "Mapped run does not play");
                panel.stopPlayback();
                // A run that does not belong to this design is not played behind disabled controls.
                Canvas other = new Canvas();
                AnimationControlPanel unmapped = new AnimationControlPanel(other);
                unmapped.loadAnalysisText(run);
                unmapped.startPlayback();
                check(!unmapped.isPlaying(), "An unmapped run played with its controls disabled");
            } catch (Throwable t) { failure[0] = t; }
        });
        if (failure[0] != null) throw new AssertionError("Replay with T1/T4", failure[0]);
        System.out.println("PASS: transitions recognised by their type, which follows their arcs (corrected on load, contradictions reported): T_in_P3/T_out_P3 replay on T1/T4, the token travels to each element, controls enabled; an unmapped run is not played behind disabled controls");
    }

    private static void copy(Path from, Path to) throws Exception { Files.createDirectories(to.getParent()); Files.copy(from, to); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
