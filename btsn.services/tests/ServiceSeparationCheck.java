import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.btsn.deployment.DeploymentConfiguration;
import org.btsn.deployment.GenerateDeploymentRules;
import org.btsn.invocation.BusinessCapabilityResolver;
import org.btsn.invocation.BooleanTokenAdapter;
import org.btsn.rulecontroller.RuleDeployer;
import org.btsn.rulecontroller.TopologyBindingGenerator;
import org.btsn.rulecontroller.model.WorkflowModel;
import org.btsn.services.StochasticService;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

/** Checks reusable implementations, endpoint contracts and preservation of the full node-type vocabulary. */
public final class ServiceSeparationCheck {
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Logger.getRootLogger().setLevel(Level.OFF);
        Path root = Path.of(args[0]).toAbsolutePath(), common = root.resolve("btsn.common");
        // Every active Petri-net profile must use the one reusable type, including older model profiles.
        int petriProfiles = 0;
        try (java.util.stream.Stream<Path> profiles = Files.list(common.resolve("ServiceDeploymentFolder/petrinet"))) {
            for (Path path : (Iterable<Path>)profiles.filter(p -> p.toString().endsWith(".json"))::iterator) {
                JSONObject deployment = DeploymentConfiguration.read(path);
                for (Object item : (JSONArray)deployment.get("capabilities")) {
                    JSONObject capability = (JSONObject)item;
                    check("StochasticService".equals(capability.get("service")), "Old service type in " + path);
                    check(capability.get("instance") != null, "Missing instance in " + path);
                    check("boolean-token".equals(capability.get("invocationAdapter")), "Missing adapter in " + path);
                }
                petriProfiles++;
            }
        }
        check(petriProfiles == 6, "Petri-net profile coverage changed");
        // Validate each migrated process against its explicitly selected profile before deployment.
        String[] modelNames = {"TrafficLight_Workflow", "P1_P2_Deterministic_Workflow", "P1_to_P6_Double_Join_Workflow", "P1_Tutorial_Workflow", "P1_P2_Workflow", "P1_P2_ForkCompanion_Workflow", "P1_P2_P3_P4_Fork_Join_Workflow"};
        for (String modelName : modelNames) {
            JSONObject model = DeploymentConfiguration.read(common.resolve("ProcessDefinitionFolder/petrinet/Workflow/" + modelName + ".json"));
            JSONObject selectedProfile = null;
            try (java.util.stream.Stream<Path> candidates = Files.walk(root.resolve("btsn.services/deployments/models"))) {
                for (Path candidate : (Iterable<Path>)candidates.filter(p -> p.toString().endsWith(".json"))::iterator) {
                    JSONObject data = DeploymentConfiguration.read(candidate);
                    if (model.get("serviceDeployment").equals(data.get("serviceDeployment"))) { selectedProfile = data; break; }
                }
            }
            check(selectedProfile != null, "Process has no deployment profile: " + modelName);
            Path selectedFixture = Files.createTempDirectory("process-contract-");
            for (String field : Arrays.asList("infrastructure", "serviceDeployment", "catalog")) {
                Path relative = Path.of(selectedProfile.get(field).toString());
                Files.createDirectories(selectedFixture.resolve(relative).getParent());
                Files.copy(common.resolve(relative), selectedFixture.resolve(relative));
            }
            Files.createDirectories(selectedFixture.resolve("BusinessServiceDefinitions"));
            Files.writeString(selectedFixture.resolve("BusinessServiceDefinitions/Deployment.json"), selectedProfile.toJSONString());
            new DeploymentConfiguration(selectedFixture).validateWorkflow(model);
        }
        JSONObject profile = DeploymentConfiguration.read(root.resolve("btsn.services/deployments/models/TrafficLightDeployment.json"));
        Path fixture = Files.createTempDirectory("service-instances-");
        for (String field : Arrays.asList("infrastructure", "serviceDeployment", "catalog")) {
            String name = profile.get(field).toString();
            Files.createDirectories(fixture.resolve(name).getParent());
            Files.copy(common.resolve(name), fixture.resolve(name));
        }
        for (Object name : (JSONArray)profile.get("directServiceRules")) {
            Files.createDirectories(fixture.resolve(name.toString()).getParent());
            Files.copy(common.resolve(name.toString()), fixture.resolve(name.toString()));
        }
        Files.createDirectories(fixture.resolve("BusinessServiceDefinitions"));
        Files.writeString(fixture.resolve("BusinessServiceDefinitions/Deployment.json"), profile.toJSONString());
        GenerateDeploymentRules.main(new String[]{fixture.toString()});
        DeploymentConfiguration configuration = new DeploymentConfiguration(fixture);
        JSONObject declaredWorkflow = DeploymentConfiguration.read(common.resolve("ProcessDefinitionFolder/petrinet/Workflow/TrafficLight_Workflow.json"));
        configuration.validateWorkflow(declaredWorkflow);
        JSONObject firstPlace = null;
        for (Object item : (JSONArray)declaredWorkflow.get("elements")) if ("PLACE".equals(((JSONObject)item).get("type"))) { firstPlace = (JSONObject)item; break; }
        String selectedService = firstPlace.get("service").toString();
        firstPlace.put("service", "StochasticEntryJoinService");
        try { configuration.validateWorkflow(declaredWorkflow); throw new AssertionError("Unknown process service accepted"); }
        catch (IllegalArgumentException expected) { check(expected.getMessage().contains("Unresolved process instance"), expected.getMessage()); }
        firstPlace.put("service", selectedService);
        JSONObject firstOperation = (JSONObject)((JSONArray)firstPlace.get("operations")).get(0);
        JSONObject firstInput = (JSONObject)((JSONArray)firstOperation.get("arguments")).get(0);
        String selectedInput = firstInput.get("name").toString();
        firstInput.put("name", "incorrect");
        try { configuration.validateWorkflow(declaredWorkflow); throw new AssertionError("Mismatched process inputs accepted"); }
        catch (IllegalArgumentException expected) { check(expected.getMessage().contains("Process inputs differ"), expected.getMessage()); }
        firstInput.put("name", selectedInput);
        firstOperation.put("returnAttribute", "incorrect");
        try { configuration.validateWorkflow(declaredWorkflow); throw new AssertionError("Mismatched process result accepted"); }
        catch (IllegalArgumentException expected) { check(expected.getMessage().contains("Process result differs"), expected.getMessage()); }
        firstOperation.put("returnAttribute", "token");
        configuration.validateWorkflow(declaredWorkflow);
        Path installed = fixture.resolve("RuleFolder.v001/processToken/Service.ruleml");
        Files.createDirectories(installed.getParent());
        BusinessCapabilityResolver resolver = new BusinessCapabilityResolver(fixture, fixture);
        int count = 0;
        for (Object item : (JSONArray)configuration.serviceDeployment.get("capabilities")) {
            JSONObject endpoint = (JSONObject)item;
            String instance = endpoint.get("instance").toString(), output = endpoint.get("returnAttribute").toString();
            JSONArray inputs = (JSONArray)endpoint.get("arguments");
            StringBuilder xml = new StringBuilder("<Atom><Rel>localDefined</Rel><Ind>" + instance + "</Ind></Atom>");
            List<String> values = new ArrayList<>();
            for (Object input : inputs) {
                String name = ((JSONObject)input).get("name").toString();
                xml.append("<Atom><Rel>canonicalBinding</Rel><Ind>processToken</Ind><Ind>").append(output)
                   .append("</Ind><Ind>").append(name).append("</Ind></Atom>");
                values.add("{\"businessValue\":\"" + name + "\"}");
            }
            Files.writeString(installed, xml.toString());
            for (String identity : Arrays.asList(instance, endpoint.get("node") + "_Place")) {
                check(StochasticService.class.getName().equals(resolver.resolve(identity, "processToken", output, inputs.size(), "v001")), "Wrong reusable implementation");
                String response = resolver.adaptedResult(identity, "processToken", values, output, "v001");
                JSONObject payload = (JSONObject)((JSONObject)new org.json.simple.parser.JSONParser().parse(response)).get(output);
                check(payload.get("decision") instanceof Boolean, "Decision is not Boolean");
                check(((JSONArray)payload.get("data")).size() == values.size(), "Input data was lost");
            }
            Files.writeString(installed, xml.toString().replace(instance, "OtherInstance"));
            try { resolver.resolve(endpoint.get("node") + "_Place", "processToken", output, inputs.size(), "v001"); throw new AssertionError("Wrong instance accepted"); }
            catch (IllegalStateException expected) { check(expected.getMessage().contains("does not select"), expected.getMessage()); }
            count++;
        }
        check(count == 6, "All six repeated placements were not exercised");
        // Repeat an ordinary JSON-returning service as well: Boolean adaptation is never implicit.
        Path ordinary = Files.createTempDirectory("ordinary-service-instances-");
        JSONObject ordinaryProfile = DeploymentConfiguration.read(common.resolve("BusinessServiceDefinitions/Deployment.json"));
        List<String> ordinaryFiles = new ArrayList<>();
        for (String field : Arrays.asList("infrastructure", "serviceDeployment", "catalog")) ordinaryFiles.add(ordinaryProfile.get(field).toString());
        for (Object name : (JSONArray)ordinaryProfile.get("directServiceRules")) ordinaryFiles.add(name.toString());
        for (String name : ordinaryFiles) { Files.createDirectories(ordinary.resolve(name).getParent()); Files.copy(common.resolve(name), ordinary.resolve(name)); }
        Files.createDirectories(ordinary.resolve("BusinessServiceDefinitions"));
        Files.writeString(ordinary.resolve("BusinessServiceDefinitions/Deployment.json"), ordinaryProfile.toJSONString());
        JSONObject placements = DeploymentConfiguration.read(ordinary.resolve(ordinaryProfile.get("serviceDeployment").toString()));
        JSONArray capabilities = (JSONArray)placements.get("capabilities");
        JSONObject original = (JSONObject)capabilities.get(0);
        original.put("instance", "FirstValidation");
        JSONObject repeated = new JSONObject(original);
        repeated.put("node", "P6"); repeated.put("instance", "SecondValidation");
        capabilities.add(repeated);
        Files.writeString(ordinary.resolve(ordinaryProfile.get("serviceDeployment").toString()), placements.toJSONString());
        GenerateDeploymentRules.main(new String[]{ordinary.toString()});
        BusinessCapabilityResolver ordinaryResolver = new BusinessCapabilityResolver(ordinary);
        for (String identity : Arrays.asList("FirstValidation", "SecondValidation", "P1_Place", "P6_Place")) {
            check("org.btsn.business.financial.ValidationService".equals(ordinaryResolver.resolve(identity, "processToken", "validationResults", 1, null)), "Non-Boolean reusable service changed");
            check(ordinaryResolver.adaptedResult(identity, "processToken", Arrays.asList("{}"), "validationResults", null) == null, "Non-Boolean service was adapted implicitly");
        }
        String carried = "{\"businessValue\":42}";
        for (int cycle = 0; cycle < 40; cycle++) {
            carried = BooleanTokenAdapter.invoke(StochasticService.class.getName(), "processToken", Arrays.asList(carried, carried), "token");
            check(carried.length() < 512, "Repeated fork/join invocations grew the transport payload");
        }
        JSONArray data = new JSONArray(); data.add("unrelated business data");
        for (boolean outcome : new boolean[]{true, false}) {
            StochasticService service = new StochasticService(new Random() { @Override public boolean nextBoolean() { return outcome; } });
            check(service.processToken("any input") == outcome, "Random service result altered");
            JSONObject payload = (JSONObject)((JSONObject)new org.json.simple.parser.JSONParser().parse(BooleanTokenAdapter.response(outcome, data, "result"))).get("result");
            check(Boolean.toString(outcome).equals(((JSONObject)payload.get("routing_decision")).get("routing_path")), "Adapter changed result");
        }
        // Parser preservation is deliberately separate from execution validity: not every combination is a valid process.
        String[] types = {"DecisionNode", "TerminateNode", "JoinNode", "XorNode", "MergeNode", "EdgeNode", "MonitorNode", "FeedFwdNode", "GatewayNode", "ForkNode"};
        int combinations = 0;
        List<Object> compilers = Arrays.asList(new RuleDeployer("fixture", "v001"), new TopologyBindingGenerator("fixture", "v001"));
        for (String incoming : types) for (String outgoing : types) {
            String json = "{\"processType\":\"PetriNet\",\"elements\":["
                + "{\"id\":\"in\",\"type\":\"TRANSITION\",\"node_type\":\"" + incoming + "\",\"transition_type\":\"T_in\"},"
                + "{\"id\":\"service\",\"type\":\"PLACE\",\"service\":\"StochasticService\",\"serviceInstance\":\"SampleInstance\",\"operations\":[{\"name\":\"processToken\",\"returnAttribute\":\"token\",\"arguments\":[{\"name\":\"token\"}]}]},"
                + "{\"id\":\"out\",\"type\":\"TRANSITION\",\"node_type\":\"" + outgoing + "\",\"transition_type\":\"T_out\"}],\"arrows\":[]}";
            for (Object compiler : compilers) {
                Method parse = compiler.getClass().getDeclaredMethod("parseJsonWorkflow", String.class); parse.setAccessible(true); parse.invoke(compiler, json.replace(":[", ": ["));
                Field model = compiler.getClass().getDeclaredField("workflowModel"); model.setAccessible(true);
                WorkflowModel workflow = (WorkflowModel)model.get(compiler);
                check(incoming.equals(workflow.getTransitionNodes().get("in").nodeType), "Incoming node changed");
                check(outgoing.equals(workflow.getTransitionNodes().get("out").nodeType), "Outgoing node changed");
            }
            combinations++;
        }
        Method stop = RuleDeployer.class.getDeclaredMethod("stopCommitmentListener"); stop.setAccessible(true); stop.invoke(compilers.get(0));
        System.out.println("PASS: six stochastic instances, repeated non-Boolean service, installed instance isolation, bounded retry payloads, Boolean adapter outcomes and " + combinations + " incoming/outgoing parser combinations");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
