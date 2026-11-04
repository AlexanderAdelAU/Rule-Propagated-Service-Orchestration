package org.btsn.business.healthcare;

import org.json.simple.JSONObject;

/**
 * The telestroke neurologist at the comprehensive stroke centre, reviewing the patient by video with the receiving
 * hospital (host: the comprehensive stroke centre). The join of the stroke code: the imaging provider's CT and the
 * pathology provider's bloods both arrive here before the decision.
 *
 * radiologyResults + laboratoryResults -> strokePlan. Routes on "thrombolyse", "thrombectomy" or "no_reperfusion":
 * - Haemorrhage on CT: no_reperfusion (no clot to treat).
 * - Clot-dissolving treatment (thrombolysis) when all hold: onset within 4.5 hours (270 min); blood pressure at or
 *   under 185/110, after one dose of an antihypertensive if it was above (taken to lower the systolic by 25 mmHg);
 *   INR at or under 1.7; platelets at least 100. Then thrombolyse, and transfer on for clot retrieval as well if
 *   the angiogram shows a large-vessel occlusion ("drip and ship").
 * - Otherwise clot retrieval (thrombectomy) alone, for a large-vessel occlusion within 24 hours.
 * - Otherwise no_reperfusion: stroke unit care at the receiving hospital.
 * Every route but thrombectomy (here or after thrombolysis) leaves the stroke centre's suite hold unused.
 */
public class TelestrokeService extends StrokeServiceBase {

    @SuppressWarnings("unchecked")
    public String decide(String radiologyResults, String laboratoryResults) {
        JSONObject strokeCase = strokeCase(radiologyResults, laboratoryResults);
        boolean haemorrhage = Boolean.parseBoolean(textValue(radiologyResults, "haemorrhage", "false"));
        boolean lvo = Boolean.parseBoolean(textValue(radiologyResults, "large_vessel_occlusion", "false"));
        double onset = number(strokeCase, "onset_minutes", 9999);
        double systolic = number(strokeCase, "systolic", 150), diastolic = number(strokeCase, "diastolic", 85);
        double inr = numberValue(laboratoryResults, "inr", 1.0), platelets = numberValue(laboratoryResults, "platelets", 250);

        JSONObject findings = new JSONObject();
        boolean bpTreated = systolic > 185 || diastolic > 110;
        if (bpTreated) { systolic -= 25; diastolic -= 10; }
        findings.put("blood_pressure", (int) systolic + "/" + (int) diastolic + (bpTreated ? " after an antihypertensive" : ""));

        String route, reason;
        if (haemorrhage) { route = "no_reperfusion"; reason = "Intracerebral haemorrhage: blood pressure lowering and neurosurgical review"; }
        else {
            String contraindication = onset > 270 ? "onset " + (int) onset + " min, beyond 4.5 hours"
                : systolic > 185 || diastolic > 110 ? "blood pressure above 185/110 despite treatment"
                : inr > 1.7 ? "INR " + inr + " above 1.7"
                : platelets < 100 ? "platelets " + (int) platelets + " below 100" : null;
            boolean retrievable = lvo && onset <= 1440;
            if (contraindication == null) {
                route = "thrombolyse";
                reason = retrievable ? "Thrombolyse now, then transfer for clot retrieval" : "Thrombolyse; no occlusion to retrieve";
            } else if (retrievable) {
                route = "thrombectomy";
                reason = "No thrombolysis (" + contraindication + "); large-vessel occlusion: transfer for clot retrieval";
            } else {
                route = "no_reperfusion";
                reason = "No thrombolysis (" + contraindication + ") and no occlusion to retrieve: stroke unit care";
            }
        }
        findings.put("decision", route);
        findings.put("reason", reason);
        findings.put("transfer_for_thrombectomy", !haemorrhage && lvo && onset <= 1440);
        findings.put("neurologist", "Telestroke on-call, comprehensive stroke centre");
        return strokeResult("strokePlan", strokeCase, firstWorkflowId(radiologyResults, laboratoryResults), findings, route);
    }

    private String firstWorkflowId(String a, String b) {
        String id = workflowId(a);
        return id != null ? id : workflowId(b);
    }
}
