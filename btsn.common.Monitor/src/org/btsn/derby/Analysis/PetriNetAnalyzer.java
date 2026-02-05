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
        
        // Find entries that don't have a corresponding exit AFTER them
        // DERBY FIX: Use CAST() for string concatenation
        // FIX: Exclude tokens that terminated normally (routed to TERMINATE)
        // NOTE: Check BOTH tables for TERMINATE records (Monitor writes to TRANSITION_FIRINGS)
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
            "  AND NOT EXISTS ( " +
            "      SELECT 1 FROM CONSOLIDATED_TRANSITION_FIRINGS t_term " +
            "      WHERE t_term.tokenId = t_in.tokenId " +
            "        AND t_term.workflowBase = t_in.workflowBase " +
            "        AND t_term.toPlace = 'TERMINATE' " +
            "  ) " +
            "  AND NOT EXISTS ( " +
            "      SELECT 1 FROM TRANSITION_FIRINGS t_term_raw " +
            "      WHERE t_term_raw.tokenId = t_in.tokenId " +
            "        AND t_term_raw.workflowBase = t_in.workflowBase " +
            "        AND t_term_raw.toPlace = 'TERMINATE' " +
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
     * Get tokens that were routed to TERMINATE (normal termination)
     * These are tokens that entered the workflow and exited via TERMINATE.
     * 
     * NOTE: Termination records may be in CONSOLIDATED_TRANSITION_FIRINGS or in
     * TRANSITION_FIRINGS (for observer services like MonitorService). We check both
     * using a UNION query.
     */
    public ArrayList<Integer> getTerminatedTokens(int workflowBase) {
        ArrayList<Integer> terminatedTokens = new ArrayList<>();
        
        // Check both tables for TERMINATE records using UNION
        String sql = 
            "SELECT DISTINCT tokenId FROM (" +
            "  SELECT tokenId FROM CONSOLIDATED_TRANSITION_FIRINGS " +
            "  WHERE workflowBase = ? AND toPlace = 'TERMINATE' " +
            "  UNION " +
            "  SELECT tokenId FROM TRANSITION_FIRINGS " +
            "  WHERE workflowBase = ? AND toPlace = 'TERMINATE' " +
            ") AS terminated " +
            "ORDER BY tokenId";
        
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setInt(1, workflowBase);
            pstmt.setInt(2, workflowBase);
            ResultSet rs = pstmt.executeQuery();
            
            while (rs.next()) {
                terminatedTokens.add(rs.getInt("tokenId"));
            }
            
            logger.info("Found " + terminatedTokens.size() + " terminated tokens (checked both tables)");
            
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
        
        // 2. Token completeness (excluding correctly joined tokens and terminated tokens)
        ArrayList<TokenPath> rawIncomplete = verifyTokenCompleteness(workflowBase);
        ArrayList<TokenPath> actualIncomplete = getActualIncompleteTokens(workflowBase);
        ArrayList<Integer> terminatedTokens = getTerminatedTokens(workflowBase);
        
        report.append("2. TOKEN COMPLETENESS\n");
        if (actualIncomplete.isEmpty()) {
            report.append("   [OK] All tokens completed successfully\n");
        } else {
            report.append("   [FAIL] ").append(actualIncomplete.size()).append(" incomplete tokens found\n");
            for (TokenPath path : actualIncomplete) {
                report.append("     - Token ").append(path.tokenId)
                      .append(" stuck at ").append(path.placeName).append("\n");
            }
        }
        if (rawIncomplete.size() > actualIncomplete.size()) {
            int joinedCount = rawIncomplete.size() - actualIncomplete.size();
            report.append("   (").append(joinedCount).append(" tokens consumed by joins - this is correct)\n");
        }
        if (!terminatedTokens.isEmpty()) {
            report.append("   [INFO] ").append(terminatedTokens.size()).append(" tokens terminated early (routed to TERMINATE)\n");
            report.append("     Terminated tokens: ").append(terminatedTokens).append("\n");
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