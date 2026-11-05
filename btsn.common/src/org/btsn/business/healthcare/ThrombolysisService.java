package org.btsn.business.healthcare;

import org.json.simple.JSONObject;

/**
 * Clot-dissolving treatment at the receiving hospital, on the neurologist's decision (host: the receiving hospital).
 *
 * strokePlan -> thrombolysisResults. Tenecteplase 0.25 mg/kg as a single bolus, at most 25 mg. Routes on "transfer"
 * (a large-vessel occlusion: on to the stroke centre for clot retrieval) or "lysis_only" (nothing to retrieve: the
 * suite hold is no longer needed).
 */
public class ThrombolysisService extends StrokeServiceBase {

    @SuppressWarnings("unchecked")
    public String thrombolyse(String strokePlan) {
        JSONObject strokeCase = strokeCase(strokePlan);
        double weight = number(strokeCase, "weight_kg", 80);
        double dose = Math.min(25.0, Math.round(weight * 0.25 * 10) / 10.0);
        boolean transfer = Boolean.parseBoolean(textValue(strokePlan, "transfer_for_thrombectomy", "false"));

        JSONObject findings = new JSONObject();
        findings.put("drug", "tenecteplase");
        findings.put("dose_mg", dose);
        findings.put("weight_kg", weight);
        findings.put("next", transfer ? "Transfer to the stroke centre for clot retrieval" : "Stroke unit care");
        return strokeResult("thrombolysisResults", strokeCase, workflowId(strokePlan), findings, transfer ? "transfer" : "lysis_only");
    }
}
