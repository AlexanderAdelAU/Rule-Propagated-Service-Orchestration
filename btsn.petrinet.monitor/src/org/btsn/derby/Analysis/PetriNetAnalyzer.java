package org.btsn.derby.Analysis;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.log4j.Logger;
import org.btsn.derby.Analysis.BuildServiceAnalysisDatabase;

/**
 * PetriNetAnalyzer - Analyzes Petri Net behavior from captured data
 * 
 * Provides analysis methods for:
 * - Token completeness (every token that enters also exits)
 * - Fork/Join analysis (sibling token synchronization)
 * - Throughput and performance metrics
 * - Marking evolution and concurrency
 * - Bottleneck identification
 * - Correctness verification
 * - Priority analysis (cross-version queue time comparison)
 * 
 * FORK/JOIN TOKEN ID ENCODING:
 * Fork creates child tokens with encoded IDs:
 *   childTokenId = parentTokenId + (joinCount * 100) + branchNumber
 * Example: Parent 1000000 with 2-way fork -> 1000201, 1000202
 * 
 * At JoinNode, sibling tokens are merged. The "joined" siblings show as
 * incomplete in raw analysis but are correctly identified here.
 * 
 * Token path queries now correctly pair each entry with its corresponding
 * exit (the next exit after that entry) instead of creating a Cartesian product
 * that caused negative residence times.
 * 
 * PRIORITY ANALYSIS:
 * Compares queue times between workflow versions (v001, v002, etc.) to verify
 * that higher priority versions (lower version numbers) receive preferential
 * queue treatment. Detects priority inversions where low priority tokens are
 * processed before competing high priority tokens.
 */
public class PetriNetAnalyzer {

    private static final Logger logger = Logger.getLogger(PetriNetAnalyzer.class);
    private static final String DB_URL = "jdbc:derby:./ServiceAnalysisDataBase;create=true";
    
    // Fork/Join token ID encoding constants (must match ServiceThread)
    private static final int TOKEN_INCREMENT = 10000;  // Gap between workflow base tokens
    
    private BuildServiceAnalysisDatabase db;
    
    public PetriNetAnalyzer() {
        this.db = new BuildServiceAnalysisDatabase();
    }
    
    // =============================================================================
    // TOKEN COMPLETENESS ANALYSIS
    // =============================================================================
    
    /**
     * Verify all tokens that entered also exited (no stuck tokens)
     * Returns list of incomplete tokens if any
     * 
     * FIXED: Now correctly identifies incomplete tokens by checking for entries
     * without a corresponding subsequent exit.
     * 
     * NOTE: Derby requires CAST() when comparing VARCHAR with concatenated strings.
     */
    public ArrayList<TokenPath> verifyTokenCompleteness(int workflowBase) {
        ArrayList<TokenPath> incompletePaths = new ArrayList<>();
        
        // Find entries that don't have a corresponding exit AFTER them
        // DERBY FIX: Use CAST() for string concatenation
        String sql = 
            "SELECT t_in.tokenId, t_in.timestamp as entryTime, t_in.toPlace " +
            "FROM CONSOLIDATED_TRANSITION_FIRINGS t_in " +
            "WHERE t_in.workflowBase = ? " +
            "  AND t_in.transitionId LIKE 'T_in_%' " +
            "  AND NOT EXISTS ( " +
            "      SELECT 1 FROM CONSOLIDATED_TRANSITION_FIRINGS t_out " +
            "      WHERE t_out.tokenId = t_in.tokenId " +
            "        AND t_out.workflowBase = t_in.workflowBase " +
            "        AND t_out.transitionId = CAST('T_out_' || t_in.toPlace AS VARCHAR(100)) " +
            "        AND t_out.timestamp >= t_in.timestamp " +
            "  ) " +
            "ORDER BY t_in.tokenId, t_in.timestamp";
        
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setInt(1, workflowBase);
            ResultSet rs = pstmt.executeQuery();
            
            while (rs.next()) {
                TokenPath path = new TokenPath();
                path.tokenId = rs.getInt("tokenId");
                path.entryTime = rs.getLong("entryTime");
                path.exitTime = 0; // Incomplete
                path.placeName = rs.getString("toPlace");
                incompletePaths.add(path);
            }
            
            logger.info("Token completeness check: " + 
                       (incompletePaths.isEmpty() ? "All tokens complete" : 
                        incompletePaths.size() + " incomplete tokens found"));
            
        } catch (SQLException e) {
            logger.error("Error checking token completeness", e);
        }
        
        return incompletePaths;
    }
    
    /**
     * Get complete token paths for a workflow
     * 
     * FIXED: The original query created a Cartesian product when a token visited
     * the same place multiple times. For example, if token 100000 entered P2_Place
     * 10 times and exited 10 times, the old JOIN produced 100 rows (10x10) instead
     * of 10 correct entry/exit pairs.
     * 
     * The fix ensures each entry is paired with its NEXT corresponding exit by:
     * 1. Requiring exit timestamp >= entry timestamp
     * 2. Using NOT EXISTS to ensure no other exit of the same type falls between
     *    this entry and exit (i.e., we pick the FIRST exit after this entry)
     * 
     * NOTE: Derby requires CAST() when comparing VARCHAR with concatenated strings.
     */
    public ArrayList<TokenPath> getTokenPaths(int workflowBase) {
        ArrayList<TokenPath> paths = new ArrayList<>();
        
        // FIXED: Pair each entry with its corresponding NEXT exit
        // The NOT EXISTS clause ensures we pick the first exit after each entry
        // DERBY FIX: Use CAST() for string concatenation to avoid VARCHAR/LONG VARCHAR error
        String sql = 
            "SELECT t_in.tokenId, t_in.toPlace, " +
            "       t_in.timestamp as entryTime, " +
            "       t_out.timestamp as exitTime " +
            "FROM CONSOLIDATED_TRANSITION_FIRINGS t_in " +
            "JOIN CONSOLIDATED_TRANSITION_FIRINGS t_out " +
            "  ON t_in.tokenId = t_out.tokenId " +
            "  AND t_in.workflowBase = t_out.workflowBase " +
            "  AND t_out.transitionId = CAST('T_out_' || t_in.toPlace AS VARCHAR(100)) " +
            "  AND t_out.timestamp >= t_in.timestamp " +
            "WHERE t_in.workflowBase = ? " +
            "  AND t_in.transitionId LIKE 'T_in_%' " +
            "  AND NOT EXISTS ( " +
            "      SELECT 1 FROM CONSOLIDATED_TRANSITION_FIRINGS t_between " +
            "      WHERE t_between.tokenId = t_in.tokenId " +
            "        AND t_between.workflowBase = t_in.workflowBase " +
            "        AND t_between.transitionId = t_out.transitionId " +
            "        AND t_between.timestamp > t_in.timestamp " +
            "        AND t_between.timestamp < t_out.timestamp " +
            "  ) " +
            "ORDER BY t_in.tokenId, t_in.timestamp";
        
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setInt(1, workflowBase);
            ResultSet rs = pstmt.executeQuery();
            
            while (rs.next()) {
                TokenPath path = new TokenPath();
                path.tokenId = rs.getInt("tokenId");
                path.placeName = rs.getString("toPlace");
                path.entryTime = rs.getLong("entryTime");
                path.exitTime = rs.getLong("exitTime");
                path.residenceTime = path.exitTime - path.entryTime;
                paths.add(path);
            }
            
            logger.info("Retrieved " + paths.size() + " token paths for workflowBase=" + workflowBase);
            
            // Sanity check for negative residence times (should not happen with fix)
            long negativeCount = paths.stream().filter(p -> p.residenceTime < 0).count();
            if (negativeCount > 0) {
                logger.warn("WARNING: Found " + negativeCount + " paths with negative residence time - check data integrity");
            }
            
        } catch (SQLException e) {
            logger.error("Error retrieving token paths", e);
        }
        
        return paths;
    }
    
    // =============================================================================
    // FORK/JOIN ANALYSIS
    // =============================================================================
    
    /**
     * Analyze fork/join patterns in the workflow
     * 
     * Token ID encoding: childTokenId = parentTokenId + (joinCount * 100) + branchNumber
     * Example: 1000201 -> parent=1000000, joinCount=2, branch=1
     * 
     * @return ForkJoinAnalysis containing fork/join statistics
     */
    public ForkJoinAnalysis analyzeForkJoin(int workflowBase) {
        ForkJoinAnalysis analysis = new ForkJoinAnalysis();
        
        // Get all unique token IDs from the workflow
        String sql = "SELECT DISTINCT tokenId FROM CONSOLIDATED_TRANSITION_FIRINGS " +
                    "WHERE workflowBase = ? ORDER BY tokenId";
        
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setInt(1, workflowBase);
            ResultSet rs = pstmt.executeQuery();
            
            // Group tokens by their parent (for forked tokens) or self (for base tokens)
            Map<Integer, Set<Integer>> parentToChildren = new HashMap<>();
            Set<Integer> allTokens = new HashSet<>();
            
            while (rs.next()) {
                int tokenId = rs.getInt("tokenId");
                allTokens.add(tokenId);
                
                // Decode token ID
                int remainder = tokenId % TOKEN_INCREMENT;
                int joinCount = remainder / 100;
                int branchNumber = remainder % 100;
                int parentTokenId = tokenId - remainder;
                
                // Check if this is a forked token (has valid encoding)
                boolean isForkedToken = (joinCount >= 2 && branchNumber >= 1 && branchNumber <= joinCount);
                
                if (isForkedToken) {
                    parentToChildren.computeIfAbsent(parentTokenId, k -> new HashSet<>()).add(tokenId);
                    analysis.forkedTokens.add(tokenId);
                } else {
                    // Base token (not forked, or the parent itself)
                    analysis.baseTokens.add(tokenId);
                }
            }
            
            // Analyze each fork group
            for (Map.Entry<Integer, Set<Integer>> entry : parentToChildren.entrySet()) {
                int parentId = entry.getKey();
                Set<Integer> children = entry.getValue();
                
                if (children.size() >= 2) {
                    ForkGroup group = new ForkGroup();
                    group.parentTokenId = parentId;
                    group.childTokenIds = new ArrayList<>(children);
                    group.expectedCount = children.size();
                    
                    // Check which children completed (have T_out from join place)
                    // The "surviving" token after join will have exited; others won't
                    for (int childId : children) {
                        if (hasExitedWorkflow(childId, workflowBase)) {
                            group.completedChildren.add(childId);
                        } else {
                            group.joinedChildren.add(childId);
                        }
                    }
                    
                    // A successful join: one child completed, others were joined
                    group.joinSuccessful = (group.completedChildren.size() == 1 && 
                                           group.joinedChildren.size() == group.expectedCount - 1);
                    
                    analysis.forkGroups.add(group);
                    
                    if (group.joinSuccessful) {
                        analysis.successfulJoins++;
                    }
                }
            }
            
            analysis.totalForks = analysis.forkGroups.size();
            
            logger.info("Fork/Join analysis: " + analysis.totalForks + " forks, " + 
                       analysis.successfulJoins + " successful joins");
            
        } catch (SQLException e) {
            logger.error("Error analyzing fork/join", e);
        }
        
        return analysis;
    }
    
    /**
     * Check if a token has exited the workflow completely
     * 
     * A token has "exited" the workflow if:
     * 1. It reached TERMINATE, OR
     * 2. It has a T_out from a place that has NO further T_in (it's the final place), OR
     * 3. For fork children: it exited the JOIN place (meaning it was the "surviving" token)
     * 
     * FIXED: Previous version checked for ANY T_out, which incorrectly counted tokens
     * that exited intermediate places (like P3) before reaching a join as "completed".
     * 
     * The correct approach: a forked token (xxx201, xxx202) has "exited" only if:
     * - It reached TERMINATE, or
     * - It has more T_out transitions than T_in transitions (exited its final place)
     * 
     * Tokens consumed by a join will have equal T_in and T_out counts up to the join,
     * then one more T_in (into the join place) with no corresponding T_out.
     */
    private boolean hasExitedWorkflow(int tokenId, int workflowBase) {
        // Strategy: Check if this token has exited the LAST place it entered
        // If T_in count == T_out count, all entries have exits -> token completed
        // If T_in count > T_out count, token is stuck (or consumed by join)
        
        String sql = 
            "SELECT " +
            "  (SELECT COUNT(*) FROM CONSOLIDATED_TRANSITION_FIRINGS " +
            "   WHERE tokenId = ? AND workflowBase = ? AND transitionId LIKE 'T_in_%') as inCount, " +
            "  (SELECT COUNT(*) FROM CONSOLIDATED_TRANSITION_FIRINGS " +
            "   WHERE tokenId = ? AND workflowBase = ? AND transitionId LIKE 'T_out_%') as outCount, " +
            "  (SELECT COUNT(*) FROM CONSOLIDATED_TRANSITION_FIRINGS " +
            "   WHERE tokenId = ? AND workflowBase = ? AND toPlace = 'TERMINATE') as terminateCount " +
            "FROM SYSIBM.SYSDUMMY1";  // Derby syntax for SELECT without table
        
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setInt(1, tokenId);
            pstmt.setInt(2, workflowBase);
            pstmt.setInt(3, tokenId);
            pstmt.setInt(4, workflowBase);
            pstmt.setInt(5, tokenId);
            pstmt.setInt(6, workflowBase);
            
            ResultSet rs = pstmt.executeQuery();
            
            if (rs.next()) {
                int inCount = rs.getInt("inCount");
                int outCount = rs.getInt("outCount");
                int terminateCount = rs.getInt("terminateCount");
                
                // Token exited if:
                // 1. It reached TERMINATE, or
                // 2. Every place it entered, it also exited (inCount == outCount)
                boolean exited = (terminateCount > 0) || (inCount > 0 && inCount == outCount);
                
                logger.debug("Token " + tokenId + " exit check: T_in=" + inCount + 
                           ", T_out=" + outCount + ", TERMINATE=" + terminateCount + 
                           " -> exited=" + exited);
                
                return exited;
            }
            
        } catch (SQLException e) {
            logger.error("Error checking token exit for tokenId=" + tokenId, e);
        }
        
        return false;
    }
    
    /**
     * Get incomplete tokens excluding those that were correctly joined
     * This filters out sibling tokens that were consumed by a join
     */
    public ArrayList<TokenPath> getActualIncompleteTokens(int workflowBase) {
        ArrayList<TokenPath> allIncomplete = verifyTokenCompleteness(workflowBase);
        ForkJoinAnalysis forkJoin = analyzeForkJoin(workflowBase);
        
        // Collect all tokens that were joined (consumed by join, not stuck)
        Set<Integer> joinedTokens = new HashSet<>();
        for (ForkGroup group : forkJoin.forkGroups) {
            if (group.joinSuccessful) {
                joinedTokens.addAll(group.joinedChildren);
            }
        }
        
        // Filter out joined tokens from incomplete list
        ArrayList<TokenPath> actualIncomplete = new ArrayList<>();
        for (TokenPath path : allIncomplete) {
            if (!joinedTokens.contains(path.tokenId)) {
                actualIncomplete.add(path);
            }
        }
        
        logger.info("Actual incomplete tokens: " + actualIncomplete.size() + 
                   " (filtered " + joinedTokens.size() + " joined tokens)");
        
        return actualIncomplete;
    }
    
    // =============================================================================
    // PERFORMANCE ANALYSIS
    // =============================================================================
    
    /**
     * Get performance statistics for a place
     * 
     * FIXED: Now computes statistics from correctly paired entry/exit events
     * instead of relying on pre-computed (potentially incorrect) values.
     */
    public PlaceStatistics getPlaceStatistics(String placeName, int workflowBase) {
        PlaceStatistics stats = new PlaceStatistics();
        stats.placeName = placeName;
        
        // First try the pre-computed table
        String sql = 
            "SELECT tokenCount, avgResidenceTime, minResidenceTime, maxResidenceTime " +
            "FROM CONSOLIDATED_PLACE_STATISTICS " +
            "WHERE placeName = ? " +
            "  AND workflowBase = ?";
        
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setString(1, placeName);
            pstmt.setInt(2, workflowBase);
            ResultSet rs = pstmt.executeQuery();
            
            if (rs.next()) {
                stats.tokenCount = rs.getInt("tokenCount");
                stats.avgResidenceTime = rs.getDouble("avgResidenceTime");
                stats.minResidenceTime = rs.getLong("minResidenceTime");
                stats.maxResidenceTime = rs.getLong("maxResidenceTime");
            } else {
                // Fallback: compute from transition firings with corrected query
                stats = computePlaceStatisticsFromFirings(placeName, workflowBase);
            }
            
            logger.info("Place " + placeName + " statistics: " + 
                       stats.tokenCount + " tokens, avg=" + 
                       String.format("%.1f", stats.avgResidenceTime) + "ms");
            
        } catch (SQLException e) {
            logger.error("Error calculating place statistics", e);
        }
        
        return stats;
    }
    
    /**
     * Compute place statistics directly from transition firings
     * Uses the corrected entry/exit pairing logic
     * 
     * NOTE: Derby requires CAST() when comparing VARCHAR with concatenated strings
     * (which produce LONG VARCHAR). All string concatenations use explicit CAST.
     */
    private PlaceStatistics computePlaceStatisticsFromFirings(String placeName, int workflowBase) {
        PlaceStatistics stats = new PlaceStatistics();
        stats.placeName = placeName;
        
        // Use corrected pairing logic to compute statistics
        // DERBY FIX: Use CAST() for all string concatenations to avoid VARCHAR/LONG VARCHAR comparison error
        String sql = 
            "SELECT COUNT(*) as tokenCount, " +
            "       AVG(t_out.timestamp - t_in.timestamp) as avgResidence, " +
            "       MIN(t_out.timestamp - t_in.timestamp) as minResidence, " +
            "       MAX(t_out.timestamp - t_in.timestamp) as maxResidence " +
            "FROM CONSOLIDATED_TRANSITION_FIRINGS t_in " +
            "JOIN CONSOLIDATED_TRANSITION_FIRINGS t_out " +
            "  ON t_in.tokenId = t_out.tokenId " +
            "  AND t_in.workflowBase = t_out.workflowBase " +
            "  AND t_out.transitionId = CAST('T_out_' || t_in.toPlace AS VARCHAR(100)) " +
            "  AND t_out.timestamp >= t_in.timestamp " +
            "WHERE t_in.workflowBase = ? " +
            "  AND t_in.toPlace = ? " +
            "  AND t_in.transitionId = CAST('T_in_' || ? AS VARCHAR(100)) " +
            "  AND NOT EXISTS ( " +
            "      SELECT 1 FROM CONSOLIDATED_TRANSITION_FIRINGS t_between " +
            "      WHERE t_between.tokenId = t_in.tokenId " +
            "        AND t_between.workflowBase = t_in.workflowBase " +
            "        AND t_between.transitionId = t_out.transitionId " +
            "        AND t_between.timestamp > t_in.timestamp " +
            "        AND t_between.timestamp < t_out.timestamp " +
            "  )";
        
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setInt(1, workflowBase);
            pstmt.setString(2, placeName);
            pstmt.setString(3, placeName);
            ResultSet rs = pstmt.executeQuery();
            
            if (rs.next()) {
                stats.tokenCount = rs.getInt("tokenCount");
                stats.avgResidenceTime = rs.getDouble("avgResidence");
                stats.minResidenceTime = rs.getLong("minResidence");
                stats.maxResidenceTime = rs.getLong("maxResidence");
            }
            
        } catch (SQLException e) {
            logger.error("Error computing place statistics from firings", e);
        }
        
        return stats;
    }
    
    /**
     * Get workflow throughput (tokens per second)
     */
    public double getWorkflowThroughput(int workflowBase) {
        String sql = 
            "SELECT COUNT(*) as tokenCount, " +
            "       MAX(timestamp) - MIN(timestamp) as duration " +
            "FROM CONSOLIDATED_TRANSITION_FIRINGS " +
            "WHERE workflowBase = ? " +
            "  AND transitionId LIKE 'T_out_%'";
        
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setInt(1, workflowBase);
            ResultSet rs = pstmt.executeQuery();
            
            if (rs.next()) {
                int tokenCount = rs.getInt("tokenCount");
                long duration = rs.getLong("duration");
                
                if (duration > 0) {
                    double throughput = (tokenCount * 1000.0) / duration; // tokens per second
                    logger.info("Workflow throughput: " + 
                               String.format("%.2f", throughput) + " tokens/sec");
                    return throughput;
                }
            }
            
        } catch (SQLException e) {
            logger.error("Error calculating throughput", e);
        }
        
        return 0.0;
    }
    
    // =============================================================================
    // MARKING ANALYSIS
    // =============================================================================
    
    /**
     * Get marking evolution over time
     */
    public ArrayList<MarkingSnapshot> getMarkingEvolution(String placeName, int workflowBase) {
        ArrayList<MarkingSnapshot> snapshots = new ArrayList<>();
        
        String sql = 
            "SELECT tokenId, timestamp, marking, bufferSize " +
            "FROM CONSOLIDATED_MARKING_EVOLUTION " +
            "WHERE placeName = ? " +
            "  AND workflowBase = ? " +
            "ORDER BY timestamp";
        
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setString(1, placeName);
            pstmt.setInt(2, workflowBase);
            ResultSet rs = pstmt.executeQuery();
            
            while (rs.next()) {
                MarkingSnapshot snapshot = new MarkingSnapshot();
                snapshot.tokenId = rs.getInt("tokenId");
                snapshot.timestamp = rs.getLong("timestamp");
                snapshot.marking = rs.getInt("marking");
                snapshot.bufferSize = rs.getInt("bufferSize");
                snapshots.add(snapshot);
            }
            
            logger.info("Retrieved " + snapshots.size() + " marking snapshots for " + placeName);
            
        } catch (SQLException e) {
            logger.error("Error retrieving marking evolution", e);
        }
        
        return snapshots;
    }
    
    /**
     * Verify bounded capacity (marking never exceeds capacity)
     */
    public boolean verifyBoundedCapacity(String placeName, int workflowBase, int expectedCapacity) {
        String sql = 
            "SELECT MAX(marking) as maxMarking " +
            "FROM CONSOLIDATED_MARKING_EVOLUTION " +
            "WHERE placeName = ? " +
            "  AND workflowBase = ?";
        
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setString(1, placeName);
            pstmt.setInt(2, workflowBase);
            ResultSet rs = pstmt.executeQuery();
            
            if (rs.next()) {
                int maxMarking = rs.getInt("maxMarking");
                boolean bounded = maxMarking <= expectedCapacity;
                
                logger.info("Capacity verification for " + placeName + ": " +
                           "max marking=" + maxMarking + ", capacity=" + expectedCapacity + 
                           " - " + (bounded ? "PASS" : "FAIL"));
                
                return bounded;
            }
            
        } catch (SQLException e) {
            logger.error("Error verifying bounded capacity", e);
        }
        
        return false;
    }
    
    // =============================================================================
    // CONCURRENCY ANALYSIS
    // =============================================================================
    
    /**
     * Analyze inter-arrival times (how tokens arrive at place)
     */
    public ArrayList<InterArrival> getInterArrivalTimes(String placeName, int workflowBase) {
        ArrayList<InterArrival> arrivals = new ArrayList<>();
        
        String sql = 
            "SELECT tokenId, timestamp, " +
            "       timestamp - LAG(timestamp, 1, timestamp) OVER (ORDER BY timestamp) as interArrival " +
            "FROM CONSOLIDATED_TRANSITION_FIRINGS " +
            "WHERE transitionId = 'T_in_' || ? " +
            "  AND workflowBase = ? " +
            "ORDER BY timestamp";
        
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setString(1, placeName);
            pstmt.setInt(2, workflowBase);
            ResultSet rs = pstmt.executeQuery();
            
            boolean first = true;
            while (rs.next()) {
                if (first) {
                    first = false;
                    continue; // Skip first record (no previous arrival)
                }
                
                InterArrival arrival = new InterArrival();
                arrival.tokenId = rs.getInt("tokenId");
                arrival.timestamp = rs.getLong("timestamp");
                arrival.interArrivalTime = rs.getLong("interArrival");
                arrivals.add(arrival);
            }
            
            logger.info("Retrieved " + arrivals.size() + " inter-arrival measurements");
            
        } catch (SQLException e) {
            logger.error("Error analyzing inter-arrival times", e);
        }
        
        return arrivals;
    }
    
    // =============================================================================
    // PRIORITY ANALYSIS (Cross-Version)
    // =============================================================================
    
    /**
     * Analyze priority behavior between workflow versions
     * 
     * Compares queue times and processing order when tokens from different
     * versions (v001, v002, etc.) compete for the same service queue.
     * 
     * Priority expectations:
     * - v001 (workflowBase 1000000) = High priority
     * - v002 (workflowBase 2000000) = Low priority
     * - Higher priority tokens should have lower queue times when competing
     * 
     * IMPORTANT: Forked tokens (join participants) are tracked separately because
     * join completion semantics override priority - when a sibling arrives to
     * complete a join, it gets fast-tracked regardless of priority. This is
     * correct workflow behavior, not a priority violation.
     * 
     * @return PriorityAnalysis containing cross-version comparison results
     */
    public PriorityAnalysis analyzePriority() {
        PriorityAnalysis analysis = new PriorityAnalysis();
        
        // Query SERVICECONTRIBUTION for all versions, looking for overlapping time windows
        String sql = 
            "SELECT WORKFLOWBASE, SEQUENCEID, SERVICENAME, ARRIVALTIME, QUEUETIME, " +
            "       SERVICETIME, TOTALTIME, BUFFERSIZE " +
            "FROM SERVICECONTRIBUTION " +
            "WHERE SERVICENAME IN ('v001', 'v002', 'v003', 'v004', 'v005') " +
            "ORDER BY ARRIVALTIME";
        
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            // Collect all service contributions by version
            ArrayList<ServiceContribution> allContributions = new ArrayList<>();
            ArrayList<ServiceContribution> rootTokenContributions = new ArrayList<>();
            
            while (rs.next()) {
                ServiceContribution sc = new ServiceContribution();
                sc.workflowBase = rs.getLong("WORKFLOWBASE");
                sc.sequenceId = rs.getInt("SEQUENCEID");
                sc.serviceName = rs.getString("SERVICENAME");
                sc.arrivalTime = rs.getLong("ARRIVALTIME");
                sc.queueTime = rs.getLong("QUEUETIME");
                sc.serviceTime = rs.getLong("SERVICETIME");
                sc.totalTime = rs.getLong("TOTALTIME");
                sc.bufferSize = rs.getInt("BUFFERSIZE");
                
                // Derive version number from serviceName (v001 -> 1, v002 -> 2)
                sc.versionNumber = Integer.parseInt(sc.serviceName.substring(1));
                
                // Detect forked tokens using token ID encoding
                // childTokenId = parentTokenId + (joinCount * 100) + branchNumber
                // Example: 1010201 -> remainder=201, joinCount=2, branch=1
                int remainder = sc.sequenceId % TOKEN_INCREMENT;
                sc.joinCount = remainder / 100;
                sc.branchNumber = remainder % 100;
                sc.isForkedToken = (sc.joinCount >= 2 && sc.branchNumber >= 1 && sc.branchNumber <= sc.joinCount);
                
                allContributions.add(sc);
                
                // Track per-version statistics (all tokens)
                analysis.versionStats.computeIfAbsent(sc.versionNumber, k -> new VersionStats(sc.versionNumber));
                VersionStats stats = analysis.versionStats.get(sc.versionNumber);
                stats.tokenCount++;
                stats.totalQueueTime += sc.queueTime;
                stats.totalServiceTime += sc.serviceTime;
                if (sc.queueTime < stats.minQueueTime) stats.minQueueTime = sc.queueTime;
                if (sc.queueTime > stats.maxQueueTime) stats.maxQueueTime = sc.queueTime;
                
                // Separate tracking for root tokens vs forked tokens
                if (sc.isForkedToken) {
                    analysis.forkedTokenSamples++;
                    analysis.joinCompletions.add(sc);
                } else {
                    analysis.rootTokenSamples++;
                    rootTokenContributions.add(sc);
                    
                    // Track root token stats separately
                    analysis.rootTokenStats.computeIfAbsent(sc.versionNumber, k -> new VersionStats(sc.versionNumber));
                    VersionStats rootStats = analysis.rootTokenStats.get(sc.versionNumber);
                    rootStats.tokenCount++;
                    rootStats.totalQueueTime += sc.queueTime;
                    rootStats.totalServiceTime += sc.serviceTime;
                    if (sc.queueTime < rootStats.minQueueTime) rootStats.minQueueTime = sc.queueTime;
                    if (sc.queueTime > rootStats.maxQueueTime) rootStats.maxQueueTime = sc.queueTime;
                }
            }
            
            analysis.totalSamples = allContributions.size();
            
            // Calculate averages for all tokens
            for (VersionStats stats : analysis.versionStats.values()) {
                if (stats.tokenCount > 0) {
                    stats.avgQueueTime = (double) stats.totalQueueTime / stats.tokenCount;
                    stats.avgServiceTime = (double) stats.totalServiceTime / stats.tokenCount;
                }
            }
            
            // Calculate averages for root tokens only
            for (VersionStats stats : analysis.rootTokenStats.values()) {
                if (stats.tokenCount > 0) {
                    stats.avgQueueTime = (double) stats.totalQueueTime / stats.tokenCount;
                    stats.avgServiceTime = (double) stats.totalServiceTime / stats.tokenCount;
                }
            }
            
            // Find contention points - all tokens and root tokens separately
            findContentionPoints(allContributions, analysis);
            findRootTokenContentionPoints(rootTokenContributions, analysis);
            
            // Detect priority inversions - all tokens and root tokens separately
            detectPriorityInversions(allContributions, analysis);
            detectRootTokenPriorityInversions(rootTokenContributions, analysis);
            
            // Calculate priority effectiveness
            calculatePriorityEffectiveness(analysis);
            calculateRootTokenPriorityEffectiveness(analysis);
            
            logger.info("Priority analysis complete: " + analysis.totalSamples + " samples (" +
                       analysis.rootTokenSamples + " root, " + analysis.forkedTokenSamples + " forked), " +
                       analysis.rootTokenContentionPoints.size() + " root contention points, " +
                       analysis.rootTokenInversions.size() + " root inversions");
            
        } catch (SQLException e) {
            logger.error("Error analyzing priority", e);
        }
        
        return analysis;
    }
    
    /**
     * Find points in time where tokens from different versions were competing
     */
    private void findContentionPoints(ArrayList<ServiceContribution> contributions, PriorityAnalysis analysis) {
        // Group by approximate time window (within 1 second)
        long WINDOW_MS = 1000;
        
        for (int i = 0; i < contributions.size(); i++) {
            ServiceContribution sc1 = contributions.get(i);
            
            for (int j = i + 1; j < contributions.size(); j++) {
                ServiceContribution sc2 = contributions.get(j);
                
                // Stop if we're past the time window
                if (sc2.arrivalTime - sc1.arrivalTime > WINDOW_MS) {
                    break;
                }
                
                // Check if different versions and both had buffer (queue contention)
                if (sc1.versionNumber != sc2.versionNumber && 
                    (sc1.bufferSize > 0 || sc2.bufferSize > 0)) {
                    
                    ContentionPoint cp = new ContentionPoint();
                    cp.timestamp = sc1.arrivalTime;
                    cp.highPriorityToken = (sc1.versionNumber < sc2.versionNumber) ? sc1 : sc2;
                    cp.lowPriorityToken = (sc1.versionNumber < sc2.versionNumber) ? sc2 : sc1;
                    cp.timeDelta = Math.abs(sc2.arrivalTime - sc1.arrivalTime);
                    
                    // High priority should have lower queue time
                    cp.highPriorityQueueTime = cp.highPriorityToken.queueTime;
                    cp.lowPriorityQueueTime = cp.lowPriorityToken.queueTime;
                    cp.priorityRespected = cp.highPriorityQueueTime <= cp.lowPriorityQueueTime;
                    
                    // Track if either token is a forked token (join participant)
                    cp.involvesForkedToken = cp.highPriorityToken.isForkedToken || cp.lowPriorityToken.isForkedToken;
                    
                    analysis.contentionPoints.add(cp);
                }
            }
        }
    }
    
    /**
     * Find contention points for ROOT TOKENS ONLY (excludes join participants)
     */
    private void findRootTokenContentionPoints(ArrayList<ServiceContribution> rootContributions, PriorityAnalysis analysis) {
        long WINDOW_MS = 1000;
        
        for (int i = 0; i < rootContributions.size(); i++) {
            ServiceContribution sc1 = rootContributions.get(i);
            
            for (int j = i + 1; j < rootContributions.size(); j++) {
                ServiceContribution sc2 = rootContributions.get(j);
                
                if (sc2.arrivalTime - sc1.arrivalTime > WINDOW_MS) {
                    break;
                }
                
                if (sc1.versionNumber != sc2.versionNumber && 
                    (sc1.bufferSize > 0 || sc2.bufferSize > 0)) {
                    
                    ContentionPoint cp = new ContentionPoint();
                    cp.timestamp = sc1.arrivalTime;
                    cp.highPriorityToken = (sc1.versionNumber < sc2.versionNumber) ? sc1 : sc2;
                    cp.lowPriorityToken = (sc1.versionNumber < sc2.versionNumber) ? sc2 : sc1;
                    cp.timeDelta = Math.abs(sc2.arrivalTime - sc1.arrivalTime);
                    cp.highPriorityQueueTime = cp.highPriorityToken.queueTime;
                    cp.lowPriorityQueueTime = cp.lowPriorityToken.queueTime;
                    cp.priorityRespected = cp.highPriorityQueueTime <= cp.lowPriorityQueueTime;
                    cp.involvesForkedToken = false;
                    
                    analysis.rootTokenContentionPoints.add(cp);
                }
            }
        }
    }
    
    /**
     * Detect cases where low priority tokens were processed before high priority ones
     */
    private void detectPriorityInversions(ArrayList<ServiceContribution> contributions, PriorityAnalysis analysis) {
        // Sort by completion time (arrival + total time)
        ArrayList<ServiceContribution> byCompletion = new ArrayList<>(contributions);
        byCompletion.sort((a, b) -> Long.compare(a.arrivalTime + a.totalTime, b.arrivalTime + b.totalTime));
        
        for (int i = 0; i < byCompletion.size(); i++) {
            ServiceContribution completed = byCompletion.get(i);
            long completionTime = completed.arrivalTime + completed.totalTime;
            
            // Check if any higher priority token arrived before this one completed
            // but was still waiting when this one finished
            for (ServiceContribution other : contributions) {
                if (other == completed) continue;
                
                // other is higher priority (lower version number)
                // other arrived before completed finished
                // other finished after completed finished (was still waiting)
                if (other.versionNumber < completed.versionNumber &&
                    other.arrivalTime < completionTime &&
                    (other.arrivalTime + other.totalTime) > completionTime) {
                    
                    PriorityInversion inv = new PriorityInversion();
                    inv.highPriorityToken = other;
                    inv.lowPriorityToken = completed;
                    inv.inversionTime = completionTime - other.arrivalTime;
                    inv.involvesForkedToken = other.isForkedToken || completed.isForkedToken;
                    
                    analysis.priorityInversions.add(inv);
                }
            }
        }
    }
    
    /**
     * Detect priority inversions for ROOT TOKENS ONLY (excludes join participants)
     */
    private void detectRootTokenPriorityInversions(ArrayList<ServiceContribution> rootContributions, PriorityAnalysis analysis) {
        ArrayList<ServiceContribution> byCompletion = new ArrayList<>(rootContributions);
        byCompletion.sort((a, b) -> Long.compare(a.arrivalTime + a.totalTime, b.arrivalTime + b.totalTime));
        
        for (int i = 0; i < byCompletion.size(); i++) {
            ServiceContribution completed = byCompletion.get(i);
            long completionTime = completed.arrivalTime + completed.totalTime;
            
            for (ServiceContribution other : rootContributions) {
                if (other == completed) continue;
                
                if (other.versionNumber < completed.versionNumber &&
                    other.arrivalTime < completionTime &&
                    (other.arrivalTime + other.totalTime) > completionTime) {
                    
                    PriorityInversion inv = new PriorityInversion();
                    inv.highPriorityToken = other;
                    inv.lowPriorityToken = completed;
                    inv.inversionTime = completionTime - other.arrivalTime;
                    inv.involvesForkedToken = false;
                    
                    analysis.rootTokenInversions.add(inv);
                }
            }
        }
    }
    
    /**
     * Calculate overall priority effectiveness metrics
     */
    private void calculatePriorityEffectiveness(PriorityAnalysis analysis) {
        if (analysis.contentionPoints.isEmpty()) {
            analysis.priorityEffectiveness = 1.0; // No contention = perfect
            return;
        }
        
        long respectedCount = analysis.contentionPoints.stream()
            .filter(cp -> cp.priorityRespected)
            .count();
        
        analysis.priorityEffectiveness = (double) respectedCount / analysis.contentionPoints.size();
        
        // Calculate average queue time advantage for high priority
        double totalAdvantage = 0;
        for (ContentionPoint cp : analysis.contentionPoints) {
            totalAdvantage += (cp.lowPriorityQueueTime - cp.highPriorityQueueTime);
        }
        analysis.avgQueueTimeAdvantage = totalAdvantage / analysis.contentionPoints.size();
    }
    
    /**
     * Calculate priority effectiveness for ROOT TOKENS ONLY
     */
    private void calculateRootTokenPriorityEffectiveness(PriorityAnalysis analysis) {
        if (analysis.rootTokenContentionPoints.isEmpty()) {
            analysis.rootTokenPriorityEffectiveness = 1.0;
            return;
        }
        
        long respectedCount = analysis.rootTokenContentionPoints.stream()
            .filter(cp -> cp.priorityRespected)
            .count();
        
        analysis.rootTokenPriorityEffectiveness = (double) respectedCount / analysis.rootTokenContentionPoints.size();
        
        double totalAdvantage = 0;
        for (ContentionPoint cp : analysis.rootTokenContentionPoints) {
            totalAdvantage += (cp.lowPriorityQueueTime - cp.highPriorityQueueTime);
        }
        analysis.rootTokenQueueTimeAdvantage = totalAdvantage / analysis.rootTokenContentionPoints.size();
    }
    
    /**
     * Generate priority analysis report
     */
    public String generatePriorityReport() {
        PriorityAnalysis analysis = analyzePriority();
        StringBuilder report = new StringBuilder();
        
        report.append("\n=== PRIORITY ANALYSIS REPORT ===\n\n");
        
        // 1. Sample breakdown
        report.append("1. SAMPLE BREAKDOWN\n");
        report.append("   Total samples: ").append(analysis.totalSamples).append("\n");
        report.append("   Root tokens: ").append(analysis.rootTokenSamples)
              .append(" (used for priority analysis)\n");
        report.append("   Forked tokens: ").append(analysis.forkedTokenSamples)
              .append(" (join participants - excluded from priority analysis)\n\n");
        
        // 2. Per-version statistics (root tokens only)
        report.append("2. VERSION STATISTICS (Root Tokens Only)\n");
        report.append(String.format("   %-8s %-10s %-12s %-12s %-12s %-12s\n", 
            "Version", "Tokens", "Avg Queue", "Min Queue", "Max Queue", "Avg Service"));
        report.append("   " + "-".repeat(70) + "\n");
        
        List<Integer> sortedVersions = new ArrayList<>(analysis.rootTokenStats.keySet());
        sortedVersions.sort(Integer::compareTo);
        for (int version : sortedVersions) {
            VersionStats stats = analysis.rootTokenStats.get(version);
            report.append(String.format("   v%03d     %-10d %-12.1f %-12d %-12d %-12.1f\n",
                version, stats.tokenCount, stats.avgQueueTime, 
                stats.minQueueTime == Long.MAX_VALUE ? 0 : stats.minQueueTime, 
                stats.maxQueueTime, stats.avgServiceTime));
        }
        report.append("\n");
        
        // 3. Contention analysis (root tokens only)
        report.append("3. CONTENTION ANALYSIS (Root Tokens Only)\n");
        report.append("   Total contention points: ").append(analysis.rootTokenContentionPoints.size()).append("\n");
        
        if (!analysis.rootTokenContentionPoints.isEmpty()) {
            long respected = analysis.rootTokenContentionPoints.stream().filter(cp -> cp.priorityRespected).count();
            report.append("   Priority respected: ").append(respected)
                  .append("/").append(analysis.rootTokenContentionPoints.size())
                  .append(" (").append(String.format("%.1f%%", analysis.rootTokenPriorityEffectiveness * 100)).append(")\n");
            report.append("   Avg queue time advantage: ")
                  .append(String.format("%.1fms", analysis.rootTokenQueueTimeAdvantage))
                  .append(" (positive = high priority faster)\n");
            
            // Show sample contention points
            report.append("\n   Sample contention points:\n");
            int shown = 0;
            for (ContentionPoint cp : analysis.rootTokenContentionPoints) {
                if (shown++ >= 5) {
                    report.append("   ... and ").append(analysis.rootTokenContentionPoints.size() - 5).append(" more\n");
                    break;
                }
                report.append(String.format("   - v%03d (seq=%d, queue=%dms) vs v%03d (seq=%d, queue=%dms) %s\n",
                    cp.highPriorityToken.versionNumber, cp.highPriorityToken.sequenceId, cp.highPriorityQueueTime,
                    cp.lowPriorityToken.versionNumber, cp.lowPriorityToken.sequenceId, cp.lowPriorityQueueTime,
                    cp.priorityRespected ? "[OK]" : "[INVERSION]"));
            }
        }
        report.append("\n");
        
        // 4. Priority inversions (root tokens only)
        report.append("4. PRIORITY INVERSIONS (Root Tokens Only)\n");
        if (analysis.rootTokenInversions.isEmpty()) {
            report.append("   [OK] No priority inversions detected\n");
        } else {
            report.append("   [WARN] ").append(analysis.rootTokenInversions.size())
                  .append(" priority inversions detected\n");
            
            int shown = 0;
            for (PriorityInversion inv : analysis.rootTokenInversions) {
                if (shown++ >= 5) {
                    report.append("   ... and ").append(analysis.rootTokenInversions.size() - 5).append(" more\n");
                    break;
                }
                report.append(String.format("   - v%03d token %d waited while v%03d token %d completed (inversion: %dms)\n",
                    inv.highPriorityToken.versionNumber, inv.highPriorityToken.sequenceId,
                    inv.lowPriorityToken.versionNumber, inv.lowPriorityToken.sequenceId,
                    inv.inversionTime));
            }
        }
        report.append("\n");
        
        // 5. Join completions (informational)
        report.append("5. JOIN COMPLETIONS (Excluded from Priority Analysis)\n");
        report.append("   Forked tokens processed: ").append(analysis.joinCompletions.size()).append("\n");
        if (!analysis.joinCompletions.isEmpty()) {
            report.append("   Note: Join participants are fast-tracked when siblings arrive.\n");
            report.append("         This is correct workflow semantics, not a priority violation.\n");
            
            // Show breakdown by version
            Map<Integer, Long> joinsByVersion = new HashMap<>();
            for (ServiceContribution sc : analysis.joinCompletions) {
                joinsByVersion.merge(sc.versionNumber, 1L, Long::sum);
            }
            report.append("   By version: ");
            List<Integer> joinVersions = new ArrayList<>(joinsByVersion.keySet());
            joinVersions.sort(Integer::compareTo);
            for (int version : joinVersions) {
                report.append("v").append(String.format("%03d", version)).append("=")
                      .append(joinsByVersion.get(version)).append(" ");
            }
            report.append("\n");
        }
        report.append("\n");
        
        // 6. Verdict (based on root tokens only)
        report.append("6. PRIORITY VERDICT\n");
        if (analysis.rootTokenPriorityEffectiveness >= 0.9) {
            report.append("   [PASS] Priority scheduling is working effectively (")
                  .append(String.format("%.1f%%", analysis.rootTokenPriorityEffectiveness * 100)).append(")\n");
        } else if (analysis.rootTokenPriorityEffectiveness >= 0.7) {
            report.append("   [WARN] Priority scheduling is partially effective (")
                  .append(String.format("%.1f%%", analysis.rootTokenPriorityEffectiveness * 100)).append(")\n");
        } else if (analysis.rootTokenContentionPoints.isEmpty()) {
            report.append("   [INFO] No contention detected between versions - priority not testable\n");
        } else {
            report.append("   [FAIL] Priority scheduling is not working as expected (")
                  .append(String.format("%.1f%%", analysis.rootTokenPriorityEffectiveness * 100)).append(")\n");
        }
        
        report.append("\n=== END PRIORITY REPORT ===\n");
        
        return report.toString();
    }
    
    /**
     * Print priority report to console
     */
    public void printPriorityReport() {
        String report = generatePriorityReport();
        System.out.println(report);
        logger.info("Generated priority analysis report");
    }
    
    // =============================================================================
    // DATA CLASSES FOR PRIORITY ANALYSIS
    // =============================================================================
    
    /**
     * Service contribution record from SERVICECONTRIBUTION table
     */
    public static class ServiceContribution {
        public long workflowBase;
        public int sequenceId;
        public String serviceName;
        public long arrivalTime;
        public long queueTime;
        public long serviceTime;
        public long totalTime;
        public int bufferSize;
        public int versionNumber;  // Derived from serviceName
        public boolean isForkedToken;  // True if this is a fork child (join participant)
        public int joinCount;  // Fork join count (2 = 2-way fork, etc.)
        public int branchNumber;  // Branch within the fork (1, 2, etc.)
    }
    
    /**
     * Per-version statistics
     */
    public static class VersionStats {
        public int versionNumber;
        public int tokenCount = 0;
        public long totalQueueTime = 0;
        public long totalServiceTime = 0;
        public double avgQueueTime = 0;
        public double avgServiceTime = 0;
        public long minQueueTime = Long.MAX_VALUE;
        public long maxQueueTime = 0;
        
        public VersionStats(int version) {
            this.versionNumber = version;
        }
    }
    
    /**
     * A point in time where tokens from different versions competed
     */
    public static class ContentionPoint {
        public long timestamp;
        public ServiceContribution highPriorityToken;
        public ServiceContribution lowPriorityToken;
        public long timeDelta;
        public long highPriorityQueueTime;
        public long lowPriorityQueueTime;
        public boolean priorityRespected;
        public boolean involvesForkedToken;  // True if either token is a join participant
    }
    
    /**
     * A case where low priority token was processed before high priority
     */
    public static class PriorityInversion {
        public ServiceContribution highPriorityToken;
        public ServiceContribution lowPriorityToken;
        public long inversionTime;
        public boolean involvesForkedToken;  // True if either token is a join participant
    }
    
    /**
     * Complete priority analysis results
     */
    public static class PriorityAnalysis {
        public int totalSamples = 0;
        public int rootTokenSamples = 0;  // Non-forked tokens only
        public int forkedTokenSamples = 0;  // Join participants
        public Map<Integer, VersionStats> versionStats = new HashMap<>();
        public Map<Integer, VersionStats> rootTokenStats = new HashMap<>();  // Stats for root tokens only
        public ArrayList<ContentionPoint> contentionPoints = new ArrayList<>();
        public ArrayList<ContentionPoint> rootTokenContentionPoints = new ArrayList<>();  // Filtered
        public ArrayList<PriorityInversion> priorityInversions = new ArrayList<>();
        public ArrayList<PriorityInversion> rootTokenInversions = new ArrayList<>();  // Filtered
        public ArrayList<ServiceContribution> joinCompletions = new ArrayList<>();  // Forked tokens
        public double priorityEffectiveness = 0;
        public double rootTokenPriorityEffectiveness = 0;  // Effectiveness excluding joins
        public double avgQueueTimeAdvantage = 0;
        public double rootTokenQueueTimeAdvantage = 0;
        
        @Override
        public String toString() {
            return "PriorityAnalysis[samples=" + totalSamples + 
                   ", rootTokens=" + rootTokenSamples +
                   ", forkedTokens=" + forkedTokenSamples +
                   ", contentionPoints=" + contentionPoints.size() +
                   ", rootContentionPoints=" + rootTokenContentionPoints.size() +
                   ", inversions=" + priorityInversions.size() +
                   ", rootInversions=" + rootTokenInversions.size() +
                   ", effectiveness=" + String.format("%.1f%%", rootTokenPriorityEffectiveness * 100) + "]";
        }
    }
    
    // =============================================================================
    // SUMMARY REPORTS
    // =============================================================================
    
    /**
     * Generate comprehensive workflow analysis report
     */
    public String generateWorkflowReport(int workflowBase) {
        StringBuilder report = new StringBuilder();
        
        report.append("=== PETRI NET ANALYSIS REPORT ===\n");
        report.append("Workflow Base: ").append(workflowBase).append("\n\n");
        
        // 1. Fork/Join Analysis (do this first to filter incomplete tokens)
        ForkJoinAnalysis forkJoin = analyzeForkJoin(workflowBase);
        report.append("1. FORK/JOIN ANALYSIS\n");
        if (forkJoin.totalForks == 0) {
            report.append("   No fork/join patterns detected\n");
        } else {
            report.append("   Total Forks: ").append(forkJoin.totalForks).append("\n");
            report.append("   Successful Joins: ").append(forkJoin.successfulJoins).append("\n");
            report.append("   Forked Tokens: ").append(forkJoin.forkedTokens.size()).append("\n");
            
            // Show fork groups
            for (ForkGroup group : forkJoin.forkGroups) {
                report.append("   Fork from ").append(group.parentTokenId)
                      .append(" -> ").append(group.childTokenIds)
                      .append(group.joinSuccessful ? " [JOINED]" : " [INCOMPLETE]").append("\n");
            }
        }
        report.append("\n");
        
        // 2. Token completeness (excluding correctly joined tokens)
        ArrayList<TokenPath> rawIncomplete = verifyTokenCompleteness(workflowBase);
        ArrayList<TokenPath> actualIncomplete = getActualIncompleteTokens(workflowBase);
        
        report.append("2. TOKEN COMPLETENESS\n");
        if (actualIncomplete.isEmpty()) {
            report.append("   [OK] All tokens completed successfully\n");
            if (rawIncomplete.size() > actualIncomplete.size()) {
                int joinedCount = rawIncomplete.size() - actualIncomplete.size();
                report.append("   (").append(joinedCount).append(" tokens consumed by joins - this is correct)\n");
            }
        } else {
            report.append("   [FAIL] ").append(actualIncomplete.size()).append(" incomplete tokens found\n");
            for (TokenPath path : actualIncomplete) {
                report.append("     - Token ").append(path.tokenId)
                      .append(" stuck at ").append(path.placeName).append("\n");
            }
            if (rawIncomplete.size() > actualIncomplete.size()) {
                int joinedCount = rawIncomplete.size() - actualIncomplete.size();
                report.append("   (").append(joinedCount).append(" additional tokens correctly consumed by joins)\n");
            }
        }
        report.append("\n");
        
        // 3. Throughput
        double throughput = getWorkflowThroughput(workflowBase);
        report.append("3. THROUGHPUT\n");
        report.append("   ").append(String.format("%.2f", throughput)).append(" tokens/second\n\n");
        
        // 4. Get all places and analyze each
        report.append("4. PLACE STATISTICS\n");
        ArrayList<String> places = getAllPlaces(workflowBase);
        for (String place : places) {
            PlaceStatistics stats = getPlaceStatistics(place, workflowBase);
            report.append("   Place: ").append(place).append("\n");
            report.append("     Tokens: ").append(stats.tokenCount).append("\n");
            report.append("     Avg residence: ").append(String.format("%.1f", stats.avgResidenceTime)).append("ms\n");
            report.append("     Min/Max: ").append(stats.minResidenceTime)
                  .append("/").append(stats.maxResidenceTime).append("ms\n");
        }
        report.append("\n");
        
        // 5. Capacity verification
        report.append("5. CAPACITY VERIFICATION\n");
        for (String place : places) {
            boolean bounded = verifyBoundedCapacity(place, workflowBase, 50); // Assuming capacity=50
            report.append("   ").append(place).append(": ")
                  .append(bounded ? "[OK] BOUNDED" : "[FAIL] EXCEEDED").append("\n");
        }
        
        // 6. Data quality check
        report.append("\n6. DATA QUALITY\n");
        ArrayList<TokenPath> allPaths = getTokenPaths(workflowBase);
        long negativePaths = allPaths.stream().filter(p -> p.residenceTime < 0).count();
        if (negativePaths == 0) {
            report.append("   [OK] All residence times are non-negative\n");
        } else {
            report.append("   [WARN] ").append(negativePaths)
                  .append(" paths have negative residence time - check data integrity\n");
        }
        
        report.append("\n=== END REPORT ===\n");
        
        return report.toString();
    }
    
 // =============================================================================
 // TOKEN GENEALOGY ANALYSIS
 // =============================================================================

 /**
  * Analyze complete token genealogy (multi-generation lineage)
  * 
  * Tracks:
  * - Parent-child relationships across all generations
  * - Token family trees
  * - Generation depth
  * - Lineage paths from root to leaves
  * 
  * @param workflowBase Workflow base ID
  * @return Complete genealogy analysis
  */
 public GenealogyAnalysis analyzeGenealogy(int workflowBase) {
     GenealogyAnalysis genealogy = new GenealogyAnalysis();
     
     try (Connection conn = getConnection();
          Statement stmt = conn.createStatement()) {
         
         // Get all tokens in workflow
         String sql = "SELECT DISTINCT tokenId FROM CONSOLIDATED_TRANSITION_FIRINGS " +
                      "WHERE workflowBase = " + workflowBase + " " +
                      "ORDER BY tokenId";
         
         ResultSet rs = stmt.executeQuery(sql);
         Set<Integer> allTokens = new HashSet<>();
         
         while (rs.next()) {
             allTokens.add(rs.getInt("tokenId"));
         }
         
         logger.info("GENEALOGY: Found " + allTokens.size() + " tokens for workflow " + workflowBase);
         
         // Build genealogy tree
         Map<Integer, TokenNode> tokenNodes = new HashMap<>();
         
         for (int tokenId : allTokens) {
             TokenNode node = new TokenNode();
             node.tokenId = tokenId;
             
             // Decode parent from token ID
             int remainder = tokenId % TOKEN_INCREMENT;
             
             if (remainder >= 100) {
                 // This is a forked token
                 int parentId = tokenId - remainder;
                 node.parentTokenId = parentId;
                 node.generation = 1;  // Will be updated if parent is also forked
                 
                 // Extract fork info
                 int encoded = remainder;
                 node.joinCount = encoded / 100;
                 node.branchNumber = encoded % 100;
                 
                 genealogy.forkedTokens.add(tokenId);
             } else {
                 // Base token (root of family tree)
                 node.parentTokenId = -1;
                 node.generation = 0;
                 genealogy.rootTokens.add(tokenId);
             }
             
             tokenNodes.put(tokenId, node);
         }
         
         // Build parent-child links
         for (TokenNode node : tokenNodes.values()) {
             if (node.parentTokenId != -1 && tokenNodes.containsKey(node.parentTokenId)) {
                 TokenNode parent = tokenNodes.get(node.parentTokenId);
                 parent.children.add(node.tokenId);
                 node.generation = parent.generation + 1;
                 genealogy.maxGeneration = Math.max(genealogy.maxGeneration, node.generation);
             }
         }
         
         // Build lineage paths for each token
         for (int tokenId : allTokens) {
             ArrayList<Integer> lineage = buildLineage(tokenId, tokenNodes);
             genealogy.lineages.put(tokenId, lineage);
             
             TokenNode node = tokenNodes.get(tokenId);
             if (node != null) {
                 genealogy.tokensByGeneration.computeIfAbsent(node.generation, k -> new ArrayList<>()).add(tokenId);
             }
         }
         
         // Identify complete families
         for (int rootId : genealogy.rootTokens) {
             TokenFamily family = buildFamily(rootId, tokenNodes);
             genealogy.families.add(family);
         }
         
         genealogy.totalTokens = allTokens.size();
         genealogy.tokenNodes = tokenNodes;
         
         logger.info("GENEALOGY: " + genealogy.rootTokens.size() + " root tokens, " +
                    genealogy.forkedTokens.size() + " forked tokens, " +
                    genealogy.maxGeneration + " max generation depth");
         
     } catch (SQLException e) {
         logger.error("Error analyzing genealogy for workflow " + workflowBase, e);
     }
     
     return genealogy;
 }

 /**
  * Build lineage path from token back to root
  * Returns: [root, parent, grandparent, ..., token]
  */
 private ArrayList<Integer> buildLineage(int tokenId, Map<Integer, TokenNode> nodes) {
     ArrayList<Integer> lineage = new ArrayList<>();
     int currentId = tokenId;
     
     while (currentId != -1) {
         lineage.add(0, currentId);  // Add at beginning
         TokenNode node = nodes.get(currentId);
         if (node == null || node.parentTokenId == -1) {
             break;
         }
         currentId = node.parentTokenId;
     }
     
     return lineage;
 }

 /**
  * Build complete family tree starting from a root token
  */
 private TokenFamily buildFamily(int rootId, Map<Integer, TokenNode> nodes) {
     TokenFamily family = new TokenFamily();
     family.rootTokenId = rootId;
     
     // Traverse tree depth-first
     Set<Integer> visited = new HashSet<>();
     collectDescendants(rootId, nodes, visited, family);
     
     family.totalMembers = visited.size();
     
     return family;
 }

 /**
  * Recursively collect all descendants
  */
 private void collectDescendants(int tokenId, Map<Integer, TokenNode> nodes, 
                                  Set<Integer> visited, TokenFamily family) {
     if (visited.contains(tokenId)) {
         return;
     }
     
     visited.add(tokenId);
     family.allMembers.add(tokenId);
     
     TokenNode node = nodes.get(tokenId);
     if (node != null && !node.children.isEmpty()) {
         for (int childId : node.children) {
             family.descendants.add(childId);
             collectDescendants(childId, nodes, visited, family);
         }
     }
 }

 /**
  * Get siblings of a token (tokens with same parent)
  */
 public ArrayList<Integer> getSiblings(int tokenId, GenealogyAnalysis genealogy) {
     ArrayList<Integer> siblings = new ArrayList<>();
     
     TokenNode node = genealogy.tokenNodes.get(tokenId);
     if (node == null || node.parentTokenId == -1) {
         return siblings;  // No siblings (root token or not found)
     }
     
     TokenNode parent = genealogy.tokenNodes.get(node.parentTokenId);
     if (parent != null) {
         for (int siblingId : parent.children) {
             if (siblingId != tokenId) {
                 siblings.add(siblingId);
             }
         }
     }
     
     return siblings;
 }

 /**
  * Get all ancestors of a token
  */
 public ArrayList<Integer> getAncestors(int tokenId, GenealogyAnalysis genealogy) {
     ArrayList<Integer> ancestors = new ArrayList<>();
     
     TokenNode node = genealogy.tokenNodes.get(tokenId);
     while (node != null && node.parentTokenId != -1) {
         ancestors.add(node.parentTokenId);
         node = genealogy.tokenNodes.get(node.parentTokenId);
     }
     
     return ancestors;
 }

 /**
  * Get all descendants of a token
  */
 public ArrayList<Integer> getDescendants(int tokenId, GenealogyAnalysis genealogy) {
     ArrayList<Integer> descendants = new ArrayList<>();
     Set<Integer> visited = new HashSet<>();
     
     collectAllDescendants(tokenId, genealogy.tokenNodes, visited, descendants);
     
     return descendants;
 }

 private void collectAllDescendants(int tokenId, Map<Integer, TokenNode> nodes,
                                    Set<Integer> visited, ArrayList<Integer> descendants) {
     TokenNode node = nodes.get(tokenId);
     if (node == null || visited.contains(tokenId)) {
         return;
     }
     
     visited.add(tokenId);
     
     for (int childId : node.children) {
         descendants.add(childId);
         collectAllDescendants(childId, nodes, visited, descendants);
     }
 }

 /**
  * Print genealogy report
  */
 public void printGenealogyReport(int workflowBase) {
     GenealogyAnalysis genealogy = analyzeGenealogy(workflowBase);
     
     System.out.println("\n=== TOKEN GENEALOGY REPORT ===");
     System.out.println("Workflow Base: " + workflowBase);
     System.out.println("Total Tokens: " + genealogy.totalTokens);
     System.out.println("Root Tokens: " + genealogy.rootTokens.size());
     System.out.println("Forked Tokens: " + genealogy.forkedTokens.size());
     System.out.println("Max Generation Depth: " + genealogy.maxGeneration);
     System.out.println("Total Families: " + genealogy.families.size());
     
     // Print generation distribution
     System.out.println("\n--- Generation Distribution ---");
     for (int gen = 0; gen <= genealogy.maxGeneration; gen++) {
         ArrayList<Integer> tokens = genealogy.tokensByGeneration.get(gen);
         if (tokens != null) {
             System.out.println("Generation " + gen + ": " + tokens.size() + " tokens");
         }
     }
     
     // Print family trees
     System.out.println("\n--- Family Trees ---");
     for (TokenFamily family : genealogy.families) {
         System.out.println("\nFamily rooted at " + family.rootTokenId + ":");
         System.out.println("  Total members: " + family.totalMembers);
         System.out.println("  Direct descendants: " + family.descendants.size());
         
         // Print lineages for this family
         for (int memberId : family.allMembers) {
             ArrayList<Integer> lineage = genealogy.lineages.get(memberId);
             if (lineage.size() > 1) {  // Skip root (no lineage)
                 System.out.print("  Lineage: ");
                 for (int i = 0; i < lineage.size(); i++) {
                     System.out.print(lineage.get(i));
                     if (i < lineage.size() - 1) {
                         System.out.print(" -> ");
                     }
                 }
                 
                 TokenNode node = genealogy.tokenNodes.get(memberId);
                 if (node != null && node.generation > 0) {
                     System.out.print(" [Gen " + node.generation + 
                                    ", Fork " + node.joinCount + 
                                    ", Branch " + node.branchNumber + "]");
                 }
                 System.out.println();
             }
         }
     }
 }

 // =============================================================================
 // DATA CLASSES FOR GENEALOGY
 // =============================================================================

 /**
  * Complete genealogy analysis results
  */
 public static class GenealogyAnalysis {
     public int totalTokens;
     public int maxGeneration;
     public Set<Integer> rootTokens = new HashSet<>();
     public Set<Integer> forkedTokens = new HashSet<>();
     public Map<Integer, ArrayList<Integer>> tokensByGeneration = new HashMap<>();
     public Map<Integer, ArrayList<Integer>> lineages = new HashMap<>();
     public ArrayList<TokenFamily> families = new ArrayList<>();
     public Map<Integer, TokenNode> tokenNodes = new HashMap<>();
     
     @Override
     public String toString() {
         return "GenealogyAnalysis[tokens=" + totalTokens + 
                ", roots=" + rootTokens.size() + 
                ", forked=" + forkedTokens.size() + 
                ", maxGen=" + maxGeneration + 
                ", families=" + families.size() + "]";
     }
 }

 /**
  * A token in the genealogy tree
  */
 public static class TokenNode {
     public int tokenId;
     public int parentTokenId = -1;
     public int generation = 0;
     public int joinCount = 0;
     public int branchNumber = 0;
     public ArrayList<Integer> children = new ArrayList<>();
     
     @Override
     public String toString() {
         return "TokenNode[id=" + tokenId + 
                ", parent=" + (parentTokenId != -1 ? parentTokenId : "root") + 
                ", gen=" + generation + 
                ", children=" + children.size() + "]";
     }
 }

 /**
  * A complete family tree (root + all descendants)
  */
 public static class TokenFamily {
     public int rootTokenId;
     public int totalMembers;
     public ArrayList<Integer> allMembers = new ArrayList<>();
     public ArrayList<Integer> descendants = new ArrayList<>();
     
     @Override
     public String toString() {
         return "TokenFamily[root=" + rootTokenId + 
                ", members=" + totalMembers + 
                ", descendants=" + descendants.size() + "]";
     }
 }
    
    /**
     * Print workflow report to console
     */
    public void printWorkflowReport(int workflowBase) {
        String report = generateWorkflowReport(workflowBase);
        System.out.println(report);
        logger.info("Generated workflow report for workflowBase=" + workflowBase);
    }
    
    // =============================================================================
    // HELPER METHODS
    // =============================================================================
    
    private ArrayList<String> getAllPlaces(int workflowBase) {
        ArrayList<String> places = new ArrayList<>();
        
        String sql = 
            "SELECT DISTINCT toPlace " +
            "FROM CONSOLIDATED_TRANSITION_FIRINGS " +
            "WHERE workflowBase = ? " +
            "  AND toPlace IS NOT NULL AND toPlace != '' " +
            "ORDER BY toPlace";
        
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setInt(1, workflowBase);
            ResultSet rs = pstmt.executeQuery();
            
            while (rs.next()) {
                places.add(rs.getString("toPlace"));
            }
            
        } catch (SQLException e) {
            logger.error("Error getting places", e);
        }
        
        return places;
    }
    
    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(DB_URL);
    }
    
    // =============================================================================
    // DATA CLASSES
    // =============================================================================
    
    public static class TokenPath {
        public int tokenId;
        public String placeName;
        public long entryTime;
        public long exitTime;
        public long residenceTime;
        
        @Override
        public String toString() {
            return "Token " + tokenId + " at " + placeName + 
                   ": entry=" + entryTime + ", exit=" + exitTime + 
                   ", residence=" + residenceTime + "ms";
        }
    }
    
    public static class PlaceStatistics {
        public String placeName;
        public int tokenCount;
        public double avgResidenceTime;
        public long minResidenceTime;
        public long maxResidenceTime;
        
        @Override
        public String toString() {
            return placeName + ": " + tokenCount + " tokens, " +
                   "avg=" + String.format("%.1f", avgResidenceTime) + "ms, " +
                   "min=" + minResidenceTime + "ms, max=" + maxResidenceTime + "ms";
        }
    }
    
    public static class MarkingSnapshot {
        public int tokenId;
        public long timestamp;
        public int marking;
        public int bufferSize;
    }
    
    public static class InterArrival {
        public int tokenId;
        public long timestamp;
        public long interArrivalTime;
    }
    
    /**
     * Fork/Join analysis results
     */
    public static class ForkJoinAnalysis {
        public int totalForks = 0;
        public int successfulJoins = 0;
        public Set<Integer> baseTokens = new HashSet<>();
        public Set<Integer> forkedTokens = new HashSet<>();
        public ArrayList<ForkGroup> forkGroups = new ArrayList<>();
        
        @Override
        public String toString() {
            return "ForkJoinAnalysis[forks=" + totalForks + ", joins=" + successfulJoins + 
                   ", forkedTokens=" + forkedTokens.size() + "]";
        }
    }
    
    /**
     * A group of sibling tokens from a single fork
     */
    public static class ForkGroup {
        public int parentTokenId;
        public ArrayList<Integer> childTokenIds = new ArrayList<>();
        public int expectedCount;
        public ArrayList<Integer> completedChildren = new ArrayList<>();  // Exited workflow
        public ArrayList<Integer> joinedChildren = new ArrayList<>();      // Consumed by join
        public boolean joinSuccessful = false;
        
        @Override
        public String toString() {
            return "ForkGroup[parent=" + parentTokenId + ", children=" + childTokenIds + 
                   ", joined=" + joinSuccessful + "]";
        }
    }
    
    // =============================================================================
    // MAIN - FOR TESTING
    // =============================================================================
    
    public static void main(String[] args) {
        PetriNetAnalyzer analyzer = new PetriNetAnalyzer();
        
        // Default workflow base (new: 1000000, old: 100000)
        int workflowBase = 1000000;
        
        // Allow override from command line
        if (args.length > 0) {
            try {
                workflowBase = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid workflow base: " + args[0] + ", using default: " + workflowBase);
            }
        }
        
        // Print full report (now includes fork/join analysis)
        analyzer.printWorkflowReport(workflowBase);
        analyzer.printGenealogyReport(workflowBase);
        
        // Print priority analysis (cross-version comparison)
        analyzer.printPriorityReport();
        
        // Get detailed token paths
        System.out.println("\n=== TOKEN PATHS ===");
        ArrayList<TokenPath> paths = analyzer.getTokenPaths(workflowBase);
        for (TokenPath path : paths) {
            System.out.println("Token " + path.tokenId + " at " + path.placeName + 
                             ": residence=" + path.residenceTime + "ms");
        }
        
        // Get marking evolution
        System.out.println("\n=== MARKING EVOLUTION ===");
        ArrayList<MarkingSnapshot> markings = analyzer.getMarkingEvolution("P1_Place", workflowBase);
        for (MarkingSnapshot snap : markings) {
            System.out.println("Time=" + snap.timestamp + 
                             " Token=" + snap.tokenId + 
                             " Marking=" + snap.marking + 
                             " Buffer=" + snap.bufferSize);
        }
    }
}