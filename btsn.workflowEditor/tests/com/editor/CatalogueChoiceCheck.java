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
        // Saved under a domain's folder, a process with only the standard services takes that domain's catalogue.
        canvas.setDefinitionLocation(common.resolve("ProcessDefinitionFolder/credit/Workflow/New_Workflow.json").toFile());
        check(canvas.adoptDomainCatalogue() && registry.hasDomainCatalogue() && "credit".equals(registry.catalogueDomain())
            && registry.services().contains("CreditLimitService") && !canvas.adoptDomainCatalogue(), "Saved under credit, the process does not use Credit.json");
        canvas.clear(); canvas.setDefinitionLocation(null);
        check(!registry.hasDomainCatalogue(), "Clearing kept the credit catalogue");

        // The choice: existing domains are listed (not the standard catalogue); a new domain gets an empty catalogue.
        List<File> domains = ServiceRegistry.domainCatalogues(common.toFile());
        check(domains.size() == 1 && domains.get(0).getName().equals("Credit.json"), "Domain catalogues: " + domains);
        check(CatalogueChoice.resolve(common.toFile(), domains.get(0), null).equals(domains.get(0)), "Existing catalogue not chosen");
        for (String bad : Arrays.asList("Travel", "1travel", "class", "home-loans", ""))
            try { CatalogueChoice.resolve(common.toFile(), null, bad); throw new AssertionError("Accepted domain name '" + bad + "'"); }
            catch (java.io.IOException expected) { }
        File travel = CatalogueChoice.resolve(common.toFile(), null, "travel");
        check(travel.equals(common.resolve("BusinessServiceDefinitions/travel/Travel.json").toFile()), "Catalogue made at " + travel);
        check(CatalogueWriter.catalogueName("home_loans").equals("HomeLoans.json"), "Catalogue name from domain");
        try { CatalogueChoice.resolve(common.toFile(), null, "travel"); throw new AssertionError("A second travel catalogue was made"); }
        catch (java.io.IOException expected) { check(expected.getMessage().contains("already has a catalogue, Travel.json"), expected.getMessage()); }

        // Listed for packaging in both places, the rest of packaging.json unchanged.
        String packagingAfter = Files.readString(packaging);
        JSONObject listed = (JSONObject)new JSONParser().parse(packagingAfter);
        check(((JSONArray)listed.get("catalogues")).contains("../btsn.common/BusinessServiceDefinitions/travel/Travel.json")
            && "travel".equals(((JSONObject)listed.get("catalogueDomains")).get("Travel.json")), "Not listed for packaging: " + packagingAfter);
        // Appended after the last entry of each list, indented like it.
        check(packagingAfter.matches("(?s).*\"[^\"]*\",\n    \"../btsn.common/BusinessServiceDefinitions/travel/Travel.json\"\n  \\].*")
            && packagingAfter.matches("(?s).*\": \"[a-z]*\",\n    \"Travel.json\": \"travel\"\n  \\}.*"), "Packaging layout not kept:\n" + packagingAfter);
        check(packagingAfter.replace(",\n    \"../btsn.common/BusinessServiceDefinitions/travel/Travel.json\"", "").replace(",\n    \"Travel.json\": \"travel\"", "").equals(packagingBefore),
            "Other packaging entries changed");
        check(!CatalogueWriter.registerForPackaging(common.toFile(), travel, "travel"), "Listed twice");

        // The empty catalogue is the process's: standard services beside it; its first service is added there.
        canvas.useCatalogue(travel);
        check(registry.hasDomainCatalogue() && "travel".equals(registry.catalogueDomain()) && registry.services().contains("StochasticService")
            && ServiceDefinitionDialog.refusal(registry) == null, "Empty travel catalogue not usable: " + registry.problem());
        CatalogueWriter.Entry triage = new CatalogueWriter.Entry();
        triage.service = "TriageService"; triage.operation = "triage"; triage.inputs.add("token"); triage.routes.addAll(Arrays.asList("simple", "complex"));
        CatalogueWriter.add(travel, triage);
        registry.reload();
        check(registry.services().contains("TriageService") && registry.isDraft("TriageService"), "First service not added to the new catalogue");
        check(ServiceRegistry.domainCatalogues(common.toFile()).size() == 2, "New domain not offered next time");
        // Every domain's services are packaged together, so a name belongs to one catalogue.
        check(ServiceRegistry.definedElsewhere(common.toFile(), "CreditLimitService", travel).getName().equals("Credit.json")
            && ServiceRegistry.definedElsewhere(common.toFile(), "CreditLimitService", domains.get(0)) == null
            && ServiceRegistry.definedElsewhere(common.toFile(), "TriageService", travel) == null, "Names used by other catalogues not found");
        check(ServiceDefinitionDialog.domainName("home_loans").equals("HomeLoans"), "Domain prefix");

        // A listed catalogue deleted later is found, and removing it restores packaging.json as it was.
        // (This copy of the project has only the credit and travel catalogues; the others are missing from the start.)
        List<String> notCopied = CatalogueWriter.missingFromPackaging(common.toFile());
        check(!notCopied.isEmpty() && !notCopied.contains("../btsn.common/BusinessServiceDefinitions/travel/Travel.json")
            && !notCopied.contains("../btsn.common/BusinessServiceDefinitions/credit/Credit.json"), "Catalogues reported missing: " + notCopied);
        Path saved = Files.move(travel.toPath(), project.resolve("Travel.json.saved"));
        List<String> expected = new ArrayList<>(notCopied); expected.add("../btsn.common/BusinessServiceDefinitions/travel/Travel.json");
        check(CatalogueWriter.missingFromPackaging(common.toFile()).equals(expected),
            "Deleted catalogue not found: " + CatalogueWriter.missingFromPackaging(common.toFile()));
        check(CatalogueWriter.unregisterFromPackaging(common.toFile(), "../btsn.common/BusinessServiceDefinitions/travel/Travel.json")
            && Files.readString(packaging).equals(packagingBefore) && CatalogueWriter.missingFromPackaging(common.toFile()).equals(notCopied),
            "Removing the deleted catalogue did not restore packaging.json:\n" + Files.readString(packaging));
        String crlf = packagingAfter.replace("\n", "\r\n");   // as git checks it out on Windows
        Files.writeString(packaging, crlf);
        check(CatalogueWriter.unregisterFromPackaging(common.toFile(), "../btsn.common/BusinessServiceDefinitions/travel/Travel.json")
            && Files.readString(packaging).equals(packagingBefore.replace("\n", "\r\n")), "CRLF packaging.json not restored:\n" + Files.readString(packaging));
        Files.move(saved, travel.toPath());
        CatalogueWriter.registerForPackaging(common.toFile(), travel, "travel");

        // Process and catalogue in one domain: saved under travel, nothing to say; under credit, Validate says so.
        File travelProcess = common.resolve("ProcessDefinitionFolder/travel/Workflow/Travel_Workflow.json").toFile();
        canvas.setDefinitionLocation(travelProcess);
        check(canvas.domainMismatch() == null && "travel".equals(CatalogueChoice.processDomain(common.toFile(), travelProcess)), "Matching domain reported");
        canvas.setDefinitionLocation(process.toFile());
        check(canvas.domainMismatch() != null && canvas.domainMismatch().startsWith("This process is saved under ProcessDefinitionFolder/credit but uses the travel catalogue (Travel.json)")
            && canvas.serviceNotes().contains(canvas.domainMismatch()) && !canvas.serviceBlockers().contains(canvas.domainMismatch()), "Mismatch not a Validate reminder: " + canvas.domainMismatch());
        System.out.println("PASS: new process offers only standard services; first own service asks for the domain catalogue; new domain catalogue created empty and listed for packaging with its layout kept; service names unique across catalogues; a listed catalogue deleted later found and removed from packaging (LF and CRLF); process and catalogue domains checked at Validate; a new process saved under a domain takes its catalogue");
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
