package org.btsn.places;

import org.btsn.base.BaseHealthcareService;
import org.btsn.base.BaseServiceAssessment;
import org.btsn.base.ServiceAssessment;
import org.btsn.base.TokenInfo;
import org.btsn.exceptions.ServiceProcessingException;

import java.util.*;

/**
 * DiagnosisService - PURE BUSINESS LOGIC
 * 
 * RESPONSIBILITY: Integrate diagnostic data from multiple sources and make clinical decisions
 * 
 * ARCHITECTURAL CONTRACT:
 * - Receives CLEAN business data from THREE sources (radiology, laboratory, cardiology) via JOIN
 * - Returns PURE business results (clinical diagnosis, treatment plan, disposition)
 * - ZERO knowledge of tokens, timing, metadata, or infrastructure
 * 
 * Infrastructure concerns (token preservation, timing, enrichment) handled by ServiceHelper
 */
public class DiagnosisService extends BaseHealthcareService {
    
    // ===== STATIC INITIALIZATION =====
    
    static {
        System.out.println("=== DIAGNOSIS SERVICE INITIALIZING ===");
        System.out.println("Pure Business Logic - No Infrastructure Concerns");
        System.out.println("Clinical decision protocols loaded");
        System.out.println("=== DIAGNOSIS SERVICE READY ===");
    }
    
    // ===== INSTANCE FIELDS =====
    
    private final String physicianId;
    
    // ===== CONSTRUCTORS =====
    
    public DiagnosisService(String sequenceID) {
        super(sequenceID, "DIAGNOSIS");
        this.physicianId = generatePhysicianId(sequenceID);
        
        System.out.printf("[%s] DiagnosisService instance created - sequenceID: %s, physician: %s\n", 
            getPlaceId(), sequenceID, physicianId);
    }
    
    private static String generatePhysicianId(String sequenceID) {
        try {
            return "DR_EM_" + (Integer.parseInt(sequenceID) % 1000);
        } catch (NumberFormatException e) {
            return "DR_EM_" + Math.abs(sequenceID.hashCode() % 1000);
        }
    }
    
    // ===== BUSINESS METHODS =====
    
    /**
     * Main entry point - SIMPLIFIED to pure business logic
     * 
     * ServiceHelper contract:
     * - Receives: Clean business data from THREE diagnostic services (JOIN node)
     * - Returns: Pure business results (clinical decision)
     * - Infrastructure (tokens, timing) handled by ServiceHelper
     * 
     * Note: This service receives data from a JOIN node, so all three inputs
     * should be present (Radiology, Laboratory, Cardiology results)
     */
    public String processClinicalDecision(String radiologyResults, String laboratoryResults, String cardiologyResults) {
        System.out.printf("[%s] ========== CLINICAL DECISION START ==========\n", getPlaceId());
        System.out.printf("[%s] Processing clinical decision for sequenceID: %s\n", getPlaceId(), getSequenceID());
        System.out.printf("[%s] Processing inputs from: Radiology, Laboratory, Cardiology\n", getPlaceId());
        
        System.out.printf("[%s] Radiology input length: %d\n", getPlaceId(), 
            radiologyResults != null ? radiologyResults.length() : 0);
        System.out.printf("[%s] Laboratory input length: %d\n", getPlaceId(), 
            laboratoryResults != null ? laboratoryResults.length() : 0);
        System.out.printf("[%s] Cardiology input length: %d\n", getPlaceId(), 
            cardiologyResults != null ? cardiologyResults.length() : 0);
        
        try {
            // Combine the inputs for framework processing
            String combinedInput = combineServiceInputs(radiologyResults, laboratoryResults, cardiologyResults);
            System.out.printf("[%s] Combined input created successfully\n", getPlaceId());
            
            // Process the diagnosis using framework - returns pure business results
            String businessResults = processAssessment(combinedInput);
            System.out.printf("[%s] Assessment processing completed\n", getPlaceId());
            
            System.out.printf("[%s] ========== CLINICAL DECISION COMPLETE ==========\n", getPlaceId());
            
            // Return pure business results - ServiceHelper will enrich with metadata
            return businessResults;
            
        } catch (Exception e) {
            System.err.printf("[%s] Error in clinical decision processing: %s\n", getPlaceId(), e.getMessage());
            e.printStackTrace();
            
            // Return a valid JSON error response so workflow can continue
            return "{\"diagnosisResults\":{\"error\":\"Clinical decision processing failed: " + 
                   e.getMessage() + "\",\"status\":\"ERROR\"}}";
        }
    }
    
    /**
     * Combine service inputs into framework-compatible format
     * This is pure business logic - combining diagnostic data from multiple sources
     */
    private String combineServiceInputs(String radiologyResults, String laboratoryResults, String cardiologyResults) {
        System.out.printf("[%s] === COMBINING SERVICE INPUTS ===\n", getPlaceId());
        
        try {
            StringBuilder combined = new StringBuilder();
            combined.append("{\"combined_clinical_data\":{");
            
            boolean hasContent = false;
            
            if (radiologyResults != null && !radiologyResults.trim().isEmpty() && !radiologyResults.contains("ERROR")) {
                combined.append("\"radiology\":").append(radiologyResults);
                hasContent = true;
                System.out.printf("[%s] Added radiology results\n", getPlaceId());
            }
            
            if (laboratoryResults != null && !laboratoryResults.trim().isEmpty() && !laboratoryResults.contains("ERROR")) {
                if (hasContent) combined.append(",");
                combined.append("\"laboratory\":").append(laboratoryResults);
                hasContent = true;
                System.out.printf("[%s] Added laboratory results\n", getPlaceId());
            }
            
            if (cardiologyResults != null && !cardiologyResults.trim().isEmpty() && !cardiologyResults.contains("ERROR")) {
                if (hasContent) combined.append(",");
                combined.append("\"cardiology\":").append(cardiologyResults);
                hasContent = true;
                System.out.printf("[%s] Added cardiology results\n", getPlaceId());
            }
            
            combined.append("}}");
            
            if (!hasContent) {
                System.err.printf("[%s] WARNING: No valid service inputs found\n", getPlaceId());
                return "{\"combined_clinical_data\":{\"error\":\"No valid inputs\"}}";
            }
            
            System.out.printf("[%s] Successfully combined %s service inputs\n", getPlaceId(),
                hasContent ? "all" : "partial");
            
            return combined.toString();
            
        } catch (Exception e) {
            System.err.printf("[%s] Error combining inputs: %s\n", getPlaceId(), e.getMessage());
            e.printStackTrace();
            return "{\"combined_clinical_data\":{\"error\":\"Failed to combine inputs\"}}";
        }
    }
    
    // ===== FRAMEWORK IMPLEMENTATION METHODS =====
    
    @Override
    protected ServiceAssessment processServiceSpecificAssessment(TokenInfo tokenInfo) 
            throws ServiceProcessingException {
        try {
            System.out.printf("[%s] Processing specific diagnosis assessment\n", getPlaceId());
            
            // Clean input data
            tokenInfo = cleanTokenInfo(tokenInfo);
            
            // Extract clinical data from combined inputs
            ClinicalData clinicalData = extractClinicalData(tokenInfo);
            
            // Get clinical protocol
            ClinicalProtocol protocol = getClinicalProtocol(clinicalData);
            
            // Generate assessment ID
            String assessmentId = generateAssessmentId("DIAGNOSIS", clinicalData.patientId);
            assessmentId = cleanValue(assessmentId);
            
            // Analyze findings from all sources
            ClinicalFindings findings = analyzeClinicalFindings(clinicalData);
            
            // Generate primary diagnosis
            String primaryDiagnosis = generatePrimaryDiagnosis(clinicalData, findings);
            
            // Generate differential diagnoses
            List<String> differentials = generateDifferentialDiagnoses(clinicalData, findings);
            
            // Generate clinical reasoning
            String reasoning = generateClinicalReasoning(clinicalData, findings, primaryDiagnosis);
            
            // Determine disposition
            String disposition = determineDisposition(clinicalData, findings, primaryDiagnosis);
            
            // Create treatment plan
            TreatmentPlan treatmentPlan = createTreatmentPlan(clinicalData, findings, primaryDiagnosis);
            
            // Determine confidence level
            String confidence = determineConfidence(findings, clinicalData);
            
            // Create assessment result
            DiagnosisAssessment assessment = new DiagnosisAssessment(assessmentId);
            assessment.setProtocol(protocol);
            assessment.setClinicalData(clinicalData);
            assessment.setFindings(findings);
            assessment.setPrimaryDiagnosis(primaryDiagnosis);
            assessment.setDifferentialDiagnoses(differentials);
            assessment.setClinicalReasoning(reasoning);
            assessment.setDisposition(disposition);
            assessment.setTreatmentPlan(treatmentPlan);
            assessment.setConfidenceLevel(confidence);
            
            System.out.printf("[%s] Diagnosis assessment completed - Primary: %s, Disposition: %s\n", 
                getPlaceId(), primaryDiagnosis, disposition);
            
            return assessment;
            
        } catch (Exception e) {
            System.err.printf("[%s] Error in diagnosis assessment: %s\n", getPlaceId(), e.getMessage());
            e.printStackTrace();
            throw new ServiceProcessingException("Error during diagnosis assessment", e);
        }
    }
    
    // ===== DIAGNOSIS BUSINESS LOGIC =====
    
    private ClinicalData extractClinicalData(TokenInfo tokenInfo) {
        ClinicalData data = new ClinicalData();
        data.patientId = tokenInfo.getPatientId();
        data.indication = tokenInfo.getIndication();
        
        // Extract from original token if available
        String originalToken = tokenInfo.getOriginalToken();
        if (originalToken != null && originalToken.contains("radiology")) {
            data.radiologyFindings = "Imaging findings extracted";
            data.radiologyImpression = "Clinical impression extracted";
        }
        if (originalToken != null && originalToken.contains("laboratory")) {
            data.criticalLabValues = "Lab values extracted";
            data.labSummary = "Lab summary extracted";
        }
        if (originalToken != null && originalToken.contains("cardiology")) {
            data.ecgFindings = "ECG findings extracted";
            data.cardiacRisk = "Cardiac risk assessed";
        }
        
        return data;
    }
    
    private ClinicalProtocol getClinicalProtocol(ClinicalData data) {
        ClinicalProtocol protocol = new ClinicalProtocol();
        
        if ("chest".equals(data.indication)) {
            protocol.protocolId = "CHEST_PAIN_DIAGNOSIS";
            protocol.diagnosisCriteria = "ACS criteria, Aortic dissection, PE, Pneumothorax";
            protocol.differentialDiagnoses = "STEMI, NSTEMI, Unstable angina, Aortic dissection, PE";
            protocol.recommendedTreatment = "Based on diagnosis: reperfusion, anticoagulation, or supportive";
        } else if ("sob".equals(data.indication)) {
            protocol.protocolId = "SOB_DIAGNOSIS";
            protocol.diagnosisCriteria = "Heart failure, COPD, Pneumonia, PE";
            protocol.differentialDiagnoses = "Acute heart failure, COPD exacerbation, Pneumonia, PE, Asthma";
            protocol.recommendedTreatment = "Diuretics, bronchodilators, antibiotics, anticoagulation as indicated";
        } else {
            protocol.protocolId = "GENERAL_DIAGNOSIS";
            protocol.diagnosisCriteria = "Clinical assessment based on available data";
            protocol.differentialDiagnoses = "Multiple possibilities based on presentation";
            protocol.recommendedTreatment = "Supportive care and targeted treatment";
        }
        
        return protocol;
    }
    
    private ClinicalFindings analyzeClinicalFindings(ClinicalData data) {
        ClinicalFindings findings = new ClinicalFindings();
        
        findings.imagingAbnormal = data.radiologyFindings != null && 
                                   !data.radiologyFindings.contains("normal");
        findings.labsAbnormal = data.criticalLabValues != null && 
                               !data.criticalLabValues.contains("normal");
        findings.cardiacAbnormal = data.ecgFindings != null && 
                                   !data.ecgFindings.contains("normal");
        
        // Determine severity
        int abnormalCount = 0;
        if (findings.imagingAbnormal) abnormalCount++;
        if (findings.labsAbnormal) abnormalCount++;
        if (findings.cardiacAbnormal) abnormalCount++;
        
        if (abnormalCount >= 2) {
            findings.severityLevel = "CRITICAL";
        } else if (abnormalCount == 1) {
            findings.severityLevel = "MODERATE";
        } else {
            findings.severityLevel = "MILD";
        }
        
        return findings;
    }
    
    private String generatePrimaryDiagnosis(ClinicalData data, ClinicalFindings findings) {
        if ("chest".equals(data.indication)) {
            if (findings.cardiacAbnormal && findings.labsAbnormal) {
                return "Acute Coronary Syndrome - NSTEMI";
            } else if (findings.cardiacAbnormal) {
                return "Acute Coronary Syndrome - Unstable Angina";
            } else if (findings.imagingAbnormal) {
                return "Chest Pain - Non-cardiac etiology, imaging abnormality noted";
            } else {
                return "Chest Pain - Low probability ACS";
            }
        } else if ("sob".equals(data.indication)) {
            if (findings.cardiacAbnormal && findings.imagingAbnormal) {
                return "Acute Decompensated Heart Failure";
            } else if (findings.imagingAbnormal) {
                return "Pneumonia vs Pulmonary Edema";
            } else {
                return "Shortness of Breath - Likely COPD exacerbation";
            }
        } else {
            return "Clinical assessment based on available findings";
        }
    }
    
    private List<String> generateDifferentialDiagnoses(ClinicalData data, ClinicalFindings findings) {
        List<String> differentials = new ArrayList<>();
        
        if ("chest".equals(data.indication)) {
            differentials.add("Acute Coronary Syndrome");
            differentials.add("Pulmonary Embolism");
            differentials.add("Aortic Dissection");
            if (findings.imagingAbnormal) {
                differentials.add("Pneumothorax");
                differentials.add("Pneumonia");
            }
        } else if ("sob".equals(data.indication)) {
            differentials.add("Acute Heart Failure");
            differentials.add("COPD Exacerbation");
            differentials.add("Pneumonia");
            differentials.add("Pulmonary Embolism");
            differentials.add("Asthma Exacerbation");
        } else {
            differentials.add("Differential diagnosis based on clinical presentation");
        }
        
        return differentials;
    }
    
    private String generateClinicalReasoning(ClinicalData data, ClinicalFindings findings, String primaryDx) {
        StringBuilder reasoning = new StringBuilder();
        
        reasoning.append("Clinical synthesis: ");
        reasoning.append("Patient presents with ").append(data.indication).append(". ");
        
        if (findings.cardiacAbnormal) {
            reasoning.append("Cardiac testing shows abnormalities. ");
        }
        if (findings.imagingAbnormal) {
            reasoning.append("Imaging reveals significant findings. ");
        }
        if (findings.labsAbnormal) {
            reasoning.append("Laboratory values support acute process. ");
        }
        
        reasoning.append("Primary diagnosis: ").append(primaryDx).append(". ");
        reasoning.append("Severity assessed as ").append(findings.severityLevel).append(".");
        
        return reasoning.toString();
    }
    
    private String determineDisposition(ClinicalData data, ClinicalFindings findings, String primaryDx) {
        if ("CRITICAL".equals(findings.severityLevel)) {
            if (primaryDx.contains("STEMI") || primaryDx.contains("dissection")) {
                return "IMMEDIATE_ICU_ADMISSION";
            } else {
                return "URGENT_ADMISSION";
            }
        } else if ("MODERATE".equals(findings.severityLevel)) {
            return "OBSERVATION_UNIT";
        } else {
            return "OUTPATIENT_FOLLOWUP";
        }
    }
    
    private TreatmentPlan createTreatmentPlan(ClinicalData data, ClinicalFindings findings, String primaryDx) {
        TreatmentPlan plan = new TreatmentPlan();
        
        if (primaryDx.contains("Coronary")) {
            plan.medications.add("Aspirin 325mg");
            plan.medications.add("Clopidogrel 600mg loading");
            plan.medications.add("Atorvastatin 80mg");
            plan.medications.add("Metoprolol 25mg");
            
            if (primaryDx.contains("STEMI")) {
                plan.urgentInterventions.add("Activate Cath Lab STAT");
                plan.urgentInterventions.add("Cardiology consultation STAT");
            } else {
                plan.urgentInterventions.add("Cardiology consultation urgent");
                plan.urgentInterventions.add("Serial troponins");
            }
            
            plan.monitoring.add("Continuous cardiac monitoring");
            plan.monitoring.add("Serial ECGs");
            plan.safetyAlerts.add("Bleeding precautions");
        } else if (primaryDx.contains("Heart Failure")) {
            plan.medications.add("Furosemide IV");
            plan.medications.add("Lisinopril");
            plan.medications.add("Metoprolol");
            
            plan.urgentInterventions.add("Diuresis protocol");
            plan.monitoring.add("Daily weights");
            plan.monitoring.add("Intake/Output");
        } else if (primaryDx.contains("Pneumonia")) {
            plan.medications.add("Ceftriaxone 1g IV");
            plan.medications.add("Azithromycin 500mg");
            
            plan.monitoring.add("Oxygen saturation");
            plan.monitoring.add("Respiratory status");
        }
        
        return plan;
    }
    
    private String determineConfidence(ClinicalFindings findings, ClinicalData data) {
        int dataPoints = 0;
        if (data.radiologyFindings != null) dataPoints++;
        if (data.criticalLabValues != null) dataPoints++;
        if (data.ecgFindings != null) dataPoints++;
        
        if (dataPoints >= 3) {
            return "HIGH";
        } else if (dataPoints == 2) {
            return "MODERATE";
        } else {
            return "LOW";
        }
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
            if (value.toLowerCase().contains("patient") || value.toLowerCase().contains("diagnosis")) {
                return "P_Diagnosis";
            } else if (value.toLowerCase().contains("dr") || value.toLowerCase().contains("em")) {
                return "DR_EM_001";
            } else {
                return "CLEAN_VALUE";
            }
        }
        
        return cleaned;
    }
    
    // ===== FRAMEWORK METHODS =====
    
    @Override
    protected String getServiceResultsKey() {
        return "diagnosisResults";
    }
    
    @Override
    protected String getCompletionStatus() {
        return "DIAGNOSIS_COMPLETE";
    }
    
    @Override
    protected String[] getRequiredTables() {
        return new String[0];
    }
    
    @Override
    protected void setupServiceDatabase() {
        System.out.printf("[%s] No database setup needed - service ready\n", getPlaceId());
    }
    
    // ===== DIAGNOSIS DATA CLASSES =====
    
    public static class DiagnosisAssessment extends BaseServiceAssessment {
        private ClinicalProtocol protocol;
        private ClinicalData clinicalData;
        private ClinicalFindings findings;
        private String primaryDiagnosis;
        private List<String> differentialDiagnoses;
        private String clinicalReasoning;
        private String disposition;
        private TreatmentPlan treatmentPlan;
        private String confidenceLevel;
        
        public DiagnosisAssessment(String assessmentId) {
            super(assessmentId);
        }
        
        @Override
        public String toJsonFields() {
            StringBuilder json = new StringBuilder();
            
            json.append(jsonField("physician", "DR_EM_EMERGENCY")).append(",");
            json.append(jsonField("primary_diagnosis", primaryDiagnosis)).append(",");
            
            if (differentialDiagnoses != null && !differentialDiagnoses.isEmpty()) {
                json.append("\"differential_diagnoses\":[");
                for (int i = 0; i < differentialDiagnoses.size(); i++) {
                    json.append("\"").append(differentialDiagnoses.get(i)).append("\"");
                    if (i < differentialDiagnoses.size() - 1) json.append(",");
                }
                json.append("],");
            }
            
            json.append(jsonField("clinical_reasoning", clinicalReasoning)).append(",");
            json.append(jsonField("disposition", disposition)).append(",");
            
            if (treatmentPlan != null) {
                if (treatmentPlan.medications != null && !treatmentPlan.medications.isEmpty()) {
                    json.append("\"medications\":[");
                    for (int i = 0; i < treatmentPlan.medications.size(); i++) {
                        json.append("\"").append(treatmentPlan.medications.get(i)).append("\"");
                        if (i < treatmentPlan.medications.size() - 1) json.append(",");
                    }
                    json.append("],");
                }
                
                if (treatmentPlan.urgentInterventions != null && !treatmentPlan.urgentInterventions.isEmpty()) {
                    json.append("\"urgent_interventions\":[");
                    for (int i = 0; i < treatmentPlan.urgentInterventions.size(); i++) {
                        json.append("\"").append(treatmentPlan.urgentInterventions.get(i)).append("\"");
                        if (i < treatmentPlan.urgentInterventions.size() - 1) json.append(",");
                    }
                    json.append("],");
                }
            }
            
            json.append(jsonField("confidence_level", confidenceLevel)).append(",");
            json.append(jsonField("protocol_id", protocol != null ? protocol.protocolId : "DEFAULT_CLINICAL"));
            
            return json.toString();
        }
        
        @Override
        public String getSummary() {
            return String.format("Clinical Decision: %s, Disposition: %s", primaryDiagnosis, disposition);
        }
        
        public void setProtocol(ClinicalProtocol protocol) { this.protocol = protocol; }
        public void setClinicalData(ClinicalData clinicalData) { this.clinicalData = clinicalData; }
        public void setFindings(ClinicalFindings findings) { this.findings = findings; }
        public void setPrimaryDiagnosis(String primaryDiagnosis) { this.primaryDiagnosis = primaryDiagnosis; }
        public void setDifferentialDiagnoses(List<String> differentialDiagnoses) { this.differentialDiagnoses = differentialDiagnoses; }
        public void setClinicalReasoning(String clinicalReasoning) { this.clinicalReasoning = clinicalReasoning; }
        public void setDisposition(String disposition) { this.disposition = disposition; }
        public void setTreatmentPlan(TreatmentPlan treatmentPlan) { this.treatmentPlan = treatmentPlan; }
        public void setConfidenceLevel(String confidenceLevel) { this.confidenceLevel = confidenceLevel; }
    }
    
    private static class ClinicalData {
        String patientId;
        String indication;
        String radiologyFindings;
        String radiologyImpression;
        String criticalLabValues;
        String labSummary;
        String ecgFindings;
        String cardiacRisk;
    }
    
    private static class ClinicalProtocol {
        String protocolId;
        String diagnosisCriteria;
        String differentialDiagnoses;
        String recommendedTreatment;
    }
    
    private static class ClinicalFindings {
        boolean imagingAbnormal;
        boolean labsAbnormal;
        boolean cardiacAbnormal;
        String severityLevel;
    }
    
    private static class TreatmentPlan {
        List<String> medications = new ArrayList<>();
        List<String> urgentInterventions = new ArrayList<>();
        List<String> monitoring = new ArrayList<>();
        List<String> safetyAlerts = new ArrayList<>();
    }
}