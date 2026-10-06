package org.btsn.derby.Analysis;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;
import org.btsn.constants.VersionConstants;

/** Read-only visit statistics. Physical host + operation defines a comparison group. */
public final class ServiceQueueTimingView extends JPanel {
    public static final class Summary {
        public final String place, operation, version, services;
        public final int count;
        public final double meanMs, medianMs;
        public final long p95Ms, maxMs;
        private Summary(String place, String operation, String version, Set<String> names, List<Long> waits) {
            this.place=place; this.operation=operation; this.version=version;
            services=names.isEmpty()?place:String.join(" / ",names);
            Collections.sort(waits);
            count=waits.size();
            meanMs=waits.stream().mapToDouble(Long::doubleValue).average().orElse(0);
            medianMs=count%2==1?waits.get(count/2):waits.get(count/2-1)/2.0+waits.get(count/2)/2.0;
            p95Ms=percentile(waits,0.95); maxMs=waits.get(count-1);
        }
        private static long percentile(List<Long> waits,double fraction) {
            return waits.get(Math.max(0,(int)Math.ceil(waits.size()*fraction)-1));
        }
    }
    public static final class Data {
        public final List<Summary> summaries;
        public final int invalidVisits, unresolvedVisits, duplicateRows;
        private Data(List<Summary> summaries,int invalid,int unresolved,int duplicates) {
            this.summaries=Collections.unmodifiableList(summaries);
            invalidVisits=invalid; unresolvedVisits=unresolved; duplicateRows=duplicates;
        }
        public String note() {
            return "Omitted visits: "+invalidVisits+" invalid/conflicting, "+unresolvedVisits
                    +" with unknown/ambiguous host. Repeated observation rows removed: "+duplicateRows+".";
        }
        public void exportCsv(Path path) throws IOException {
            StringBuilder csv=new StringBuilder("service,host,operation,version,visits,mean_ms,median_ms,p95_ms,max_ms\n");
            for(Summary s:summaries) csv.append(quote(s.services)).append(',').append(quote(s.place)).append(',')
                    .append(quote(s.operation)).append(',').append(quote(s.version)).append(',').append(s.count).append(',')
                    .append(Double.toString(s.meanMs)).append(',').append(s.medianMs).append(',').append(s.p95Ms).append(',').append(s.maxMs).append('\n');
            Files.writeString(path,csv);
        }
        private static String quote(String text) { return "\""+text.replace("\"","\"\"")+"\""; }
    }
    private static final class Visit {
        long token;
        String operation;
        final Set<Long> waits=new TreeSet<>();
        final Set<String> fallbackPlaces=new TreeSet<>();
        boolean invalid;
    }
    private static final class Group {
        String place,operation,version;
        final Set<String> names=new TreeSet<>();
        final List<Long> waits=new ArrayList<>();
    }
    private final Data data;
    private final Map<String,List<Summary>> queues=new TreeMap<>();
    private final double scaleMax;
    public ServiceQueueTimingView(Data data) {
        this.data=data;
        for(Summary s:data.summaries) queues.computeIfAbsent(s.place+"\u0000"+s.operation,k->new ArrayList<>()).add(s);
        scaleMax=Math.max(1,data.summaries.stream().mapToDouble(s->Math.max(s.meanMs,s.p95Ms)).max().orElse(1))*1.05;
        setBackground(Color.WHITE);
        setPreferredSize(new Dimension(1250,Math.max(330,205+queues.size()*68+data.summaries.size()*34)));
    }
    private static String key(long workflow,long token,String operation,long arrival) {
        return workflow+"/"+token+"/"+arrival+"/"+operation;
    }
    private static boolean hasTable(Connection c,String table) throws Exception {
        try(ResultSet r=c.getMetaData().getTables(null,"APP",table,new String[]{"TABLE"})) { return r.next(); }
    }
    private static boolean present(String value) { return value!=null&&!value.trim().isEmpty(); }
    public static Data load(Connection c) throws Exception {
        Map<String,Map<String,Set<String>>> identities=new TreeMap<>();
        if(hasTable(c,ServiceDisplayNames.TABLE)) try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT * FROM "+ServiceDisplayNames.TABLE)) {
            while(r.next()) {
                String place=r.getString("physicalPlace");
                if(!present(place)) continue;
                String key=key(r.getLong("workflowBase"),r.getLong("sequenceID"),r.getString("operation"),r.getLong("arrivalTime"));
                Set<String> names=identities.computeIfAbsent(key,k->new TreeMap<>()).computeIfAbsent(place,k->new TreeSet<>());
                String logical=r.getString("logicalService"); if(present(logical)) names.add(logical);
            }
        }
        Map<String,Visit> visits=new TreeMap<>();
        int duplicateRows=0;
        if(hasTable(c,"SERVICECONTRIBUTION")) try(Statement s=c.createStatement();ResultSet r=s.executeQuery(
                "SELECT workflowBase,sequenceID,serviceName,operation,arrivalTime,queueTime FROM SERVICECONTRIBUTION")) {
            while(r.next()) {
                long workflow=r.getLong("workflowBase"),token=r.getLong("sequenceID"),arrival=r.getLong("arrivalTime");
                if(token/1000000==999||workflow/1000000==999) continue;
                String operation=r.getString("operation"),key=key(workflow,token,operation,arrival);
                Visit visit=visits.get(key);
                if(visit==null) { visit=new Visit(); visit.token=token; visit.operation=operation; visits.put(key,visit); }
                else duplicateRows++;
                long wait=r.getLong("queueTime");
                if(r.wasNull()||wait<0||arrival<=0||token<=0||!present(operation)) visit.invalid=true;
                else visit.waits.add(wait);
                String source=r.getString("serviceName");
                // Version labels are not physical locations. Never merge their unknown queues.
                if(present(source)&&!source.matches("(?i)v[0-9]+")) visit.fallbackPlaces.add(source);
            }
        }
        Map<String,Group> groups=new TreeMap<>();
        int invalid=0,unresolved=0;
        for(Map.Entry<String,Visit> entry:visits.entrySet()) {
            Visit v=entry.getValue();
            if(v.invalid||v.waits.size()!=1) { invalid++; continue; }
            Map<String,Set<String>> captured=identities.get(entry.getKey());
            Set<String> places=captured==null?v.fallbackPlaces:captured.keySet();
            if(places.size()!=1) { unresolved++; continue; }
            String place=places.iterator().next();
            if(!v.fallbackPlaces.isEmpty()&&!v.fallbackPlaces.equals(places)) { unresolved++; continue; }
            String version=VersionConstants.getVersionFromSequenceId((int)v.token);
            String groupKey=place+"\u0000"+v.operation+"\u0000"+version;
            Group group=groups.computeIfAbsent(groupKey,k->new Group());
            group.place=place; group.operation=v.operation; group.version=version;
            group.waits.add(v.waits.iterator().next());
            if(captured!=null) group.names.addAll(captured.get(place));
        }
        List<Summary> summaries=new ArrayList<>();
        for(Group g:groups.values()) summaries.add(new Summary(g.place,g.operation,g.version,g.names,g.waits));
        return new Data(summaries,invalid,unresolved,duplicateRows);
    }
    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g=(Graphics2D)graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(35,45,65));
        g.drawString("Measured queue waits by service / host operation",20,28);
        g.drawString("Bars: mean wait. Diamonds: 95th percentile. Common millisecond scale; one sample per observed service visit.",20,50);
        g.drawString("Queue averages describe waiting; execution-order evidence is needed to establish priority selection.",20,70);
        if(queues.isEmpty()) { g.drawString("No visits with both valid queue timing and a reliable host identity.",20,115); g.drawString(data.note(),20,145); g.dispose(); return; }
        int chartWidth=Math.max(1,getWidth()-420),y=105;
        for(List<Summary> rows:queues.values()) {
            Summary first=rows.get(0);
            Set<String> names=new TreeSet<>(); for(Summary row:rows) names.add(row.services);
            g.setColor(Color.DARK_GRAY);
            String label=String.join(" / ",names);
            g.drawString(label.equals(first.place)?label:label+"   ["+first.place+"]",20,y+14);
            g.drawString("Operation: "+first.operation,20,y+34);
            y+=50;
            for(Summary row:rows) {
                g.setColor(new Color(232,235,240)); g.drawLine(190,y+23,190+chartWidth,y+23);
                g.setColor(Color.DARK_GRAY); g.drawString(row.version,35,y+16);
                int length=(int)Math.round(row.meanMs*chartWidth/scaleMax);
                g.setColor(versionColor(row.version)); if(length>0) g.fillRect(190,y,Math.max(1,length),21);
                int x=190+(int)Math.round(row.p95Ms*chartWidth/scaleMax);
                g.setColor(Color.DARK_GRAY); g.fillPolygon(new int[]{x,x+4,x,x-4},new int[]{y+6,y+10,y+14,y+10},4);
                g.drawString(String.format(java.util.Locale.ROOT,"mean %.1f ms · P95 %d · n=%d",row.meanMs,row.p95Ms,row.count),getWidth()-215,y+16);
                y+=34;
            }
            y+=18;
        }
        for(int tick=0;tick<=10;tick++) {
            int x=190+tick*chartWidth/10;
            g.drawLine(x,y-2,x,y+3);
            g.drawString(Long.toString(Math.round(scaleMax*tick/10)),x-8,y+19);
        }
        g.drawString("Queue wait (milliseconds)",190,y+42);
        g.drawString(data.note(),20,y+67);
        g.dispose();
    }
    private static Color versionColor(String version) {
        switch(version) {
            case "v001":return new Color(231,76,60);
            case "v002":return new Color(52,152,219);
            case "v003":return new Color(46,204,113);
            default:return new Color(155,89,182);
        }
    }
    public static void showWindow() {
        try {
            Data data;
            try(Connection c=DriverManager.getConnection("jdbc:derby:ServiceAnalysisDataBase")) { data=load(c); }
            JFrame frame=new JFrame("Service Queue Timings"); frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            JTabbedPane tabs=new JTabbedPane();
            tabs.addTab("Queue comparison",new JScrollPane(new ServiceQueueTimingView(data)));
            DefaultTableModel model=new DefaultTableModel(new String[]{"Service","Host","Operation","Version","Visits","Mean ms","Median ms","P95 ms","Max ms"},0) {
                @Override public boolean isCellEditable(int row,int column) { return false; }
                @Override public Class<?> getColumnClass(int column) {
                    return column<4?String.class:column==4?Integer.class:column<7?Double.class:Long.class;
                }
            };
            for(Summary s:data.summaries) model.addRow(new Object[]{s.services,s.place,s.operation,s.version,s.count,s.meanMs,s.medianMs,s.p95Ms,s.maxMs});
            JTable table=new JTable(model); table.setAutoCreateRowSorter(true); table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
            int[] widths={180,110,235,75,65,90,90,85,85};
            for(int i=0;i<widths.length;i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
            tabs.addTab("Statistics",new JScrollPane(table));
            JButton export=new JButton("Export statistics to CSV");
            export.addActionListener(e->{
                JFileChooser chooser=new JFileChooser(); chooser.setSelectedFile(new java.io.File("service-queue-timings.csv"));
                if(chooser.showSaveDialog(frame)==JFileChooser.APPROVE_OPTION) try { data.exportCsv(chooser.getSelectedFile().toPath()); }
                catch(IOException error) { JOptionPane.showMessageDialog(frame,error.getMessage(),"Export failed",JOptionPane.ERROR_MESSAGE); }
            });
            JPanel container=new JPanel(new BorderLayout()); container.add(tabs,BorderLayout.CENTER); container.add(export,BorderLayout.SOUTH);
            frame.add(container); frame.setSize(1300,750); frame.setLocationByPlatform(true); frame.setVisible(true);
        } catch(Exception e) { JOptionPane.showMessageDialog(null,e.getMessage(),"Unable to load queue timings",JOptionPane.ERROR_MESSAGE); }
    }
}
