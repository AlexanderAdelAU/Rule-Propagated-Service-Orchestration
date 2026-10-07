package org.btsn.deployment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.json.simple.JSONObject;

/** Derives network RuleML from the selected physical infrastructure and service placement. */
public final class GenerateDeploymentRules {
    public static void main(String[] args) throws Exception {
        Path common = args.length == 0 ? Path.of(".") : Path.of(args[0]);
        String address = args.length > 1 ? args[1].trim() : "";
        DeploymentConfiguration config = new DeploymentConfiguration(common);
        if (!address.isEmpty()) {
            Set<String> existing = new HashSet<>();
            for (Object item : DeploymentConfiguration.array(config.infrastructure, "nodes"))
                existing.add(DeploymentConfiguration.text((JSONObject)item, "address"));
            if (existing.size() != 1) throw new IllegalArgumentException("host.address override requires a single-host infrastructure");
            for (Object item : DeploymentConfiguration.array(config.infrastructure, "nodes"))
                ((JSONObject)item).put("address", address);
            Files.writeString(config.infrastructureFile, config.infrastructure.toJSONString());
            config = new DeploymentConfiguration(common);
        }
        String rules = rules(config);
        Path output = common.resolve(DeploymentConfiguration.text(config.profile, "deploymentRules"));
        Files.createDirectories(output.getParent());
        Files.writeString(output, rules);
        System.out.println("Generated deployment rules from " + config.profile.get("infrastructure") + " + " + config.profile.get("serviceDeployment"));
    }

    public static String rules(DeploymentConfiguration config) {
        StringBuilder output = new StringBuilder("<!-- Generated from the selected infrastructure and service deployment. -->\n");
        Set<String> channels = new HashSet<>(), runtimes = new HashSet<>(), operations = new HashSet<>(), sockets = new HashSet<>();
        for (Object item : DeploymentConfiguration.array(config.infrastructure, "nodes")) {
            JSONObject node = (JSONObject)item;
            String channel = DeploymentConfiguration.text(node, "channel");
            if (channels.add(channel)) atom(output, "boundChannel", channel, DeploymentConfiguration.text(node, "address"));
        }
        for (Object item : DeploymentConfiguration.array(config.serviceDeployment, "capabilities")) {
            JSONObject placement = (JSONObject)item;
            String nodeName = DeploymentConfiguration.text(placement, "node");
            String runtime = nodeName.matches("P\\d+") ? nodeName + "_Place" : nodeName;
            String operation = DeploymentConfiguration.text(placement, "operation");
            JSONObject node = config.node(nodeName);
            String channel = DeploymentConfiguration.text(node, "channel");
            String port = Integer.toString(config.port(placement));
            if (!operations.add(runtime + "\u0000" + operation)) throw new IllegalArgumentException("Duplicate runtime operation: " + runtime + "." + operation);
            if (!sockets.add(DeploymentConfiguration.text(node, "address") + ":" + channel + ":" + port)) throw new IllegalArgumentException("Two operations select the same port slot: " + nodeName);
            if (runtimes.add(runtime)) atom(output, "localDefined", runtime);
            atom(output, "activeService", runtime, operation, channel, port);
        }
        return output.toString();
    }
    private static void atom(StringBuilder output, String relation, String... values) {
        output.append("<Atom><Rel>").append(relation).append("</Rel>");
        for (String value : values) output.append("<Ind>").append(value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")).append("</Ind>");
        output.append("</Atom>\n");
    }
}
