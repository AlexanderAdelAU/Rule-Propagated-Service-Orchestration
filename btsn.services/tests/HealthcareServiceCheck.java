import org.btsn.business.healthcare.*;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

/** Exercises the packaged healthcare contracts and checks that diagnostic inputs stay distinct. */
public final class HealthcareServiceCheck {
    private static final String PATIENT = "{\"data\":{\"patientId\":\"PAT-001\",\"condition\":\"chest pain\"}}";
    public static void main(String[] args) throws Exception {
        TriageService triage = new TriageService("1000000");
        LaboratoryService laboratory = new LaboratoryService("1000001");
        CardiologyService cardiology = new CardiologyService("1000002");
        RadiologyService radiology = new RadiologyService("1000003");
        DiagnosisService diagnosis = new DiagnosisService("1000001");
        TreatmentService treatment = new TreatmentService("1000001");
        try {
            JSONObject triageResult = result(triage.processTriageAssessment(PATIENT), "triageResults");
            require(triageResult.get("routing_decision") instanceof JSONObject, "Missing triage routing decision");
            String input = triageResult.toJSONString();
            JSONObject lab = result(laboratory.processLabRequest(input), "laboratoryResults");
            JSONObject cardiac = result(cardiology.processCardiacAssessment(input), "cardiologyResults");
            JSONObject imaging = result(radiology.processImagingRequest(input), "radiologyResults");
            JSONObject clinical = result(diagnosis.processClinicalDecision(imaging.toJSONString(), lab.toJSONString(), cardiac.toJSONString()), "diagnosisResults");
            result(treatment.executeTreatmentPlan(clinical.toJSONString()), "treatmentResults");
            JSONObject directTriage = result(triage.processTriageAssessment("{\"data\":{\"patientId\":\"PAT-002\",\"condition\":\"heart attack\"}}"), "triageResults");
            require("DIRECT_TO_TREATMENT".equals(((JSONObject) directTriage.get("routing_decision")).get("routing_path")), "Critical case lost its direct route");
            JSONObject direct = result(treatment.executeDirectTreatment(directTriage.toJSONString()), "treatmentResults");
            require(Boolean.TRUE.equals(direct.get("feedforward")), "Direct treatment lost its route identity");
            JSONObject federated = result(radiology.federatedRadiologyRequest("{\"data\":{\"patientId\":\"EXT-001\",\"condition\":\"chest pain\",\"external_hospital\":\"EXTERNAL_HOSPITAL_001\"}}"), "radiologyResults");
            require(Boolean.TRUE.equals(federated.get("cross_facility_audit")), "Federated audit fields are outside the declared result");
            require(federated.get("federated_request_id") != null, "Missing federated request ID");
            System.out.println("PASS: eight packaged healthcare operations, diagnostic join inputs, direct treatment and federated audit contract");
        } finally {
            triage.shutdown(); laboratory.shutdown(); cardiology.shutdown();
            radiology.shutdown(); diagnosis.shutdown(); treatment.shutdown();
        }
    }
    private static JSONObject result(String json, String attribute) throws Exception {
        JSONObject outer = (JSONObject) new JSONParser().parse(json);
        require(outer.size() == 1 && outer.get(attribute) instanceof JSONObject, "Invalid business return contract: " + json);
        JSONObject result = (JSONObject) outer.get(attribute);
        require(!result.containsKey("error") && !"ERROR".equals(result.get("status")), "Business operation returned an error: " + json);
        for (String field : new String[]{"marking", "executionTime", "notAfter", "workflow_start_time"})
            require(!result.containsKey(field), "Business response supplies runtime field " + field);
        require(result.get("data") instanceof JSONObject || Boolean.TRUE.equals(result.get("feedforward")),
                "Missing assessment data: " + json);
        return result;
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
