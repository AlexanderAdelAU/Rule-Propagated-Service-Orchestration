package org.btsn.derby.Analysis;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.log4j.Logger;
import org.btsn.derby.Analysis.BuildServiceAnalysisDatabase;
import org.btsn.constants.VersionConstants;

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
 * FORK/JOIN TOKEN ID ENCODING (NEW - Simple):
 * Fork creates child tokens with simple encoding:
 *   childTokenId = parentTokenId + branchNumber
 * Example: Parent 1000000 with 2-way fork -> 1000001, 1000002
 * branchNumber is 1-99, allowing up to 99 branches per fork.
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
    
    // Fork/Join token ID encoding constants - use centralized VersionConstants
    private static final int TOKEN_INCREMENT = VersionConstants.TOKEN_INCREMENT;
    
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
        
        // This is an event-pairing diagnostic, not workflow completeness.
        // A logical place execution begins only at ENTER. BUFFERED is queue state.
        // Match the subsequent exit by physical place name instead of constructing
        // T_out_<toPlace>, because topology labels (P1) and service names
        // (P1_Place) intentionally differ.
        String sql = 
            "SELECT t_in.tokenId, t_in.timestamp as entryTime, t_in.toPlace " +
            "FROM CONSOLIDATED_TRANSITION_FIRINGS t_in " +
            "WHERE t_in.workflowBase = ? " +
            "  AND t_in.eventType = 'ENTER' " +
            "  AND NOT EXISTS ( " +
            "      SELECT 1 FROM CONSOLIDATED_TRANSITION_FIRINGS t_out " +
            "      WHERE t_out.tokenId = t_in.tokenId " +
            "        AND t_out.workflowBase = t_in.workflowBase " +
            "        AND t_out.fromPlace = t_in.toPlace " +
            "        AND t_out.eventType IN ('EXIT', 'TERMINATE') " +
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
                path.exitTime = 0;
                path.placeName = rs.getString("toPlace");
                incompletePaths.add(path);
            }
            
            logger.info("Place ENTER/EXIT pairing check: " + 
                       (incompletePaths.isEmpty() ? "all ENTER events paired" : 
                        incompletePaths.size() + " unpaired ENTER events found"));
            
        } catch (SQLException e) {
            logger.error("Error checking place event pairing", e);
        }
        
        return incompletePaths;
    }
    
    /**
     * Get tokens that were routed to TERMINATE (normal termination)
     * These are tokens that entered the workflow and exited via TERMINATE.
     * 
     * NOTE: Termination records may be in CONSOLIDATED_TRANSITION_FIRINGS or in
     * TRANSITION_FIRINGS (for observer services like MonitorService). We check both
     * using a UNION query.
     */
    public ArrayList<Integer> getTerminatedTokens(int workflowBase) {
        ArrayList<Integer> terminatedTokens = new ArrayList<>();
        
        // Only consolidated business-place events belong to the analyzed workflow.
        // The Monitor's raw TRANSITION_FIRINGS table also contains collection/admin
        // operations such as writeCollectorData and must not be interpreted as
        // business workflow termination.
        String sql = 
            "SELECT DISTINCT tokenId FROM CONSOLIDATED_TRANSITION_FIRINGS " +
            "WHERE workflowBase = ? " +
            "  AND (eventType = 'TERMINATE' OR toPlace = 'TERMINATE') " +
            "ORDER BY tokenId";
        
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setInt(1, workflowBase);
            ResultSet rs = pstmt.executeQuery();
            
            while (rs.next()) {
                terminatedTokens.add(rs.getInt("tokenId"));
            }
            
            logger.info("Found " + terminatedTokens.size() + " business TERMINATE events");
            
        } catch (SQLException e) {
            logger.error("Error getting terminated tokens", e);
        }
        
        return terminatedTokens;
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
        
        String sql = 
            "SELECT t_in.tokenId, t_in.toPlace, " +
            "       t_in.timestamp as entryTime, " +
            "       t_out.timestamp as exitTime " +
            "FROM CONSOLIDATED_TRANSITION_FIRINGS t_in " +
            "JOIN CONSOLIDATED_TRANSITION_FIRINGS t_out " +
            "  ON t_in.tokenId = t_out.tokenId " +
            "  AND t_in.workflowBase = t_out.workflowBase " +
            "  AND t_out.fromPlace = t_in.toPlace " +
            "  AND t_out.eventType IN ('EXIT', 'TERMINATE') " +
            "  AND t_out.timestamp >= t_in.timestamp " +
            "WHERE t_in.workflowBase = ? " +
            "  AND t_in.eventType = 'ENTER' " +
            "  AND NOT EXISTS ( " +
            "      SELECT 1 FROM CONSOLIDATED_TRANSITION_FIRINGS t_between " +
            "      WHERE t_between.tokenId = t_in.tokenId " +
            "        AND t_between.workflowBase = t_in.workflowBase " +
            "        AND t_between.fromPlace = t_in.toPlace " +
            "        AND t_between.eventType IN ('EXIT', 'TERMINATE') " +
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
            
            logger.info("Retrieved " + paths.size() + " logical place paths for workflowBase=" + workflowBase);
            
            long negativeCount = paths.stream().filter(p -> p.residenceTime < 0).count();
            if (negativeCount > 0) {
                logger.warn("WARNING: Found " + negativeCount + " paths with negative residence time");
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
     * NEW Token ID encoding: childTokenId = parentTokenId + branchNumber
     * Example: 1000001 -> parent=1000000, branch=1
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
                
                // NEW ENCODING: childTokenId = parentTokenId + branchNumber
                // branchNumber is simply tokenId % 100 (1, 2, 3... for branches, 0 for parent)
                int branchNumber = tokenId % 100;
                int parentTokenId = tokenId - branchNumber;
                
                // Check if this is a forked token (branchNumber >= 1)
                boolean isForkedToken = (branchNumber >= 1);
                
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
                    // In a proper JOIN with base token reset:
                    // - ALL children are consumed by the join (none complete individually)
                    // - The PARENT (base) token continues after the join
                    for (int childId : children) {
                        if (hasExitedWorkflow(childId, workflowBase)) {
                            group.completedChildren.add(childId);
                        } else {
                            group.joinedChildren.add(childId);
                        }
                    }
                    
                    // A successful join can happen in two scenarios:
                    //
                    // SCENARIO 1 (Legacy - one child survives):
                    //   One child completed (the "winning" sibling), others were joined
                    //   completedChildren.size() == 1 && joinedChildren.size() == expectedCount - 1
                    //
                    // SCENARIO 2 (Base token reset - preferred):
                    //   ALL children are consumed by the join (none complete individually)
                    //   The PARENT token continues after the join and eventually terminates
                    //   completedChildren.size() == 0 && joinedChildren.size() == expectedCount
                    //   AND parent token has exited/terminated
                    
                    boolean legacyJoin = (group.completedChildren.size() == 1 && 
                                         group.joinedChildren.size() == group.expectedCount - 1);
                    
                    boolean baseTokenResetJoin = (group.completedChildren.size() == 0 && 
                                                  group.joinedChildren.size() == group.expectedCount &&
                                                  hasExitedWorkflow(parentId, workflowBase));
                    
                    group.joinSuccessful = legacyJoin || baseTokenResetJoin;
                    
                    if (baseTokenResetJoin) {
                        logger.debug("Join " + parentId + ": Base token reset pattern - all " + 
                                   group.expectedCount + " children consumed, parent exited");
                    }
                    
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
     * IMPORTANT: For forked child tokens (like 1000201, 1000202), the T_in/T_out count
     * check is misleading because:
     * - FORK creates a T_out record for the child (child "exits" from fork place)
     * - Child enters intermediate place (T_in)
     * - Child exits intermediate place (T_out) heading to JOIN
     * - Child enters JOIN place (T_in)
     * - At JOIN, the BASE token gets the T_out, not the child
     * 
     * So a properly joined child has T_in == T_out (e.g., 2 each), but this doesn't
     * mean it "completed" - it was consumed by the join!
     * 
     * For forked tokens, we check if their LAST entry was to a place where the
     * PARENT token has a T_out (indicating the join fired and parent continued).
     * 
     * NOTE: Termination records may be in CONSOLIDATED_TRANSITION_FIRINGS or in
     * TRANSITION_FIRINGS (for observer services like MonitorService). We check both.
     */
    private boolean hasExitedWorkflow(int tokenId, int workflowBase) {
        // First check if this is a forked token
        boolean isForkedToken = isForkedChildToken(tokenId);
        
        if (isForkedToken) {
            // For forked tokens, check if they were consumed by a join
            // A forked token is "consumed" (not exited) if:
            // - Its last T_in was to a place where the PARENT token has a T_out
            return hasForkedTokenExited(tokenId, workflowBase);
        }
        
        // For base tokens, use the standard check
        return hasBaseTokenExited(tokenId, workflowBase);
    }
    
    /**
     * Check if a token ID represents a forked child token
     * NEW ENCODING: childTokenId = parentTokenId + branchNumber
     * branchNumber is 1-99, so forked tokens have (tokenId % 100) >= 1
     * e.g., 1000001 = parent 1000000 + branch 1
     */
    private boolean isForkedChildToken(int tokenId) {
        int branchNumber = tokenId % 100;
        // Forked tokens have branchNumber >= 1 (e.g., 1, 2, 3...)
        return branchNumber >= 1;
    }
    
    /**
     * Extract parent token ID from a forked child token
     * NEW ENCODING: parentTokenId = childTokenId - branchNumber
     * e.g., 1000001 -> 1000000, 1000002 -> 1000000
     */
    private int getParentTokenId(int childTokenId) {
        int branchNumber = childTokenId % 100;
        return childTokenId - branchNumber;
    }
    
    /**
     * Check if a forked child token has truly exited the workflow
     * (not just consumed by a join)
     * 
     * A forked token has "exited" only if it reached TERMINATE.
     * If T_in == T_out but no TERMINATE, it was consumed by a join.
     */
    private boolean hasForkedTokenExited(int tokenId, int workflowBase) {
        // For forked tokens, only TERMINATE counts as "exited"
        // Equal T_in/T_out means consumed by join, not completed
        String sql = 
            "SELECT " +
            "  (SELECT COUNT(*) FROM CONSOLIDATED_TRANSITION_FIRINGS " +
            "   WHERE tokenId = ? AND workflowBase = ? AND toPlace = 'TERMINATE') as consolidatedTerminateCount, " +
            "  (SELECT COUNT(*) FROM TRANSITION_FIRINGS " +
            "   WHERE tokenId = ? AND workflowBase = ? AND toPlace = 'TERMINATE') as rawTerminateCount " +
            "FROM SYSIBM.SYSDUMMY1";
        
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setInt(1, tokenId);
            pstmt.setInt(2, workflowBase);
            pstmt.setInt(3, tokenId);
            pstmt.setInt(4, workflowBase);
            
            ResultSet rs = pstmt.executeQuery();
            
            if (rs.next()) {
                int terminateCount = rs.getInt("consolidatedTerminateCount") + rs.getInt("rawTerminateCount");
                
                // Forked token only "exits" if it reached TERMINATE
                boolean exited = (terminateCount > 0);
                
                logger.debug("Forked token " + tokenId + " exit check: TERMINATE=" + terminateCount + 
                           " -> exited=" + exited + " (forked tokens only exit via TERMINATE)");
                
                return exited;
            }
            
        } catch (SQLException e) {
            logger.error("Error checking forked token exit for tokenId=" + tokenId, e);
        }
        
        return false;
    }
    
    /**
     * Check if a base (non-forked) token has exited the workflow
     */
    private boolean hasBaseTokenExited(int tokenId, int workflowBase) {
        // Check CONSOLIDATED_TRANSITION_FIRINGS for place-based counts
        // and check BOTH tables for TERMINATE records (Monitor writes to TRANSITION_FIRINGS)
        String sql = 
            "SELECT " +
            "  (SELECT COUNT(*) FROM CONSOLIDATED_TRANSITION_FIRINGS " +
            "   WHERE tokenId = ? AND workflowBase = ? AND transitionId LIKE 'T_in_%') as inCount, " +
            "  (SELECT COUNT(*) FROM CONSOLIDATED_TRANSITION_FIRINGS " +
            "   WHERE tokenId = ? AND workflowBase = ? AND transitionId LIKE 'T_out_%') as outCount, " +
            "  (SELECT COUNT(*) FROM CONSOLIDATED_TRANSITION_FIRINGS " +
            "   WHERE tokenId = ? AND workflowBase = ? AND toPlace = 'TERMINATE') as consolidatedTerminateCount, " +
            "  (SELECT COUNT(*) FROM TRANSITION_FIRINGS " +
            "   WHERE tokenId = ? AND workflowBase = ? AND toPlace = 'TERMINATE') as rawTerminateCount " +
            "FROM SYSIBM.SYSDUMMY1";  // Derby syntax for SELECT without table
        
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setInt(1, tokenId);
            pstmt.setInt(2, workflowBase);
            pstmt.setInt(3, tokenId);
            pstmt.setInt(4, workflowBase);
            pstmt.setInt(5, tokenId);
            pstmt.setInt(6, workflowBase);
            pstmt.setInt(7, tokenId);
            pstmt.setInt(8, workflowBase);
            
            ResultSet rs = pstmt.executeQuery();
            
            if (rs.next()) {
                int inCount = rs.getInt("inCount");
                int outCount = rs.getInt("outCount");
                int consolidatedTerminateCount = rs.getInt("consolidatedTerminateCount");
                int rawTerminateCount = rs.getInt("rawTerminateCount");
                
                // Total terminate count from both tables
                int terminateCount = consolidatedTerminateCount + rawTerminateCount;
                
                // Base token exited if:
                // 1. It reached TERMINATE (in either table), or
                // 2. Every place it entered, it also exited (inCount == outCount)
                boolean exited = (terminateCount > 0) || (inCount > 0 && inCount == outCount);
                
                logger.debug("Base token " + tokenId + " exit check: T_in=" + inCount + 
                           ", T_out=" + outCount + ", TERMINATE=" + terminateCount + 
                           " (consolidated=" + consolidatedTerminateCount + ", raw=" + rawTerminateCount + ")" +
                           " -> exited=" + exited);
                
                return exited;
            }
            
        } catch (SQLException e) {
            logger.error("Error checking base token exit for tokenId=" + tokenId, e);
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
        PlaceStatistics stats = computePlaceStatisticsFromFirings(placeName, workflowBase);
        logger.info("Place " + placeName + " statistics: " +
                   stats.tokenCount + " paired logical visits, avg=" +
                   String.format("%.1f", stats.avgResidenceTime) + "ms");
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
        
        String sql = 
            "SELECT COUNT(*) as tokenCount, " +
            "       AVG(t_out.timestamp - t_in.timestamp) as avgResidence, " +
            "       MIN(t_out.timestamp - t_in.timestamp) as minResidence, " +
            "       MAX(t_out.timestamp - t_in.timestamp) as maxResidence " +
            "FROM CONSOLIDATED_TRANSITION_FIRINGS t_in " +
            "JOIN CONSOLIDATED_TRANSITION_FIRINGS t_out " +
            "  ON t_in.tokenId = t_out.tokenId " +
            "  AND t_in.workflowBase = t_out.workflowBase " +
            "  AND t_out.fromPlace = t_in.toPlace " +
            "  AND t_out.eventType IN ('EXIT', 'TERMINATE') " +
            "  AND t_out.timestamp >= t_in.timestamp " +
            "WHERE t_in.workflowBase = ? " +
            "  AND t_in.toPlace = ? " +
            "  AND t_in.eventType = 'ENTER' " +
            "  AND NOT EXISTS ( " +
            "      SELECT 1 FROM CONSOLIDATED_TRANSITION_FIRINGS t_between " +
            "      WHERE t_between.tokenId = t_in.tokenId " +
            "        AND t_between.workflowBase = t_in.workflowBase " +
            "        AND t_between.fromPlace = t_in.toPlace " +
            "        AND t_between.eventType IN ('EXIT', 'TERMINATE') " +
            "        AND t_between.timestamp > t_in.timestamp " +
            "        AND t_between.timestamp < t_out.timestamp " +
            "  )";
        
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setInt(1, workflowBase);
            pstmt.setString(2, placeName);
            ResultSet rs = pstmt.executeQuery();
            
            if (rs.next()) {
                stats.tokenCount = rs.getInt("tokenCount");
                stats.avgResidenceTime = rs.getDouble("avgResidence");
                stats.minResidenceTime = rs.getLong("minResidence");
                stats.maxResidenceTime = rs.getLong("maxResidence");
            }
            
        } catch (SQLException e) {
            logger.error("Error computing place statistics from lifecycle events", e);
        }
        
        return stats;
    }
    
    /**
     * Get canonical workflow completion throughput.
     *
     * Throughput is completed workflow instances divided by the observation
     * window from the first GENERATED root to the last canonical completion.
     */
    public double getWorkflowThroughput(int workflowBase) {
        CanonicalWorkflowAnalysis canonical = analyzeCanonicalWorkflows(workflowBase);
        long firstGenerated = Long.MAX_VALUE;
        long lastCompleted = Long.MIN_VALUE;
        int timedCompletions = 0;

        for (WorkflowInstanceSummary instance : canonical.instances.values()) {
            if (instance.generatedAt > 0) {
                firstGenerated = Math.min(firstGenerated, instance.generatedAt);
            }
            if (instance.completedAt > 0) {
                lastCompleted = Math.max(lastCompleted, instance.completedAt);
                timedCompletions++;
            }
        }

        if (timedCompletions > 0 && firstGenerated != Long.MAX_VALUE && lastCompleted > firstGenerated) {
            long duration = lastCompleted - firstGenerated;
            double throughput = (timedCompletions * 1000.0) / duration;
            logger.info("Canonical workflow throughput: " +
                        String.format("%.3f", throughput) + " workflows/sec");
            return throughput;
        }

        return 0.0;
    }

    // =============================================================================
    // STAGE 2 - TEMPORAL ANALYSIS
    // =============================================================================

    /**
     * Analyze temporal behaviour using the Stage-1 canonical workflow model.
     *
     * Workflow latency is GENERATED -> canonical completion.
     * Place residence is ENTER -> EXIT/TERMINATE.
     * Queue/service timing comes from SERVICECONTRIBUTION.
     * Join synchronization is measured separately from place residence.
     */
    public TemporalAnalysis analyzeTemporal(int workflowBase) {
        TemporalAnalysis analysis = new TemporalAnalysis();
        analysis.workflowBase = workflowBase;

        CanonicalWorkflowAnalysis canonical = analyzeCanonicalWorkflows(workflowBase);
        analysis.expectedCompletedWorkflows = canonical.completedWorkflows;

        ArrayList<Long> workflowLatencies = new ArrayList<>();
        ArrayList<Long> generatedTimes = new ArrayList<>();
        long firstGenerated = Long.MAX_VALUE;
        long lastCompleted = Long.MIN_VALUE;

        for (WorkflowInstanceSummary instance : canonical.instances.values()) {
            if (instance.generatedAt > 0) {
                generatedTimes.add(instance.generatedAt);
                firstGenerated = Math.min(firstGenerated, instance.generatedAt);
            }
            if (instance.generatedAt > 0 && instance.completedAt > 0) {
                workflowLatencies.add(instance.completedAt - instance.generatedAt);
                lastCompleted = Math.max(lastCompleted, instance.completedAt);
            }
        }

        analysis.workflowLatency = buildTimingDistribution(workflowLatencies);

        Collections.sort(generatedTimes);
        ArrayList<Long> generationInterArrivals = new ArrayList<>();
        for (int i = 1; i < generatedTimes.size(); i++) {
            generationInterArrivals.add(generatedTimes.get(i) - generatedTimes.get(i - 1));
        }
        analysis.generationInterArrival = buildTimingDistribution(generationInterArrivals);

        if (analysis.workflowLatency.samples > 0 &&
            firstGenerated != Long.MAX_VALUE &&
            lastCompleted > firstGenerated) {
            analysis.observationWindowMs = lastCompleted - firstGenerated;
            analysis.completionThroughputPerSecond =
                (analysis.workflowLatency.samples * 1000.0) / analysis.observationWindowMs;
        }

        Map<String, ArrayList<Long>> residenceByPlace = new TreeMap<>();
        for (TokenPath tokenPath : getTokenPaths(workflowBase)) {
            residenceByPlace
                .computeIfAbsent(tokenPath.placeName, k -> new ArrayList<>())
                .add(tokenPath.residenceTime);
        }
        for (Map.Entry<String, ArrayList<Long>> entry : residenceByPlace.entrySet()) {
            analysis.placeResidence.put(entry.getKey(), buildTimingDistribution(entry.getValue()));
        }

        try (Connection conn = getConnection()) {
            ArrayList<ServiceContribution> contributions = new ArrayList<>();
            String timingSql =
                "SELECT WORKFLOWBASE, SEQUENCEID, ARRIVALTIME, QUEUETIME, SERVICETIME, " +
                "       TOTALTIME, BUFFERSIZE " +
                "FROM SERVICECONTRIBUTION " +
                "WHERE WORKFLOWBASE = ? " +
                "ORDER BY ARRIVALTIME";
            try (PreparedStatement pstmt = conn.prepareStatement(timingSql)) {
                pstmt.setInt(1, workflowBase);
                ResultSet rs = pstmt.executeQuery();
                while (rs.next()) {
                    ServiceContribution sc = new ServiceContribution();
                    sc.workflowBase = rs.getLong("WORKFLOWBASE");
                    sc.sequenceId = rs.getInt("SEQUENCEID");
                    sc.arrivalTime = rs.getLong("ARRIVALTIME");
                    sc.queueTime = rs.getLong("QUEUETIME");
                    sc.serviceTime = rs.getLong("SERVICETIME");
                    sc.totalTime = rs.getLong("TOTALTIME");
                    sc.bufferSize = rs.getInt("BUFFERSIZE");
                    contributions.add(sc);
                }
            }

            correlatePlaceNames(conn, contributions);

            Map<String, ArrayList<Long>> queueByPlace = new TreeMap<>();
            Map<String, ArrayList<Long>> serviceByPlace = new TreeMap<>();
            Map<String, ArrayList<Long>> totalByPlace = new TreeMap<>();

            for (ServiceContribution sc : contributions) {
                if (sc.placeName == null) {
                    analysis.unmatchedServiceTimingSamples++;
                    continue;
                }
                queueByPlace.computeIfAbsent(sc.placeName, k -> new ArrayList<>()).add(sc.queueTime);
                serviceByPlace.computeIfAbsent(sc.placeName, k -> new ArrayList<>()).add(sc.serviceTime);
                totalByPlace.computeIfAbsent(sc.placeName, k -> new ArrayList<>()).add(sc.totalTime);
            }

            Set<String> timedPlaces = new HashSet<>();
            timedPlaces.addAll(queueByPlace.keySet());
            timedPlaces.addAll(serviceByPlace.keySet());
            timedPlaces.addAll(totalByPlace.keySet());

            for (String place : timedPlaces) {
                ServiceTimingSummary summary = new ServiceTimingSummary();
                summary.placeName = place;
                summary.queueTime = buildTimingDistribution(
                    queueByPlace.getOrDefault(place, new ArrayList<Long>()));
                summary.serviceTime = buildTimingDistribution(
                    serviceByPlace.getOrDefault(place, new ArrayList<Long>()));
                summary.totalTime = buildTimingDistribution(
                    totalByPlace.getOrDefault(place, new ArrayList<Long>()));
                analysis.serviceTiming.put(place, summary);
            }

            Map<Integer, Integer> parentByChild = new HashMap<>();
            String genealogySql =
                "SELECT DISTINCT parentTokenId, childTokenId " +
                "FROM CONSOLIDATED_TOKEN_GENEALOGY " +
                "WHERE workflowBase = ?";
            try (PreparedStatement pstmt = conn.prepareStatement(genealogySql)) {
                pstmt.setInt(1, workflowBase);
                ResultSet rs = pstmt.executeQuery();
                while (rs.next()) {
                    parentByChild.put(rs.getInt("childTokenId"), rs.getInt("parentTokenId"));
                }
            }

            Map<String, JoinTemporalAccumulator> joinGroups = new HashMap<>();
            String joinSql =
                "SELECT tokenId, transitionId, timestamp, toPlace, eventType " +
                "FROM CONSOLIDATED_TRANSITION_FIRINGS " +
                "WHERE workflowBase = ? " +
                "  AND eventType IN ('BUFFERED', 'ENTER', 'JOIN_CONSUMED') " +
                "ORDER BY timestamp";
            try (PreparedStatement pstmt = conn.prepareStatement(joinSql)) {
                pstmt.setInt(1, workflowBase);
                ResultSet rs = pstmt.executeQuery();
                while (rs.next()) {
                    int tokenId = rs.getInt("tokenId");
                    int root = resolveRootToken(tokenId, parentByChild);
                    String transitionId = rs.getString("transitionId");
                    String eventType = rs.getString("eventType");
                    String key = root + "|" + transitionId;

                    JoinTemporalAccumulator acc =
                        joinGroups.computeIfAbsent(key, k -> new JoinTemporalAccumulator());
                    acc.transitionId = transitionId;

                    long timestamp = rs.getLong("timestamp");
                    if ("BUFFERED".equals(eventType)) {
                        acc.bufferedTokens.add(tokenId);
                        acc.firstBuffered = Math.min(acc.firstBuffered, timestamp);
                        acc.lastBuffered = Math.max(acc.lastBuffered, timestamp);
                        String place = rs.getString("toPlace");
                        if (place != null && !"JOIN_CONSUMED".equals(place)) {
                            acc.placeName = place;
                        }
                    } else if ("ENTER".equals(eventType)) {
                        if (acc.enterAt == 0 || timestamp < acc.enterAt) {
                            acc.enterAt = timestamp;
                        }
                        String place = rs.getString("toPlace");
                        if (place != null && !"JOIN_CONSUMED".equals(place)) {
                            acc.placeName = place;
                        }
                    } else if ("JOIN_CONSUMED".equals(eventType)) {
                        acc.consumedCount++;
                    }
                }
            }

            Map<String, ArrayList<Long>> waitByJoin = new TreeMap<>();
            Map<String, ArrayList<Long>> skewByJoin = new TreeMap<>();
            Map<String, String> placeByJoin = new HashMap<>();

            for (JoinTemporalAccumulator acc : joinGroups.values()) {
                if (acc.consumedCount == 0 ||
                    acc.bufferedTokens.size() < 2 ||
                    acc.firstBuffered == Long.MAX_VALUE ||
                    acc.lastBuffered == Long.MIN_VALUE ||
                    acc.enterAt == 0) {
                    continue;
                }

                long wait = acc.enterAt - acc.firstBuffered;
                long skew = acc.lastBuffered - acc.firstBuffered;
                waitByJoin.computeIfAbsent(acc.transitionId, k -> new ArrayList<>()).add(wait);
                skewByJoin.computeIfAbsent(acc.transitionId, k -> new ArrayList<>()).add(skew);
                placeByJoin.put(acc.transitionId, acc.placeName);
            }

            for (String transitionId : waitByJoin.keySet()) {
                JoinTimingSummary summary = new JoinTimingSummary();
                summary.transitionId = transitionId;
                summary.placeName = placeByJoin.get(transitionId);
                summary.synchronizationWait = buildTimingDistribution(waitByJoin.get(transitionId));
                summary.arrivalSkew = buildTimingDistribution(
                    skewByJoin.getOrDefault(transitionId, new ArrayList<Long>()));
                analysis.joinTiming.put(transitionId, summary);
            }

        } catch (SQLException e) {
            logger.error("Error performing Stage-2 temporal analysis for workflowBase=" + workflowBase, e);
        }

        return analysis;
    }

    private TimingDistribution buildTimingDistribution(List<Long> values) {
        TimingDistribution distribution = new TimingDistribution();
        ArrayList<Long> valid = new ArrayList<>();

        for (Long value : values) {
            if (value == null || value < 0) {
                distribution.invalidSamples++;
            } else {
                valid.add(value);
            }
        }

        Collections.sort(valid);
        distribution.samples = valid.size();
        if (valid.isEmpty()) {
            return distribution;
        }

        long total = 0;
        for (long value : valid) {
            total += value;
        }

        distribution.minMs = valid.get(0);
        distribution.maxMs = valid.get(valid.size() - 1);
        distribution.avgMs = (double) total / valid.size();
        distribution.p50Ms = nearestRank(valid, 0.50);
        distribution.p95Ms = nearestRank(valid, 0.95);
        return distribution;
    }

    private long nearestRank(List<Long> sortedValues, double percentile) {
        if (sortedValues.isEmpty()) {
            return 0;
        }
        int rank = (int) Math.ceil(percentile * sortedValues.size());
        int index = Math.max(0, Math.min(sortedValues.size() - 1, rank - 1));
        return sortedValues.get(index);
    }

    public String generateTemporalReport(int workflowBase) {
        TemporalAnalysis temporal = analyzeTemporal(workflowBase);
        StringBuilder report = new StringBuilder();

        report.append("=== STAGE 2 TEMPORAL ANALYSIS ===\n");
        report.append("Workflow Base: ").append(workflowBase).append("\n\n");

        report.append("1. END-TO-END WORKFLOW LATENCY\n");
        report.append("   Canonical completed workflows: ")
              .append(temporal.expectedCompletedWorkflows).append("\n");
        report.append("   Timed completion samples:      ")
              .append(temporal.workflowLatency.samples).append("\n");
        if (temporal.workflowLatency.samples > 0) {
            appendDistribution(report, "   Latency", temporal.workflowLatency);
            report.append("   Observation window: ")
                  .append(temporal.observationWindowMs).append("ms\n");
            report.append("   Completion throughput: ")
                  .append(String.format("%.3f", temporal.completionThroughputPerSecond))
                  .append(" workflows/sec\n");
        }
        if (temporal.workflowLatency.samples != temporal.expectedCompletedWorkflows) {
            report.append("   [WARN] Not every canonical completion has a usable completion timestamp\n");
        }
        report.append("\n");

        report.append("2. WORKLOAD GENERATION\n");
        appendDistribution(report, "   Root inter-arrival", temporal.generationInterArrival);
        report.append("\n");

        report.append("3. PLACE RESIDENCE DISTRIBUTIONS\n");
        for (Map.Entry<String, TimingDistribution> entry : temporal.placeResidence.entrySet()) {
            appendDistribution(report, "   " + entry.getKey(), entry.getValue());
        }
        report.append("\n");

        report.append("4. SERVICE EXECUTION TIMING\n");
        for (Map.Entry<String, ServiceTimingSummary> entry : temporal.serviceTiming.entrySet()) {
            ServiceTimingSummary timing = entry.getValue();
            report.append("   Place: ").append(entry.getKey()).append("\n");
            appendDistribution(report, "     Queue", timing.queueTime);
            appendDistribution(report, "     Service", timing.serviceTime);
            appendDistribution(report, "     Total", timing.totalTime);
        }
        report.append("   Unmatched service timing samples: ")
              .append(temporal.unmatchedServiceTimingSamples).append("\n\n");

        report.append("5. JOIN SYNCHRONIZATION\n");
        if (temporal.joinTiming.isEmpty()) {
            report.append("   No completed joins observed\n");
        } else {
            for (JoinTimingSummary join : temporal.joinTiming.values()) {
                report.append("   ").append(join.transitionId);
                if (join.placeName != null) {
                    report.append(" -> ").append(join.placeName);
                }
                report.append("\n");
                appendDistribution(report, "     First-arrival to ENTER", join.synchronizationWait);
                appendDistribution(report, "     Branch arrival skew", join.arrivalSkew);
            }
        }
        report.append("\n");

        int invalidSamples = temporal.workflowLatency.invalidSamples +
                             temporal.generationInterArrival.invalidSamples;
        for (TimingDistribution d : temporal.placeResidence.values()) {
            invalidSamples += d.invalidSamples;
        }
        for (ServiceTimingSummary timing : temporal.serviceTiming.values()) {
            invalidSamples += timing.queueTime.invalidSamples;
            invalidSamples += timing.serviceTime.invalidSamples;
            invalidSamples += timing.totalTime.invalidSamples;
        }
        for (JoinTimingSummary join : temporal.joinTiming.values()) {
            invalidSamples += join.synchronizationWait.invalidSamples;
            invalidSamples += join.arrivalSkew.invalidSamples;
        }

        report.append("6. TEMPORAL DATA QUALITY\n");
        report.append("   Negative/invalid timing samples: ").append(invalidSamples).append("\n");
        report.append("   Temporal result: ")
              .append(invalidSamples == 0 &&
                      temporal.workflowLatency.samples == temporal.expectedCompletedWorkflows &&
                      temporal.unmatchedServiceTimingSamples == 0 ? "[OK]" : "[CHECK]")
              .append("\n");
        report.append("\n=== END STAGE 2 REPORT ===\n");

        return report.toString();
    }

    private void appendDistribution(StringBuilder report, String label, TimingDistribution distribution) {
        report.append(label)
              .append(": n=").append(distribution.samples);
        if (distribution.samples > 0) {
            report.append(", avg=").append(String.format("%.1f", distribution.avgMs)).append("ms")
                  .append(", p50=").append(distribution.p50Ms).append("ms")
                  .append(", p95=").append(distribution.p95Ms).append("ms")
                  .append(", min/max=").append(distribution.minMs)
                  .append("/").append(distribution.maxMs).append("ms");
        }
        if (distribution.invalidSamples > 0) {
            report.append(", invalid=").append(distribution.invalidSamples);
        }
        report.append("\n");
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
            "SELECT tokenId, timestamp, marking, bufferSize, toPlace, transitionId, eventType " +
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
                snapshot.toPlace = rs.getString("toPlace");
                snapshot.transitionId = rs.getString("transitionId");
                snapshot.eventType = rs.getString("eventType");
                snapshots.add(snapshot);
            }
            
            logger.info("Retrieved " + snapshots.size() + " marking snapshots for " + placeName);
            
        } catch (SQLException e) {
            logger.error("Error retrieving marking evolution", e);
        }
        
        return snapshots;
    }
    
    /**
     * Get GENERATED events from Event Generator for a workflow.
     * These events are stored in CONSOLIDATED_TRANSITION_FIRINGS with eventType='GENERATED'
     * and are needed by TokenAnimator to determine when child tokens were created.
     */
    public ArrayList<MarkingSnapshot> getGeneratedEvents(int workflowBase) {
        ArrayList<MarkingSnapshot> snapshots = new ArrayList<>();
        
        String sql = 
            "SELECT tokenId, timestamp, toPlace, transitionId, eventType, bufferSize " +
            "FROM CONSOLIDATED_TRANSITION_FIRINGS " +
            "WHERE workflowBase = ? " +
            "  AND eventType = 'GENERATED' " +
            "ORDER BY timestamp";
        
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setInt(1, workflowBase);
            ResultSet rs = pstmt.executeQuery();
            
            while (rs.next()) {
                MarkingSnapshot snapshot = new MarkingSnapshot();
                snapshot.tokenId = rs.getInt("tokenId");
                snapshot.timestamp = rs.getLong("timestamp");
                snapshot.marking = 0;  // GENERATED events don't have marking
                snapshot.bufferSize = rs.getInt("bufferSize");
                snapshot.toPlace = rs.getString("toPlace");
                snapshot.transitionId = rs.getString("transitionId");
                snapshot.eventType = rs.getString("eventType");
                snapshots.add(snapshot);
            }
            
            logger.info("Retrieved " + snapshots.size() + " GENERATED events for workflowBase " + workflowBase);
            
        } catch (SQLException e) {
            logger.error("Error retrieving GENERATED events", e);
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
            "WHERE toPlace = ? " +
            "  AND eventType = 'BUFFERED' " +
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
                
                // NEW ENCODING: childTokenId = parentTokenId + branchNumber
                // branchNumber is tokenId % 100 (1, 2, 3... for branches, 0 for parent)
                sc.branchNumber = sc.sequenceId % 100;
                sc.joinCount = 0;  // Not embedded in new encoding
                sc.isForkedToken = (sc.branchNumber >= 1);
                
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
            
            // =========================================================================
            // CORRELATE PLACE NAMES: Match each ServiceContribution to its Petri net place
            // by looking up T_in entries in CONSOLIDATED_TRANSITION_FIRINGS
            // =========================================================================
            correlatePlaceNames(conn, allContributions);
            
            // Build shared places map: place -> set of version numbers
            for (ServiceContribution sc : allContributions) {
                if (sc.placeName != null) {
                    analysis.placeToVersions
                        .computeIfAbsent(sc.placeName, k -> new HashSet<>())
                        .add(sc.versionNumber);
                }
            }
            // Shared places = those with tokens from 2+ versions
            for (Map.Entry<String, Set<Integer>> entry : analysis.placeToVersions.entrySet()) {
                if (entry.getValue().size() > 1) {
                    analysis.sharedPlaces.add(entry.getKey());
                }
            }
            
            logger.info("Shared services (cross-version): " + analysis.sharedPlaces);
            
            // Filter to root tokens at shared services only
            ArrayList<ServiceContribution> sharedServiceRootContributions = new ArrayList<>();
            for (ServiceContribution sc : rootTokenContributions) {
                if (sc.placeName != null && analysis.sharedPlaces.contains(sc.placeName)) {
                    sharedServiceRootContributions.add(sc);
                }
            }
            
            // Find contention points - all tokens and root tokens separately (legacy)
            findContentionPoints(allContributions, analysis);
            findRootTokenContentionPoints(rootTokenContributions, analysis);
            
            // NEW: Find contention points scoped to shared services
            findSharedServiceContentionPoints(sharedServiceRootContributions, analysis);
            
            // Detect priority inversions - all tokens and root tokens separately (legacy)
            detectPriorityInversions(allContributions, analysis);
            detectRootTokenPriorityInversions(rootTokenContributions, analysis);
            
            // NEW: Detect inversions scoped to shared services
            detectSharedServicePriorityInversions(sharedServiceRootContributions, analysis);
            
            // Calculate priority effectiveness
            calculatePriorityEffectiveness(analysis);
            calculateRootTokenPriorityEffectiveness(analysis);
            calculateSharedServicePriorityEffectiveness(analysis);
            
            logger.info("Priority analysis complete: " + analysis.totalSamples + " samples (" +
                       analysis.rootTokenSamples + " root, " + analysis.forkedTokenSamples + " forked), " +
                       analysis.sharedPlaces.size() + " shared services, " +
                       analysis.sharedServiceContentionPoints.size() + " shared-service contention points, " +
                       analysis.sharedServiceInversions.size() + " shared-service inversions");
            
        } catch (SQLException e) {
            logger.error("Error analyzing priority", e);
        }
        
        return analysis;
    }
    
    /**
     * Correlate each ServiceContribution with its Petri net place name
     * by matching (workflowBase, tokenId, arrivalTime) to T_in entries
     * in CONSOLIDATED_TRANSITION_FIRINGS.
     * 
     * For each contribution, finds the T_in entry with the closest timestamp
     * at or before the arrival time. This identifies which service/place
     * the token was queued at when the SERVICECONTRIBUTION was recorded.
     */
    private void correlatePlaceNames(Connection conn, ArrayList<ServiceContribution> contributions) throws SQLException {
        // Build lookup: (workflowBase, tokenId) -> sorted list of (timestamp, placeName)
        // from T_in entries in CONSOLIDATED_TRANSITION_FIRINGS
        String sql = 
            "SELECT workflowBase, tokenId, timestamp, toPlace " +
            "FROM CONSOLIDATED_TRANSITION_FIRINGS " +
            "WHERE transitionId LIKE 'T_in_%' " +
            "ORDER BY workflowBase, tokenId, timestamp";
        
        // Key: "workflowBase:tokenId" -> list of (timestamp, placeName) pairs sorted by time
        Map<String, ArrayList<long[]>> timestampIndex = new HashMap<>();
        Map<String, ArrayList<String>> placeIndex = new HashMap<>();
        
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            while (rs.next()) {
                long wb = rs.getLong("workflowBase");
                int tokenId = rs.getInt("tokenId");
                long ts = rs.getLong("timestamp");
                String place = rs.getString("toPlace");
                
                String key = wb + ":" + tokenId;
                timestampIndex.computeIfAbsent(key, k -> new ArrayList<>()).add(new long[]{ts});
                placeIndex.computeIfAbsent(key, k -> new ArrayList<>()).add(place);
            }
        }
        
        // For each ServiceContribution, find the closest T_in entry at or before arrivalTime
        int matched = 0;
        int unmatched = 0;
        
        for (ServiceContribution sc : contributions) {
            String key = sc.workflowBase + ":" + sc.sequenceId;
            ArrayList<long[]> timestamps = timestampIndex.get(key);
            ArrayList<String> places = placeIndex.get(key);
            
            if (timestamps == null || timestamps.isEmpty()) {
                unmatched++;
                continue;
            }
            
            // Find the last T_in entry at or before sc.arrivalTime
            // timestamps are sorted ascending
            String bestPlace = null;
            for (int i = timestamps.size() - 1; i >= 0; i--) {
                if (timestamps.get(i)[0] <= sc.arrivalTime) {
                    bestPlace = places.get(i);
                    break;
                }
            }
            
            if (bestPlace == null) {
                // Fallback: use first entry (token may have arrived before T_in was recorded)
                bestPlace = places.get(0);
            }
            
            sc.placeName = bestPlace;
            matched++;
        }
        
        logger.info("Place correlation: " + matched + " matched, " + unmatched + " unmatched out of " + contributions.size());
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
                    cp.sharedPlace = (sc1.placeName != null && sc1.placeName.equals(sc2.placeName)) ? sc1.placeName : null;
                    
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
                    cp.sharedPlace = (sc1.placeName != null && sc1.placeName.equals(sc2.placeName)) ? sc1.placeName : null;
                    
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
     * Find contention points scoped to SHARED SERVICES only.
     * Only compares root tokens from different versions that were at the SAME place.
     * This eliminates false positives where v001 is at RadiologyService and v002
     * is at TriageService - they never actually compete in the same queue.
     */
    private void findSharedServiceContentionPoints(ArrayList<ServiceContribution> sharedServiceRootContributions, PriorityAnalysis analysis) {
        long WINDOW_MS = 1000;
        
        for (int i = 0; i < sharedServiceRootContributions.size(); i++) {
            ServiceContribution sc1 = sharedServiceRootContributions.get(i);
            
            for (int j = i + 1; j < sharedServiceRootContributions.size(); j++) {
                ServiceContribution sc2 = sharedServiceRootContributions.get(j);
                
                // Stop if we're past the time window
                if (sc2.arrivalTime - sc1.arrivalTime > WINDOW_MS) {
                    break;
                }
                
                // CRITICAL: Only compare tokens at the SAME place AND different versions
                if (sc1.versionNumber != sc2.versionNumber && 
                    sc1.placeName != null && sc1.placeName.equals(sc2.placeName) &&
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
                    cp.sharedPlace = sc1.placeName;
                    
                    analysis.sharedServiceContentionPoints.add(cp);
                }
            }
        }
    }
    
    /**
     * Detect priority inversions scoped to SHARED SERVICES only.
     * Only flags inversions where tokens from different versions were at the SAME place.
     */
    private void detectSharedServicePriorityInversions(ArrayList<ServiceContribution> sharedServiceRootContributions, PriorityAnalysis analysis) {
        ArrayList<ServiceContribution> byCompletion = new ArrayList<>(sharedServiceRootContributions);
        byCompletion.sort((a, b) -> Long.compare(a.arrivalTime + a.totalTime, b.arrivalTime + b.totalTime));
        
        for (int i = 0; i < byCompletion.size(); i++) {
            ServiceContribution completed = byCompletion.get(i);
            long completionTime = completed.arrivalTime + completed.totalTime;
            
            for (ServiceContribution other : sharedServiceRootContributions) {
                if (other == completed) continue;
                
                // CRITICAL: Same place + different version + temporal overlap
                if (other.versionNumber < completed.versionNumber &&
                    other.placeName != null && other.placeName.equals(completed.placeName) &&
                    other.arrivalTime < completionTime &&
                    (other.arrivalTime + other.totalTime) > completionTime) {
                    
                    PriorityInversion inv = new PriorityInversion();
                    inv.highPriorityToken = other;
                    inv.lowPriorityToken = completed;
                    inv.inversionTime = completionTime - other.arrivalTime;
                    inv.involvesForkedToken = false;
                    
                    analysis.sharedServiceInversions.add(inv);
                }
            }
        }
    }
    
    /**
     * Calculate priority effectiveness for SHARED SERVICES only
     */
    private void calculateSharedServicePriorityEffectiveness(PriorityAnalysis analysis) {
        if (analysis.sharedServiceContentionPoints.isEmpty()) {
            analysis.sharedServiceEffectiveness = 1.0; // No contention = perfect
            return;
        }
        
        long respectedCount = analysis.sharedServiceContentionPoints.stream()
            .filter(cp -> cp.priorityRespected)
            .count();
        
        analysis.sharedServiceEffectiveness = (double) respectedCount / analysis.sharedServiceContentionPoints.size();
        
        double totalAdvantage = 0;
        for (ContentionPoint cp : analysis.sharedServiceContentionPoints) {
            totalAdvantage += (cp.lowPriorityQueueTime - cp.highPriorityQueueTime);
        }
        analysis.sharedServiceQueueTimeAdvantage = totalAdvantage / analysis.sharedServiceContentionPoints.size();
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
        
        // 2. Shared services discovery
        report.append("2. SHARED SERVICES (Cross-Version Traffic)\n");
        if (analysis.sharedPlaces.isEmpty()) {
            report.append("   [INFO] No shared services detected - each version uses exclusive services\n");
            report.append("   Priority contention cannot be measured without shared services\n");
        } else {
            report.append("   Shared services: ").append(analysis.sharedPlaces).append("\n");
            for (String place : analysis.sharedPlaces) {
                Set<Integer> versions = analysis.placeToVersions.get(place);
                StringBuilder versionList = new StringBuilder();
                for (int v : versions) {
                    if (versionList.length() > 0) versionList.append(", ");
                    versionList.append("v").append(String.format("%03d", v));
                }
                report.append("     ").append(place).append(": ").append(versionList).append("\n");
            }
            
            // Also show exclusive services for context
            report.append("   Exclusive services (no contention possible):\n");
            for (Map.Entry<String, Set<Integer>> entry : analysis.placeToVersions.entrySet()) {
                if (entry.getValue().size() == 1) {
                    int version = entry.getValue().iterator().next();
                    report.append("     ").append(entry.getKey())
                          .append(": v").append(String.format("%03d", version)).append(" only\n");
                }
            }
        }
        report.append("\n");
        
        // 3. Per-version statistics (root tokens only)
        report.append("3. VERSION STATISTICS (Root Tokens Only)\n");
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
        
        // 4. SHARED SERVICE CONTENTION (primary metric)
        report.append("4. SHARED SERVICE CONTENTION (Primary - Same Queue Only)\n");
        report.append("   Total contention points: ").append(analysis.sharedServiceContentionPoints.size()).append("\n");
        
        if (!analysis.sharedServiceContentionPoints.isEmpty()) {
            long respected = analysis.sharedServiceContentionPoints.stream().filter(cp -> cp.priorityRespected).count();
            report.append("   Priority respected: ").append(respected)
                  .append("/").append(analysis.sharedServiceContentionPoints.size())
                  .append(" (").append(String.format("%.1f%%", analysis.sharedServiceEffectiveness * 100)).append(")\n");
            report.append("   Avg queue time advantage: ")
                  .append(String.format("%.1fms", analysis.sharedServiceQueueTimeAdvantage))
                  .append(" (positive = high priority faster)\n");
            
            // Show sample contention points grouped by place
            report.append("\n   Sample contention points (by shared service):\n");
            Map<String, List<ContentionPoint>> byPlace = new HashMap<>();
            for (ContentionPoint cp : analysis.sharedServiceContentionPoints) {
                byPlace.computeIfAbsent(cp.sharedPlace, k -> new ArrayList<>()).add(cp);
            }
            for (Map.Entry<String, List<ContentionPoint>> entry : byPlace.entrySet()) {
                report.append("   [").append(entry.getKey()).append("] ");
                List<ContentionPoint> cps = entry.getValue();
                long placeRespected = cps.stream().filter(cp -> cp.priorityRespected).count();
                report.append(placeRespected).append("/").append(cps.size()).append(" respected\n");
                
                int shown = 0;
                for (ContentionPoint cp : cps) {
                    if (shown++ >= 3) {
                        if (cps.size() > 3) {
                            report.append("     ... and ").append(cps.size() - 3).append(" more at ").append(entry.getKey()).append("\n");
                        }
                        break;
                    }
                    report.append(String.format("     v%03d (seq=%d, queue=%dms) vs v%03d (seq=%d, queue=%dms) %s\n",
                        cp.highPriorityToken.versionNumber, cp.highPriorityToken.sequenceId, cp.highPriorityQueueTime,
                        cp.lowPriorityToken.versionNumber, cp.lowPriorityToken.sequenceId, cp.lowPriorityQueueTime,
                        cp.priorityRespected ? "[OK]" : "[INVERSION]"));
                }
            }
        } else if (!analysis.sharedPlaces.isEmpty()) {
            report.append("   [INFO] No temporal contention at shared services (tokens didn't overlap)\n");
        }
        report.append("\n");
        
        // 5. SHARED SERVICE INVERSIONS
        report.append("5. SHARED SERVICE PRIORITY INVERSIONS\n");
        if (analysis.sharedServiceInversions.isEmpty()) {
            report.append("   [OK] No priority inversions at shared services\n");
        } else {
            report.append("   [WARN] ").append(analysis.sharedServiceInversions.size())
                  .append(" priority inversions at shared services\n");
            
            int shown = 0;
            for (PriorityInversion inv : analysis.sharedServiceInversions) {
                if (shown++ >= 5) {
                    report.append("   ... and ").append(analysis.sharedServiceInversions.size() - 5).append(" more\n");
                    break;
                }
                report.append(String.format("   - [%s] v%03d token %d waited while v%03d token %d completed (inversion: %dms)\n",
                    inv.highPriorityToken.placeName,
                    inv.highPriorityToken.versionNumber, inv.highPriorityToken.sequenceId,
                    inv.lowPriorityToken.versionNumber, inv.lowPriorityToken.sequenceId,
                    inv.inversionTime));
            }
        }
        report.append("\n");
        
        // 6. Legacy root token analysis (for comparison)
        report.append("6. LEGACY ROOT TOKEN ANALYSIS (Global - Includes Cross-Service Comparisons)\n");
        report.append("   Contention points: ").append(analysis.rootTokenContentionPoints.size()).append("\n");
        if (!analysis.rootTokenContentionPoints.isEmpty()) {
            long respected = analysis.rootTokenContentionPoints.stream().filter(cp -> cp.priorityRespected).count();
            report.append("   Priority respected: ").append(respected)
                  .append("/").append(analysis.rootTokenContentionPoints.size())
                  .append(" (").append(String.format("%.1f%%", analysis.rootTokenPriorityEffectiveness * 100)).append(")\n");
            report.append("   NOTE: This includes false positives from tokens at different services\n");
        }
        report.append("   Inversions: ").append(analysis.rootTokenInversions.size()).append("\n");
        report.append("\n");
        
        // 7. Join completions (informational)
        report.append("7. JOIN COMPLETIONS (Excluded from Priority Analysis)\n");
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
        
        // 8. Verdict (based on shared service analysis - the accurate metric)
        report.append("8. PRIORITY VERDICT (Based on Shared Service Analysis)\n");
        if (analysis.sharedPlaces.isEmpty()) {
            report.append("   [INFO] No shared services - priority cannot be evaluated\n");
            report.append("   Each version uses exclusive services with no queue contention\n");
        } else if (analysis.sharedServiceContentionPoints.isEmpty()) {
            report.append("   [INFO] No contention detected at shared services - priority not testable\n");
        } else if (analysis.sharedServiceEffectiveness >= 0.9) {
            report.append("   [PASS] Priority scheduling is working effectively (")
                  .append(String.format("%.1f%%", analysis.sharedServiceEffectiveness * 100)).append(")\n");
        } else if (analysis.sharedServiceEffectiveness >= 0.7) {
            report.append("   [WARN] Priority scheduling is partially effective (")
                  .append(String.format("%.1f%%", analysis.sharedServiceEffectiveness * 100)).append(")\n");
        } else {
            report.append("   [FAIL] Priority scheduling is not working as expected (")
                  .append(String.format("%.1f%%", analysis.sharedServiceEffectiveness * 100)).append(")\n");
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
        public String placeName;  // Actual Petri net service/place (e.g., RadiologyService)
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
        public String sharedPlace;  // The shared service where contention occurred
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
        
        // Shared service analysis (scoped to services with cross-version traffic)
        public Set<String> sharedPlaces = new HashSet<>();  // Places with tokens from 2+ versions
        public Map<String, Set<Integer>> placeToVersions = new HashMap<>();  // Place -> set of versions
        public ArrayList<ContentionPoint> sharedServiceContentionPoints = new ArrayList<>();
        public ArrayList<PriorityInversion> sharedServiceInversions = new ArrayList<>();
        public double sharedServiceEffectiveness = 0;
        public double sharedServiceQueueTimeAdvantage = 0;
        
        @Override
        public String toString() {
            return "PriorityAnalysis[samples=" + totalSamples + 
                   ", rootTokens=" + rootTokenSamples +
                   ", forkedTokens=" + forkedTokenSamples +
                   ", contentionPoints=" + contentionPoints.size() +
                   ", rootContentionPoints=" + rootTokenContentionPoints.size() +
                   ", sharedServiceContentionPoints=" + sharedServiceContentionPoints.size() +
                   ", inversions=" + priorityInversions.size() +
                   ", rootInversions=" + rootTokenInversions.size() +
                   ", sharedServiceInversions=" + sharedServiceInversions.size() +
                   ", effectiveness=" + String.format("%.1f%%", sharedServiceEffectiveness * 100) + "]";
        }
    }
    

    // =============================================================================
    // CANONICAL WORKFLOW RECONSTRUCTION
    // =============================================================================
    
    /**
     * Reconstruct logical workflow instances from the event stream.
     *
     * workflowBase identifies the deployed version range, not an individual
     * business transaction. A workflow instance is rooted at a GENERATED token.
     * Fork descendants are mapped back to that root through explicit genealogy.
     *
     * This is the authoritative Stage-1 correctness view. BUFFERED is queue state,
     * ENTER is one logical place execution, JOIN_CONSUMED is a consumed branch,
     * and workflow completion is an explicit business TERMINATE or a successful
     * MonitorService.acknowledgeTokenArrival service execution.
     */
    public CanonicalWorkflowAnalysis analyzeCanonicalWorkflows(int workflowBase) {
        CanonicalWorkflowAnalysis analysis = new CanonicalWorkflowAnalysis();
        analysis.workflowBase = workflowBase;
        
        Map<Integer, Integer> parentByChild = new HashMap<>();
        Map<Integer, Set<Integer>> childrenByParent = new HashMap<>();
        Map<Integer, Set<String>> enterTransitionsByToken = new HashMap<>();
        Map<Integer, Set<String>> consumedTransitionsByToken = new HashMap<>();
        Map<Integer, Long> generatedAt = new TreeMap<>();
        
        try (Connection conn = getConnection()) {
            // 1. Workflow instances are born at GENERATED events.
            String generatedSql =
                "SELECT tokenId, MIN(timestamp) AS generatedAt " +
                "FROM CONSOLIDATED_TRANSITION_FIRINGS " +
                "WHERE workflowBase = ? AND eventType = 'GENERATED' " +
                "GROUP BY tokenId ORDER BY tokenId";
            try (PreparedStatement pstmt = conn.prepareStatement(generatedSql)) {
                pstmt.setInt(1, workflowBase);
                ResultSet rs = pstmt.executeQuery();
                while (rs.next()) {
                    int root = rs.getInt("tokenId");
                    long timestamp = rs.getLong("generatedAt");
                    generatedAt.put(root, timestamp);
                    analysis.generatedRoots.add(root);
                    WorkflowInstanceSummary instance = new WorkflowInstanceSummary();
                    instance.rootTokenId = root;
                    instance.generatedAt = timestamp;
                    instance.members.add(root);
                    analysis.instances.put(root, instance);
                }
            }
            
            // 2. Explicit fork genealogy defines family membership. DISTINCT is
            // required because more than one collector may observe the same relation.
            try {
                String genealogySql =
                    "SELECT DISTINCT parentTokenId, childTokenId " +
                    "FROM CONSOLIDATED_TOKEN_GENEALOGY " +
                    "WHERE workflowBase = ?";
                try (PreparedStatement pstmt = conn.prepareStatement(genealogySql)) {
                    pstmt.setInt(1, workflowBase);
                    ResultSet rs = pstmt.executeQuery();
                    while (rs.next()) {
                        int parent = rs.getInt("parentTokenId");
                        int child = rs.getInt("childTokenId");
                        parentByChild.put(child, parent);
                        childrenByParent.computeIfAbsent(parent, k -> new HashSet<>()).add(child);
                    }
                }
            } catch (SQLException e) {
                logger.warn("Canonical reconstruction: genealogy table unavailable: " + e.getMessage());
            }
            
            Set<Integer> distinctChildren = new HashSet<>();
            for (Map.Entry<Integer, Set<Integer>> entry : childrenByParent.entrySet()) {
                distinctChildren.addAll(entry.getValue());
                int root = resolveRootToken(entry.getKey(), parentByChild);
                WorkflowInstanceSummary instance = analysis.instances.get(root);
                for (int child : entry.getValue()) {
                    int childRoot = resolveRootToken(child, parentByChild);
                    if (childRoot != root || !analysis.generatedRoots.contains(childRoot)) {
                        analysis.orphanForkChildren++;
                    } else if (instance != null) {
                        instance.members.add(child);
                    }
                }
            }
            analysis.forkFamilies = childrenByParent.size();
            analysis.forkChildren = distinctChildren.size();
            
            // 3. ENTER, not BUFFERED, is one logical service/place execution.
            // SELECT DISTINCT protects the reconstruction if a collector payload
            // is accidentally ingested more than once.
            Set<String> seenVisits = new HashSet<>();
            String enterSql =
                "SELECT DISTINCT tokenId, toPlace, transitionId, timestamp " +
                "FROM CONSOLIDATED_TRANSITION_FIRINGS " +
                "WHERE workflowBase = ? AND eventType = 'ENTER' " +
                "ORDER BY timestamp";
            try (PreparedStatement pstmt = conn.prepareStatement(enterSql)) {
                pstmt.setInt(1, workflowBase);
                ResultSet rs = pstmt.executeQuery();
                while (rs.next()) {
                    int tokenId = rs.getInt("tokenId");
                    String place = rs.getString("toPlace");
                    String transition = rs.getString("transitionId");
                    long timestamp = rs.getLong("timestamp");
                    
                    enterTransitionsByToken.computeIfAbsent(tokenId, k -> new HashSet<>()).add(transition);
                    
                    String visitKey = tokenId + "|" + place + "|" + timestamp;
                    if (!seenVisits.add(visitKey)) {
                        continue;
                    }
                    
                    int root = resolveRootToken(tokenId, parentByChild);
                    WorkflowInstanceSummary instance = analysis.instances.get(root);
                    if (instance == null) {
                        analysis.unassignedPlaceVisits++;
                        continue;
                    }
                    
                    instance.members.add(tokenId);
                    instance.placeExecutions.put(place,
                        instance.placeExecutions.getOrDefault(place, 0) + 1);
                    analysis.placeExecutions.put(place,
                        analysis.placeExecutions.getOrDefault(place, 0) + 1);
                }
            }
            
            // 4. JOIN_CONSUMED closes a branch. A successful join requires every
            // child of the fork to be accounted for at the same T_in transition,
            // with one continuation ENTER (or a parent ENTER for reset semantics).
            String consumedSql =
                "SELECT DISTINCT tokenId, transitionId " +
                "FROM CONSOLIDATED_TRANSITION_FIRINGS " +
                "WHERE workflowBase = ? AND eventType = 'JOIN_CONSUMED'";
            try (PreparedStatement pstmt = conn.prepareStatement(consumedSql)) {
                pstmt.setInt(1, workflowBase);
                ResultSet rs = pstmt.executeQuery();
                while (rs.next()) {
                    int tokenId = rs.getInt("tokenId");
                    String transition = rs.getString("transitionId");
                    consumedTransitionsByToken.computeIfAbsent(tokenId, k -> new HashSet<>()).add(transition);
                }
            }
            
            for (Map.Entry<Integer, Set<Integer>> fork : childrenByParent.entrySet()) {
                int parent = fork.getKey();
                Set<Integer> children = fork.getValue();
                Set<String> candidateJoinTransitions = new HashSet<>();
                for (int child : children) {
                    Set<String> transitions = consumedTransitionsByToken.get(child);
                    if (transitions != null) {
                        candidateJoinTransitions.addAll(transitions);
                    }
                }
                
                boolean joined = false;
                for (String transition : candidateJoinTransitions) {
                    int accountedChildren = 0;
                    boolean continuationObserved = false;
                    Set<Integer> participantRoots = new HashSet<>();
                    
                    for (int child : children) {
                        boolean consumed = consumedTransitionsByToken
                            .getOrDefault(child, Collections.emptySet()).contains(transition);
                        boolean entered = enterTransitionsByToken
                            .getOrDefault(child, Collections.emptySet()).contains(transition);
                        if (consumed || entered) {
                            accountedChildren++;
                            participantRoots.add(resolveRootToken(child, parentByChild));
                        }
                        if (entered) {
                            continuationObserved = true;
                        }
                    }
                    
                    if (enterTransitionsByToken.getOrDefault(parent, Collections.emptySet()).contains(transition)) {
                        continuationObserved = true;
                        participantRoots.add(resolveRootToken(parent, parentByChild));
                    }
                    
                    if (participantRoots.size() > 1) {
                        analysis.joinFamilyViolations++;
                    }
                    
                    if (accountedChildren == children.size() && continuationObserved) {
                        joined = true;
                        break;
                    }
                }
                
                if (joined) {
                    analysis.successfulJoins++;
                }
            }
            
            // 5. Record business hand-off to the Monitor separately from actual
            // acknowledgement. An EXIT to Monitor proves publication intent; the
            // service timing row proves acknowledgeTokenArrival actually executed.
            String handoffSql =
                "SELECT DISTINCT tokenId FROM CONSOLIDATED_TRANSITION_FIRINGS " +
                "WHERE workflowBase = ? AND eventType = 'EXIT' AND toPlace = 'MonitorService'";
            try (PreparedStatement pstmt = conn.prepareStatement(handoffSql)) {
                pstmt.setInt(1, workflowBase);
                ResultSet rs = pstmt.executeQuery();
                while (rs.next()) {
                    int tokenId = rs.getInt("tokenId");
                    int root = resolveRootToken(tokenId, parentByChild);
                    WorkflowInstanceSummary instance = analysis.instances.get(root);
                    if (instance != null) {
                        instance.monitorHandoff = true;
                        analysis.monitorHandoffRoots.add(root);
                    }
                }
            }
            
            // SERVICEMEASUREMENTS is local to MonitorService and includes the
            // operation name, so it cleanly excludes writeCollectorData/admin traffic.
            String ackSql =
                "SELECT sequenceID, MIN(arrivalTime) AS arrivalTime " +
                "FROM SERVICEMEASUREMENTS " +
                "WHERE serviceName = 'MonitorService' " +
                "  AND operation = 'acknowledgeTokenArrival' " +
                "GROUP BY sequenceID";
            try (PreparedStatement pstmt = conn.prepareStatement(ackSql)) {
                ResultSet rs = pstmt.executeQuery();
                while (rs.next()) {
                    int tokenId = rs.getInt("sequenceID");
                    int root = resolveRootToken(tokenId, parentByChild);
                    WorkflowInstanceSummary instance = analysis.instances.get(root);
                    if (instance != null) {
                        instance.monitorAcknowledged = true;
                        long completedAt = rs.getLong("arrivalTime");
                        if (completedAt > 0 && (instance.completedAt == 0 || completedAt < instance.completedAt)) {
                            instance.completedAt = completedAt;
                        }
                        analysis.monitorAcknowledgedRoots.add(root);
                    }
                }
            } catch (SQLException e) {
                logger.warn("Canonical reconstruction: Monitor acknowledgement timing unavailable: " + e.getMessage());
            }
            
            // Explicit business TerminateNode paths are valid completion boundaries
            // for workflows such as invalid/declined branches.
            String terminateSql =
                "SELECT tokenId, MIN(timestamp) AS terminatedAt " +
                "FROM CONSOLIDATED_TRANSITION_FIRINGS " +
                "WHERE workflowBase = ? " +
                "  AND (eventType = 'TERMINATE' OR toPlace = 'TERMINATE') " +
                "GROUP BY tokenId";
            try (PreparedStatement pstmt = conn.prepareStatement(terminateSql)) {
                pstmt.setInt(1, workflowBase);
                ResultSet rs = pstmt.executeQuery();
                while (rs.next()) {
                    int root = resolveRootToken(rs.getInt("tokenId"), parentByChild);
                    WorkflowInstanceSummary instance = analysis.instances.get(root);
                    if (instance != null) {
                        instance.businessTerminated = true;
                        long completedAt = rs.getLong("terminatedAt");
                        if (completedAt > 0 && (instance.completedAt == 0 || completedAt < instance.completedAt)) {
                            instance.completedAt = completedAt;
                        }
                        analysis.businessTerminatedRoots.add(root);
                    }
                }
            }
            
        } catch (SQLException e) {
            logger.error("Error reconstructing canonical workflows for workflowBase=" + workflowBase, e);
        }
        
        analysis.generatedWorkflows = analysis.generatedRoots.size();
        analysis.monitorHandoffs = analysis.monitorHandoffRoots.size();
        analysis.monitorAcknowledgements = analysis.monitorAcknowledgedRoots.size();
        analysis.businessTerminations = analysis.businessTerminatedRoots.size();
        
        analysis.completedRoots.addAll(analysis.monitorAcknowledgedRoots);
        analysis.completedRoots.addAll(analysis.businessTerminatedRoots);
        analysis.completedWorkflows = analysis.completedRoots.size();
        
        analysis.incompleteRoots.addAll(analysis.generatedRoots);
        analysis.incompleteRoots.removeAll(analysis.completedRoots);
        analysis.incompleteWorkflows = analysis.incompleteRoots.size();
        
        logger.info("Canonical reconstruction: generated=" + analysis.generatedWorkflows +
            ", completed=" + analysis.completedWorkflows +
            ", monitorAck=" + analysis.monitorAcknowledgements +
            ", forks=" + analysis.forkFamilies +
            ", joins=" + analysis.successfulJoins +
            ", orphanChildren=" + analysis.orphanForkChildren);
        
        return analysis;
    }
    
    private int resolveRootToken(int tokenId, Map<Integer, Integer> parentByChild) {
        int current = tokenId;
        Set<Integer> seen = new HashSet<>();
        while (parentByChild.containsKey(current) && seen.add(current)) {
            current = parentByChild.get(current);
        }
        return current;
    }
    
    // =============================================================================
    // SUMMARY REPORTS
    // =============================================================================
    
    /**
     * Generate comprehensive workflow analysis report
     */
    public String generateWorkflowReport(int workflowBase) {
        StringBuilder report = new StringBuilder();
        CanonicalWorkflowAnalysis canonical = analyzeCanonicalWorkflows(workflowBase);
        
        report.append("=== PETRI NET ANALYSIS REPORT ===\n");
        report.append("Workflow Base: ").append(workflowBase).append("\n\n");
        
        report.append("1. CANONICAL WORKFLOW RECONSTRUCTION\n");
        report.append("   Generated workflows:       ").append(canonical.generatedWorkflows).append("\n");
        report.append("   Completed workflows:       ").append(canonical.completedWorkflows).append("\n");
        report.append("   Monitor handoffs:          ").append(canonical.monitorHandoffs).append("\n");
        report.append("   Monitor acknowledgements:  ").append(canonical.monitorAcknowledgements).append("\n");
        report.append("   Business terminations:     ").append(canonical.businessTerminations).append("\n");
        report.append("   Fork families:             ").append(canonical.forkFamilies).append("\n");
        report.append("   Fork children:             ").append(canonical.forkChildren).append("\n");
        report.append("   Successful joins:          ").append(canonical.successfulJoins).append("\n");
        report.append("   Orphan fork children:      ").append(canonical.orphanForkChildren).append("\n");
        report.append("   Unassigned place visits:   ").append(canonical.unassignedPlaceVisits).append("\n");
        report.append("   Join-family violations:    ").append(canonical.joinFamilyViolations).append("\n");
        report.append("   Incomplete workflows:      ").append(canonical.incompleteWorkflows).append("\n");
        if (!canonical.incompleteRoots.isEmpty()) {
            report.append("     Incomplete roots: ").append(canonical.incompleteRoots).append("\n");
        }
        
        boolean canonicalOk = canonical.generatedWorkflows > 0 &&
                              canonical.incompleteWorkflows == 0 &&
                              canonical.orphanForkChildren == 0 &&
                              canonical.unassignedPlaceVisits == 0 &&
                              canonical.joinFamilyViolations == 0;
        report.append("   Structural result:         ")
              .append(canonicalOk ? "[OK]" : "[CHECK]")
              .append("\n\n");
        
        report.append("2. LOGICAL PLACE EXECUTIONS\n");
        if (canonical.placeExecutions.isEmpty()) {
            report.append("   No ENTER events reconstructed\n");
        } else {
            for (Map.Entry<String, Integer> entry : canonical.placeExecutions.entrySet()) {
                report.append("   ").append(entry.getKey()).append(": ")
                      .append(entry.getValue()).append(" executions\n");
            }
        }
        report.append("\n");
        
        ForkJoinAnalysis forkJoin = analyzeForkJoin(workflowBase);
        report.append("3. FORK/JOIN DETAIL\n");
        report.append("   Forks detected (legacy cross-check): ").append(forkJoin.totalForks).append("\n");
        report.append("   Joins detected (legacy cross-check): ").append(forkJoin.successfulJoins).append("\n");
        for (ForkGroup group : forkJoin.forkGroups) {
            report.append("   Fork from ").append(group.parentTokenId)
                  .append(" -> ").append(group.childTokenIds)
                  .append(group.joinSuccessful ? " [JOINED]" : " [INCOMPLETE]").append("\n");
        }
        report.append("\n");
        
        // Event pairing is an instrumentation-quality diagnostic only. It does
        // not decide logical workflow completion.
        ArrayList<TokenPath> unpaired = verifyTokenCompleteness(workflowBase);
        report.append("4. INSTRUMENTATION CONSISTENCY\n");
        report.append("   Unpaired place ENTER events: ").append(unpaired.size()).append("\n");
        for (TokenPath path : unpaired) {
            report.append("     - Token ").append(path.tokenId)
                  .append(" entered ").append(path.placeName)
                  .append(" without a same-token EXIT/TERMINATE\n");
        }
        report.append("\n");
        
        report.append("5. PLACE RESIDENCE SAMPLES\n");
        ArrayList<String> places = getAllPlaces(workflowBase);
        for (String place : places) {
            PlaceStatistics stats = getPlaceStatistics(place, workflowBase);
            int logicalExecutions = canonical.placeExecutions.getOrDefault(place, 0);
            report.append("   Place: ").append(place).append("\n");
            report.append("     Logical executions: ").append(logicalExecutions).append("\n");
            report.append("     Paired timing samples: ").append(stats.tokenCount).append("\n");
            if (stats.tokenCount > 0) {
                report.append("     Avg residence: ").append(String.format("%.1f", stats.avgResidenceTime)).append("ms\n");
                report.append("     Min/Max: ").append(stats.minResidenceTime)
                      .append("/").append(stats.maxResidenceTime).append("ms\n");
            }
            if (stats.tokenCount != logicalExecutions) {
                report.append("     [WARN] execution/timing-sample mismatch - inspect token identity\n");
            }
        }
        report.append("\n");
        
        report.append("6. CAPACITY VERIFICATION\n");
        for (String place : places) {
            boolean bounded = verifyBoundedCapacity(place, workflowBase, 50);
            report.append("   ").append(place).append(": ")
                  .append(bounded ? "[OK] BOUNDED" : "[FAIL] EXCEEDED").append("\n");
        }
        
        report.append("\n7. DATA QUALITY\n");
        ArrayList<TokenPath> allPaths = getTokenPaths(workflowBase);
        long negativePaths = allPaths.stream().filter(p -> p.residenceTime < 0).count();
        report.append("   Reconstructed place paths: ").append(allPaths.size()).append("\n");
        if (negativePaths == 0) {
            report.append("   [OK] All paired residence times are non-negative\n");
        } else {
            report.append("   [WARN] ").append(negativePaths)
                  .append(" paths have negative residence time\n");
        }
        report.append("   NOTE: temporal distributions and canonical throughput are reported in Stage 2.\n");
        
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
             
             // NEW ENCODING: childTokenId = parentTokenId + branchNumber
             // branchNumber is tokenId % 100 (1, 2, 3... for branches, 0 for parent)
             int branchNumber = tokenId % 100;
             
             if (branchNumber >= 1) {
                 // This is a forked token
                 int parentId = tokenId - branchNumber;
                 node.parentTokenId = parentId;
                 node.generation = 1;  // Will be updated if parent is also forked
                 
                 // Extract fork info - with new encoding, we don't have joinCount embedded
                 // Just store the branch number
                 node.joinCount = 0;  // Will be computed from sibling count if needed
                 node.branchNumber = branchNumber;
                 
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
    
    public ArrayList<String> getAllPlaces(int workflowBase) {
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
    
    public static class CanonicalWorkflowAnalysis {
        public int workflowBase;
        public int generatedWorkflows;
        public int completedWorkflows;
        public int incompleteWorkflows;
        public int monitorHandoffs;
        public int monitorAcknowledgements;
        public int businessTerminations;
        public int forkFamilies;
        public int forkChildren;
        public int successfulJoins;
        public int orphanForkChildren;
        public int unassignedPlaceVisits;
        public int joinFamilyViolations;
        public Map<String, Integer> placeExecutions = new TreeMap<>();
        public Map<Integer, WorkflowInstanceSummary> instances = new TreeMap<>();
        public Set<Integer> generatedRoots = new HashSet<>();
        public Set<Integer> monitorHandoffRoots = new HashSet<>();
        public Set<Integer> monitorAcknowledgedRoots = new HashSet<>();
        public Set<Integer> businessTerminatedRoots = new HashSet<>();
        public Set<Integer> completedRoots = new HashSet<>();
        public Set<Integer> incompleteRoots = new HashSet<>();
    }
    
    public static class WorkflowInstanceSummary {
        public int rootTokenId;
        public long generatedAt;
        public long completedAt;
        public boolean monitorHandoff;
        public boolean monitorAcknowledged;
        public boolean businessTerminated;
        public Set<Integer> members = new HashSet<>();
        public Map<String, Integer> placeExecutions = new TreeMap<>();
    }
    
    public static class TimingDistribution {
        public int samples;
        public int invalidSamples;
        public double avgMs;
        public long p50Ms;
        public long p95Ms;
        public long minMs;
        public long maxMs;
    }

    public static class ServiceTimingSummary {
        public String placeName;
        public TimingDistribution queueTime = new TimingDistribution();
        public TimingDistribution serviceTime = new TimingDistribution();
        public TimingDistribution totalTime = new TimingDistribution();
    }

    public static class JoinTimingSummary {
        public String transitionId;
        public String placeName;
        public TimingDistribution synchronizationWait = new TimingDistribution();
        public TimingDistribution arrivalSkew = new TimingDistribution();
    }

    public static class TemporalAnalysis {
        public int workflowBase;
        public int expectedCompletedWorkflows;
        public long observationWindowMs;
        public double completionThroughputPerSecond;
        public int unmatchedServiceTimingSamples;
        public TimingDistribution workflowLatency = new TimingDistribution();
        public TimingDistribution generationInterArrival = new TimingDistribution();
        public Map<String, TimingDistribution> placeResidence = new TreeMap<>();
        public Map<String, ServiceTimingSummary> serviceTiming = new TreeMap<>();
        public Map<String, JoinTimingSummary> joinTiming = new TreeMap<>();
    }

    private static class JoinTemporalAccumulator {
        public String transitionId;
        public String placeName;
        public long firstBuffered = Long.MAX_VALUE;
        public long lastBuffered = Long.MIN_VALUE;
        public long enterAt;
        public int consumedCount;
        public Set<Integer> bufferedTokens = new HashSet<>();
    }

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
        public String toPlace;       // Destination place (for exits)
        public String transitionId;  // T_in_X or T_out_X
        public String eventType;     // ENTER, EXIT, FORK_CONSUMED, TERMINATE
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
    
    /**
     * Get all distinct workflowBase values from the database
     */
    public ArrayList<Integer> getAllWorkflowBases() {
        ArrayList<Integer> workflowBases = new ArrayList<>();
        
        String sql = "SELECT DISTINCT workflowBase FROM CONSOLIDATED_TRANSITION_FIRINGS ORDER BY workflowBase";
        
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            while (rs.next()) {
                workflowBases.add(rs.getInt("workflowBase"));
            }
            
            logger.info("Found " + workflowBases.size() + " distinct workflowBases: " + workflowBases);
            
        } catch (SQLException e) {
            logger.error("Error getting workflow bases", e);
        }
        
        return workflowBases;
    }
    
    public static void main(String[] args) {
        PetriNetAnalyzer analyzer = new PetriNetAnalyzer();
        
        // Check for --all flag to analyze all workflow bases
        boolean analyzeAll = false;
        int specificWorkflowBase = -1;
        
        for (String arg : args) {
            if ("--all".equals(arg) || "-a".equals(arg)) {
                analyzeAll = true;
            } else {
                try {
                    specificWorkflowBase = Integer.parseInt(arg);
                } catch (NumberFormatException e) {
                    // Ignore invalid numbers
                }
            }
        }
        
        ArrayList<Integer> workflowBases;
        
        if (analyzeAll) {
            // Analyze all workflow bases found in database
            workflowBases = analyzer.getAllWorkflowBases();
            if (workflowBases.isEmpty()) {
                System.err.println("No workflow bases found in database");
                return;
            }
            System.out.println("Analyzing ALL " + workflowBases.size() + " workflow bases: " + workflowBases);
        } else if (specificWorkflowBase > 0) {
            // Use specified workflow base
            workflowBases = new ArrayList<>();
            workflowBases.add(specificWorkflowBase);
        } else {
            // Default: analyze all workflow bases
            workflowBases = analyzer.getAllWorkflowBases();
            if (workflowBases.isEmpty()) {
                // Fallback to legacy default using VersionConstants
                workflowBases = new ArrayList<>();
                workflowBases.add(VersionConstants.V001_BASE);
                System.out.println("No workflow bases found, using default: " + VersionConstants.V001_BASE);
            } else {
                System.out.println("Auto-detected " + workflowBases.size() + " workflow bases: " + workflowBases);
            }
        }
        
        // Print report for each workflow base
        for (int workflowBase : workflowBases) {
            String versionStr = VersionConstants.getVersionString(workflowBase);
            System.out.println("\n" + "=".repeat(80));
            System.out.println("WORKFLOW BASE: " + workflowBase + " (" + versionStr + ")");
            System.out.println("=".repeat(80));
            
            // Print full report (now includes fork/join analysis)
            analyzer.printWorkflowReport(workflowBase);
            analyzer.printGenealogyReport(workflowBase);
            System.out.println("\n" + analyzer.generateTemporalReport(workflowBase));
            
            // Get detailed token paths
            System.out.println("\n=== TOKEN PATHS ===");
            ArrayList<TokenPath> paths = analyzer.getTokenPaths(workflowBase);
            for (TokenPath path : paths) {
                System.out.println("Token " + path.tokenId + " at " + path.placeName + 
                                 ": residence=" + path.residenceTime + "ms");
            }
            
            // Get marking evolution for ALL places (legacy format)
            System.out.println("\n=== MARKING EVOLUTION ===");
            
            // First, output GENERATED events from Event Generator
            // These are needed by TokenAnimator to know when child tokens were created
            ArrayList<MarkingSnapshot> generatedEvents = analyzer.getGeneratedEvents(workflowBase);
            for (MarkingSnapshot snap : generatedEvents) {
                // Output in same format as other marking events, with Place= showing destination
                System.out.println("Time=" + snap.timestamp + 
                                 " Token=" + snap.tokenId + 
                                 " Place=" + snap.toPlace +  // Destination place
                                 " Marking=" + snap.marking + 
                                 " Buffer=" + snap.bufferSize +
                                 " ToPlace=" + (snap.toPlace != null ? snap.toPlace : "") +
                                 " TransitionId=" + (snap.transitionId != null ? snap.transitionId : "") +
                                 " EventType=" + (snap.eventType != null ? snap.eventType : ""));
            }
            
            // Then output marking evolution for each place
            ArrayList<String> allPlaces = analyzer.getAllPlaces(workflowBase);
            for (String placeName : allPlaces) {
                ArrayList<MarkingSnapshot> markings = analyzer.getMarkingEvolution(placeName, workflowBase);
                for (MarkingSnapshot snap : markings) {
                    System.out.println("Time=" + snap.timestamp + 
                                     " Token=" + snap.tokenId + 
                                     " Place=" + placeName +
                                     " Marking=" + snap.marking + 
                                     " Buffer=" + snap.bufferSize +
                                     " ToPlace=" + (snap.toPlace != null ? snap.toPlace : "") +
                                     " TransitionId=" + (snap.transitionId != null ? snap.transitionId : "") +
                                     " EventType=" + (snap.eventType != null ? snap.eventType : ""));
                }
            }
        }
        
        // Print priority analysis (cross-version comparison) - only once at end
        System.out.println("\n" + "=".repeat(80));
        System.out.println("CROSS-VERSION PRIORITY ANALYSIS");
        System.out.println("=".repeat(80));
        analyzer.printPriorityReport();
    }
}