package com.editor;

import com.editor.animator.AnimationSegment;
import com.editor.animator.Phase;
import java.awt.*;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.swing.*;

/**
 * An event generator with two arcs forks: EG1 -> T2 -> P3 and EG1 -> T7 -> P6, joined at T5 before P10, ending at T9.
 * The fork follows from the arcs (no Fork Children setting); Build sends child N01 of each token to P3's host and N02 to
 * P6's; the join's standard service takes one input per arc and the services feeding it return those inputs' names;
 * a join with no place after it is reported; the recorded run (5 tokens, every family joined) replays on the design.
 */
public final class GeneratorForkCheck {
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]).getCanonicalFile();
        Path fixtures = root.toPath().resolve("btsn.workflowEditor/tests/fixtures/generator_fork");
        Path common = Files.createTempDirectory("generator-fork-").resolve("btsn.common");
        Path process = common.resolve("ProcessDefinitionFolder/petrinet/Workflow/Fork_EG_Test.json");
        copy(fixtures.resolve("Fork_EG_Test.json"), process);
        copy(fixtures.resolve("deployment.json"), common.resolve("ServiceDeploymentFolder/petrinet/Fork_EG_Test.json"));
        copy(root.toPath().resolve("btsn.common").resolve(ServiceRegistry.STANDARD_CATALOGUE), common.resolve(ServiceRegistry.STANDARD_CATALOGUE));
        String run = Files.readString(fixtures.resolve("Analysis_Fork_EG_Test.txt"));

        Throwable[] failure = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                Canvas canvas = new Canvas(); canvas.setDefinitionLocation(process.toFile()); canvas.loadFromJSON(Files.readString(process));
                Map<String, ProcessElement> e = new HashMap<>();
                for (ProcessElement element : canvas.getElements()) e.put(element.getLabel(), element);

                // The join's standard service takes one input per arc; the services feeding it return those names.
                check(canvas.joinArcs(e.get("P10")) == 2 && canvas.validateServiceContracts().isEmpty(), "Contracts: " + canvas.validateServiceContracts());
                check(names(e.get("P10")).equals(Arrays.asList("token_branch1", "token_branch2")), "Join inputs: " + names(e.get("P10")));
                check("token_branch1".equals(returns(e.get("P3"))) && "token_branch2".equals(returns(e.get("P6"))) && "token".equals(returns(e.get("P10"))),
                    "Returns: " + returns(e.get("P3")) + ", " + returns(e.get("P6")) + ", " + returns(e.get("P10")));
                check(ServiceDefinitionDialog.suggestion(canvas, e.get("P10")).inputs.equals(Arrays.asList("token_branch1", "token_branch2")), "New service for a join not suggested one input per arc");
                check(canvas.serviceBlockers().isEmpty() && canvas.validatePetriNet().isEmpty(), "Blockers: " + canvas.serviceBlockers() + " " + canvas.validatePetriNet());

                // Build: one generator, two branches; child k of each token goes to branch k (top to bottom).
                BuildAndRunGenerator.Plan plan = BuildAndRunGenerator.plan(canvas, process.toFile());
                check(plan.problems.isEmpty() && plan.feeds("EG1").equals("P1_Place.processToken + P2_Place.processToken")
                    && plan.branches.get("EG1").equals(Arrays.asList("P1_Place:processToken", "P2_Place:processToken")) && !plan.joins.containsKey("EG1"),
                    "Plan: " + plan.problems + " " + plan.branches);
                BuildAndRunGenerator.Options o = plan.defaults; o.folder = common.getParent().resolve("btsn.petrinet.ProjectLoader").toFile();
                check(BuildAndRunGenerator.launcherXml(plan, o).contains("<property name=\"eg1.branches\" value=\"P1_Place:processToken,P2_Place:processToken\"/>"), "Launcher has no branches");
                check(BuildAndRunGenerator.summary(plan, o).stream().anyMatch(l -> l.contains("each forked into 2 children (N01, N02, ...)")), "Summary: " + BuildAndRunGenerator.summary(plan, o));

                // No Fork Children setting: the fork is the generator's arcs.
                EditorFrame attributes = new EditorFrame(canvas); attributes.updateSelection(e.get("EG1"));
                check(labels(attributes).stream().noneMatch(t -> t.startsWith("Fork Children") || t.startsWith("Join Target")), "Fork setting still offered: " + labels(attributes));
                check(!canvas.saveToJSON().contains("fork_behavior"), "fork_behavior saved");

                // The recorded run replays: each child travels its own branch to the join; the joined token ends at T9.
                TokenAnimator animator = new TokenAnimator(); animator.setCanvas(canvas); animator.parseOutput(run);
                Field f = TokenAnimator.class.getDeclaredField("segments"); f.setAccessible(true);
                Map<String, Set<String>> moves = new HashMap<>();
                for (Object s : (List<?>) f.get(animator)) {
                    AnimationSegment seg = (AnimationSegment) s;
                    if (seg.fromElement != null && seg.toElement != null) moves.computeIfAbsent(seg.tokenId, k -> new LinkedHashSet<>()).add(seg.fromElement + "->" + seg.toElement);
                }
                check(moves.getOrDefault("1000001", Set.of()).containsAll(Arrays.asList("EG1->T2", "T2->P3", "P3->T4", "T4->T5", "P10->T11", "T11->T9")), "Child 1: " + moves.get("1000001"));
                check(moves.getOrDefault("1000002", Set.of()).containsAll(Arrays.asList("EG1->T7", "T7->P6", "P6->T8", "T8->T5")), "Child 2: " + moves.get("1000002"));

                // A join with no place after it has nothing to host it: reported, and it stops Deploy, Build and Run.
                Arrow toP10 = null;
                for (Arrow a : canvas.getArrows()) if (a.getSource() == e.get("T5") && a.getTarget() == e.get("P10")) toP10 = a;
                canvas.getArrows().remove(toP10);
                Arrow toEnd = new Arrow(e.get("T5"), e.get("T9")); canvas.getArrows().add(toEnd);
                check(canvas.serviceBlockers().stream().anyMatch(n -> n.startsWith("T5 is a join but no place follows it")), "Join without a place not reported: " + canvas.serviceBlockers());
            } catch (Throwable t) { failure[0] = t; }
        });
        if (failure[0] != null) throw new AssertionError("Generator fork", failure[0]);
        System.out.println("PASS: a generator with two arcs forks (no Fork Children setting): Build sends child N01 to P3's host and N02 to P6's; the join's standard service takes token_branch1/2 and the services feeding it return them; a new service for a join is suggested one input per arc; a join with no place is reported; the recorded run replays each child along its branch to the join and on to T9");
    }

    private static List<String> names(ProcessElement place) {
        List<String> n = new ArrayList<>();
        for (ServiceArgument a : place.getServiceOperations().get(0).getArguments()) n.add(a.getName());
        return n;
    }
    private static String returns(ProcessElement place) { return place.getServiceOperations().get(0).getReturnAttribute(); }
    private static List<String> labels(Container parent) {
        List<String> result = new ArrayList<>();
        for (Component c : parent.getComponents()) {
            if (c instanceof JLabel) result.add(((JLabel) c).getText());
            if (c instanceof Container) result.addAll(labels((Container) c));
        }
        return result;
    }
    private static void copy(Path from, Path to) throws Exception { Files.createDirectories(to.getParent()); Files.copy(from, to); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
