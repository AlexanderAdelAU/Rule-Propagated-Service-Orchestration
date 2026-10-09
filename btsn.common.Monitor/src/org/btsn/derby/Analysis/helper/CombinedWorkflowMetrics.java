package org.btsn.derby.Analysis.helper;

import org.btsn.derby.Analysis.PetriNetAnalyzer;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Read-only workflow elapsed intervals and independent service-visit queue maxima. */
public final class CombinedWorkflowMetrics {
    private CombinedWorkflowMetrics() { }
    public static final String CAPTION = "Bars show measured workflow elapsed time; diamonds show maximum observed service-visit queue wait, including fork branches. Queue markers do not represent total workflow waiting time.";
    public static final class Workflow {
        public final int rootTokenId, workflowBase;
        public long generatedAt, completedAt, arrivalOrderTime;
        public long maxQueueMs;
        public int validQueueVisits, invalidQueueVisits;
        public boolean canonical;
        public String services = "Unresolved";
        public String process = WorkflowProcessNames.UNKNOWN;
        private Workflow(int base, int root) { workflowBase=base; rootTokenId=root; }
        public boolean hasDuration() { return canonical && generatedAt>0 && completedAt>=generatedAt; }
        public long durationMs() {
            if (!hasDuration()) throw new IllegalStateException("No measured elapsed interval");
            return completedAt-generatedAt;
        }
        public boolean hasQueueMaximum() { return validQueueVisits>0; }
    }
    private static final class Visit {
        int base, token;
        long orderingTime=Long.MAX_VALUE;
        boolean invalid;
        final Set<Long> waits=new TreeSet<>();
    }
    private static String tokenKey(int base,int token) { return base+"/"+token; }
    private static boolean hasTable(Connection c,String name) throws Exception {
        try (ResultSet r=c.getMetaData().getTables(null,"APP",name,new String[]{"TABLE"})) { return r.next(); }
    }
    public static List<Workflow> load(Connection c) throws Exception {
        Map<String,Workflow> roots=new TreeMap<>(), owners=new HashMap<>();
        Set<Integer> canonicalBases=new HashSet<>();
        ServiceDisplayNames names=ServiceDisplayNames.load(c);
        WorkflowProcessNames processes=WorkflowProcessNames.load(c);
        if (hasTable(c,"CONSOLIDATED_TRANSITION_FIRINGS")) {
            List<Integer> bases=new ArrayList<>();
            try (Statement s=c.createStatement(); ResultSet r=s.executeQuery(
                    "SELECT DISTINCT workflowBase FROM CONSOLIDATED_TRANSITION_FIRINGS WHERE eventType='GENERATED'")) {
                while(r.next()) if(r.getInt(1)/1000000!=999) bases.add(r.getInt(1));
            }
            PetriNetAnalyzer analyzer=new PetriNetAnalyzer();
            for(int base:bases) {
                canonicalBases.add(base);
                PetriNetAnalyzer.CanonicalWorkflowAnalysis analysis=analyzer.analyzeCanonicalWorkflows(base);
                for(PetriNetAnalyzer.WorkflowInstanceSummary instance:analysis.instances.values()) {
                    if(instance.rootTokenId/1000000==999) continue;
                    Workflow w=new Workflow(base,instance.rootTokenId);
                    w.canonical=true; w.generatedAt=instance.generatedAt; w.arrivalOrderTime=instance.generatedAt;
                    w.completedAt=analysis.completedRoots.contains(instance.rootTokenId)?instance.completedAt:0;
                    w.services=names.familyServices(w.rootTokenId);
                    roots.put(tokenKey(base,w.rootTokenId),w);
                    // Genealogy, not decimal token truncation, defines parallel family membership.
                    for(int member:instance.members) owners.put(tokenKey(base,member),w);
                }
            }
        }
        Map<String,Visit> visits=new TreeMap<>();
        if(hasTable(c,"SERVICECONTRIBUTION")) try(Statement s=c.createStatement(); ResultSet r=s.executeQuery(
                "SELECT workflowBase,sequenceID,operation,arrivalTime,queueTime,workflowStartTime FROM SERVICECONTRIBUTION")) {
            while(r.next()) {
                int base=r.getInt("workflowBase"),token=r.getInt("sequenceID");
                if(base/1000000==999||token/1000000==999) continue;
                long arrival=r.getLong("arrivalTime");
                String operation=r.getString("operation");
                String key=tokenKey(base,token)+"/"+arrival+"/"+operation;
                Visit v=visits.computeIfAbsent(key,k->new Visit());
                v.base=base; v.token=token;
                long queue=r.getLong("queueTime");
                if(r.wasNull()||queue<0||arrival<=0||token<=0||operation==null||operation.trim().isEmpty()) v.invalid=true;
                else v.waits.add(queue);
                long start=r.getLong("workflowStartTime");
                if(start>0) v.orderingTime=Math.min(v.orderingTime,start);
            }
        }
        for(Visit v:visits.values()) {
            Workflow w=owners.get(tokenKey(v.base,v.token));
            if(w==null) {
                // Older observations can show a queue marker, but cannot supply a measured bar.
                // Do not invent extra roots for orphan children of a canonical run.
                if(canonicalBases.contains(v.base)||v.token<=0) continue;
                int root=v.token-v.token%org.btsn.constants.VersionConstants.TOKEN_INCREMENT;
                w=roots.computeIfAbsent(tokenKey(v.base,root),k->new Workflow(v.base,root));
                w.arrivalOrderTime=w.arrivalOrderTime==0?v.orderingTime:Math.min(w.arrivalOrderTime,v.orderingTime);
                w.services=names.familyServices(root);
            }
            // Exact repeats contribute once. Conflicting/null/negative observations stay unknown.
            if(v.invalid||v.waits.size()!=1) w.invalidQueueVisits++;
            else { w.validQueueVisits++; w.maxQueueMs=Math.max(w.maxQueueMs,v.waits.iterator().next()); }
        }
        if(hasTable(c,"PROCESSMEASUREMENTS")) try(Statement s=c.createStatement(); ResultSet r=s.executeQuery(
                "SELECT sequenceID,workflowStartTime FROM PROCESSMEASUREMENTS")) {
            while(r.next()) {
                int token=r.getInt(1),base=token/1000000*1000000;
                if(token<=0||base/1000000==999||canonicalBases.contains(base)) continue;
                int root=token-token%org.btsn.constants.VersionConstants.TOKEN_INCREMENT;
                Workflow w=roots.computeIfAbsent(tokenKey(base,root),k->new Workflow(base,root));
                long start=r.getLong(2);
                if(start>0) w.arrivalOrderTime=w.arrivalOrderTime==0?start:Math.min(w.arrivalOrderTime,start);
                w.services=names.familyServices(root);
            }
        }
        // Captured business labels follow the same genealogy as the queue measurements.
        Map<Workflow,Set<String>> capturedNames=new HashMap<>();
        if(hasTable(c,ServiceDisplayNames.TABLE)) try(Statement s=c.createStatement();ResultSet r=s.executeQuery(
                "SELECT workflowBase,sequenceID,logicalService FROM "+ServiceDisplayNames.TABLE)) {
            while(r.next()) {
                int base=r.getInt(1),token=r.getInt(2);
                Workflow w=owners.get(tokenKey(base,token));
                if(w==null&&!canonicalBases.contains(base))
                    w=roots.get(tokenKey(base,token-token%org.btsn.constants.VersionConstants.TOKEN_INCREMENT));
                String label=r.getString(3);
                if(w!=null&&label!=null&&!label.trim().isEmpty())
                    capturedNames.computeIfAbsent(w,k->new TreeSet<>()).add(label);
            }
        }
        for(Map.Entry<Workflow,Set<String>> e:capturedNames.entrySet()) e.getKey().services=String.join(" / ",e.getValue());
        List<Workflow> result=new ArrayList<>(roots.values());
        for (Workflow w : result) w.process=processes.forRoot(w.rootTokenId,w.generatedAt);
        result.sort(Comparator.comparingLong((Workflow w)->w.arrivalOrderTime>0?w.arrivalOrderTime:Long.MAX_VALUE)
                .thenComparingInt(w->w.rootTokenId));
        return result;
    }
}
