package org.btsn.derby.Analysis;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.imageio.ImageIO;
import javax.swing.JPanel;

/** Read-only check of a completed healthcare run, separate from fixture checks. */
public class ServiceNamingRuntimeCheck {
    public static void main(String[] args) throws Exception {
        PetriNetAnalyzer analyzer = new PetriNetAnalyzer();
        PetriNetAnalyzer.CanonicalWorkflowAnalysis analysis = analyzer.analyzeCanonicalWorkflows(1000000);
        if (analysis.generatedWorkflows != 10 || analysis.completedWorkflows != 10)
            throw new AssertionError("expected ten complete real workflows");
        try (Connection c = DriverManager.getConnection("jdbc:derby:ServiceAnalysisDataBase")) {
            int timing = count(c, "SERVICECONTRIBUTION");
            int identities = count(c, ServiceDisplayNames.TABLE);
            if (timing != identities || identities == 0)
                throw new AssertionError("captured=" + identities + ", timings=" + timing);
            ServiceDisplayNames names = ServiceDisplayNames.load(c);
            if (names.inferredExecutions() != 0) throw new AssertionError("new run relied on catalogue inference");
            String[] expected = {"TriageService", "LaboratoryService", "CardiologyService", "RadiologyService", "DiagnosisService", "TreatmentService"};
            WorkflowSpatialView spatial = new WorkflowSpatialView();
            for (int i = 0; i < expected.length; i++) {
                String place = "P" + (i+1) + "_Place";
                if (!expected[i].equals(names.label(1000000, place)) || !expected[i].equals(spatial.getPlaceDisplayName(place)))
                    throw new AssertionError("wrong runtime label at " + place);
            }
            render(spatial, "healthcare-service-names-spatial.png");
            SwingGanttChart_WithLatency_v1d gantt = new SwingGanttChart_WithLatency_v1d();
            if (gantt.tasks.size() != 10) throw new AssertionError("wrong Gantt workflow count");
            for (SwingGanttChart_WithLatency_v1d.Task task : gantt.tasks) {
                if (!task.businessServices.contains("TriageService") || !task.businessServices.contains("TreatmentService"))
                    throw new AssertionError("Gantt missing service identities");
            }
            render(gantt, "healthcare-service-names-gantt.png");
            System.out.println("PASS: ten real workflows; " + timing + " captured business identities; all six service labels; spatial and Gantt rendering");
        }
    }
    private static int count(Connection c, String table) throws Exception {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT COUNT(*) FROM " + table + " WHERE workflowBase=1000000")) {
            r.next(); return r.getInt(1);
        }
    }
    private static void render(JPanel panel, String file) throws Exception {
        panel.setSize(panel.getPreferredSize());
        BufferedImage image = new BufferedImage(panel.getWidth(), panel.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics(); panel.paint(graphics); graphics.dispose();
        ImageIO.write(image, "png", new File(file));
    }
}
