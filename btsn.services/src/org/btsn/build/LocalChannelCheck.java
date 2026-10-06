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

    public static String readIp0(Path rules) throws Exception {
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
        Set<String> addresses = new LinkedHashSet<>();
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
            if (!variable && "boundChannel".equals(relation) && arguments.size() == 2
                    && "ip0".equals(arguments.get(0))) addresses.add(arguments.get(1));
        }
        if (addresses.size() != 1 || addresses.contains(""))
            throw new IllegalArgumentException("Expected one unambiguous boundChannel(ip0, address) fact in "
                + rules + "; found " + addresses);
        return addresses.iterator().next();
    }

    public static boolean isLocalAddress(String host) throws IOException {
        for (InetAddress address : InetAddress.getAllByName(host)) {
            if (address.isAnyLocalAddress())
                throw new IllegalArgumentException("ip0 must identify a destination, not a wildcard address: " + host);
            if (address.isLoopbackAddress() || NetworkInterface.getByInetAddress(address) != null) return true;
        }
        return false;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected: rules-file output-properties-file");
        String address = readIp0(Path.of(args[0]));
        boolean local = isLocalAddress(address);
        Properties properties = new Properties();
        properties.setProperty("ip0.address", address);
        properties.setProperty("ip0.mode", local ? "local" : "remote");
        properties.setProperty(local ? "ip0.is.local" : "ip0.is.remote", "true");
        Path output = Path.of(args[1]).toAbsolutePath();
        Files.createDirectories(output.getParent());
        try (Writer writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            properties.store(writer, "Local ip0 launch decision");
        }
        System.out.println("ip0 -> " + address + ": " + (local
            ? "local; launch configured local runtimes"
            : "remote; use manually started runtimes"));
    }
}
