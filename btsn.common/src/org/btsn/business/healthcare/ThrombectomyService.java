package org.btsn.business.healthcare;

import org.json.simple.JSONObject;

/**
 * Clot retrieval at the comprehensive stroke centre, in the suite the ambulance's alert held (host: the stroke
 * centre's neurointervention team).
 *
 * retrieveClot: strokePlan -> thrombectomyResults, straight from the neurologist's decision (no thrombolysis).
 * retrieveClotAfterLysis: thrombolysisResults -> thrombectomyResults, after thrombolysis at the receiving hospital
 * ("drip and ship").
 * Both use the suite hold. The result grades reperfusion on the eTICI scale: 2b or better is a successful retrieval.
 * Routes on "retrieved".
 */
public class ThrombectomyService extends StrokeServiceBase {

    public String retrieveClot(String strokePlan) {
        return retrieve(strokePlan, false);
    }

    public String retrieveClotAfterLysis(String thrombolysisResults) {
        return retrieve(thrombolysisResults, true);
    }

    @SuppressWarnings("unchecked")
    private String retrieve(String input, boolean afterLysis) {
        JSONObject strokeCase = strokeCase(input);
        String grade = text(strokeCase, "etici", "2c");
        JSONObject findings = new JSONObject();
        findings.put("procedure", "Mechanical thrombectomy" + (afterLysis ? " after thrombolysis" : ""));
        findings.put("site", text(strokeCase, "occlusion_site", "left M1 middle cerebral artery"));
        findings.put("etici", grade);
        findings.put("reperfused", grade.startsWith("2b") || grade.startsWith("2c") || grade.startsWith("3"));
        findings.put("suite", "Angiography suite 1 (held since the ambulance's alert)");
        return strokeResult("thrombectomyResults", strokeCase, workflowId(input), findings, "retrieved");
    }
}
