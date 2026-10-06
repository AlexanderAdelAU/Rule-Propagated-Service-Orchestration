import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Properties;
import org.btsn.build.LocalChannelCheck;

/** Checks real interface matching and fact parsing without contacting a remote host. */
public final class LocalChannelCheckTest {
    private static int checks;
    private static void require(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
    private static String fact(String channel, String address) {
        return "<Atom><Rel>boundChannel</Rel><Ind>" + channel + "</Ind><Ind>" + address + "</Ind></Atom>";
    }
    private static Path rules(String xml) throws Exception {
        Path file = Files.createTempFile("channel-facts", ".xml");
        file.toFile().deleteOnExit(); Files.writeString(file, xml); return file;
    }
    private static void rejects(String xml) throws Exception {
        try { LocalChannelCheck.readIp0(rules(xml)); throw new AssertionError("Accepted missing/conflicting ip0"); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    public static void main(String[] args) throws Exception {
        require(LocalChannelCheck.isLocalAddress("127.0.0.1"), "IPv4 loopback must be local");
        require(LocalChannelCheck.isLocalAddress("::1"), "IPv6 loopback must be local");
        require(LocalChannelCheck.isLocalAddress("localhost"), "Local hostname must be local");
        for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces()))
            for (InetAddress address : Collections.list(network.getInetAddresses()))
                require(LocalChannelCheck.isLocalAddress(address.getHostAddress()), "Interface address not detected: " + address);
        require(!LocalChannelCheck.isLocalAddress("192.0.2.123"), "A remote address must not launch locally");
        try { LocalChannelCheck.isLocalAddress("0.0.0.0"); throw new AssertionError("Accepted wildcard destination"); }
        catch (IllegalArgumentException expected) { checks++; }
        require("127.0.0.1".equals(LocalChannelCheck.readIp0(rules(
            "<!-- " + fact("ip0", "192.0.2.123") + " -->" + fact("ip0", "127.0.0.1")
            + fact("ip1", "192.0.2.123")))), "Comments/other channels affected ip0");
        require("127.0.0.1".equals(LocalChannelCheck.readIp0(rules(
            "<?xml version=\"1.0\"?><Assert xmlns=\"urn:test:ruleml\">" + fact("ip0", "127.0.0.1")
            + fact("ip0", "127.0.0.1") + "</Assert>"))), "Wrapped/namespaced/repeated facts failed");
        rejects(fact("ip1", "127.0.0.1"));
        rejects(fact("ip0", "127.0.0.1") + fact("ip0", "192.0.2.123"));
        rejects("<Implies><then>" + fact("ip0", "127.0.0.1") + "</then></Implies>");
        rejects(fact("ip0", ""));
        Path output = Files.createTempFile("channel-result", ".properties");
        output.toFile().deleteOnExit();
        LocalChannelCheck.main(new String[]{rules(fact("ip0", "127.0.0.1")).toString(), output.toString()});
        Properties result = new Properties();
        try (java.io.Reader reader = Files.newBufferedReader(output)) { result.load(reader); }
        require("local".equals(result.getProperty("ip0.mode")) && result.containsKey("ip0.is.local"), "Local flag missing");
        LocalChannelCheck.main(new String[]{rules(fact("ip0", "192.0.2.123")).toString(), output.toString()});
        result.clear();
        try (java.io.Reader reader = Files.newBufferedReader(output)) { result.load(reader); }
        require("remote".equals(result.getProperty("ip0.mode")) && !result.containsKey("ip0.is.local"), "Stale local flag in remote result");
        System.out.println("PASS: " + checks + " local-channel checks");
    }
}
