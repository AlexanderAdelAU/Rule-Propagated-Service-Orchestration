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
 * A fork and join built from places: EG1 -> T1 -> P0, whose T_out T0 is a ForkNode to T2 -> P3 and T7 -> P6, joined at
 * T5 before P10, ending at T9. The event generator feeds one place (one with two arcs is reported); the designer types
 * the join's Inputs and the feeding places' Returns, which Validate advises but the editor never changes; a join with
 * no place after it is reported; the recorded run (5 tokens, every family joined) replays on the design.
 */
public final class ForkJoinDesignCheck {
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]).getCanonicalFile();
        Path fixtures = root.toPath().resolve("btsn.workflowEditor/tests/fixtures/fork_join");
        Path common = Files.createTempDirectory("fork-join-").resolve("btsn.common");
        Path process = common.resolve("ProcessDefinitionFolder/petrinet/Workflow/Fork_Place_Test.json");
        copy(fixtures.resolve("Fork_Place_Test.json"), process);
        copy(fixtures.resolve("deployment.json"), common.resolve("ServiceDeploymentFolder/petrinet/Fork_Place_Test.json"));
        copy(root.toPath().resolve("btsn.common").resolve(ServiceRegistry.STANDARD_CATALOGUE), common.resolve(ServiceRegistry.STANDARD_CATALOGUE));
        String run = Files.readString(fixtures.resolve("Analysis_Fork_Place_Test.txt"));
        // The same process deployed before its join was fitted: the deployment still says P3 and P6 return token.
        Path staleProcess = common.resolve("ProcessDefinitionFolder/petrinet/Workflow/Fork_Place_Stale.json");
        Files.writeString(staleProcess, Files.readString(process).replace("ServiceDeploymentFolder/petrinet/Fork_Place_Test.json", "ServiceDeploymentFolder/petrinet/Fork_Place_Stale.json"));
        Files.writeString(common.resolve("ServiceDeploymentFolder/petrinet/Fork_Place_Stale.json"),
            Files.readString(fixtures.resolve("deployment.json")).replace("token_branch1", "token").replace("\"returnAttribute\": \"token_branch2\"", "\"returnAttribute\": \"token\""));

        Throwable[] failure = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                Canvas canvas = new Canvas(); canvas.setDefinitionLocation(process.toFile()); canvas.loadFromJSON(Files.readString(process));
                Map<String, ProcessElement> e = new HashMap<>();
                for (ProcessElement element : canvas.getElements()) e.put(element.getLabel(), element);

                // Nothing is changed for the designer: the process saves, and Validate says what the join needs and where to type it.
                check(canvas.joinArcs(e.get("P10")) == 2 && canvas.validateServiceContracts().isEmpty(), "Contracts: " + canvas.validateServiceContracts());
                check(names(e.get("P10")).equals(Arrays.asList("token")) && "token".equals(returns(e.get("P3"))), "The editor changed the design by itself");
                check(canvas.serviceBlockers().contains("P10 is a join with 2 incoming arcs but its service takes 1 input; a join's place takes one input per arc, e.g. token_branch1, token_branch2. Type them in P10's Inputs field.")
                    && canvas.serviceBlockers().contains("P3 feeds a join where its arc fills the input token_branch1, but it returns token; the join tells its inputs apart by name. Type token_branch1 in P3's Returns field."),
                    "Join advice: " + canvas.serviceBlockers());
                check(ServiceDefinitionDialog.suggestion(canvas, e.get("P10")).inputs.equals(Arrays.asList("token_branch1", "token_branch2")), "New service for a join not suggested one input per arc");

                // The designer types them in the place's Attributes: Inputs for the join, Returns for the places feeding it.
                EditorFrame form = new EditorFrame(canvas);
                type(form, e.get("P10"), "Inputs", "token_branch1, token_branch2");
                type(form, e.get("P3"), "Returns", "token_branch1");
                type(form, e.get("P6"), "Returns", "token_branch2");
                check(names(e.get("P10")).equals(Arrays.asList("token_branch1", "token_branch2")) && "token_branch1".equals(returns(e.get("P3"))) && "token_branch2".equals(returns(e.get("P6"))),
                    "Typed contract not applied: " + names(e.get("P10")) + " " + returns(e.get("P3")) + " " + returns(e.get("P6")));
                check(canvas.serviceBlockers().isEmpty() && canvas.validatePetriNet().isEmpty(), "Blockers: " + canvas.serviceBlockers() + " " + canvas.validatePetriNet());
                check(canvas.saveToJSON().contains("\"name\": \"token_branch2\""), "Typed inputs not saved");

                // Build: the generator feeds P0's host; the fork is P0's T_out.
                BuildAndRunGenerator.Plan plan = BuildAndRunGenerator.plan(canvas, process.toFile());
                check(plan.problems.isEmpty() && "P4_Place".equals(plan.targets.get("EG1")) && !plan.joins.containsKey("EG1"), "Plan: " + plan.problems + " " + plan.targets);

                // A second arc makes the generator an initial marking (sent once), and Validate says so, with how to fork arrivals instead.
                Arrow second = new Arrow(e.get("EG1"), e.get("T2")); canvas.getArrows().add(second);
                check(canvas.generatorRole(e.get("EG1")).marks.size() == 2 && canvas.serviceNotes().stream().anyMatch(n -> n.startsWith("EG1 sets the initial marking: 2 tokens, once")
                        && n.endsWith("For a stream of arrivals that forks instead, give EG1 one arc and make its place's T_out a ForkNode.")),
                    "Generator with two arcs not reported as a marking: " + canvas.serviceNotes());
                canvas.getArrows().remove(second);

                // No Fork Children setting on a generator.
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
                check(moves.getOrDefault("1000000", Set.of()).containsAll(Arrays.asList("EG1->T1", "T1->P0")), "Parent: " + moves.get("1000000"));
                check(moves.getOrDefault("1000001", Set.of()).containsAll(Arrays.asList("T0->T2", "P3->T4", "T4->T5", "P10->T11", "T11->T9")), "Child 1: " + moves.get("1000001"));
                check(moves.getOrDefault("1000002", Set.of()).containsAll(Arrays.asList("T0->T7", "P6->T8", "T8->T5")), "Child 2: " + moves.get("1000002"));

                // Deployed before the join was set up: the design still saves, Deploy is not refused, and Build and Run
                // wait for the deployment to be saved again.
                Canvas stale = new Canvas(); stale.setDefinitionLocation(staleProcess.toFile()); stale.loadFromJSON(Files.readString(staleProcess));
                for (ProcessElement p : stale.getPlaces()) if (p.getLabel().equals("P3")) p.getServiceOperations().get(0).setReturnAttribute("token_branch1");
                check(stale.validateServiceContracts().isEmpty(), "An out-of-date deployment stops saving: " + stale.validateServiceContracts());
                check(stale.deploymentDifferences().stream().anyMatch(d -> d.startsWith("Place 'P3' returns token_branch1 but the saved deployment (Fork_Place_Stale.json, instance ForkTestP3) still has token. Deploy again"))
                    && stale.serviceBlockers().containsAll(stale.deploymentDifferences()), "Out-of-date deployment not reported: " + stale.serviceBlockers());

                // A join whose service takes too few inputs does not stop saving the design; it stops Deploy, Build and Run.
                ProcessElement p10 = e.get("P10");
                List<ServiceOperation> kept = new ArrayList<>(p10.getServiceOperations());
                ServiceOperation narrow = new ServiceOperation("processToken"); narrow.addArgument(new ServiceArgument("token", "String", "String", false));
                narrow.setReturnAttribute("token");
                Canvas domain = new Canvas(); domain.setDefinitionLocation(process.toFile()); domain.loadFromJSON(Files.readString(process));
                for (ProcessElement p : domain.getPlaces()) if (p.getLabel().equals("P10")) { p.setService("UnknownJoinService"); p.setServiceOperations(new ArrayList<>(List.of(narrow))); }
                check(domain.validateServiceContracts().stream().noneMatch(m -> m.contains("incoming arcs")), "Join inputs stop saving: " + domain.validateServiceContracts());
                check(domain.serviceBlockers().stream().anyMatch(m -> m.startsWith("P10 is a join with 2 incoming arcs but its service takes 1 input")), "Join inputs not a blocker: " + domain.serviceBlockers());

                // A join with no place after it has nothing to host it: reported, and it stops Deploy, Build and Run.
                Arrow toP10 = null;
                for (Arrow a : canvas.getArrows()) if (a.getSource() == e.get("T5") && a.getTarget() == e.get("P10")) toP10 = a;
                canvas.getArrows().remove(toP10);
                Arrow toEnd = new Arrow(e.get("T5"), e.get("T9")); canvas.getArrows().add(toEnd);
                check(canvas.serviceBlockers().stream().anyMatch(n -> n.startsWith("T5 is a join but no place follows it")), "Join without a place not reported: " + canvas.serviceBlockers());
            } catch (Throwable t) { failure[0] = t; }
        });
        if (failure[0] != null) throw new AssertionError("Generator fork", failure[0]);
        System.out.println("PASS: a fork made by a place's T_out (ForkNode), joined at P10: the generator feeds one place and a second arc makes it an initial marking, with how to fork arrivals instead (no Fork Children setting); nothing is changed by the editor: Validate says what a join needs and the designer types the join's Inputs and the feeding places' Returns; a new service for a join is suggested one input per arc; a process deployed before its join was set up saves and asks to Deploy again; a join whose service takes too few inputs is saved but stops Deploy, Build and Run; a join with no place is reported; the recorded run (5 forks, 5 joined, 5 completed) replays each child from the fork along its branch to the join and on to T9");
    }

    /** Selects the place and types into its Inputs or Returns field, as the designer would. */
    private static void type(EditorFrame form, ProcessElement place, String field, String text) {
        form.updateSelection(place);
        JTextField target = null;
        for (JTextField f : fields(form)) if (f.getToolTipText() != null && f.getToolTipText().startsWith(field.equals("Inputs") ? "The names this place takes" : "The name this place's result")) target = f;
        check(target != null, "No " + field + " field for " + place.getLabel());
        target.setText(text);
    }
    private static List<JTextField> fields(Container parent) {
        List<JTextField> result = new ArrayList<>();
        for (Component c : parent.getComponents()) {
            if (c instanceof JTextField) result.add((JTextField) c);
            if (c instanceof Container) result.addAll(fields((Container) c));
        }
        return result;
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
