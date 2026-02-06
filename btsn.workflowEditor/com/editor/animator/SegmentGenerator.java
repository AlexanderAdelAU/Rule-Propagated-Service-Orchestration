package com.editor.animator;

import java.util.*;

import org.apache.log4j.Logger;

/**
 * SegmentGenerator - Orchestrates animation segment generation.
 * 
 * Processes events and uses SegmentBuilder to create segments.
 * The SegmentBuilder enforces rules (like JOIN buffering),
 * while this class handles the logic of walking through events.
 */
public class SegmentGenerator {
    
    private static final Logger logger = Logger.getLogger(SegmentGenerator.class.getName());
    
    private final PetriNetTopology topology;
    private final List<AnimationSegment> segments;
    private final SegmentBuilder builder;
    
    // Fork tracking
    private final Map<String, String> childToParent = new HashMap<>();
    private final Map<String, Long> forkTimes = new HashMap<>();
    private final Map<String, String> childBirthTOut = new HashMap<>();
    
    // Time bounds
    private long endTime = Long.MIN_VALUE;
    
    public SegmentGenerator(PetriNetTopology topology) {
        this.topology = topology;
        this.segments = new ArrayList<>();
        this.builder = new SegmentBuilder(topology, segments);
    }
    
    /**
     * Generate segments from events.
     * 
     * @param events Parsed and sorted events
     * @param endTime Animation end time
     * @return Generated segments
     */
    public List<AnimationSegment> generate(List<MarkingEvent> events, long endTime) {
        this.endTime = endTime;
        segments.clear();
        childToParent.clear();
        forkTimes.clear();
        childBirthTOut.clear();
        
        // Build fork relationships
        buildForkRelationships(events);
        
        // Group events by token
        Map<String, List<MarkingEvent>> eventsByToken = EventParser.groupByToken(events);
        
        // Generate segments for each token
        for (Map.Entry<String, List<MarkingEvent>> entry : eventsByToken.entrySet()) {
            generateForToken(entry.getKey(), entry.getValue());
        }
        
        logger.info("Generated " + segments.size() + " segments");
        return segments;
    }
    
    /**
     * Get child birth T_out map (for synchronizer)
     */
    public Map<String, String> getChildBirthTOut() {
        return childBirthTOut;
    }
    
    private void buildForkRelationships(List<MarkingEvent> events) {
        for (MarkingEvent event : events) {
            if (event.isFork()) {
                String parentId = getParentTokenId(event.tokenId);
                if (parentId != null) {
                    childToParent.put(event.tokenId, parentId);
                    forkTimes.put(event.tokenId, event.timestamp);
                    
                    // Track birth T_out
                    String birthTOut = null;
                    if (event.placeId != null) {
                        birthTOut = topology.getTOutForPlace(event.placeId);
                        if (birthTOut == null) {
                            String resolved = topology.resolveToServiceName(event.placeId);
                            birthTOut = topology.getTOutForPlace(resolved);
                        }
                    }
                    
                    if (birthTOut != null) {
                        childBirthTOut.put(event.tokenId, birthTOut);
                    }
                    
                    logger.debug("FORK: Child " + event.tokenId + " born at " + birthTOut + 
                        " (parent=" + parentId + ", time=" + event.timestamp + ")");
                }
            }
        }
    }
    
    private void generateForToken(String tokenId, List<MarkingEvent> tokenEvents) {
        if (tokenEvents.isEmpty()) return;
        
        String version = tokenEvents.get(0).version;
        boolean isChild = isChildToken(tokenId);
        
        // Find key events
        MarkingEvent generatedEvent = findFirst(tokenEvents, MarkingEvent::isGenerated);
        MarkingEvent forkEvent = findFirst(tokenEvents, MarkingEvent::isFork);
        MarkingEvent firstEnterOrBuffered = findFirst(tokenEvents, 
            e -> e.isEnter() || e.isBuffered());
        
        // Generate approach segments
        generateApproachSegments(tokenId, version, isChild, tokenEvents,
            generatedEvent, forkEvent, firstEnterOrBuffered);
        
        // Process events for in-place movement
        processEvents(tokenId, version, isChild, tokenEvents, 
            generatedEvent, forkEvent, firstEnterOrBuffered);
    }
    
    private void generateApproachSegments(String tokenId, String version, boolean isChild,
                                           List<MarkingEvent> tokenEvents,
                                           MarkingEvent generatedEvent, MarkingEvent forkEvent,
                                           MarkingEvent firstEnterOrBuffered) {
        
        if (firstEnterOrBuffered == null) return;
        
        // Check for fork-before-enter pattern (child spawned by FORK)
        boolean forkBeforeFirstEnter = forkEvent != null && firstEnterOrBuffered != null &&
            forkEvent.timestamp < firstEnterOrBuffered.timestamp;
        
        if (isChild && forkEvent != null && forkBeforeFirstEnter) {
            // Child born from FORK - approach from fork point
            generateForkChildApproach(tokenId, version, forkEvent, tokenEvents, firstEnterOrBuffered);
            
        } else if (generatedEvent == null && !isChild) {
            // Reconstituted parent - appears at place when JOIN completes
            generateReconstitutedParentApproach(tokenId, version, tokenEvents, 
                forkEvent, firstEnterOrBuffered);
            
        } else if (isChild && generatedEvent != null) {
            // Child arriving at JOIN from event generator
            generateChildAtJoinApproach(tokenId, version, tokenEvents,
                generatedEvent, forkEvent, firstEnterOrBuffered);
            
        } else if (generatedEvent != null) {
            // Regular token from event generator
            generateRegularApproach(tokenId, version, generatedEvent, firstEnterOrBuffered);
        }
    }
    
    private void generateForkChildApproach(String tokenId, String version,
                                            MarkingEvent forkEvent, List<MarkingEvent> tokenEvents,
                                            MarkingEvent firstEnterOrBuffered) {
        // Find actual destination from EXIT event
        String destPlace = null;
        for (MarkingEvent e : tokenEvents) {
            if (e.isExit() && e.toPlace != null && !e.toPlace.isEmpty()) {
                destPlace = e.toPlace;
                break;
            }
        }
        if (destPlace == null && firstEnterOrBuffered != null) {
            destPlace = firstEnterOrBuffered.placeId;
        }
        if (destPlace == null) {
            destPlace = forkEvent.toPlace;
        }
        if (destPlace == null) return;
        
        destPlace = topology.resolveToServiceName(destPlace);
        String forkTOut = topology.getTOutForPlace(forkEvent.placeId);
        String destTIn = topology.getTInForPlace(destPlace);
        
        if (forkTOut == null || destTIn == null) return;
        
        long arrivalTime = firstEnterOrBuffered != null ? 
            firstEnterOrBuffered.timestamp : forkEvent.timestamp + 500;
        
        builder.travelToNextTIn(tokenId, version, forkTOut, destTIn, destPlace,
            forkEvent.timestamp, arrivalTime);
    }
    
    private void generateReconstitutedParentApproach(String tokenId, String version,
                                                      List<MarkingEvent> tokenEvents,
                                                      MarkingEvent forkEvent,
                                                      MarkingEvent firstEnterOrBuffered) {
        if (forkEvent == null || firstEnterOrBuffered == null) return;
        
        MarkingEvent exitEvent = findFirst(tokenEvents, MarkingEvent::isExit);
        long exitTime = exitEvent != null ? exitEvent.timestamp : forkEvent.timestamp;
        
        // Parent appears briefly at place, then travels to T_out
        long atPlaceStart = exitTime - PetriNetTopology.FALLBACK_TRAVEL_TO_TOUT - 200;
        long atPlaceEnd = exitTime - PetriNetTopology.FALLBACK_TRAVEL_TO_TOUT;
        
        builder.atPlace(tokenId, version, firstEnterOrBuffered.placeId, atPlaceStart, atPlaceEnd);
        builder.travelingToTOut(tokenId, version, firstEnterOrBuffered.placeId, atPlaceEnd, exitTime);
    }
    
    private void generateChildAtJoinApproach(String tokenId, String version,
                                              List<MarkingEvent> tokenEvents,
                                              MarkingEvent generatedEvent, MarkingEvent forkEvent,
                                              MarkingEvent firstEnterOrBuffered) {
        String destPlace = firstEnterOrBuffered.placeId;
        String eventGenId = generatedEvent.transitionId;
        
        if (eventGenId == null || eventGenId.isEmpty()) {
            // Find event generator by T_in
            String destTIn = topology.getTInForPlace(destPlace);
            for (String egId : topology.getEventGeneratorIds()) {
                if (destTIn.equals(topology.getTInForEventGenerator(egId))) {
                    eventGenId = egId;
                    break;
                }
            }
        }
        
        // Determine if consumed or survivor
        MarkingEvent joinConsumed = findFirst(tokenEvents, 
            e -> e.isJoinConsumed() && e.placeId.equals(destPlace));
        MarkingEvent enterEvent = findFirst(tokenEvents,
            e -> e.isEnter() && e.placeId.equals(destPlace));
        
        boolean isJoinSurvivor = joinConsumed == null && enterEvent != null;
        
        // Use SegmentBuilder for approach (handles JOIN automatically)
        builder.approachFromEventGenerator(tokenId, version, eventGenId, destPlace,
            generatedEvent.timestamp, firstEnterOrBuffered.timestamp);
        
        if (isJoinSurvivor) {
            // Survivor continues into place - generate remaining segments
            generateJoinSurvivorContinuation(tokenId, version, destPlace, tokenEvents,
                enterEvent, firstEnterOrBuffered.timestamp);
        } else if (joinConsumed != null) {
            // Consumed - add CONSUMED segment, then check for rebirth
            builder.consumed(tokenId, version, destPlace, 
                joinConsumed.timestamp, findRebirthTime(tokenEvents, joinConsumed));
            
            generateRebirthSegments(tokenId, version, tokenEvents, joinConsumed);
        }
    }
    
    private void generateJoinSurvivorContinuation(String tokenId, String version,
                                                   String destPlace, List<MarkingEvent> tokenEvents,
                                                   MarkingEvent enterEvent, long bufferEnd) {
        String tInId = topology.getTInForPlace(destPlace);
        String tOutId = topology.getTOutForPlace(destPlace);
        
        // TRAVELING_TO_PLACE
        long travelToPlaceEnd = bufferEnd + PetriNetTopology.FALLBACK_TRAVEL_TO_PLACE;
        builder.travelingToPlace(tokenId, version, destPlace, tInId, bufferEnd, travelToPlaceEnd);
        
        // Find exit
        MarkingEvent exitEvent = findFirst(tokenEvents, 
            e -> e.isExit() && e.placeId.equals(destPlace));
        MarkingEvent terminateEvent = findFirst(tokenEvents, MarkingEvent::isTerminate);
        
        // AT_PLACE duration
        long atPlaceStart = travelToPlaceEnd;
        long atPlaceEnd;
        
        if (exitEvent != null) {
            atPlaceEnd = exitEvent.timestamp - PetriNetTopology.FALLBACK_TRAVEL_TO_TOUT;
        } else if (terminateEvent != null) {
            atPlaceEnd = terminateEvent.timestamp - PetriNetTopology.FALLBACK_TRAVEL_TO_TOUT;
        } else {
            atPlaceEnd = atPlaceStart + 200;
        }
        
        if (atPlaceEnd > atPlaceStart) {
            builder.atPlace(tokenId, version, destPlace, atPlaceStart, atPlaceEnd);
        }
        
        // TRAVELING_TO_TOUT and exit handling
        if (exitEvent != null || terminateEvent != null) {
            long travelToToutEnd = Math.max(atPlaceEnd, atPlaceStart) + PetriNetTopology.FALLBACK_TRAVEL_TO_TOUT;
            builder.travelingToTOut(tokenId, version, destPlace, atPlaceEnd, travelToToutEnd);
            
            if (terminateEvent != null || topology.leadsToTerminate(tOutId)) {
                String terminateId = topology.getTerminateForTOut(tOutId);
                if (terminateId == null) terminateId = "TERMINATE";
                
                builder.travelingToTerminate(tokenId, version, tOutId, terminateId,
                    travelToToutEnd, travelToToutEnd + PetriNetTopology.FALLBACK_TRAVEL_TO_NEXT);
                builder.atTerminate(tokenId, version, terminateId,
                    travelToToutEnd + PetriNetTopology.FALLBACK_TRAVEL_TO_NEXT, endTime + 10000);
            } else if (exitEvent != null && exitEvent.toPlace != null) {
                // Travel to next place
                String nextPlace = topology.resolveToServiceName(exitEvent.toPlace);
                String nextTIn = topology.getTInForPlace(nextPlace);
                
                if (nextTIn != null) {
                    // Find arrival time
                    long arrivalTime = travelToToutEnd + PetriNetTopology.FALLBACK_TRAVEL_TO_NEXT;
                    for (MarkingEvent e : tokenEvents) {
                        if ((e.isBuffered() || e.isEnter()) && 
                            nextPlace.equals(e.placeId) && e.timestamp > travelToToutEnd) {
                            arrivalTime = e.timestamp;
                            break;
                        }
                    }
                    
                    builder.travelToNextTIn(tokenId, version, tOutId, nextTIn, nextPlace,
                        travelToToutEnd, arrivalTime);
                }
            }
        }
    }
    
    private long findRebirthTime(List<MarkingEvent> tokenEvents, MarkingEvent joinConsumed) {
        // Look for FORK event after JOIN_CONSUMED (rebirth)
        for (MarkingEvent e : tokenEvents) {
            if (e.isFork() && e.timestamp > joinConsumed.timestamp) {
                return e.timestamp;
            }
        }
        return endTime + 10000;
    }
    
    private void generateRebirthSegments(String tokenId, String version,
                                          List<MarkingEvent> tokenEvents,
                                          MarkingEvent joinConsumed) {
        // Find all FORK-EXIT pairs after this JOIN_CONSUMED
        MarkingEvent rebirthFork = null;
        MarkingEvent rebirthExit = null;
        
        for (MarkingEvent e : tokenEvents) {
            if (e.timestamp <= joinConsumed.timestamp) continue;
            
            if (e.isFork() && rebirthFork == null) {
                rebirthFork = e;
            }
            if (e.isExit() && rebirthFork != null && rebirthExit == null) {
                rebirthExit = e;
                break;
            }
        }
        
        if (rebirthFork != null && rebirthExit != null && rebirthExit.toPlace != null) {
            String destPlace = topology.resolveToServiceName(rebirthExit.toPlace);
            String forkTOut = topology.getTOutForPlace(rebirthFork.placeId);
            String destTIn = topology.getTInForPlace(destPlace);
            
            if (forkTOut != null && destTIn != null) {
                // Find arrival time
                long arrivalTime = rebirthExit.timestamp + PetriNetTopology.FALLBACK_TRAVEL_TO_NEXT;
                for (MarkingEvent e : tokenEvents) {
                    if ((e.isBuffered() || e.isEnter()) &&
                        destPlace.equals(e.placeId) && e.timestamp > rebirthExit.timestamp) {
                        arrivalTime = e.timestamp;
                        break;
                    }
                }
                
                builder.travelToNextTIn(tokenId, version, forkTOut, destTIn, destPlace,
                    rebirthExit.timestamp, arrivalTime);
                    
                logger.debug("Generated rebirth: " + tokenId + " consumed@" + joinConsumed.timestamp +
                    " reborn@" + rebirthFork.timestamp + " -> " + destTIn);
            }
        }
    }
    
    private void generateRegularApproach(String tokenId, String version,
                                          MarkingEvent generatedEvent,
                                          MarkingEvent firstEnterOrBuffered) {
        String destPlace = firstEnterOrBuffered.placeId;
        String eventGenId = generatedEvent.transitionId;
        
        if (eventGenId == null || eventGenId.isEmpty()) {
            String destTIn = topology.getTInForPlace(destPlace);
            for (String egId : topology.getEventGeneratorIds()) {
                if (destTIn.equals(topology.getTInForEventGenerator(egId))) {
                    eventGenId = egId;
                    break;
                }
            }
        }
        
        builder.approachFromEventGenerator(tokenId, version, eventGenId, destPlace,
            generatedEvent.timestamp, firstEnterOrBuffered.timestamp);
    }
    
    private void processEvents(String tokenId, String version, boolean isChild,
                               List<MarkingEvent> tokenEvents,
                               MarkingEvent generatedEvent, MarkingEvent forkEvent,
                               MarkingEvent firstEnterOrBuffered) {
        
        Set<Integer> processedIndices = new HashSet<>();
        
        // Mark already-processed events
        markProcessedApproachEvents(tokenEvents, processedIndices, isChild, 
            generatedEvent, forkEvent, firstEnterOrBuffered);
        
        // Process remaining events
        for (int i = 0; i < tokenEvents.size(); i++) {
            if (processedIndices.contains(i)) continue;
            
            MarkingEvent event = tokenEvents.get(i);
            
            if (event.isFork()) {
                processedIndices.add(i);
                continue;
            }
            
            if (event.isEnter() || event.isJoinSurvivor()) {
                processEnterEvent(tokenId, version, isChild, tokenEvents, i, event, processedIndices);
            } else if (event.isExit()) {
                processExitEvent(tokenId, version, tokenEvents, i, event, processedIndices);
            } else if (event.isBuffered()) {
                processBufferedEvent(tokenId, version, tokenEvents, i, event, processedIndices);
            } else if (event.isJoinConsumed()) {
                processJoinConsumedEvent(tokenId, version, tokenEvents, i, event, processedIndices);
            } else if (event.isTerminate()) {
                // Handle terminate
                String tOut = topology.getTOutForPlace(event.placeId);
                String terminateId = topology.getTerminateForTOut(tOut);
                if (terminateId == null) terminateId = "TERMINATE";
                
                builder.consumed(tokenId, version, null, event.timestamp + 200, endTime + 10000);
                processedIndices.add(i);
            }
        }
    }
    
    private void processEnterEvent(String tokenId, String version, boolean isChild,
                                    List<MarkingEvent> tokenEvents, int index,
                                    MarkingEvent event, Set<Integer> processedIndices) {
        
        // Check for JOIN_CONSUMED after this ENTER (child waiting at JOIN)
        MarkingEvent joinConsumed = null;
        MarkingEvent exitEvent = null;
        
        for (int j = index + 1; j < tokenEvents.size(); j++) {
            MarkingEvent e = tokenEvents.get(j);
            if (e.isJoinConsumed() && e.placeId.equals(event.placeId)) {
                joinConsumed = e;
                break;
            }
            if (e.isExit() && e.placeId.equals(event.placeId)) {
                exitEvent = e;
                break;
            }
        }
        
        if (isChild && joinConsumed != null && exitEvent == null) {
            // Child consumed at JOIN
            String tInId = topology.getTInForPlace(event.placeId);
            builder.bufferedAtTIn(tokenId, version, event.placeId, tInId,
                event.timestamp, joinConsumed.timestamp);
            builder.consumed(tokenId, version, event.placeId, 
                joinConsumed.timestamp, endTime + 10000);
            
            int joinIdx = tokenEvents.indexOf(joinConsumed);
            if (joinIdx >= 0) processedIndices.add(joinIdx);
            processedIndices.add(index);
            return;
        }
        
        // Regular enter - find exit
        if (exitEvent == null) {
            exitEvent = findNextExit(tokenEvents, index, event.placeId);
        }
        
        if (exitEvent != null) {
            int exitIdx = tokenEvents.indexOf(exitEvent);
            
            long atPlaceEnd = exitEvent.timestamp - PetriNetTopology.FALLBACK_TRAVEL_TO_TOUT;
            if (atPlaceEnd > event.timestamp) {
                builder.atPlace(tokenId, version, event.placeId, event.timestamp, atPlaceEnd);
            }
            
            builder.travelingToTOut(tokenId, version, event.placeId,
                Math.max(atPlaceEnd, event.timestamp), exitEvent.timestamp);
            
            // Handle exit destination
            processExitDestination(tokenId, version, exitEvent, tokenEvents, exitIdx);
            
            if (exitIdx >= 0) processedIndices.add(exitIdx);
        } else {
            // Token stays at place
            builder.atPlace(tokenId, version, event.placeId, event.timestamp, endTime + 10000);
        }
        
        processedIndices.add(index);
    }
    
    private void processExitEvent(String tokenId, String version,
                                   List<MarkingEvent> tokenEvents, int index,
                                   MarkingEvent event, Set<Integer> processedIndices) {
        processExitDestination(tokenId, version, event, tokenEvents, index);
        processedIndices.add(index);
    }
    
    private void processExitDestination(String tokenId, String version,
                                         MarkingEvent exitEvent, List<MarkingEvent> tokenEvents,
                                         int exitIndex) {
        String fromPlace = exitEvent.placeId;
        String tOut = topology.getTOutForPlace(fromPlace);
        
        // Check for fork
        boolean isForkPoint = topology.isFork(tOut);
        boolean isParent = !isChildToken(tokenId);
        
        // Check for terminate
        boolean isTerminating = exitEvent.isTerminate() ||
            "TERMINATE".equals(exitEvent.toPlace) ||
            (exitEvent.toPlace != null && exitEvent.toPlace.toUpperCase().contains("TERMINATE"));
        
        if (isForkPoint && isParent && !isTerminating) {
            // Parent consumed at fork
            return;
        }
        
        // Find next event
        MarkingEvent nextEvent = null;
        for (int i = exitIndex + 1; i < tokenEvents.size(); i++) {
            MarkingEvent e = tokenEvents.get(i);
            if (e.isEnter() || e.isBuffered() || e.isJoinConsumed() || e.isTerminate()) {
                nextEvent = e;
                break;
            }
        }
        
        if (isTerminating || (nextEvent == null && topology.leadsToTerminate(tOut))) {
            String terminateId = topology.getTerminateForTOut(tOut);
            if (terminateId == null) terminateId = "TERMINATE";
            
            long travelDuration = PetriNetTopology.FALLBACK_TRAVEL_TO_NEXT;
            builder.travelingToTerminate(tokenId, version, tOut, terminateId,
                exitEvent.timestamp, exitEvent.timestamp + travelDuration);
            builder.atTerminate(tokenId, version, terminateId,
                exitEvent.timestamp + travelDuration, endTime + 10000);
            return;
        }
        
        if (nextEvent == null && exitEvent.toPlace != null) {
            // Use exit event's toPlace
            String destPlace = topology.resolveToServiceName(exitEvent.toPlace);
            String destTIn = topology.getTInForPlace(destPlace);
            
            if (destTIn != null) {
                builder.travelToNextTIn(tokenId, version, tOut, destTIn, destPlace,
                    exitEvent.timestamp, exitEvent.timestamp + PetriNetTopology.FALLBACK_TRAVEL_TO_NEXT);
            }
            return;
        }
        
        if (nextEvent != null) {
            String destPlace = nextEvent.placeId;
            String destTIn = topology.getTInForPlace(destPlace);
            
            if (destTIn != null) {
                builder.travelToNextTIn(tokenId, version, tOut, destTIn, destPlace,
                    exitEvent.timestamp, nextEvent.timestamp);
            }
        }
    }
    
    private void processBufferedEvent(String tokenId, String version,
                                       List<MarkingEvent> tokenEvents, int index,
                                       MarkingEvent event, Set<Integer> processedIndices) {
        // Find when buffering ends
        MarkingEvent nextEvent = null;
        for (int j = index + 1; j < tokenEvents.size(); j++) {
            MarkingEvent e = tokenEvents.get(j);
            if (e.placeId.equals(event.placeId) &&
                (e.isJoinSurvivor() || e.isEnter() || e.isJoinConsumed())) {
                nextEvent = e;
                break;
            }
        }
        
        long bufferEnd = nextEvent != null ? nextEvent.timestamp : endTime + 10000;
        String tInId = topology.getTInForPlace(event.placeId);
        
        builder.bufferedAtTIn(tokenId, version, event.placeId, tInId,
            event.timestamp, bufferEnd);
        processedIndices.add(index);
    }
    
    private void processJoinConsumedEvent(String tokenId, String version,
                                           List<MarkingEvent> tokenEvents, int index,
                                           MarkingEvent event, Set<Integer> processedIndices) {
        // Look for rebirth
        MarkingEvent rebirthFork = null;
        MarkingEvent rebirthExit = null;
        
        for (int j = index + 1; j < tokenEvents.size(); j++) {
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
            // Token reborn
            builder.consumed(tokenId, version, event.placeId, 
                event.timestamp, rebirthFork.timestamp);
            
            String destPlace = topology.resolveToServiceName(rebirthExit.toPlace);
            String forkTOut = topology.getTOutForPlace(rebirthFork.placeId);
            String destTIn = topology.getTInForPlace(destPlace);
            
            if (forkTOut != null && destTIn != null) {
                long arrivalTime = rebirthExit.timestamp + PetriNetTopology.FALLBACK_TRAVEL_TO_NEXT;
                for (MarkingEvent e : tokenEvents) {
                    if ((e.isBuffered() || e.isEnter()) &&
                        destPlace.equals(e.placeId) && e.timestamp > rebirthExit.timestamp) {
                        arrivalTime = e.timestamp;
                        break;
                    }
                }
                
                builder.travelToNextTIn(tokenId, version, forkTOut, destTIn, destPlace,
                    rebirthExit.timestamp, arrivalTime);
                
                logger.debug("Generated rebirth: " + tokenId + " -> " + destTIn);
            }
            
            // Mark FORK and EXIT as processed
            int forkIdx = tokenEvents.indexOf(rebirthFork);
            int exitIdx = tokenEvents.indexOf(rebirthExit);
            if (forkIdx >= 0) processedIndices.add(forkIdx);
            if (exitIdx >= 0) processedIndices.add(exitIdx);
        } else {
            // Truly consumed
            builder.consumed(tokenId, version, event.placeId, event.timestamp, endTime + 10000);
        }
        
        processedIndices.add(index);
    }
    
    private void markProcessedApproachEvents(List<MarkingEvent> tokenEvents,
                                              Set<Integer> processedIndices,
                                              boolean isChild, MarkingEvent generatedEvent,
                                              MarkingEvent forkEvent, MarkingEvent firstEnterOrBuffered) {
        // Mark generated event
        if (generatedEvent != null) {
            int idx = tokenEvents.indexOf(generatedEvent);
            if (idx >= 0) processedIndices.add(idx);
        }
        
        // Mark fork event for children
        if (isChild && forkEvent != null) {
            int idx = tokenEvents.indexOf(forkEvent);
            if (idx >= 0) processedIndices.add(idx);
            
            // Also mark first EXIT after FORK
            for (int i = 0; i < tokenEvents.size(); i++) {
                MarkingEvent e = tokenEvents.get(i);
                if (e.isExit() && e.placeId.equals(forkEvent.placeId)) {
                    processedIndices.add(i);
                    break;
                }
            }
        }
        
        // Mark events at first place for children with GENERATED events
        if (isChild && generatedEvent != null && firstEnterOrBuffered != null) {
            String destPlace = firstEnterOrBuffered.placeId;
            
            for (int i = 0; i < tokenEvents.size(); i++) {
                MarkingEvent e = tokenEvents.get(i);
                if (e.placeId != null && e.placeId.equals(destPlace)) {
                    if (e.isBuffered() || e.isEnter() || e.isJoinConsumed()) {
                        processedIndices.add(i);
                        if (e.isJoinConsumed()) break;
                    }
                }
            }
        }
        
        // Mark events for reconstituted parent
        if (generatedEvent == null && !isChild && firstEnterOrBuffered != null) {
            int idx = tokenEvents.indexOf(firstEnterOrBuffered);
            if (idx >= 0) processedIndices.add(idx);
        }
    }
    
    private MarkingEvent findNextExit(List<MarkingEvent> events, int startIndex, String placeId) {
        for (int i = startIndex + 1; i < events.size(); i++) {
            MarkingEvent e = events.get(i);
            if ((e.isExit() || e.isFork() || e.isTerminate()) && e.placeId.equals(placeId)) {
                return e;
            }
        }
        return null;
    }
    
    private <T> T findFirst(List<T> list, java.util.function.Predicate<T> predicate) {
        for (T item : list) {
            if (predicate.test(item)) return item;
        }
        return null;
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
}
