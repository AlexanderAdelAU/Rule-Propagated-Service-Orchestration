package com.editor;

import java.io.File;
import java.nio.file.*;
import java.util.*;

/**
 * Every process in ProcessDefinitionFolder/petrinet opens cleanly in the editor: it saves without contract errors,
 * Validate has nothing that stops Deploy, Build or Run, and Build's plan has no problems.
 */
public final class PetriNetExamplesCheck {
    public static void main(String[] args) throws Exception {
        Path folder = new File(args[0], "btsn.common/ProcessDefinitionFolder/petrinet").getCanonicalFile().toPath();
        List<Path> processes = new ArrayList<>();
        try (var walk = Files.walk(folder)) { walk.filter(p -> p.toString().endsWith(".json")).sorted().forEach(processes::add); }
        List<String> problems = new ArrayList<>();
        for (Path process : processes) {
            Canvas canvas = new Canvas(); canvas.setDefinitionLocation(process.toFile()); canvas.loadFromJSON(Files.readString(process));
            List<String> found = new ArrayList<>(canvas.validateServiceContracts());
            found.addAll(canvas.serviceBlockers());
            found.addAll(BuildAndRunGenerator.plan(canvas, process.toFile()).problems);
            for (String p : found) problems.add(folder.relativize(process) + ": " + p);
        }
        if (processes.size() < 10 || !problems.isEmpty())
            throw new AssertionError(processes.size() + " processes; problems:\n" + String.join("\n", problems));
        System.out.println("PASS: all " + processes.size() + " petrinet processes save, validate and plan a build cleanly");
    }
}
