package org.btsn.business.healthcare;

import org.json.simple.JSONObject;

/**
 * The receiving hospital's emergency department meets the ambulance at the door (host: the receiving hospital).
 *
 * suiteHold -> triageResults. Routes on "stroke_code" (CT and bloods at once, in parallel) or "stroke_excluded":
 * - Symptoms fully resolved on arrival (a transient ischaemic attack), or a severity score of 0: stroke_excluded. No
 *   reperfusion treatment: the suite the ambulance's alert held is no longer needed.
 * - Otherwise the stroke code goes on: imaging at the regional imaging provider and bloods at the regional pathology
 *   provider, ordered together.
 */
public class StrokeTriageService extends StrokeServiceBase {

    @SuppressWarnings("unchecked")
    public String assessStroke(String suiteHold) {
        JSONObject strokeCase = strokeCase(suiteHold);
        double severity = number(strokeCase, "nihss", 0);
        boolean resolved = flag(strokeCase, "symptoms_resolved");

        String route = resolved || severity <= 0 ? "stroke_excluded" : "stroke_code";
        JSONObject findings = new JSONObject();
        findings.put("nihss", (int) severity);
        findings.put("severity", severity >= 16 ? "severe" : severity >= 5 ? "moderate" : "minor");
        findings.put("reason", resolved ? "Symptoms resolved on arrival: transient ischaemic attack"
            : severity <= 0 ? "No deficit on examination" : "Stroke code: CT brain with angiography, and bloods");
        // Read by the shared imaging and pathology services: a stroke code is imaged and tested as such.
        findings.put("indication", "stroke");
        findings.put("priority", "CRITICAL");
        return strokeResult("triageResults", strokeCase, workflowId(suiteHold), findings, route);
    }
}
