package org.btsn.derby.Analysis;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import javax.imageio.ImageIO;

/** Visit fixtures distinguish shared queues, versions, operations and duplicate observations. */
public final class ServiceQueueTimingCheck {
    public static void main(String[] args) throws Exception {
        new BuildServiceAnalysisDatabase().initializeDatabase();
        try(Connection c=DriverManager.getConnection("jdbc:derby:ServiceAnalysisDataBase")) {
            sample(c,1000000,1000,0L,"P1_Place","processTriageAssessment","TriageService");
            sample(c,1010000,1100,40L,"P1_Place","processTriageAssessment","TriageService");
            sample(c,2000000,1200,100L,"P1_Place","processTriageAssessment","TriageService");
            sample(c,2010000,1300,300L,"P1_Place","processTriageAssessment","TriageService");
            sample(c,3000000,1400,250L,"P1_Place","processTriageAssessment","TriageService");
            sample(c,3010000,1500,350L,"P1_Place","processTriageAssessment","TriageService");
            sample(c,1020000,1600,50L,"P4_Place","federatedRadiologyRequest","RadiologyService, \"Audit\"");
            sample(c,3020001,1700,60L,"P4_Place","processImagingRequest","RadiologyService");
            sample(c,1030000,1800,80L,"P2_Place","processTriageAssessment","TriageService");
            // Repeated collection does not double the zero-wait visit or skew its mean.
            sample(c,1000000,1000,0L,"P1_Place","processTriageAssessment","TriageService");
            sample(c,1040000,1900,12L,"P1_Place","processTriageAssessment","TriageService");
            sample(c,1040000,1900,14L,"P1_Place","processTriageAssessment","TriageService");
            sample(c,3030000,2000,-1L,"P1_Place","processTriageAssessment","TriageService");
            sample(c,3040000,2100,null,"P1_Place","processTriageAssessment","TriageService");
            sample(c,2020000,2200,30L,null,"processTriageAssessment",null);
            sample(c,2030000,2300,35L,"P1_Place","processTriageAssessment","TriageService");
            ServiceDisplayNames.record(c,2000000,2030000,"P2_Place","processTriageAssessment",2300,"TriageService");
            sample(c,999010000,2400,500L,"P1_Place","processTriageAssessment","TriageService");
            sample(c,1050000,2500,25L,null,"legacyWork",null);
            try(Statement s=c.createStatement()) { s.executeUpdate("UPDATE SERVICECONTRIBUTION SET serviceName='P6_Place' WHERE sequenceID=1050000"); }
            ServiceQueueTimingView.Data data=ServiceQueueTimingView.load(c);
            if(data.summaries.size()!=7||data.invalidVisits!=3||data.unresolvedVisits!=2||data.duplicateRows!=2)
                throw new AssertionError("Grouping/omissions: "+data.summaries.size()+" "+data.note());
            ServiceQueueTimingView.Summary v1=find(data,"P1_Place","processTriageAssessment","v001");
            if(v1.count!=2||v1.meanMs!=20||v1.medianMs!=20||v1.p95Ms!=40||v1.maxMs!=40)
                throw new AssertionError("Zero wait, even median or duplicate handling failed");
            if(find(data,"P1_Place","processTriageAssessment","v002").meanMs!=200
                    ||find(data,"P1_Place","processTriageAssessment","v003").meanMs!=300)
                throw new AssertionError("Versions were merged");
            if(find(data,"P2_Place","processTriageAssessment","v001").meanMs!=80
                    ||find(data,"P4_Place","federatedRadiologyRequest","v001").meanMs!=50
                    ||find(data,"P4_Place","processImagingRequest","v003").meanMs!=60)
                throw new AssertionError("Distinct host/operation queues were merged");
            data.exportCsv(Path.of("service-queue-statistics.csv"));
            String csv=Files.readString(Path.of("service-queue-statistics.csv"));
            if(!csv.contains("\"RadiologyService, \"\"Audit\"\"\"")) throw new AssertionError("CSV quoting lost service identity");
            ServiceQueueTimingView panel=new ServiceQueueTimingView(data);
            render(panel,"service-queue-fixture.png");
            // The original chart retains arrival/version lanes but cannot expose maxima as a wait fraction.
            SwingGanttChart_WithLatency_v1d overview=new SwingGanttChart_WithLatency_v1d();
            String report=overview.generateWorkflowSummaryReport();
            if(!report.contains("Bar size does not encode duration")||report.contains("Maxima Ratio")||report.contains("SUM OF MAXIMA")||report.contains("v999"))
                throw new AssertionError("Misleading overview totals remain");
            render(overview,"workflow-overview-fixture.png");
        }
        System.out.println("PASS: per-host/operation/version waits; zero waits; mean/median/P95; duplicate/conflicting/null/negative observations; ambiguous-host omission; admin exclusion; legacy host fallback; CSV escaping; queue and overview rendering");
    }
    private static ServiceQueueTimingView.Summary find(ServiceQueueTimingView.Data data,String place,String operation,String version) {
        return data.summaries.stream().filter(s->s.place.equals(place)&&s.operation.equals(operation)&&s.version.equals(version)).findFirst().orElseThrow();
    }
    private static void sample(Connection c,long token,long arrival,Long wait,String place,String operation,String logical) throws Exception {
        long workflow=token/1000000*1000000;
        try(PreparedStatement p=c.prepareStatement("INSERT INTO SERVICECONTRIBUTION (workflowBase,sequenceID,serviceName,operation,arrivalTime,queueTime,serviceTime,totalTime,workflowStartTime) VALUES (?,?,?,?,?,?,80,100,?)")) {
            p.setLong(1,workflow); p.setLong(2,token); p.setString(3,"v"+String.format("%03d",token/1000000)); p.setString(4,operation);
            p.setLong(5,arrival); if(wait==null) p.setNull(6,java.sql.Types.BIGINT); else p.setLong(6,wait);
            p.setLong(7,arrival-10); p.executeUpdate();
        }
        if(place!=null) ServiceDisplayNames.record(c,workflow,token,place,operation,arrival,logical);
    }
    private static void render(javax.swing.JPanel panel,String filename) throws Exception {
        panel.setSize(panel.getPreferredSize());
        BufferedImage image=new BufferedImage(panel.getWidth(),panel.getHeight(),BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics=image.createGraphics(); panel.paint(graphics); graphics.dispose();
        ImageIO.write(image,"png",new File(filename));
    }
}
