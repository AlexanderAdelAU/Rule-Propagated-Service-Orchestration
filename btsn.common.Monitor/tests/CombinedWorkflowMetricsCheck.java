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
        SwingGanttChart_WithLatency_v1d panel=new SwingGanttChart_WithLatency_v1d(false);
        check(panel.generateLaTeXFigure().contains("(2.045,4.000) rectangle (2.645,4.800)"),
                "Default publication bars did not retain the arrival slot centre");
        panel.setWideBars(false);
        check(panel.tasks.size()==9&&panel.axisMaximum()==400,"Panel scale omitted a marker or included an orphan");
        check(panel.axisMaximum("v001")==200&&panel.axisMaximum("v002")==250
                &&panel.axisMaximum("v003")==400,"Queue observations changed the measured duration scale");
        double scale=panel.axisMaximum("v002"); panel.setMaxDisplayTasks(2);
        check(panel.axisMaximum("v002")==scale,"Display range changed a version's millisecond scale");
        panel.setMaxDisplayTasks(Integer.MAX_VALUE);
        String report=panel.generateWorkflowSummaryReport();
        check(report.contains(CombinedWorkflowMetrics.CAPTION)&&report.contains("N/A")
                &&!report.contains("v999")&&!report.contains("9876"),"Summary changed measured values or unknowns");
        panel.exportToLaTeX("combined-workflow-fixture.tex");
        panel.exportToLaTeXTable("combined-workflow-table.tex");
        String figure=Files.readString(Path.of("combined-workflow-fixture.tex"));
        String table=Files.readString(Path.of("combined-workflow-table.tex"));
        check(figure.contains(CombinedWorkflowMetrics.CAPTION)&&figure.contains("400")
                &&figure.contains("Each version scales to its maximum measured workflow duration")&&!figure.contains("All lanes share")&&figure.contains("\\fill[black]")
                &&figure.contains("$\\times$")&&!figure.contains("NaN"),"Vector export lost scale, glyphs or caption");
        check(table.contains("2000000 & v002 & 250 & 180 & 3 & 0")
                &&table.contains("3001000 & v003 & 0 & 0 & 1 & 0")
                &&table.contains("6000000 & v006 & N/A & N/A"),"Table changed measured values or unknowns");
        panel.setSize(panel.getPreferredSize());
        BufferedImage image=new BufferedImage(panel.getWidth(),panel.getHeight(),BufferedImage.TYPE_INT_RGB);
        Graphics2D g=image.createGraphics(); panel.paint(g); g.dispose();
        ImageIO.write(image,"png",new File("combined-workflow-fixture.png"));
        int redHeight=longestColourRun(image,0xe74c3c),blueHeight=longestColourRun(image,0x3498db);
        check(redHeight==blueHeight&&redHeight>40,"Per-version bars did not fill equal relative axis ranges");
        panel.setIndependentLaneScales(false);
        check(panel.axisMaximum("v001")==400&&panel.axisMaximum("v002")==400,"Shared mode did not use one absolute scale");
        String shared=panel.generateLaTeXFigure();
        check(shared.contains("All versions share the maximum measured workflow duration")
                &&!shared.contains("Each version scales to its maximum measured workflow duration"),"Shared export retained per-version caption");
        Files.writeString(Path.of("combined-shared-scale-fixture.tex"),shared);
        BufferedImage sharedImage=new BufferedImage(panel.getWidth(),panel.getHeight(),BufferedImage.TYPE_INT_RGB);
        g=sharedImage.createGraphics(); panel.paint(g); g.dispose();
        check(longestColourRun(sharedImage,0xe74c3c)<longestColourRun(sharedImage,0x3498db),
                "Shared renderer lost absolute duration comparison");
        panel.setIndependentLaneScales(true);
        // Hovering the independent black marker must return its workflow, including at zero.
        boolean forkMarker=false,zeroMarker=false;
        for(int y=90;y<image.getHeight()-90;y++) for(int x=150;x<image.getWidth()-35;x++) {
            if((image.getRGB(x,y)&0xffffff)!=0) continue;
            SwingGanttChart_WithLatency_v1d.Task t=panel.getTaskAt(x,y);
            if(t!=null&&t.sequenceId==2000000) forkMarker=true;
            if(t!=null&&t.sequenceId==3001000) zeroMarker=true;
        }
        check(forkMarker&&zeroMarker,"Hover hit regions lost branch/zero markers");
        // Alternative queue bars retain measured values, axis ranges, zero and unknown semantics.
        panel.setLightQueueBars(true);
        check(panel.axisMaximum("v001")==200&&panel.axisMaximum("v002")==250
                &&panel.axisMaximum("v003")==400,"Queue style changed axis ranges");
        String lightReport=panel.generateWorkflowSummaryReport();
        check(lightReport.contains("lighter lower shading")&&lightReport.contains("no lighter shading")
                &&!lightReport.contains("diamonds"),"Queue bar summary retained diamond descriptions");
        check(lightReport.substring(lightReport.indexOf("Arrival | Root sequence"))
                .equals(report.substring(report.indexOf("Arrival | Root sequence"))),"Queue style changed measured values");
        String lightFigure=panel.generateLaTeXFigure();
        check(lightFigure.contains("Dashed outlines: queue wait measured, elapsed interval unavailable (2)"),
                "Queue-only annotation lost its measurement meaning or count");
        check(lightFigure.contains("\\definecolor{queue0}")&&lightFigure.contains("\\fill[queue1]")
                &&lightFigure.contains("\\draw[queue2,thick]")&&lightFigure.contains("dashed")&&lightFigure.contains("\\uparrow")&&!lightFigure.contains("\\fill[black]")
                &&!lightFigure.contains("diamonds")&&!lightFigure.contains("NaN"),"Queue bar export lost shade/zero/caption or retained diamonds");
        Files.writeString(Path.of("combined-queue-bars-fixture.tex"),lightFigure);
        BufferedImage lightImage=new BufferedImage(panel.getWidth(),panel.getHeight(),BufferedImage.TYPE_INT_RGB);
        g=lightImage.createGraphics(); panel.paint(g); g.dispose();
        ImageIO.write(lightImage,"png",new File("combined-queue-bars-fixture.png"));
        int darkRed=new java.awt.Color(0xe74c3c).darker().getRGB()&0xffffff;
        int darkBlue=new java.awt.Color(0x3498db).darker().getRGB()&0xffffff;
        check(longestColourRun(lightImage,darkRed)==longestColourRun(image,darkRed)
                &&longestColourRun(lightImage,darkBlue)==longestColourRun(image,darkBlue),
                "Lower queue shading changed the original bar outline height");
        int paleBlue=SwingGanttChart_WithLatency_v1d.queueBarColor(new java.awt.Color(0x3498db)).getRGB()&0xffffff;
        int paleGreen=SwingGanttChart_WithLatency_v1d.queueBarColor(new java.awt.Color(0x2ecc71)).getRGB()&0xffffff;
        int[] blueBounds=colourColumns(image,0x3498db),shadeBounds=colourColumns(lightImage,paleBlue);
        check(blueBounds[0]==shadeBounds[0]&&blueBounds[1]==shadeBounds[1],
                "Queue shading is not aligned inside the original bar");
        int paleBlueHeight=longestColourRun(lightImage,paleBlue);
        check(paleBlueHeight>0&&Math.abs(paleBlueHeight/(double)blueHeight-180.0/250.0)<0.04,
                "Lighter queue bar height does not encode the independent queue maximum");
        boolean lightFork=false,lightZero=false,lightIncomplete=false;
        for(int y=112;y<lightImage.getHeight()-90;y++) for(int x=150;x<lightImage.getWidth()-35;x++) {
            int colour=lightImage.getRGB(x,y)&0xffffff;
            if(colour!=paleBlue&&colour!=paleGreen) continue;
            SwingGanttChart_WithLatency_v1d.Task t=panel.getTaskAt(x,y);
            if(t!=null&&t.sequenceId==2000000) lightFork=true;
            if(t!=null&&t.sequenceId==3001000) lightZero=true;
            if(t!=null&&t.sequenceId==3000000) lightIncomplete=true;
        }
        check(lightFork&&lightZero&&lightIncomplete,"Queue bar hover lost fork, measured zero or incomplete workflow");
        SwingGanttChart_WithLatency_v1d.Task forkTask=panel.tasks.stream().filter(t->t.sequenceId==2000000).findFirst().orElseThrow();
        long originalWait=forkTask.queueTime; forkTask.queueTime=300;
        check(panel.axisMaximum("v002")==250,"Oversized queue observation changed the workflow-duration scale");
        String capped=panel.generateLaTeXFigure();
        check(capped.contains("\\fill[queue1] (2.233,4.000) rectangle (2.457,4.800)")
                &&capped.contains("\\uparrow")&&panel.generateWorkflowSummaryReport().contains("250 | 300"),
                "Oversized queue shading expanded the elapsed bar or concealed the measured value");
        forkTask.queueTime=originalWait;
        panel.setIndependentLaneScales(false);
        Files.writeString(Path.of("combined-queue-bars-shared-fixture.tex"),panel.generateLaTeXFigure());
        check(panel.generateLaTeXFigure().contains("All versions share the maximum measured workflow duration"),
                "Queue bar export ignored shared mode");
        panel.setIndependentLaneScales(true); panel.setLightQueueBars(false);
        check(panel.generateLaTeXFigure().contains("\\fill[black]")&&panel.generateWorkflowSummaryReport().equals(report),
                "Switching back to diamonds changed metrics or captions");
        // Typical three-version density: independently scaled lanes keep all three versions readable.
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
        check(panel.axisMaximum("v001")==219&&panel.axisMaximum("v002")==487
                &&panel.axisMaximum("v003")==2414,"Dense figure did not scale to exact workflow maxima");
        panel.setLightQueueBars(true);
        g=image.createGraphics(); panel.paint(g); g.dispose();
        ImageIO.write(image,"png",new File("combined-three-version-queue-bars-fixture.png"));
        panel.exportToLaTeX("combined-three-version-queue-bars-fixture.tex");
        String completeFigure=panel.generateLaTeXFigure();
        check(!completeFigure.contains("Dashed outlines")&&!completeFigure.contains("queue-only")
                &&!completeFigure.contains("elapsed interval unavailable")
                &&completeFigure.contains("Showing 40 of 40 observed root workflows"),
                "Complete run retained misleading missing-measurement notes");
        String completeTable=panel.generateLaTeXTable();
        int narrowRun=widestColourRun(image,0xe74c3c);
        panel.setWideBars(true);
        BufferedImage wideImage=new BufferedImage(panel.getWidth(),panel.getHeight(),BufferedImage.TYPE_INT_RGB);
        g=wideImage.createGraphics(); panel.paint(g); g.dispose();
        check(widestColourRun(wideImage,0xe74c3c)>2*narrowRun
                &&longestColourRun(wideImage,0xe74c3c)==longestColourRun(image,0xe74c3c),
                "Wide style did not increase density or changed measured bar height");
        check(panel.generateLaTeXTable().equals(completeTable),"Bar width changed values or workflow ordering");
        boolean wideHover=false;
        for(int y=160;y<wideImage.getHeight()-90;y++) for(int x=150;x<wideImage.getWidth()-35;x++) {
            if((wideImage.getRGB(x,y)&0xffffff)!=0xe74c3c) continue;
            SwingGanttChart_WithLatency_v1d.Task t=panel.getTaskAt(x,y);
            if(t!=null&&t.sequenceId==1000000) wideHover=true;
        }
        check(wideHover,"Wide style lost root hover identity");
        ImageIO.write(wideImage,"png",new File("combined-three-version-wide-fixture.png"));
        Files.writeString(Path.of("combined-three-version-wide-fixture.tex"),panel.generateLaTeXFigure());
        panel.setWideBars(false);
        check(panel.generateLaTeXFigure().equals(completeFigure),"Returning to narrow bars did not restore the original figure");
        panel.setMaxDisplayTasks(20);
        check(panel.generateLaTeXFigure().contains("Showing 20 of 40 observed root workflows")
                &&panel.axisMaximum("v001")==219,"Truncation concealed omitted roots or changed the scale");
        System.out.println("PASS: measured serial/fork durations; nested genealogy queue maxima; no sum/fraction; duplicate/admin/orphan exclusion; incomplete/invalid/zero/legacy values; stable per-version/shared scales; publication exports; diamond/lower-shading and wide/narrow switching; unchanged heights, aligned proportional shading, root hover, conditional measurement notes and display-range counts");
    }
    private static int widestColourRun(BufferedImage image,int colour) {
        int longest=0;
        for(int y=112;y<image.getHeight()-90;y++) {
            int run=0;
            for(int x=150;x<image.getWidth()-35;x++) {
                if((image.getRGB(x,y)&0xffffff)==colour) { run++; longest=Math.max(longest,run); }
                else run=0;
            }
        }
        return longest;
    }
    private static int[] colourColumns(BufferedImage image,int colour) {
        int min=image.getWidth(),max=-1;
        for(int x=150;x<image.getWidth()-35;x++) for(int y=112;y<image.getHeight()-90;y++)
            if((image.getRGB(x,y)&0xffffff)==colour) { min=Math.min(min,x); max=Math.max(max,x); }
        return new int[]{min,max};
    }
    private static int longestColourRun(BufferedImage image,int colour) {
        int longest=0;
        for(int x=0;x<image.getWidth();x++) {
            int run=0;
            for(int y=0;y<image.getHeight();y++) {
                if((image.getRGB(x,y)&0xffffff)==colour) { run++; longest=Math.max(longest,run); }
                else run=0;
            }
        }
        return longest;
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
