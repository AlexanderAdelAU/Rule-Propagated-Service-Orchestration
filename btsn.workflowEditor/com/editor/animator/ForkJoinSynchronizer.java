package com.editor.animator;

import java.util.*;

import org.apache.log4j.Logger;

/**
 * ForkJoinSynchronizer - Synchronizes sibling tokens at fork points.
 * 
 * Pass 2 of segment generation: ensures that sibling tokens (from same FORK)
 * visually depart from the T_out at the same moment.
 * 
 * Rules:
 * 1. Siblings born together should start traveling together
 * 2. Only sync at birth point (where FORK created the child)
 * 3. Never extend CONSUMED segments
 * 4. Never extend buffers at JOINs
 */
public class ForkJoinSynchronizer {
    
    private static final Logger logger = Logger.getLogger(ForkJoinSynchronizer.class.getName());
    
    private final PetriNetTopology topology;
    
    public ForkJoinSynchronizer(PetriNetTopology topology) {
        this.topology = topology;
    }
    
    /**
     * Synchronize fork points in segments.
     * 
     * @param segments Segments to synchronize (modified in place)
     * @param childBirthTOut Map of childTokenId -> T_out where child was born
     */
    public void synchronize(List<AnimationSegment> segments, Map<String, String> childBirthTOut) {
        // Build parent-child map
        Map<String, String> tokenToParent = new HashMap<>();
        for (AnimationSegment seg : segments) {
            String parent = getParentTokenId(seg.tokenId);
            if (parent != null && !parent.equals(seg.tokenId)) {
                tokenToParent.put(seg.tokenId, parent);
            }
        }
        
        // Group TRAVELING_TO_NEXT_TIN segments by (T_out, parent)
        Map<String, List<Integer>> rawGroups = groupByForkPoint(segments, tokenToParent, childBirthTOut);
        
        // Split into sibling batches using fork arity
        Map<String, List<Integer>> siblingBatches = splitIntoBatches(segments, rawGroups);
        
        // Synchronize each batch
        int totalSyncs = 0;
        for (Map.Entry<String, List<Integer>> entry : siblingBatches.entrySet()) {
            if (entry.getValue().size() > 1) {
                totalSyncs += synchronizeBatch(segments, entry.getValue(), tokenToParent);
            }
        }
        
        // Re-sort after modifications
        segments.sort(Comparator.comparingLong(s -> s.startTime));
        
        logger.info("Pass 2 complete: synchronized " + totalSyncs + " sibling segments");
    }
    
    private Map<String, List<Integer>> groupByForkPoint(List<AnimationSegment> segments,
                                                         Map<String, String> tokenToParent,
                                                         Map<String, String> childBirthTOut) {
        Map<String, List<Integer>> groups = new HashMap<>();
        
        for (int i = 0; i < segments.size(); i++) {
            AnimationSegment seg = segments.get(i);
            if (seg.phase != Phase.TRAVELING_TO_NEXT_TIN || seg.fromElement == null) continue;
            
            // Only sync at birth point
            String birthPoint = childBirthTOut.get(seg.tokenId);
            if (birthPoint == null || !birthPoint.equals(seg.fromElement)) continue;
            
            String parent = tokenToParent.get(seg.tokenId);
            if (parent == null) continue;
            
            String groupKey = seg.fromElement + "|" + parent;
            groups.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(i);
        }
        
        return groups;
    }
    
    private Map<String, List<Integer>> splitIntoBatches(List<AnimationSegment> segments,
                                                         Map<String, List<Integer>> rawGroups) {
        Map<String, List<Integer>> batches = new HashMap<>();
        
        for (Map.Entry<String, List<Integer>> entry : rawGroups.entrySet()) {
            String tOutId = entry.getKey().split("\\|")[0];
            List<String> forkTargets = topology.getAllNextTInsForTOut(tOutId);
            int forkArity = forkTargets.size();
            
            if (forkArity <= 1) continue;
            
            List<Integer> indices = entry.getValue();
            if (indices.size() <= 1) continue;
            
            // Sort by start time
            indices.sort((a, b) -> Long.compare(segments.get(a).startTime, segments.get(b).startTime));
            
            // Split into batches of forkArity
            int batchNum = 0;
            for (int i = 0; i < indices.size(); i += forkArity) {
                List<Integer> batch = new ArrayList<>();
                for (int j = i; j < Math.min(i + forkArity, indices.size()); j++) {
                    batch.add(indices.get(j));
                }
                
                if (batch.size() > 1) {
                    batches.put(entry.getKey() + "#" + batchNum, batch);
                    logger.debug("Sibling batch " + batchNum + " at " + tOutId + 
                        " (arity=" + forkArity + "): " + batch.size() + " siblings");
                }
                batchNum++;
            }
        }
        
        return batches;
    }
    
    private int synchronizeBatch(List<AnimationSegment> segments, List<Integer> siblingIndices,
                                  Map<String, String> tokenToParent) {
        if (siblingIndices.isEmpty()) return 0;
        
        AnimationSegment firstSeg = segments.get(siblingIndices.get(0));
        String forkPointId = firstSeg.fromElement;
        String nominalParentId = tokenToParent.get(firstSeg.tokenId);
        
        // Find parent arrival time
        long parentArrival = Long.MIN_VALUE;
        String effectiveParentId = nominalParentId;
        
        if (nominalParentId != null) {
            for (AnimationSegment seg : segments) {
                if (seg.tokenId.equals(nominalParentId) &&
                    seg.phase == Phase.TRAVELING_TO_TOUT &&
                    forkPointId.equals(seg.toElement)) {
                    parentArrival = seg.endTime;
                    break;
                }
            }
        }
        
        // If no parent found, look for effective parent (JOIN_SURVIVOR pattern)
        if (parentArrival == Long.MIN_VALUE) {
            for (int idx : siblingIndices) {
                AnimationSegment childSeg = segments.get(idx);
                for (AnimationSegment seg : segments) {
                    if (seg.tokenId.equals(childSeg.tokenId) &&
                        seg.phase == Phase.TRAVELING_TO_TOUT &&
                        forkPointId.equals(seg.toElement)) {
                        parentArrival = seg.endTime;
                        effectiveParentId = childSeg.tokenId;
                        break;
                    }
                }
                if (parentArrival != Long.MIN_VALUE) break;
            }
        }
        
        // Find latest child start time
        long latestChildStart = Long.MIN_VALUE;
        for (int idx : siblingIndices) {
            AnimationSegment seg = segments.get(idx);
            if (!seg.tokenId.equals(effectiveParentId)) {
                latestChildStart = Math.max(latestChildStart, seg.startTime);
            }
        }
        
        if (latestChildStart == Long.MIN_VALUE) {
            latestChildStart = parentArrival;
        }
        
        // Sync time is max of parent arrival and latest child start
        long syncTime = Math.max(parentArrival, latestChildStart);
        
        if (syncTime == Long.MIN_VALUE) {
            logger.warn("Could not determine sync time for fork at " + forkPointId);
            return 0;
        }
        
        logger.debug("Fork group " + forkPointId + ": parentArrival=" + parentArrival +
            ", latestChild=" + latestChildStart + ", syncTime=" + syncTime);
        
        // Extend parent if needed
        if (effectiveParentId != null && parentArrival != Long.MIN_VALUE && syncTime > parentArrival) {
            extendParent(segments, effectiveParentId, forkPointId, syncTime);
        }
        
        // Adjust children
        int adjusted = 0;
        for (int idx : siblingIndices) {
            AnimationSegment seg = segments.get(idx);
            if (seg.tokenId.equals(effectiveParentId)) continue;
            
            if (seg.startTime != syncTime) {
                long duration = seg.endTime - seg.startTime;
                AnimationSegment adjusted_seg = seg.withTiming(syncTime, syncTime + duration);
                segments.set(idx, adjusted_seg);
                
                // Extend preceding segment
                if (syncTime > seg.startTime) {
                    extendPreceding(segments, seg.tokenId, seg.startTime, syncTime);
                }
                
                // Adjust following segments
                adjustFollowing(segments, seg.tokenId, seg.endTime, syncTime + duration);
                
                adjusted++;
            }
        }
        
        return adjusted;
    }
    
    private void extendParent(List<AnimationSegment> segments, String parentId, 
                              String forkPointId, long newEndTime) {
        for (int i = 0; i < segments.size(); i++) {
            AnimationSegment seg = segments.get(i);
            if (seg.tokenId.equals(parentId) &&
                seg.phase == Phase.TRAVELING_TO_TOUT &&
                forkPointId.equals(seg.toElement) &&
                seg.endTime < newEndTime) {
                
                segments.set(i, seg.withExtendedEnd(newEndTime));
                logger.debug("Extended parent " + parentId + " to " + newEndTime);
                return;
            }
        }
    }
    
    private void extendPreceding(List<AnimationSegment> segments, String tokenId,
                                  long originalStart, long newStart) {
        int precedingIdx = -1;
        long closestEnd = Long.MIN_VALUE;
        
        for (int i = 0; i < segments.size(); i++) {
            AnimationSegment seg = segments.get(i);
            if (seg.tokenId.equals(tokenId) && seg.endTime <= originalStart) {
                if (seg.endTime > closestEnd) {
                    closestEnd = seg.endTime;
                    precedingIdx = i;
                }
            }
        }
        
        if (precedingIdx >= 0) {
            AnimationSegment preceding = segments.get(precedingIdx);
            
            // Don't extend buffers at JOINs
            if (preceding.phase == Phase.BUFFERED_AT_TIN && 
                preceding.tInId != null && topology.isJoin(preceding.tInId)) {
                logger.debug("NOT extending buffer at JOIN " + preceding.tInId);
                return;
            }
            
            // Don't extend CONSUMED
            if (preceding.phase == Phase.CONSUMED) {
                logger.debug("NOT extending CONSUMED for " + tokenId);
                return;
            }
            
            segments.set(precedingIdx, preceding.withExtendedEnd(newStart));
        }
    }
    
    private void adjustFollowing(List<AnimationSegment> segments, String tokenId,
                                  long originalEnd, long newEnd) {
        if (newEnd == originalEnd) return;
        
        long shift = newEnd - originalEnd;
        
        for (int i = 0; i < segments.size(); i++) {
            AnimationSegment seg = segments.get(i);
            if (seg.tokenId.equals(tokenId) && seg.startTime == originalEnd) {
                segments.set(i, seg.shifted(shift));
                adjustFollowing(segments, tokenId, seg.endTime, seg.endTime + shift);
                break;
            }
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
