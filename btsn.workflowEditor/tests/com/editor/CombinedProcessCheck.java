package com.editor;

import java.io.File;
import java.util.List;

/** A combined process (&lt;name&gt;_LiveChange.json) is built and run through the launcher of the same name. */
public final class CombinedProcessCheck {
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]).getCanonicalFile();
        String name = "P1_P2_P3_P4_ForkRebalance_Workflow_LiveChange";
        File drawing = new File(root, "btsn.common/ProcessDefinitionFolder/petrinet/LiveChange/" + name + ".json");
        File launcher = BuildAndRunGenerator.combinedLauncher(drawing);
        check(launcher != null && launcher.getName().equals(name + ".xml"), "Combined process did not find its launcher: " + launcher);
        List<String> lines = BuildAndRunGenerator.combinedSummary(launcher);
        check(lines.size() == 2, "Expected two event generators: " + lines);
        check(lines.get(0).startsWith("EG1: v001, P1_P2_P3_P4_ForkRebalance_Workflow, 90 tokens, from the start"), "EG1: " + lines.get(0));
        check(lines.get(1).startsWith("EG2: v002, P1_P3_P4_P6_ForkRebalance_Workflow, 30 tokens, deployed to the running hosts at 45 s"), "EG2: " + lines.get(1));
        check(LauncherRun.launchersFor(root, "petrinet/LiveChange/" + name).stream().anyMatch(f -> f.getName().equals(name + ".xml")),
            "Run on hosts does not find the combined launcher");
        check(BuildAndRunGenerator.combinedLauncher(new File(root, "btsn.common/ProcessDefinitionFolder/petrinet/LiveChange/P1_P2_P3_P4_ForkRebalance_Workflow.json")) == null,
            "An ordinary process was taken for a combined one");
        if (args.length > 1 && !java.awt.GraphicsEnvironment.isHeadless()) {
            File picture = new File(args[1], "combined-build.png");
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                CombinedBuildDialog dialog = new CombinedBuildDialog(null, root, "petrinet/LiveChange/" + name, launcher);
                check(dialog.getTitle().equals("Build"), "Dialog title: " + dialog.getTitle());
                java.awt.Container content = dialog.getContentPane();
                java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(content.getWidth(), content.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
                content.paint(image.getGraphics());
                try { javax.imageio.ImageIO.write(image, "png", picture); } catch (java.io.IOException ex) { throw new RuntimeException(ex); }
                dialog.dispose();
            });
        }
        System.out.println("PASS: combined process finds its launcher, summarises both versions, and Run on hosts finds it");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
