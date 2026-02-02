package com.editor.animator;

import java.awt.Color;
import java.awt.Point;
import java.util.*;

import org.apache.log4j.Logger;

// Import the EXISTING com.editor.Canvas
import com.editor.Canvas;

/**
 * TokenAnimator - Coordinates token animation for Petri net visualization.
 * 
 * This is the main entry point for animation. It coordinates:
 * - Topology building (via PetriNetTopology)
 * - Event parsing (via EventParser)
 * - Segment generation (via SegmentGenerator + SegmentBuilder)
 * - Fork/join synchronization (via ForkJoinSynchronizer)
 * - Post-processing (invariant enforcement)
 * - Animation state queries
 * 
 * BACKWARD COMPATIBILITY:
 * This class exports inner classes (Phase, TokenAnimState, BufferedToken, AnimationSnapshot)
 * for compatibility with existing Canvas.java which uses TokenAnimator.Phase, etc.
 */
public class TokenAnimator {
    
    private static final Logger logger = Logger.getLogger(TokenAnimator.class.getName());
    
    // ==================== Inner Classes for Canvas Compatibility ====================
    // These mirror the package-level classes but are exposed as inner classes
    // so existing code using TokenAnimator.Phase etc. continues to work.
    
    /**
     * Token animation phase - for Canvas compatibility
     */
    public enum Phase {
        AT_EVENT_GENERATOR,
        TRAVELING_TO_TIN,
        BUFFERED_AT_TIN,
        TRAVELING_TO_PLACE,
        AT_PLACE,
        TRAVELING_TO_TOUT,
        TRAVELING_TO_NEXT_TIN,
        TRAVELING_TO_TERMINATE,
        AT_TERMINATE,
        CONSUMED
    }
    
    /**
     * Current animation state of a single token (for Canvas compatibility)
     */
    public static class TokenAnimState {
        public String tokenId;
        public String version;
        public Phase phase;
        public String currentPlaceId;
        public String eventGenId;
        public String tInId;
        public String tOutId;
        public String nextTInId;
        public String terminateNodeId;
        public String fromElementId;
        public String toElementId;
        public long phaseStartTime;
        public long phaseEndTime;
        public double progress;
        public int bufferPosition;
        
        public TokenAnimState(String tokenId, String version) {
            this.tokenId = tokenId;
            this.version = version;
            this.phase = Phase.AT_EVENT_GENERATOR;
            this.progress = 0.0;
            this.bufferPosition = 0;
        }
    }
    
    /**
     * Buffered token for display
     */
    public static class BufferedToken {
        public final String tokenId;
        public final String version;
        public final long arrivalTime;
        
        public BufferedToken(String tokenId, String version, long arrivalTime) {
            this.tokenId = tokenId;
            this.version = version;
            this.arrivalTime = arrivalTime;
        }
    }
    
    /**
     * Animation snapshot at a point in time
     */
    public static class AnimationSnapshot {
        public final Map<String, TokenAnimState> tokenStates;
        public final Map<String, List<BufferedToken>> bufferStates;
        
        public AnimationSnapshot(Map<String, TokenAnimState> tokenStates,
                                Map<String, List<BufferedToken>> bufferStates) {
            this.tokenStates = tokenStates;
            this.bufferStates = bufferStates;
        }
    }
    
    // ==================== Components ====================
    
    private final PetriNetTopology topology;
    private final EventParser eventParser;
    private Canvas canvas;  // com.editor.Canvas
    
    // State
    private List<AnimationSegment> segments = new ArrayList<>();
    private List<MarkingEvent> events = new ArrayList<>();
    private long startTime = Long.MAX_VALUE;
    private long endTime = Long.MIN_VALUE;
    
    // Version colors for rendering
    private final Map<String, Color> versionColors = new HashMap<>();
    
    public TokenAnimator() {
        this.topology = new PetriNetTopology();
        this.eventParser = new EventParser();
        initializeColors();
    }
    
    private void initializeColors() {
        versionColors.put("v001", new Color(0, 100, 200));
        versionColors.put("v002", new Color(200, 50, 50));
        versionColors.put("v003", new Color(50, 150, 50));
        versionColors.put("v004", new Color(150, 100, 200));
        versionColors.put("v005", new Color(200, 150, 50));
        versionColors.put("v006", new Color(50, 150, 150));
        versionColors.put("v007", new Color(150, 50, 150));
        versionColors.put("v008", new Color(100, 100, 100));
    }
    
    // ==================== Configuration ====================
    
    public void setCanvas(Canvas canvas) {
        this.canvas = canvas;
    }
    
    // ==================== Main Entry Point ====================
    
    /**
     * Parse analyzer output and generate animation segments.
     * 
     * This is the main entry point - call this with analyzer text output.
     */
    public void parseOutput(String text) {
        clear();
        
        // Build topology from canvas
        if (canvas != null) {
            topology.buildFromCanvas(canvas);
        }
        
        // Parse events
        EventParser.ParseResult parseResult = eventParser.parse(text);
        events = parseResult.events;
        startTime = parseResult.startTime;
        endTime = parseResult.endTime;
        
        // Generate segments
        SegmentGenerator generator = new SegmentGenerator(topology);
        segments = generator.generate(events, endTime);
        
        // Pass 2: Synchronize fork points
        ForkJoinSynchronizer synchronizer = new ForkJoinSynchronizer(topology);
        synchronizer.synchronize(segments, generator.getChildBirthTOut());
        
        // Post-processing: Ensure JOIN buffer invariant
        ensureJoinBufferSegments();
        
        // Post-processing: Resolve place occupancy overlaps
        resolveOverlappingPlaceOccupancy();
        
        // Adjust time bounds
        adjustTimeBounds();
        
        logger.info("Parsed " + events.size() + " events, generated " + segments.size() + " segments");
        logger.info("Time range: " + startTime + " - " + endTime);
        
        printFinalTokenLocations();
    }
    
    /**
     * Backward compatibility aliases
     */
    public void parse(String text) { parseOutput(text); }
    public void parseAnalyzerOutput(String text) { parseOutput(text); }
    
    public void buildTopologyFromCanvas() {
        if (canvas != null) {
            topology.buildFromCanvas(canvas);
        }
    }
    
    // ==================== Post-Processing ====================
    
    /**
     * Safety net: Ensure all arrivals at JOIN T_ins have buffer segments.
     * 
     * This catches any code path that might have missed generating a buffer.
     */
    private void ensureJoinBufferSegments() {
        List<AnimationSegment> newBuffers = new ArrayList<>();
        
        for (AnimationSegment travelSeg : segments) {
            if (travelSeg.phase != com.editor.animator.Phase.TRAVELING_TO_NEXT_TIN) continue;
            if (travelSeg.toElement == null) continue;
            if (!topology.isJoin(travelSeg.toElement)) continue;
            
            // Check for existing buffer
            boolean hasBuffer = false;
            long travelEnd = travelSeg.endTime;
            String destTIn = travelSeg.toElement;
            
            for (AnimationSegment seg : segments) {
                if (!seg.tokenId.equals(travelSeg.tokenId)) continue;
                if (seg.phase != com.editor.animator.Phase.BUFFERED_AT_TIN) continue;
                if (!destTIn.equals(seg.tInId)) continue;
                
                if (Math.abs(seg.startTime - travelEnd) < 100) {
                    hasBuffer = true;
                    break;
                }
            }
            
            if (!hasBuffer) {
                // Find when token next does something
                long bufferEnd = findNextEventTime(travelSeg.tokenId, travelEnd, travelSeg.placeId);
                
                if (bufferEnd > travelEnd) {
                    newBuffers.add(new AnimationSegment(
                        travelSeg.tokenId, travelSeg.version, com.editor.animator.Phase.BUFFERED_AT_TIN,
                        travelEnd, bufferEnd,
                        travelSeg.placeId, destTIn, null,
                        null, null, null, null
                    ));
                    
                    logger.debug("POST-PROCESS: Added buffer at JOIN " + destTIn + 
                        " for " + travelSeg.tokenId);
                }
            }
        }
        
        if (!newBuffers.isEmpty()) {
            segments.addAll(newBuffers);
            segments.sort(Comparator.comparingLong(s -> s.startTime));
            logger.info("POST-PROCESS: Added " + newBuffers.size() + " missing JOIN buffers");
        }
    }
    
    private long findNextEventTime(String tokenId, long afterTime, String destPlace) {
        long nextTime = afterTime + 500; // Default
        
        for (AnimationSegment seg : segments) {
            if (!seg.tokenId.equals(tokenId)) continue;
            if (seg.startTime <= afterTime) continue;
            
            // Use package-level Phase for comparison with AnimationSegment
            if ((seg.placeId != null && seg.placeId.equals(destPlace)) ||
                seg.phase == com.editor.animator.Phase.CONSUMED) {
                return seg.startTime;
            }
        }
        
        return nextTime;
    }
    
    /**
     * Resolve overlapping place occupancy (only one token at a time).
     */
    private void resolveOverlappingPlaceOccupancy() {
        // Group AT_PLACE segments by place
        Map<String, List<AnimationSegment>> byPlace = new HashMap<>();
        for (AnimationSegment seg : segments) {
            // Use package-level Phase for comparison with AnimationSegment
            if (seg.phase == com.editor.animator.Phase.AT_PLACE && seg.placeId != null) {
                byPlace.computeIfAbsent(seg.placeId, k -> new ArrayList<>()).add(seg);
            }
        }
        
        Map<AnimationSegment, AnimationSegment> replacements = new HashMap<>();
        int resolved = 0;
        
        for (List<AnimationSegment> placeSegs : byPlace.values()) {
            if (placeSegs.size() < 2) continue;
            
            placeSegs.sort(Comparator.comparingLong(s -> s.startTime));
            
            for (int i = 0; i < placeSegs.size() - 1; i++) {
                AnimationSegment current = replacements.getOrDefault(placeSegs.get(i), placeSegs.get(i));
                
                for (int j = i + 1; j < placeSegs.size(); j++) {
                    AnimationSegment next = replacements.getOrDefault(placeSegs.get(j), placeSegs.get(j));
                    
                    if (current.tokenId.equals(next.tokenId)) continue;
                    
                    if (next.startTime < current.endTime) {
                        long delay = current.endTime - next.startTime;
                        AnimationSegment delayed = next.shifted(delay);
                        replacements.put(placeSegs.get(j), delayed);
                        resolved++;
                    }
                }
            }
        }
        
        if (!replacements.isEmpty()) {
            List<AnimationSegment> newSegments = new ArrayList<>();
            for (AnimationSegment seg : segments) {
                newSegments.add(replacements.getOrDefault(seg, seg));
            }
            segments = newSegments;
            segments.sort(Comparator.comparingLong(s -> s.startTime));
            logger.info("Resolved " + resolved + " place occupancy overlaps");
        }
    }
    
    private void adjustTimeBounds() {
        if (!segments.isEmpty()) {
            long preWindow = SegmentBuilder.TIME_AT_EVENT_GEN + 
                PetriNetTopology.FALLBACK_TRAVEL_EG_TO_TIN + 500;
            long postWindow = PetriNetTopology.FALLBACK_TRAVEL_TO_TOUT + 
                PetriNetTopology.FALLBACK_TRAVEL_TO_NEXT + 500;
            
            startTime = Math.min(startTime, segments.get(0).startTime) - preWindow;
            endTime = Math.max(endTime, segments.get(segments.size() - 1).endTime) + postWindow;
        }
    }
    
    // ==================== Animation State Queries ====================
    
    /**
     * Get token states at a specific time.
     */
    public Map<String, TokenAnimState> getTokenStatesAt(long time) {
        Map<String, TokenAnimState> states = new HashMap<>();
        
        for (AnimationSegment seg : segments) {
            // Use package-level Phase for comparison
            if (seg.isActiveAt(time) && seg.phase != com.editor.animator.Phase.CONSUMED) {
                TokenAnimState existing = states.get(seg.tokenId);
                if (existing == null || seg.startTime > existing.phaseStartTime) {
                    states.put(seg.tokenId, convertToState(seg, time));
                }
            }
        }
        
        return states;
    }
    
    private TokenAnimState convertToState(AnimationSegment seg, long time) {
        TokenAnimState state = new TokenAnimState(seg.tokenId, seg.version);
        // Convert from package-level Phase to inner class Phase
        state.phase = Phase.valueOf(seg.phase.name());
        state.phaseStartTime = seg.startTime;
        state.phaseEndTime = seg.endTime;
        state.progress = seg.getProgress(time);
        state.currentPlaceId = seg.placeId;
        state.tInId = seg.tInId;
        state.tOutId = seg.tOutId;
        state.nextTInId = seg.tInId;
        state.terminateNodeId = seg.terminateId;
        state.eventGenId = seg.eventGenId;
        state.fromElementId = seg.fromElement;
        state.toElementId = seg.toElement;
        return state;
    }
    
    /**
     * Get buffer states at T_ins at a specific time.
     */
    public Map<String, List<BufferedToken>> getBufferStatesAt(long time) {
        Map<String, List<BufferedToken>> buffers = new HashMap<>();
        
        for (String tInId : topology.getTInIds()) {
            buffers.put(tInId, new ArrayList<>());
        }
        
        for (AnimationSegment seg : segments) {
            if (seg.isActiveAt(time) && seg.phase == com.editor.animator.Phase.BUFFERED_AT_TIN && seg.tInId != null) {
                buffers.computeIfAbsent(seg.tInId, k -> new ArrayList<>())
                    .add(new BufferedToken(seg.tokenId, seg.version, seg.startTime));
            }
        }
        
        for (List<BufferedToken> buffer : buffers.values()) {
            buffer.sort(Comparator.comparingLong(t -> t.arrivalTime));
        }
        
        return buffers;
    }
    
    /**
     * Get complete animation snapshot at a time.
     */
    public AnimationSnapshot getAnimationSnapshotAt(long time) {
        return new AnimationSnapshot(getTokenStatesAt(time), getBufferStatesAt(time));
    }
    
    // ==================== Getters ====================
    
    public long getStartTime() { return startTime; }
    public long getEndTime() { return endTime; }
    public long getDuration() { return endTime - startTime; }
    public List<MarkingEvent> getEvents() { return Collections.unmodifiableList(events); }
    public boolean hasData() { return !segments.isEmpty(); }
    public boolean hasTopology() { return topology.hasTopology(); }
    public Color getVersionColor(String version) { return versionColors.getOrDefault(version, Color.GRAY); }
    
    public Set<String> getVersions() {
        Set<String> versions = new HashSet<>();
        for (AnimationSegment seg : segments) {
            versions.add(seg.version);
        }
        return versions;
    }
    
    // Topology delegations
    public String getTInForPlace(String placeId) { return topology.getTInForPlace(placeId); }
    public String getTOutForPlace(String placeId) { return topology.getTOutForPlace(placeId); }
    public String getNextTInForTOut(String tOutId) { return topology.getNextTInForTOut(tOutId); }
    public boolean isJoin(String tInId) { return topology.isJoin(tInId); }
    public boolean isFork(String tOutId) { return topology.isFork(tOutId); }
    public Set<String> getPlaceIds() { return topology.getPlaceIds(); }
    public Set<String> getTInIds() { return topology.getTInIds(); }
    public Set<String> getEventGeneratorIds() { return topology.getEventGeneratorIds(); }
    public java.awt.Point getElementPosition(String elementId) { return topology.getElementPosition(elementId); }
    public java.awt.Point getTerminatePosition(String terminateId) { return topology.getTerminatePosition(terminateId); }
    public Set<String> getTerminateNodeIds() { return topology.getTerminateIds(); }
    public String getPlaceForTIn(String tInId) { return topology.getPlaceForTIn(tInId); }
    public String getTInForEventGenerator(String eventGenId) { return topology.getTInForEventGenerator(eventGenId); }
    
    // ==================== Utility ====================
    
    public void clear() {
        events.clear();
        segments.clear();
        startTime = Long.MAX_VALUE;
        endTime = Long.MIN_VALUE;
    }
    
    private void printFinalTokenLocations() {
        logger.info("\n=== FINAL TOKEN LOCATIONS ===");
        
        Map<String, String> locations = new HashMap<>();
        Map<String, Long> times = new HashMap<>();
        
        for (AnimationSegment seg : segments) {
            if (!times.containsKey(seg.tokenId) || seg.endTime > times.get(seg.tokenId)) {
                times.put(seg.tokenId, seg.endTime);
                
                String loc;
                if (seg.phase == com.editor.animator.Phase.AT_TERMINATE) loc = "TERMINATE";
                else if (seg.phase == com.editor.animator.Phase.CONSUMED) loc = "CONSUMED";
                else if (seg.phase == com.editor.animator.Phase.AT_PLACE) loc = "AT " + seg.placeId;
                else if (seg.phase == com.editor.animator.Phase.BUFFERED_AT_TIN) loc = "BUFFERED at " + seg.tInId;
                else loc = seg.phase + " -> " + seg.toElement;
                
                locations.put(seg.tokenId, loc);
            }
        }
        
        for (String tokenId : new TreeSet<>(locations.keySet())) {
            logger.info("  " + tokenId + ": " + locations.get(tokenId));
        }
        logger.info("========================\n");
    }
    
    public void printTopology() {
        topology.printTopology();
    }
    
    public void printSegments() {
        logger.debug("=== All Segments ===");
        for (AnimationSegment seg : segments) {
            logger.debug(seg.toString());
        }
    }
}
