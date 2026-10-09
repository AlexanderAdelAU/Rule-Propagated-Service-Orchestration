import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.btsn.deployment.DeploymentConfiguration;
import org.btsn.deployment.GenerateDeploymentRules;
import org.btsn.invocation.BusinessCapabilityResolver;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

/** Check all domain placements against one physical definition and reject network drift. */
public final class SharedInfrastructureCheck {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath(), common = root.resolve("btsn.common");
        List<Path> profiles = new ArrayList<>();
        profiles.add(common.resolve("BusinessServiceDefinitions/Deployment.json"));
        try (java.util.stream.Stream<Path> files = Files.walk(root.resolve("btsn.services/deployments"))) {
            files.filter(p -> p.toString().endsWith(".json")).sorted().forEach(profiles::add);
        }
        int capabilities = 0;
        for (Path profile : profiles) {
            Path fixture = fixture(common, profile);
            DeploymentConfiguration config = new DeploymentConfiguration(fixture);
            check("InfrastructureDefinitionFolder/SingleHost.json".equals(config.profile.get("infrastructure")), "Domain retained a separate physical definition: " + profile);
            for (int number = 1; number <= 6; number++)
                check(((Number)((JSONArray)config.node("P" + number).get("basePorts")).get(0)).intValue() == 4000 + number, "Primary port changed: P" + number);
            GenerateDeploymentRules.main(new String[]{fixture.toString()});
            BusinessCapabilityResolver resolver = new BusinessCapabilityResolver(fixture);
            JSONObject catalog = DeploymentConfiguration.read(fixture.resolve((String)config.profile.get("catalog")));
            for (Object entry : (JSONArray)catalog.get("services")) {
                JSONObject service = (JSONObject)entry;
                if (!"active".equals(service.get("status"))) continue;
                check(service.get("implementationClass").equals(resolver.resolve((String)service.get("service"), (String)service.get("operation"), (String)service.get("returnAttribute"), ((JSONArray)service.get("inputs")).size(), null)), "Wrong implementation: " + service.get("service"));
                capabilities++;
            }
            String physical = Files.readString(config.infrastructureFile);
            String rules = Files.readString(fixture.resolve((String)config.profile.get("deploymentRules")));
            JSONObject deployment = config.serviceDeployment;
            JSONObject first = (JSONObject)((JSONArray)deployment.get("capabilities")).get(0);
            String originalService = first.get("service").toString();
            first.put("service", "AlternativeFunction");
            Files.writeString(fixture.resolve((String)config.profile.get("serviceDeployment")), deployment.toJSONString());
            rejects(() -> GenerateDeploymentRules.main(new String[]{fixture.toString()}), "Undefined catalogue operation");
            check(rules.equals(Files.readString(fixture.resolve((String)config.profile.get("deploymentRules")))), "Invalid service wrote runtime rules");
            JSONObject alternative = null;
            for (Object entry : (JSONArray)catalog.get("services")) {
                JSONObject definition = (JSONObject)entry;
                if (originalService.equals(definition.get("service")) && first.get("operation").equals(definition.get("operation"))) alternative = new JSONObject(definition);
            }
            check(alternative != null, "Missing original catalogue operation");
            alternative.put("service", "AlternativeFunction");
            ((JSONArray)catalog.get("services")).add(alternative);
            Files.writeString(fixture.resolve((String)config.profile.get("catalog")), catalog.toJSONString());
            Files.writeString(fixture.resolve((String)config.profile.get("serviceDeployment")), deployment.toJSONString());
            GenerateDeploymentRules.main(new String[]{fixture.toString()});
            check(physical.equals(Files.readString(config.infrastructureFile)), "Changing service function changed physical settings");
            check(rules.equals(Files.readString(fixture.resolve((String)config.profile.get("deploymentRules")))), "Changing service function changed runtime endpoint rules");
            first.put("portSlot", 99L);
            Files.writeString(fixture.resolve((String)config.profile.get("serviceDeployment")), deployment.toJSONString());
            rejects(() -> new DeploymentConfiguration(fixture), "Undefined port slot");
            first.put("portSlot", 0L); first.put("basePort", 9999L);
            Files.writeString(fixture.resolve((String)config.profile.get("serviceDeployment")), deployment.toJSONString());
            rejects(() -> new DeploymentConfiguration(fixture), "not network settings");
            System.out.println("PASS: shared physical infrastructure / " + root.relativize(profile));
        }
        Path override = fixture(common, common.resolve("BusinessServiceDefinitions/Deployment.json"));
        GenerateDeploymentRules.main(new String[]{override.toString(), "127.0.0.1"});
        DeploymentConfiguration config = new DeploymentConfiguration(override);
        for (Object item : (JSONArray)config.infrastructure.get("nodes")) check("127.0.0.1".equals(((JSONObject)item).get("address")), "Host override not synchronized");
        new BusinessCapabilityResolver(override);
        JSONObject physical = config.infrastructure;
        ((JSONObject)((JSONArray)physical.get("nodes")).get(1)).put("address", "192.0.2.2");
        Files.writeString(config.infrastructureFile, physical.toJSONString());
        rejects(() -> new DeploymentConfiguration(override), "Conflicting address for channel");
        check(Files.mismatch(common.resolve("InfrastructureDefinitionFolder/SingleHost.json"), override.resolve("InfrastructureDefinitionFolder/SingleHost.json")) != -1, "Override did not change isolated runtime");
        System.out.println("PASS: " + profiles.size() + " domain profiles, " + capabilities + " capability operations, fixed endpoints across service changes, invalid placement rejection and synchronized runtime host override");
    }
    private static Path fixture(Path common, Path profile) throws Exception {
        Path fixture = Files.createTempDirectory("shared-infrastructure-");
        JSONObject config = DeploymentConfiguration.read(profile);
        List<String> paths = new ArrayList<>(Arrays.asList((String)config.get("infrastructure"), (String)config.get("serviceDeployment"), (String)config.get("catalog")));
        for (Object file : (JSONArray)config.get("directServiceRules")) paths.add(file.toString());
        for (String file : paths) { Files.createDirectories(fixture.resolve(file).getParent()); Files.copy(common.resolve(file), fixture.resolve(file)); }
        Files.createDirectories(fixture.resolve("BusinessServiceDefinitions"));
        Files.copy(profile, fixture.resolve("BusinessServiceDefinitions/Deployment.json"));
        return fixture;
    }
    private static void rejects(Throwing action, String expected) throws Exception {
        try { action.run(); throw new AssertionError("Expected rejection: " + expected); }
        catch (IllegalArgumentException ex) { check(ex.getMessage().contains(expected), ex.getMessage()); }
    }
    private interface Throwing { void run() throws Exception; }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
