package org.btsn.places;

/**
 * Example showing how CardiologyService works with ServiceHelper reflection architecture
 * 
 * ServiceHelper will:
 * 1. Call: new CardiologyService("sequenceID") 
 * 2. Call: service.processCardiacAssessment("tokenData")
 * 3. Expect: String result back
 */
public class CardiologyServiceExample {
    
    public static void main(String[] args) {
        System.out.println("=== CardiologyService ServiceHelper Architecture Demo ===\n");
        
        // Simulate what ServiceHelper does via reflection
        demonstrateServiceHelperFlow();
        
        // Show direct usage for comparison
        demonstrateDirectUsage();
        
        // Show error handling
        demonstrateErrorHandling();
        
        // Show compatibility info
        showServiceHelperCompatibility();
    }
    
    /**
     * Demonstrate how ServiceHelper invokes the service via reflection
     */
    private static void demonstrateServiceHelperFlow() {
        System.out.println("--- SERVICEHELPER REFLECTION FLOW ---");
        
        try {
            // Step 1: ServiceHelper creates instance via reflection
            String sequenceID = "1001";
            System.out.println("ServiceHelper: Creating CardiologyService with sequenceID: " + sequenceID);
            
            // This is what ServiceHelper does:
            // Constructor<?> constructor = serviceClass.getConstructor(String.class);
            // Object serviceInstance = constructor.newInstance(sequenceID);
            CardiologyService service = new CardiologyService(sequenceID);
            
            // Step 2: ServiceHelper prepares token data from input arguments
            String tokenData = createSampleTokenData();
            System.out.println("ServiceHelper: Prepared token data: " + tokenData);
            
            // Step 3: ServiceHelper invokes method via reflection
            System.out.println("ServiceHelper: Invoking processCardiacAssessment...");
            
            // This is what ServiceHelper does:
            // Object result = targetMethod.invoke(serviceInstance, parametersArray);
            String result = service.processCardiacAssessment(tokenData);
            
            // Step 4: ServiceHelper returns result
            System.out.println("ServiceHelper: Result received: " + result);
            System.out.println("ServiceHelper: Return type: " + result.getClass().getSimpleName());
            
            // Cleanup
            service.shutdown();
            
        } catch (Exception e) {
            System.err.println("ServiceHelper flow error: " + e.getMessage());
            e.printStackTrace();
        }
        
        System.out.println();
    }
    
    /**
     * Show direct usage for comparison
     */
    private static void demonstrateDirectUsage() {
        System.out.println("--- DIRECT USAGE FLOW ---");
        
        CardiologyService service = null;
        try {
            // Create service directly
            service = new CardiologyService("2002");
            
            // Test different cardiac indication scenarios
            testScenario(service, "chest_pain", "P_CHEST_PAIN");
            testScenario(service, "shortness_of_breath", "P_SOB");
            testScenario(service, "palpitations", "P_PALPITATIONS");
            testScenario(service, "syncope", "P_SYNCOPE");
            testScenario(service, "routine_ecg", "P_ROUTINE");
            
        } catch (Exception e) {
            System.err.println("Error in direct usage: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // Cleanup
            if (service != null) {
                service.shutdown();
            }
        }
        System.out.println();
    }
    
    /**
     * Test different clinical scenarios
     */
    private static void testScenario(CardiologyService service, String indication, String patientId) {
        System.out.printf("Testing scenario: %s (Patient: %s)\n", indication, patientId);
        
        // Create token data in the format the system expects: {"token":"\"patient\", \"cardiologist\""}
        String tokenData = String.format(
            "{\"token\":\"\\\"%s_%s\\\", \\\"DR_CARDIO\\\"\"}",
            patientId, indication.toUpperCase()
        );
        
        String result = service.processCardiacAssessment(tokenData);
        System.out.println("Input: " + tokenData);
        System.out.println("Result: " + result);
        System.out.println();
    }
    
    /**
     * Create sample token data that would come from the system
     */
    private static String createSampleTokenData() {
        // This simulates the JSON token data that would be passed 
        // from the rule engine through ServiceHelper
        // Format matches: {"token":"\"P_Cardiology\", \"DR_CARD001\""}
        return "{\"token\":\"\\\"P_CHEST_PAIN\\\", \\\"DR_CARD001\\\"\"}";
    }
    
    /**
     * Demonstrate error handling
     */
    private static void demonstrateErrorHandling() {
        System.out.println("--- ERROR HANDLING ---");
        
        CardiologyService service = null;
        try {
            service = new CardiologyService("3003");
            
            // Test with invalid data
            String invalidData = "invalid_json_data";
            String result = service.processCardiacAssessment(invalidData);
            System.out.println("Invalid data result: " + result);
            
            // Test with empty data
            String emptyResult = service.processCardiacAssessment("");
            System.out.println("Empty data result: " + emptyResult);
            
            // Test with null
            String nullResult = service.processCardiacAssessment(null);
            System.out.println("Null data result: " + nullResult);
            
            // Test with valid token format
            String validToken = "{\"token\":\"\\\"P_ERROR_TEST\\\", \\\"DR_CARD_ERR\\\"\"}";
            String validResult = service.processCardiacAssessment(validToken);
            System.out.println("Valid token result: " + validResult);
            
        } catch (Exception e) {
            System.err.println("Error handling demonstration failed: " + e.getMessage());
        } finally {
            if (service != null) {
                service.shutdown();
            }
        }
        System.out.println();
    }
    
    /**
     * Show what the actual ServiceHelper method signature looks like
     */
    public static void showServiceHelperCompatibility() {
        System.out.println("=== SERVICEHELPER COMPATIBILITY ===");
        System.out.println("Required constructor: CardiologyService(String sequenceID)");
        System.out.println("Required method: String processCardiacAssessment(String tokenData)");
        System.out.println("Return type: String (JSON format)");
        System.out.println();
        
        System.out.println("Expected Input Format: {\"token\":\"\\\"P_CHEST_PAIN\\\", \\\"DR_CARD001\\\"\"}");
        System.out.println("Expected Output Format: {\"cardiology\":{\"cardiology\":{\"original_token\":\"...\",\"assessment_id\":\"...\",\"ecg_findings\":{...},\"risk_stratification\":\"high_risk\",\"status\":\"CARDIAC_COMPLETE\",...}}}");
        System.out.println("Structure: {attributeName: {attributeName: {actualData}}}");
        System.out.println();
        
        System.out.println("ServiceHelper reflection calls:");
        System.out.println("1. Class.forName('org.btsn.healthcare.services.CardiologyService')");
        System.out.println("2. serviceClass.getConstructor(String.class)");
        System.out.println("3. constructor.newInstance(sequenceID)");
        System.out.println("4. serviceClass.getDeclaredMethods() -> find 'processCardiacAssessment'");
        System.out.println("5. method.invoke(serviceInstance, tokenData)");
        System.out.println("6. return result (JSON string)");
        System.out.println();
        
        System.out.println("CARDIOLOGY-SPECIFIC FEATURES:");
        System.out.println("• 12-Lead ECG Analysis and Interpretation");
        System.out.println("• Cardiac Rhythm Detection and Classification");
        System.out.println("• ST Segment Analysis for ACS Detection");
        System.out.println("• Cardiac Risk Stratification (TIMI-like scoring)");
        System.out.println("• Clinical Recommendations Based on Findings");
        System.out.println("• Arrhythmia Detection and Assessment");
        System.out.println();
        
        System.out.println("ECG RHYTHMS DETECTED:");
        System.out.println("• normal_sinus_rhythm: Regular rhythm 60-100 bpm");
        System.out.println("• sinus_tachycardia: Regular rhythm >100 bpm");
        System.out.println("• sinus_bradycardia: Regular rhythm <60 bpm");
        System.out.println("• atrial_fibrillation: Irregularly irregular rhythm");
        System.out.println();
        
        System.out.println("ST SEGMENT ANALYSIS:");
        System.out.println("• no_acute_st_changes: Normal ST segments");
        System.out.println("• st_elevation_in_leads_V2_V3_V4: Anterior STEMI pattern");
        System.out.println("• st_depression_in_leads_II_III_aVF: Inferior ischemia");
        System.out.println("• diffuse_st_depression: Widespread ischemia");
        System.out.println("• right_heart_strain_pattern: Pulmonary embolism/RHF");
        System.out.println();
        
        System.out.println("RISK STRATIFICATION:");
        System.out.println("• high_risk: ST elevation, high risk score, urgent intervention needed");
        System.out.println("• intermediate_risk: ST depression, moderate risk score, close monitoring");
        System.out.println("• low_risk: Normal ECG, low risk factors, routine follow-up");
        System.out.println();
        
        System.out.println("CLINICAL INDICATIONS HANDLED:");
        System.out.println("• chest_pain -> ACS assessment, STEMI/NSTEMI detection");
        System.out.println("• shortness_of_breath -> Heart failure assessment, strain patterns");
        System.out.println("• palpitations -> Arrhythmia detection, rhythm analysis");
        System.out.println("• syncope -> Conduction abnormalities, arrhythmia evaluation");
        System.out.println("• routine_ecg -> Baseline assessment, screening");
        System.out.println();
        
        System.out.println("CRITICAL FINDINGS FLAGGED:");
        System.out.println("• STEMI patterns requiring immediate catheterization");
        System.out.println("• High-grade AV blocks requiring pacing");
        System.out.println("• Malignant arrhythmias requiring urgent treatment");
        System.out.println("• Acute ischemic changes requiring cardiology consultation");
        System.out.println();
        
        System.out.println("INTEGRATION WITH HEALTHCARE FLOW:");
        System.out.println("• Receives orders from triage with specific indications");
        System.out.println("• Provides cardiac assessment for clinical decision synchronization");
        System.out.println("• Generates risk-appropriate recommendations");
        System.out.println("• Feeds results to T_in_Diagnosis synchronization point");
        System.out.println();
    }
}

/**
 * Sample output when run:
 * 
 * === CardiologyService ServiceHelper Architecture Demo ===
 * 
 * --- SERVICEHELPER REFLECTION FLOW ---
 * ServiceHelper: Creating CardiologyService with sequenceID: 1001
 * [CARDIO_PLACE_1001] CardiologyService initialized for sequence: 1001, cardiologist: DR_CARD_1
 * [CARDIO_PLACE_1001] Database initialized
 * [CARDIO_PLACE_1001] Cardiac protocols loaded
 * ServiceHelper: Prepared token data: {"token":"\"P_CHEST_PAIN\", \"DR_CARD001\""}
 * ServiceHelper: Invoking processCardiacAssessment...
 * [CARDIO_PLACE_1001] Processing cardiac assessment for sequence 1001 with data: {"token":"\"P_CHEST_PAIN\", \"DR_CARD001\""}
 * [CARDIO_PLACE_1001] Extracted: Patient=P_CHEST_PAIN, Indication=chest_pain
 * [CARDIO_PLACE_1001] Stored cardiac assessment: CARDIAC_P_CHEST_PAIN_20250702123456_1234
 * [CARDIO_PLACE_1001] Cardiac assessment completed - Risk: high_risk
 * ServiceHelper: Result received: {"cardiology":{"cardiology":{"original_token":"'P_CHEST_PAIN', 'DR_CARD001'","assessment_id":"CARDIAC_P_CHEST_PAIN_20250702123456_1234","patient_id":"P_CHEST_PAIN","indication":"chest_pain","cardiologist":"DR_CARD_1","ecg_findings":{"rhythm":"normal_sinus_rhythm","rate":95,"intervals":"PR:150ms, QRS:90ms, QT:400ms","axis":"normal","st_changes":"st_elevation_in_leads_V2_V3_V4","arrhythmias":"no_significant_arrhythmias"},"risk_factors":{"age_risk":"high_risk_age","cardiac_history":"no_known_cardiac_disease","risk_score":4},"clinical_interpretation":"Normal sinus rhythm. ST elevation consistent with acute STEMI. URGENT cardiology consultation required. Moderate cardiac risk profile.","risk_stratification":"high_risk","recommendations":"URGENT cardiology consultation, Serial troponins, Consider immediate catheterization, STEMI protocol activation","status":"CARDIAC_COMPLETE"}}}
 * ServiceHelper: Return type: String
 * [CARDIO_PLACE_1001] Database closed
 * 
 * --- DIRECT USAGE FLOW ---
 * [CARDIO_PLACE_2002] CardiologyService initialized for sequence: 2002, cardiologist: DR_CARD_2
 * [CARDIO_PLACE_2002] Database initialized
 * [CARDIO_PLACE_2002] Cardiac protocols loaded
 * 
 * Testing scenario: chest_pain (Patient: P_CHEST_PAIN)
 * [CARDIO_PLACE_2002] Processing cardiac assessment for sequence 2002 with data: {"token":"\"P_CHEST_PAIN_CHEST_PAIN\", \"DR_CARDIO\""}
 * [CARDIO_PLACE_2002] Extracted: Patient=P_CHEST_PAIN_CHEST_PAIN, Indication=chest_pain
 * [CARDIO_PLACE_2002] Stored cardiac assessment: CARDIAC_P_CHEST_PAIN_CHEST_PAIN_20250702123457_5678
 * [CARDIO_PLACE_2002] Cardiac assessment completed - Risk: intermediate_risk
 * Input: {"token":"\"P_CHEST_PAIN_CHEST_PAIN\", \"DR_CARDIO\""}
 * Result: {"cardiology":{"cardiology":{"original_token":"'P_CHEST_PAIN_CHEST_PAIN', 'DR_CARDIO'","assessment_id":"CARDIAC_P_CHEST_PAIN_CHEST_PAIN_20250702123457_5678","patient_id":"P_CHEST_PAIN_CHEST_PAIN","indication":"chest_pain","cardiologist":"DR_CARD_2","ecg_findings":{"rhythm":"sinus_tachycardia","rate":115,"intervals":"PR:160ms, QRS:95ms, QT:380ms","axis":"normal","st_changes":"st_depression_in_leads_II_III_aVF","arrhythmias":"sinus_tachycardia_likely_reactive"},"risk_factors":{"age_risk":"moderate_risk_age","cardiac_history":"no_known_cardiac_disease","risk_score":2},"clinical_interpretation":"Sinus tachycardia, likely reactive to clinical condition. ST depression may indicate ischemia or NSTEMI. Low to moderate cardiac risk profile.","risk_stratification":"intermediate_risk","recommendations":"Cardiology consultation within 24 hours, Serial ECGs, Continuous cardiac monitoring, Stress testing if symptoms resolve","status":"CARDIAC_COMPLETE"}}}
 */