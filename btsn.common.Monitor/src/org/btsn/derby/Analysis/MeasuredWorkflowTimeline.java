package org.btsn.derby.Analysis;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import org.btsn.constants.VersionConstants;

/** Absolute elapsed-time view; parallel service durations are never added or split into a queue fraction. */
public final class MeasuredWorkflowTimeline extends JPanel {
    public static final class Interval {
        public final int rootTokenId;
        public final long generatedAt, completedAt;
        public final String services, process;
        public Interval(int rootTokenId, long generatedAt, long completedAt, String services) {
            this(rootTokenId, generatedAt, completedAt, services, WorkflowProcessNames.UNKNOWN);
        }
        public Interval(int rootTokenId, long generatedAt, long completedAt, String services, String process) {
            this.process = process;
            this.rootTokenId = rootTokenId;
            this.generatedAt = generatedAt;
            this.completedAt = completedAt;
            this.services = services;
        }
        public boolean hasDuration() { return generatedAt > 0 && completedAt >= generatedAt; }
        public long durationMs() {
            if (!hasDuration()) throw new IllegalStateException("No measured completion for " + rootTokenId);
            return completedAt - generatedAt;
        }
    }
    private final List<Interval> intervals;
    private final long origin, span;
    public MeasuredWorkflowTimeline(List<Interval> data) {
        List<Interval> sorted = new ArrayList<>(data);
        sorted.sort(Comparator.comparingLong((Interval i) -> i.generatedAt).thenComparingInt(i -> i.rootTokenId));
        intervals = Collections.unmodifiableList(sorted);
        origin = sorted.stream().filter(i -> i.generatedAt > 0).mapToLong(i -> i.generatedAt).min().orElse(0);
        long last = sorted.stream().mapToLong(i -> Math.max(i.generatedAt, i.hasDuration() ? i.completedAt : 0)).max().orElse(origin);
        span = Math.max(1, last - origin);
        setBackground(Color.WHITE);
        setPreferredSize(new Dimension(1250, Math.max(300, 180 + headerHeight(1250) + intervals.size()*32)));
        setToolTipText("");
    }
    public List<Interval> getIntervals() { return intervals; }
    public static List<Interval> loadFromDatabase() throws Exception {
        Class.forName("org.apache.derby.jdbc.EmbeddedDriver");
        List<Interval> intervals = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:derby:ServiceAnalysisDataBase");
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT DISTINCT workflowBase FROM CONSOLIDATED_TRANSITION_FIRINGS WHERE eventType='GENERATED' ORDER BY workflowBase")) {
            ServiceDisplayNames names = ServiceDisplayNames.load(c);
            WorkflowProcessNames processes = WorkflowProcessNames.load(c);
            PetriNetAnalyzer analyzer = new PetriNetAnalyzer();
            while (rs.next()) {
                int base = rs.getInt(1);
                if (base / 1000000 == 999) continue;
                PetriNetAnalyzer.CanonicalWorkflowAnalysis analysis = analyzer.analyzeCanonicalWorkflows(base);
                for (PetriNetAnalyzer.WorkflowInstanceSummary instance : analysis.instances.values()) {
                    intervals.add(new Interval(instance.rootTokenId, instance.generatedAt,
                            analysis.completedRoots.contains(instance.rootTokenId) ? instance.completedAt : 0,
                            names.familyServices(instance.rootTokenId), processes.forRoot(instance.rootTokenId, instance.generatedAt)));
                }
            }
        }
        return intervals;
    }
    public String processDescription() {
        java.util.Map<String, java.util.Set<String>> names = new java.util.TreeMap<>();
        for (Interval i : intervals) names.computeIfAbsent(VersionConstants.getVersionFromSequenceId(i.rootTokenId),
                k -> new java.util.TreeSet<>()).add(WorkflowProcessNames.shortName(i.process));
        return names.isEmpty() ? WorkflowProcessNames.UNKNOWN : "Processes: " + names.entrySet().stream()
                .map(e -> e.getKey() + ": " + String.join(" / ", e.getValue()))
                .collect(java.util.stream.Collectors.joining("; "));
    }
    private List<String> headerLines(int width) {
        return WorkflowProcessNames.wrap(processDescription(), getFontMetrics(getFont()), Math.max(40, width-40));
    }
    private int headerHeight(int width) { return headerLines(width).size() * (getFontMetrics(getFont()).getHeight()+2); }
    private Rectangle bounds(int row) {
        Interval i = intervals.get(row);
        int width = Math.max(1, getWidth()-430);
        int x = 225 + (int)((i.generatedAt-origin)*(double)width/span);
        int length = i.hasDuration() ? Math.max(2,(int)(i.durationMs()*(double)width/span)) : 8;
        return new Rectangle(x, 95+headerHeight(getWidth())+row*32, length, 19);
    }
    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D)graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(35,45,65));
        g.drawString("Measured workflow timelines: generation to canonical completion", 20, 30);
        g.drawString("One root workflow per row; common time scale. Fork children belong to their root. No estimated queue/service split.",20,53);
        int header = headerHeight(getWidth()), labelY = 74;
        for (String line : headerLines(getWidth())) { g.drawString(line, 20, labelY); labelY += getFontMetrics(getFont()).getHeight()+2; }
        if (intervals.isEmpty()) { g.drawString("No GENERATED workflow observations available.",20,95); g.dispose(); return; }
        int width = Math.max(1,getWidth()-430), axisY = 115+header+intervals.size()*32;
        for (int tick=0;tick<=10;tick++) {
            int x = 225+tick*width/10;
            g.setColor(new Color(230,233,238));
            g.drawLine(x,80+header,x,axisY);
            g.setColor(Color.DARK_GRAY);
            g.drawString(Long.toString(Math.round(span*tick/10.0)),x-8,axisY+18);
        }
        for (int row=0;row<intervals.size();row++) {
            Interval i = intervals.get(row);
            Rectangle bar = bounds(row);
            String version = VersionConstants.getVersionFromSequenceId(i.rootTokenId);
            g.setColor(Color.DARK_GRAY);
            g.drawString(version+" / "+i.rootTokenId,20,bar.y+15);
            Color color = i.rootTokenId/1000000==1 ? new Color(184,66,58) : i.rootTokenId/1000000==2 ? new Color(40,107,160) : new Color(39,134,84);
            g.setColor(i.hasDuration()?color:Color.GRAY);
            if (i.hasDuration()) g.fillRect(bar.x,bar.y,bar.width,bar.height);
            else g.drawRect(bar.x,bar.y,bar.width,bar.height);
            g.setColor(Color.DARK_GRAY);
            g.drawString(i.hasDuration()?i.durationMs()+" ms":"Completion unavailable",getWidth()-185,bar.y+15);
        }
        g.drawString("Milliseconds since first recorded generation",225,axisY+42);
        g.dispose();
    }
    @Override public String getToolTipText(MouseEvent event) {
        for (int row=0;row<intervals.size();row++) if (bounds(row).contains(event.getPoint())) {
            Interval i = intervals.get(row);
            return "Process: "+i.process+"; Root "+i.rootTokenId+"; "+i.services+"; generated "+i.generatedAt+"; "
                    +(i.hasDuration()?"completed "+i.completedAt+"; elapsed "+i.durationMs()+" ms":"no usable completion timestamp");
        }
        return null;
    }
    public static void showWindow() {
        try {
            MeasuredWorkflowTimeline timeline = new MeasuredWorkflowTimeline(loadFromDatabase());
            JFrame frame = new JFrame("Measured Workflow Timelines");
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.add(new JScrollPane(timeline));
            frame.setSize(1300,700);
            frame.setLocationByPlatform(true);
            frame.setVisible(true);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null,e.getMessage(),"Unable to load workflow observations",JOptionPane.ERROR_MESSAGE);
        }
    }
}
