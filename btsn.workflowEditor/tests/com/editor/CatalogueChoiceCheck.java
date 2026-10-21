package com.editor;

import java.io.File;
import java.nio.file.*;
import java.util.*;
import org.json.simple.*;
import org.json.simple.parser.JSONParser;

/**
 * A new process starts with only the standard services, not the previous process's catalogue. Its first service of
 * its own asks for its domain's catalogue: an existing one, or a new one created empty and listed for packaging.
 * A process and its catalogue belong to one domain; Validate says when they do not.
 */
public final class CatalogueChoiceCheck {
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]).getCanonicalFile();
        Path project = Files.createTempDirectory("catalogue-choice-");
        Path common = project.resolve("btsn.common");
        for (String file : Arrays.asList("BusinessServiceDefinitions/credit/Credit.json", ServiceRegistry.STANDARD_CATALOGUE,
                "ProcessDefinitionFolder/credit/Workflow/Credit_Fraud_Rest_Workflow.json", "ServiceDeploymentFolder/credit/Credit_Fraud_Rest_Workflow.json")) {
            Path copy = common.resolve(file);
            Files.createDirectories(copy.getParent());
            Files.copy(root.toPath().resolve("btsn.common").resolve(file), copy);
        }
        Path packaging = project.resolve("btsn.services/packaging.json");
        Files.createDirectories(packaging.getParent());
        Files.copy(root.toPath().resolve("btsn.services/packaging.json"), packaging);
        String packagingBefore = Files.readString(packaging);

        // A process of the credit domain, then File > New: the new process does not inherit Credit.json.
        Path process = common.resolve("ProcessDefinitionFolder/credit/Workflow/Credit_Fraud_Rest_Workflow.json");
        Canvas canvas = new Canvas(); canvas.setDefinitionLocation(process.toFile()); canvas.loadFromJSON(Files.readString(process));
        check(canvas.getServiceRegistry().hasDomainCatalogue() && canvas.getServiceRegistry().services().contains("CreditLimitService"), "Credit process not on its catalogue");
        canvas.clear(); canvas.setDefinitionLocation(null);
        ServiceRegistry registry = canvas.getServiceRegistry();
        check(registry.problem() == null && !registry.hasDomainCatalogue() && !registry.services().contains("CreditLimitService")
            && registry.services().contains("StochasticService") && registry.services().contains("AlwaysTrueService"),
            "New process should offer only the standard services: " + registry.services());
        check(ServiceDefinitionDialog.refusal(registry) != null, "A service could be added to the standard catalogue");

        // The choice: existing domains are listed (not the standard catalogue); a new domain gets an empty catalogue.
        List<File> domains = ServiceRegistry.domainCatalogues(common.toFile());
        check(domains.size() == 1 && domains.get(0).getName().equals("Credit.json"), "Domain catalogues: " + domains);
        check(CatalogueChoice.resolve(common.toFile(), domains.get(0), null).equals(domains.get(0)), "Existing catalogue not chosen");
        for (String bad : Arrays.asList("Claims", "1claims", "class", "home-loans", ""))
            try { CatalogueChoice.resolve(common.toFile(), null, bad); throw new AssertionError("Accepted domain name '" + bad + "'"); }
            catch (java.io.IOException expected) { }
        File claims = CatalogueChoice.resolve(common.toFile(), null, "claims");
        check(claims.equals(common.resolve("BusinessServiceDefinitions/claims/Claims.json").toFile()), "Catalogue made at " + claims);
        check(CatalogueWriter.catalogueName("home_loans").equals("HomeLoans.json"), "Catalogue name from domain");
        try { CatalogueChoice.resolve(common.toFile(), null, "claims"); throw new AssertionError("A second claims catalogue was made"); }
        catch (java.io.IOException expected) { check(expected.getMessage().contains("already has a catalogue, Claims.json"), expected.getMessage()); }

        // Listed for packaging in both places, the rest of packaging.json unchanged.
        String packagingAfter = Files.readString(packaging);
        JSONObject listed = (JSONObject)new JSONParser().parse(packagingAfter);
        check(((JSONArray)listed.get("catalogues")).contains("../btsn.common/BusinessServiceDefinitions/claims/Claims.json")
            && "claims".equals(((JSONObject)listed.get("catalogueDomains")).get("Claims.json")), "Not listed for packaging: " + packagingAfter);
        check(packagingAfter.contains("\"../btsn.common/BusinessServiceDefinitions/credit/Credit.json\",\n    \"../btsn.common/BusinessServiceDefinitions/claims/Claims.json\"")
            && packagingAfter.contains("\"Credit.json\": \"credit\",\n    \"Claims.json\": \"claims\""), "Packaging layout not kept:\n" + packagingAfter);
        check(packagingAfter.replace(",\n    \"../btsn.common/BusinessServiceDefinitions/claims/Claims.json\"", "").replace(",\n    \"Claims.json\": \"claims\"", "").equals(packagingBefore),
            "Other packaging entries changed");
        check(!CatalogueWriter.registerForPackaging(common.toFile(), claims, "claims"), "Listed twice");

        // The empty catalogue is the process's: standard services beside it; its first service is added there.
        canvas.useCatalogue(claims);
        check(registry.hasDomainCatalogue() && "claims".equals(registry.catalogueDomain()) && registry.services().contains("StochasticService")
            && ServiceDefinitionDialog.refusal(registry) == null, "Empty claims catalogue not usable: " + registry.problem());
        CatalogueWriter.Entry triage = new CatalogueWriter.Entry();
        triage.service = "TriageService"; triage.operation = "triage"; triage.inputs.add("token"); triage.routes.addAll(Arrays.asList("simple", "complex"));
        CatalogueWriter.add(claims, triage);
        registry.reload();
        check(registry.services().contains("TriageService") && registry.isDraft("TriageService"), "First service not added to the new catalogue");
        check(ServiceRegistry.domainCatalogues(common.toFile()).size() == 2, "New domain not offered next time");
        // Every domain's services are packaged together, so a name belongs to one catalogue.
        check(ServiceRegistry.definedElsewhere(common.toFile(), "CreditLimitService", claims).getName().equals("Credit.json")
            && ServiceRegistry.definedElsewhere(common.toFile(), "CreditLimitService", domains.get(0)) == null
            && ServiceRegistry.definedElsewhere(common.toFile(), "TriageService", claims) == null, "Names used by other catalogues not found");
        check(ServiceDefinitionDialog.domainName("home_loans").equals("HomeLoans"), "Domain prefix");

        // Process and catalogue in one domain: saved under claims, nothing to say; under credit, Validate says so.
        File claimsProcess = common.resolve("ProcessDefinitionFolder/claims/Workflow/Claims_Workflow.json").toFile();
        canvas.setDefinitionLocation(claimsProcess);
        check(canvas.domainMismatch() == null && "claims".equals(CatalogueChoice.processDomain(common.toFile(), claimsProcess)), "Matching domain reported");
        canvas.setDefinitionLocation(process.toFile());
        check(canvas.domainMismatch() != null && canvas.domainMismatch().startsWith("This process is saved under ProcessDefinitionFolder/credit but uses the claims catalogue (Claims.json)")
            && canvas.serviceNotes().contains(canvas.domainMismatch()) && !canvas.serviceBlockers().contains(canvas.domainMismatch()), "Mismatch not a Validate reminder: " + canvas.domainMismatch());
        System.out.println("PASS: new process offers only standard services; first own service asks for the domain catalogue; new domain catalogue created empty and listed for packaging with its layout kept; service names unique across catalogues; process and catalogue domains checked at Validate");
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
