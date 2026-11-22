package com.editor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.prefs.Preferences;

/** A new ZIP extraction must not inherit infrastructure or Ant settings from another copy. */
public final class ReleaseSettingsCheck {
    public static void main(String[] args) throws Exception {
        String priorRoot = System.getProperty("rpso.distribution.root");
        String priorOverride = System.getProperty("rpso.infrastructure");
        Path first = Files.createTempDirectory("rpso-release-first-");
        Path second = Files.createTempDirectory("rpso-release-second-");
        Preferences firstInfra = null, firstAnt = null, secondInfra = null, secondAnt = null;
        try {
            System.clearProperty("rpso.infrastructure");
            System.setProperty("rpso.distribution.root", first.toString());
            firstInfra = ServiceRegistry.scopedPreferences(ServiceRegistry.class);
            firstAnt = ServiceRegistry.scopedPreferences(RunWindow.class);
            check("SingleHost".equals(ServiceRegistry.selectedInfrastructure()), "First extraction did not default to SingleHost");
            check(RunWindow.antCommand().isEmpty(), "First extraction inherited an Ant path");
            ServiceRegistry.selectInfrastructure("MultiHost");
            firstAnt.put("antCommand", "C:/example/ant");

            System.setProperty("rpso.distribution.root", second.toString());
            secondInfra = ServiceRegistry.scopedPreferences(ServiceRegistry.class);
            secondAnt = ServiceRegistry.scopedPreferences(RunWindow.class);
            check("SingleHost".equals(ServiceRegistry.selectedInfrastructure()), "Another extraction inherited MultiHost");
            check(RunWindow.antCommand().isEmpty(), "Another extraction inherited the old Ant path");
            ServiceRegistry.selectInfrastructure("TwoHost");

            System.setProperty("rpso.distribution.root", first.toString());
            check("MultiHost".equals(ServiceRegistry.selectedInfrastructure()), "Earlier extraction forgot the user's selection");
            check("C:/example/ant".equals(RunWindow.antCommand()), "Earlier extraction forgot its Ant choice");

            System.setProperty("rpso.distribution.root", second.toString());
            check("TwoHost".equals(ServiceRegistry.selectedInfrastructure()), "Second extraction forgot its selection");
            System.out.println("PASS: new release folders start on SingleHost; each folder remembers its own machines and Ant");
        } finally {
            // Both classes use the same package-level Preferences node.
            java.util.Set<String> removed = new java.util.HashSet<>();
            for (Preferences node : new Preferences[]{firstInfra, firstAnt, secondInfra, secondAnt})
                if (node != null && removed.add(node.absolutePath())) node.removeNode();
            if (priorRoot == null) System.clearProperty("rpso.distribution.root");
            else System.setProperty("rpso.distribution.root", priorRoot);
            if (priorOverride == null) System.clearProperty("rpso.infrastructure");
            else System.setProperty("rpso.infrastructure", priorOverride);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
