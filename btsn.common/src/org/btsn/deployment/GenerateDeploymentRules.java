package org.btsn.deployment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.simple.JSONObject;

/**
 * Derives network RuleML from the selected physical infrastructure and service placement.
 *
 * Arguments: common-folder [host-address [infrastructure-name]]. Runtime preparation runs this on an
 * isolated copy of btsn.common, never on the source folder. A host address points every node of a
 * single-host infrastructure at that address; an infrastructure name (for example MultiHost) selects
 * InfrastructureDefinitionFolder/MultiHost.json in place of the profile's own choice.
 */
public final class GenerateDeploymentRules {
    /** Channels carry their port offset in their name (ip0, ip1, ...); hosts and senders both rely on it. */
    private static final Pattern CHANNEL = Pattern.compile("ip\\d+");
    /** activeService(Pn_SomethingService, operation, channel, port): an administrative service run by host Pn. */
    private static final Pattern NODE_ADMIN_SERVICE = Pattern.compile(
        "(<Rel>\\s*activeService\\s*</Rel>\\s*<Ind>\\s*(P\\d+)_\\w+\\s*</Ind>\\s*<Ind>[^<]*</Ind>\\s*<Ind>)\\s*([^<]*?)\\s*(</Ind>)");

    public static void main(String[] args) throws Exception {
        Path common = args.length == 0 ? Path.of(".") : Path.of(args[0]);
        String address = args.length > 1 ? args[1].trim() : "";
        String infrastructureName = args.length > 2 ? args[2].trim() : "";
        if (!infrastructureName.isEmpty()) selectInfrastructure(common, infrastructureName);
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
        requireChannels(config);
        String rules = rules(config);
        Path output = common.resolve(DeploymentConfiguration.text(config.profile, "deploymentRules"));
        Files.createDirectories(output.getParent());
        Files.writeString(output, rules);
        int moved = placeAdministrativeServices(common, config);
        System.out.println("Generated deployment rules from " + config.profile.get("infrastructure") + " + " + config.profile.get("serviceDeployment")
            + (moved == 0 ? "" : "; " + moved + " administrative services follow their node's channel"));
    }

    /** Points the profile at InfrastructureDefinitionFolder/name.json (name may include .json). */
    static void selectInfrastructure(Path common, String name) throws Exception {
        String file = name.toLowerCase(java.util.Locale.ROOT).endsWith(".json") ? name : name + ".json";
        if (file.contains("/") || file.contains("\\") || file.startsWith("."))
            throw new IllegalArgumentException("Infrastructure is a file name in InfrastructureDefinitionFolder: " + name);
        Path definition = common.resolve("InfrastructureDefinitionFolder").resolve(file);
        if (!Files.isRegularFile(definition)) throw new IllegalArgumentException("No infrastructure definition " + definition);
        Path profileFile = common.resolve("BusinessServiceDefinitions/Deployment.json");
        JSONObject profile = DeploymentConfiguration.read(profileFile);
        profile.put("infrastructure", "InfrastructureDefinitionFolder/" + file);
        Files.writeString(profileFile, profile.toJSONString());
    }

    /** Every channel is ipN (its port offset), and ip0 exists: Monitor and the event generators use it. */
    static void requireChannels(DeploymentConfiguration config) {
        boolean ip0 = false;
        for (Object item : DeploymentConfiguration.array(config.infrastructure, "nodes")) {
            String channel = DeploymentConfiguration.text((JSONObject)item, "channel");
            if (!CHANNEL.matcher(channel).matches())
                throw new IllegalArgumentException("Channel must be ip0, ip1, ... (it selects the port offset): " + channel);
            ip0 |= "ip0".equals(channel);
        }
        if (!ip0) throw new IllegalArgumentException("Infrastructure must use channel ip0 for the machine that runs the launcher and Monitor");
    }

    /**
     * The initialisation and collector services of host Pn run inside Pn's process, so they must be
     * addressed on Pn's channel. The registered files declare them on ip0; this rewrites the channel of
     * each one whose node the infrastructure defines, leaving the operation and port unchanged. On a
     * single-host infrastructure every node is on ip0 and nothing changes. Returns how many moved.
     */
    static int placeAdministrativeServices(Path common, DeploymentConfiguration config) throws Exception {
        int moved = 0;
        if (!(config.profile.get("directServiceRules") instanceof org.json.simple.JSONArray)) return 0;
        for (Object name : DeploymentConfiguration.array(config.profile, "directServiceRules")) {
            Path file = common.resolve(name.toString());
            if (!Files.isRegularFile(file)) continue;
            String text = Files.readString(file);
            Matcher m = NODE_ADMIN_SERVICE.matcher(text);
            StringBuilder out = new StringBuilder();
            int count = 0;
            while (m.find()) {
                JSONObject node = config.findNode(m.group(2));
                String channel = node == null ? m.group(3) : DeploymentConfiguration.text(node, "channel");
                if (!channel.equals(m.group(3))) count++;
                m.appendReplacement(out, Matcher.quoteReplacement(m.group(1) + channel + m.group(4)));
            }
            m.appendTail(out);
            if (count > 0) Files.writeString(file, out.toString());
            moved += count;
        }
        return moved;
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
