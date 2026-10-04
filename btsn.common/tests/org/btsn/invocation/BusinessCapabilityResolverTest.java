package org.btsn.invocation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import org.apache.log4j.Logger;
import org.apache.log4j.Level;
import org.btsn.handlers.ServiceHelper;
import org.btsn.places.P1_Place;
import org.btsn.places.P2_Place;
import org.btsn.places.P3_Place;
import org.btsn.places.P4_Place;
import org.btsn.places.P5_Place;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

/** Standalone checks using repository metadata and isolated temporary deployment fixtures. */
public final class BusinessCapabilityResolverTest {
    private static int checks;
    private static Path common;
    private static final String CATALOG = "BusinessServiceDefinitions/FinancialSystem.json";
    private static final String INFRASTRUCTURE = "ProcessDefinitionFolder/FinancialSystem_Infrastructure.json";
    private static final String RULES = "RuleBase/Generated/InfrastructureDeployment.ruleml.xml";
    private static final String[] SERVICES = {"ValidationService", "CreditCheckService", "FraudCheckService",
            "UnderwritingService", "DecisionService"};
    private static final String[] RETURNS = {"validationResults", "creditCheckResults", "fraudCheckResults",
            "underwritingResults", "decisionResults"};

    public static void main(String[] args) throws Exception {
        Logger.getRootLogger().setLevel(Level.OFF);
        common = Paths.get(args[0]).toAbsolutePath();
        System.setProperty("btsn.common.dir", common.toString());
        // The runner supplies an isolated working directory for versioned rulebases.
        Path runtime = Paths.get("").toAbsolutePath();
        BusinessCapabilityResolver resolver = new BusinessCapabilityResolver(common, runtime);
        for (int i = 0; i < SERVICES.length; i++) {
            String physical = "org.btsn.places.P" + (i + 1) + "_Place";
            String expected = "org.btsn.business.financial." + SERVICES[i];
            for (String version : Arrays.asList("v001", "v002")) {
                Path installed = runtime.resolve("RuleFolder." + version + "/processToken/Service.ruleml");
                Files.createDirectories(installed.getParent());
                String contract = Files.readString(common.resolve("ServiceAttributeBindings/" + SERVICES[i] + "/"
                        + SERVICES[i] + "-CanonicalBindings.ruleml.xml"));
                Files.writeString(installed, "<Assert><Rulebase>" + contract + "</Rulebase></Assert>");
                equal(expected, resolver.resolve(physical, "processToken", RETURNS[i], i == 3 ? 2 : 1, version));
                String[] inputs = i == 3 ? new String[] {"{}", "{}"} : new String[] {"{}"};
                String output = new ServiceHelper().process("1000000", physical, "processToken",
                        new ArrayList<String>(Arrays.asList(inputs)), RETURNS[i], version).getResult();
                equal(true, parse(output).containsKey(RETURNS[i]));
            }
            equal(expected, resolver.resolve(SERVICES[i], "processToken", RETURNS[i], i == 3 ? 2 : 1, null));
        }
        rejects(() -> resolver.resolve("org.btsn.places.P1_Place", "processToken", RETURNS[0], 1, "v001"),
                "does not select logical service"); // Installed fixture currently selects DecisionService.
        Path installed = runtime.resolve("RuleFolder.v001/processToken/Service.ruleml");
        String original = Files.readString(installed);
        Files.writeString(installed, original.replace("underwritingResults", "wrongInput"));
        rejects(() -> resolver.resolve("P5_Place", "processToken", RETURNS[4], 1, "v001"), "Installed input contract conflicts");
        Files.writeString(installed, original.replace("decisionResults", "wrongOutput"));
        rejects(() -> resolver.resolve("P5_Place", "processToken", RETURNS[4], 1, "v001"), "Installed return contract conflicts");
        Files.writeString(installed, original.replace("</Rulebase>",
                "<Atom><Rel>localDefined</Rel><Ind>ValidationService</Ind></Atom></Rulebase>"));
        rejects(() -> resolver.resolve("P5_Place", "processToken", RETURNS[4], 1, "v001"), "ambiguous logical service identities");
        Files.writeString(installed, original);
        rejects(() -> resolver.resolve(SERVICES[0], "processToken", "wrongReturn", 1, null), "return contract mismatch");
        rejects(() -> resolver.resolve(SERVICES[3], "processToken", RETURNS[3], 1, null), "argument count mismatch");
        rejects(() -> resolver.resolve("MissingService", "processToken", RETURNS[0], 1, null), "No configured capability");
        rejects(() -> resolver.resolve("P6_Place", "processToken", "token", 1, null), "No configured capability");
        rejects(() -> resolver.resolve("IdentityVerificationService", "verifyApplicantIdentity", "identityVerificationResults", 1, null),
                "inactive/unbound");
        for (int i = 1; i <= 6; i++) {
            String initializer = "org.btsn.places.P" + i + "_InitializationService";
            String collector = "org.btsn.places.P" + i + "_CollectorService";
            equal(initializer, resolver.resolve(initializer, "purgeAndInitialize", "token", 1, "v999"));
            equal(collector, resolver.resolve(collector, "collectAllData", "token", 1, "v999"));
        }
        verifyBusinessResults();
        verifyMetadataFailures();
        verifyConfiguredImplementation();
        System.out.println("PASS: " + checks + " capability/invocation checks");
    }

    private static void verifyBusinessResults() throws Exception {
        String token = "{\"application_id\":\"TEST-LOAN\",\"annual_income\":85000,\"requested_amount\":25000,"
                + "\"credit_score\":720,\"fraud_risk\":\"low\"}";
        String validation = invoke("P1_Place", RETURNS[0], token);
        sameBusiness(new P1_Place("test").processToken(token), validation, RETURNS[0]);
        String credit = invoke("P2_Place", RETURNS[1], validation);
        sameBusiness(new P2_Place("test").processToken(validation), credit, RETURNS[1]);
        String fraud = invoke("P3_Place", RETURNS[2], validation);
        sameBusiness(new P3_Place("test").processToken(validation), fraud, RETURNS[2]);
        String underwriting = invoke("P4_Place", RETURNS[3], credit, fraud);
        sameBusiness(new P4_Place("test").processToken(credit, fraud), underwriting, RETURNS[3]);
        String decision = invoke("P5_Place", RETURNS[4], underwriting);
        sameBusiness(new P5_Place("test").processToken(underwriting), decision, RETURNS[4]);
        JSONObject result = (JSONObject) parse(decision).get(RETURNS[4]);
        equal(true, result.containsKey("original_token"));
        equal(true, result.containsKey("workflow_start_time"));
        equal(true, result.containsKey("service_start_time"));
        equal(true, result.containsKey("service_end_time"));
    }

    private static void verifyMetadataFailures() throws Exception {
        Path fixture = fixture();
        JSONObject infrastructure = read(fixture.resolve(INFRASTRUCTURE));
        JSONArray capabilities = (JSONArray) infrastructure.get("capabilities");
        capabilities.add(capabilities.get(0));
        write(fixture.resolve(INFRASTRUCTURE), infrastructure);
        rejects(() -> new BusinessCapabilityResolver(fixture), "Duplicate deployment capability");
        capabilities.remove(capabilities.size() - 1);
        capabilities.remove(0);
        write(fixture.resolve(INFRASTRUCTURE), infrastructure);
        rejects(() -> new BusinessCapabilityResolver(fixture), "not deployed");
        Path conflict = fixture();
        JSONObject catalog = read(conflict.resolve(CATALOG));
        JSONObject validation = (JSONObject) ((JSONArray) catalog.get("services")).get(0);
        validation.put("returnAttribute", "contradiction");
        write(conflict.resolve(CATALOG), catalog);
        rejects(() -> new BusinessCapabilityResolver(conflict), "Conflicting return attribute");
        Path duplicate = fixture();
        catalog = read(duplicate.resolve(CATALOG));
        JSONArray services = (JSONArray) catalog.get("services");
        services.add(services.get(0));
        write(duplicate.resolve(CATALOG), catalog);
        rejects(() -> new BusinessCapabilityResolver(duplicate), "Duplicate catalogue capability");
        Path endpoint = fixture();
        Files.writeString(endpoint.resolve(RULES), Files.readString(endpoint.resolve(RULES))
                + "<Atom><Rel>activeService</Rel><Ind>AnotherHost</Ind><Ind>processToken</Ind><Ind>ip0</Ind><Ind>4001</Ind></Atom>");
        rejects(() -> new BusinessCapabilityResolver(endpoint), "Ambiguous runtime endpoint");
        Path missing = fixture();
        Files.delete(missing.resolve(CATALOG));
        rejects(() -> new BusinessCapabilityResolver(missing), "FinancialSystem.json");
    }

    private static void verifyConfiguredImplementation() throws Exception {
        Path alternate = fixture();
        JSONObject catalog = read(alternate.resolve(CATALOG));
        ((JSONObject) ((JSONArray) catalog.get("services")).get(0))
                .put("implementationClass", AlternateService.class.getName());
        write(alternate.resolve(CATALOG), catalog);
        // Arbitrary runtime names prove that the lookup does not infer P-class names.
        Files.writeString(alternate.resolve(RULES), Files.readString(alternate.resolve(RULES)).replace("P1_Place", "LoanDesk"));
        System.setProperty("btsn.common.dir", alternate.toString());
        JSONObject response = parse(invoke("LoanDesk", RETURNS[0], "{}"));
        equal("from-config", ((JSONObject) response.get(RETURNS[0])).get("implementation"));
        ((JSONObject) ((JSONArray) catalog.get("services")).get(0)).put("implementationClass", "missing.Implementation");
        write(alternate.resolve(CATALOG), catalog);
        rejects(() -> invoke("LoanDesk", RETURNS[0], "{}"), "missing.Implementation");
        System.setProperty("btsn.common.dir", common.toString());
    }

    public static final class AlternateService {
        public String processToken(String token) {
            return "{\"validationResults\":{\"implementation\":\"from-config\"}}";
        }
    }

    private static String invoke(String service, String output, String... inputs) {
        return new ServiceHelper().process("1000000", "org.btsn.places." + service, "processToken",
                new ArrayList<String>(Arrays.asList(inputs)), output).getResult();
    }

    private static void sameBusiness(String expected, String actual, String output) throws Exception {
        JSONObject result = (JSONObject) parse(actual).get(output);
        for (String field : Arrays.asList("original_token", "workflow_start_time", "service_start_time",
                "service_end_time", "service_processing_time_ms")) result.remove(field);
        equal(parse(expected).get(output), result);
    }

    private static Path fixture() throws Exception {
        Path directory = Files.createTempDirectory("resolver-metadata-");
        JSONObject deployment = read(common.resolve("BusinessServiceDefinitions/Deployment.json"));
        ArrayList<String> files = new ArrayList<>(Arrays.asList("BusinessServiceDefinitions/Deployment.json", CATALOG, INFRASTRUCTURE, RULES));
        for (Object file : (JSONArray) deployment.get("directServiceRules")) files.add(file.toString());
        for (String file : files) {
            Files.createDirectories(directory.resolve(file).getParent());
            Files.copy(common.resolve(file), directory.resolve(file));
        }
        return directory;
    }

    private static JSONObject read(Path path) throws Exception { return parse(Files.readString(path)); }
    private static JSONObject parse(String text) throws Exception { return (JSONObject) new JSONParser().parse(text); }
    private static void write(Path path, JSONObject json) throws Exception { Files.writeString(path, json.toJSONString()); }
    private static void equal(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
        checks++;
    }
    private interface Checked { void run() throws Exception; }
    private static void rejects(Checked action, String message) throws Exception {
        try { action.run(); } catch (Exception failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause.toString().contains(message)) { checks++; return; }
            }
            throw new AssertionError("Wrong failure, expected " + message, failure);
        }
        throw new AssertionError("Expected failure: " + message);
    }
}
