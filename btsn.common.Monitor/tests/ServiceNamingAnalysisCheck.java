package org.btsn.derby.Analysis;

import org.btsn.derby.Analysis.helper.ServiceDisplayNames;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.imageio.ImageIO;
import org.btsn.places.MonitorService;
import org.btsn.base.BaseCollectorService;

/** Isolated observation fixtures; never used to generate runtime measurements. */
public class ServiceNamingAnalysisCheck {
    public static void main(String[] args) throws Exception {
        Path common = Path.of(args[0]);
        System.setProperty("btsn.common.dir", common.toString());
        BuildServiceAnalysisDatabase db = new BuildServiceAnalysisDatabase();
        db.initializeDatabase();
        try (Connection c = DriverManager.getConnection("jdbc:derby:ServiceAnalysisDataBase")) {
            // Old healthcare data resolves from unique operation identities.
            execution(c, 1000000, 1000000, "P1_Place", "processTriageAssessment", 1000);
            execution(c, 1000000, 1000000, "P6_Place", "executeDirectTreatment", 2000);
            execution(c, 1000000, 1010001, "P4_Place", "processImagingRequest", 3000);
            execution(c, 1000000, 1020000, "P4_Place", "federatedRadiologyRequest", 4000);
            // Shared processToken cannot identify a domain from the current profile.
            execution(c, 2000000, 2000000, "P1_Place", "processToken", 5000);
            execution(c, 3000000, 3000000, "P1_Place", "processToken", 6000);
            ServiceDisplayNames before = ServiceDisplayNames.load(c, common);
            expect(before.label(1000000, "P1_Place"), "TriageService");
            expect(before.label(1000000, "P6_Place"), "TreatmentService");
            expect(before.label(1000000, "P4_Place"), "RadiologyService");
            expect(before.label(2000000, "P1_Place"), "P1_Place");
            expect(before.label(3000000, "P1_Place"), "P1_Place");
            expect(before.familyServices(1000000), "TreatmentService / TriageService");

            // Captured identities survive catalogue ambiguity and different workflows.
            ServiceDisplayNames.record(c, 2000000, 2000000, "P1_Place", "processToken", 5000, "ValidationService");
            ServiceDisplayNames.record(c, 3000000, 3000000, "P1_Place", "processToken", 6000, "BooleanTokenService");
            // Two instances of the same service must retain their distinct physical-node labels.
            ServiceDisplayNames.record(c, 5000000, 5000001, "P3_Place", "processToken", 9000, "StochasticService");
            ServiceDisplayNames.record(c, 5000000, 5000002, "P5_Place", "processToken", 9100, "StochasticService");
            ServiceDisplayNames names = ServiceDisplayNames.load(c, common);
            expect(names.label(2000000, "P1_Place"), "ValidationService");
            expect(names.label(3000000, "P1_Place"), "BooleanTokenService");
            expect(names.visitLabel(3000000, 3000000, "P1_Place", 6010), "BooleanTokenService");
            expect(names.describe(1000000, "P1_Place"), "TriageService (P1_Place)");
            expect(names.spatialLaneLabel("P3_Place", -1), "StochasticService (P3)");
            expect(names.spatialLaneLabel("P5_Place", -1), "StochasticService (P5)");
            expect(names.spatialLaneLabel("UnidentifiedPlace", -1), "UnidentifiedPlace");
            if (!names.laneLabel("P1_Place", -1).contains("ValidationService")) throw new AssertionError("mixed lane lost a service");
            // Recorded identity remains sufficient when catalogue files are absent.
            expect(ServiceDisplayNames.load(c, Path.of("missing-catalogue")).label(2000000, "P1_Place"), "ValidationService");

            // Collector-to-Monitor path preserves metadata separately from version/timing fields.
            MonitorService monitor = new MonitorService("999070000");
            String response = monitor.writeCollectorData("{\"monitoredPlace\":\"P2_Place\",\"performanceData\":{\"workflowGroups\":{\"v004\":[{\"sequenceId\":4000000,\"serviceName\":\"v004\",\"operation\":\"processToken\",\"arrivalTime\":7000,\"queueTime\":2,\"serviceTime\":10,\"totalTime\":12,\"logicalService\":\"CreditCheckService\"}]}}}");
            if (!response.contains("success")) throw new AssertionError(response);
            expect(ServiceDisplayNames.load(c, common).label(4000000, "P2_Place"), "CreditCheckService");
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT serviceName, queueTime, serviceTime FROM SERVICECONTRIBUTION WHERE sequenceID=4000000")) {
                if (!r.next() || !"v004".equals(r.getString(1)) || r.getLong(2) != 2 || r.getLong(3) != 10)
                    throw new AssertionError("display identity changed original measurements");
            }

            String temporal = new PetriNetAnalyzer().generateTemporalReport(1000000);
            if (!temporal.contains("Service: TriageService") || !temporal.contains("Orchestration place: P1_Place"))
                throw new AssertionError(temporal);
            WorkflowSpatialView spatial = new WorkflowSpatialView();
            expect(spatial.getPlaceDisplayName("P4_Place"), "RadiologyService (P4)");
            expect(spatial.getPlaceDisplayName("P2_Place"), "CreditCheckService (P2)");
            expect(spatial.getPlaceDisplayName("P3_Place"), "StochasticService (P3)");
            expect(spatial.getPlaceDisplayName("P5_Place"), "StochasticService (P5)");
            if (!spatial.getAllPlaces().contains("P1_Place")) throw new AssertionError("physical keys changed");
            if (!spatial.generateSummaryReport().contains("TriageService (P1_Place)"))
                throw new AssertionError("spatial summary missing service name");
            spatial.setSize(spatial.getPreferredSize());
            BufferedImage image = new BufferedImage(spatial.getWidth(), spatial.getHeight(), BufferedImage.TYPE_INT_RGB);
            spatial.paint(image.getGraphics());
            ImageIO.write(image, "png", new File("service-label-fixture.png"));
            // Local measurement serviceName is a physical place, not a version.
            // Verify multi-version collector labels against installed rule contracts.
            for (String version : new String[]{"v001", "v002"}) {
                Path installed = Path.of("RuleFolder." + version, "processToken", "Service.ruleml");
                Files.createDirectories(installed.getParent());
                Files.writeString(installed, "<RuleML><Assert><Atom><Rel>localDefined</Rel><Ind>ValidationService</Ind></Atom><Atom><Rel>canonicalBinding</Rel><Ind>processToken</Ind><Ind>validationResults</Ind><Ind>token</Ind></Atom></Assert></RuleML>");
            }
            try (Statement s = c.createStatement()) {
                s.executeUpdate("INSERT INTO SERVICEMEASUREMENTS (sequenceID,serviceName,operation,arrivalTime,invocationTime,publishTime) VALUES (1000000,'P1_Place','processToken',7000,7002,7012),(2000000,'P1_Place','processToken',8000,8002,8012)");
            }
            BaseCollectorService collector = new BaseCollectorService("999010000", "P1_Place", "v999") {
                protected String getCollectorName() { return "NamingFixtureCollector"; }
                protected String getMonitoredPlaceName() { return "P1_Place"; }
            };
            String collected = collector.getPerformanceData("workflow_999010000_v001,v002");
            int occurrences = collected.split("\\\"logicalService\\\":\\\"ValidationService\\\"", -1).length - 1;
            if (occurrences != 2 || !collected.contains("\"serviceName\":\"P1_Place\""))
                throw new AssertionError("multi-version collector identity: " + collected);
        }
        db.purgeAllTables();
        try (Connection c = db.getConnection(); Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT COUNT(*) FROM " + ServiceDisplayNames.TABLE)) {
            r.next(); if (r.getInt(1) != 0) throw new AssertionError("initialization left old labels behind");
        }
        System.out.println("PASS: healthcare names, multi-operation names, ambiguous fallback, captured cross-domain identities, Monitor ingestion, unchanged timings, spatial rendering and metadata purge");
    }
    private static void execution(Connection c, long workflow, long token, String place, String operation, long arrival) throws Exception {
        try (PreparedStatement p = c.prepareStatement("INSERT INTO SERVICECONTRIBUTION (workflowBase,sequenceID,serviceName,operation,arrivalTime,queueTime,serviceTime,totalTime) VALUES (?,?,?, ?,?,2,10,12)")) {
            p.setLong(1, workflow); p.setLong(2, token); p.setString(3, "v00" + workflow/1000000); p.setString(4, operation); p.setLong(5, arrival); p.executeUpdate();
        }
        try (PreparedStatement p = c.prepareStatement("INSERT INTO CONSOLIDATED_TRANSITION_FIRINGS (workflowBase,tokenId,transitionId,timestamp,toPlace,fromPlace,eventType) VALUES (?,?,?, ?,?,?,?)")) {
            for (String type : new String[]{"ENTER", "EXIT"}) {
                p.setLong(1, workflow); p.setLong(2, token); p.setString(3, "T_in_" + place);
                p.setLong(4, arrival + (type.equals("ENTER") ? 10 : 20)); p.setString(5, place); p.setString(6, place); p.setString(7, type); p.executeUpdate();
            }
        }
        try (PreparedStatement p = c.prepareStatement("INSERT INTO CONSOLIDATED_TOKEN_PATHS (workflowBase,tokenId,placeName,entryTime,exitTime,residenceTime) VALUES (?,?,?,?,?,10)")) {
            p.setLong(1, workflow); p.setLong(2, token); p.setString(3, place); p.setLong(4, arrival+10); p.setLong(5, arrival+20); p.executeUpdate();
        }
    }
    private static void expect(String actual, String expected) {
        if (!expected.equals(actual)) throw new AssertionError("expected " + expected + ", got " + actual);
    }
}
