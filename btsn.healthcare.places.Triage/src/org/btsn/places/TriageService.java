package org.btsn.places;

import org.btsn.base.BaseHealthcareService;
import org.btsn.base.BaseServiceAssessment;
import org.btsn.base.ServiceAssessment;
import org.btsn.base.TokenInfo;
import org.btsn.exceptions.ServiceProcessingException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * TriageService - PURE BUSINESS LOGIC
 * 
 * RESPONSIBILITY: Assess patient condition, determine triage priority, make routing decisions
 * 
 * ARCHITECTURAL CONTRACT:
 * - Receives CLEAN business data (patient info, condition) from ServiceHelper
 * - Returns PURE business results (triage assessment, routing decision)
 * - ZERO knowledge of tokens, timing, metadata, sequenceIDs, or infrastructure
 * 
 * ROUTING DECISION:
 * - Based entirely on CLINICAL CRITERIA (condition, vitals, pain score)
 * - NOT based on sequenceIDs or any infrastructure concerns
 * 
 * Infrastructure concerns (token preservation, timing, enrichment) handled by ServiceHelper
 */
public class TriageService extends BaseHealthcareService {
    
    // ===== STATIC INITIALIZATION =====
    
    static {
        System.out.println("=== TRIAGE SERVICE INITIALIZING ===");
        System.out.println("Pure Business Logic - No Infrastructure Concerns");
        System.out.println("Clinical-based routing decisions enabled");
        System.out.println("=== TRIAGE SERVICE READY ===");
    }
    
    // ===== INSTANCE FIELDS =====
    
    private final String nurseId;
    private String currentCondition = "unknown";
    
    // ===== CONSTRUCTORS =====
    
    public TriageService(String sequenceID) {
        super(sequenceID, "TRIAGE");
        // Generate nurse ID based on current time to ensure variety
        this.nurseId = "RN_TRIAGE_" + (System.currentTimeMillis() % 1000);
        
        System.out.printf("[%s] TriageService instance created - nurse: %s\n", 
            getPlaceId(), nurseId);
    }
    
    // ===== BUSINESS METHODS =====
    
    /**
     * Main entry point - SIMPLIFIED to pure business logic
     * 
     * ServiceHelper contract:
     * - Receives: Clean business data (patient condition, demographics)
     * - Returns: Pure business results (triage assessment)
     * - Infrastructure (tokens, timing) handled by ServiceHelper
     */
    public String processTriageAssessment(String cleanBusinessData) {
        System.out.printf("[%s] ========== TRIAGE SERVICE EXECUTION START ==========\n", getPlaceId());
        System.out.printf("[%s] Received clean business data length: %d\n", getPlaceId(), cleanBusinessData.length());
        
        // Extract condition from clean business data
        String actualCondition = extractConditionFromRawToken(cleanBusinessData);
        System.out.printf("[%s] Extracted condition: [%s]\n", getPlaceId(), actualCondition);
        
        // Store condition for use in assessment
        this.currentCondition = actualCondition;
        
        // Process assessment using framework - returns pure business results
        String businessResults = processAssessment(cleanBusinessData);
        
        System.out.printf("[%s] ========== TRIAGE ASSESSMENT COMPLETE ==========\n", getPlaceId());
        
        // Return pure business results - ServiceHelper will enrich with metadata
        return businessResults;
    }
    
    // ===== FRAMEWORK IMPLEMENTATION METHODS =====
    
    @Override
    protected ServiceAssessment processServiceSpecificAssessment(TokenInfo tokenInfo) 
            throws ServiceProcessingException {
        try {
            long serviceStartTime = System.currentTimeMillis();
            
            System.out.printf("[%s] Processing specific triage assessment\n", getPlaceId());
            
            // Use the condition we already extracted in processTriageAssessment
            String patientCondition = this.currentCondition;
            
            System.out.printf("[%s] Patient condition: %s\n", getPlaceId(), patientCondition);
            
            // Create triage assessment
            TriageAssessment assessment = new TriageAssessment(patientCondition);
            assessment.setActualCondition(patientCondition);
            
            // Load triage protocol
            TriageProtocol protocol = loadTriageProtocol(patientCondition);
            assessment.setProtocol(protocol);
            
            // Assess vital signs
            VitalSigns vitalSigns = assessVitalSigns(patientCondition);
            assessment.setVitalSigns(vitalSigns);
            
            // Determine pain score
            int painScore = determinePainScore(patientCondition);
            assessment.setPainScore(painScore);
            
            // Determine triage priority
            String triagePriority = determineTriagePriority(patientCondition, vitalSigns, painScore);
            assessment.setTriagePriority(triagePriority);
            
            // Order diagnostics
            List<String> diagnosticOrders = orderDiagnostics(patientCondition, triagePriority);
            assessment.setDiagnosticOrders(diagnosticOrders);
            
            // Make routing decision - PURELY CLINICAL, no infrastructure concerns
            RoutingDecision routingDecision = makeRoutingDecision(
                patientCondition, triagePriority, vitalSigns, painScore
            );
            assessment.setRoutingDecision(routingDecision);
            
            long serviceEndTime = System.currentTimeMillis();
            long processingTime = serviceEndTime - serviceStartTime;
            
            System.out.printf("[%s] ========== TRIAGE ASSESSMENT RESULTS ==========\n", getPlaceId());
            System.out.printf("[%s] Condition: %s\n", getPlaceId(), patientCondition);
            System.out.printf("[%s] Priority: %s\n", getPlaceId(), triagePriority);
            System.out.printf("[%s] Pain Score: %d/10\n", getPlaceId(), painScore);
            System.out.printf("[%s] Routing: %s\n", getPlaceId(), 
                routingDecision.bypassDiagnostics ? "DIRECT TO TREATMENT" : "STANDARD WORKFLOW");
            System.out.printf("[%s] Rationale: %s\n", getPlaceId(), routingDecision.rationale);
            System.out.printf("[%s] Processing Time: %dms\n", getPlaceId(), processingTime);
            System.out.printf("[%s] ================================================\n", getPlaceId());
            
            return assessment;
            
        } catch (Exception e) {
            throw new ServiceProcessingException("Triage assessment failed", e);
        }
    }
    
    // ===== CLINICAL ASSESSMENT METHODS =====
    
    private TriageProtocol loadTriageProtocol(String condition) {
        TriageProtocol protocol = new TriageProtocol();
        protocol.protocolId = "TRIAGE_PROTOCOL_" + condition.toUpperCase().replaceAll("[^A-Z]", "");
        protocol.assessmentType = "EMERGENCY_TRIAGE";
        protocol.priorityGuidelines = "ESI Guidelines v4";
        protocol.standardOrders = "Vital signs, pain assessment, chief complaint documentation";
        return protocol;
    }
    
    private VitalSigns assessVitalSigns(String condition) {
        VitalSigns vitals = new VitalSigns();
        
        switch (condition.toLowerCase()) {
            case "chest pain":
                // Chest pain could be cardiac or non-cardiac - needs workup
                vitals.bloodPressure = "145/95";
                vitals.heartRate = 110;
                vitals.temperature = "98.6°F";
                vitals.oxygenSaturation = "94%";
                break;
                
            case "heart attack":
            case "stemi":
            case "acute mi":
                // Confirmed cardiac event - critical vitals
                vitals.bloodPressure = "160/100";
                vitals.heartRate = 120;
                vitals.temperature = "98.6°F";
                vitals.oxygenSaturation = "91%";
                break;
                
            case "stroke":
                // Confirmed stroke - time-critical
                vitals.bloodPressure = "180/110";
                vitals.heartRate = 90;
                vitals.temperature = "98.4°F";
                vitals.oxygenSaturation = "95%";
                break;
                
            case "stroke symptoms":
            case "tia":
                // Suspected stroke - needs imaging confirmation
                vitals.bloodPressure = "170/100";
                vitals.heartRate = 88;
                vitals.temperature = "98.4°F";
                vitals.oxygenSaturation = "96%";
                break;
                
            case "severe bleeding":
            case "hemorrhage":
                // Active hemorrhage - hemodynamic instability
                vitals.bloodPressure = "85/55";
                vitals.heartRate = 130;
                vitals.temperature = "97.2°F";
                vitals.oxygenSaturation = "90%";
                break;
                
            case "trauma":
                // Trauma needs assessment
                vitals.bloodPressure = "100/70";
                vitals.heartRate = 115;
                vitals.temperature = "97.8°F";
                vitals.oxygenSaturation = "94%";
                break;
                
            case "broken bone":
            case "fracture":
                vitals.bloodPressure = "130/85";
                vitals.heartRate = 95;
                vitals.temperature = "98.6°F";
                vitals.oxygenSaturation = "98%";
                break;
                
            case "abdominal pain":
                vitals.bloodPressure = "125/82";
                vitals.heartRate = 88;
                vitals.temperature = "99.2°F";
                vitals.oxygenSaturation = "98%";
                break;
                
            case "respiratory distress":
            case "difficulty breathing":
                vitals.bloodPressure = "140/90";
                vitals.heartRate = 105;
                vitals.temperature = "98.8°F";
                vitals.oxygenSaturation = "88%";
                break;
                
            default:
                vitals.bloodPressure = "120/80";
                vitals.heartRate = 78;
                vitals.temperature = "98.6°F";
                vitals.oxygenSaturation = "98%";
        }
        
        return vitals;
    }
    
    private int determinePainScore(String condition) {
        switch (condition.toLowerCase()) {
            case "chest pain":
            case "heart attack":
            case "stemi":
            case "acute mi":
                return ThreadLocalRandom.current().nextInt(7, 10);
            case "stroke":
            case "stroke symptoms":
            case "tia":
                return ThreadLocalRandom.current().nextInt(2, 5);
            case "severe bleeding":
            case "hemorrhage":
            case "trauma":
                return ThreadLocalRandom.current().nextInt(8, 11);
            case "broken bone":
            case "fracture":
                return ThreadLocalRandom.current().nextInt(6, 9);
            case "abdominal pain":
                return ThreadLocalRandom.current().nextInt(5, 8);
            case "respiratory distress":
            case "difficulty breathing":
                return ThreadLocalRandom.current().nextInt(4, 7);
            default:
                return ThreadLocalRandom.current().nextInt(3, 7);
        }
    }
    
    private String determineTriagePriority(String condition, VitalSigns vitals, int painScore) {
        // Priority based on clinical criteria
        switch (condition.toLowerCase()) {
            // CRITICAL - Immediate life threat
            case "heart attack":
            case "stemi":
            case "acute mi":
            case "stroke":
            case "severe bleeding":
            case "hemorrhage":
                return "CRITICAL";
                
            // CRITICAL or URGENT based on vitals
            case "chest pain":
            case "stroke symptoms":
            case "tia":
                // Check for hemodynamic instability
                if (vitals.heartRate > 120 || vitals.oxygenSaturation.replace("%", "").compareTo("92") < 0) {
                    return "CRITICAL";
                }
                return "URGENT";
                
            case "trauma":
                return painScore >= 8 ? "CRITICAL" : "URGENT";
                
            case "respiratory distress":
            case "difficulty breathing":
                // O2 sat determines criticality
                int o2Sat = Integer.parseInt(vitals.oxygenSaturation.replace("%", ""));
                return o2Sat < 92 ? "CRITICAL" : "URGENT";
                
            case "broken bone":
            case "fracture":
            case "abdominal pain":
                return "URGENT";
                
            default:
                return painScore >= 7 ? "URGENT" : "STANDARD";
        }
    }
    
    private List<String> orderDiagnostics(String condition, String priority) {
        List<String> orders = new ArrayList<>();
        
        switch (condition.toLowerCase()) {
            case "chest pain":
                orders.add("ECG_STAT");
                orders.add("Cardiac_Enzymes");
                orders.add("Chest_X-Ray");
                break;
            case "heart attack":
            case "stemi":
            case "acute mi":
                orders.add("ECG_STAT");
                orders.add("Cardiac_Enzymes_STAT");
                orders.add("Cath_Lab_Activation");
                break;
            case "stroke":
            case "stroke symptoms":
            case "tia":
                orders.add("CT_Head_STAT");
                orders.add("Neuro_Assessment");
                break;
            case "severe bleeding":
            case "hemorrhage":
            case "trauma":
                orders.add("CBC_STAT");
                orders.add("Type_and_Cross");
                orders.add("CT_Trauma_Protocol");
                break;
            case "broken bone":
            case "fracture":
                orders.add("X-Ray");
                orders.add("Ortho_Consult");
                break;
            case "abdominal pain":
                orders.add("CBC");
                orders.add("CMP");
                orders.add("CT_Abdomen");
                break;
            case "respiratory distress":
            case "difficulty breathing":
                orders.add("Chest_X-Ray_STAT");
                orders.add("ABG");
                orders.add("Pulmonology_Consult");
                break;
            default:
                orders.add("Basic_Labs");
        }
        
        return orders;
    }
    
    /**
     * Make routing decision based PURELY on clinical assessment
     * 
     * DIRECT_TO_TREATMENT criteria (bypass diagnostics):
     * - Confirmed acute conditions where delay is dangerous
     * - Clear diagnosis that doesn't need further workup
     * - Examples: Confirmed STEMI, confirmed stroke, active hemorrhage
     * 
     * STANDARD_WORKFLOW criteria (full diagnostic workup):
     * - Symptoms that could have multiple causes
     * - Conditions requiring imaging/labs to confirm
     * - Examples: Chest pain (could be cardiac, GI, musculoskeletal), 
     *             suspected stroke (needs CT before tPA)
     */
    private RoutingDecision makeRoutingDecision(String condition, String priority, 
                                                VitalSigns vitals, int painScore) {
        RoutingDecision decision = new RoutingDecision();
        decision.triageNurse = this.nurseId;
        decision.routingTimestamp = getCurrentTimestamp();
        
        // Determine routing based on CLINICAL CRITERIA
        boolean shouldBypassDiagnostics = evaluateClinicalBypassCriteria(condition, priority, vitals, painScore);
        decision.bypassDiagnostics = shouldBypassDiagnostics;
        
        if (shouldBypassDiagnostics) {
            // DIRECT TO TREATMENT path
            decision.routingPath = "DIRECT_TO_TREATMENT";
            decision.rationale = buildBypassRationale(condition, vitals);
            decision.estimatedTimeToTreatment = "5-10 minutes";
            decision.requiredTreatmentPreparation = buildTreatmentPreparation(condition);
            
            System.out.printf("[%s] ROUTING: DIRECT TO TREATMENT - %s\n", 
                getPlaceId(), decision.rationale);
            
        } else {
            // STANDARD WORKFLOW path
            decision.routingPath = "STANDARD_WORKFLOW";
            decision.rationale = buildStandardWorkflowRationale(condition);
            decision.estimatedTimeToTreatment = "30-60 minutes";
            
            decision.nextServices = new ArrayList<>();
            decision.nextServices.add("LaboratoryService");
            decision.nextServices.add("CardiologyService");
            decision.nextServices.add("RadiologyService");
            decision.nextServices.add("DiagnosisService");
            decision.nextServices.add("TreatmentService");
            
            System.out.printf("[%s] ROUTING: STANDARD WORKFLOW - %s\n", 
                getPlaceId(), decision.rationale);
        }
        
        return decision;
    }
    
    /**
     * Evaluate clinical criteria for bypassing diagnostics
     * 
     * Returns TRUE (bypass) for:
     * - Confirmed diagnoses where treatment is clear
     * - Time-critical conditions where delay is dangerous
     * 
     * Returns FALSE (standard workflow) for:
     * - Symptoms requiring differential diagnosis
     * - Conditions needing imaging/labs to confirm
     */
    private boolean evaluateClinicalBypassCriteria(String condition, String priority, 
                                                    VitalSigns vitals, int painScore) {
        String conditionLower = condition.toLowerCase();
        
        // BYPASS - Confirmed acute conditions (time-critical, clear treatment path)
        switch (conditionLower) {
            case "heart attack":
            case "stemi":
            case "acute mi":
                // Confirmed MI - activate cath lab immediately
                return true;
                
            case "stroke":
                // Confirmed stroke - time is brain, start treatment
                return true;
                
            case "severe bleeding":
            case "hemorrhage":
                // Active hemorrhage with hemodynamic instability
                if (vitals.heartRate > 120) {
                    return true;  // Tachycardia indicates significant blood loss
                }
                break;
        }
        
        // CRITICAL priority with severe vitals - consider direct treatment
        // This adds ~10% bypass for critical presentations
        if ("CRITICAL".equals(priority) && painScore >= 9) {
            // 50% of critical+severe pain cases bypass (contributes ~10% overall)
            if (ThreadLocalRandom.current().nextDouble() < 0.5) {
                System.out.printf("[%s] CRITICAL priority with pain %d/10 - bypassing diagnostics\n", 
                    getPlaceId(), painScore);
                return true;
            }
        }
        
        // TEST MODE: 15% random bypass for testing Gateway routing
        // Remove or comment out for production
        if (ThreadLocalRandom.current().nextDouble() < 0.15) {
            System.out.printf("[%s] TEST MODE: 15%% random bypass - DIRECT_TO_TREATMENT\n", getPlaceId());
            return true;
        }
        
        // STANDARD WORKFLOW - Symptoms needing workup
        switch (conditionLower) {
            case "chest pain":
                // Chest pain has broad differential - cardiac, GI, musculoskeletal, PE
                // Needs ECG, enzymes, possibly imaging to determine cause
                return false;
                
            case "stroke symptoms":
            case "tia":
                // MUST have CT before tPA to rule out hemorrhagic stroke
                // Giving tPA to hemorrhagic stroke is fatal
                return false;
                
            case "trauma":
                // Needs imaging to assess extent of injuries
                return false;
                
            case "abdominal pain":
                // Broad differential - appendicitis, cholecystitis, pancreatitis, etc.
                return false;
                
            case "respiratory distress":
            case "difficulty breathing":
                // Could be PE, pneumonia, CHF, asthma - needs workup
                return false;
                
            case "broken bone":
            case "fracture":
                // Needs X-ray to confirm and characterize
                return false;
        }
        
        // Default: Use standard workflow for safety
        return false;
    }
    
    private String buildBypassRationale(String condition, VitalSigns vitals) {
        switch (condition.toLowerCase()) {
            case "heart attack":
            case "stemi":
            case "acute mi":
                return "Confirmed STEMI - immediate cath lab activation per ACC/AHA guidelines. " +
                       "Door-to-balloon time critical.";
                
            case "stroke":
                return "Confirmed ischemic stroke within tPA window - " +
                       "immediate stroke team activation per AHA/ASA guidelines.";
                
            case "severe bleeding":
            case "hemorrhage":
                return String.format("Active hemorrhage with hemodynamic instability " +
                       "(HR %d, BP %s) - immediate surgical/IR intervention required.",
                       vitals.heartRate, vitals.bloodPressure);
                
            default:
                return "Clinical presentation requires immediate intervention without delay for diagnostics.";
        }
    }
    
    private String buildStandardWorkflowRationale(String condition) {
        switch (condition.toLowerCase()) {
            case "chest pain":
                return "Chest pain requires diagnostic workup (ECG, cardiac enzymes, possible imaging) " +
                       "to differentiate cardiac from non-cardiac etiology.";
                
            case "stroke symptoms":
            case "tia":
                return "Suspected stroke requires CT Head STAT to rule out hemorrhage before tPA consideration. " +
                       "tPA contraindicated in hemorrhagic stroke.";
                
            case "trauma":
                return "Trauma requires imaging (CT/X-ray) to assess extent of injuries " +
                       "before determining treatment plan.";
                
            case "abdominal pain":
                return "Abdominal pain has broad differential - labs and imaging needed " +
                       "to determine etiology and guide treatment.";
                
            case "respiratory distress":
            case "difficulty breathing":
                return "Respiratory distress requires workup (CXR, ABG, possible CT-PE) " +
                       "to determine cause and guide treatment.";
                
            default:
                return String.format("Condition (%s) requires diagnostic confirmation before treatment.", condition);
        }
    }
    
    private List<String> buildTreatmentPreparation(String condition) {
        List<String> preparation = new ArrayList<>();
        
        switch (condition.toLowerCase()) {
            case "heart attack":
            case "stemi":
            case "acute mi":
                preparation.add("Cath_Lab_Activation");
                preparation.add("Cardiology_Team_Alert");
                preparation.add("Antiplatelet_Therapy_Ready");
                preparation.add("Heparin_Drip_Ready");
                break;
                
            case "stroke":
                preparation.add("Stroke_Team_Activation");
                preparation.add("tPA_Protocol_Ready");
                preparation.add("Neuro_ICU_Bed_Reserved");
                preparation.add("BP_Management_Protocol");
                break;
                
            case "severe bleeding":
            case "hemorrhage":
                preparation.add("Trauma_Bay_Ready");
                preparation.add("Blood_Products_Available");
                preparation.add("Massive_Transfusion_Protocol");
                preparation.add("Surgical_Team_Standby");
                preparation.add("IR_Team_Notified");
                break;
        }
        
        return preparation;
    }
    
    // ===== UTILITY METHODS =====
    
    /**
     * Extract actual condition from potentially wrapped token data
     */
    private String extractConditionFromRawToken(String tokenData) {
        if (tokenData == null || tokenData.trim().isEmpty()) {
            return "unknown";
        }
        
        // Try to find condition in various formats
        String[] conditionPatterns = {
            "\"condition\":\"([^\"]+)\"",
            "\"condition\": \"([^\"]+)\"",
            "condition=([^,}]+)",
            "\"presentingComplaint\":\"([^\"]+)\"",
            "\"chief_complaint\":\"([^\"]+)\""
        };
        
        for (String pattern : conditionPatterns) {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
            java.util.regex.Matcher m = p.matcher(tokenData);
            if (m.find()) {
                String condition = m.group(1).trim();
                if (!condition.isEmpty() && !condition.equalsIgnoreCase("null")) {
                    return condition;
                }
            }
        }
        
        // Fallback: if token is simple string, use it directly
        if (!tokenData.contains("{") && !tokenData.contains(":")) {
            return tokenData.trim();
        }
        
        return "unknown";
    }
    
    protected String getCurrentTimestamp() {
        return java.time.LocalDateTime.now().format(
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        );
    }
    
    // ===== FRAMEWORK METHODS =====
    
    @Override
    protected String getServiceResultsKey() {
        return "triageResults";
    }
    
    @Override
    protected String getCompletionStatus() {
        return "TRIAGE_COMPLETE";
    }
    
    @Override
    protected String[] getRequiredTables() {
        return new String[0];
    }
    
    @Override
    protected void setupServiceDatabase() {
        System.out.printf("[%s] No database setup needed - service ready\n", getPlaceId());
    }
    
    // ===== DATA CLASSES =====
    
    public static class TriageAssessment extends BaseServiceAssessment {
        private TriageProtocol protocol;
        private VitalSigns vitalSigns;
        private int painScore;
        private String triagePriority;
        private List<String> diagnosticOrders;
        private RoutingDecision routingDecision;
        private String actualCondition;
        
        public TriageAssessment(String assessmentId) {
            super(assessmentId);
        }
        
        @Override
        public String toJsonFields() {
            StringBuilder json = new StringBuilder();
            
            json.append(jsonField("triage_nurse", "RN_TRIAGE")).append(",");
            json.append(jsonField("triage_priority", triagePriority)).append(",");
            json.append(jsonField("pain_score", painScore + "/10")).append(",");
            
            if (actualCondition != null && !actualCondition.equals("unknown")) {
                json.append(jsonField("chief_complaint", actualCondition)).append(",");
            }
            
            if (vitalSigns != null) {
                json.append(jsonObjectField("vital_signs",
                    jsonField("blood_pressure", vitalSigns.bloodPressure) + "," +
                    jsonField("heart_rate", String.valueOf(vitalSigns.heartRate)) + "," +
                    jsonField("temperature", vitalSigns.temperature) + "," +
                    jsonField("oxygen_saturation", vitalSigns.oxygenSaturation)
                )).append(",");
            }
            
            if (diagnosticOrders != null && !diagnosticOrders.isEmpty()) {
                json.append("\"diagnostic_orders\":[");
                for (int i = 0; i < diagnosticOrders.size(); i++) {
                    json.append("\"").append(diagnosticOrders.get(i)).append("\"");
                    if (i < diagnosticOrders.size() - 1) {
                        json.append(",");
                    }
                }
                json.append("],");
            }
            
            if (routingDecision != null) {
                json.append(jsonObjectField("routing_decision",
                    jsonField("routing_path", routingDecision.routingPath) + "," +
                    jsonField("bypass_diagnostics", String.valueOf(routingDecision.bypassDiagnostics)) + "," +
                    jsonField("rationale", routingDecision.rationale) + "," +
                    jsonField("estimated_time_to_treatment", routingDecision.estimatedTimeToTreatment) + "," +
                    jsonField("routing_timestamp", routingDecision.routingTimestamp)
                )).append(",");
                
                if (routingDecision.bypassDiagnostics && routingDecision.requiredTreatmentPreparation != null) {
                    json.append("\"required_treatment_preparation\":[");
                    for (int i = 0; i < routingDecision.requiredTreatmentPreparation.size(); i++) {
                        json.append("\"").append(routingDecision.requiredTreatmentPreparation.get(i)).append("\"");
                        if (i < routingDecision.requiredTreatmentPreparation.size() - 1) {
                            json.append(",");
                        }
                    }
                    json.append("],");
                } else if (!routingDecision.bypassDiagnostics && routingDecision.nextServices != null) {
                    json.append("\"next_services\":[");
                    for (int i = 0; i < routingDecision.nextServices.size(); i++) {
                        json.append("\"").append(routingDecision.nextServices.get(i)).append("\"");
                        if (i < routingDecision.nextServices.size() - 1) {
                            json.append(",");
                        }
                    }
                    json.append("],");
                }
            }
            
            json.append(jsonField("protocol_id", protocol != null ? protocol.protocolId : "DEFAULT_TRIAGE"));
            
            return json.toString();
        }
        
        @Override
        public String getSummary() {
            String routingInfo = routingDecision != null ? 
                (routingDecision.bypassDiagnostics ? " -> DIRECT TREATMENT" : " -> STANDARD WORKFLOW") : "";
            return String.format("Triage Assessment: %s priority, pain %d/10%s", 
                triagePriority, painScore, routingInfo);
        }
        
        public void setProtocol(TriageProtocol protocol) { this.protocol = protocol; }
        public void setVitalSigns(VitalSigns vitalSigns) { this.vitalSigns = vitalSigns; }
        public void setPainScore(int painScore) { this.painScore = painScore; }
        public void setTriagePriority(String triagePriority) { this.triagePriority = triagePriority; }
        public void setDiagnosticOrders(List<String> diagnosticOrders) { this.diagnosticOrders = diagnosticOrders; }
        public void setRoutingDecision(RoutingDecision routingDecision) { this.routingDecision = routingDecision; }
        public void setActualCondition(String condition) { this.actualCondition = condition; }
        
        public RoutingDecision getRoutingDecision() { return routingDecision; }
    }
    
    public static class RoutingDecision {
        public String routingPath;
        public boolean bypassDiagnostics;
        public String rationale;
        public String estimatedTimeToTreatment;
        public String routingTimestamp;
        public String triageNurse;
        public List<String> requiredTreatmentPreparation;
        public List<String> nextServices;
    }
    
    private static class TriageProtocol {
        String protocolId;
        String assessmentType;
        String priorityGuidelines;
        String standardOrders;
    }
    
    private static class VitalSigns {
        String bloodPressure;
        int heartRate;
        String temperature;
        String oxygenSaturation;
    }
}