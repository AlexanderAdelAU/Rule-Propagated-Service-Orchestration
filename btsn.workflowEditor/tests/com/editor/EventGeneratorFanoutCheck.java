package com.editor;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Editor topology, launcher metadata and schedule regression for EG fan-out.
 * Standard single-entry workflows and legacy fork_behavior settings must not
 * acquire a second, manually configured fork count.
 */
public final class EventGeneratorFanoutCheck {
    public static void main(String[] args) throws Exception {
        Canvas canvas = new Canvas();
        ProcessElement eg = new ProcessElement(ProcessElement.Type.EVENT_GENERATOR, 0, 0);
        ProcessElement t2 = new ProcessElement(ProcessElement.Type.TRANSITION, 100, 40);
        ProcessElement t7 = new ProcessElement(ProcessElement.Type.TRANSITION, 100, 140);
        ProcessElement t9 = new ProcessElement(ProcessElement.Type.TRANSITION, 100, 240);
        ProcessElement p3 = new ProcessElement(ProcessElement.Type.PLACE, 220, 40);
        ProcessElement p6 = new ProcessElement(ProcessElement.Type.PLACE, 220, 140);
        ProcessElement p9 = new ProcessElement(ProcessElement.Type.PLACE, 220, 240);
        canvas.getElements().addAll(Arrays.asList(eg, t2, t7, t9, p3, p6, p9));
        canvas.getArrows().add(new Arrow(eg, t2));
        canvas.getArrows().add(new Arrow(t2, p3));
        check(BuildAndRunGenerator.entryPlaces(canvas, eg).equals(List.of(p3)), "One branch");
        canvas.getArrows().add(new Arrow(eg, t7));
        canvas.getArrows().add(new Arrow(t7, p6));
        check(BuildAndRunGenerator.entryPlaces(canvas, eg).equals(List.of(p3, p6)), "Two branch order");
        // A stale legacy spinner value must not be consulted by the builder.
        eg.setForkEnabled(true);
        eg.setForkChildCount(8);
        check(BuildAndRunGenerator.entryPlaces(canvas, eg).size() == 2, "Manual fork count affected topology");
        canvas.getArrows().add(new Arrow(eg, t9));
        check(!BuildAndRunGenerator.invalidEntryArcs(canvas, eg).isEmpty(),
            "Dangling third route was silently ignored");
        canvas.getArrows().add(new Arrow(t9, p9));
        check(BuildAndRunGenerator.invalidEntryArcs(canvas, eg).isEmpty(),
            "Connected third route still flagged as invalid");
        check(BuildAndRunGenerator.entryPlaces(canvas, eg).equals(List.of(p3, p6, p9)), "Three branch order");

        Path root = Files.createTempDirectory("fanout-launcher-");
        BuildAndRunGenerator.Plan plan = new BuildAndRunGenerator.Plan();
        plan.root = root.toFile();
        plan.processName = "mytest/Workflow/mytest";
        plan.profile = root.resolve("btsn.services/deployments/mytest.json").toFile();
        plan.serviceDeployment = "ServiceDeploymentFolder/mytest/mytest.json";
        plan.catalogue = "BusinessServiceDefinitions/petrinet/PetriNetModels.json";
        plan.nodeSetName = "P1_P2";
        plan.nodes.addAll(List.of("P1", "P2"));
        plan.initializer = root.resolve("btsn.common/ProcessDefinitionFolder/common/Initializers/P1_P2_Initialization.json").toFile();
        plan.collector = root.resolve("btsn.common/ProcessDefinitionFolder/common/Collectors/P1_P2_Collector.json").toFile();
        String generatorId = eg.getId();
        plan.targets.put(generatorId, "P1_Place");
        plan.operations.put(generatorId, "processToken");
        plan.destinations.put(generatorId, List.of(
            new BuildAndRunGenerator.Target("P1_Place", "processToken"),
            new BuildAndRunGenerator.Target("P2_Place", "processToken")));
        BuildAndRunGenerator.Options options = new BuildAndRunGenerator.Options();
        options.folder = root.resolve("btsn.petrinet.ProjectLoader").toFile();
        options.launcherName = "mytest_BuildAndRun.xml";
        options.tokens = 10;
        BuildAndRunGenerator.Run run = new BuildAndRunGenerator.Run();
        run.generatorId = generatorId;
        run.intervalMs = 1000;
        options.runs.add(run);

        String description = BuildAndRunGenerator.describeDestinations(plan, generatorId);
        check(description.equals("P1_Place.processToken, P2_Place.processToken"), description);
        check(BuildAndRunGenerator.summary(plan, options).stream()
            .anyMatch(x -> x.contains("10 parent events") && x.contains("20 deliveries")), "Fork summary");
        File launcher = BuildAndRunGenerator.write(plan, options);
        String xml = Files.readString(launcher.toPath());
        check(xml.contains("eg1.fork.targets\" value=\"P1_Place:processToken,P2_Place:processToken"),
            "Launcher omitted the fork targets: " + xml);
        check(xml.contains("eg1.place\" value=\"P1_Place"), "First target needed for legacy deploy stage");
        String csv = Files.readString(plan.schedule(options, run).toPath());
        check(csv.lines().count() == 10 && csv.contains("0,0,1")
            && csv.contains("9000,0,1"), "Expected ten parent-event schedule rows: " + csv);

        options.tokens = 101;
        check(BuildAndRunGenerator.problems(plan, options).stream()
            .anyMatch(x -> x.contains("at most 100 parent events")), "Fork IDs must stay inside version range");
        options.tokens = 10;
        plan.destinations.put(generatorId, List.of(new BuildAndRunGenerator.Target("P1_Place", "processToken")));
        String single = BuildAndRunGenerator.launcherXml(plan, options);
        check(!single.contains(".fork.targets"), "Single-entry workflow was marked as a fork");
        check(BuildAndRunGenerator.summary(plan, options).stream()
            .anyMatch(x -> x.contains("Tokens   10 from ")), "Legacy single-entry summary changed");
        System.out.println("PASS: one, two, three topology branches; old fork count ignored; "
            + "10 scheduled parents -> 20 deliveries and both deployed targets in launcher");
    }

    private static void check(boolean okay, String detail) {
        if (!okay) throw new AssertionError(detail);
    }
}
