package org.btsn.places;

import org.btsn.base.BaseHealthcareService;
import org.btsn.base.BaseServiceAssessment;
import org.btsn.base.ServiceAssessment;
import org.btsn.base.TokenInfo;
import org.btsn.exceptions.ServiceProcessingException;

import java.util.concurrent.ThreadLocalRandom;

/**
 * RadiologyService - PURE BUSINESS LOGIC
 * 
 * RESPONSIBILITY: Perform imaging studies and provide radiologic interpretations
 * 
 * ARCHITECTURAL CONTRACT:
 * - Receives CLEAN business data (patient info, imaging orders) from ServiceHelper
 * - Returns PURE business results (imaging findings, impressions, recommendations)
 * - ZERO knowledge of tokens, timing, metadata, or infrastructure
 * 
 * SPECIAL CAPABILITY: Supports federated radiology requests from external hospitals
 * 
 * Infrastructure concerns (token preservation, timing, enrichment) handled by ServiceHelper
 */
public class RadiologyService extends BaseHealthcareService {
    
    // ===== STATIC INITIALIZATION =====
    
    static {
        System.out.println("=== RADIOLOGY SERVICE INITIALIZING ===");
        System.out.println("Pure Business Logic - No Infrastructure Concerns");
        System.out.println("Imaging protocols loaded");
        System.out.println("Federated radiology support enabled");
        System.out.println("=== RADIOLOGY SERVICE READY ===");
    }
    
    // ===== INSTANCE FIELDS =====
    
    private final String radiologistId;
    
    // ===== CONSTRUCTORS =====
    
    public RadiologyService(String sequenceID) {
        super(sequenceID, "RAD");
        this.radiologistId = generateRadiologistId(sequenceID);
        
        System.out.printf("[%s] RadiologyService instance created - sequenceID: %s, radiologist: %s\n", 
            getPlaceId(), sequenceID, radiologistId);
    }
    
    private static String generateRadiologistId(String sequenceID) {
        try {
            return "DR_RAD_" + (Integer.parseInt(sequenceID) % 1000);
        } catch (NumberFormatException e) {
            return "DR_RAD_" + Math.abs(sequenceID.hashCode() % 1000);
        }
    }
    
    // ===== BUSINESS METHODS =====
    
    /**
     * Main entry point - SIMPLIFIED to pure business logic
     * 
     * ServiceHelper contract:
     * - Receives: Clean business data (patient info, imaging orders)
     * - Returns: Pure business results (imaging findings and interpretation)
     * - Infrastructure (tokens, timing) handled by ServiceHelper
     */
    public String processImagingRequest(String cleanBusinessData) {
        System.out.printf("[%s] Processing imaging request for sequenceID: %s\n", getPlaceId(), getSequenceID());
        
        // Process the core radiology assessment - returns pure business results
        String businessResults = processAssessment(cleanBusinessData);
        
        // Return pure business results - ServiceHelper will enrich with metadata
        return businessResults;
    }
    
    /**
     * Federated radiology request - BUSINESS LOGIC ONLY
     * 
     * Handles cross-facility imaging requests from external hospitals.
     * This is a legitimate business use case, not infrastructure.
     * 
     * Business responsibilities:
     * - Extract external hospital information
     * - Process the imaging study
     * - Add federated metadata (audit trail, routing info)
     * 
     * ServiceHelper will handle infrastructure (token preservation, timing)
     */
    public String federatedRadiologyRequest(String cleanBusinessData) {
        System.out.printf("[%s] Processing federated radiology request\n", getPlaceId());
        
        // Extract external hospital info - BUSINESS LOGIC
        String externalHospital = extractExternalHospitalInfo(cleanBusinessData);
        
        // Process the core radiology assessment - BUSINESS LOGIC
        String serviceResult = processAssessment(cleanBusinessData);
        
        // Enhance with federated metadata - BUSINESS LOGIC
        String enhancedResult = enhanceResultForFederation(serviceResult, externalHospital);
        
        // Return enhanced business results - ServiceHelper will enrich with infrastructure metadata
        return enhancedResult;
    }
    
    /**
     * Extract external hospital information from federated request
     * PURE BUSINESS LOGIC - identifying the requesting facility
     */
    private String extractExternalHospitalInfo(String tokenData) {
        try {
            // Look for external hospital identifiers in the token
            String[] hospitalPatterns = {
                "\"external_hospital\":", "\"origin_hospital\":", "\"requesting_hospital\":"
            };
            
            for (String pattern : hospitalPatterns) {
                int startIndex = tokenData.indexOf(pattern);
                if (startIndex >= 0) {
                    startIndex += pattern.length();
                    // Skip whitespace and quotes
                    while (startIndex < tokenData.length() && 
                           (Character.isWhitespace(tokenData.charAt(startIndex)) || tokenData.charAt(startIndex) == '"')) {
                        startIndex++;
                    }
                    
                    // Find end of hospital name
                    int endIndex = startIndex;
                    while (endIndex < tokenData.length() && 
                           tokenData.charAt(endIndex) != '"' && 
                           tokenData.charAt(endIndex) != ',' && 
                           tokenData.charAt(endIndex) != '}') {
                        endIndex++;
                    }
                    
                    if (endIndex > startIndex) {
                        return tokenData.substring(startIndex, endIndex).trim();
                    }
                }
            }
            
            // Default if no specific hospital found
            return "EXTERNAL_HOSPITAL_" + (System.currentTimeMillis() % 1000);
            
        } catch (Exception e) {
            System.err.printf("[%s] Error extracting external hospital info: %s\n", getPlaceId(), e.getMessage());
            return "UNKNOWN_EXTERNAL";
        }
    }

    /**
     * Enhance result with federated metadata
     * PURE BUSINESS LOGIC - adding audit trail and routing info for cross-facility request
     */
    private String enhanceResultForFederation(String serviceResult, String externalHospital) {
        try {
            // Add federated-specific metadata - BUSINESS REQUIREMENTS for audit/compliance
            long federatedTimestamp = System.currentTimeMillis();
            String federatedId = "FED_" + getSequenceID() + "_" + (federatedTimestamp % 10000);
            
            // Parse existing result and add federated fields
            if (serviceResult.startsWith("{") && serviceResult.endsWith("}")) {
                // Remove closing brace and add federated metadata
                String enhanced = serviceResult.substring(0, serviceResult.length() - 1);
                enhanced += ",\"federated_request_id\":\"" + federatedId + "\"";
                enhanced += ",\"external_hospital\":\"" + (externalHospital != null ? externalHospital : "UNKNOWN") + "\"";
                enhanced += ",\"federated_timestamp\":" + federatedTimestamp;
                enhanced += ",\"cross_facility_audit\":true";
                enhanced += ",\"result_routing\":\"return_to_origin\"";
                enhanced += "}";
                
                System.out.printf("[%s] 📋 Added federated metadata: ID=%s, Hospital=%s\n", 
                    getPlaceId(), federatedId, externalHospital);
                
                return enhanced;
            } else {
                // If not JSON, wrap it
                return "{" + serviceResult + 
                       ",\"federated_request_id\":\"" + federatedId + "\"" +
                       ",\"external_hospital\":\"" + (externalHospital != null ? externalHospital : "UNKNOWN") + "\"" +
                       "}";
            }
            
        } catch (Exception e) {
            System.err.printf("[%s] Error enhancing result for federation: %s\n", getPlaceId(), e.getMessage());
            return serviceResult; // Return original if enhancement fails
        }
    }
    
    // ===== FRAMEWORK IMPLEMENTATION METHODS =====
    
    @Override
    protected ServiceAssessment processServiceSpecificAssessment(TokenInfo tokenInfo) 
            throws ServiceProcessingException {
        try {
            System.out.printf("[%s] Processing specific radiology assessment\n", getPlaceId());
            
            // Clean input data
            tokenInfo = superCleanTokenInfo(tokenInfo);
            
            // Radiology assessment - pure business logic
            String imagingType = determineImagingType(tokenInfo.getIndication());
            ImagingProtocol protocol = getImagingProtocol(imagingType, tokenInfo.getIndication());
            String studyId = generateAssessmentId("STUDY", tokenInfo.getPatientId());
            studyId = superCleanValue(studyId);
            
            ClinicalInterpretation interpretation = performClinicalInterpretation(imagingType, tokenInfo.getIndication());
            
            // Create assessment result
            RadiologyAssessment assessment = new RadiologyAssessment(studyId);
            assessment.setProtocol(protocol);
            assessment.setImagingType(imagingType);
            assessment.setInterpretation(interpretation);
            assessment.setRadiologistId(radiologistId);
            
            System.out.printf("[%s] Assessment completed: %s - %s\n", 
                getPlaceId(), imagingType, interpretation.impression);
            
            return assessment;
            
        } catch (Exception e) {
            System.err.printf("[%s] Error in radiology assessment: %s\n", getPlaceId(), e.getMessage());
            e.printStackTrace();
            throw new ServiceProcessingException("Imaging study failed", e);
        }
    }
    
    // ===== RADIOLOGY BUSINESS LOGIC =====
    
    private String determineImagingType(String indication) {
        if (indication == null) {
            return "chest_xray";
        }
        
        String lowerIndication = indication.toLowerCase();
        
        if (lowerIndication.contains("chest") || lowerIndication.contains("cardiac") || 
            lowerIndication.contains("shortness") || lowerIndication.contains("sob")) {
            return "chest_xray";
        } else if (lowerIndication.contains("head") || lowerIndication.contains("neuro")) {
            return "ct_head";
        } else if (lowerIndication.contains("abdomen") || lowerIndication.contains("pain")) {
            return "ct_abdomen";
        } else {
            return "chest_xray";
        }
    }
    
    private ImagingProtocol getImagingProtocol(String imagingType, String indication) {
        ImagingProtocol protocol = new ImagingProtocol();
        
        switch (imagingType) {
            case "chest_xray":
                protocol.protocolId = "CHEST_XR_01";
                protocol.technique = "PA and lateral chest radiographs";
                protocol.durationMinutes = 10;
                break;
                
            case "ct_head":
                protocol.protocolId = "CT_HEAD_01";
                protocol.technique = "Non-contrast head CT, axial images";
                protocol.durationMinutes = 15;
                break;
                
            case "ct_abdomen":
                protocol.protocolId = "CT_ABD_01";
                protocol.technique = "CT abdomen/pelvis with IV contrast";
                protocol.durationMinutes = 30;
                break;
                
            default:
                protocol.protocolId = "CHEST_XR_01";
                protocol.technique = "Standard chest radiograph";
                protocol.durationMinutes = 10;
        }
        
        System.out.printf("[%s] Selected protocol: %s (%s)\n", 
            getPlaceId(), protocol.protocolId, protocol.technique);
        return protocol;
    }
    
    private ClinicalInterpretation performClinicalInterpretation(String imagingType, String indication) {
        ClinicalInterpretation interpretation;
        
        switch (imagingType) {
            case "chest_xray":
                interpretation = interpretChestXray(indication);
                break;
                
            case "ct_head":
                interpretation = interpretCTHead(indication);
                break;
                
            case "ct_abdomen":
                interpretation = interpretCTAbdomen(indication);
                break;
                
            default:
                interpretation = new ClinicalInterpretation();
                interpretation.findings = "Study completed per protocol";
                interpretation.impression = "No acute abnormalities detected";
                interpretation.recommendations = "Clinical correlation";
        }
        
        return interpretation;
    }
    
    private ClinicalInterpretation interpretChestXray(String indication) {
        ClinicalInterpretation interpretation = new ClinicalInterpretation();
        
        if ("chest_pain".equals(indication) || "chest".equals(indication)) {
            if (ThreadLocalRandom.current().nextDouble() < 0.85) {
                interpretation.findings = "Normal cardiac silhouette, clear lung fields bilaterally";
                interpretation.impression = "No acute cardiopulmonary abnormalities";
                interpretation.recommendations = "Clinical correlation recommended";
            } else {
                interpretation.findings = "Mild cardiomegaly, clear lung fields";
                interpretation.impression = "Cardiomegaly, no acute pulmonary abnormality";
                interpretation.recommendations = "Consider echocardiogram if clinically indicated";
            }
        } else if ("shortness_of_breath".equals(indication) || "sob".equals(indication)) {
            double random = ThreadLocalRandom.current().nextDouble();
            if (random < 0.3) {
                interpretation.findings = "Cardiomegaly, pulmonary vascular congestion";
                interpretation.impression = "Findings consistent with heart failure";
                interpretation.recommendations = "Clinical correlation, consider BNP and echocardiogram";
            } else if (random < 0.5) {
                interpretation.findings = "Bilateral lower lobe infiltrates";
                interpretation.impression = "Bilateral pneumonia";
                interpretation.recommendations = "Clinical correlation with laboratory values";
            } else {
                interpretation.findings = "Normal cardiac silhouette, clear lung fields bilaterally";
                interpretation.impression = "No acute cardiopulmonary abnormalities";
                interpretation.recommendations = "Clinical correlation recommended";
            }
        } else {
            interpretation.findings = "Normal cardiac silhouette, clear lung fields bilaterally";
            interpretation.impression = "No acute cardiopulmonary abnormalities";
            interpretation.recommendations = "Clinical correlation recommended";
        }
        
        return interpretation;
    }
    
    private ClinicalInterpretation interpretCTHead(String indication) {
        ClinicalInterpretation interpretation = new ClinicalInterpretation();
        
        double random = ThreadLocalRandom.current().nextDouble();
        if (random < 0.9) {
            interpretation.findings = "No acute intracranial abnormality, normal gray-white differentiation";
            interpretation.impression = "Normal non-contrast head CT";
            interpretation.recommendations = "Clinical correlation";
        } else {
            interpretation.findings = "Subtle hypodensity in left frontal region, no midline shift";
            interpretation.impression = "Possible early ischemic changes, correlate clinically";
            interpretation.recommendations = "Consider neurology consultation and MRI if clinically indicated";
        }
        
        return interpretation;
    }
    
    private ClinicalInterpretation interpretCTAbdomen(String indication) {
        ClinicalInterpretation interpretation = new ClinicalInterpretation();
        
        double random = ThreadLocalRandom.current().nextDouble();
        if (random < 0.7) {
            interpretation.findings = "Normal appearance of solid organs, no free fluid or air";
            interpretation.impression = "No acute abdominal pathology";
            interpretation.recommendations = "Clinical correlation";
        } else if (random < 0.9) {
            interpretation.findings = "Appendiceal wall thickening with periappendiceal fat stranding";
            interpretation.impression = "Findings consistent with acute appendicitis";
            interpretation.recommendations = "Surgical consultation recommended";
        } else {
            interpretation.findings = "Multiple renal calculi, largest 8mm in left ureter with hydronephrosis";
            interpretation.impression = "Obstructing left ureteral calculus";
            interpretation.recommendations = "Urology consultation, pain management, consider lithotripsy";
        }
        
        return interpretation;
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
            if (value.toLowerCase().contains("patient") || value.toLowerCase().contains("rad")) {
                return "P_Radiology";
            } else if (value.toLowerCase().contains("dr") || value.toLowerCase().contains("radiologist")) {
                return "DR_RAD001";
            } else {
                return "CLEAN_VALUE";
            }
        }
        
        return cleaned;
    }
    
    // ===== FRAMEWORK METHODS =====
    
    @Override
    protected String getServiceResultsKey() {
        return "radiologyResults";
    }
    
    @Override
    protected String getCompletionStatus() {
        return "IMAGING_COMPLETE";
    }
    
    @Override
    protected String[] getRequiredTables() {
        return new String[0];
    }
    
    @Override
    protected void setupServiceDatabase() {
        System.out.printf("[%s] No database setup needed - service ready\n", getPlaceId());
    }
    
    // ===== RADIOLOGY DATA CLASSES =====
    
    public static class RadiologyAssessment extends BaseServiceAssessment {
        private ImagingProtocol protocol;
        private String imagingType;
        private ClinicalInterpretation interpretation;
        private String radiologistId;
        
        public RadiologyAssessment(String assessmentId) {
            super(assessmentId);
        }
        
        @Override
        public String toJsonFields() {
            StringBuilder json = new StringBuilder();
            
            json.append(jsonField("radiologist", radiologistId)).append(",");
            json.append(jsonField("study_id", getAssessmentId())).append(",");
            json.append(jsonField("imaging_type", imagingType)).append(",");
            
            if (interpretation != null) {
                json.append(jsonField("findings", interpretation.findings)).append(",");
                json.append(jsonField("impression", interpretation.impression)).append(",");
                json.append(jsonField("recommendations", interpretation.recommendations)).append(",");
            }
            
            json.append(jsonField("protocol_id", protocol != null ? protocol.protocolId : "CHEST_XR_01"));
            
            return json.toString();
        }
        
        @Override
        public String getSummary() {
            return String.format("Radiology: %s - %s", 
                imagingType, 
                interpretation != null ? interpretation.impression : "completed");
        }
        
        public void setProtocol(ImagingProtocol protocol) { this.protocol = protocol; }
        public void setImagingType(String imagingType) { this.imagingType = imagingType; }
        public void setInterpretation(ClinicalInterpretation interpretation) { this.interpretation = interpretation; }
        public void setRadiologistId(String radiologistId) { this.radiologistId = radiologistId; }
    }
    
    private static class ImagingProtocol {
        String protocolId;
        String technique;
        int durationMinutes;
    }
    
    private static class ClinicalInterpretation {
        String findings;
        String impression;
        String recommendations;
    }
}