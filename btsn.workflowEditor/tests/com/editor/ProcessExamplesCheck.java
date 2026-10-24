package com.editor;

import java.io.File;
import java.nio.file.*;
import java.util.*;

/**
 * Every process definition in ProcessDefinitionFolder opens cleanly in the editor: it saves without contract errors and
 * Validate has nothing that stops Deploy, Build or Run. A business process also plans a build without problems; an
 * initialiser or collector is recognised as one (it is run by every launcher, not built on its own).
 */
public final class ProcessExamplesCheck {
    public static void main(String[] args) throws Exception {
        Path folder = new File(args[0], "btsn.common/ProcessDefinitionFolder").getCanonicalFile().toPath();
        List<Path> processes = new ArrayList<>();
        try (var walk = Files.walk(folder)) { walk.filter(p -> p.toString().endsWith(".json")).sorted().forEach(processes::add); }
        List<String> problems = new ArrayList<>();
        int administrative = 0;
        for (Path process : processes) {
            Canvas canvas = new Canvas(); canvas.setDefinitionLocation(process.toFile()); canvas.loadFromJSON(Files.readString(process));
            List<String> found = new ArrayList<>(canvas.validateServiceContracts());
            found.addAll(canvas.serviceBlockers());
            found.addAll(canvas.validatePetriNet());
            if (canvas.getServiceRegistry().problem() != null) found.add(canvas.getServiceRegistry().problem());
            List<String> plan = BuildAndRunGenerator.plan(canvas, process.toFile()).problems;
            if (canvas.isAdministrativeProcess()) { if (!plan.equals(List.of(Canvas.ADMINISTRATIVE))) found.add("not recognised as an initialiser or collector: " + plan); administrative++; }
            else found.addAll(plan);
            for (String p : found) problems.add(folder.relativize(process) + ": " + p);
        }
        if (processes.size() < 40 || administrative < 10 || !problems.isEmpty())
            throw new AssertionError(processes.size() + " processes; problems:\n" + String.join("\n", problems));
        System.out.println("PASS: all " + processes.size() + " process definitions save and validate cleanly; " + (processes.size() - administrative)
            + " business processes plan a build without problems; " + administrative + " initialisers and collectors are recognised as such");
    }
}
