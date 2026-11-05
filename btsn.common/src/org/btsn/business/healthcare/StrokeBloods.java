package org.btsn.business.healthcare;

import org.json.simple.JSONObject;

/**
 * The regional pathology provider's stroke bloods: what LaboratoryService.processLabRequest does for a stroke code.
 * The provider's analyser serves every hospital that sends to it; a stroke code is run first because its process has
 * the higher priority on the provider's host.
 *
 * The results the neurologist needs before clot-dissolving treatment: INR (clotting; raised by warfarin), platelet
 * count and blood glucose.
 */
public class StrokeBloods extends StrokeServiceBase {

    public boolean isStrokeCode(String request) {
        return Boolean.parseBoolean(textValue(request, "stroke_code", "false"));
    }

    @SuppressWarnings("unchecked")
    public String strokeBloods(String triageResults) {
        JSONObject strokeCase = strokeCase(triageResults);
        double inr = number(strokeCase, "inr", 1.0), platelets = number(strokeCase, "platelets", 250), glucose = number(strokeCase, "glucose_mmol", 6.0);
        JSONObject findings = new JSONObject();
        findings.put("panel", "STROKE_BLOODS: INR, full blood count, glucose");
        findings.put("inr", inr);
        findings.put("platelets", platelets);
        findings.put("glucose_mmol", glucose);
        findings.put("critical", inr > 1.7 ? "INR " + inr + " above 1.7" : platelets < 100 ? "Platelets " + (int) platelets + " below 100" : "none");
        return strokeResult("laboratoryResults", strokeCase, workflowId(triageResults), findings, null);
    }
}
