package com.editor;

import com.editor.animator.AnimationSegment;
import com.editor.animator.Phase;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.*;
import javax.swing.SwingUtilities;

/**
 * A Signal node ends the token in its process, as a Terminate node does, and launches the process it names. The loan
 * process's "Loan Abandoned" names Loan_Compensation_Workflow: it is drawn as S, keeps the process it launches when
 * saved and reloaded, counts as an end (no "leads nowhere"), is reported when it names no process or one that does not
 * exist, and in a recorded run the abandoned applications end there while their compensation starts at the
 * compensation process's start node.
 */
public final class SignalNodeCheck {
    static final String LAUNCHES = "financial/Workflow/Loan_Compensation_Workflow";

    public static void main(String[] args) throws Exception {
        File root = new File(args[0]).getCanonicalFile();
        File loan = new File(root, "btsn.common/ProcessDefinitionFolder/financial/Workflow/FinancialSystem_P1_P5_Workflow.json");
        File compensation = new File(root, "btsn.common/ProcessDefinitionFolder/financial/Workflow/Loan_Compensation_Workflow.json");
        String run = Files.readString(root.toPath().resolve("btsn.workflowEditor/tests/fixtures/signal/Analysis_Loan_Compensation.txt"));
        Throwable[] failure = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                Canvas canvas = load(loan);
                ProcessElement signal = canvas.getElements().stream().filter(e -> "Loan Abandoned".equals(e.getLabel())).findFirst().orElse(null);
                check(signal != null && signal.isSignalNode(), "Loan Abandoned is not a Signal node");
                check("S".equals(signal.getNodeTypeIndicator()), "Signal node not drawn as S: " + signal.getNodeTypeIndicator());
                check(LAUNCHES.equals(signal.getLaunchProcess()), "Wrong process launched: " + signal.getLaunchProcess());
                check(canvas.processDefinitions().contains(LAUNCHES), "The process list offers no " + LAUNCHES);

                // Saved and reloaded, it still launches the same process.
                Canvas reloaded = new Canvas(); reloaded.setDefinitionLocation(loan); reloaded.loadFromJSON(canvas.saveToJSON());
                ProcessElement again = reloaded.getElements().stream().filter(e -> "Loan Abandoned".equals(e.getLabel())).findFirst().orElse(null);
                check(again != null && again.isSignalNode() && LAUNCHES.equals(again.getLaunchProcess()), "Signal node lost on save and reload");

                // An end, like a Terminate node; reported when it names no process, or one that does not exist.
                check(mentions(canvas.routingGaps(false), "Loan Abandoned").isEmpty(), "Signal node reported: " + canvas.routingGaps(false));
                signal.setLaunchProcess("");
                check(!mentions(canvas.routingGaps(false), "does not name the process").isEmpty(), "A Signal node naming no process is not reported");
                signal.setLaunchProcess("financial/Workflow/No_Such_Process");
                check(!mentions(canvas.routingGaps(false), "not a process under ProcessDefinitionFolder").isEmpty(), "A missing process is not reported");
                signal.setLaunchProcess(LAUNCHES);

                // Create Build and Run: the launcher also deploys the process the Signal node launches, under its own
                // version, as a slot its generator does not fire; its hosts are among the run's.
                BuildAndRunGenerator.Plan plan = BuildAndRunGenerator.plan(canvas, loan);
                check(plan.problems.isEmpty(), "Loan process not ready for Build and Run: " + plan.problems);
                check(plan.launched.size() == 1 && LAUNCHES.equals(plan.launched.get(0).process), "Launched process not planned: " + plan.launched.size());
                String xml = BuildAndRunGenerator.launcherXml(plan, plan.defaults);
                for (String expected : new String[] {"name=\"query.version\" value=\"v001,v002\"", "name=\"eg2.process\" value=\"" + LAUNCHES + "\"",
                        "name=\"eg2.version\" value=\"v002\"", "name=\"eg2.place\" value=\"P2_Place\"", "name=\"eg2.operation\" value=\"withdrawEnquiry\"",
                        "name=\"eg2.started.by.signal\" value=\"true\"", "name=\"hosts\" value=\"P1,P2,P3,P4,P5\""})
                    check(xml.contains(expected), "Launcher lacks " + expected + "\n" + xml);
                check(!xml.contains("eg2.trigger.file"), "The launched process should fire nothing itself\n" + xml);
                check(BuildAndRunGenerator.summary(plan, plan.defaults).stream().anyMatch(l -> l.startsWith("Signal   Loan Abandoned launches " + LAUNCHES)),
                    "Build does not say what the Signal node launches: " + BuildAndRunGenerator.summary(plan, plan.defaults));
                // A launched process with the generator's version moves to the next free one.
                BuildAndRunGenerator.Options clash = plan.defaults; clash.runs.get(0).version = "v002";
                check(BuildAndRunGenerator.launcherXml(plan, clash).contains("name=\"query.version\" value=\"v002,v001\""), "Version clash not resolved");
                clash.runs.get(0).version = "v001";

                // A recorded run of that launcher, in which the fraud bureau was unavailable for 3 applications: they end
                // at Loan Abandoned; the others are approved.
                Map<String, Integer> loanEnds = endings(canvas, run);
                check(loanEnds.getOrDefault("Loan Abandoned", 0) == 3, "Three abandoned applications should end at Loan Abandoned: " + loanEnds);

                // In the compensation process, each starts at Loan Abandoned and ends with its enquiry withdrawn.
                Map<String, Integer> compensationEnds = endings(load(compensation), run);
                check(compensationEnds.getOrDefault("Enquiry Withdrawn", 0) == 3, "Three compensations should end at Enquiry Withdrawn: " + compensationEnds);
            } catch (Throwable t) { failure[0] = t; }
        });
        if (failure[0] != null) throw new AssertionError("Signal node", failure[0]);
        System.out.println("PASS: the loan process's Signal node Loan Abandoned (drawn S) launches Loan_Compensation_Workflow, keeps it through "
            + "save and reload, counts as an end, is reported without a process or with a missing one; Create Build and Run deploys "
            + "Loan_Compensation_Workflow as v002, fired only by the signal; in the run that launcher made, the 3 abandoned "
            + "applications end there and their 3 compensations end at Enquiry Withdrawn");
    }

    private static Canvas load(File process) throws Exception {
        Canvas canvas = new Canvas(); canvas.setDefinitionLocation(process); canvas.loadFromJSON(Files.readString(process.toPath()));
        return canvas;
    }

    /** Where the replayed tokens end: terminate (or Signal) node label -> tokens. */
    private static Map<String, Integer> endings(Canvas canvas, String run) throws Exception {
        TokenAnimator animator = new TokenAnimator(); animator.setCanvas(canvas); animator.parseOutput(run);
        Field f = TokenAnimator.class.getDeclaredField("segments"); f.setAccessible(true);
        Map<String, Set<String>> tokens = new TreeMap<>();
        for (Object o : (List<?>) f.get(animator)) {
            AnimationSegment seg = (AnimationSegment) o;
            if (seg.phase == Phase.AT_TERMINATE && seg.terminateId != null)
                tokens.computeIfAbsent(seg.terminateId, k -> new TreeSet<>()).add(String.valueOf(seg.tokenId));
        }
        Map<String, Integer> counts = new TreeMap<>();
        tokens.forEach((k, v) -> counts.put(k, v.size()));
        return counts;
    }

    private static List<String> mentions(List<String> gaps, String text) {
        List<String> found = new ArrayList<>();
        for (String gap : gaps) if (gap.contains(text)) found.add(gap);
        return found;
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
