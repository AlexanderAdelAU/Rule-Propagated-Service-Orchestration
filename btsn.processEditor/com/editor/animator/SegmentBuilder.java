package com.editor.animator;

import java.util.ArrayList;
import java.util.List;

import org.apache.log4j.Logger;

/**
 * SegmentBuilder - Unified segment creation with built-in rules.
 * 
 * THIS IS THE KEY CLASS that prevents bugs like missing JOIN buffers.
 * 
 * All segment creation goes through this builder, which automatically
 * enforces rules like:
 * - Travel to JOIN destination → must add buffer segment
 * - Fork children → must have travel segments
 * - Terminate → must have travel-to-terminate segment
 * 
 * By centralizing segment creation, we ensure consistency across all
 * code paths (exit handling, fork handling, rebirth handling, etc.)
 */
public class SegmentBuilder {
    
    private static final Logger logger = Logger.getLogger(SegmentBuilder.class.getName());
    
    private final PetriNetTopology topology;
    private final List<AnimationSegment> segments;
    
    // Timing constants
    public static final long TIME_AT_EVENT_GEN = 300;
    public static final long MIN_TRAVEL_DURATION = 50;
    
    public SegmentBuilder(PetriNetTopology topology, List<AnimationSegment> segments) {
        this.topology = topology;
        this.segments = segments;
    }
    
    // ==================== Simple Segment Creation ====================
    
    /**
     * Create a segment where token is at the event generator
     */
    public void atEventGenerator(String tokenId, String version, String eventGenId,
                                  String destTIn, long startTime, long endTime) {
        segments.add(new AnimationSegment(
            tokenId, version, Phase.AT_EVENT_GENERATOR,
            startTime, endTime,
            null, destTIn, null,
            null, null, null, eventGenId
        ));
    }
    
    /**
     * Create a segment where token is traveling from event generator to T_in
     */
    public void travelingToTIn(String tokenId, String version, String eventGenId,
                               String destTIn, String destPlace,
                               long startTime, long endTime) {
        segments.add(new AnimationSegment(
            tokenId, version, Phase.TRAVELING_TO_TIN,
            startTime, endTime,
            destPlace, destTIn, null,
            eventGenId, destTIn,
            null, eventGenId
        ));
    }
    
    /**
     * Create a segment where token is buffered at T_in
     */
    public void bufferedAtTIn(String tokenId, String version, String destPlace,
                              String tInId, long startTime, long endTime) {
        if (endTime <= startTime) return; // No zero-duration buffers
        
        segments.add(new AnimationSegment(
            tokenId, version, Phase.BUFFERED_AT_TIN,
            startTime, endTime,
            destPlace, tInId, null,
            null, null, null, null
        ));
    }
    
    /**
     * Create a segment where token is traveling from T_in into place
     */
    public void travelingToPlace(String tokenId, String version, String destPlace,
                                  String tInId, long startTime, long endTime) {
        segments.add(new AnimationSegment(
            tokenId, version, Phase.TRAVELING_TO_PLACE,
            startTime, endTime,
            destPlace, tInId, null,
            tInId, destPlace,
            null, null
        ));
    }
    
    /**
     * Create a segment where token is at a place
     */
    public void atPlace(String tokenId, String version, String placeId,
                        long startTime, long endTime) {
        String tInId = topology.getTInForPlace(placeId);
        String tOutId = topology.getTOutForPlace(placeId);
        
        segments.add(new AnimationSegment(
            tokenId, version, Phase.AT_PLACE,
            startTime, endTime,
            placeId, tInId, tOutId,
            null, null, null, null
        ));
    }
    
    /**
     * Create a segment where token is traveling from place to T_out
     */
    public void travelingToTOut(String tokenId, String version, String placeId,
                                 long startTime, long endTime) {
        String tInId = topology.getTInForPlace(placeId);
        String tOutId = topology.getTOutForPlace(placeId);
        
        segments.add(new AnimationSegment(
            tokenId, version, Phase.TRAVELING_TO_TOUT,
            startTime, endTime,
            placeId, tInId, tOutId,
            placeId, tOutId,
            null, null
        ));
    }
    
    /**
     * Create a segment where token is consumed (invisible)
     */
    public void consumed(String tokenId, String version, String placeId,
                         long startTime, long endTime) {
        segments.add(new AnimationSegment(
            tokenId, version, Phase.CONSUMED,
            startTime, endTime,
            placeId, null, null,
            null, null, null, null
        ));
    }
    
    /**
     * Create a segment where token is traveling to terminate
     */
    public void travelingToTerminate(String tokenId, String version, String tOutId,
                                      String terminateId, long startTime, long endTime) {
        segments.add(new AnimationSegment(
            tokenId, version, Phase.TRAVELING_TO_TERMINATE,
            startTime, endTime,
            null, null, tOutId,
            tOutId, terminateId,
            terminateId, null
        ));
    }
    
    /**
     * Create a segment where token is at terminate (workflow complete)
     */
    public void atTerminate(String tokenId, String version, String terminateId,
                            long startTime, long endTime) {
        segments.add(new AnimationSegment(
            tokenId, version, Phase.AT_TERMINATE,
            startTime, endTime,
            null, null, null,
            null, null, terminateId, null
        ));
    }
    
    // ==================== Smart Segment Creation (with rules) ====================
    
    /**
     * SMART: Travel from T_out to next T_in, with automatic JOIN handling.
     * 
     * This is the KEY method that prevents the missing buffer bug.
     * If destination is a JOIN, it automatically creates a buffer segment.
     * 
     * @param tokenId Token ID
     * @param version Token version
     * @param fromTOut Source T_out
     * @param destTIn Destination T_in
     * @param destPlace Destination place
     * @param travelStart When travel starts
     * @param arrivalTime When token arrives at destination (ENTER/BUFFERED event time)
     */
    public void travelToNextTIn(String tokenId, String version, 
                                 String fromTOut, String destTIn, String destPlace,
                                 long travelStart, long arrivalTime) {
        
        // Calculate travel duration
        long travelDuration = topology.calculateTravelDuration(
            fromTOut, destTIn, PetriNetTopology.FALLBACK_TRAVEL_TO_NEXT);
        long travelEnd = Math.min(travelStart + travelDuration, arrivalTime);
        
        // TRAVELING_TO_NEXT_TIN segment
        segments.add(new AnimationSegment(
            tokenId, version, Phase.TRAVELING_TO_NEXT_TIN,
            travelStart, travelEnd,
            destPlace, destTIn, fromTOut,
            fromTOut, destTIn,
            null, null
        ));
        
        // ============================================================
        // RULE: If destination is a JOIN, token MUST buffer
        // ============================================================
        // This is the central enforcement point that prevents the bug.
        // No matter what code path calls this method, JOIN semantics
        // are automatically enforced.
        // ============================================================
        
        boolean destIsJoin = topology.isJoin(destTIn);
        
        if (destIsJoin) {
            // JOIN destination - buffer until arrival event
            if (travelEnd < arrivalTime) {
                bufferedAtTIn(tokenId, version, destPlace, destTIn, travelEnd, arrivalTime);
                logger.debug("RULE: Added buffer at JOIN " + destTIn + " for " + tokenId +
                            " (" + travelEnd + "-" + arrivalTime + ")");
            }
            // Note: TRAVELING_TO_PLACE is NOT added here.
            // For JOINs, that happens when the JOIN fires (handled by caller).
            
        } else {
            // Non-JOIN destination - add buffer if time allows, then travel to place
            long travelToPlaceDuration = topology.calculateTravelDuration(
                destTIn, destPlace, PetriNetTopology.FALLBACK_TRAVEL_TO_PLACE);
            long bufferEnd = arrivalTime - travelToPlaceDuration;
            
            if (bufferEnd > travelEnd) {
                // There's buffer time
                bufferedAtTIn(tokenId, version, destPlace, destTIn, travelEnd, bufferEnd);
                travelingToPlace(tokenId, version, destPlace, destTIn, bufferEnd, arrivalTime);
            } else {
                // Tight timing - just travel to place
                if (travelEnd < arrivalTime) {
                    travelingToPlace(tokenId, version, destPlace, destTIn, travelEnd, arrivalTime);
                }
            }
        }
    }
    
    /**
     * SMART: Handle token exiting a place.
     * 
     * Automatically handles:
     * - FORK: parent consumed, no travel segments
     * - TERMINATE: travel to terminate node
     * - Normal exit: travel to next T_in with JOIN handling
     * 
     * @param tokenId Token ID
     * @param version Token version  
     * @param fromPlace Place being exited
     * @param exitTime When exit occurs
     * @param destPlace Destination place (from EXIT event toPlace)
     * @param arrivalTime When token arrives at destination
     * @param isParentAtFork True if this is a parent token at a fork point
     * @param isTerminating True if going to terminate
     */
    public void exitPlace(String tokenId, String version, String fromPlace,
                          long exitTime, String destPlace, long arrivalTime,
                          boolean isParentAtFork, boolean isTerminating) {
        
        String tOutId = topology.getTOutForPlace(fromPlace);
        
        if (isParentAtFork) {
            // Parent consumed at fork - no travel segments
            // Children will handle the actual movement
            return;
        }
        
        if (isTerminating) {
            String terminateId = topology.getTerminateForTOut(tOutId);
            if (terminateId == null) {
                terminateId = topology.getTerminateIds().isEmpty() ? 
                    "TERMINATE" : topology.getTerminateIds().iterator().next();
            }
            
            long travelDuration = topology.calculateTravelDuration(
                tOutId, terminateId, PetriNetTopology.FALLBACK_TRAVEL_TO_NEXT);
            
            travelingToTerminate(tokenId, version, tOutId, terminateId, 
                exitTime, exitTime + travelDuration);
            atTerminate(tokenId, version, terminateId, 
                exitTime + travelDuration, Long.MAX_VALUE);
            return;
        }
        
        // Normal exit - travel to next T_in
        if (destPlace != null) {
            destPlace = topology.resolveToServiceName(destPlace);
            String destTIn = topology.getTInForPlace(destPlace);
            
            if (destTIn != null) {
                travelToNextTIn(tokenId, version, tOutId, destTIn, destPlace,
                    exitTime, arrivalTime);
            }
        }
    }
    
    /**
     * SMART: Full approach from event generator to place.
     * 
     * Creates the complete sequence:
     * AT_EVENT_GENERATOR → TRAVELING_TO_TIN → BUFFERED_AT_TIN → TRAVELING_TO_PLACE
     * 
     * With JOIN handling built in.
     */
    public void approachFromEventGenerator(String tokenId, String version,
                                            String eventGenId, String destPlace,
                                            long generatedTime, long enterTime) {
        
        String destTIn = topology.getTInForPlace(destPlace);
        if (destTIn == null) {
            destTIn = topology.getTInForEventGenerator(eventGenId);
        }
        
        // Calculate durations
        long travelToTInDuration = topology.calculateTravelDuration(
            eventGenId, destTIn, PetriNetTopology.FALLBACK_TRAVEL_EG_TO_TIN);
        long travelToPlaceDuration = topology.calculateTravelDuration(
            destTIn, destPlace, PetriNetTopology.FALLBACK_TRAVEL_TO_PLACE);
        
        // Allocate time
        long availableTime = enterTime - generatedTime;
        long minRequired = TIME_AT_EVENT_GEN + travelToTInDuration + travelToPlaceDuration + MIN_TRAVEL_DURATION;
        
        long atEgDuration = TIME_AT_EVENT_GEN;
        long bufferDuration;
        
        if (availableTime > minRequired) {
            bufferDuration = availableTime - atEgDuration - travelToTInDuration - travelToPlaceDuration;
        } else {
            double scale = (double) availableTime / minRequired;
            atEgDuration = Math.max(MIN_TRAVEL_DURATION, (long)(atEgDuration * scale));
            travelToTInDuration = Math.max(MIN_TRAVEL_DURATION, (long)(travelToTInDuration * scale));
            travelToPlaceDuration = Math.max(MIN_TRAVEL_DURATION, (long)(travelToPlaceDuration * scale));
            bufferDuration = Math.max(MIN_TRAVEL_DURATION / 2, 
                availableTime - atEgDuration - travelToTInDuration - travelToPlaceDuration);
        }
        
        // Calculate timestamps
        long atEgStart = generatedTime;
        long travelToTInStart = atEgStart + atEgDuration;
        long bufferStart = travelToTInStart + travelToTInDuration;
        long travelToPlaceStart = bufferStart + bufferDuration;
        
        // Create segments
        atEventGenerator(tokenId, version, eventGenId, destTIn, atEgStart, travelToTInStart);
        travelingToTIn(tokenId, version, eventGenId, destTIn, destPlace, travelToTInStart, bufferStart);
        
        // Buffer and travel-to-place depend on whether dest is JOIN
        boolean destIsJoin = topology.isJoin(destTIn);
        
        if (destIsJoin) {
            // JOIN: buffer until enter time, no travel-to-place yet
            bufferedAtTIn(tokenId, version, destPlace, destTIn, bufferStart, enterTime);
        } else {
            // Normal: buffer then travel to place
            bufferedAtTIn(tokenId, version, destPlace, destTIn, bufferStart, travelToPlaceStart);
            travelingToPlace(tokenId, version, destPlace, destTIn, travelToPlaceStart, enterTime);
        }
    }
    
    // ==================== Utility ====================
    
    /**
     * Add a raw segment (for cases not covered by smart methods)
     */
    public void addRaw(AnimationSegment segment) {
        segments.add(segment);
    }
    
    /**
     * Get current segment count
     */
    public int getSegmentCount() {
        return segments.size();
    }
}
