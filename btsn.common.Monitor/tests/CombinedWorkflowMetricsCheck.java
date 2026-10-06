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
import java.util.List;
import javax.imageio.ImageIO;

/** Elapsed intervals and queue maxima remain distinct, including parallel genealogy and unknowns. */
public final class CombinedWorkflowMetricsCheck {
    public static void main(String[] args) throws Exception {
        new BuildServiceAnalysisDatabase().initializeDatabase();
        List<CombinedWorkflowMetrics.Workflow> rows;
        try(Connection c=DriverManager.getConnection("jdbc:derby:ServiceAnalysisDataBase")) {
            event(c,1000000,1000000,"GENERATED",1010);
            event(c,1000000,1000000,"TERMINATE",1210);
            sample(c,1000000,1000000,1020,40L,"triage");
            sample(c,1000000,1000000,1090,80L,"lab");
            event(c,2000000,2000000,"GENERATED",1020);
            try(Statement s=c.createStatement()) {
                s.executeUpdate("INSERT INTO CONSOLIDATED_TOKEN_GENEALOGY (workflowBase,parentTokenId,childTokenId) VALUES (2000000,2000000,2000001),(2000000,2000000,2000002),(2000000,2000002,2090003)");
            }
            event(c,2000000,2090003,"TERMINATE",1270);
            event(c,2000000,2090003,"TERMINATE",1270); // duplicate completion
            sample(c,2000000,2000000,1030,20L,"triage");
            sample(c,2000000,2000001,1040,150L,"lab");
            sample(c,2000000,2090003,1050,180L,"imaging"); // beyond decimal root bucket
            sample(c,2000000,2090003,1050,180L,"imaging"); // duplicate visit
            sample(c,2000000,2080001,1051,9000L,"orphan"); // not part of a generated family
            event(c,3000000,3000000,"GENERATED",1000); // incomplete, still has observed queue data
            sample(c,3000000,3000000,1001,500L,"triage");
            event(c,4000000,4000000,"GENERATED",1400);
            event(c,4000000,4000000,"TERMINATE",1399); // bad clock order
            sample(c,4000000,4000000,1410,null,"triage");
            event(c,1000000,1001000,"GENERATED",1030);
            event(c,1000000,1001000,"TERMINATE",1130);
            sample(c,1000000,1001000,1040,20L,"triage");
            event(c,3000000,3001000,"GENERATED",1040);
            event(c,3000000,3001000,"TERMINATE",1040); // measured zero duration is valid
            sample(c,3000000,3001000,1041,0L,"triage"); // measured zero wait is valid
            event(c,3000000,3002000,"GENERATED",1050);
            event(c,3000000,3002000,"TERMINATE",1450);
            sample(c,3000000,3002000,1060,null,"triage");
            sample(c,3000000,3002000,1070,-1L,"lab");
            sample(c,3000000,3002000,1080,12L,"imaging");
            sample(c,3000000,3002000,1080,14L,"imaging");
            sample(c,5000000,5000001,1500,90L,"legacy");
            sample(c,5000000,5000001,1500,90L,"legacy");
            try(Statement s=c.createStatement()) {
                s.executeUpdate("INSERT INTO PROCESSMEASUREMENTS (sequenceID,workflowStartTime,elapsedTime) VALUES (6000000,1510,9876),(6000000,1510,9876)");
            }
            event(c,999000000,999000000,"GENERATED",1005);
            event(c,999000000,999000000,"TERMINATE",1015);
            sample(c,999000000,999000000,1006,10000L,"admin");
            rows=CombinedWorkflowMetrics.load(c);
        }
        check(rows.size()==9,"Canonical children/orphans/admin or legacy duplicates became extra workflows");
        check(rows.get(0).rootTokenId==3000000&&rows.get(1).rootTokenId==1000000,"Lost generation chronology");
        CombinedWorkflowMetrics.Workflow serial=find(rows,1000000),fork=find(rows,2000000);
        check(serial.durationMs()==200&&serial.maxQueueMs==80&&serial.validQueueVisits==2,"Serial duration or independent queue maximum incorrect");
        check(fork.durationMs()==250&&fork.maxQueueMs==180&&fork.validQueueVisits==3,"Nested fork membership or deduplication incorrect");
        check(fork.services.contains("imaging")&&!fork.services.contains("orphan"),"Business labels lost canonical family membership");
        check(!find(rows,3000000).hasDuration()&&find(rows,3000000).maxQueueMs==500,"Incomplete work lost observed queue marker");
        check(!find(rows,4000000).hasDuration()&&!find(rows,4000000).hasQueueMaximum(),"Invalid times became zero");
        check(find(rows,3001000).hasDuration()&&find(rows,3001000).durationMs()==0
                &&find(rows,3001000).hasQueueMaximum()&&find(rows,3001000).maxQueueMs==0,"Measured zeros became unknown");
        check(find(rows,3002000).durationMs()==400&&!find(rows,3002000).hasQueueMaximum()
                &&find(rows,3002000).invalidQueueVisits==3,"Missing/negative/conflicting waits were plotted");
        check(!find(rows,5000000).hasDuration()&&find(rows,5000000).maxQueueMs==90
                &&find(rows,5000000).validQueueVisits==1,"Legacy queue data acquired fabricated elapsed time");
        check(!find(rows,6000000).hasDuration()&&!find(rows,6000000).hasQueueMaximum(),"PROCESS-only legacy row became measured");
        SwingGanttChart_WithLatency_v1d panel=new SwingGanttChart_WithLatency_v1d();
        check(panel.tasks.size()==9&&panel.axisMaximum()==500,"Panel scale omitted a marker or included an orphan");
        double scale=panel.axisMaximum(); panel.setMaxDisplayTasks(2);
        check(panel.axisMaximum()==scale,"Display range changed the common millisecond scale");
        panel.setMaxDisplayTasks(Integer.MAX_VALUE);
        String report=panel.generateWorkflowSummaryReport();
        check(report.contains(CombinedWorkflowMetrics.CAPTION)&&report.contains("N/A")
                &&!report.contains("v999")&&!report.contains("9876"),"Summary changed measured values or unknowns");
        panel.exportToLaTeX("combined-workflow-fixture.tex");
        panel.exportToLaTeXTable("combined-workflow-table.tex");
        String figure=Files.readString(Path.of("combined-workflow-fixture.tex"));
        String table=Files.readString(Path.of("combined-workflow-table.tex"));
        check(figure.contains(CombinedWorkflowMetrics.CAPTION)&&figure.contains("500")&&figure.contains("\\fill[black]")
                &&figure.contains("$\\times$")&&!figure.contains("NaN"),"Vector export lost scale, glyphs or caption");
        check(table.contains("2000000 & v002 & 250 & 180 & 3 & 0")
                &&table.contains("3001000 & v003 & 0 & 0 & 1 & 0")
                &&table.contains("6000000 & v006 & N/A & N/A"),"Table changed measured values or unknowns");
        panel.setSize(panel.getPreferredSize());
        BufferedImage image=new BufferedImage(panel.getWidth(),panel.getHeight(),BufferedImage.TYPE_INT_RGB);
        Graphics2D g=image.createGraphics(); panel.paint(g); g.dispose();
        ImageIO.write(image,"png",new File("combined-workflow-fixture.png"));
        // Hovering the independent black marker must return its workflow, including at zero.
        boolean forkMarker=false,zeroMarker=false;
        for(int y=90;y<image.getHeight()-90;y++) for(int x=150;x<image.getWidth()-35;x++) {
            if((image.getRGB(x,y)&0xffffff)!=0) continue;
            SwingGanttChart_WithLatency_v1d.Task t=panel.getTaskAt(x,y);
            if(t!=null&&t.sequenceId==2000000) forkMarker=true;
            if(t!=null&&t.sequenceId==3001000) zeroMarker=true;
        }
        check(forkMarker&&zeroMarker,"Hover hit regions lost branch/zero markers");
        // Typical three-version density: a shared scale keeps short-route bars shorter.
        panel.tasks.clear();
        for(int i=0;i<40;i++) {
            int version=i%3+1;
            SwingGanttChart_WithLatency_v1d.Task t=new SwingGanttChart_WithLatency_v1d.Task(i+1,
                    "v00"+version,version*1000000+(i/3)*10000,0);
            t.hasElapsedTime=true; t.hasQueueTime=true; t.canonical=true;
            t.elapsedTime=version==1?180+i:version==2?450+i:2300+i*3;
            t.queueTime=version==1?70+i:version==2?120+i:10+i;
            panel.tasks.add(t);
        }
        panel.setDisplayByVersion(true); panel.setSize(panel.getPreferredSize());
        image=new BufferedImage(panel.getWidth(),panel.getHeight(),BufferedImage.TYPE_INT_RGB);
        g=image.createGraphics(); panel.paint(g); g.dispose();
        ImageIO.write(image,"png",new File("combined-three-version-fixture.png"));
        panel.exportToLaTeX("combined-three-version-fixture.tex");
        check(panel.axisMaximum()>=2417,"Dense figure clipped measured durations");
        System.out.println("PASS: measured serial/fork durations; nested genealogy queue maxima; no sum/fraction; duplicate/admin/orphan exclusion; incomplete/invalid/zero/legacy values; stable shared scale; publication exports; marker hover and rendering");
    }
    private static CombinedWorkflowMetrics.Workflow find(List<CombinedWorkflowMetrics.Workflow> rows,int root) {
        return rows.stream().filter(w->w.rootTokenId==root).findFirst().orElseThrow();
    }
    private static void check(boolean condition,String reason) { if(!condition) throw new AssertionError(reason); }
    private static void event(Connection c,int base,int token,String type,long time) throws Exception {
        try(PreparedStatement p=c.prepareStatement("INSERT INTO CONSOLIDATED_TRANSITION_FIRINGS (workflowBase,tokenId,transitionId,timestamp,toPlace,fromPlace,eventType) VALUES (?,?,?,?,?,?,?)")) {
            p.setInt(1,base); p.setInt(2,token); p.setString(3,"T_TERMINATE"); p.setLong(4,time);
            String place=type.equals("GENERATED")?"Generator":"TERMINATE";
            p.setString(5,place); p.setString(6,place); p.setString(7,type); p.executeUpdate();
        }
    }
    private static void sample(Connection c,int base,int token,long arrival,Long wait,String operation) throws Exception {
        try(PreparedStatement p=c.prepareStatement("INSERT INTO SERVICECONTRIBUTION (workflowBase,sequenceID,serviceName,operation,arrivalTime,queueTime,serviceTime,totalTime,workflowStartTime) VALUES (?,?,?,?,?,?,80,100,?)")) {
            p.setInt(1,base); p.setInt(2,token); p.setString(3,"v"+String.format("%03d",base/1000000)); p.setString(4,operation);
            p.setLong(5,arrival); if(wait==null) p.setNull(6,java.sql.Types.BIGINT); else p.setLong(6,wait);
            p.setLong(7,arrival-10); p.executeUpdate();
        }
        ServiceDisplayNames.record(c,base,token,"P1_Place",operation,arrival,operation+"Service");
    }
}
