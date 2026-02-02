package org.btsn.places;

import org.btsn.base.BaseHealthcareService;
import org.btsn.base.BaseServiceAssessment;
import org.btsn.base.ServiceAssessment;
import org.btsn.base.TokenInfo;
import org.btsn.exceptions.ServiceProcessingException;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * LaboratoryService - PURE BUSINESS LOGIC
 * 
 * RESPONSIBILITY: Process laboratory specimens and generate test results
 * 
 * ARCHITECTURAL CONTRACT:
 * - Receives CLEAN business data (patient info, diagnostic orders) from ServiceHelper
 * - Returns PURE business results (lab test results, critical values, summary)
 * - ZERO knowledge of tokens, timing, metadata, or infrastructure
 * 
 * Infrastructure concerns (token preservation, timing, enrichment) handled by ServiceHelper
 */
public class LaboratoryService extends BaseHealthcareService {
    
    // ===== STATIC INITIALIZATION =====
    
    static {
        System.out.println("=== LABORATORY SERVICE INITIALIZING ===");
        System.out.println("Pure Business Logic - No Infrastructure Concerns");
        System.out.println("Laboratory testing protocols loaded");
        System.out.println("=== LABORATORY SERVICE READY ===");
    }
    
    // ===== INSTANCE FIELDS =====
    
    private final String technicianId;
    
    // ===== CONSTRUCTORS =====
    
    public LaboratoryService(String sequenceID) {
        super(sequenceID, "LAB");
        this.technicianId = generateTechnicianId(sequenceID);
        
        System.out.printf("[%s] LaboratoryService instance created - sequenceID: %s, technician: %s\n", 
            getPlaceId(), sequenceID, technicianId);
    }
    
    private static String generateTechnicianId(String sequenceID) {
        try {
            return "LT_" + (Integer.parseInt(sequenceID) % 1000);
        } catch (NumberFormatException e) {
            return "LT_" + Math.abs(sequenceID.hashCode() % 1000);
        }
    }
    
    // ===== BUSINESS METHODS =====
    
    /**
     * Main entry point - SIMPLIFIED to pure business logic
     * 
     * ServiceHelper contract:
     * - Receives: Clean business data (patient info, diagnostic orders)
     * - Returns: Pure business results (laboratory test results)
     * - Infrastructure (tokens, timing) handled by ServiceHelper
     */
    public String processLabRequest(String cleanBusinessData) {
        System.out.printf("[%s] ========== LABORATORY SERVICE EXECUTION START ==========\n", getPlaceId());
        System.out.printf("[%s] Processing laboratory analysis for sequenceID: %s\n", getPlaceId(), getSequenceID());
        System.out.printf("[%s] Received clean business data length: %d\n", getPlaceId(), 
            cleanBusinessData != null ? cleanBusinessData.length() : 0);
        
        // Process the laboratory assessment using framework - returns pure business results
        String businessResults = processAssessment(cleanBusinessData);
        
        System.out.printf("[%s] ========== LABORATORY ANALYSIS COMPLETE ==========\n", getPlaceId());
        
        // Return pure business results - ServiceHelper will enrich with metadata
        return businessResults;
    }
    
    // ===== FRAMEWORK IMPLEMENTATION METHODS =====
    
    @Override
    protected ServiceAssessment processServiceSpecificAssessment(TokenInfo tokenInfo) 
            throws ServiceProcessingException {
        try {
            System.out.printf("[%s] Processing specific laboratory assessment\n", getPlaceId());
            
            // Clean input data
            tokenInfo = superCleanTokenInfo(tokenInfo);
            
            // Laboratory assessment - pure business logic
            String specimenId = generateAssessmentId("SPEC", tokenInfo.getPatientId());
            specimenId = superCleanValue(specimenId);
            
            List<String> diagnosticOrders = extractDiagnosticOrders(tokenInfo);
            List<String> orderedTests = mapDiagnosticOrdersToTests(diagnosticOrders, tokenInfo.getIndication());
            
            // Process each test
            List<LabResult> results = new ArrayList<>();
            for (String testName : orderedTests) {
                LabResult result = processIndividualTest(testName, tokenInfo.getPatientId(), specimenId);
                results.add(result);
            }
            
            // Generate analysis
            String criticalResults = identifyCriticalResults(results);
            String summary = generateLabSummary(results, tokenInfo.getIndication());
            
            // Create assessment result
            LaboratoryAssessment assessment = new LaboratoryAssessment(specimenId);
            assessment.setTechnicianId(technicianId);
            assessment.setResults(results);
            assessment.setCriticalResults(criticalResults);
            assessment.setSummary(summary);
            
            System.out.printf("[%s] Assessment completed: %d tests, critical: %s\n", 
                getPlaceId(), results.size(), criticalResults);
            
            return assessment;
            
        } catch (Exception e) {
            System.err.printf("[%s] Error in laboratory assessment: %s\n", getPlaceId(), e.getMessage());
            e.printStackTrace();
            throw new ServiceProcessingException("Laboratory analysis failed", e);
        }
    }
    
    // ===== LABORATORY BUSINESS LOGIC =====
    
    private List<String> extractDiagnosticOrders(TokenInfo tokenInfo) {
        List<String> diagnosticOrders = new ArrayList<>();
        
        String originalToken = tokenInfo.getOriginalToken();
        if (originalToken != null && originalToken.contains("diagnostic_orders")) {
            try {
                String searchPattern = "\"diagnostic_orders\":[";
                int startIndex = originalToken.indexOf(searchPattern);
                if (startIndex != -1) {
                    startIndex += searchPattern.length();
                    int endIndex = originalToken.indexOf("]", startIndex);
                    if (endIndex != -1) {
                        String ordersContent = originalToken.substring(startIndex, endIndex);
                        String[] orders = ordersContent.split(",");
                        
                        for (String order : orders) {
                            String cleanOrder = superCleanValue(order);
                            if (!cleanOrder.isEmpty()) {
                                diagnosticOrders.add(cleanOrder);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                System.err.printf("[%s] Error extracting diagnostic orders: %s\n", getPlaceId(), e.getMessage());
            }
        }
        
        // If no orders found, provide defaults
        if (diagnosticOrders.isEmpty()) {
            diagnosticOrders.add("basic_metabolic_panel");
        }
        
        System.out.printf("[%s] Extracted diagnostic orders: %s\n", getPlaceId(), diagnosticOrders);
        return diagnosticOrders;
    }
    
    private List<String> mapDiagnosticOrdersToTests(List<String> diagnosticOrders, String indication) {
        Set<String> tests = new LinkedHashSet<>();
        
        for (String order : diagnosticOrders) {
            String cleanOrder = order.toLowerCase();
            
            switch (cleanOrder) {
                case "cbc":
                    tests.add("wbc");
                    tests.add("hemoglobin");
                    tests.add("platelets");
                    break;
                    
                case "basic_metabolic_panel":
                case "bmp":
                    tests.add("sodium");
                    tests.add("potassium");
                    tests.add("glucose");
                    tests.add("creatinine");
                    break;
                    
                case "cardiac_enzymes":
                    tests.add("troponin");
                    tests.add("ck-mb");
                    break;
                    
                case "type_and_cross":
                    tests.add("blood_type");
                    tests.add("antibody_screen");
                    break;
                    
                case "ecg_stat":
                    // Non-lab order, skip
                    break;
                    
                default:
                    tests.add(cleanOrder);
            }
        }
        
        // Add indication-specific tests
        if ("chest".equals(indication) || "chest_pain".equals(indication)) {
            tests.add("troponin");
        } else if ("sob".equals(indication) || "shortness_of_breath".equals(indication)) {
            tests.add("bnp");
        }
        
        List<String> testList = new ArrayList<>(tests);
        System.out.printf("[%s] Mapped to %d laboratory tests\n", getPlaceId(), testList.size());
        return testList;
    }
    
    private LabResult processIndividualTest(String testName, String patientId, String specimenId) {
        LabTestInfo testInfo = getTestInfo(testName);
        
        LabResult result = new LabResult();
        result.testName = testName;
        result.units = testInfo.units;
        result.referenceRange = String.format("%.1f-%.1f %s", 
            testInfo.normalMin, testInfo.normalMax, testInfo.units);
        
        // Generate realistic result value
        result.resultValue = generateTestResult(testName, testInfo);
        
        // Determine if abnormal
        if (result.resultValue < testInfo.normalMin) {
            result.abnormalFlag = "LOW";
        } else if (result.resultValue > testInfo.normalMax) {
            result.abnormalFlag = "HIGH";
        } else {
            result.abnormalFlag = "NORMAL";
        }
        
        // Check if critical
        result.critical = (result.resultValue < testInfo.criticalLow) || 
                         (result.resultValue > testInfo.criticalHigh);
        
        return result;
    }
    
    private LabTestInfo getTestInfo(String testName) {
        LabTestInfo info = new LabTestInfo();
        info.testName = testName;
        
        switch (testName.toLowerCase()) {
            case "sodium":
                info.normalMin = 135.0;
                info.normalMax = 145.0;
                info.units = "mEq/L";
                info.criticalLow = 120.0;
                info.criticalHigh = 160.0;
                break;
                
            case "potassium":
                info.normalMin = 3.5;
                info.normalMax = 5.0;
                info.units = "mEq/L";
                info.criticalLow = 2.5;
                info.criticalHigh = 6.5;
                break;
                
            case "glucose":
                info.normalMin = 70.0;
                info.normalMax = 100.0;
                info.units = "mg/dL";
                info.criticalLow = 40.0;
                info.criticalHigh = 400.0;
                break;
                
            case "creatinine":
                info.normalMin = 0.6;
                info.normalMax = 1.2;
                info.units = "mg/dL";
                info.criticalLow = 0.0;
                info.criticalHigh = 5.0;
                break;
                
            case "troponin":
                info.normalMin = 0.0;
                info.normalMax = 0.04;
                info.units = "ng/mL";
                info.criticalLow = 0.0;
                info.criticalHigh = 0.3;
                break;
                
            case "bnp":
                info.normalMin = 0.0;
                info.normalMax = 100.0;
                info.units = "pg/mL";
                info.criticalLow = 0.0;
                info.criticalHigh = 2000.0;
                break;
                
            case "wbc":
                info.normalMin = 4.5;
                info.normalMax = 11.0;
                info.units = "K/uL";
                info.criticalLow = 1.0;
                info.criticalHigh = 30.0;
                break;
                
            case "hemoglobin":
                info.normalMin = 12.0;
                info.normalMax = 16.0;
                info.units = "g/dL";
                info.criticalLow = 6.0;
                info.criticalHigh = 20.0;
                break;
                
            case "platelets":
                info.normalMin = 150.0;
                info.normalMax = 400.0;
                info.units = "K/uL";
                info.criticalLow = 20.0;
                info.criticalHigh = 1000.0;
                break;
                
            default:
                info.normalMin = 0.0;
                info.normalMax = 100.0;
                info.units = "units";
                info.criticalLow = 0.0;
                info.criticalHigh = 200.0;
        }
        
        return info;
    }
    
    private double generateTestResult(String testName, LabTestInfo testInfo) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        
        // 70% chance of normal value
        if (random.nextDouble() < 0.7) {
            return testInfo.normalMin + random.nextDouble() * (testInfo.normalMax - testInfo.normalMin);
        }
        
        // 25% chance of mildly abnormal
        if (random.nextDouble() < 0.83) { // 25 out of remaining 30
            if (random.nextBoolean()) {
                // Slightly low
                return testInfo.criticalLow + random.nextDouble() * (testInfo.normalMin - testInfo.criticalLow);
            } else {
                // Slightly high
                return testInfo.normalMax + random.nextDouble() * (testInfo.criticalHigh - testInfo.normalMax) * 0.5;
            }
        }
        
        // 5% chance of critical value
        if (random.nextBoolean()) {
            return testInfo.criticalLow * random.nextDouble();
        } else {
            return testInfo.criticalHigh + random.nextDouble() * testInfo.criticalHigh * 0.5;
        }
    }
    
    private String identifyCriticalResults(List<LabResult> results) {
        List<String> criticalTests = results.stream()
            .filter(r -> r.critical)
            .map(r -> String.format("%s: %.2f %s", r.testName, r.resultValue, r.units))
            .collect(java.util.stream.Collectors.toList());
        
        if (criticalTests.isEmpty()) {
            return "None";
        } else {
            return String.join(", ", criticalTests);
        }
    }
    
    private String generateLabSummary(List<LabResult> results, String indication) {
        StringBuilder summary = new StringBuilder();
        
        // Indication-specific interpretation
        if ("chest".equals(indication) || "chest_pain".equals(indication)) {
            LabResult troponin = findResult(results, "troponin");
            if (troponin != null) {
                if (troponin.resultValue > 0.04) {
                    summary.append("CRITICAL: Elevated troponin suggests acute myocardial injury. ");
                } else {
                    summary.append("Troponin negative for acute MI. ");
                }
            }
        } else if ("shortness_of_breath".equals(indication) || "sob".equals(indication)) {
            LabResult bnp = findResult(results, "bnp");
            if (bnp != null) {
                if (bnp.resultValue > 100.0) {
                    summary.append("Elevated BNP consistent with heart failure. ");
                } else {
                    summary.append("BNP normal, heart failure unlikely. ");
                }
            }
        }
        
        // Count abnormal results
        long abnormalCount = results.stream().filter(r -> !"NORMAL".equals(r.abnormalFlag)).count();
        if (abnormalCount == 0) {
            summary.append("All laboratory values within normal limits.");
        } else {
            summary.append(abnormalCount).append(" abnormal results require clinical correlation.");
        }
        
        return summary.toString();
    }
    
    private LabResult findResult(List<LabResult> results, String testName) {
        return results.stream()
                     .filter(r -> testName.equals(r.testName))
                     .findFirst()
                     .orElse(null);
    }
    
    // ===== UTILITY METHODS =====
    
    private TokenInfo superCleanTokenInfo(TokenInfo tokenInfo) {
        TokenInfo cleanedInfo = new TokenInfo();
        cleanedInfo.setOriginalToken(superCleanValue(tokenInfo.getOriginalToken()));
        cleanedInfo.setPatientId(superCleanValue(tokenInfo.getPatientId()));
        cleanedInfo.setProviderId(superCleanValue(tokenInfo.getProviderId()));
        cleanedInfo.setIndication(superCleanValue(tokenInfo.getIndication()));
        
        System.out.printf("[%s] CLEANED TokenInfo - Patient: %s, Provider: %s, Indication: %s\n", 
            getPlaceId(), cleanedInfo.getPatientId(), cleanedInfo.getProviderId(), cleanedInfo.getIndication());
        
        return cleanedInfo;
    }
    
    private String superCleanValue(String value) {
        if (value == null) return "";
        
        String cleaned = value
            .replace("\\", "")       
            .replace("\"", "")       
            .replace("'", "")        
            .replace("\n", " ")      
            .replace("\r", " ")      
            .replace("\t", " ")      
            .replace("  ", " ")      
            .trim();                 
        
        if (cleaned.isEmpty()) {
            if (value.toLowerCase().contains("patient") || value.toLowerCase().contains("lab")) {
                return "P_Lab";
            } else if (value.toLowerCase().contains("lt") || value.toLowerCase().contains("tech")) {
                return "LT001";
            } else {
                return "CLEAN_VALUE";
            }
        }
        
        return cleaned;
    }
    
    // ===== FRAMEWORK METHODS =====
    
    @Override
    protected String getServiceResultsKey() {
        return "laboratoryResults";
    }
    
    @Override
    protected String getCompletionStatus() {
        return "LAB_COMPLETE";
    }
    
    @Override
    protected String[] getRequiredTables() {
        return new String[0];
    }
    
    @Override
    protected void setupServiceDatabase() {
        System.out.printf("[%s] No database setup needed - service ready\n", getPlaceId());
    }
    
    // ===== LABORATORY DATA CLASSES =====
    
    public static class LaboratoryAssessment extends BaseServiceAssessment {
        private String technicianId;
        private List<LabResult> results;
        private String criticalResults;
        private String summary;
        
        public LaboratoryAssessment(String assessmentId) {
            super(assessmentId);
        }
        
        @Override
        public String toJsonFields() {
            StringBuilder json = new StringBuilder();
            
            json.append(jsonField("technician", technicianId)).append(",");
            json.append(jsonField("specimen_id", getAssessmentId())).append(",");
            
            if (results != null && !results.isEmpty()) {
                json.append("\"lab_results\":[");
                for (int i = 0; i < results.size(); i++) {
                    LabResult result = results.get(i);
                    json.append("{");
                    json.append("\"test_name\":\"").append(result.testName).append("\",");
                    json.append("\"result_value\":").append(String.format("%.2f", result.resultValue)).append(",");
                    json.append("\"units\":\"").append(result.units).append("\",");
                    json.append("\"reference_range\":\"").append(result.referenceRange).append("\",");
                    json.append("\"abnormal_flag\":\"").append(result.abnormalFlag).append("\",");
                    json.append("\"critical\":").append(result.critical);
                    json.append("}");
                    if (i < results.size() - 1) {
                        json.append(",");
                    }
                }
                json.append("],");
            }
            
            json.append(jsonField("critical_results", criticalResults)).append(",");
            json.append(jsonField("clinical_summary", summary));
            
            return json.toString();
        }
        
        @Override
        public String getSummary() {
            return String.format("Laboratory: %d tests completed", 
                results != null ? results.size() : 0);
        }
        
        public void setTechnicianId(String technicianId) { this.technicianId = technicianId; }
        public void setResults(List<LabResult> results) { this.results = results; }
        public void setCriticalResults(String criticalResults) { this.criticalResults = criticalResults; }
        public void setSummary(String summary) { this.summary = summary; }
    }
    
    private static class LabTestInfo {
        String testName;
        double normalMin;
        double normalMax;
        String units;
        double criticalLow;
        double criticalHigh;
    }
    
    private static class LabResult {
        String testName;
        double resultValue;
        String units;
        String referenceRange;
        String abnormalFlag;
        boolean critical;
    }
}