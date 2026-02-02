package org.btsn.places;

import org.btsn.base.BaseHealthcareService;
import org.btsn.base.BaseServiceAssessment;
import org.btsn.base.ServiceAssessment;
import org.btsn.base.TokenInfo;
import org.btsn.exceptions.ServiceProcessingException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * TreatmentService - PURE BUSINESS LOGIC
 * 
 * RESPONSIBILITY: Execute treatment plans and provide patient care
 * 
 * ARCHITECTURAL CONTRACT:
 * - Receives CLEAN business data (diagnosis results OR triage results for feedforward) from ServiceHelper
 * - Returns PURE business results (treatment execution, medications, procedures, discharge planning)
 * - ZERO knowledge of tokens, timing, metadata, or infrastructure
 * 
 * SPECIAL CAPABILITIES:
 * - Standard treatment execution from Diagnosis results
 * - FEEDFORWARD: Direct treatment from Triage for simple cases
 * - Final service in healthcare workflow
 * 
 * Infrastructure concerns (token preservation, timing, enrichment) handled by ServiceHelper
 */
public class TreatmentService extends BaseHealthcareService {
    
    // ===== STATIC INITIALIZATION =====
    
    static {
        System.out.println("=== TREATMENT SERVICE INITIALIZING ===");
        System.out.println("Pure Business Logic - No Infrastructure Concerns");
        System.out.println("Treatment protocols loaded");
        System.out.println("ðŸ�� FINAL SERVICE IN HEALTHCARE WORKFLOW");
        System.out.println("ðŸ”„ FEEDFORWARD ENABLED - Direct from Triage support");
        System.out.println("=== TREATMENT SERVICE READY ===");
    }
    
    // ===== DATABASE CONSTANTS =====
    
    private static final String TREATMENT_DB_PROTOCOL = "jdbc:derby:";
    private static final String TREATMENT_DB_NAME = "./TreatmentServiceDB";
    private static final String TREATMENT_DB_URL = TREATMENT_DB_PROTOCOL + TREATMENT_DB_NAME + ";create=true";
    
    // ===== INSTANCE FIELDS =====
    
    private final String nurseId;
    
    // ===== CONSTRUCTORS =====
    
    public TreatmentService(String sequenceID) {
        super(sequenceID, "TREATMENT");
        this.nurseId = generateNurseId(sequenceID);
        
        System.out.printf("[%s] TreatmentService instance created - sequenceID: %s, nurse: %s\n", 
            getPlaceId(), sequenceID, nurseId);
        System.out.printf("[%s] ðŸ�� FINAL SERVICE: Patient care conclusion\n", getPlaceId());
        System.out.printf("[%s] ðŸ”„ FEEDFORWARD: Direct triage-to-treatment support enabled\n", getPlaceId());
    }
    
    private static String generateNurseId(String sequenceID) {
        try {
            return "RN_TREAT_" + (Integer.parseInt(sequenceID) % 1000);
        } catch (NumberFormatException e) {
            return "RN_TREAT_" + Math.abs(sequenceID.hashCode() % 1000);
        }
    }
    
    // ===== MAIN SERVICE METHODS =====
    
    /**
     * Main entry point - Standard treatment from Diagnosis
     * 
     * ServiceHelper contract:
     * - Receives: Clean business data (diagnosis results with treatment plan)
     * - Returns: Pure business results (treatment execution details)
     * - Infrastructure (tokens, timing) handled by ServiceHelper
     */
    public String executeTreatmentPlan(String cleanBusinessData) {
        System.out.printf("[%s] ========== TREATMENT SERVICE EXECUTION START ==========\n", getPlaceId());
        System.out.printf("[%s] Processing treatment execution for sequenceID: %s\n", getPlaceId(), getSequenceID());
        System.out.printf("[%s] ðŸ�� FINAL SERVICE: Processing treatment for workflow conclusion\n", getPlaceId());
        System.out.printf("[%s] Received clean business data length: %d\n", getPlaceId(), 
            cleanBusinessData != null ? cleanBusinessData.length() : 0);
        
        try {
            // Extract inner diagnosis data if nested
            String actualDiagnosisData = extractInnerDiagnosisResults(cleanBusinessData);
            
            System.out.printf("[%s] Processed data length: %d\n", getPlaceId(), 
                actualDiagnosisData != null ? actualDiagnosisData.length() : 0);
            
            // Process assessment using framework - returns pure business results
            String businessResults = processAssessment(actualDiagnosisData);
            
            System.out.printf("[%s] ========== TREATMENT EXECUTION COMPLETE ==========\n", getPlaceId());
            System.out.printf("[%s] ðŸ�� HEALTHCARE WORKFLOW COMPLETE - PATIENT CARE CONCLUDED ðŸ��\n", getPlaceId());
            
            // Return pure business results - ServiceHelper will enrich with metadata
            return businessResults;
            
        } catch (Exception e) {
            System.err.printf("[%s] Error in executeTreatmentPlan: %s\n", getPlaceId(), e.getMessage());
            e.printStackTrace();
            
            return String.format("{\"treatmentResults\":{\"status\":\"ERROR\",\"message\":\"Treatment execution failed: %s\",\"sequence\":\"%s\",\"place\":\"%s\"}}", 
                e.getMessage(), getSequenceID(), getPlaceId());
        }
    }
    
    /**
     * FEEDFORWARD: Direct treatment from Triage - PURE BUSINESS LOGIC
     * 
     * Bypasses full diagnostic workup for simple cases.
     * This is a legitimate business use case, not infrastructure.
     * 
     * Business responsibilities:
     * - Evaluate if condition is appropriate for direct treatment
     * - Execute simple treatment protocols
     * - Provide discharge instructions
     * 
     * ServiceHelper contract:
     * - Receives: Clean business data (triage results)
     * - Returns: Pure business results (direct treatment execution)
     * - Infrastructure (tokens, timing) handled by ServiceHelper
     * 
     * USE CASES: Minor injuries, routine medications, discharge instructions
     */
    public String executeDirectTreatment(String cleanBusinessData) {
        System.out.printf("[%s] ========== DIRECT TREATMENT (FEEDFORWARD) START ==========\n", getPlaceId());
        System.out.printf("[%s] Processing direct treatment from triage for sequenceID: %s\n", getPlaceId(), getSequenceID());
        System.out.printf("[%s] ðŸ”„ FEEDFORWARD: Bypassing diagnostic services for simple case\n", getPlaceId());
        System.out.printf("[%s] Received clean business data length: %d\n", getPlaceId(), 
            cleanBusinessData != null ? cleanBusinessData.length() : 0);
        
        try {
            // Extract inner triage data if nested
            String actualTriageData = extractInnerTriageResults(cleanBusinessData);
            
            System.out.printf("[%s] Processed triage data length: %d\n", getPlaceId(), 
                actualTriageData != null ? actualTriageData.length() : 0);
            
            // Process direct treatment using framework - returns pure business results
            String businessResults = processDirectTreatmentAssessment(actualTriageData);
            
            System.out.printf("[%s] ========== DIRECT TREATMENT COMPLETE ==========\n", getPlaceId());
            System.out.printf("[%s] ðŸŽ¯ FEEDFORWARD SUCCESS - SIMPLE CASE RESOLVED EFFICIENTLY ðŸŽ¯\n", getPlaceId());
            
            // Return pure business results - ServiceHelper will enrich with metadata
            return businessResults;
            
        } catch (Exception e) {
            System.err.printf("[%s] Error in executeDirectTreatment: %s\n", getPlaceId(), e.getMessage());
            e.printStackTrace();
            
            return String.format("{\"treatmentResults\":{\"status\":\"ERROR\",\"message\":\"Direct treatment failed: %s\",\"sequence\":\"%s\",\"place\":\"%s\",\"feedforward\":true}}", 
                e.getMessage(), getSequenceID(), getPlaceId());
        }
    }
    
    // ===== FRAMEWORK IMPLEMENTATION METHODS =====
    
    @Override
    protected ServiceAssessment processServiceSpecificAssessment(TokenInfo tokenInfo) 
            throws ServiceProcessingException {
        try {
            System.out.printf("[%s] Processing specific treatment assessment\n", getPlaceId());
            
            // Clean input data
            tokenInfo = cleanTokenInfo(tokenInfo);
            
            // Extract clinical decision from diagnosis results
            ClinicalDecision clinicalDecision = extractClinicalDecision(tokenInfo);
            
            // Get treatment protocol
            TreatmentProtocol protocol = getTreatmentProtocol(clinicalDecision);
            
            // Generate treatment ID
            String treatmentId = generateAssessmentId("TREAT", tokenInfo.getPatientId());
            treatmentId = cleanValue(treatmentId);
            
            // Execute treatment plan - pure business logic
            MedicationAdministration medicationAdmin = administerMedications(clinicalDecision.medications);
            ProcedureExecution procedureExecution = executeProcedures(clinicalDecision.procedures);
            PatientMonitoring monitoring = performMonitoring(clinicalDecision.monitoring);
            TreatmentResponse response = assessTreatmentResponse(medicationAdmin, procedureExecution, monitoring);
            
            // Identify any adverse events
            List<String> adverseEvents = identifyAdverseEvents(medicationAdmin, procedureExecution);
            
            // Determine treatment goals and discharge readiness
            boolean treatmentGoalsMet = evaluateTreatmentGoals(response);
            boolean dischargeReady = assessDischargeReadiness(response, adverseEvents);
            
            // Create assessment result
            TreatmentAssessment assessment = new TreatmentAssessment(treatmentId);
            assessment.setProtocol(protocol);
            assessment.setClinicalDecision(clinicalDecision);
            assessment.setMedicationAdmin(medicationAdmin);
            assessment.setProcedureExecution(procedureExecution);
            assessment.setMonitoring(monitoring);
            assessment.setResponse(response);
            assessment.setAdverseEvents(adverseEvents);
            assessment.setTreatmentGoalsMet(treatmentGoalsMet);
            assessment.setDischargeReady(dischargeReady);
            
            System.out.printf("[%s] Treatment assessment completed - Goals met: %s, Discharge ready: %s\n", 
                getPlaceId(), treatmentGoalsMet, dischargeReady);
            
            return assessment;
            
        } catch (Exception e) {
            System.err.printf("[%s] Error in treatment assessment: %s\n", getPlaceId(), e.getMessage());
            e.printStackTrace();
            throw new ServiceProcessingException("Treatment execution failed", e);
        }
    }
    
    // ===== DIRECT TREATMENT PROCESSING (FEEDFORWARD) =====
    
    /**
     * Process direct treatment from triage results (feedforward path)
     */
    private String processDirectTreatmentAssessment(String triageData) {
        try {
            // Parse triage data
            TokenInfo triageTokenInfo = parseTokenInfo(triageData);
            triageTokenInfo = cleanTokenInfo(triageTokenInfo);
            
            // Generate treatment ID
            String treatmentId = generateAssessmentId("DIRECT_TREAT", triageTokenInfo.getPatientId());
            
            // Assess if appropriate for direct treatment
            DirectTreatmentDecision decision = assessDirectTreatmentAppropriate(triageTokenInfo);
            
            if (!decision.appropriateForDirectTreatment) {
                System.out.printf("[%s] Case not appropriate for direct treatment: %s\n", 
                    getPlaceId(), decision.redirectReason);
                
                // Create response indicating redirect needed
                DirectTreatmentAssessment assessment = new DirectTreatmentAssessment(treatmentId);
                assessment.setTriageTokenInfo(triageTokenInfo);
                assessment.setDecision(decision);
                assessment.setProcessingPlace(getPlaceId());
                assessment.setCompletionTime(getCurrentTimestamp());
                
                return wrapInTreatmentResults(assessment.toJsonString());
            }
            
            // Execute direct treatment
            DirectTreatmentExecution execution = executeSimpleTreatment(triageTokenInfo, decision);
            
            // Create assessment result
            DirectTreatmentAssessment assessment = new DirectTreatmentAssessment(treatmentId);
            assessment.setTriageTokenInfo(triageTokenInfo);
            assessment.setDecision(decision);
            assessment.setExecution(execution);
            assessment.setProcessingPlace(getPlaceId());
            assessment.setCompletionTime(getCurrentTimestamp());
            
            System.out.printf("[%s] Direct treatment completed successfully\n", getPlaceId());
            
            return wrapInTreatmentResults(assessment.toJsonString());
            
        } catch (Exception e) {
            System.err.printf("[%s] Error in direct treatment assessment: %s\n", getPlaceId(), e.getMessage());
            e.printStackTrace();
            return "{\"error\":\"Direct treatment assessment failed: " + e.getMessage() + "\"}";
        }
    }
    
    // ===== TREATMENT BUSINESS LOGIC =====
    
    /**
     * Extract clinical decision from diagnosis results
     */
    private ClinicalDecision extractClinicalDecision(TokenInfo tokenInfo) {
        ClinicalDecision decision = new ClinicalDecision();
        
        String originalToken = tokenInfo.getOriginalToken();
        if (originalToken != null) {
            // Extract diagnosis and treatment plan from token
            decision.primaryDiagnosis = extractValue(originalToken, "primary_diagnosis", "Unknown diagnosis");
            decision.disposition = extractValue(originalToken, "disposition", "OBSERVATION");
            
            // Extract medications
            String medicationsStr = extractArrayValue(originalToken, "medications");
            if (medicationsStr != null && !medicationsStr.isEmpty()) {
                decision.medications = Arrays.asList(medicationsStr.split(","));
            }
            
            // Extract procedures
            String proceduresStr = extractArrayValue(originalToken, "urgent_interventions");
            if (proceduresStr != null && !proceduresStr.isEmpty()) {
                decision.procedures = Arrays.asList(proceduresStr.split(","));
            }
            
            // Set monitoring requirements based on diagnosis
            decision.monitoring.add("vital_signs_q15min");
            decision.monitoring.add("cardiac_monitoring");
            
            // Set safety alerts based on diagnosis
            if (decision.primaryDiagnosis.toLowerCase().contains("coronary") || 
                decision.primaryDiagnosis.toLowerCase().contains("cardiac")) {
                decision.safetyAlerts.add("Bleeding precautions - on antiplatelet therapy");
                decision.safetyAlerts.add("Monitor for chest pain recurrence");
            }
        } else {
            // Default treatment plan
            decision.primaryDiagnosis = "Chest pain - undifferentiated";
            decision.disposition = "OBSERVATION";
            decision.medications.add("Aspirin 325mg");
            decision.monitoring.add("vital_signs_q30min");
        }
        
        return decision;
    }
    
    /**
     * Get treatment protocol based on clinical decision
     */
    private TreatmentProtocol getTreatmentProtocol(ClinicalDecision clinicalDecision) {
        TreatmentProtocol protocol = new TreatmentProtocol();
        
        if (clinicalDecision.primaryDiagnosis.toLowerCase().contains("coronary") ||
            clinicalDecision.primaryDiagnosis.toLowerCase().contains("stemi") ||
            clinicalDecision.primaryDiagnosis.toLowerCase().contains("nstemi")) {
            protocol.protocolId = "ACS_TREATMENT_01";
            protocol.treatmentCategory = "Acute Coronary Syndrome";
            protocol.medicationGuidelines = "Dual antiplatelet therapy, statin, beta blocker";
            protocol.monitoringRequirements = "Continuous cardiac monitoring, serial troponins";
        } else if (clinicalDecision.primaryDiagnosis.toLowerCase().contains("heart failure")) {
            protocol.protocolId = "HF_TREATMENT_01";
            protocol.treatmentCategory = "Heart Failure";
            protocol.medicationGuidelines = "Diuretics, ACE inhibitor, beta blocker";
            protocol.monitoringRequirements = "Daily weights, intake/output, oxygen saturation";
        } else if (clinicalDecision.primaryDiagnosis.toLowerCase().contains("pneumonia")) {
            protocol.protocolId = "PNEUMONIA_TREATMENT_01";
            protocol.treatmentCategory = "Infectious Disease";
            protocol.medicationGuidelines = "Antibiotics per hospital guidelines";
            protocol.monitoringRequirements = "Oxygen saturation, respiratory status, temperature";
        } else {
            protocol.protocolId = "GENERAL_TREATMENT_01";
            protocol.treatmentCategory = "General Medical";
            protocol.medicationGuidelines = "Supportive care as indicated";
            protocol.monitoringRequirements = "Vital signs per routine";
        }
        
        System.out.printf("[%s] Selected treatment protocol: %s\n", getPlaceId(), protocol.protocolId);
        return protocol;
    }
    
    /**
     * Administer medications
     */
    private MedicationAdministration administerMedications(List<String> medications) {
        MedicationAdministration admin = new MedicationAdministration();
        
        String currentTime = getCurrentTimestamp();
        
        for (String medication : medications) {
            admin.medicationsGiven.add(medication);
            admin.administrationTimes.add(currentTime);
            
            // Simulate medication effects
            if (medication.toLowerCase().contains("aspirin")) {
                admin.medicationEffects.add("Antiplatelet effect initiated");
            } else if (medication.toLowerCase().contains("furosemide")) {
                admin.medicationEffects.add("Diuresis achieved - 800mL output");
            } else if (medication.toLowerCase().contains("antibiotic")) {
                admin.medicationEffects.add("Antibiotic therapy initiated");
            } else {
                admin.medicationEffects.add("Medication administered successfully");
            }
        }
        
        System.out.printf("[%s] Administered %d medications\n", getPlaceId(), medications.size());
        return admin;
    }
    
    /**
     * Execute procedures
     */
    private ProcedureExecution executeProcedures(List<String> procedures) {
        ProcedureExecution execution = new ProcedureExecution();
        
        String currentTime = getCurrentTimestamp();
        
        for (String procedure : procedures) {
            execution.proceduresPerformed.add(procedure);
            execution.procedureTimes.add(currentTime);
            
            // Simulate procedure outcomes
            if (procedure.toLowerCase().contains("cath lab")) {
                execution.procedureOutcomes.add("Successful PCI, stent placed in LAD");
            } else if (procedure.toLowerCase().contains("intubation")) {
                execution.procedureOutcomes.add("Successful endotracheal intubation");
            } else {
                execution.procedureOutcomes.add("Procedure completed successfully");
            }
        }
        
        System.out.printf("[%s] Executed %d procedures\n", getPlaceId(), procedures.size());
        return execution;
    }
    
    /**
     * Perform patient monitoring
     */
    private PatientMonitoring performMonitoring(List<String> monitoringRequirements) {
        PatientMonitoring monitoring = new PatientMonitoring();
        
        for (String requirement : monitoringRequirements) {
            if (requirement.contains("vital_signs")) {
                monitoring.vitalSigns.put("heart_rate", "72");
                monitoring.vitalSigns.put("blood_pressure", "128/76");
                monitoring.vitalSigns.put("respiratory_rate", "16");
                monitoring.vitalSigns.put("oxygen_saturation", "98");
                monitoring.vitalSigns.put("temperature", "37.0");
            } else if (requirement.contains("cardiac")) {
                monitoring.monitoringData.put("rhythm", "Normal sinus rhythm");
                monitoring.monitoringData.put("ectopy", "None observed");
            } else if (requirement.contains("intake")) {
                monitoring.monitoringData.put("intake_24h", "1200mL");
                monitoring.monitoringData.put("output_24h", "1400mL");
            }
        }
        
        System.out.printf("[%s] Monitoring data collected\n", getPlaceId());
        return monitoring;
    }
    
    /**
     * Assess treatment response
     */
    private TreatmentResponse assessTreatmentResponse(MedicationAdministration medication, 
                                                       ProcedureExecution procedure, 
                                                       PatientMonitoring monitoring) {
        TreatmentResponse response = new TreatmentResponse();
        
        // Assess symptom improvement
        double random = ThreadLocalRandom.current().nextDouble();
        if (random < 0.7) {
            response.symptomImprovement = "SIGNIFICANT - Pain resolved, breathing easier";
        } else if (random < 0.9) {
            response.symptomImprovement = "MODERATE - Partial symptom relief";
        } else {
            response.symptomImprovement = "MINIMAL - Symptoms persist";
        }
        
        // Assess hemodynamic response
        if (monitoring.vitalSigns.containsKey("blood_pressure")) {
            response.hemodynamicResponse = "STABLE - Hemodynamics within normal limits";
        } else {
            response.hemodynamicResponse = "UNKNOWN - Monitoring ongoing";
        }
        
        // Assess laboratory response (if applicable)
        response.laboratoryResponse = "PENDING - Serial labs not yet available";
        
        // Overall response
        if (response.symptomImprovement.contains("SIGNIFICANT")) {
            response.overallResponse = "EXCELLENT - Treatment goals being met";
        } else if (response.symptomImprovement.contains("MODERATE")) {
            response.overallResponse = "GOOD - Continued monitoring needed";
        } else {
            response.overallResponse = "POOR - Consider alternative treatment";
        }
        
        System.out.printf("[%s] Treatment response: %s\n", getPlaceId(), response.overallResponse);
        return response;
    }
    
    /**
     * Identify adverse events
     */
    private List<String> identifyAdverseEvents(MedicationAdministration medication, 
                                                ProcedureExecution procedure) {
        List<String> adverseEvents = new ArrayList<>();
        
        // Random chance of adverse events
        double random = ThreadLocalRandom.current().nextDouble();
        
        if (random < 0.05) {
            adverseEvents.add("Minor bleeding at IV site");
        }
        
        if (random < 0.02) {
            adverseEvents.add("Mild allergic reaction to antibiotic - treated with antihistamine");
        }
        
        if (adverseEvents.isEmpty()) {
            System.out.printf("[%s] No adverse events identified\n", getPlaceId());
        } else {
            System.out.printf("[%s] Identified %d adverse events\n", getPlaceId(), adverseEvents.size());
        }
        
        return adverseEvents;
    }
    
    /**
     * Evaluate if treatment goals met
     */
    private boolean evaluateTreatmentGoals(TreatmentResponse response) {
        return response.overallResponse.contains("EXCELLENT") || 
               response.overallResponse.contains("GOOD");
    }
    
    /**
     * Assess discharge readiness
     */
    private boolean assessDischargeReadiness(TreatmentResponse response, List<String> adverseEvents) {
        return response.overallResponse.contains("EXCELLENT") && 
               adverseEvents.isEmpty();
    }
    
    // ===== DIRECT TREATMENT (FEEDFORWARD) BUSINESS LOGIC =====
    
    /**
     * Assess if condition is appropriate for direct treatment
     */
    private DirectTreatmentDecision assessDirectTreatmentAppropriate(TokenInfo triageInfo) {
        DirectTreatmentDecision decision = new DirectTreatmentDecision();
        
        String indication = triageInfo.getIndication() != null ? triageInfo.getIndication().toLowerCase() : "";
        String acuityLevel = extractValue(triageInfo.getOriginalToken(), "acuity_level", "ESI_3");
        
        // Simple cases appropriate for direct treatment
        if (indication.contains("minor") || indication.contains("simple") || 
            acuityLevel.equals("ESI_4") || acuityLevel.equals("ESI_5")) {
            
            decision.appropriateForDirectTreatment = true;
            decision.treatmentCategory = "SIMPLE_OUTPATIENT";
            decision.treatmentProtocol = "MINOR_TREATMENT_PROTOCOL";
            decision.estimatedDuration = "30 minutes";
            decision.requiredSupplies.add("Basic wound care supplies");
            decision.requiredSupplies.add("Standard medications");
            
        } else {
            // Complex cases need full diagnostic workup
            decision.appropriateForDirectTreatment = false;
            decision.redirectReason = "Patient requires full diagnostic workup - " + 
                                      "forwarding to Radiology, Laboratory, and Cardiology";
        }
        
        System.out.printf("[%s] Direct treatment appropriate: %s\n", 
            getPlaceId(), decision.appropriateForDirectTreatment);
        
        return decision;
    }
    
    /**
     * Execute simple treatment for feedforward cases
     */
    private DirectTreatmentExecution executeSimpleTreatment(TokenInfo triageInfo, 
                                                             DirectTreatmentDecision decision) {
        DirectTreatmentExecution execution = new DirectTreatmentExecution();
        
        execution.startTime = getCurrentTimestamp();
        execution.category = decision.treatmentCategory;
        
        // Perform simple interventions
        String indication = triageInfo.getIndication() != null ? triageInfo.getIndication().toLowerCase() : "";
        
        if (indication.contains("minor")) {
            execution.interventionsPerformed.add("Wound cleaned and dressed");
            execution.interventionsPerformed.add("Tetanus prophylaxis assessed");
            execution.medicationsGiven.add("Topical antibiotic applied");
            execution.patientEducation.add("Wound care instructions provided");
            execution.patientEducation.add("Return precautions discussed");
            execution.outcome = "Successful wound treatment";
            execution.dischargeRecommendation = "HOME - Follow up with primary care in 3-5 days";
        } else {
            execution.interventionsPerformed.add("Basic assessment completed");
            execution.interventionsPerformed.add("Supportive care provided");
            execution.patientEducation.add("Self-care instructions provided");
            execution.outcome = "Supportive treatment provided";
            execution.dischargeRecommendation = "HOME - Return if symptoms worsen";
        }
        
        execution.endTime = getCurrentTimestamp();
        
        System.out.printf("[%s] Simple treatment executed successfully\n", getPlaceId());
        return execution;
    }
    
    // ===== UTILITY METHODS =====
    
    /**
     * Extract inner diagnosis results from potentially wrapped data
     */
    private String extractInnerDiagnosisResults(String data) {
        if (data == null) return "{}";
        
        // Look for diagnosisResults wrapper
        int startIndex = data.indexOf("\"diagnosisResults\":");
        if (startIndex >= 0) {
            startIndex += "\"diagnosisResults\":".length();
            
            // Find the matching closing brace
            int braceCount = 0;
            int currentIndex = startIndex;
            boolean inString = false;
            
            while (currentIndex < data.length()) {
                char c = data.charAt(currentIndex);
                
                if (c == '"' && (currentIndex == 0 || data.charAt(currentIndex - 1) != '\\')) {
                    inString = !inString;
                } else if (!inString) {
                    if (c == '{') {
                        braceCount++;
                    } else if (c == '}') {
                        braceCount--;
                        if (braceCount == 0) {
                            return data.substring(startIndex, currentIndex + 1);
                        }
                    }
                }
                currentIndex++;
            }
        }
        
        return data;
    }
    
    /**
     * Extract inner triage results from potentially wrapped data
     */
    private String extractInnerTriageResults(String data) {
        if (data == null) return "{}";
        
        // Look for triageResults wrapper
        int startIndex = data.indexOf("\"triageResults\":");
        if (startIndex >= 0) {
            startIndex += "\"triageResults\":".length();
            
            // Find the matching closing brace
            int braceCount = 0;
            int currentIndex = startIndex;
            boolean inString = false;
            
            while (currentIndex < data.length()) {
                char c = data.charAt(currentIndex);
                
                if (c == '"' && (currentIndex == 0 || data.charAt(currentIndex - 1) != '\\')) {
                    inString = !inString;
                } else if (!inString) {
                    if (c == '{') {
                        braceCount++;
                    } else if (c == '}') {
                        braceCount--;
                        if (braceCount == 0) {
                            return data.substring(startIndex, currentIndex + 1);
                        }
                    }
                }
                currentIndex++;
            }
        }
        
        return data;
    }
    
    /**
     * Extract string value from JSON-like data
     */
    private String extractValue(String data, String key, String defaultValue) {
        if (data == null) return defaultValue;
        
        String searchKey = "\"" + key + "\":\"";
        int startIndex = data.indexOf(searchKey);
        if (startIndex >= 0) {
            startIndex += searchKey.length();
            int endIndex = data.indexOf("\"", startIndex);
            if (endIndex > startIndex) {
                return data.substring(startIndex, endIndex);
            }
        }
        
        return defaultValue;
    }
    
    /**
     * Extract array value from JSON-like data
     */
    private String extractArrayValue(String data, String key) {
        if (data == null) return null;
        
        String searchKey = "\"" + key + "\":[";
        int startIndex = data.indexOf(searchKey);
        if (startIndex >= 0) {
            startIndex += searchKey.length();
            int endIndex = data.indexOf("]", startIndex);
            if (endIndex > startIndex) {
                String arrayContent = data.substring(startIndex, endIndex);
                // Remove quotes and join
                return arrayContent.replace("\"", "").trim();
            }
        }
        
        return null;
    }
    
    /**
     * Wrap content in treatmentResults key
     */
    private String wrapInTreatmentResults(String content) {
        return "{\"treatmentResults\":" + content + "}";
    }
    
    /**
     * Parse TokenInfo from data string
     */
    private TokenInfo parseTokenInfo(String data) {
        TokenInfo tokenInfo = new TokenInfo();
        tokenInfo.setOriginalToken(data);
        tokenInfo.setPatientId(extractValue(data, "patient_id", "P_Unknown"));
        tokenInfo.setProviderId(extractValue(data, "provider_id", "PROVIDER_Unknown"));
        tokenInfo.setIndication(extractValue(data, "chief_complaint", "unknown"));
        return tokenInfo;
    }
    
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
            if (value.toLowerCase().contains("patient") || value.toLowerCase().contains("treat")) {
                return "P_Treatment";
            } else if (value.toLowerCase().contains("rn") || value.toLowerCase().contains("nurse")) {
                return "RN_TREAT_001";
            } else {
                return "CLEAN_VALUE";
            }
        }
        
        return cleaned;
    }
    
    protected String getCurrentTimestamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
    
    // ===== FRAMEWORK METHODS =====
    
    @Override
    protected String getServiceResultsKey() {
        return "treatmentResults";
    }
    
    @Override
    protected String getCompletionStatus() {
        return "TREATMENT_COMPLETE";
    }
    
    @Override
    protected String[] getRequiredTables() {
        return new String[] {
            "TREATMENT_RECORDS",
            "MEDICATION_ADMINISTRATION",
            "PROCEDURE_EXECUTION"
        };
    }
    
    @Override
    protected void setupServiceDatabase() {
        Connection conn = null;
        Statement stmt = null;
        
        try {
            conn = DriverManager.getConnection(TREATMENT_DB_URL);
            stmt = conn.createStatement();
            
            // Check if tables exist
            ResultSet rs = conn.getMetaData().getTables(null, null, "TREATMENT_RECORDS", null);
            if (!rs.next()) {
                System.out.printf("[%s] Creating treatment database tables...\n", getPlaceId());
                
                // Treatment records table
                stmt.executeUpdate(
                    "CREATE TABLE TREATMENT_RECORDS (" +
                    "TREATMENT_ID VARCHAR(50) PRIMARY KEY, " +
                    "PATIENT_ID VARCHAR(50), " +
                    "DIAGNOSIS VARCHAR(200), " +
                    "DISPOSITION VARCHAR(50), " +
                    "GOALS_MET BOOLEAN, " +
                    "DISCHARGE_READY BOOLEAN, " +
                    "COMPLETION_TIME TIMESTAMP)"
                );
                
                // Medication administration table
                stmt.executeUpdate(
                    "CREATE TABLE MEDICATION_ADMINISTRATION (" +
                    "ADMIN_ID VARCHAR(50) PRIMARY KEY, " +
                    "TREATMENT_ID VARCHAR(50), " +
                    "MEDICATION_NAME VARCHAR(100), " +
                    "ADMINISTRATION_TIME TIMESTAMP, " +
                    "EFFECT VARCHAR(200))"
                );
                
                // Procedure execution table
                stmt.executeUpdate(
                    "CREATE TABLE PROCEDURE_EXECUTION (" +
                    "PROCEDURE_ID VARCHAR(50) PRIMARY KEY, " +
                    "TREATMENT_ID VARCHAR(50), " +
                    "PROCEDURE_NAME VARCHAR(100), " +
                    "EXECUTION_TIME TIMESTAMP, " +
                    "OUTCOME VARCHAR(200))"
                );
                
                System.out.printf("[%s] Treatment database tables created successfully\n", getPlaceId());
            }
            
            rs.close();
            
        } catch (SQLException e) {
            System.err.printf("[%s] Error setting up treatment database: %s\n", getPlaceId(), e.getMessage());
            e.printStackTrace();
        } finally {
            try {
                if (stmt != null) stmt.close();
                if (conn != null) conn.close();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }
    
    // ===== TREATMENT DATA CLASSES =====
    
    public static class TreatmentAssessment extends BaseServiceAssessment {
        private TreatmentProtocol protocol;
        private ClinicalDecision clinicalDecision;
        private MedicationAdministration medicationAdmin;
        private ProcedureExecution procedureExecution;
        private PatientMonitoring monitoring;
        private TreatmentResponse response;
        private List<String> adverseEvents;
        private boolean treatmentGoalsMet;
        private boolean dischargeReady;
        
        public TreatmentAssessment(String assessmentId) {
            super(assessmentId);
        }
        
        @Override
        public String toJsonFields() {
            StringBuilder json = new StringBuilder();
            
            json.append(jsonField("treatment_id", getAssessmentId())).append(",");
            json.append(jsonField("nurse", "RN_TREATMENT")).append(",");
            
            if (clinicalDecision != null) {
                json.append(jsonField("diagnosis_treated", clinicalDecision.primaryDiagnosis)).append(",");
                json.append(jsonField("disposition", clinicalDecision.disposition)).append(",");
            }
            
            if (medicationAdmin != null && !medicationAdmin.medicationsGiven.isEmpty()) {
                json.append("\"medications_administered\":[");
                for (int i = 0; i < medicationAdmin.medicationsGiven.size(); i++) {
                    json.append("{");
                    json.append("\"medication\":\"").append(medicationAdmin.medicationsGiven.get(i)).append("\",");
                    json.append("\"time\":\"").append(medicationAdmin.administrationTimes.get(i)).append("\",");
                    json.append("\"effect\":\"").append(medicationAdmin.medicationEffects.get(i)).append("\"");
                    json.append("}");
                    if (i < medicationAdmin.medicationsGiven.size() - 1) json.append(",");
                }
                json.append("],");
            }
            
            if (procedureExecution != null && !procedureExecution.proceduresPerformed.isEmpty()) {
                json.append("\"procedures_performed\":[");
                for (int i = 0; i < procedureExecution.proceduresPerformed.size(); i++) {
                    json.append("{");
                    json.append("\"procedure\":\"").append(procedureExecution.proceduresPerformed.get(i)).append("\",");
                    json.append("\"time\":\"").append(procedureExecution.procedureTimes.get(i)).append("\",");
                    json.append("\"outcome\":\"").append(procedureExecution.procedureOutcomes.get(i)).append("\"");
                    json.append("}");
                    if (i < procedureExecution.proceduresPerformed.size() - 1) json.append(",");
                }
                json.append("],");
            }
            
            if (response != null) {
                json.append(jsonField("symptom_improvement", response.symptomImprovement)).append(",");
                json.append(jsonField("overall_response", response.overallResponse)).append(",");
            }
            
            if (adverseEvents != null && !adverseEvents.isEmpty()) {
                json.append("\"adverse_events\":[");
                for (int i = 0; i < adverseEvents.size(); i++) {
                    json.append("\"").append(adverseEvents.get(i)).append("\"");
                    if (i < adverseEvents.size() - 1) json.append(",");
                }
                json.append("],");
            }
            
            json.append(jsonField("treatment_goals_met", String.valueOf(treatmentGoalsMet))).append(",");
            json.append(jsonField("discharge_ready", String.valueOf(dischargeReady))).append(",");
            
            if (protocol != null) {
                json.append(jsonField("protocol_id", protocol.protocolId)).append(",");
            }
            
            json.append(jsonField("completion_time", getCurrentTimestamp()));
            
            return json.toString();
        }
        
        @Override
        public String getSummary() {
            return String.format("Treatment: %s, Goals: %s, Discharge: %s", 
                clinicalDecision != null ? clinicalDecision.primaryDiagnosis : "Unknown",
                treatmentGoalsMet ? "Met" : "In Progress",
                dischargeReady ? "Ready" : "Not Ready");
        }
        
        public void setProtocol(TreatmentProtocol protocol) { this.protocol = protocol; }
        public void setClinicalDecision(ClinicalDecision clinicalDecision) { this.clinicalDecision = clinicalDecision; }
        public void setMedicationAdmin(MedicationAdministration medicationAdmin) { this.medicationAdmin = medicationAdmin; }
        public void setProcedureExecution(ProcedureExecution procedureExecution) { this.procedureExecution = procedureExecution; }
        public void setMonitoring(PatientMonitoring monitoring) { this.monitoring = monitoring; }
        public void setResponse(TreatmentResponse response) { this.response = response; }
        public void setAdverseEvents(List<String> adverseEvents) { this.adverseEvents = adverseEvents; }
        public void setTreatmentGoalsMet(boolean treatmentGoalsMet) { this.treatmentGoalsMet = treatmentGoalsMet; }
        public void setDischargeReady(boolean dischargeReady) { this.dischargeReady = dischargeReady; }
        
        private String getCurrentTimestamp() {
            return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
    }
    
    // ===== FEEDFORWARD DATA CLASSES =====
    
    private static class DirectTreatmentDecision {
        boolean appropriateForDirectTreatment = false;
        String treatmentCategory;
        String treatmentProtocol;
        String estimatedDuration;
        List<String> requiredSupplies = new ArrayList<>();
        String redirectReason;
    }
    
    private static class DirectTreatmentExecution {
        String startTime;
        String endTime;
        String category;
        List<String> interventionsPerformed = new ArrayList<>();
        List<String> medicationsGiven = new ArrayList<>();
        List<String> patientEducation = new ArrayList<>();
        String outcome;
        String dischargeRecommendation;
    }
    
    public static class DirectTreatmentAssessment {
        private String treatmentId;
        private TokenInfo triageTokenInfo;
        private DirectTreatmentDecision decision;
        private DirectTreatmentExecution execution;
        private String processingPlace;
        private String completionTime;
        
        public DirectTreatmentAssessment(String treatmentId) {
            this.treatmentId = treatmentId;
        }
        
        public String toJsonString() {
            StringBuilder json = new StringBuilder();
            json.append("{");
            
            json.append("\"treatment_id\":\"").append(treatmentId).append("\",");
            json.append("\"feedforward\":true,");
            json.append("\"treatment_type\":\"DIRECT_FROM_TRIAGE\",");
            
            if (triageTokenInfo != null) {
                json.append("\"patient_id\":\"").append(triageTokenInfo.getPatientId()).append("\",");
                json.append("\"indication\":\"").append(triageTokenInfo.getIndication()).append("\",");
            }
            
            if (decision != null) {
                json.append("\"treatment_category\":\"").append(decision.treatmentCategory).append("\",");
                json.append("\"protocol_used\":\"").append(decision.treatmentProtocol).append("\",");
            }
            
            if (execution != null) {
                json.append("\"interventions_performed\":[");
                for (int i = 0; i < execution.interventionsPerformed.size(); i++) {
                    json.append("\"").append(execution.interventionsPerformed.get(i)).append("\"");
                    if (i < execution.interventionsPerformed.size() - 1) json.append(",");
                }
                json.append("],");
                
                json.append("\"outcome\":\"").append(execution.outcome).append("\",");
                json.append("\"discharge_recommendation\":\"").append(execution.dischargeRecommendation).append("\",");
            }
            
            json.append("\"processing_place\":\"").append(processingPlace).append("\",");
            json.append("\"completion_time\":\"").append(completionTime).append("\",");
            json.append("\"status\":\"TREATMENT_COMPLETE\"");
            
            json.append("}");
            return json.toString();
        }
        
        public void setTriageTokenInfo(TokenInfo triageTokenInfo) { this.triageTokenInfo = triageTokenInfo; }
        public void setDecision(DirectTreatmentDecision decision) { this.decision = decision; }
        public void setExecution(DirectTreatmentExecution execution) { this.execution = execution; }
        public void setProcessingPlace(String processingPlace) { this.processingPlace = processingPlace; }
        public void setCompletionTime(String completionTime) { this.completionTime = completionTime; }
    }
    
    // Data classes
    private static class ClinicalDecision {
        String primaryDiagnosis;
        String disposition;
        List<String> medications = new ArrayList<>();
        List<String> procedures = new ArrayList<>();
        List<String> monitoring = new ArrayList<>();
        List<String> safetyAlerts = new ArrayList<>();
    }
    
    private static class TreatmentProtocol {
        String protocolId;
        String treatmentCategory;
        String medicationGuidelines;
        String monitoringRequirements;
    }
    
    private static class MedicationAdministration {
        List<String> medicationsGiven = new ArrayList<>();
        List<String> administrationTimes = new ArrayList<>();
        List<String> medicationEffects = new ArrayList<>();
    }
    
    private static class ProcedureExecution {
        List<String> proceduresPerformed = new ArrayList<>();
        List<String> procedureTimes = new ArrayList<>();
        List<String> procedureOutcomes = new ArrayList<>();
    }
    
    private static class PatientMonitoring {
        Map<String, String> monitoringData = new HashMap<>();
        Map<String, String> vitalSigns = new HashMap<>();
    }
    
    private static class TreatmentResponse {
        String symptomImprovement;
        String hemodynamicResponse;
        String laboratoryResponse;
        String overallResponse;
    }
}