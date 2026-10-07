package org.btsn.deployment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

/** Combines reusable network resources with a separately selected service placement. */
public final class DeploymentConfiguration {
    public final JSONObject profile;
    public final JSONObject infrastructure;
    public final JSONObject serviceDeployment;
    public final Path infrastructureFile;
    private final Map<String, JSONObject> nodes = new HashMap<>();

    public DeploymentConfiguration(Path common) throws Exception {
        profile = read(common.resolve("BusinessServiceDefinitions/Deployment.json"));
        infrastructureFile = common.resolve(text(profile, "infrastructure"));
        infrastructure = read(infrastructureFile);
        serviceDeployment = read(common.resolve(text(profile, "serviceDeployment")));
        require("Infrastructure".equals(infrastructure.get("definitionType")), "Selected definition is not Infrastructure");
        require(!infrastructure.containsKey("capabilities"), "Physical infrastructure must not contain service capabilities");
        require("ServiceDeployment".equals(serviceDeployment.get("definitionType")), "Selected definition is not ServiceDeployment");
        require(!serviceDeployment.containsKey("nodes"), "Service deployment must not contain physical nodes");
        Set<String> sockets = new HashSet<>();
        Map<String,String> channels = new HashMap<>();
        for (Object item : array(infrastructure, "nodes")) {
            JSONObject node = (JSONObject)item;
            String name = text(node, "node"), channel = text(node, "channel"), address = text(node, "address");
            require(nodes.put(name, node) == null, "Duplicate infrastructure node: " + name);
            String previous = channels.put(channel, address);
            require(previous == null || previous.equals(address), "Conflicting address for channel: " + channel);
            JSONArray ports = array(node, "basePorts");
            require(!ports.isEmpty(), "No fixed ports for node: " + name);
            for (Object value : ports) {
                int port = integer(value, "base port");
                require(port > 0 && port <= 45535, "Invalid base port for node: " + name);
                require(sockets.add(address + ":" + channel + ":" + port), "Duplicate physical endpoint: " + name + ":" + port);
            }
        }
        require(!nodes.isEmpty(), "No infrastructure nodes");
        for (Object item : array(serviceDeployment, "capabilities")) {
            JSONObject placement = (JSONObject)item;
            require(!placement.containsKey("basePort") && !placement.containsKey("address") && !placement.containsKey("channel"),
                    "Service placement must select a fixed port slot, not network settings");
            port(placement);
        }
    }

    public JSONObject node(String name) {
        JSONObject node = nodes.get(name);
        require(node != null, "Capability refers to an undefined node: " + name);
        return node;
    }

    public int port(JSONObject placement) {
        JSONArray ports = array(node(text(placement, "node")), "basePorts");
        int slot = integer(placement.get("portSlot"), "port slot");
        require(slot >= 0 && slot < ports.size(), "Undefined port slot for node: " + text(placement, "node"));
        return integer(ports.get(slot), "base port");
    }

    public static JSONObject read(Path file) throws Exception {
        return (JSONObject)new JSONParser().parse(Files.readString(file));
    }
    public static String text(JSONObject object, String key) {
        Object value = object.get(key);
        require(value != null && !value.toString().trim().isEmpty(), "Missing " + key);
        return value.toString();
    }
    public static JSONArray array(JSONObject object, String key) {
        require(object.get(key) instanceof JSONArray, "Missing array: " + key);
        return (JSONArray)object.get(key);
    }
    private static int integer(Object value, String label) {
        require(value instanceof Number, "Invalid " + label);
        long number = ((Number)value).longValue();
        require(number == ((Number)value).doubleValue() && number >= 0 && number <= Integer.MAX_VALUE, "Invalid " + label);
        return (int)number;
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
