package org.btsn.derby.Analysis;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.List;
import javax.imageio.ImageIO;
import org.btsn.observation.WorkflowRunMetadata;

/** Provenance follows the submitted token and timestamp, including reused IDs and concurrent versions. */
public final class WorkflowProcessNamesCheck {
    private static final String RADIOLOGY = "healthcare/Workflow/Federated_Radiology_Workflow";
    private static final String CANARY = "healthcare/Workflow/Triage_CanaryTest_Workflow";
    private static final String EMERGENCY = "healthcare/Workflow/Emergency_Department_Patient_Workflow";
    public static void main(String[] args) throws Exception {
        check(WorkflowRunMetadata.directory().equals(Path.of(args[0]).toAbsolutePath().normalize().resolve("WorkflowRunMetadata")),
                "Prepared runtime did not find the shared Monitor observation directory");
        Path metadata = Path.of("metadata").toAbsolutePath();
        System.setProperty("btsn.workflow.metadata.dir", metadata.toString());
        new BuildServiceAnalysisDatabase().initializeDatabase();
        try (Connection c = DriverManager.getConnection("jdbc:derby:ServiceAnalysisDataBase")) {
            long radiology = submit("healthcare", RADIOLOGY, "v001", 1000000, false);
            long canary = submit("healthcare", CANARY, "v002", 2000000, false);
            long emergency = submit("healthcare", EMERGENCY, "v003", 3000000, false);
            long model = submit("petrinet", "models/Workflow/Double_Join_Workflow", "v004", 4000000, true);
            event(c,1000000,radiology,"GENERATED","SHARED_SOURCE");
            event(c,2000000,canary,"GENERATED","SHARED_SOURCE");
            event(c,3000000,emergency,"GENERATED","SHARED_SOURCE");
            event(c,4000000,model,"GENERATED","EVENT_GENERATOR");
            event(c,1000000,radiology+200,"TERMINATE","TERMINATE");
            event(c,2000000,canary+250,"TERMINATE","TERMINATE");
            event(c,3000000,emergency+300,"TERMINATE","TERMINATE");
            // An older launch reusing the same ID must never supply the current name.
            WorkflowRunMetadata.record("old/Wrong_Process", "v001", 1000000, radiology-1);
            // Multiple definitions can coexist under one version; show both, never infer from the version.
            event(c,1001000,500,"GENERATED","OLD_SOURCE");
            WorkflowRunMetadata.record("models/Another_Process", "v001", 1001000, 500);
            event(c,5000000,600,"GENERATED","LEGACY_GENERATOR");
            WorkflowRunMetadata.record("incorrect/version", "v002", 5000000, 600);
            WorkflowRunMetadata.record("admin", "v999", 999000000, 700);
            Files.writeString(metadata.resolve("600-5000000-broken.json"), "{broken");
            Files.writeString(metadata.resolve("500-1001000-incomplete.tmp"), "{unfinished");
            WorkflowProcessNames names = WorkflowProcessNames.load(c);
            check(names.forRoot(1000000,radiology).equals(RADIOLOGY),"Stale generation or duplicate source overrode radiology");
            check(names.forRoot(2000000,canary).equals(CANARY),"Concurrent canary identity lost");
            check(names.forRoot(3000000,emergency).equals(EMERGENCY),"Concurrent emergency identity lost");
            check(names.forRoot(4000000,model).equals("models/Workflow/Double_Join_Workflow"),"Petri-net join submission lost process identity");
            check(names.forRoot(5000000,600).equals("Process not captured (source: LEGACY_GENERATOR)"),"Old result or mismatched version acquired an invented process");
            check(names.caption(1000000).contains(RADIOLOGY) && names.caption(1000000).contains("models/Another_Process")
                    && !names.caption(-1).contains("Wrong_Process") && !names.caption(-1).contains("v999"),"Version caption assumed one deployment or included administration");
            List<CombinedWorkflowMetrics.Workflow> workflows = CombinedWorkflowMetrics.load(c);
            check(workflows.stream().anyMatch(w -> w.rootTokenId==1000000 && w.process.equals(RADIOLOGY) && w.durationMs()==200),
                    "Process attachment changed timing or root membership");
            SwingGanttChart_WithLatency_v1d chart = new SwingGanttChart_WithLatency_v1d();
            check(chart.processDescription(false).contains(EMERGENCY) && chart.processDescription(true).contains("v002: Triage CanaryTest Workflow"),
                    "Chart omitted the concurrent version/process mapping");
            chart.exportToLaTeX("process-labelled-workflow.tex");
            chart.exportToLaTeXTable("process-labelled-table.tex");
            check(Files.readString(Path.of("process-labelled-workflow.tex")).contains("Emergency\\_Department\\_Patient\\_Workflow")
                    && Files.readString(Path.of("process-labelled-table.tex")).contains("Triage\\_CanaryTest\\_Workflow")
                    && chart.generateWorkflowSummaryReport().contains(RADIOLOGY),"Export omitted exact captured process names");
            render(chart,"process-labelled-workflow.png");
            chart.setLightQueueBars(true);
            render(chart,"process-labelled-queue-shading.png");
            MeasuredWorkflowTimeline timeline = new MeasuredWorkflowTimeline(MeasuredWorkflowTimeline.loadFromDatabase());
            check(timeline.getIntervals().stream().anyMatch(i -> i.process.equals(CANARY) && i.durationMs()==250),"Timeline lost name or measured duration");
            render(timeline,"process-labelled-timeline.png");
            ServiceQueueTimingView.Data queues = ServiceQueueTimingView.load(c);
            check(queues.processNames.caption(-1).contains(EMERGENCY),"Queue view lost recorded workflow context");
            WorkflowSpatialView spatial = new WorkflowSpatialView();
            check(spatial.processDescription().contains(EMERGENCY),"Spatial view omitted recorded process");
            WorkflowRunMetadata.record("different/Conflicting_Process", "v001", 1000000, radiology);
            check(WorkflowProcessNames.load(c).forRoot(1000000,radiology).startsWith("Conflicting process metadata:"),"Conflicting provenance silently selected a name");
        }
        // Metadata failure is nonfatal and cannot prevent or change token delivery.
        Path blocked = Path.of("blocked-metadata").toAbsolutePath(); Files.writeString(blocked,"file");
        System.setProperty("btsn.workflow.metadata.dir",blocked.toString());
        submit("healthcare",EMERGENCY,"v003",3001000,false);
        System.out.println("PASS: real UDP generator submissions; exact token/timestamp/process matches; concurrent versions; joins; reused IDs; legacy source fallback; invalid/conflicting metadata; labelled exports and views; unchanged measured durations; nonfatal metadata failure");
    }
    private static long submit(String family,String process,String version,int token,boolean join) throws Exception {
        String name=family.equals("healthcare") ? "GenericHealthcareTokenGenerator" : "GenericPetriNetTokenGenerator";
        Class<?> generator=Class.forName("org.btsn."+family+".eventgenerators."+name);
        set(generator,"processName",process); set(generator,"ruleBaseVersion",version); set(generator,"resolvedChannelId",null);
        Method build=family.equals("healthcare") ? generator.getDeclaredMethod("buildXMLPayload",String.class,int.class)
                : generator.getDeclaredMethod("buildXMLPayload",String.class,int.class,String.class,int.class);
        build.setAccessible(true);
        String payload=(String)(family.equals("healthcare") ? build.invoke(null,"{}",token) : build.invoke(null,"{}",token,"token_branch1",join?token:-1));
        long timestamp=Long.parseLong(payload.substring(payload.indexOf("<eventGeneratorTimestamp>")+25,payload.indexOf("</eventGeneratorTimestamp>")));
        Method send=generator.getDeclaredMethod("sendUDPEvent",String.class,String.class,String.class); send.setAccessible(true);
        try (DatagramSocket socket=new DatagramSocket(0,InetAddress.getLoopbackAddress())) {
            socket.setSoTimeout(2000);
            send.invoke(null,payload,"127.0.0.1",Integer.toString(socket.getLocalPort()-10000));
            DatagramPacket packet=new DatagramPacket(new byte[65535],65535); socket.receive(packet);
            check(new String(packet.getData(),0,packet.getLength()).equals(payload),"Provenance altered the UDP payload");
        } finally {
            Field socket=generator.getDeclaredField("udpSocket"); socket.setAccessible(true);
            DatagramSocket sender=(DatagramSocket)socket.get(null); if(sender!=null) sender.close(); socket.set(null,null);
        }
        return timestamp;
    }
    private static void set(Class<?> c,String name,Object value) throws Exception { Field f=c.getDeclaredField(name); f.setAccessible(true); f.set(null,value); }
    private static void event(Connection c,int token,long timestamp,String type,String source) throws Exception {
        try (PreparedStatement p=c.prepareStatement("INSERT INTO CONSOLIDATED_TRANSITION_FIRINGS (workflowBase,tokenId,transitionId,timestamp,toPlace,fromPlace,eventType) VALUES (?,?,?,?,?,?,?)")) {
            p.setInt(1,token/1000000*1000000); p.setInt(2,token); p.setString(3,source); p.setLong(4,timestamp);
            p.setString(5,"P1_Place"); p.setString(6,"Generator"); p.setString(7,type); p.executeUpdate();
        }
    }
    private static void render(javax.swing.JPanel panel,String filename) throws Exception {
        panel.setSize(panel.getPreferredSize());
        BufferedImage image=new BufferedImage(panel.getWidth(),panel.getHeight(),BufferedImage.TYPE_INT_RGB);
        Graphics2D g=image.createGraphics(); panel.paint(g); g.dispose(); ImageIO.write(image,"png",Path.of(filename).toFile());
    }
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
}
