package com.editor.animator;

import java.awt.Point;
import java.util.*;

import org.apache.log4j.Logger;

// Import existing com.editor classes
import com.editor.Canvas;
import com.editor.ProcessElement;
import com.editor.Arrow;

/**
 * Petri net topology - structural information about places, transitions, and connections.
 * 
 * This class is responsible for:
 * - Storing the graph structure (places, T_ins, T_outs, connections)
 * - Answering queries about the structure (isJoin, isFork, etc.)
 * - Element positions for distance calculations
 * - Resolving place labels to service names
 * 
 * Built from Canvas data, then used by segment generation.
 * Immutable after construction - call rebuild() to update from Canvas.
 */
public class PetriNetTopology {
    
    private static final Logger logger = Logger.getLogger(PetriNetTopology.class.getName());
    
    // Core topology maps
    private final Map<String, String> placeToTIn = new HashMap<>();
    private final Map<String, String> placeToTOut = new HashMap<>();
    private final Map<String, List<String>> tOutToNextTIns = new HashMap<>();
    private final Map<String, List<String>> tInFromTOuts = new HashMap<>();
    private final Map<String, String> tOutToNextTIn = new HashMap<>();
    private final Map<String, String> tInToPlace = new HashMap<>();
    private final Map<String, String> tOutToTerminate = new HashMap<>();
    private final Map<String, String> labelToService = new HashMap<>();
    
    // Element sets
    private final Set<String> placeIds = new HashSet<>();
    private final Set<String> tInIds = new HashSet<>();
    private final Set<String> eventGeneratorIds = new HashSet<>();
    private final Set<String> terminateIds = new HashSet<>();
    private final Set<String> explicitJoinTIns = new HashSet<>();
    private final Set<String> implicitJoinTIns = new HashSet<>();
    
    // Event generator mappings
    private final Map<String, String> eventGenToTIn = new HashMap<>();
    private final Map<String, String> eventGenImplicitForkToJoin = new HashMap<>();
    private final Map<String, Integer> eventGenForkChildCount = new HashMap<>();
    
    // Positions for distance calculations
    private final Map<String, Point> elementPositions = new HashMap<>();
    private final Map<String, Point> terminatePositions = new HashMap<>();
    private final Map<String, List<Point>> arrowWaypoints = new HashMap<>();
    
    // Animation timing constants
    private static final double VELOCITY = 0.5; // pixels per ms
    private static final long MIN_TRAVEL_DURATION = 50;
    
    // Fallback durations when positions unavailable
    public static final long FALLBACK_TRAVEL_EG_TO_TIN = 150;
    public static final long FALLBACK_TRAVEL_TO_PLACE = 120;
    public static final long FALLBACK_TRAVEL_TO_TOUT = 80;
    public static final long FALLBACK_TRAVEL_TO_NEXT = 300;
    
    /**
     * Build topology from Canvas.
     * Call this whenever the canvas structure changes.
     */
    public void buildFromCanvas(Canvas canvas) {
        if (canvas == null) {
            logger.error("Cannot build topology: Canvas is null");
            return;
        }
        
        clear();
        
        List<ProcessElement> elements = canvas.getElements();
        List<Arrow> arrows = canvas.getArrows();
        
        // First pass: identify elements and store positions
        for (ProcessElement elem : elements) {
            processElement(elem);
        }
        
        // Second pass: analyze arrows
        for (Arrow arrow : arrows) {
            processArrow(arrow);
        }
        
        logger.info("Topology built: " + placeIds.size() + " places, " +
            tInIds.size() + " T_ins, " + terminateIds.size() + " terminates");
        logger.info("Element positions stored: " + elementPositions.size() + 
            ", Arrows with waypoints: " + arrowWaypoints.size());
    }
    
    private void processElement(ProcessElement elem) {
        Point center = new Point(
            elem.getX() + elem.getWidth() / 2,
            elem.getY() + elem.getHeight() / 2
        );
        String label = elem.getLabel();
        
        switch (elem.getType()) {
            case PLACE:
                processPlace(elem, center);
                break;
            case EVENT_GENERATOR:
                processEventGenerator(elem, center);
                break;
            case TRANSITION:
                processTransition(elem, center);
                break;
        }
    }
    
    private void processPlace(ProcessElement elem, Point center) {
        String serviceName = elem.getService();
        String label = elem.getLabel();
        
        if (serviceName == null || serviceName.isEmpty()) {
            serviceName = label;
        }
        
        placeIds.add(serviceName);
        elementPositions.put(serviceName, center);
        
        if (label != null && !label.equals(serviceName)) {
            elementPositions.put(label, center);
            labelToService.put(label, serviceName);
        }
        labelToService.put(serviceName, serviceName);
    }
    
    private void processEventGenerator(ProcessElement elem, Point center) {
        String label = elem.getLabel();
        eventGeneratorIds.add(label);
        elementPositions.put(label, center);
        
        // Check for fork_behavior (implicit fork/join)
        if (elem.isForkEnabled()) {
            String joinTarget = elem.getForkJoinTarget();
            int childCount = elem.getForkChildCount();
            
            if (joinTarget != null && childCount > 1) {
                eventGenImplicitForkToJoin.put(label, joinTarget);
                eventGenForkChildCount.put(label, childCount);
                implicitJoinTIns.add(joinTarget);
                logger.info("Registered implicit fork: " + label + " -> " + joinTarget + 
                    " (expects " + childCount + " children)");
            }
        }
    }
    
    private void processTransition(ProcessElement elem, Point center) {
        String label = elem.getLabel();
        elementPositions.put(label, center);
        
        if (label != null && label.startsWith("T_in_")) {
            tInIds.add(label);
            
            // Check for explicit JoinNode
            String nodeType = elem.getNodeType();
            String nodeValue = elem.getNodeValue();
            if ("JoinNode".equals(nodeType) || "JOIN_NODE".equals(nodeValue)) {
                explicitJoinTIns.add(label);
                logger.debug("Detected explicit JoinNode: " + label);
            }
        }
        
        // Check for terminate node
        String nodeValue = elem.getNodeValue();
        if ("TERMINATE_NODE".equals(nodeValue) ||
            (label != null && label.toLowerCase().contains("terminate"))) {
            terminateIds.add(label);
            terminatePositions.put(label, new Point(elem.getX(), elem.getY()));
        }
    }
    
    private void processArrow(Arrow arrow) {
        ProcessElement source = arrow.getSource();
        ProcessElement target = arrow.getTarget();
        if (source == null || target == null) return;
        
        String sourceLabel = source.getLabel();
        String targetLabel = target.getLabel();
        
        // Event Generator -> T_in
        if (source.getType() == ProcessElement.Type.EVENT_GENERATOR &&
            target.getType() == ProcessElement.Type.TRANSITION &&
            targetLabel != null && targetLabel.startsWith("T_in_")) {
            eventGenToTIn.put(sourceLabel, targetLabel);
        }
        
        // T_in -> Place
        if (source.getType() == ProcessElement.Type.TRANSITION &&
            target.getType() == ProcessElement.Type.PLACE &&
            sourceLabel != null && sourceLabel.startsWith("T_in_")) {
            String placeService = target.getService();
            if (placeService == null || placeService.isEmpty()) {
                placeService = targetLabel;
            }
            placeToTIn.put(placeService, sourceLabel);
            tInToPlace.put(sourceLabel, placeService);
        }
        
        // Place -> T_out
        if (source.getType() == ProcessElement.Type.PLACE &&
            target.getType() == ProcessElement.Type.TRANSITION &&
            targetLabel != null && targetLabel.startsWith("T_out_")) {
            String placeService = source.getService();
            if (placeService == null || placeService.isEmpty()) {
                placeService = sourceLabel;
            }
            placeToTOut.put(placeService, targetLabel);
        }
        
        // T_out -> T_in (with fork/xor distinction)
        if (source.getType() == ProcessElement.Type.TRANSITION &&
            target.getType() == ProcessElement.Type.TRANSITION &&
            sourceLabel != null && sourceLabel.startsWith("T_out_") &&
            targetLabel != null && targetLabel.startsWith("T_in_")) {
            
            String sourceNodeType = source.getNodeType();
            boolean isTrueFork = "ForkNode".equals(sourceNodeType) || "GatewayNode".equals(sourceNodeType);
            
            if (isTrueFork) {
                tOutToNextTIns.computeIfAbsent(sourceLabel, k -> new ArrayList<>()).add(targetLabel);
                logger.debug("Fork edge: " + sourceLabel + " -> " + targetLabel);
            } else {
                if (!tOutToNextTIns.containsKey(sourceLabel)) {
                    tOutToNextTIns.computeIfAbsent(sourceLabel, k -> new ArrayList<>()).add(targetLabel);
                }
                logger.debug("XOR/Edge: " + sourceLabel + " -> " + targetLabel);
            }
            
            tInFromTOuts.computeIfAbsent(targetLabel, k -> new ArrayList<>()).add(sourceLabel);
            
            if (!tOutToNextTIn.containsKey(sourceLabel)) {
                tOutToNextTIn.put(sourceLabel, targetLabel);
            }
        }
        
        // T_out -> Terminate
        if (source.getType() == ProcessElement.Type.TRANSITION &&
            target.getType() == ProcessElement.Type.TRANSITION &&
            sourceLabel != null && sourceLabel.startsWith("T_out_") &&
            targetLabel != null && terminateIds.contains(targetLabel)) {
            tOutToTerminate.put(sourceLabel, targetLabel);
        }
        
        // Store waypoints
        List<Point> waypoints = arrow.getWaypoints();
        if (waypoints != null && !waypoints.isEmpty()) {
            String key = sourceLabel + "->" + targetLabel;
            arrowWaypoints.put(key, new ArrayList<>(waypoints));
        }
    }
    
    private void clear() {
        placeToTIn.clear();
        placeToTOut.clear();
        tOutToNextTIn.clear();
        tOutToNextTIns.clear();
        tInFromTOuts.clear();
        tInToPlace.clear();
        placeIds.clear();
        tInIds.clear();
        eventGeneratorIds.clear();
        eventGenToTIn.clear();
        tOutToTerminate.clear();
        terminateIds.clear();
        terminatePositions.clear();
        labelToService.clear();
        elementPositions.clear();
        arrowWaypoints.clear();
        eventGenImplicitForkToJoin.clear();
        eventGenForkChildCount.clear();
        implicitJoinTIns.clear();
        explicitJoinTIns.clear();
    }
    
    // ==================== Query Methods ====================
    
    /**
     * Check if T_in is a JOIN (explicit JoinNode or implicit from fork_behavior)
     */
    public boolean isJoin(String tInId) {
        return explicitJoinTIns.contains(tInId) || implicitJoinTIns.contains(tInId);
    }
    
    /**
     * Check if T_out is a FORK (has multiple outgoing paths)
     */
    public boolean isFork(String tOutId) {
        List<String> targets = tOutToNextTIns.get(tOutId);
        return targets != null && targets.size() > 1;
    }
    
    /**
     * Check if T_out leads to terminate
     */
    public boolean leadsToTerminate(String tOutId) {
        return tOutToTerminate.containsKey(tOutId);
    }
    
    /**
     * Resolve a place identifier to its service name.
     * Events may use labels (P1, P2) while topology uses service names (P1_Place).
     */
    public String resolveToServiceName(String placeIdOrLabel) {
        if (placeIdOrLabel == null) return null;
        
        if (placeIds.contains(placeIdOrLabel)) {
            return placeIdOrLabel;
        }
        
        String service = labelToService.get(placeIdOrLabel);
        if (service != null) {
            return service;
        }
        
        String withSuffix = placeIdOrLabel + "_Place";
        if (placeIds.contains(withSuffix)) {
            return withSuffix;
        }
        
        return placeIdOrLabel;
    }
    
    // ==================== Distance Calculations ====================
    
    /**
     * Calculate travel duration based on distance between elements
     */
    public long calculateTravelDuration(String fromElement, String toElement, long fallback) {
        double distance = calculatePathDistance(fromElement, toElement);
        if (distance < 0) {
            return fallback;
        }
        long duration = (long)(distance / VELOCITY);
        return Math.max(duration, MIN_TRAVEL_DURATION);
    }
    
    private double calculatePathDistance(String fromElement, String toElement) {
        Point from = elementPositions.get(fromElement);
        Point to = elementPositions.get(toElement);
        
        if (from == null || to == null) return -1;
        
        String key = fromElement + "->" + toElement;
        List<Point> waypoints = arrowWaypoints.get(key);
        
        if (waypoints == null || waypoints.isEmpty()) {
            return calculateDistance(from, to);
        }
        
        double total = 0;
        Point current = from;
        for (Point wp : waypoints) {
            total += calculateDistance(current, wp);
            current = wp;
        }
        total += calculateDistance(current, to);
        return total;
    }
    
    private double calculateDistance(Point from, Point to) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        return Math.sqrt(dx * dx + dy * dy);
    }
    
    // ==================== Getters ====================
    
    public String getTInForPlace(String placeId) { return placeToTIn.get(placeId); }
    public String getTOutForPlace(String placeId) { return placeToTOut.get(placeId); }
    public String getNextTInForTOut(String tOutId) { return tOutToNextTIn.get(tOutId); }
    public String getPlaceForTIn(String tInId) { return tInToPlace.get(tInId); }
    public String getTerminateForTOut(String tOutId) { return tOutToTerminate.get(tOutId); }
    public String getTInForEventGenerator(String eventGenId) { return eventGenToTIn.get(eventGenId); }
    
    public List<String> getAllNextTInsForTOut(String tOutId) {
        return tOutToNextTIns.getOrDefault(tOutId, Collections.emptyList());
    }
    
    public List<String> getTOutsForTIn(String tInId) {
        return tInFromTOuts.getOrDefault(tInId, Collections.emptyList());
    }
    
    public Set<String> getPlaceIds() { return Collections.unmodifiableSet(placeIds); }
    public Set<String> getTInIds() { return Collections.unmodifiableSet(tInIds); }
    public Set<String> getEventGeneratorIds() { return Collections.unmodifiableSet(eventGeneratorIds); }
    public Set<String> getTerminateIds() { return Collections.unmodifiableSet(terminateIds); }
    public Set<String> getExplicitJoinTIns() { return Collections.unmodifiableSet(explicitJoinTIns); }
    
    public Point getElementPosition(String elementId) { return elementPositions.get(elementId); }
    public Point getTerminatePosition(String terminateId) { return terminatePositions.get(terminateId); }
    
    public boolean hasTopology() { return !placeToTIn.isEmpty(); }
    
    /**
     * Debug: print topology
     */
    public void printTopology() {
        logger.info("=== PetriNetTopology ===");
        logger.info("Event Generators: " + eventGeneratorIds);
        logger.info("EventGen -> T_in: " + eventGenToTIn);
        logger.info("Places: " + placeIds);
        logger.info("T_in transitions: " + tInIds);
        logger.info("Place -> T_in: " + placeToTIn);
        logger.info("Place -> T_out: " + placeToTOut);
        logger.info("T_out -> next T_in: " + tOutToNextTIn);
        logger.info("T_out -> all T_ins (fork): " + tOutToNextTIns);
        logger.info("T_in <- all T_outs (join): " + tInFromTOuts);
        logger.info("Explicit join T_ins: " + explicitJoinTIns);
        logger.info("Implicit join T_ins: " + implicitJoinTIns);
        logger.info("Terminate nodes: " + terminateIds);
    }
}
