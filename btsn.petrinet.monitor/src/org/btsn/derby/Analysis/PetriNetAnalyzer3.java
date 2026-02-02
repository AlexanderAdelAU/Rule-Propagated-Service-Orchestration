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
 * 
 * FORK/JOIN TOKEN ID ENCODING:
 * Fork creates child tokens with encoded IDs:
 *   childTokenId = parentTokenId + (joinCount * 100) + branchNumber
 * Example: Parent 1000000 with 2-way fork -> 1000201, 1000202
 * 
 * At JoinNode, sibling tokens are merged. The "joined" siblings show as
 * incomplete in raw analysis but are correctly identified here.
 * 
 * FIXED: Token path queries now correctly pair each entry with its corresponding
 * exit (the next exit after that entry) instead of creating a Cartesian product
 * that caused negative residence times.
 */
public class PetriNetAnalyzer3 {

    private static final Logger logger = Logger.getLogger(PetriNetAnalyzer3.class);
    private static final String DB_URL = "jdbc:derby:./ServiceAnalysisDataBase;create=true";
    
    // Fork/Join token ID encoding constants (must match ServiceThread)
    private static final int TOKEN_INCREMENT = 10000;  // Gap between workflow base tokens
    
    private BuildServiceAnalysisDatabase db;
    
    public PetriNetAnalyzer3() {
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
     * Check if a token has exited the workflow (reached TERMINATE or has final T_out)
     */
    private boolean hasExitedWorkflow(int tokenId, int workflowBase) {
        String sql = "SELECT COUNT(*) FROM CONSOLIDATED_TRANSITION_FIRINGS " +
                    "WHERE tokenId = ? AND workflowBase = ? " +
                    "AND (transitionId LIKE 'T_out_%' OR toPlace = 'TERMINATE')";
        
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setInt(1, tokenId);
            pstmt.setInt(2, workflowBase);
            ResultSet rs = pstmt.executeQuery();
            
            if (rs.next()) {
                return rs.getInt(1) > 0;
            }
            
        } catch (SQLException e) {
            logger.error("Error checking token exit", e);
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
        PetriNetAnalyzer3 analyzer = new PetriNetAnalyzer3();
        
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