package org.btsn.derby.Analysis;

import org.btsn.derby.Analysis.helper.MeasuredWorkflowTimeline;

import java.awt.image.BufferedImage;
import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import javax.imageio.ImageIO;

/** Recorded-event fixtures exercise elapsed time independently of service-visit sums/maxima. */
public final class MeasuredWorkflowTimelineCheck {
    public static void main(String[] args) throws Exception {
        new BuildServiceAnalysisDatabase().initializeDatabase();
        try (Connection c = DriverManager.getConnection("jdbc:derby:ServiceAnalysisDataBase")) {
            // Serial path: observed elapsed time is 200, not the largest visit (100).
            event(c,1000000,1000000,"GENERATED",1010,"Generator");
            event(c,1000000,1000000,"ENTER",1020,"P1_Place");
            event(c,1000000,1000000,"EXIT",1120,"P1_Place");
            event(c,1000000,1000000,"ENTER",1130,"P2_Place");
            event(c,1000000,1000000,"EXIT",1200,"P2_Place");
            event(c,1000000,1000000,"TERMINATE",1210,"TERMINATE");
            // Parallel children return through a join; one completed root row with 250 ms.
            event(c,2000000,2000000,"GENERATED",1020,"Generator");
            try (Statement s = c.createStatement()) {
                s.executeUpdate("INSERT INTO CONSOLIDATED_TOKEN_GENEALOGY (workflowBase,parentTokenId,childTokenId) VALUES (2000000,2000000,2000001),(2000000,2000000,2000002)");
            }
            event(c,2000000,2000001,"ENTER",1040,"P2_Place");
            event(c,2000000,2000001,"EXIT",1140,"P2_Place");
            event(c,2000000,2000002,"ENTER",1040,"P3_Place");
            event(c,2000000,2000002,"EXIT",1180,"P3_Place");
            event(c,2000000,2000002,"JOIN_CONSUMED",1185,"P4_Place");
            event(c,2000000,2000001,"ENTER",1190,"P4_Place");
            event(c,2000000,2000001,"TERMINATE",1270,"TERMINATE");
            event(c,2000000,2000001,"TERMINATE",1270,"TERMINATE"); // duplicate collector record
            // Incomplete work is shown, with no invented duration.
            event(c,3000000,3000000,"GENERATED",1000,"Generator");
            event(c,3000000,3000000,"ENTER",1030,"P1_Place");
            // Invalid clock ordering must not become a zero-length successful duration.
            event(c,4000000,4000000,"GENERATED",1400,"Generator");
            event(c,4000000,4000000,"TERMINATE",1399,"TERMINATE");
            // Administration should not appear as a patient workflow.
            event(c,999000000,999000000,"GENERATED",1005,"Generator");
            event(c,999000000,999000000,"TERMINATE",1015,"TERMINATE");
        }
        List<MeasuredWorkflowTimeline.Interval> rows = MeasuredWorkflowTimeline.loadFromDatabase();
        if (rows.size()!=4) throw new AssertionError("Expected four roots, got "+rows.size());
        MeasuredWorkflowTimeline panel = new MeasuredWorkflowTimeline(rows);
        rows = panel.getIntervals();
        if (rows.get(0).rootTokenId!=3000000 || rows.get(1).rootTokenId!=1000000)
            throw new AssertionError("Chronology was replaced by version order");
        if (rows.get(1).durationMs()!=200 || rows.get(2).durationMs()!=250)
            throw new AssertionError("Serial or parallel elapsed time was estimated from visits");
        if (rows.get(0).hasDuration() || rows.get(3).hasDuration())
            throw new AssertionError("Missing/invalid completion became a successful duration");
        panel.setSize(panel.getPreferredSize());
        BufferedImage image = new BufferedImage(panel.getWidth(),panel.getHeight(),BufferedImage.TYPE_INT_RGB);
        panel.paint(image.getGraphics());
        ImageIO.write(image,"png",new File("measured-workflow-fixture.png"));
        System.out.println("PASS: measured serial and fork/join elapsed times, duplicate completion, incomplete/invalid intervals, admin exclusion, chronological rows and headless rendering");
    }
    private static void event(Connection c,int base,int token,String type,long time,String place) throws Exception {
        try (PreparedStatement p = c.prepareStatement("INSERT INTO CONSOLIDATED_TRANSITION_FIRINGS (workflowBase,tokenId,transitionId,timestamp,toPlace,fromPlace,eventType) VALUES (?,?,?,?,?,?,?)")) {
            p.setInt(1,base); p.setInt(2,token); p.setString(3,"T_"+place); p.setLong(4,time);
            p.setString(5,place); p.setString(6,place); p.setString(7,type); p.executeUpdate();
        }
    }
}
