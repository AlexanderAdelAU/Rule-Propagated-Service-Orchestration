package org.btsn.build;

import java.io.IOException;
import java.io.StringReader;
import java.io.Writer;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/** Ant build helper: decide where ip0 runs without contacting or starting a host. */
public final class LocalChannelCheck {
    private LocalChannelCheck() { }

    /** Unconditional ground facts (relation followed by its Ind values), in file order. */
    static java.util.List<java.util.List<String>> facts(Path rules) throws Exception {
        String xml = Files.readString(rules, StandardCharsets.UTF_8)
            .replace("\uFEFF", "").replaceFirst("(?s)^\\s*<\\?xml.*?\\?>", "");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        NodeList atoms = factory.newDocumentBuilder().parse(new InputSource(
            new StringReader("<channels>" + xml + "</channels>")))
            .getElementsByTagNameNS("*", "Atom");
        java.util.List<java.util.List<String>> facts = new java.util.ArrayList<>();
        for (int n = 0; n < atoms.getLength(); n++) {
            Element atom = (Element) atoms.item(n);
            boolean conditional = false;
            for (Node parent = atom.getParentNode(); parent != null; parent = parent.getParentNode())
                if ("Implies".equals(parent.getLocalName())) conditional = true;
            if (conditional) continue;
            String relation = "";
            java.util.List<String> arguments = new java.util.ArrayList<>();
            boolean variable = false;
            for (Node child = atom.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (!(child instanceof Element)) continue;
                if ("Rel".equals(child.getLocalName())) relation = child.getTextContent().trim();
                else if ("Ind".equals(child.getLocalName())) arguments.add(child.getTextContent().trim());
                else variable = true;
            }
            if (variable || relation.isEmpty()) continue;
            arguments.add(0, relation);
            facts.add(arguments);
        }
        return facts;
    }

    public static String readIp0(Path rules) throws Exception {
        Set<String> addresses = new LinkedHashSet<>();
        for (java.util.List<String> fact : facts(rules))
            if (fact.size() == 3 && "boundChannel".equals(fact.get(0)) && "ip0".equals(fact.get(1))) addresses.add(fact.get(2));
        if (addresses.size() != 1 || addresses.contains(""))
            throw new IllegalArgumentException("Expected one unambiguous boundChannel(ip0, address) fact in "
                + rules + "; found " + addresses);
        return addresses.iterator().next();
    }

    /** Host node (P1, P2, ...) to the channel its place operations use, from activeService(Pn_Place, op, channel, port). */
    public static java.util.Map<String, String> nodeChannels(Path rules) throws Exception {
        java.util.Map<String, String> channels = new java.util.TreeMap<>();
        for (java.util.List<String> fact : facts(rules)) {
            if (fact.size() != 5 || !"activeService".equals(fact.get(0))) continue;
            java.util.regex.Matcher place = java.util.regex.Pattern.compile("(P\\d+)_Place").matcher(fact.get(1));
            if (!place.matches()) continue;
            String previous = channels.put(place.group(1), fact.get(3));
            if (previous != null && !previous.equals(fact.get(3)))
                throw new IllegalArgumentException(place.group(1) + "_Place uses two channels: " + previous + " and " + fact.get(3));
        }
        return channels;
    }

    /** Channel to address, from boundChannel(channel, address); a channel bound twice must agree. */
    public static java.util.Map<String, String> channelAddresses(Path rules) throws Exception {
        java.util.Map<String, String> addresses = new java.util.TreeMap<>();
        for (java.util.List<String> fact : facts(rules)) {
            if (fact.size() != 3 || !"boundChannel".equals(fact.get(0))) continue;
            String previous = addresses.put(fact.get(1), fact.get(2));
            if (previous != null && !previous.equals(fact.get(2)))
                throw new IllegalArgumentException("Channel " + fact.get(1) + " is bound to " + previous + " and " + fact.get(2));
        }
        return addresses;
    }

    public static boolean isLocalAddress(String host) throws IOException {
        for (InetAddress address : InetAddress.getAllByName(host)) {
            if (address.isAnyLocalAddress())
                throw new IllegalArgumentException("ip0 must identify a destination, not a wildcard address: " + host);
            if (address.isLoopbackAddress() || NetworkInterface.getByInetAddress(address) != null) return true;
        }
        return false;
    }

    /**
     * Arguments: rules-file output-properties-file. Writes ip0's decision (Monitor and the event
     * generators), and for each host node pN its channel, address and, when that address is this
     * machine, pN.channel.is.local.
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected: rules-file output-properties-file");
        Path rules = Path.of(args[0]);
        String address = readIp0(rules);
        boolean local = isLocalAddress(address);
        Properties properties = new Properties();
        properties.setProperty("ip0.address", address);
        properties.setProperty("ip0.mode", local ? "local" : "remote");
        properties.setProperty(local ? "ip0.is.local" : "ip0.is.remote", "true");
        java.util.Map<String, String> addresses = channelAddresses(rules);
        StringBuilder summary = new StringBuilder();
        for (java.util.Map.Entry<String, String> node : nodeChannels(rules).entrySet()) {
            String key = node.getKey().toLowerCase(java.util.Locale.ROOT), channel = node.getValue();
            String nodeAddress = addresses.get(channel);
            if (nodeAddress == null || nodeAddress.isEmpty())
                throw new IllegalArgumentException(node.getKey() + " uses channel " + channel + ", which has no boundChannel address");
            boolean here = isLocalAddress(nodeAddress);
            properties.setProperty(key + ".channel", channel);
            properties.setProperty(key + ".address", nodeAddress);
            if (here) properties.setProperty(key + ".channel.is.local", "true");
            summary.append(String.format("%n  %s -> %s (%s): %s", node.getKey(), nodeAddress, channel, here ? "this machine" : "another machine"));
        }
        Path output = Path.of(args[1]).toAbsolutePath();
        Files.createDirectories(output.getParent());
        try (Writer writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            properties.store(writer, "Local launch decisions");
        }
        System.out.println("ip0 -> " + address + ": " + (local
            ? "local; launch configured local runtimes"
            : "remote; use manually started runtimes") + summary);
    }
}
