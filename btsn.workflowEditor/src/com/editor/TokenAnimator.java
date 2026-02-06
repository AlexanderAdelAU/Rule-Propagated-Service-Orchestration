package com.editor;

import java.awt.Color;
import java.awt.Point;
import java.util.*;

import org.apache.log4j.Logger;

import com.editor.animator.*;
import static com.editor.animator.AnimationConstants.*;

/**
 * TokenAnimator - Refactored Version with Pre-computed Animation Segments
 * 
 * DESIGN PHILOSOPHY:
 * Instead of recalculating token positions every frame by scanning events,
 * we pre-compute all animation segments during parsing. Each segment represents
 * a token being in a specific state for a specific time range.
 * 
 * This approach:
 * - Handles cyclic paths naturally (each pass creates new segments)
 * - No confusion about which EXIT matches which ENTER
 * - Much simpler rendering logic
 * - Easier to debug (can dump all segments)
 * 
 * ANIMATION SEGMENT:
 * {tokenId, phase, startTime, endTime, fromElement, toElement, placeId, tInId, tOutId}
 * 
 * During rendering, we simply find all segments where startTime <= time < endTime
 * and interpolate based on segment type.
 * 
 * TOKEN ID CONVENTION:
 * - Parent tokens: 1000000, 1010000, 1020000, ... (divisible by 100)
 * - Child tokens: 1030001, 1030002 (parent 1030000 + suffix 01, 02)
 * 
 * EVENT TYPES:
 * - ENTER: Token enters a place
 * - EXIT: Token leaves a place  
 * - FORK: Child token created
 * - BUFFERED: Token waiting at JOIN
 * - JOIN_CONSUMED: Child token consumed by JOIN
 * - TERMINATE: Token exits workflow
 * 
 * Data classes extracted to com.editor.animator package:
 * - Phase, AnimationSegment, MarkingEvent, TokenAnimState, 
 *   BufferedToken, AnimationSnapshot, AnimationConstants
 */
public class TokenAnimator {
    
    private static final Logger logger = Logger.getLogger(TokenAnimator.class.getName());
    
    // ==================== Topology ====================
    
    private Map<String, String> placeToTIn = new HashMap<>();
    private Map<String, String> placeToTOut = new HashMap<>();
    private Map<String, List<String>> tOutToNextTIns = new HashMap<>();
    private Map<String, List<String>> tInFromTOuts = new HashMap<>();
    private Map<String, String> tOutToNextTIn = new HashMap<>();
    private Map<String, String> tInToPlace = new HashMap<>();
    private Set<String> placeIds = new HashSet<>();
    private Set<String> tInIds = new HashSet<>();
    private Set<String> eventGeneratorIds = new HashSet<>();
    private Map<String, String> eventGenToTIn = new HashMap<>();
    private Map<String, String> tOutToTerminate = new HashMap<>();
    private Set<String> terminateIds = new HashSet<>();
    private Map<String, Point> terminatePositions = new HashMap<>();
    private Map<String, String> labelToService = new HashMap<>();  // Maps place label -> service name
    
    // Implicit fork tracking from Event Generators (fork_behavior in JSON)
    private Map<String, String> eventGenImplicitForkToJoin = new HashMap<>();  // eventGenId -> joinTInId
    private Map<String, Integer> eventGenForkChildCount = new HashMap<>();     // eventGenId -> expected child count
    private Set<String> implicitJoinTIns = new HashSet<>();                    // T_ins that are implicit join targets
    private Set<String> explicitJoinTIns = new HashSet<>();                    // T_ins explicitly marked as JoinNode
    
    // Element positions for Euclidean distance calculations
    private Map<String, Point> elementPositions = new HashMap<>();  // element ID/label -> center position
    private Map<String, List<Point>> arrowWaypoints = new HashMap<>();  // "source->target" -> waypoints
    
    // ==================== Data ====================
    
    private List<MarkingEvent> events = new ArrayList<>();
    private List<AnimationSegment> segments = new ArrayList<>();
    private long startTime = Long.MAX_VALUE;
    private long endTime = Long.MIN_VALUE;
    
    private Map<String, Color> versionColors = new HashMap<>();
    private Canvas canvas;
    
    public TokenAnimator() {
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
    
    public void setCanvas(Canvas canvas) {
        this.canvas = canvas;
    }
    
    // ==================== Distance Calculation ====================
    
    /**
     * Get the center position of an element by its ID/label
     */
    private Point getElementPosition(String elementId) {
        if (elementId == null) return null;
        return elementPositions.get(elementId);
    }
    
    /**
     * Calculate Euclidean distance between two points
     */
    private double calculateDistance(Point from, Point to) {
        if (from == null || to == null) return -1;
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        return Math.sqrt(dx * dx + dy * dy);
    }
    
    /**
     * Calculate total path distance including waypoints
     */
    private double calculatePathDistance(String fromElement, String toElement) {
        Point from = getElementPosition(fromElement);
        Point to = getElementPosition(toElement);
        
        if (from == null || to == null) return -1;
        
        // Check for waypoints on this path
        String key = fromElement + "->" + toElement;
        List<Point> waypoints = arrowWaypoints.get(key);
        
        if (waypoints == null || waypoints.isEmpty()) {
            // Direct path
            return calculateDistance(from, to);
        }
        
        // Sum distances along waypoints
        double total = 0;
        Point current = from;
        for (Point wp : waypoints) {
            total += calculateDistance(current, wp);
            current = wp;
        }
        total += calculateDistance(current, to);
        return total;
    }
    
    /**
     * Calculate travel duration based on distance and velocity
     * Returns minimum duration if distance unavailable
     */
    private long calculateTravelDuration(String fromElement, String toElement, long fallback) {
        double distance = calculatePathDistance(fromElement, toElement);
        if (distance < 0) {
            return fallback;
        }
        long duration = (long)(distance / VELOCITY);
        return Math.max(duration, MIN_TRAVEL_DURATION);
    }
    
    /**
     * Calculate travel duration for a direct distance
     */
    private long calculateTravelDuration(double distance, long fallback) {
        if (distance < 0) {
            return fallback;
        }
        long duration = (long)(distance / VELOCITY);
        return Math.max(duration, MIN_TRAVEL_DURATION);
    }
    
    /**
     * Resolve a place identifier to its service name.
     * Events may use labels (P1, P2) while topology uses service names (P1_Place, P2_Place).
     */
    private String resolveToServiceName(String placeIdOrLabel) {
        if (placeIdOrLabel == null) return null;
        
        // Already a service name?
        if (placeIds.contains(placeIdOrLabel)) {
            return placeIdOrLabel;
        }
        
        // Try label -> service mapping
        String service = labelToService.get(placeIdOrLabel);
        if (service != null) {
            return service;
        }
        
        // Try common patterns: P1 -> P1_Place, NS_Green -> NS_Green (might be the service itself)
        String withSuffix = placeIdOrLabel + "_Place";
        if (placeIds.contains(withSuffix)) {
            return withSuffix;
        }
        
        // Not found - return as-is and hope for the best
        return placeIdOrLabel;
    }
    
    // ==================== Topology Building ====================
    
    public void buildTopologyFromCanvas() {
        if (canvas == null) {
            System.err.println("TokenAnimator: Canvas not set");
            return;
        }
        
        // Clear existing
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
        
        List<ProcessElement> elements = canvas.getElements();
        List<Arrow> arrows = canvas.getArrows();
        
        // First pass: identify elements and store positions
        for (ProcessElement elem : elements) {
            // Store element center position for distance calculations
            Point center = new Point(
                elem.getX() + elem.getWidth() / 2,
                elem.getY() + elem.getHeight() / 2
            );
            
            if (elem.getType() == ProcessElement.Type.PLACE) {
                String serviceName = elem.getService();
                String label = elem.getLabel();
                if (serviceName == null || serviceName.isEmpty()) {
                    serviceName = label;
                }
                placeIds.add(serviceName);
                
                // Store position by service name
                elementPositions.put(serviceName, center);
                if (label != null && !label.equals(serviceName)) {
                    elementPositions.put(label, center);
                }
                
                // Map label -> service (e.g., "P1" -> "P1_Place", "NS_Green" -> "NS_Green")
                if (label != null && !label.equals(serviceName)) {
                    labelToService.put(label, serviceName);
                }
                // Also map service to itself for consistency
                labelToService.put(serviceName, serviceName);
                
            } else if (elem.getType() == ProcessElement.Type.EVENT_GENERATOR) {
                String label = elem.getLabel();
                eventGeneratorIds.add(label);
                elementPositions.put(label, center);
                
                // Read fork_behavior from EVENT_GENERATOR
                // This tells us if this generator produces multiple child tokens that need to join
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
            } else if (elem.getType() == ProcessElement.Type.TRANSITION) {
                String label = elem.getLabel();
                elementPositions.put(label, center);
                
                if (label != null && label.startsWith("T_in_")) {
                    tInIds.add(label);
                    
                    // Check if this T_in is explicitly marked as a JoinNode
                    String nodeType = elem.getNodeType();
                    String nodeValue = elem.getNodeValue();
                    if ("JoinNode".equals(nodeType) || "JOIN_NODE".equals(nodeValue)) {
                        explicitJoinTIns.add(label);
                        logger.debug("Detected explicit JoinNode: " + label);
                    }
                }
                String nodeValue = elem.getNodeValue();
                if ("TERMINATE_NODE".equals(nodeValue) ||
                    (label != null && label.toLowerCase().contains("terminate"))) {
                    terminateIds.add(label);
                    terminatePositions.put(label, new Point(elem.getX(), elem.getY()));
                }
            }
        }
        
        // Second pass: analyze arrows
        for (Arrow arrow : arrows) {
            ProcessElement source = arrow.getSource();
            ProcessElement target = arrow.getTarget();
            if (source == null || target == null) continue;
            
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
            
            // ============================================================
            // SEMANTIC RULE: Fork vs XOR distinction
            // ============================================================
            // ForkNode: Parallel split - creates N children that travel simultaneously.
            //           All outgoing edges fire together. Children need synchronization.
            // 
            // XorNode:  Exclusive choice - token takes ONE path based on condition.
            //           Multiple edges but only one fires. Same token, no children.
            //           Retry loops are XOR, not Fork - the token loops, not splits.
            // 
            // Only ForkNode/GatewayNode edges go in tOutToNextTIns (fork map).
            // XorNode edges are tracked for routing but NOT for fork synchronization.
            // ============================================================
            
            // T_out -> T_in
            if (source.getType() == ProcessElement.Type.TRANSITION &&
                target.getType() == ProcessElement.Type.TRANSITION &&
                sourceLabel != null && sourceLabel.startsWith("T_out_") &&
                targetLabel != null && targetLabel.startsWith("T_in_")) {
                
                // Check if source is a true fork (parallel split) vs exclusive choice (XOR)
                String sourceNodeType = source.getNodeType();
                boolean isTrueFork = "ForkNode".equals(sourceNodeType) || "GatewayNode".equals(sourceNodeType);
                
                // Only add to fork map if this is a true parallel fork
                // XorNode multiple edges are exclusive choices, not parallel paths
                if (isTrueFork) {
                    tOutToNextTIns.computeIfAbsent(sourceLabel, k -> new ArrayList<>()).add(targetLabel);
                    logger.debug("Fork edge: " + sourceLabel + " -> " + targetLabel + " (nodeType=" + sourceNodeType + ")");
                } else {
                    // For XorNode/EdgeNode, still track for single-target routing but NOT as fork
                    // Only add if not already present (first edge wins for default routing)
                    if (!tOutToNextTIns.containsKey(sourceLabel)) {
                        tOutToNextTIns.computeIfAbsent(sourceLabel, k -> new ArrayList<>()).add(targetLabel);
                    }
                    logger.debug("XOR/Edge: " + sourceLabel + " -> " + targetLabel + " (nodeType=" + sourceNodeType + ", not a fork)");
                }
                
                // Always track incoming edges to T_ins (for join detection)
                // Multiple T_outs leading to same T_in = join point (regardless of fork vs xor)
                tInFromTOuts.computeIfAbsent(targetLabel, k -> new ArrayList<>()).add(sourceLabel);
                
                // Track first/default next T_in for this T_out
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
            
            // Store waypoints for all arrows (used for path distance calculation)
            List<Point> waypoints = arrow.getWaypoints();
            if (waypoints != null && !waypoints.isEmpty()) {
                String key = sourceLabel + "->" + targetLabel;
                arrowWaypoints.put(key, new ArrayList<>(waypoints));
            }
        }
        
        logger.info("Topology built: " + placeIds.size() + " places, " +
            tInIds.size() + " T_ins, " + terminateIds.size() + " terminates");
        logger.info("Element positions stored: " + elementPositions.size() + 
            ", Arrows with waypoints: " + arrowWaypoints.size());
    }
    
    // ==================== Parsing ====================
    
    /**
     * Main parsing entry point - detects format and parses
     */
    public void parseOutput(String text) {
        events.clear();
        segments.clear();
        startTime = Long.MAX_VALUE;
        endTime = Long.MIN_VALUE;
        
        if (canvas != null) {
            buildTopologyFromCanvas();
        }
        
        // Parse events from text
        parseEventsFromText(text);
        
        // Sort events by timestamp
        Collections.sort(events);
        
        // Generate animation segments from events
        generateSegments();
        
        logger.info("Parsed " + events.size() + " events, generated " + segments.size() + " segments");
        logger.info("Time range: " + startTime + " - " + endTime);
        
        // Debug: print first few segments
        logger.debug("First 20 segments:");
        for (int i = 0; i < Math.min(20, segments.size()); i++) {
            logger.debug("  " + segments.get(i));
        }
        
        // Print summary of final token locations
        printFinalTokenLocations();
    }
    
    /**
     * Print a summary of where each token ends up at the end of the animation
     */
    private void printFinalTokenLocations() {
        logger.info("\n=== FINAL TOKEN LOCATIONS (at end time) ===");
        
        Map<String, String> finalLocations = new HashMap<>();
        Map<String, Long> finalTimes = new HashMap<>();
        
        // Find the last segment for each token
        for (AnimationSegment seg : segments) {
            String tokenId = seg.tokenId;
            if (!finalTimes.containsKey(tokenId) || seg.endTime > finalTimes.get(tokenId)) {
                finalTimes.put(tokenId, seg.endTime);
                
                String location;
                if (seg.phase == Phase.AT_TERMINATE) {
                    location = "TERMINATE (" + seg.terminateId + ")";
                } else if (seg.phase == Phase.CONSUMED) {
                    location = "CONSUMED (JOIN)";
                } else if (seg.phase == Phase.AT_PLACE) {
                    location = "AT " + seg.placeId;
                } else if (seg.phase == Phase.BUFFERED_AT_TIN) {
                    location = "BUFFERED at " + seg.tInId;
                } else {
                    location = seg.phase + " -> " + (seg.toElement != null ? seg.toElement : seg.placeId);
                }
                finalLocations.put(tokenId, location);
            }
        }
        
        // Count by location
        Map<String, Integer> locationCounts = new HashMap<>();
        for (String location : finalLocations.values()) {
            locationCounts.merge(location, 1, Integer::sum);
        }
        
        // Print individual tokens
        List<String> sortedTokens = new ArrayList<>(finalLocations.keySet());
        Collections.sort(sortedTokens);
        for (String tokenId : sortedTokens) {
            logger.info("  " + tokenId + ": " + finalLocations.get(tokenId));
        }
        
        // Print summary counts
        logger.info("\n=== LOCATION COUNTS ===");
        for (Map.Entry<String, Integer> entry : locationCounts.entrySet()) {
            logger.info("  " + entry.getKey() + ": " + entry.getValue() + " tokens");
        }
        logger.info("========================\n");
    }
    
    /**
     * Parse events from analyzer text output
     */
    private void parseEventsFromText(String text) {
        String[] lines = text.split("\n");
        
        // Use a set to detect duplicates
        Set<String> seenEvents = new HashSet<>();
        
        for (String line : lines) {
            line = line.trim();
            
            // Parse: Time=xxx Token=xxx Place=xxx Marking=x Buffer=x ToPlace=xxx EventType=xxx TransitionId=xxx
            if (line.startsWith("Time=") && line.contains("Marking=")) {
                try {
                    long timestamp = 0;
                    String tokenId = "";
                    String placeId = "";
                    int marking = 0;
                    int buffer = 0;
                    String toPlace = null;
                    String eventType = null;
                    String transitionId = null;
                    
                    String[] parts = line.split(" ");
                    for (String part : parts) {
                        if (part.startsWith("Time=")) {
                            timestamp = Long.parseLong(part.substring(5));
                        } else if (part.startsWith("Token=")) {
                            tokenId = part.substring(6);
                        } else if (part.startsWith("Place=")) {
                            placeId = part.substring(6);
                        } else if (part.startsWith("Marking=")) {
                            marking = Integer.parseInt(part.substring(8));
                        } else if (part.startsWith("Buffer=")) {
                            buffer = Integer.parseInt(part.substring(7));
                        } else if (part.startsWith("ToPlace=")) {
                            toPlace = part.substring(8);
                        } else if (part.startsWith("EventType=")) {
                            eventType = part.substring(10);
                        } else if (part.startsWith("TransitionId=")) {
                            transitionId = part.substring(13);
                        }
                    }
                    
                    if (tokenId.isEmpty()) continue;
                    if (placeId.isEmpty() && !"GENERATED".equals(eventType)) continue;
                    if (marking < 0) continue; // Skip negative markings
                    
                    // Deduplicate: create a unique key for this event
                    String eventKey = timestamp + "|" + tokenId + "|" + placeId + "|" + marking + "|" + eventType + "|" + toPlace;
                    if (seenEvents.contains(eventKey)) {
                        continue; // Skip duplicate
                    }
                    seenEvents.add(eventKey);
                    
                    String version = getVersionFromTokenId(tokenId);
                    boolean entering = (marking == 1);
                    
                    if (eventType == null) {
                        eventType = entering ? "ENTER" : "EXIT";
                    }
                    
                    events.add(new MarkingEvent(timestamp, tokenId, version,
                        placeId, entering, buffer, toPlace, eventType, transitionId));
                    
                    if (timestamp < startTime) startTime = timestamp;
                    if (timestamp > endTime) endTime = timestamp;
                    
                } catch (Exception e) {
                    // Skip malformed lines
                }
            }
        }
        
        logger.info("Parsed " + events.size() + " unique events (filtered " + (seenEvents.size() - events.size()) + " duplicates)");
    }
    
    /**
     * Generate animation segments from parsed events.
     * This is the core of the refactored approach.
     */
    private void generateSegments() {
        // ==================== PASS 1: Generate raw segments ====================
        // Each token is processed independently using actual event timestamps.
        // No cross-token lookups. Visual stretching happens but without sync.
        logger.info("=== PASS 1: Generating raw segments ===");
        
        // Group events by token
        Map<String, List<MarkingEvent>> eventsByToken = new HashMap<>();
        for (MarkingEvent event : events) {
            eventsByToken.computeIfAbsent(event.tokenId, k -> new ArrayList<>()).add(event);
        }
        
        // Track parent-child relationships for fork handling
        Map<String, String> childToParent = new HashMap<>();
        Map<String, Long> forkTimes = new HashMap<>(); // tokenId -> fork timestamp (for compatibility)
        Map<String, String> childBirthTOut = new HashMap<>(); // tokenId -> T_out where child was born (for compatibility)
        
        // Track ALL fork births - a token can be born multiple times at the same T_out in cyclic workflows
        // Each birth is uniquely identified by (tokenId, T_out, timestamp)
        List<long[]> allForkBirths = new ArrayList<>(); // Each entry: [tokenId hash, timestamp]
        Map<String, List<Long>> tokenForkTimestamps = new HashMap<>(); // tokenId -> list of fork timestamps
        
        // Identify fork relationships
        for (MarkingEvent event : events) {
            if (event.isFork()) {
                String parentId = getParentTokenId(event.tokenId);
                if (parentId != null) {
                    childToParent.put(event.tokenId, parentId);
                    forkTimes.put(event.tokenId, event.timestamp);
                    
                    // Track where this child was born (the T_out from the FORK event)
                    // IMPORTANT: Use placeToTOut to get the animator's T_out name
                    // The event.transitionId uses instrumentation naming (T_out_P1)
                    // but animator uses workflow label naming (T_out_NS_Green)
                    String birthTOut = null;
                    if (event.placeId != null) {
                        // Try direct lookup first
                        birthTOut = placeToTOut.get(event.placeId);
                        
                        // If not found, try resolving the place name
                        if (birthTOut == null) {
                            String resolvedPlace = resolveToServiceName(event.placeId);
                            birthTOut = placeToTOut.get(resolvedPlace);
                        }
                    }
                    
                    if (birthTOut != null) {
                        childBirthTOut.put(event.tokenId, birthTOut);
                        
                        // Track this specific fork birth timestamp
                        tokenForkTimestamps.computeIfAbsent(event.tokenId, k -> new ArrayList<>())
                                          .add(event.timestamp);
                    }
                    logger.debug("FORK: Child " + event.tokenId + " born at " + 
                                birthTOut + " (parent=" + parentId + ", placeId=" + event.placeId + 
                                ", time=" + event.timestamp + ")");
                }
            }
        }
        
        // Process each token's events to generate segments
        for (Map.Entry<String, List<MarkingEvent>> entry : eventsByToken.entrySet()) {
            String tokenId = entry.getKey();
            List<MarkingEvent> tokenEvents = entry.getValue();
            
            generateSegmentsForToken(tokenId, tokenEvents, childToParent, forkTimes);
        }
        
        logger.info("Pass 1 complete: " + segments.size() + " raw segments generated");
        
        // ==================== PASS 2: DISABLED ====================
        // Fork synchronization was causing tokens to disappear because it shifted
        // child token start times later without creating segments to fill the gap.
        // 
        // More fundamentally: synchronization is WRONG. The instrumentation captures
        // REAL timestamps when tokens actually FORK/EXIT. A 1000ms spread between
        // siblings reflects actual service call latency - that's reality, not a bug.
        // The animation should show what actually happened, not an idealized
        // "simultaneous departure" that never occurred.
        //
        // DISABLED: synchronizeForkPoints(childBirthTOut);
        logger.info("=== PASS 2: Fork synchronization DISABLED (using real timestamps) ===");
        
        // Sort segments by start time
        segments.sort(Comparator.comparingLong(s -> s.startTime));
        
        // POST-PROCESSING: Ensure all arrivals at JOIN T_ins have buffer segments
        // This catches any code path that generates TRAVELING_TO_NEXT_TIN without proper JOIN handling
        ensureJoinBufferSegments();
        
        // POST-PROCESSING: Resolve overlapping occupancy at places
        // This ensures only one token occupies a place at a time visually
        resolveOverlappingPlaceOccupancy();
        
        // Adjust time bounds
        if (!segments.isEmpty()) {
            long preWindow = TIME_AT_EVENT_GEN + TRAVEL_DURATION_EG_TO_TIN + 500;
            long postWindow = TRAVEL_DURATION_TO_TOUT + TRAVEL_DURATION_TO_NEXT + 500;
            startTime = Math.min(startTime, segments.get(0).startTime) - preWindow;
            endTime = Math.max(endTime, segments.get(segments.size() - 1).endTime) + postWindow;
        }
        
        logger.info("=== Segment generation complete ===");
    }
    
    /**
     * PASS 2: Synchronize sibling tokens at fork points.
     * 
     * <h3>Semantic Rules:</h3>
     * <ol>
     *   <li><b>Siblings are born together:</b> When sibling tokens (same parent) leave from 
     *       the same T_out as part of a FORK, they should visually start traveling at the 
     *       same moment.</li>
     *   
     *   <li><b>Birth point only:</b> We only sync at the T_out where each child was BORN 
     *       (forked into existence). A child token may pass through many T_outs during its 
     *       lifetime (e.g., XOR retry loops), but synchronization only happens at birth.</li>
     *   
     *   <li><b>Fork arity determines siblings:</b> A fork with arity N (N output arcs) creates 
     *       exactly N children per firing. When sorted by time, consecutive batches of N 
     *       segments are siblings from the same firing. This uses topology structure, not 
     *       arbitrary time thresholds.</li>
     *   
     *   <li><b>Never extend CONSUMED:</b> CONSUMED means the token doesn't exist (invisible). 
     *       You cannot extend non-existence. A reborn token becomes visible at its FORK time.</li>
     *   
     *   <li><b>Never extend buffers at JOINs:</b> At a JOIN, tokens are consumed, not waiting 
     *       longer. Extending a buffer at a JOIN would make a consumed token appear stuck.</li>
     * </ol>
     * 
     * @param childBirthTOut Map of childTokenId -> T_out where that child was born
     */
    private void synchronizeForkPoints(Map<String, String> childBirthTOut) {
        // Build parent-child map from token IDs
        Map<String, String> tokenToParent = new HashMap<>();
        Set<String> allTokens = new HashSet<>();
        
        for (AnimationSegment seg : segments) {
            allTokens.add(seg.tokenId);
            String parent = getParentTokenId(seg.tokenId);
            if (parent != null && !parent.equals(seg.tokenId)) {
                tokenToParent.put(seg.tokenId, parent);
            }
        }
        
        // Group TRAVELING_TO_NEXT_TIN segments by (T_out, parent)
        // Then use fork arity and ordering to identify siblings from each firing
        Map<String, List<Integer>> rawGroups = new HashMap<>();
        
        for (int i = 0; i < segments.size(); i++) {
            AnimationSegment seg = segments.get(i);
            if (seg.phase == Phase.TRAVELING_TO_NEXT_TIN && seg.fromElement != null) {
                // Only sync at the token's BIRTH POINT
                String birthPoint = childBirthTOut.get(seg.tokenId);
                if (birthPoint == null || !birthPoint.equals(seg.fromElement)) {
                    continue; // This T_out is not where this child was born - skip
                }
                
                String parent = tokenToParent.get(seg.tokenId);
                if (parent == null) continue;
                
                // Group by (T_out, parent)
                String groupKey = seg.fromElement + "|" + parent;
                rawGroups.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(i);
            }
        }
        
        // Now split each raw group into sibling batches using fork arity
        // The fork arity tells us how many children are created per firing
        Map<String, List<Integer>> forkGroups = new HashMap<>();
        
        for (Map.Entry<String, List<Integer>> entry : rawGroups.entrySet()) {
            String[] parts = entry.getKey().split("\\|");
            String tOutId = parts[0];
            
            // Get fork arity from topology
            List<String> forkTargets = tOutToNextTIns.get(tOutId);
            int forkArity = (forkTargets != null) ? forkTargets.size() : 1;
            
            List<Integer> indices = entry.getValue();
            
            if (forkArity <= 1 || indices.size() <= 1) {
                // Not a real fork or only one segment - nothing to sync
                continue;
            }
            
            // Sort segments by start time
            indices.sort((a, b) -> Long.compare(segments.get(a).startTime, segments.get(b).startTime));
            
            // Group into batches of forkArity - each batch is siblings from one firing
            // This is semantically correct: each firing creates exactly forkArity children
            int batchNum = 0;
            for (int i = 0; i < indices.size(); i += forkArity) {
                List<Integer> batch = new ArrayList<>();
                for (int j = i; j < Math.min(i + forkArity, indices.size()); j++) {
                    batch.add(indices.get(j));
                }
                
                if (batch.size() > 1) {
                    String batchKey = entry.getKey() + "#" + batchNum;
                    forkGroups.put(batchKey, batch);
                    
                    logger.debug("PASS2: Sibling batch " + batchNum + " at " + tOutId + 
                                " (arity=" + forkArity + "): " + batch.size() + " siblings");
                }
                batchNum++;
            }
        }
        
        int syncsPerformed = 0;
        
        // Process each sibling group
        for (Map.Entry<String, List<Integer>> entry : forkGroups.entrySet()) {
            List<Integer> siblingIndices = entry.getValue();
            
            if (siblingIndices.size() > 1) {
                String[] parts = entry.getKey().split("\\|");
                String tOutId = parts[0];
                String parentId = parts.length > 1 ? parts[1] : "unknown";
                // Key format is now: T_out|parent#batchNum
                logger.debug("PASS2: Processing sibling group '" + entry.getKey() + 
                            "' with " + siblingIndices.size() + " siblings");
                syncsPerformed += synchronizeForkGroup(tOutId, siblingIndices);
            }
        }
        
        logger.info("Pass 2 complete: synchronized " + syncsPerformed + " sibling segments across fork points");
    }
    
    /**
     * Synchronize a single fork group - all segments leaving from the same T_out.
     * Returns the number of segments adjusted.
     */
    private int synchronizeForkGroup(String forkPointId, List<Integer> segmentIndices) {
        if (segmentIndices.isEmpty()) return 0;
        
        // Find the parent token ID (all children share the same parent)
        String nominalParentId = null;
        for (int idx : segmentIndices) {
            AnimationSegment seg = segments.get(idx);
            nominalParentId = getParentTokenId(seg.tokenId);
            if (nominalParentId != null) break;
        }
        
        // Find when the PARENT's TRAVELING_TO_TOUT segment ends (when parent visually reaches T_out)
        long parentToutArrival = Long.MIN_VALUE;
        String effectiveParentId = nominalParentId;
        
        if (nominalParentId != null) {
            for (AnimationSegment seg : segments) {
                if (seg.tokenId.equals(nominalParentId) && 
                    seg.phase == Phase.TRAVELING_TO_TOUT &&
                    forkPointId.equals(seg.toElement)) {
                    parentToutArrival = seg.endTime;
                    break;
                }
            }
        }
        
        // JOINâ†’FORK PATTERN: If nominal parent has no TRAVELING_TO_TOUT at this fork point,
        // look for an "effective parent" among the siblings - one that arrives at the fork point
        // (this happens in TrafficLights where 1000001 is JOIN_SURVIVOR that reaches T_out,
        // and 1000002 is reborn from that point)
        if (parentToutArrival == Long.MIN_VALUE) {
            for (int idx : segmentIndices) {
                AnimationSegment childSeg = segments.get(idx);
                String childId = childSeg.tokenId;
                
                // Look for this child's TRAVELING_TO_TOUT to the fork point
                for (AnimationSegment seg : segments) {
                    if (seg.tokenId.equals(childId) && 
                        seg.phase == Phase.TRAVELING_TO_TOUT &&
                        forkPointId.equals(seg.toElement)) {
                        parentToutArrival = seg.endTime;
                        effectiveParentId = childId;
                        logger.debug("PASS2: Found effective parent " + childId + 
                                    " (JOIN_SURVIVOR) arriving at " + forkPointId + " at time " + parentToutArrival);
                        break;
                    }
                }
                if (parentToutArrival != Long.MIN_VALUE) break;
            }
        }
        
        // Find the latest start time among all child segments (excluding effective parent if found)
        long latestChildStart = Long.MIN_VALUE;
        for (int idx : segmentIndices) {
            AnimationSegment seg = segments.get(idx);
            // Don't include the effective parent's segments in child timing
            if (!seg.tokenId.equals(effectiveParentId)) {
                latestChildStart = Math.max(latestChildStart, seg.startTime);
            }
        }
        
        // If all segments belong to effective parent, use parent arrival
        if (latestChildStart == Long.MIN_VALUE) {
            latestChildStart = parentToutArrival;
        }
        
        // Sync time is the later of: parent arrival OR latest child start
        // This ensures children don't appear before parent visually reaches T_out
        long syncTime = Math.max(parentToutArrival, latestChildStart);
        
        logger.debug("PASS2: Fork group " + forkPointId + " (nominalParent=" + nominalParentId + 
                    ", effectiveParent=" + effectiveParentId + "): " +
                    "parentArrival=" + parentToutArrival + ", latestChild=" + latestChildStart + 
                    ", syncTime=" + syncTime);
        
        // If we still don't have a valid sync time, warn and skip
        if (syncTime == Long.MIN_VALUE) {
            logger.warn("PASS2: Could not determine sync time for fork group at " + forkPointId);
            return 0;
        }
        
        // If syncTime is later than parent arrival, extend parent to bridge the gap
        if (effectiveParentId != null && parentToutArrival != Long.MIN_VALUE && syncTime > parentToutArrival) {
            extendParentToForkPoint(effectiveParentId, forkPointId, syncTime);
        }
        
        int adjustedCount = 0;
        
        // Adjust children (not the effective parent) to start at syncTime
        for (int idx : segmentIndices) {
            AnimationSegment seg = segments.get(idx);
            
            // Skip the effective parent - it's the source, not something to sync
            if (seg.tokenId.equals(effectiveParentId)) {
                continue;
            }
            
            if (seg.startTime != syncTime) {
                long originalStart = seg.startTime;
                long originalEnd = seg.endTime;
                long duration = originalEnd - originalStart;
                long newEnd = syncTime + duration;
                
                // Create adjusted segment with new timing
                AnimationSegment adjusted = new AnimationSegment(
                    seg.tokenId, seg.version, seg.phase,
                    syncTime, newEnd,
                    seg.placeId, seg.tInId, seg.tOutId,
                    seg.fromElement, seg.toElement,
                    seg.terminateId, seg.eventGenId
                );
                
                // Replace in list
                segments.set(idx, adjusted);
                
                // Only extend preceding segment if we're moving the start LATER
                if (syncTime > originalStart) {
                    extendPrecedingSegment(seg.tokenId, originalStart, syncTime);
                }
                
                // Adjust following segments to maintain continuity
                adjustFollowingSegments(seg.tokenId, originalEnd, newEnd);
                
                logger.debug("PASS2: Adjusted " + seg.tokenId + " TRAVELING_TO_NEXT_TIN " +
                            "from " + originalStart + "-" + originalEnd +
                            " to " + syncTime + "-" + newEnd);
                
                adjustedCount++;
            }
        }
        
        return adjustedCount;
    }
    
    /**
     * Extend the parent token's last segment (typically TRAVELING_TO_TOUT) to bridge the gap
     * until the children start traveling. This keeps the parent visible at T_out until the fork.
     */
    private void extendParentToForkPoint(String parentId, String forkPointId, long forkStartTime) {
        // Find the parent's TRAVELING_TO_TOUT segment that ends at this fork point
        int parentSegmentIdx = -1;
        long latestEnd = Long.MIN_VALUE;
        
        for (int i = 0; i < segments.size(); i++) {
            AnimationSegment seg = segments.get(i);
            if (seg.tokenId.equals(parentId) && 
                seg.phase == Phase.TRAVELING_TO_TOUT &&
                forkPointId.equals(seg.toElement)) {
                if (seg.endTime > latestEnd) {
                    latestEnd = seg.endTime;
                    parentSegmentIdx = i;
                }
            }
        }
        
        if (parentSegmentIdx >= 0) {
            AnimationSegment parentSeg = segments.get(parentSegmentIdx);
            
            if (parentSeg.endTime < forkStartTime) {
                // Extend the parent's segment to end when children start
                AnimationSegment extended = new AnimationSegment(
                    parentSeg.tokenId, parentSeg.version, parentSeg.phase,
                    parentSeg.startTime, forkStartTime,
                    parentSeg.placeId, parentSeg.tInId, parentSeg.tOutId,
                    parentSeg.fromElement, parentSeg.toElement,
                    parentSeg.terminateId, parentSeg.eventGenId
                );
                
                segments.set(parentSegmentIdx, extended);
                
                logger.debug("PASS2: Extended parent " + parentId + " TRAVELING_TO_TOUT " +
                            "from end=" + parentSeg.endTime + " to end=" + forkStartTime +
                            " (bridging " + (forkStartTime - parentSeg.endTime) + "ms gap)");
            }
        } else {
            // Parent might not have a TRAVELING_TO_TOUT if it's a reconstituted parent
            // In that case, look for any segment ending near the fork point
            logger.debug("PASS2: No TRAVELING_TO_TOUT found for parent " + parentId + 
                        " at fork point " + forkPointId);
        }
    }
    
    /**
     * Extend the segment that ends just before the given timestamp to fill a gap.
     * 
     * <h3>Semantic Rules - When NOT to extend:</h3>
     * <ul>
     *   <li><b>BUFFERED_AT_TIN at a JOIN:</b> At a JOIN, tokens are consumed, not waiting 
     *       longer. Extending would make a consumed token appear stuck at the JOIN point.</li>
     *   
     *   <li><b>CONSUMED segments:</b> CONSUMED means the token doesn't exist (invisible). 
     *       You cannot extend non-existence. A reborn token becomes visible at the FORK time, 
     *       as defined by the actual FORK event, not by sibling sync timing.</li>
     * </ul>
     * 
     * @param tokenId The token whose preceding segment should be extended
     * @param originalSegmentStart The original start time of the segment being adjusted
     * @param newStart The new start time (preceding segment's end time should extend to this)
     */
    private void extendPrecedingSegment(String tokenId, long originalSegmentStart, long newStart) {
        // Find the segment for this token that ends at or just before originalSegmentStart
        int precedingIdx = -1;
        long closestEnd = Long.MIN_VALUE;
        
        for (int i = 0; i < segments.size(); i++) {
            AnimationSegment seg = segments.get(i);
            if (seg.tokenId.equals(tokenId) && seg.endTime <= originalSegmentStart) {
                if (seg.endTime > closestEnd) {
                    closestEnd = seg.endTime;
                    precedingIdx = i;
                }
            }
        }
        
        if (precedingIdx >= 0) {
            AnimationSegment preceding = segments.get(precedingIdx);
            
            // IMPORTANT: Don't extend BUFFERED_AT_TIN segments that are at JOIN T_ins
            // At a JOIN, the token is CONSUMED, not waiting longer. Extending would make
            // it appear stuck at the JOIN point.
            if (preceding.phase == Phase.BUFFERED_AT_TIN && preceding.tInId != null && isJoin(preceding.tInId)) {
                logger.debug("PASS2: NOT extending buffer at JOIN T_in " + preceding.tInId + 
                            " for " + tokenId + " (token is consumed at JOIN, not waiting)");
                return;
            }
            
            // IMPORTANT: Don't extend CONSUMED segments
            // CONSUMED means the token doesn't exist (invisible). You can't extend non-existence.
            // A reborn token should become visible at the FORK time, defined by the actual
            // FORK event, not by sibling sync timing.
            if (preceding.phase == Phase.CONSUMED) {
                logger.debug("PASS2: NOT extending CONSUMED for " + tokenId + 
                            " (can't extend invisibility - token becomes visible at rebirth)");
                return;
            }
            
            // Create extended segment with new end time
            AnimationSegment extended = new AnimationSegment(
                preceding.tokenId, preceding.version, preceding.phase,
                preceding.startTime, newStart,  // Extended end time
                preceding.placeId, preceding.tInId, preceding.tOutId,
                preceding.fromElement, preceding.toElement,
                preceding.terminateId, preceding.eventGenId
            );
            
            segments.set(precedingIdx, extended);
            
            logger.debug("PASS2: Extended preceding " + preceding.phase + 
                        " for " + tokenId + " from end=" + preceding.endTime + " to end=" + newStart);
        } else {
            // Child tokens spawned at a fork point don't have preceding segments - this is expected
            // Only warn for non-child tokens
            if (!isChildToken(tokenId)) {
                logger.warn("PASS2: Could not find preceding segment for " + tokenId + 
                           " ending at or before " + originalSegmentStart);
            }
        }
    }
    
    /**
     * Adjust following segments when a segment's end time changes.
     * This ensures continuity - no gaps or overlaps between consecutive segments.
     */
    private void adjustFollowingSegments(String tokenId, long originalEnd, long newEnd) {
        if (newEnd == originalEnd) return;
        
        long shift = newEnd - originalEnd;
        
        // Find segments for this token that start at the original end time
        for (int i = 0; i < segments.size(); i++) {
            AnimationSegment seg = segments.get(i);
            
            if (seg.tokenId.equals(tokenId) && seg.startTime == originalEnd) {
                // This segment starts right where the adjusted one ended - shift it
                long shiftedStart = seg.startTime + shift;
                long shiftedEnd = seg.endTime + shift;
                
                AnimationSegment shifted = new AnimationSegment(
                    seg.tokenId, seg.version, seg.phase,
                    shiftedStart, shiftedEnd,
                    seg.placeId, seg.tInId, seg.tOutId,
                    seg.fromElement, seg.toElement,
                    seg.terminateId, seg.eventGenId
                );
                
                segments.set(i, shifted);
                
                logger.debug("PASS2: Shifted following " + seg.phase + 
                            " for " + tokenId + " by " + shift + "ms");
                
                // Recursively adjust the next segment
                adjustFollowingSegments(tokenId, seg.endTime, shiftedEnd);
                break; // Only one segment should start at exactly this time
            }
        }
    }
    
    /**
     * Post-processing: Resolve overlapping place occupancy.
     * 
     * Petri net semantics require that only one token occupies a place at a time
     * (unless it's a multi-capacity place, which we don't model here).
     * 
     * When two tokens would visually occupy the same place simultaneously:
     * 1. The earlier token (by AT_PLACE start time) keeps its timing
     * 2. The later token's BUFFERED_AT_TIN is extended to wait
     * 3. The later token's TRAVELING_TO_PLACE is shifted accordingly
     * 
     * This creates a visual "queue" at T_in, which accurately represents
     * tokens waiting for admission to an occupied place.
     */
    private void resolveOverlappingPlaceOccupancy() {
        // Group AT_PLACE segments by place
        Map<String, List<AnimationSegment>> atPlaceByPlace = new HashMap<>();
        for (AnimationSegment seg : segments) {
            if (seg.phase == Phase.AT_PLACE && seg.placeId != null) {
                atPlaceByPlace.computeIfAbsent(seg.placeId, k -> new ArrayList<>()).add(seg);
            }
        }
        
        // Track segments to replace: old -> new
        Map<AnimationSegment, AnimationSegment> replacements = new HashMap<>();
        int overlapsResolved = 0;
        
        // For each place, detect and resolve overlaps
        for (Map.Entry<String, List<AnimationSegment>> entry : atPlaceByPlace.entrySet()) {
            String placeId = entry.getKey();
            List<AnimationSegment> placeSegments = entry.getValue();
            
            if (placeSegments.size() < 2) continue;
            
            // Sort by start time
            placeSegments.sort(Comparator.comparingLong(s -> s.startTime));
            
            // Check each pair for overlaps
            for (int i = 0; i < placeSegments.size() - 1; i++) {
                AnimationSegment current = placeSegments.get(i);
                
                // Skip if already being replaced
                while (replacements.containsKey(current)) {
                    current = replacements.get(current);
                }
                
                for (int j = i + 1; j < placeSegments.size(); j++) {
                    AnimationSegment next = placeSegments.get(j);
                    
                    // Skip if already being replaced
                    while (replacements.containsKey(next)) {
                        next = replacements.get(next);
                    }
                    
                    // Skip same-token comparisons - a token can't block itself
                    // This happens in cyclic workflows where the same token visits a place multiple times
                    if (current.tokenId.equals(next.tokenId)) {
                        continue;
                    }
                    
                    // Check for overlap: next starts before current ends
                    if (next.startTime < current.endTime) {
                        // Overlap detected! Delay the later token
                        long requiredDelay = current.endTime - next.startTime;
                        
                        logger.debug("Overlap at " + placeId + ": token " + current.tokenId + 
                                    " [" + current.startTime + "-" + current.endTime + "] overlaps with " +
                                    next.tokenId + " [" + next.startTime + "-" + next.endTime + 
                                    "], delay=" + requiredDelay + "ms");
                        
                        // Find and adjust the overlapping token's approach segments
                        adjustTokenApproachForDelay(next, requiredDelay, replacements);
                        overlapsResolved++;
                        
                        // Update placeSegments with the replacement for further overlap checks
                        AnimationSegment replacement = replacements.get(next);
                        if (replacement != null) {
                            placeSegments.set(j, replacement);
                        }
                    }
                }
            }
        }
        
        // Apply all replacements to the segments list
        if (!replacements.isEmpty()) {
            List<AnimationSegment> newSegments = new ArrayList<>();
            for (AnimationSegment seg : segments) {
                AnimationSegment replacement = replacements.get(seg);
                newSegments.add(replacement != null ? replacement : seg);
            }
            segments.clear();
            segments.addAll(newSegments);
            
            // Re-sort after modifications
            segments.sort(Comparator.comparingLong(s -> s.startTime));
            
            logger.info("Resolved " + overlapsResolved + " place occupancy overlaps (cross-token)");
        }
    }
    
    /**
     * POST-PROCESSING: Ensure all arrivals at JOIN T_ins have buffer segments.
     * 
     * This is a safety net that catches ANY code path that generates TRAVELING_TO_NEXT_TIN
     * to a JOIN destination without generating the required BUFFERED_AT_TIN segment.
     * 
     * The rule: Every TRAVELING_TO_NEXT_TIN segment whose destination (toElement) is a 
     * JOIN T_in MUST be followed by a BUFFERED_AT_TIN segment for the same token at that T_in.
     * If not present, we create one.
     * 
     * This handles:
     * - Feedback paths in cyclic workflows (P5â†’T_in_NS_Green, P6â†’T_in_NS_Green)
     * - JOIN_SURVIVOR exits that go to another JOIN
     * - Reborn tokens traveling to JOINs
     * - Any other edge cases missed by individual code paths
     */
    private void ensureJoinBufferSegments() {
        List<AnimationSegment> newBufferSegments = new ArrayList<>();
        
        // Find all TRAVELING_TO_NEXT_TIN segments going to JOIN T_ins
        for (AnimationSegment travelSeg : segments) {
            if (travelSeg.phase != Phase.TRAVELING_TO_NEXT_TIN) continue;
            if (travelSeg.toElement == null) continue;
            
            // Check if destination is a JOIN
            String destTIn = travelSeg.toElement;
            if (!isJoin(destTIn)) continue;
            
            // Check if there's already a BUFFERED_AT_TIN segment for this token
            // starting at or near when the travel ends
            boolean hasBuffer = false;
            long travelEnd = travelSeg.endTime;
            
            for (AnimationSegment seg : segments) {
                if (!seg.tokenId.equals(travelSeg.tokenId)) continue;
                if (seg.phase != Phase.BUFFERED_AT_TIN) continue;
                if (seg.tInId == null || !seg.tInId.equals(destTIn)) continue;
                
                // Check if this buffer starts at or near when travel ends
                // Allow some tolerance for timing variations
                long timeDiff = Math.abs(seg.startTime - travelEnd);
                if (timeDiff < 100) {  // Within 100ms tolerance
                    hasBuffer = true;
                    break;
                }
            }
            
            if (!hasBuffer) {
                // Need to create a buffer segment
                // Find when this token next does something at the destination place
                String destPlace = travelSeg.placeId;
                long bufferEnd = travelEnd + 500;  // Default: 500ms buffer
                
                // Look for the next event for this token at the destination
                for (AnimationSegment seg : segments) {
                    if (!seg.tokenId.equals(travelSeg.tokenId)) continue;
                    if (seg.startTime <= travelEnd) continue;
                    
                    // Found next segment for this token after travel
                    if (seg.placeId != null && seg.placeId.equals(destPlace)) {
                        bufferEnd = seg.startTime;
                        break;
                    }
                    // Also check for CONSUMED (JOIN_CONSUMED case)
                    if (seg.phase == Phase.CONSUMED) {
                        bufferEnd = seg.startTime;
                        break;
                    }
                }
                
                // Only create buffer if there's actual time to buffer
                if (bufferEnd > travelEnd) {
                    AnimationSegment bufferSeg = new AnimationSegment(
                        travelSeg.tokenId, travelSeg.version, Phase.BUFFERED_AT_TIN,
                        travelEnd, bufferEnd,
                        destPlace, destTIn, null,
                        null, null, null, null
                    );
                    newBufferSegments.add(bufferSeg);
                    
                    logger.debug("POST-PROCESS: Added missing BUFFERED_AT_TIN for " + travelSeg.tokenId +
                                " at JOIN " + destTIn + " (" + travelEnd + "-" + bufferEnd + ")");
                }
            }
        }
        
        if (!newBufferSegments.isEmpty()) {
            segments.addAll(newBufferSegments);
            segments.sort(Comparator.comparingLong(s -> s.startTime));
            logger.info("POST-PROCESS: Added " + newBufferSegments.size() + " missing buffer segments at JOINs");
        }
    }
    
    /**
     * Adjust a token's approach segments to add delay before entering a place.
     * 
     * For cyclic workflows, we need to find the SPECIFIC segments that precede
     * the overlapping AT_PLACE segment (by matching time ranges), not just any
     * segments with the same tokenId/placeId.
     * 
     * @param overlappingAtPlace The specific AT_PLACE segment that needs to be delayed
     * @param delay How much to delay the segments
     * @param replacements Map to track segment replacements
     */
    private void adjustTokenApproachForDelay(AnimationSegment overlappingAtPlace, long delay,
                                              Map<AnimationSegment, AnimationSegment> replacements) {
        String tokenId = overlappingAtPlace.tokenId;
        String placeId = overlappingAtPlace.placeId;
        long atPlaceStart = overlappingAtPlace.startTime;
        
        // Find the segments that immediately precede this AT_PLACE segment
        // They should end at or just before atPlaceStart
        AnimationSegment bufferSeg = null;
        AnimationSegment travelToPlaceSeg = null;
        
        // Time window to find related segments (within 500ms of AT_PLACE start)
        long searchWindow = 500;
        
        for (AnimationSegment seg : segments) {
            if (!seg.tokenId.equals(tokenId)) continue;
            if (seg.placeId == null || !seg.placeId.equals(placeId)) continue;
            if (replacements.containsKey(seg)) continue;
            
            // Find segments that end near the AT_PLACE start time
            long timeDiff = Math.abs(seg.endTime - atPlaceStart);
            
            if (seg.phase == Phase.BUFFERED_AT_TIN && timeDiff < searchWindow) {
                // Prefer the closest match
                if (bufferSeg == null || timeDiff < Math.abs(bufferSeg.endTime - atPlaceStart)) {
                    bufferSeg = seg;
                }
            } else if (seg.phase == Phase.TRAVELING_TO_PLACE && timeDiff < searchWindow) {
                if (travelToPlaceSeg == null || timeDiff < Math.abs(travelToPlaceSeg.endTime - atPlaceStart)) {
                    travelToPlaceSeg = seg;
                }
            }
        }
        
        // Create replacement segments with delay applied
        if (bufferSeg != null) {
            AnimationSegment newBuffer = new AnimationSegment(
                bufferSeg.tokenId, bufferSeg.version, bufferSeg.phase,
                bufferSeg.startTime, bufferSeg.endTime + delay,
                bufferSeg.placeId, bufferSeg.tInId, bufferSeg.tOutId,
                bufferSeg.fromElement, bufferSeg.toElement,
                bufferSeg.terminateId, bufferSeg.eventGenId
            );
            replacements.put(bufferSeg, newBuffer);
        }
        
        if (travelToPlaceSeg != null) {
            AnimationSegment newTravel = new AnimationSegment(
                travelToPlaceSeg.tokenId, travelToPlaceSeg.version, travelToPlaceSeg.phase,
                travelToPlaceSeg.startTime + delay, travelToPlaceSeg.endTime + delay,
                travelToPlaceSeg.placeId, travelToPlaceSeg.tInId, travelToPlaceSeg.tOutId,
                travelToPlaceSeg.fromElement, travelToPlaceSeg.toElement,
                travelToPlaceSeg.terminateId, travelToPlaceSeg.eventGenId
            );
            replacements.put(travelToPlaceSeg, newTravel);
        }
        
        // Shift the AT_PLACE segment itself
        AnimationSegment newAtPlace = new AnimationSegment(
            overlappingAtPlace.tokenId, overlappingAtPlace.version, overlappingAtPlace.phase,
            overlappingAtPlace.startTime + delay, overlappingAtPlace.endTime + delay,
            overlappingAtPlace.placeId, overlappingAtPlace.tInId, overlappingAtPlace.tOutId,
            overlappingAtPlace.fromElement, overlappingAtPlace.toElement,
            overlappingAtPlace.terminateId, overlappingAtPlace.eventGenId
        );
        replacements.put(overlappingAtPlace, newAtPlace);
    }
    
    /**
     * Generate segments for a single token based on its events
     */
    private void generateSegmentsForToken(String tokenId, List<MarkingEvent> tokenEvents,
                                          Map<String, String> childToParent,
                                          Map<String, Long> forkTimes) {
        if (tokenEvents.isEmpty()) return;
        
        String version = tokenEvents.get(0).version;
        boolean isChild = isChildToken(tokenId);
        
        // Look for GENERATED event (token created at event generator)
        MarkingEvent generatedEvent = null;
        
        // For child tokens, find their FORK event which tells us where they're going
        MarkingEvent forkEvent = null;
        MarkingEvent firstEnterOrBuffered = null;
        for (MarkingEvent e : tokenEvents) {
            if (e.isGenerated() && generatedEvent == null) {
                generatedEvent = e;
            }
            if (e.isFork() && forkEvent == null) {
                forkEvent = e;
            }
            if ((e.isEnter() || e.isBuffered()) && firstEnterOrBuffered == null) {
                firstEnterOrBuffered = e;
            }
        }
        
        // Generate approach segments
        // Special case: In cyclic workflows, a child token's first ENTER may come BEFORE its first FORK
        // (the token exists from a previous cycle or the analysis started mid-workflow).
        // In this case, treat the child token as if it came from the event generator.
        boolean forkBeforeFirstEnter = (forkEvent != null && firstEnterOrBuffered != null && 
                                        forkEvent.timestamp < firstEnterOrBuffered.timestamp);
        
        if (isChild && forkEvent != null && forkBeforeFirstEnter) {
            // Child token with FORK that comes before first ENTER: animate from fork point to destination
            // IMPORTANT: The FORK event's toPlace is often wrong (just the fork location label like "P1")
            // The child's actual destination is in the EXIT event that follows the FORK
            String destPlace = null;
            
            // Look for the EXIT event that follows the FORK - it has the real destination
            for (MarkingEvent e : tokenEvents) {
                if (e.isExit() && e.toPlace != null && !e.toPlace.isEmpty()) {
                    destPlace = e.toPlace;
                    break;
                }
            }
            
            // Fallback: use firstEnterOrBuffered's placeId
            if (destPlace == null && firstEnterOrBuffered != null) {
                destPlace = firstEnterOrBuffered.placeId;
            }
            
            // Last resort: try FORK event's toPlace (but it's probably wrong)
            if (destPlace == null) {
                destPlace = forkEvent.toPlace;
            }
            
            if (destPlace != null) {
                destPlace = resolveToServiceName(destPlace);
                generateChildApproachSegmentsFromFork(tokenId, version, forkEvent, destPlace, firstEnterOrBuffered);
            }
        } else if (firstEnterOrBuffered != null) {
            // Determine what kind of token this is and how to handle approach segments
            
            boolean isReconstitutedParent = (generatedEvent == null && !isChild);
            boolean isChildAtJoin = isChild && generatedEvent != null;
            
            logger.debug("Token " + tokenId + " approach decision: isChild=" + isChild + 
                        " generatedEvent=" + (generatedEvent != null) + 
                        " isReconstitutedParent=" + isReconstitutedParent + 
                        " isChildAtJoin=" + isChildAtJoin);
            
            if (isReconstitutedParent) {
                // NO approach segments for reconstituted parent!
                // The parent appears directly at the place when the join completes.
                // 
                // IMPORTANT: Find the FORK event to determine when the parent should appear.
                // The parent appears just before it exits (FORKs), not at the ENTER timestamp
                // which may be recorded before children finish arriving.
                MarkingEvent parentForkEvent = null;
                MarkingEvent parentExitEvent = null;
                for (MarkingEvent e : tokenEvents) {
                    if (e.isFork() && parentForkEvent == null) {
                        parentForkEvent = e;
                    }
                    if (e.isExit() && parentExitEvent == null) {
                        parentExitEvent = e;
                    }
                }
                
                if (parentForkEvent != null && firstEnterOrBuffered != null) {
                    // Parent appears briefly, then travels to T_out and FORKs
                    long exitTime = (parentExitEvent != null) ? parentExitEvent.timestamp : parentForkEvent.timestamp;
                    long atPlaceStart = exitTime - TRAVEL_DURATION_TO_TOUT - 200; // Brief time at place
                    long atPlaceEnd = exitTime - TRAVEL_DURATION_TO_TOUT;
                    
                    // AT_PLACE segment
                    segments.add(new AnimationSegment(
                        tokenId, version, Phase.AT_PLACE,
                        atPlaceStart, atPlaceEnd,
                        firstEnterOrBuffered.placeId,
                        placeToTIn.get(firstEnterOrBuffered.placeId),
                        placeToTOut.get(firstEnterOrBuffered.placeId),
                        null, null, null, null
                    ));
                    
                    // TRAVELING_TO_TOUT segment  
                    segments.add(new AnimationSegment(
                        tokenId, version, Phase.TRAVELING_TO_TOUT,
                        atPlaceEnd, exitTime,
                        firstEnterOrBuffered.placeId,
                        placeToTIn.get(firstEnterOrBuffered.placeId),
                        placeToTOut.get(firstEnterOrBuffered.placeId),
                        firstEnterOrBuffered.placeId,
                        placeToTOut.get(firstEnterOrBuffered.placeId),
                        null, null
                    ));
                    
                    logger.debug("Reconstituted parent " + tokenId + 
                                " - AT_PLACE from " + atPlaceStart + " to " + atPlaceEnd +
                                " then TRAVELING_TO_TOUT until " + exitTime);
                } else {
                    logger.debug("Reconstituted parent token " + tokenId + 
                                " - no FORK found, using default processing");
                }
            } else if (isChildAtJoin) {
                // Child token with GENERATED event - it came from Event Generator
                // but it's arriving at a JOIN. It should:
                // 1. Appear at Event Generator (briefly)
                // 2. Travel to T_in (the JOIN)
                // 3. Buffer at T_in waiting for sibling
                // 4. Either get CONSUMED or continue as JOIN_SURVIVOR
                logger.debug("Child at JOIN token " + tokenId + " - calling generateChildApproachToJoin");
                generateChildApproachToJoin(tokenId, version, firstEnterOrBuffered, generatedEvent, forkEvent, tokenEvents);
              /*  
                // ALSO: This child will be "reborn" after the parent FORKs.
                // Generate travel segments from FORK to the child's next destination (P2 or P3)
                if (forkEvent != null) {
                    // Find the ENTER event AFTER the FORK - that's where the child goes after rebirth
                    MarkingEvent enterAfterFork = null;
                    for (MarkingEvent e : tokenEvents) {
                        if (e.isEnter() && e.timestamp > forkEvent.timestamp) {
                            enterAfterFork = e;
                            break;
                        }
                    }
                    
                    if (enterAfterFork != null) {
                        String destPlace = resolveToServiceName(enterAfterFork.placeId);
                        logger.debug("Child " + tokenId + " reborn after FORK, going to " + destPlace);
                        generateChildApproachSegmentsFromFork(tokenId, version, forkEvent, destPlace, enterAfterFork);
                    }
                }*/
            } else {
                // Regular token (not a child, has GENERATED event) - full approach from EG
                logger.debug("Regular token " + tokenId + " - calling generateRegularApproachSegments");
                generateRegularApproachSegments(tokenId, version, firstEnterOrBuffered, generatedEvent);
            }
        }
        
        // Track which events we've processed to avoid duplicates
        Set<Integer> processedIndices = new HashSet<>();
        
        // For reconstituted parent, mark the first ENTER as processed 
        // (we already created the AT_PLACE segment above)
        // Reconstituted parent = no GENERATED event and not a child token
        boolean isReconstitutedParentToken = (generatedEvent == null && !isChild);
        if (isReconstitutedParentToken && firstEnterOrBuffered != null) {
            for (int i = 0; i < tokenEvents.size(); i++) {
                MarkingEvent e = tokenEvents.get(i);
                if (e == firstEnterOrBuffered) {
                    processedIndices.add(i);
                    break;
                }
            }
        }
        
        // For children with GENERATED events arriving at JOIN, mark their BUFFERED, ENTER,
        // and JOIN_CONSUMED as already processed (handled by generateChildApproachToJoin)
        // For JOIN_SURVIVOR, also mark EXIT and TERMINATE as processed
        if (isChild && generatedEvent != null && firstEnterOrBuffered != null) {
            // Mark the GENERATED event as processed
            int genIndex = tokenEvents.indexOf(generatedEvent);
            if (genIndex >= 0) {
                processedIndices.add(genIndex);
            }
            
            // Mark BUFFERED events at the JOIN place as processed
            for (int i = 0; i < tokenEvents.size(); i++) {
                MarkingEvent e = tokenEvents.get(i);
                if (e.isBuffered() && e.placeId.equals(firstEnterOrBuffered.placeId)) {
                    processedIndices.add(i);
                }
            }
            
            // Mark the first ENTER at the JOIN place as processed
            for (int i = 0; i < tokenEvents.size(); i++) {
                MarkingEvent e = tokenEvents.get(i);
                if (e.isEnter() && e.placeId.equals(firstEnterOrBuffered.placeId)) {
                    processedIndices.add(i);
                    break;
                }
            }
            
            // Mark the first JOIN_CONSUMED at the JOIN place as processed
            for (int i = 0; i < tokenEvents.size(); i++) {
                MarkingEvent e = tokenEvents.get(i);
                if (e.isJoinConsumed() && e.placeId.equals(firstEnterOrBuffered.placeId)) {
                    processedIndices.add(i);
                    break;
                }
            }
            
            // For JOIN_SURVIVOR (no JOIN_CONSUMED), mark EXIT and TERMINATE as processed
            // since generateChildApproachToJoin handles the full flow
            boolean hasJoinConsumed = false;
            for (MarkingEvent e : tokenEvents) {
                if (e.isJoinConsumed() && e.placeId.equals(firstEnterOrBuffered.placeId)) {
                    hasJoinConsumed = true;
                    break;
                }
            }
            
            if (!hasJoinConsumed) {
                // This is a JOIN_SURVIVOR - mark EXIT and TERMINATE as processed
                for (int i = 0; i < tokenEvents.size(); i++) {
                    MarkingEvent e = tokenEvents.get(i);
                    if (e.isExit() && e.placeId.equals(firstEnterOrBuffered.placeId)) {
                        processedIndices.add(i);
                        break;
                    }
                }
                for (int i = 0; i < tokenEvents.size(); i++) {
                    MarkingEvent e = tokenEvents.get(i);
                    if (e.isTerminate()) {
                        processedIndices.add(i);
                        break;
                    }
                }
            }
        }
        
        // For child tokens, mark the FORK and first EXIT as already processed
        // (they're handled by generateChildApproachSegmentsFromFork)
        if (isChild && forkEvent != null) {
            int forkIndex = tokenEvents.indexOf(forkEvent);
            if (forkIndex >= 0) {
                processedIndices.add(forkIndex);
            }
            // Also mark the first EXIT after FORK as processed
            for (int i = 0; i < tokenEvents.size(); i++) {
                MarkingEvent e = tokenEvents.get(i);
                if (e.isExit() && e.placeId.equals(forkEvent.placeId)) {
                    processedIndices.add(i);
                    break; // Only skip the first one
                }
            }
        }
        
        // Process events to generate movement segments
        for (int i = 0; i < tokenEvents.size(); i++) {
            if (processedIndices.contains(i)) continue;
            
            MarkingEvent current = tokenEvents.get(i);
            
            if (current.isFork()) {
                // FORK events are handled in approach segments for child tokens
                // For parent tokens, FORK means they're consumed
                if (!isChild) {
                    // Parent is consumed at fork - but may rejoin later
                    // Don't add CONSUMED segment here; we'll handle it via EXIT logic
                }
                processedIndices.add(i);
                continue;
            }
            
            
            if ((current.isEnter() || current.isJoinSurvivor()) && !current.isBuffered()) {
                // Token at place - but check if this is a child token waiting for JOIN
                // If there's a JOIN_CONSUMED event after this ENTER (without an EXIT in between),
                // the token should be shown as BUFFERED at T_in, not AT_PLACE
                MarkingEvent joinConsumed = null;
                MarkingEvent exitEvent = null;
                
                for (int j = i + 1; j < tokenEvents.size(); j++) {
                    MarkingEvent e = tokenEvents.get(j);
                    if (e.isJoinConsumed() && e.placeId.equals(current.placeId)) {
                        joinConsumed = e;
                        break;
                    }
                    if (e.isExit() && e.placeId.equals(current.placeId)) {
                        exitEvent = e;
                        break;
                    }
                }
                
                // If this child token gets JOIN_CONSUMED (not EXIT), it should buffer at T_in
                if (isChild && joinConsumed != null && exitEvent == null) {
                    // Child waiting for JOIN - show as BUFFERED_AT_TIN until JOIN_CONSUMED
                    String tInId = placeToTIn.get(current.placeId);
                    
                    segments.add(new AnimationSegment(
                        tokenId, version, Phase.BUFFERED_AT_TIN,
                        current.timestamp, joinConsumed.timestamp,
                        current.placeId, tInId, null,
                        null, null, null, null
                    ));
                    
                    // Then add CONSUMED segment
                    segments.add(new AnimationSegment(
                        tokenId, version, Phase.CONSUMED,
                        joinConsumed.timestamp, endTime + 10000,
                        current.placeId, null, null, null, null, null, null
                    ));
                    
                    // Mark the JOIN_CONSUMED as processed
                    int joinIndex = tokenEvents.indexOf(joinConsumed);
                    if (joinIndex >= 0) {
                        processedIndices.add(joinIndex);
                    }
                    processedIndices.add(i);
                    continue;
                }
                
                // Regular token or token that exits - find when it exits
                if (exitEvent == null) {
                    exitEvent = findNextExitAfterIndex(tokenEvents, i, current.placeId);
                }
                
                if (exitEvent != null) {
                    int exitIndex = tokenEvents.indexOf(exitEvent);
                    
                    // AT_PLACE segment
                    long atPlaceEnd = exitEvent.timestamp - TRAVEL_DURATION_TO_TOUT;
                    if (atPlaceEnd > current.timestamp) {
                        segments.add(new AnimationSegment(
                            tokenId, version, Phase.AT_PLACE,
                            current.timestamp, atPlaceEnd,
                            current.placeId,
                            placeToTIn.get(current.placeId),
                            placeToTOut.get(current.placeId),
                            null, null, null, null
                        ));
                    }
                    
                    // TRAVELING_TO_TOUT segment
                    segments.add(new AnimationSegment(
                        tokenId, version, Phase.TRAVELING_TO_TOUT,
                        Math.max(atPlaceEnd, current.timestamp), exitEvent.timestamp,
                        current.placeId,
                        placeToTIn.get(current.placeId),
                        placeToTOut.get(current.placeId),
                        current.placeId,
                        placeToTOut.get(current.placeId),
                        null, null
                    ));
                    
                    // IMPORTANT: Also generate the segments for where the token goes AFTER T_out
                    // This handles TERMINATE, travel to next T_in, etc.
                    generateExitSegments(tokenId, version, exitEvent, tokenEvents, exitIndex);
                    
                    // Mark the exit as processed since we handled it here
                    if (exitIndex >= 0) {
                        processedIndices.add(exitIndex);
                    }
                } else {
                    // No exit found - token stays at place
                    segments.add(new AnimationSegment(
                        tokenId, version, Phase.AT_PLACE,
                        current.timestamp, endTime + 10000,
                        current.placeId,
                        placeToTIn.get(current.placeId),
                        placeToTOut.get(current.placeId),
                        null, null, null, null
                    ));
                }
                processedIndices.add(i);
                
            } else if (current.isExit()) {
                // Token exiting - generate travel to next T_in
                generateExitSegments(tokenId, version, current, tokenEvents, i);
                processedIndices.add(i);
                
                
            } else if (current.isBuffered()) {
                // Token buffered at JOIN - find when buffering ends
                MarkingEvent nextEvent = null;
                
                // Look ahead to find what happens to this buffered token
                for (int j = i + 1; j < tokenEvents.size(); j++) {
                    MarkingEvent e = tokenEvents.get(j);
                    
                    // Buffering ends when token either:
                    // 1. Survives join (JOIN_SURVIVOR)
                    // 2. Enters normally (ENTER at same place)
                    // 3. Gets consumed (JOIN_CONSUMED)
                    if (e.placeId.equals(current.placeId) && 
                        (e.isJoinSurvivor() || e.isEnter() || e.isJoinConsumed())) {
                        nextEvent = e;
                        break;
                    }
                }
                
                // Determine when buffering ends
                long bufferEnd;
                if (nextEvent != null) {
                    bufferEnd = nextEvent.timestamp;
                } else {
                    bufferEnd = endTime + 10000;
                }
                
                // Get the T_in for this place
                String tInId = placeToTIn.get(current.placeId);
                
                segments.add(new AnimationSegment(
                    tokenId, version, Phase.BUFFERED_AT_TIN,
                    current.timestamp, bufferEnd,
                    current.placeId,
                    tInId,
                    null,
                    null, null, null, null
                ));
                processedIndices.add(i);
                
                
            } else if (current.isJoinConsumed()) {
                // ============================================================
                // SEMANTIC RULE: Rebirth after JOIN consumption
                // ============================================================
                // A token consumed at a JOIN may be reborn at a subsequent FORK.
                // This can happen multiple times in cyclic workflows (e.g., traffic lights).
                // 
                // The rule: If there's a FORK event after this JOIN_CONSUMED, the token
                // is temporarily invisible (CONSUMED) until the FORK, then travels to
                // its new destination. The CONSUMED period is bounded by the actual
                // FORK timestamp, not extended artificially.
                // ============================================================
                
                MarkingEvent rebirthFork = null;
                MarkingEvent rebirthExit = null;
                
                for (int j = i + 1; j < tokenEvents.size(); j++) {
                    MarkingEvent e = tokenEvents.get(j);
                    if (e.isFork() && rebirthFork == null) {
                        rebirthFork = e;
                    }
                    if (e.isExit() && rebirthFork != null && rebirthExit == null) {
                        rebirthExit = e;
                        break;
                    }
                }
                
                if (rebirthFork != null && rebirthExit != null && rebirthExit.toPlace != null) {
                    // Token is reborn via FORK!
                    long forkTime = rebirthFork.timestamp;
                    long exitTime = rebirthExit.timestamp;
                    
                    // CONSUMED from JOIN_CONSUMED until FORK
                    segments.add(new AnimationSegment(
                        tokenId, version, Phase.CONSUMED,
                        current.timestamp, forkTime,
                        current.placeId,
                        null, null, null, null, null, null
                    ));
                    
                    // Generate travel segment for rebirth
                    String rebirthDestPlace = resolveToServiceName(rebirthExit.toPlace);
                    String forkTOut = placeToTOut.get(rebirthFork.placeId);
                    String rebirthDestTIn = placeToTIn.get(rebirthDestPlace);
                    
                    if (forkTOut != null && rebirthDestTIn != null) {
                        // Find arrival time at destination
                        MarkingEvent destArrival = null;
                        for (int j = i + 1; j < tokenEvents.size(); j++) {
                            MarkingEvent e = tokenEvents.get(j);
                            if ((e.isBuffered() || e.isEnter()) && 
                                rebirthDestPlace.equals(e.placeId) &&
                                e.timestamp > exitTime) {
                                destArrival = e;
                                break;
                            }
                        }
                        
                        long travelStart = exitTime;
                        long travelEnd = destArrival != null ? destArrival.timestamp : 
                                         travelStart + TRAVEL_DURATION_TO_NEXT;
                        
                        // TRAVELING_TO_NEXT_TIN for reborn token
                        segments.add(new AnimationSegment(
                            tokenId, version, Phase.TRAVELING_TO_NEXT_TIN,
                            travelStart, travelEnd,
                            rebirthDestPlace, rebirthDestTIn, forkTOut,
                            forkTOut, rebirthDestTIn,
                            null, null
                        ));
                        
                        logger.debug("Generated rebirth from subsequent JOIN: " + tokenId + 
                                    " consumed@" + current.timestamp + " reborn@" + forkTime +
                                    " -> " + rebirthDestTIn);
                        
                        // Mark the FORK and EXIT events as processed
                        int forkIdx = tokenEvents.indexOf(rebirthFork);
                        int exitIdx = tokenEvents.indexOf(rebirthExit);
                        if (forkIdx >= 0) processedIndices.add(forkIdx);
                        if (exitIdx >= 0) processedIndices.add(exitIdx);
                    }
                } else {
                    // Truly consumed - no rebirth
                    segments.add(new AnimationSegment(
                        tokenId, version, Phase.CONSUMED,
                        current.timestamp, endTime + 10000,
                        current.placeId,
                        null, null, null, null, null, null
                    ));
                }
                processedIndices.add(i);
                
            } else if (current.isTerminate()) {
                // Token terminated
                String tOut = placeToTOut.get(current.placeId);
                String terminateNode = tOutToTerminate.get(tOut);
                
                segments.add(new AnimationSegment(
                    tokenId, version, Phase.CONSUMED,
                    current.timestamp + 200, endTime + 10000,
                    null, null, null, null, null, terminateNode, null
                ));
                processedIndices.add(i);
            }
        }
    }
    
    /**
     * Find next EXIT event for same place, starting after given index
     */
    private MarkingEvent findNextExitAfterIndex(List<MarkingEvent> events, int startIndex, String placeId) {
        for (int i = startIndex + 1; i < events.size(); i++) {
            MarkingEvent e = events.get(i);
            if ((e.isExit() || e.isFork() || e.isTerminate()) && e.placeId.equals(placeId)) {
                return e;
            }
        }
        return null;
    }
    
    /**
     * Generate approach segments for child token from fork point.
     * Uses the FORK event's toPlace to determine destination.
     */
    private void generateChildApproachSegmentsFromFork(String tokenId, String version,
                                                        MarkingEvent forkEvent, String destPlace,
                                                        MarkingEvent firstEnterOrBuffered) {
        // The fork happens at the parent's T_out
        String forkPlace = forkEvent.placeId;  // Where the fork occurred (parent's place)
        String forkTOut = placeToTOut.get(forkPlace);
        
        if (forkTOut == null) {
            System.err.println("Child " + tokenId + ": Cannot find T_out for fork place " + forkPlace);
            return;
        }
        
        String destTIn = placeToTIn.get(destPlace);
        if (destTIn == null) {
            System.err.println("Child " + tokenId + ": Cannot find T_in for dest place " + destPlace);
            return;
        }
        
        // Check if destination T_in is a JOIN (explicitly marked as JoinNode or implicit from fork_behavior)
        // If so, and there's no EXIT after the ENTER, the child will be CONSUMED
        // and should NOT travel to the place - just buffer at T_in
        // NOTE: We use explicit JoinNode marking, NOT edge counting, to avoid
        // misidentifying retry loops as joins
        boolean destIsJoin = explicitJoinTIns.contains(destTIn) || implicitJoinTIns.contains(destTIn);
        
        long forkTime = forkEvent.timestamp;
        
        // Determine when the child arrives at destination
        long arrivalTime;
        if (firstEnterOrBuffered != null) {
            arrivalTime = firstEnterOrBuffered.timestamp;
        } else {
            // No arrival event - estimate based on travel time
            arrivalTime = forkTime + TRAVEL_DURATION_TO_NEXT + 500;
        }
        
        // Calculate timing
        long travelEnd = forkTime + TRAVEL_DURATION_TO_NEXT;
        long bufferEnd = arrivalTime - TRAVEL_DURATION_TO_PLACE;
        
        // TRAVELING_TO_NEXT_TIN - from fork T_out to destination T_in
        segments.add(new AnimationSegment(
            tokenId, version, Phase.TRAVELING_TO_NEXT_TIN,
            forkTime, Math.min(travelEnd, arrivalTime),
            destPlace, destTIn, forkTOut,
            forkTOut, destTIn,
            null, null
        ));
        
        // If destination is a JOIN, don't add TRAVELING_TO_PLACE here.
        // The main event loop will handle buffering at T_in and CONSUMED segments.
        if (destIsJoin) {
            // Just add buffer segment until arrival time - main loop handles the rest
            if (travelEnd < arrivalTime) {
                segments.add(new AnimationSegment(
                    tokenId, version, Phase.BUFFERED_AT_TIN,
                    travelEnd, arrivalTime,
                    destPlace, destTIn, null,
                    null, null, null, null
                ));
            }
            return; // Don't add TRAVELING_TO_PLACE for JOIN destinations
        }
        
        // BUFFERED_AT_TIN (if there's waiting time at the T_in)
        if (bufferEnd > travelEnd && firstEnterOrBuffered != null) {
            if (firstEnterOrBuffered.isBuffered()) {
                // Token is buffered at destination - create buffer segment from end of travel
                // to the actual BUFFERED event timestamp (fills any gap from travel calculation)
                if (travelEnd < arrivalTime) {
                    segments.add(new AnimationSegment(
                        tokenId, version, Phase.BUFFERED_AT_TIN,
                        travelEnd, arrivalTime,
                        destPlace, destTIn, null,
                        null, null, null, null
                    ));
                }
                // Note: Main loop handles subsequent BUFFERED->ENTER transition
            } else {
                // Regular enter - add buffer time
                segments.add(new AnimationSegment(
                    tokenId, version, Phase.BUFFERED_AT_TIN,
                    travelEnd, bufferEnd,
                    destPlace, destTIn, null,
                    null, null, null, null
                ));
                
                // TRAVELING_TO_PLACE
                segments.add(new AnimationSegment(
                    tokenId, version, Phase.TRAVELING_TO_PLACE,
                    bufferEnd, arrivalTime,
                    destPlace, destTIn, null,
                    destTIn, destPlace,
                    null, null
                ));
            }
        } else if (firstEnterOrBuffered != null && !firstEnterOrBuffered.isBuffered()) {
            // No buffer time, just travel to place
            segments.add(new AnimationSegment(
                tokenId, version, Phase.TRAVELING_TO_PLACE,
                Math.min(travelEnd, arrivalTime), arrivalTime,
                destPlace, destTIn, null,
                destTIn, destPlace,
                null, null
            ));
        }
    }
    
    /**
     * Generate approach segments for regular token from event generator.
     * 
     * DESIGN: All segments are derived from real data:
     *   - GENERATED timestamp: when token was created at event generator
     *   - ENTER timestamp: when token arrived at place
     *   - Distances: from topology positions
     * 
     * VELOCITY-BASED TIMING:
     *   - Travel durations calculated from distance / velocity (consistent speed)
     *   - AT_EVENT_GENERATOR: fixed dwell time for visibility
     *   - BUFFERED_AT_TIN: absorbs any extra time (represents waiting for admission)
     *   - Travel phases use actual distance-based timing
     * 
     * All segments fit exactly within GENERATEDâ†’ENTER. No hardcoded durations
     * that could cause overlap with subsequent AT_PLACE segments.
     */
    private void generateRegularApproachSegments(String tokenId, String version,
                                                  MarkingEvent firstEnter,
                                                  MarkingEvent generatedEvent) {
        String destPlace = firstEnter.placeId;
        String destTIn = placeToTIn.get(destPlace);
        
        // Find event generator - prefer the one from GENERATED event (accurate for concurrent workflows)
        String eventGen = null;
        if (generatedEvent != null && generatedEvent.transitionId != null && 
            !generatedEvent.transitionId.isEmpty()) {
            eventGen = generatedEvent.transitionId;
        } else {
            // Fallback: find event generator by T_in mapping
            for (Map.Entry<String, String> entry : eventGenToTIn.entrySet()) {
                if (entry.getValue().equals(destTIn)) {
                    eventGen = entry.getKey();
                    break;
                }
            }
        }
        
        long enterTime = firstEnter.timestamp;
        long generatedTime;
        
        if (generatedEvent != null) {
            generatedTime = generatedEvent.timestamp;
        } else {
            // No GENERATED event - estimate reasonable approach time before ENTER
            generatedTime = enterTime - 500;
        }
        
        // Total available time between GENERATED and ENTER
        long availableTime = enterTime - generatedTime;
        
        // Ensure minimum reasonable time for animation visibility
        if (availableTime < MIN_TRAVEL_DURATION * 4) {
            availableTime = MIN_TRAVEL_DURATION * 4;
            generatedTime = enterTime - availableTime;
        }
        
        // Get actual distances from topology for VELOCITY-BASED timing
        double distEgToTIn = calculatePathDistance(eventGen, destTIn);
        double distTInToPlace = calculatePathDistance(destTIn, destPlace);
        
        // Fallback distances if positions unavailable
        if (distEgToTIn < 0) distEgToTIn = 100.0;
        if (distTInToPlace < 0) distTInToPlace = 60.0;
        
        // VELOCITY-BASED TIMING: Calculate travel durations from distance and fixed velocity
        // This ensures consistent visual speed regardless of available time gap
        long travelToTInDuration = calculateTravelDuration(distEgToTIn, FALLBACK_TRAVEL_DURATION_EG_TO_TIN);
        long travelToPlaceDuration = calculateTravelDuration(distTInToPlace, FALLBACK_TRAVEL_DURATION_TO_PLACE);
        
        // Fixed dwell time at event generator (visible appearance)
        long atEgDuration = TIME_AT_EVENT_GEN;
        
        // Calculate minimum required time for animation phases
        long minRequiredTime = atEgDuration + travelToTInDuration + travelToPlaceDuration + MIN_TRAVEL_DURATION;
        
        // BUFFER ABSORPTION: All extra time goes into buffer at T_in
        // This represents the token waiting for admission to the place
        long bufferDuration;
        if (availableTime > minRequiredTime) {
            // Extra time = buffer wait (this is where tokens "queue" visually)
            bufferDuration = availableTime - atEgDuration - travelToTInDuration - travelToPlaceDuration;
        } else {
            // Tight timing - compress proportionally but keep minimum buffer
            double scale = (double) availableTime / minRequiredTime;
            atEgDuration = Math.max(MIN_TRAVEL_DURATION, (long)(atEgDuration * scale));
            travelToTInDuration = Math.max(MIN_TRAVEL_DURATION, (long)(travelToTInDuration * scale));
            travelToPlaceDuration = Math.max(MIN_TRAVEL_DURATION, (long)(travelToPlaceDuration * scale));
            bufferDuration = availableTime - atEgDuration - travelToTInDuration - travelToPlaceDuration;
            bufferDuration = Math.max(MIN_TRAVEL_DURATION / 2, bufferDuration);
        }
        
        // Calculate timestamps - all fit exactly within generatedTime to enterTime
        long atEventGenStart = generatedTime;
        long travelToTInStart = atEventGenStart + atEgDuration;
        long bufferStart = travelToTInStart + travelToTInDuration;
        long travelToPlaceStart = bufferStart + bufferDuration;
        // travelToPlaceEnd = enterTime (by construction)
        
        logger.debug("Token " + tokenId + " VELOCITY-BASED allocation: available=" + availableTime + 
                    "ms, atEG=" + atEgDuration + "ms, travelToTIn=" + travelToTInDuration + 
                    "ms, buffer=" + bufferDuration + "ms, travelToPlace=" + travelToPlaceDuration + "ms");
        
        // Generate segments
        if (eventGen != null) {
            // AT_EVENT_GENERATOR
            segments.add(new AnimationSegment(
                tokenId, version, Phase.AT_EVENT_GENERATOR,
                atEventGenStart, travelToTInStart,
                null, destTIn, null,
                null, null, null, eventGen
            ));
            
            // TRAVELING_TO_TIN
            segments.add(new AnimationSegment(
                tokenId, version, Phase.TRAVELING_TO_TIN,
                travelToTInStart, bufferStart,
                destPlace, destTIn, null,
                eventGen, destTIn,
                null, eventGen
            ));
        }
        
        // BUFFERED_AT_TIN
        segments.add(new AnimationSegment(
            tokenId, version, Phase.BUFFERED_AT_TIN,
            bufferStart, travelToPlaceStart,
            destPlace, destTIn, null,
            null, null, null, null
        ));
        
        // TRAVELING_TO_PLACE - ends exactly at enterTime
        segments.add(new AnimationSegment(
            tokenId, version, Phase.TRAVELING_TO_PLACE,
            travelToPlaceStart, enterTime,
            destPlace, destTIn, null,
            destTIn, destPlace,
            null, null
        ));
    }
    
    /**
     * Generate approach segments for regular token - backwards compatible version
     * without GENERATED event (falls back to reverse-engineering timestamps)
     */
    private void generateRegularApproachSegments(String tokenId, String version,
                                                  MarkingEvent firstEnter) {
        generateRegularApproachSegments(tokenId, version, firstEnter, null);
    }
    
    /**
     * Generate approach segments for a child token arriving at a JOIN from the Event Generator.
     * 
     * These children:
     * 1. Appear at Event Generator (using GENERATED timestamp)
     * 2. Travel to T_in (the JOIN transition)
     * 3. Buffer at T_in waiting for sibling(s) until JOIN fires
     * 4. Either:
     *    a. Get CONSUMED if they have a JOIN_CONSUMED event (most children)
     *    b. Continue to place if they are the JOIN_SURVIVOR (one child survives)
     * 
     * @param tokenEvents - all events for this token (to check for JOIN_CONSUMED vs ENTER)
     * @param forkEvent - the FORK event that happens after JOIN completes (used to determine CONSUMED time)
     */
    private void generateChildApproachToJoin(String tokenId, String version,
                                              MarkingEvent firstEnterOrBuffered,
                                              MarkingEvent generatedEvent,
                                              MarkingEvent forkEvent,
                                              List<MarkingEvent> tokenEvents) {
        String destPlace = firstEnterOrBuffered.placeId;
        String destTIn = placeToTIn.get(destPlace);
        
        // Find event generator - prefer the one from GENERATED event (accurate for concurrent workflows)
        String eventGen = null;
        if (generatedEvent != null && generatedEvent.transitionId != null && 
            !generatedEvent.transitionId.isEmpty()) {
            // Use the event generator from the GENERATED event - this is the actual source
            eventGen = generatedEvent.transitionId;
        } else {
            // Fallback: find event generator by T_in mapping (may be ambiguous with multiple EGs)
            for (Map.Entry<String, String> entry : eventGenToTIn.entrySet()) {
                if (entry.getValue().equals(destTIn)) {
                    eventGen = entry.getKey();
                    break;
                }
            }
        }
        
        // ============================================================
        // SEMANTIC RULE: First event, not last (cyclic workflow handling)
        // ============================================================
        // In cyclic workflows, a token may enter/exit the same place multiple times.
        // When looking for matching events, we must use the FIRST occurrence, not the
        // last. Using the last would cause timing to reference events from later cycles,
        // creating negative time segments or incorrect buffer durations.
        // ============================================================
        
        // Determine if this child is JOIN_CONSUMED or JOIN_SURVIVOR
        MarkingEvent joinConsumedEvent = null;
        MarkingEvent enterEvent = null;
        for (MarkingEvent e : tokenEvents) {
            if (joinConsumedEvent == null && e.isJoinConsumed() && e.placeId.equals(destPlace)) {
                joinConsumedEvent = e;
            }
            if (enterEvent == null && e.isEnter() && e.placeId.equals(destPlace)) {
                enterEvent = e;
            }
            if (joinConsumedEvent != null && enterEvent != null) {
                break;  // Found first of each - stop
            }
        }
        
        boolean isJoinSurvivor = (joinConsumedEvent == null && enterEvent != null);
        
        // Calculate distance-based travel duration
        long travelDurationEgToTIn = calculateTravelDuration(eventGen, destTIn, FALLBACK_TRAVEL_DURATION_EG_TO_TIN);
        
        // Use GENERATED timestamp for accurate timing
        long atEventGenStart = generatedEvent.timestamp;
        long travelToTInStart = atEventGenStart + TIME_AT_EVENT_GEN;
        long bufferStart = travelToTInStart + travelDurationEgToTIn;
        
        // Find when ALL siblings arrive at the join (for proper visual synchronization)
        // Look for all GENERATED events from the same event generator going to same destPlace
        long latestSiblingArrival = bufferStart;
        if (eventGen != null) {
            for (MarkingEvent e : events) {
                if (e.isGenerated() && eventGen.equals(e.transitionId)) {
                    // This is a sibling - calculate when it arrives at the buffer
                    long siblingGenTime = e.timestamp;
                    long siblingTravelStart = siblingGenTime + TIME_AT_EVENT_GEN;
                    long siblingArrival = siblingTravelStart + travelDurationEgToTIn;
                    latestSiblingArrival = Math.max(latestSiblingArrival, siblingArrival);
                }
            }
        }
        
        // Buffer end time depends on whether this is survivor or consumed
        // But must be at least when the last sibling arrives
        long bufferEnd;
        if (isJoinSurvivor && enterEvent != null) {
            // Survivor buffers until ENTER event OR last sibling arrives, whichever is later
            bufferEnd = Math.max(enterEvent.timestamp, latestSiblingArrival);
        } else if (joinConsumedEvent != null) {
            // Consumed at JOIN_CONSUMED time OR last sibling arrives, whichever is later
            bufferEnd = Math.max(joinConsumedEvent.timestamp, latestSiblingArrival);
        } else if (forkEvent != null) {
            // Fallback: buffer until FORK event
            bufferEnd = Math.max(forkEvent.timestamp, latestSiblingArrival);
        } else {
            // Last resort: buffer until end
            bufferEnd = endTime;
        }
        
        // AT_EVENT_GENERATOR
        if (eventGen != null) {
            segments.add(new AnimationSegment(
                tokenId, version, Phase.AT_EVENT_GENERATOR,
                atEventGenStart, travelToTInStart,
                null, destTIn, null,
                null, null, null, eventGen
            ));
            
            // TRAVELING_TO_TIN
            segments.add(new AnimationSegment(
                tokenId, version, Phase.TRAVELING_TO_TIN,
                travelToTInStart, bufferStart,
                destPlace, destTIn, null,
                eventGen, destTIn,
                null, eventGen
            ));
        }
        
        // BUFFERED_AT_TIN - child waits here until join fires
        segments.add(new AnimationSegment(
            tokenId, version, Phase.BUFFERED_AT_TIN,
            bufferStart, bufferEnd,
            destPlace, destTIn, null,
            null, null, null, null
        ));
        
        if (isJoinSurvivor) {
            // JOIN_SURVIVOR continues into the place
            // All timings must be calculated forward from bufferEnd to ensure proper sequencing
            String tOutId = placeToTOut.get(destPlace);
            long travelToPlaceStart = bufferEnd;
            long travelToPlaceEnd = travelToPlaceStart + TRAVEL_DURATION_TO_PLACE;
            
            // TRAVELING_TO_PLACE
            segments.add(new AnimationSegment(
                tokenId, version, Phase.TRAVELING_TO_PLACE,
                travelToPlaceStart, travelToPlaceEnd,
                destPlace, destTIn, tOutId,
                destTIn, destPlace,
                null, null
            ));
            
            // Find EXIT event to determine duration at place
            MarkingEvent exitEvent = null;
            for (MarkingEvent e : tokenEvents) {
                if (e.isExit() && e.placeId.equals(destPlace)) {
                    exitEvent = e;
                    break;
                }
            }
            
            // Also check for TERMINATE event
            MarkingEvent terminateEvent = null;
            for (MarkingEvent e : tokenEvents) {
                if (e.isTerminate()) {
                    terminateEvent = e;
                    break;
                }
            }
            
            // Calculate time at place - use original duration from DB events if available,
            // otherwise use a default duration
            long atPlaceStart = travelToPlaceEnd;
            long atPlaceEnd;
            long atPlaceDuration = 200; // default minimum time at place
            
            if (exitEvent != null && enterEvent != null) {
                // Use the original duration the token spent at the place
                atPlaceDuration = Math.max(atPlaceDuration, exitEvent.timestamp - enterEvent.timestamp - TRAVEL_DURATION_TO_TOUT);
            } else if (terminateEvent != null && enterEvent != null) {
                atPlaceDuration = Math.max(atPlaceDuration, terminateEvent.timestamp - enterEvent.timestamp - TRAVEL_DURATION_TO_TOUT);
            }
            atPlaceEnd = atPlaceStart + atPlaceDuration;
            
            // AT_PLACE
            segments.add(new AnimationSegment(
                tokenId, version, Phase.AT_PLACE,
                atPlaceStart, atPlaceEnd,
                destPlace, destTIn, tOutId,
                null, null, null, null
            ));
            
            // TRAVELING_TO_TOUT and beyond (if exiting/terminating)
            if (exitEvent != null || terminateEvent != null) {
                long travelToToutStart = atPlaceEnd;
                long travelToToutEnd = travelToToutStart + TRAVEL_DURATION_TO_TOUT;
                
                segments.add(new AnimationSegment(
                    tokenId, version, Phase.TRAVELING_TO_TOUT,
                    travelToToutStart, travelToToutEnd,
                    destPlace, destTIn, tOutId,
                    destPlace, tOutId,
                    null, null
                ));
                
                // Check if this place directly routes to terminate
                String terminateNode = tOutToTerminate.get(tOutId);
                
                if (terminateNode != null) {
                    // This place routes directly to terminate
                    long travelToTermStart = travelToToutEnd;
                    long travelToTermEnd = travelToTermStart + TRAVEL_DURATION_TO_NEXT;
                    
                    segments.add(new AnimationSegment(
                        tokenId, version, Phase.TRAVELING_TO_TERMINATE,
                        travelToTermStart, travelToTermEnd,
                        destPlace, null, tOutId,
                        tOutId, terminateNode,
                        terminateNode, null
                    ));
                    
                    segments.add(new AnimationSegment(
                        tokenId, version, Phase.AT_TERMINATE,
                        travelToTermEnd, endTime + 10000,
                        null, null, null,
                        null, null, terminateNode, null
                    ));
                } else if (exitEvent != null) {
                    // Exit to another place - generate TRAVELING_TO_NEXT_TIN
                    // Find the destination from the exit event's toPlace
                    String exitDestPlace = exitEvent.toPlace;
                    if (exitDestPlace != null && !exitDestPlace.isEmpty()) {
                        exitDestPlace = resolveToServiceName(exitDestPlace);
                        String exitDestTIn = placeToTIn.get(exitDestPlace);
                        
                        if (exitDestTIn != null) {
                            long travelToNextStart = travelToToutEnd;
                            long travelToNextEnd = travelToNextStart + TRAVEL_DURATION_TO_NEXT;
                            
                            // Find when token arrives at destination (BUFFERED or ENTER event)
                            // IMPORTANT: Use travelToNextStart as reference, not exitEvent.timestamp
                            // In cyclic workflows, exitEvent is the FIRST exit which may be from an earlier cycle
                            MarkingEvent nextArrival = null;
                            for (MarkingEvent e : tokenEvents) {
                                if ((e.isBuffered() || e.isEnter()) && 
                                    exitDestPlace.equals(e.placeId) &&
                                    e.timestamp > travelToNextStart) {
                                    nextArrival = e;
                                    break;
                                }
                            }
                            
                            // Adjust travel end time if we know when token arrives
                            if (nextArrival != null) {
                                travelToNextEnd = nextArrival.timestamp;
                            }
                            
                            segments.add(new AnimationSegment(
                                tokenId, version, Phase.TRAVELING_TO_NEXT_TIN,
                                travelToNextStart, travelToNextEnd,
                                exitDestPlace, exitDestTIn, tOutId,
                                tOutId, exitDestTIn,
                                null, null
                            ));
                            
                            logger.debug("Generated TRAVELING_TO_NEXT_TIN for JOIN_SURVIVOR " + tokenId + 
                                        ": " + tOutId + " -> " + exitDestTIn + 
                                        " (" + travelToNextStart + "-" + travelToNextEnd + ")");
                        }
                    }
                }
            }
            
            logger.debug("Generated JOIN_SURVIVOR approach: " + tokenId + 
                        " atEG@" + atEventGenStart + " travel@" + travelToTInStart + 
                        " buffer@" + bufferStart + "-" + bufferEnd + " -> continues to place");
        } else {
            // CONSUMED - child disappears when JOIN fires
            // BUT: Check if this token is "reborn" via FORK after being consumed
            // This happens in JOIN->FORK patterns where token IDs are reused
            MarkingEvent rebirthFork = null;
            MarkingEvent rebirthExit = null;
            for (MarkingEvent e : tokenEvents) {
                if (e.isFork() && e.timestamp > (joinConsumedEvent != null ? joinConsumedEvent.timestamp : 0)) {
                    rebirthFork = e;
                }
                if (e.isExit() && e.timestamp > (joinConsumedEvent != null ? joinConsumedEvent.timestamp : 0)) {
                    rebirthExit = e;
                    break; // Take first exit after JOIN_CONSUMED
                }
            }
            
            if (rebirthFork != null && rebirthExit != null) {
                // Token is reborn via FORK!
                // PASS 1: Use raw EXIT timestamp. Pass 2 will synchronize with survivor.
                long exitTime = rebirthExit.timestamp;
                
                // CONSUMED from JOIN until EXIT event
                segments.add(new AnimationSegment(
                    tokenId, version, Phase.CONSUMED,
                    bufferEnd, exitTime,
                    destPlace, null, null,
                    null, null, null, null
                ));
                
                // Generate travel segment using EXIT event timestamp
                String rebirthDestPlace = rebirthExit.toPlace;
                if (rebirthDestPlace != null && !rebirthDestPlace.isEmpty()) {
                    rebirthDestPlace = resolveToServiceName(rebirthDestPlace);
                    String rebirthTOut = placeToTOut.get(destPlace);
                    String rebirthDestTIn = placeToTIn.get(rebirthDestPlace);
                    
                    if (rebirthTOut != null && rebirthDestTIn != null) {
                        // Find arrival time at destination
                        MarkingEvent destArrival = null;
                        for (MarkingEvent e : tokenEvents) {
                            if ((e.isBuffered() || e.isEnter()) && 
                                rebirthDestPlace.equals(e.placeId) &&
                                e.timestamp > exitTime) {
                                destArrival = e;
                                break;
                            }
                        }
                        
                        long travelStart = exitTime;
                        long travelEnd = destArrival != null ? destArrival.timestamp : 
                                         travelStart + TRAVEL_DURATION_TO_NEXT;
                        
                        // TRAVELING_TO_NEXT_TIN for reborn token
                        segments.add(new AnimationSegment(
                            tokenId, version, Phase.TRAVELING_TO_NEXT_TIN,
                            travelStart, travelEnd,
                            rebirthDestPlace, rebirthDestTIn, rebirthTOut,
                            rebirthTOut, rebirthDestTIn,
                            null, null
                        ));
                        
                        logger.debug("PASS1: Generated rebirth TRAVELING_TO_NEXT_TIN for " + tokenId + 
                                    ": " + rebirthTOut + " -> " + rebirthDestTIn +
                                    " (" + travelStart + "-" + travelEnd + ") [will be synced in Pass 2]");
                    }
                }
            } else {
                // Truly consumed - no rebirth
                segments.add(new AnimationSegment(
                    tokenId, version, Phase.CONSUMED,
                    bufferEnd, endTime + 10000,
                    destPlace, null, null,
                    null, null, null, null
                ));
            }
            
            logger.debug("Generated child approach to JOIN: " + tokenId + 
                        " atEG@" + atEventGenStart + " travel@" + travelToTInStart + 
                        " buffer@" + bufferStart + "-" + bufferEnd + " (consumed)");
        }
    }
    
    /**
     * Backward compatible version without tokenEvents - assumes all consumed
     */
    private void generateChildApproachToJoin(String tokenId, String version,
                                              MarkingEvent firstEnterOrBuffered,
                                              MarkingEvent generatedEvent,
                                              MarkingEvent forkEvent) {
        generateChildApproachToJoin(tokenId, version, firstEnterOrBuffered, generatedEvent, forkEvent, new ArrayList<>());
    }
    
    /**
     * Generate segments for token exiting a place
     */
    private void generateExitSegments(String tokenId, String version,
                                      MarkingEvent exitEvent, List<MarkingEvent> tokenEvents, int exitIndex) {
        String fromPlace = exitEvent.placeId;
        String tOut = placeToTOut.get(fromPlace);
        
        // Check if this is a FORK point (T_out has multiple destinations)
        boolean isForkPoint = tOut != null && tOutToNextTIns.containsKey(tOut) && 
                              tOutToNextTIns.get(tOut).size() > 1;
        
        // Check for terminate route first
        boolean isTerminating = exitEvent.isTerminate() || 
                                "TERMINATE".equals(exitEvent.toPlace) ||
                                (exitEvent.toPlace != null && exitEvent.toPlace.toUpperCase().contains("TERMINATE"));
        
        // If this is a parent token at a FORK point and NOT terminating,
        // the parent should be hidden (consumed) - children take over
        // Don't generate travel segments for the parent
        if (isForkPoint && !isChildToken(tokenId) && !isTerminating) {
            // Check if this token actually forked (has children in the events)
            boolean hasChildren = false;
            for (MarkingEvent e : events) {
                if (e.isFork() && getParentTokenId(e.tokenId) != null && 
                    getParentTokenId(e.tokenId).equals(tokenId)) {
                    hasChildren = true;
                    break;
                }
            }
            
            if (hasChildren) {
                // Parent is consumed at fork - don't generate travel segments
                // Parent will reappear after JOIN (handled by ENTER event at rejoin place)
                return;
            }
        }
        
        // Find next ENTER or BUFFERED event for this token
        MarkingEvent nextEvent = null;
        for (int i = exitIndex + 1; i < tokenEvents.size(); i++) {
            MarkingEvent e = tokenEvents.get(i);
            if (e.isEnter() || e.isBuffered()) {
                nextEvent = e;
                break;
            }
            if (e.isJoinConsumed() || e.isTerminate()) {
                nextEvent = e;
                break;
            }
        }
        
        // Also check: if the EXIT has a specific toPlace that's NOT terminate, don't route to terminate
        if (!isTerminating && exitEvent.toPlace != null && !exitEvent.toPlace.isEmpty()) {
            String resolvedDest = resolveToServiceName(exitEvent.toPlace);
            if (placeIds.contains(resolvedDest)) {
                // EXIT event specifies a real place destination, not terminate
                isTerminating = false;
            }
        }
        
        if (isTerminating) {
            String terminateNode = tOutToTerminate.get(tOut);
            if (terminateNode == null) {
                // Try to find any terminate node
                terminateNode = terminateIds.isEmpty() ? "TERMINATE" : terminateIds.iterator().next();
            }
            
            // Calculate distance-based travel duration to terminate
            long travelDurationToTerminate = calculateTravelDuration(tOut, terminateNode, FALLBACK_TRAVEL_DURATION_TO_NEXT);
            
            // TRAVELING_TO_TERMINATE
            segments.add(new AnimationSegment(
                tokenId, version, Phase.TRAVELING_TO_TERMINATE,
                exitEvent.timestamp, exitEvent.timestamp + travelDurationToTerminate,
                null, null, tOut,
                tOut, terminateNode,
                terminateNode, null
            ));
            
            // AT_TERMINATE - persist until end of animation so tokens accumulate visibly
            segments.add(new AnimationSegment(
                tokenId, version, Phase.AT_TERMINATE,
                exitEvent.timestamp + travelDurationToTerminate,
                endTime + 10000,  // Stay visible until end
                null, null, null,
                null, null, terminateNode, null
            ));
            
            return;
        }
        
        // If no next event, the token exits but we don't see where it goes
        // Use toPlace from the exit event if available, and show token traveling there then staying
        if (nextEvent == null) {
            if (exitEvent.toPlace != null && !exitEvent.toPlace.isEmpty()) {
                String destPlace = resolveToServiceName(exitEvent.toPlace);
                String destTIn = placeToTIn.get(destPlace);
                
                // Calculate distance-based travel duration
                long travelDurationToNext = calculateTravelDuration(tOut, destTIn, FALLBACK_TRAVEL_DURATION_TO_NEXT);
                
                // TRAVELING_TO_NEXT_TIN
                segments.add(new AnimationSegment(
                    tokenId, version, Phase.TRAVELING_TO_NEXT_TIN,
                    exitEvent.timestamp, exitEvent.timestamp + travelDurationToNext,
                    destPlace, destTIn, tOut,
                    tOut, destTIn,
                    null, null
                ));
                
                // Token stays at T_in (or place if we can determine it) until end
                if (destTIn != null) {
                    segments.add(new AnimationSegment(
                        tokenId, version, Phase.BUFFERED_AT_TIN,
                        exitEvent.timestamp + travelDurationToNext,
                        endTime + 10000,
                        destPlace, destTIn, null,
                        null, null, null, null
                    ));
                } else {
                    // No T_in found, show at place
                    segments.add(new AnimationSegment(
                        tokenId, version, Phase.AT_PLACE,
                        exitEvent.timestamp + travelDurationToNext,
                        endTime + 10000,
                        destPlace, null, null,
                        null, null, null, null
                    ));
                }
            }
            return;
        }
        
        // Determine destination
        String destPlace = nextEvent.placeId;
        String destTIn = placeToTIn.get(destPlace);
        
        // ============================================================
        // SEMANTIC RULE: JOIN destination handling
        // ============================================================
        // If the destination T_in is a JOIN (explicit or implicit), the token
        // MUST buffer there waiting for its sibling, regardless of whether
        // the raw data has a BUFFERED event or the timing arithmetic.
        // This ensures feedback paths (e.g., P5â†’P1, P6â†’P1) show buffering.
        // ============================================================
        boolean destIsJoin = isJoin(destTIn);
        
        // Calculate distance-based travel durations
        long travelDurationToNext = calculateTravelDuration(tOut, destTIn, FALLBACK_TRAVEL_DURATION_TO_NEXT);
        long travelDurationToPlace = calculateTravelDuration(destTIn, destPlace, FALLBACK_TRAVEL_DURATION_TO_PLACE);
        
        // Calculate timing
        long travelEnd = exitEvent.timestamp + travelDurationToNext;
        long bufferEnd = nextEvent.timestamp - travelDurationToPlace;
        
        // TRAVELING_TO_NEXT_TIN
        segments.add(new AnimationSegment(
            tokenId, version, Phase.TRAVELING_TO_NEXT_TIN,
            exitEvent.timestamp, Math.min(travelEnd, nextEvent.timestamp),
            destPlace, destTIn, tOut,
            tOut, destTIn,
            null, null
        ));
        
        // Handle arrival at destination based on whether it's a JOIN
        if (destIsJoin) {
            // ============================================================
            // JOIN DESTINATION: Token MUST buffer waiting for sibling
            // ============================================================
            // At a JOIN, tokens wait at the T_in until all siblings arrive.
            // The TRAVELING_TO_PLACE happens only after the JOIN fires,
            // which is handled by the main event loop when processing
            // the ENTER event that follows the JOIN completion.
            // ============================================================
            long bufferStart = Math.min(travelEnd, nextEvent.timestamp);
            if (bufferStart < nextEvent.timestamp) {
                segments.add(new AnimationSegment(
                    tokenId, version, Phase.BUFFERED_AT_TIN,
                    bufferStart, nextEvent.timestamp,
                    destPlace, destTIn, null,
                    null, null, null, null
                ));
                
                logger.debug("Generated BUFFERED_AT_TIN for JOIN destination: " + tokenId +
                            " at " + destTIn + " (" + bufferStart + "-" + nextEvent.timestamp + ")");
            }
            // Note: TRAVELING_TO_PLACE for JOIN is handled when the JOIN fires
            // (in the main event loop processing ENTER events)
            
        } else if (!nextEvent.isBuffered() && bufferEnd > travelEnd) {
            // Non-JOIN destination with buffer time available
            segments.add(new AnimationSegment(
                tokenId, version, Phase.BUFFERED_AT_TIN,
                travelEnd, bufferEnd,
                destPlace, destTIn, null,
                null, null, null, null
            ));
            
            // TRAVELING_TO_PLACE
            segments.add(new AnimationSegment(
                tokenId, version, Phase.TRAVELING_TO_PLACE,
                bufferEnd, nextEvent.timestamp,
                destPlace, destTIn, null,
                destTIn, destPlace,
                null, null
            ));
        } else if (nextEvent.isBuffered()) {
            // Explicit BUFFERED event - travel then buffer
            if (travelEnd < nextEvent.timestamp) {
                segments.add(new AnimationSegment(
                    tokenId, version, Phase.BUFFERED_AT_TIN,
                    travelEnd, nextEvent.timestamp,
                    destPlace, destTIn, null,
                    null, null, null, null
                ));
            }
        }
    }
    
    /**
     * Find JOIN_CONSUMED event
     */
    private MarkingEvent findConsumedEvent(List<MarkingEvent> events, int startIndex) {
        for (int i = startIndex + 1; i < events.size(); i++) {
            MarkingEvent e = events.get(i);
            if (e.isJoinConsumed()) {
                return e;
            }
        }
        return null;
    }
    
    // ==================== Animation State Retrieval ====================
    
    /**
     * Get token states at a specific time.
     * This is now simple: find all active segments and convert to TokenAnimState.
     */
    public Map<String, TokenAnimState> getTokenStatesAt(long time) {
        Map<String, TokenAnimState> states = new HashMap<>();
        
        for (AnimationSegment segment : segments) {
            if (segment.isActiveAt(time) && segment.phase != Phase.CONSUMED) {
                // Only keep the latest segment for each token
                TokenAnimState state = states.get(segment.tokenId);
                if (state == null || shouldReplaceState(state, segment)) {
                    state = convertSegmentToState(segment, time);
                    if (state != null && state.phase != null) {
                        states.put(segment.tokenId, state);
                    }
                }
            }
        }
        
        return states;
    }
    
    /**
     * Determine if new segment should replace existing state
     */
    private boolean shouldReplaceState(TokenAnimState existing, AnimationSegment newSegment) {
        // Prefer non-consumed states
        if (existing.phase == Phase.CONSUMED) return true;
        // Prefer later start times
        return newSegment.startTime > existing.phaseStartTime;
    }
    
    /**
     * Convert an animation segment to a TokenAnimState
     */
    private TokenAnimState convertSegmentToState(AnimationSegment segment, long time) {
        TokenAnimState state = new TokenAnimState(segment.tokenId, segment.version);
        
        state.phase = segment.phase;
        state.phaseStartTime = segment.startTime;
        state.phaseEndTime = segment.endTime;
        state.progress = segment.getProgress(time);
        state.currentPlaceId = segment.placeId;
        state.tInId = segment.tInId;
        state.tOutId = segment.tOutId;
        state.nextTInId = segment.tInId; // For Canvas compatibility
        state.terminateNodeId = segment.terminateId;
        state.eventGenId = segment.eventGenId;
        state.fromElementId = segment.fromElement;  // For travel interpolation
        state.toElementId = segment.toElement;      // For travel interpolation
        
        return state;
    }
    
    /**
     * Get animation snapshot at a specific time
     */
    public AnimationSnapshot getAnimationSnapshotAt(long time) {
        Map<String, TokenAnimState> tokenStates = getTokenStatesAt(time);
        Map<String, List<BufferedToken>> bufferStates = new HashMap<>();
        
        // Initialize buffers
        for (String tInId : tInIds) {
            bufferStates.put(tInId, new ArrayList<>());
        }
        
        // Add buffered tokens
        for (TokenAnimState state : tokenStates.values()) {
            if (state.phase == Phase.BUFFERED_AT_TIN && state.tInId != null) {
                List<BufferedToken> buffer = bufferStates.get(state.tInId);
                if (buffer == null) {
                    buffer = new ArrayList<>();
                    bufferStates.put(state.tInId, buffer);
                }
                buffer.add(new BufferedToken(state.tokenId, state.version, state.phaseStartTime));
            }
        }
        
        // Sort buffers by arrival time
        for (List<BufferedToken> buffer : bufferStates.values()) {
            buffer.sort(Comparator.comparingLong(t -> t.arrivalTime));
        }
        
        return new AnimationSnapshot(tokenStates, bufferStates);
    }
    
    // ==================== Utility Methods ====================
    
    private String getVersionFromTokenId(String tokenId) {
        try {
            long id = Long.parseLong(tokenId);
            int versionNum = (int)(id / 1000000);
            return String.format("v%03d", versionNum);
        } catch (NumberFormatException e) {
            return "v001";
        }
    }
    
    private static boolean isChildToken(String tokenId) {
        try {
            int id = Integer.parseInt(tokenId);
            int suffix = id % 100;
            return suffix >= 1 && suffix <= 99;
        } catch (NumberFormatException e) {
            return false;
        }
    }
    
    private static String getParentTokenId(String childTokenId) {
        try {
            int id = Integer.parseInt(childTokenId);
            int parentId = (id / 100) * 100;
            return String.valueOf(parentId);
        } catch (NumberFormatException e) {
            return null;
        }
    }
    
    // ==================== Topology Getters ====================
    
    public String getTInForPlace(String placeId) { return placeToTIn.get(placeId); }
    public String getTOutForPlace(String placeId) { return placeToTOut.get(placeId); }
    public String getNextTInForTOut(String tOutId) { return tOutToNextTIn.get(tOutId); }
    public List<String> getAllNextTInsForTOut(String tOutId) {
        return tOutToNextTIns.getOrDefault(tOutId, Collections.emptyList());
    }
    public List<String> getTOutsForTIn(String tInId) {
        return tInFromTOuts.getOrDefault(tInId, Collections.emptyList());
    }
    public boolean isFork(String tOutId) {
        List<String> targets = tOutToNextTIns.get(tOutId);
        return targets != null && targets.size() > 1;
    }
    public boolean isJoin(String tInId) {
        // Check explicit joins (T_in marked as JoinNode in workflow definition)
        if (explicitJoinTIns.contains(tInId)) {
            return true;
        }
        // Check implicit joins (event generator with fork_behavior targets this T_in)
        return implicitJoinTIns.contains(tInId);
    }
    public boolean leadsToTerminate(String tOutId) { return tOutToTerminate.containsKey(tOutId); }
    public String getTerminateForTOut(String tOutId) { return tOutToTerminate.get(tOutId); }
    public boolean isTerminateNode(String nodeId) { return terminateIds.contains(nodeId); }
    public Point getTerminatePosition(String terminateNodeId) { return terminatePositions.get(terminateNodeId); }
    public Set<String> getTerminateNodeIds() { return new HashSet<>(terminateIds); }
    public Map<String, Point> getTerminatePositions() { return new HashMap<>(terminatePositions); }
    public String getPlaceForTIn(String tInId) { return tInToPlace.get(tInId); }
    public Set<String> getEventGeneratorIds() { return new HashSet<>(eventGeneratorIds); }
    public String getTInForEventGenerator(String eventGenId) { return eventGenToTIn.get(eventGenId); }
    
    // Implicit fork/join getters (for event generators with fork_behavior)
    public boolean isImplicitFork(String eventGenId) { return eventGenImplicitForkToJoin.containsKey(eventGenId); }
    public String getImplicitJoinTarget(String eventGenId) { return eventGenImplicitForkToJoin.get(eventGenId); }
    public int getImplicitForkChildCount(String eventGenId) { 
        return eventGenForkChildCount.getOrDefault(eventGenId, 0); 
    }
    public boolean isImplicitJoin(String tInId) { return implicitJoinTIns.contains(tInId); }
    public boolean isExplicitJoin(String tInId) { return explicitJoinTIns.contains(tInId); }
    public String getEventGenForImplicitJoin(String tInId) {
        for (Map.Entry<String, String> entry : eventGenImplicitForkToJoin.entrySet()) {
            if (tInId.equals(entry.getValue())) {
                return entry.getKey();
            }
        }
        return null;
    }
    
    public long getStartTime() { return startTime; }
    public long getEndTime() { return endTime; }
    public long getDuration() { return endTime - startTime; }
    public List<MarkingEvent> getEvents() { return Collections.unmodifiableList(events); }
    public Set<String> getPlaceIds() { return new HashSet<>(placeIds); }
    public Set<String> getTInIds() { return new HashSet<>(tInIds); }
    public Color getVersionColor(String version) { return versionColors.getOrDefault(version, Color.GRAY); }
    public boolean hasData() { return !segments.isEmpty(); }
    public boolean hasTopology() { return !placeToTIn.isEmpty(); }
    
    public Set<String> getVersions() {
        Set<String> versions = new HashSet<>();
        for (AnimationSegment seg : segments) {
            versions.add(seg.version);
        }
        return versions;
    }
    
    public void clear() {
        events.clear();
        segments.clear();
        startTime = Long.MAX_VALUE;
        endTime = Long.MIN_VALUE;
    }
    
    /**
     * Convenience method for backward compatibility
     */
    public void parse(String text) {
        parseOutput(text);
    }
    
    /**
     * Backward compatibility: alias for parseOutput
     */
    public void parseAnalyzerOutput(String text) {
        parseOutput(text);
    }
    
    /**
     * Backward compatibility: get buffer states separately
     * In the refactored version, this extracts buffer info from segments
     */
    public Map<String, List<BufferedToken>> getBufferStatesAt(long time) {
        Map<String, List<BufferedToken>> bufferStates = new HashMap<>();
        
        // Initialize all known T_in buffers
        for (String tInId : tInIds) {
            bufferStates.put(tInId, new ArrayList<>());
        }
        
        // Find all tokens in BUFFERED_AT_TIN phase at this time
        for (AnimationSegment segment : segments) {
            if (segment.isActiveAt(time) && segment.phase == Phase.BUFFERED_AT_TIN) {
                String tInId = segment.tInId;
                if (tInId != null) {
                    List<BufferedToken> buffer = bufferStates.get(tInId);
                    if (buffer == null) {
                        buffer = new ArrayList<>();
                        bufferStates.put(tInId, buffer);
                    }
                    buffer.add(new BufferedToken(segment.tokenId, segment.version, segment.startTime));
                }
            }
        }
        
        // Sort each buffer by arrival time (FIFO)
        for (List<BufferedToken> buffer : bufferStates.values()) {
            buffer.sort(Comparator.comparingLong(t -> t.arrivalTime));
        }
        
        return bufferStates;
    }
    
    /**
     * Debug: print all segments
     */
    public void printSegments() {
        logger.debug("=== All Animation Segments ===");
        for (AnimationSegment seg : segments) {
            logger.debug(seg.toString());
        }
        logger.debug("=== End Segments ===");
    }
    
    /**
     * Debug: print topology
     */
    public void printTopology() {
        logger.info("=== TokenAnimator Topology ===");
        logger.info("Event Generators: " + eventGeneratorIds);
        logger.info("EventGen -> T_in: " + eventGenToTIn);
        logger.info("Places: " + placeIds);
        logger.info("Label -> Service: " + labelToService);
        logger.info("T_in transitions: " + tInIds);
        logger.info("Place -> T_in: " + placeToTIn);
        logger.info("Place -> T_out: " + placeToTOut);
        logger.info("T_out -> next T_in: " + tOutToNextTIn);
        logger.info("T_out -> all T_ins (fork): " + tOutToNextTIns);
        logger.info("T_in <- all T_outs (join): " + tInFromTOuts);
        logger.info("T_in -> Place: " + tInToPlace);
        logger.info("T_out -> Terminate: " + tOutToTerminate);
        logger.info("Terminate nodes: " + terminateIds);
        logger.info("EventGen implicit fork -> join: " + eventGenImplicitForkToJoin);
        logger.info("EventGen fork child counts: " + eventGenForkChildCount);
        logger.info("Implicit join T_ins: " + implicitJoinTIns);
        logger.info("Explicit join T_ins (JoinNode): " + explicitJoinTIns);
    }
}