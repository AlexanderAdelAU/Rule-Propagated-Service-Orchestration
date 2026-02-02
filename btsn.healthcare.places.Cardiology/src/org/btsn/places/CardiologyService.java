package org.btsn.places;

import org.btsn.base.BaseHealthcareService;
import org.btsn.base.BaseServiceAssessment;
import org.btsn.base.ServiceAssessment;
import org.btsn.base.TokenInfo;
import org.btsn.exceptions.ServiceProcessingException;

import java.util.concurrent.ThreadLocalRandom;

/**
 * CardiologyService - PURE BUSINESS LOGIC
 * 
 * RESPONSIBILITY: Perform cardiac assessment, ECG analysis, risk stratification
 * 
 * ARCHITECTURAL CONTRACT:
 * - Receives CLEAN business data (patient info, indication) from ServiceHelper
 * - Returns PURE business results (cardiac assessment, ECG findings, recommendations)
 * - ZERO knowledge of tokens, timing, metadata, or infrastructure
 * 
 * Infrastructure concerns (token preservation, timing, enrichment) handled by ServiceHelper
 */
public class CardiologyService extends BaseHealthcareService {
    
    // ===== STATIC INITIALIZATION =====
    
    static {
        System.out.println("=== CARDIOLOGY SERVICE INITIALIZING ===");
        System.out.println("Pure Business Logic - No Infrastructure Concerns");
        System.out.println("Cardiac assessment protocols loaded");
        System.out.println("=== CARDIOLOGY SERVICE READY ===");
    }
    
    // ===== INSTANCE FIELDS =====
    
    private final String cardiologistId;
    
    // ===== CONSTRUCTORS =====
    
    public CardiologyService(String sequenceID) {
        super(sequenceID, "CARDIO");
        this.cardiologistId = generateCardiologistId(sequenceID);
        
        System.out.printf("[%s] CardiologyService instance created - sequenceID: %s, cardiologist: %s\n", 
            getPlaceId(), sequenceID, cardiologistId);
    }
    
    private static String generateCardiologistId(String sequenceID) {
        try {
            return "DR_CARD_" + (Integer.parseInt(sequenceID) % 1000);
        } catch (NumberFormatException e) {
            return "DR_CARD_" + Math.abs(sequenceID.hashCode() % 1000);
        }
    }
    
    // ===== BUSINESS METHODS =====
    
    /**
     * Main entry point - SIMPLIFIED to pure business logic
     * 
     * ServiceHelper contract:
     * - Receives: Clean business data (patient indication, demographics)
     * - Returns: Pure business results (cardiac assessment)
     * - Infrastructure (tokens, timing) handled by ServiceHelper
     */
    public String processCardiacAssessment(String cleanBusinessData) {
        System.out.printf("[%s] ========== CARDIAC ASSESSMENT START ==========\n", getPlaceId());
        System.out.printf("[%s] Processing cardiac assessment for sequenceID: %s\n", getPlaceId(), getSequenceID());
        System.out.printf("[%s] Received clean business data length: %d\n", getPlaceId(), 
            cleanBusinessData != null ? cleanBusinessData.length() : 0);
        
        // Process assessment using framework - returns pure business results
        String businessResults = processAssessment(cleanBusinessData);
        
        System.out.printf("[%s] ========== CARDIAC ASSESSMENT COMPLETE ==========\n", getPlaceId());
        
        // Return pure business results - ServiceHelper will enrich with metadata
        return businessResults;
    }
    
    // ===== FRAMEWORK IMPLEMENTATION METHODS =====
    
    @Override
    protected ServiceAssessment processServiceSpecificAssessment(TokenInfo tokenInfo) 
            throws ServiceProcessingException {
        try {
            System.out.printf("[%s] Processing specific cardiac assessment\n", getPlaceId());
            
            // Clean input data
            tokenInfo = cleanTokenInfo(tokenInfo);
            
            // Get cardiac protocol (in-memory lookup)
            CardiacProtocol protocol = getCardiacProtocol(tokenInfo.getIndication());
            
            // Generate assessment ID using framework utility
            String assessmentId = generateAssessmentId("CARDIAC", tokenInfo.getPatientId());
            assessmentId = cleanValue(assessmentId);
            
            // Cardiac assessment - pure business logic
            ECGAnalysis ecgAnalysis = performECGAnalysis(tokenInfo.getIndication());
            CardiacRiskFactors riskFactors = assessCardiacRiskFactors(tokenInfo.getIndication());
            String interpretation = generateClinicalInterpretation(ecgAnalysis, riskFactors, tokenInfo.getIndication());
            String riskStratification = determineRiskStratification(ecgAnalysis, riskFactors, tokenInfo.getIndication());
            String recommendations = generateRecommendations(ecgAnalysis, riskStratification, tokenInfo.getIndication());
            
            // Create assessment result
            CardiacAssessment assessment = new CardiacAssessment(assessmentId);
            assessment.setProtocol(protocol);
            assessment.setEcgAnalysis(ecgAnalysis);
            assessment.setRiskFactors(riskFactors);
            assessment.setInterpretation(interpretation);
            assessment.setRiskStratification(riskStratification);
            assessment.setRecommendations(recommendations);
            
            System.out.printf("[%s] Cardiac assessment completed - Risk: %s, Rhythm: %s, Rate: %d\n", 
                getPlaceId(), riskStratification, ecgAnalysis.rhythm, ecgAnalysis.rate);
            
            return assessment;
            
        } catch (Exception e) {
            System.err.printf("[%s] Error in cardiac assessment: %s\n", getPlaceId(), e.getMessage());
            e.printStackTrace();
            throw new ServiceProcessingException("Error during cardiac assessment", e);
        }
    }
    
    // ===== CARDIAC BUSINESS LOGIC =====
    
    private CardiacProtocol getCardiacProtocol(String indication) {
        System.out.printf("[%s] Looking up cardiac protocol for indication: %s\n", getPlaceId(), indication);
        
        CardiacProtocol protocol = new CardiacProtocol();
        
        if ("chest".equals(indication)) {
            protocol.protocolId = "CHEST_PAIN_ECG";
            protocol.assessmentType = "Acute Coronary Syndrome Assessment";
            protocol.standardTests = "12_lead_ecg,rhythm_monitoring";
            protocol.interpretationGuidelines = "AHA/ACC STEMI/NSTEMI guidelines";
        } else if ("sob".equals(indication)) {
            protocol.protocolId = "SOB_CARDIAC";
            protocol.assessmentType = "Heart Failure Assessment";
            protocol.standardTests = "12_lead_ecg,rhythm_strip";
            protocol.interpretationGuidelines = "AHA/ACC Heart Failure guidelines";
        } else {
            protocol.protocolId = "DEFAULT_CARDIAC";
            protocol.assessmentType = "General Cardiac Assessment";
            protocol.standardTests = "12_lead_ecg";
            protocol.interpretationGuidelines = "Standard cardiac evaluation";
        }
        
        System.out.printf("[%s] Found cardiac protocol: %s\n", getPlaceId(), protocol.protocolId);
        return protocol;
    }
    
    private ECGAnalysis performECGAnalysis(String indication) {
        ECGAnalysis analysis = new ECGAnalysis();
        
        analysis.rhythm = generateRhythm(indication);
        analysis.rate = generateHeartRate(indication);
        analysis.intervals = generateIntervals(indication);
        analysis.axis = generateAxis();
        analysis.stChanges = generateSTChanges(indication);
        analysis.arrhythmias = generateArrhythmias(analysis.rhythm);
        
        return analysis;
    }
    
    private String generateRhythm(String indication) {
        if ("chest".equals(indication)) {
            String[] chestRhythms = {"sinus_rhythm", "atrial_fibrillation", "sinus_tachycardia"};
            return chestRhythms[ThreadLocalRandom.current().nextInt(chestRhythms.length)];
        } else if ("sob".equals(indication)) {
            String[] sobRhythms = {"sinus_rhythm", "atrial_fibrillation", "sinus_tachycardia"};
            return sobRhythms[ThreadLocalRandom.current().nextInt(sobRhythms.length)];
        }
        return "sinus_rhythm";
    }
    
    private int generateHeartRate(String indication) {
        if ("chest".equals(indication)) {
            return ThreadLocalRandom.current().nextInt(70, 110);
        } else if ("sob".equals(indication)) {
            return ThreadLocalRandom.current().nextInt(80, 120);
        }
        return ThreadLocalRandom.current().nextInt(60, 100);
    }
    
    private String generateIntervals(String indication) {
        if ("chest".equals(indication) && ThreadLocalRandom.current().nextBoolean()) {
            return "PR: 0.16s, QRS: 0.10s, QT: 0.42s (prolonged)";
        }
        return "PR: 0.16s, QRS: 0.08s, QT: 0.38s (normal)";
    }
    
    private String generateAxis() {
        String[] axes = {"normal", "left_axis_deviation", "right_axis_deviation"};
        return axes[ThreadLocalRandom.current().nextInt(axes.length)];
    }
    
    private String generateSTChanges(String indication) {
        if ("chest".equals(indication)) {
            String[] changes = {
                "ST elevation in V2-V4 (anterior STEMI pattern)",
                "ST depression in II, III, aVF (inferior ischemia)",
                "No acute ST changes",
                "Diffuse ST elevation (pericarditis pattern)"
            };
            return changes[ThreadLocalRandom.current().nextInt(changes.length)];
        } else if ("sob".equals(indication)) {
            String[] changes = {
                "No acute ST changes",
                "ST depression in lateral leads",
                "Non-specific T wave changes"
            };
            return changes[ThreadLocalRandom.current().nextInt(changes.length)];
        }
        return "No acute ST changes";
    }
    
    private String generateArrhythmias(String rhythm) {
        if ("atrial_fibrillation".equals(rhythm)) {
            return "Irregularly irregular rhythm, no P waves, fibrillatory waves present";
        } else if ("sinus_tachycardia".equals(rhythm)) {
            return "Regular rhythm, rate >100, normal P wave morphology";
        }
        return "None detected";
    }
    
    private CardiacRiskFactors assessCardiacRiskFactors(String indication) {
        CardiacRiskFactors factors = new CardiacRiskFactors();
        
        factors.ageRisk = "moderate";
        factors.cardiacHistory = "unknown";
        factors.riskScore = ThreadLocalRandom.current().nextInt(1, 10);
        
        return factors;
    }
    
    private String generateClinicalInterpretation(ECGAnalysis ecg, CardiacRiskFactors risk, String indication) {
        StringBuilder interp = new StringBuilder();
        
        interp.append(ecg.rhythm.replace("_", " ")).append(" with rate ").append(ecg.rate).append(" bpm. ");
        
        if (ecg.stChanges.contains("elevation") || ecg.stChanges.contains("STEMI")) {
            interp.append("CRITICAL: Acute ST elevation consistent with STEMI. Immediate cardiology consultation required. ");
        } else if (ecg.stChanges.contains("depression") || ecg.stChanges.contains("ischemia")) {
            interp.append("URGENT: ST segment depression suggesting cardiac ischemia. Urgent cardiology evaluation needed. ");
        } else {
            interp.append("No acute ST changes identified. ");
        }
        
        if ("atrial_fibrillation".equals(ecg.rhythm)) {
            interp.append("Atrial fibrillation noted - anticoagulation and rate control assessment needed. ");
        }
        
        return interp.toString();
    }
    
    private String determineRiskStratification(ECGAnalysis ecg, CardiacRiskFactors risk, String indication) {
        if (ecg.stChanges.contains("STEMI") || ecg.stChanges.contains("elevation")) {
            return "CRITICAL";
        } else if (ecg.stChanges.contains("depression") || ecg.stChanges.contains("ischemia")) {
            return "HIGH";
        } else if ("atrial_fibrillation".equals(ecg.rhythm)) {
            return "MODERATE";
        } else if (ecg.rate > 100) {
            return "MODERATE";
        }
        return "LOW";
    }
    
    private String generateRecommendations(ECGAnalysis ecg, String riskStrat, String indication) {
        StringBuilder recommendations = new StringBuilder();
        
        if ("CRITICAL".equals(riskStrat) || "HIGH".equals(riskStrat)) {
            recommendations.append("IMMEDIATE cardiology consultation, ");
            recommendations.append("Activate cardiac catheterization lab if STEMI, ");
            recommendations.append("Cardiology consultation within 24 hours, ");
            recommendations.append("Serial ECGs, ");
            recommendations.append("Continuous cardiac monitoring, ");
            recommendations.append("Stress testing if symptoms resolve");
        } else {
            recommendations.append("Clinical correlation with symptoms, ");
            recommendations.append("Consider outpatient cardiology follow-up, ");
            recommendations.append("Risk factor modification counseling");
        }
        
        if ("atrial_fibrillation".equals(ecg.rhythm)) {
            recommendations.append(", Anticoagulation assessment, Rate control evaluation");
        }
        
        return recommendations.toString();
    }
    
    // ===== UTILITY METHODS =====
    
    private TokenInfo cleanTokenInfo(TokenInfo tokenInfo) {
        TokenInfo cleaned = new TokenInfo();
        cleaned.setOriginalToken(cleanValue(tokenInfo.getOriginalToken()));
        cleaned.setPatientId(cleanValue(tokenInfo.getPatientId()));
        cleaned.setProviderId(cleanValue(tokenInfo.getProviderId()));
        cleaned.setIndication(cleanValue(tokenInfo.getIndication()));
        
        System.out.printf("[%s] CLEANED TokenInfo - Patient: %s, Provider: %s, Indication: %s\n", 
            getPlaceId(), cleaned.getPatientId(), cleaned.getProviderId(), cleaned.getIndication());
        
        return cleaned;
    }
    
    private String cleanValue(String value) {
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
            if (value.toLowerCase().contains("patient") || value.toLowerCase().contains("cardiac")) {
                return "P_Cardiac";
            } else if (value.toLowerCase().contains("dr") || value.toLowerCase().contains("card")) {
                return "DR_CARD_001";
            } else {
                return "CLEAN_VALUE";
            }
        }
        
        return cleaned;
    }
    
    // ===== FRAMEWORK METHODS =====
    
    @Override
    protected String getServiceResultsKey() {
        return "cardiologyResults";
    }
    
    @Override
    protected String getCompletionStatus() {
        return "CARDIAC_COMPLETE";
    }
    
    @Override
    protected String[] getRequiredTables() {
        return new String[0];
    }
    
    @Override
    protected void setupServiceDatabase() {
        System.out.printf("[%s] No database setup needed - service ready\n", getPlaceId());
    }
    
    // ===== CARDIAC DATA CLASSES =====
    
    public static class CardiacAssessment extends BaseServiceAssessment {
        private CardiacProtocol protocol;
        private ECGAnalysis ecgAnalysis;
        private CardiacRiskFactors riskFactors;
        private String interpretation;
        private String riskStratification;
        private String recommendations;
        
        public CardiacAssessment(String assessmentId) {
            super(assessmentId);
        }
        
        @Override
        public String toJsonFields() {
            StringBuilder json = new StringBuilder();
            
            json.append(jsonField("cardiologist", "DR_CARDIOLOGY")).append(",");
            json.append(jsonField("risk_stratification", riskStratification)).append(",");
            
            if (ecgAnalysis != null) {
                json.append(jsonObjectField("ecg_findings", 
                    jsonField("rhythm", ecgAnalysis.rhythm) + "," +
                    jsonField("rate", String.valueOf(ecgAnalysis.rate)) + "," +
                    jsonField("intervals", ecgAnalysis.intervals) + "," +
                    jsonField("axis", ecgAnalysis.axis) + "," +
                    jsonField("st_changes", ecgAnalysis.stChanges) + "," +
                    jsonField("arrhythmias", ecgAnalysis.arrhythmias)
                )).append(",");
            }
            
            if (riskFactors != null) {
                json.append(jsonObjectField("risk_factors",
                    jsonField("age_risk", riskFactors.ageRisk) + "," +
                    jsonField("cardiac_history", riskFactors.cardiacHistory) + "," +
                    jsonField("risk_score", String.valueOf(riskFactors.riskScore))
                )).append(",");
            }
            
            json.append(jsonField("interpretation", interpretation)).append(",");
            json.append(jsonField("recommendations", recommendations)).append(",");
            json.append(jsonField("protocol_id", protocol != null ? protocol.protocolId : "DEFAULT_CARDIAC"));
            
            return json.toString();
        }
        
        @Override
        public String getSummary() {
            return String.format("Cardiac Assessment: %s risk, %s rhythm", 
                riskStratification, ecgAnalysis != null ? ecgAnalysis.rhythm : "unknown");
        }
        
        public void setProtocol(CardiacProtocol protocol) { this.protocol = protocol; }
        public void setEcgAnalysis(ECGAnalysis ecgAnalysis) { this.ecgAnalysis = ecgAnalysis; }
        public void setRiskFactors(CardiacRiskFactors riskFactors) { this.riskFactors = riskFactors; }
        public void setInterpretation(String interpretation) { this.interpretation = interpretation; }
        public void setRiskStratification(String riskStratification) { this.riskStratification = riskStratification; }
        public void setRecommendations(String recommendations) { this.recommendations = recommendations; }
    }
    
    private static class CardiacProtocol {
        String protocolId;
        String assessmentType;
        String standardTests;
        String interpretationGuidelines;
    }
    
    private static class ECGAnalysis {
        String rhythm;
        int rate;
        String intervals;
        String axis;
        String stChanges;
        String arrhythmias;
    }
    
    private static class CardiacRiskFactors {
        String ageRisk;
        String cardiacHistory;
        int riskScore;
    }
}