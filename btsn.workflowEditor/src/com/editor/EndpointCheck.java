package com.editor;

import java.io.IOException;
import java.net.*;
import java.util.*;

/**
 * Before a run: is each REST service the deployment calls answering? Any HTTP answer counts (a service that only
 * takes POST answers a GET with 405); no answer at all means the hosts' calls would fail and the tokens stop there.
 */
public final class EndpointCheck {
    public static final int TIMEOUT_MS = 5000;

    private EndpointCheck() {}

    /** Null when the address answers; otherwise why not, in a few words. */
    public static String probe(String address, int timeoutMs) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection)new URL(address).openConnection();
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setRequestMethod("GET");
            connection.getResponseCode();
            return null;
        } catch (UnknownHostException e) {
            return "unknown host " + e.getMessage();
        } catch (ConnectException e) {
            return "connection refused";
        } catch (SocketTimeoutException e) {
            return "no answer within " + timeoutMs / 1000 + " s";
        } catch (IOException | IllegalArgumentException e) {
            return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** An address on the machine that makes the call: only meaningful from here when the hosts run on this machine too. */
    public static boolean isLoopback(String address) {
        try {
            String host = new URL(address).getHost().replaceAll("^\\[|\\]$", "");
            return host.equalsIgnoreCase("localhost") || host.startsWith("127.") || host.equals("::1");
        } catch (MalformedURLException e) {
            return false;
        }
    }

    /**
     * The REST services of this process that do not answer, one line each. Loopback addresses are checked only when
     * the hosts run on this machine (singleMachine); from another machine they would name the wrong computer.
     */
    public static List<String> notAnswering(Canvas canvas, boolean singleMachine, int timeoutMs) {
        List<String> problems = new ArrayList<>();
        Map<String, String> checked = new HashMap<>();
        for (ProcessElement place : canvas.getPlaces()) {
            String address = canvas.getServiceRegistry().restEndpointFor(place);
            if (address == null || address.isEmpty() || (!singleMachine && isLoopback(address))) continue;
            String reason = checked.containsKey(address) ? checked.get(address) : probe(address, timeoutMs);
            checked.put(address, reason);
            if (reason != null)
                problems.add("Place '" + place.getLabel() + "': " + place.getService() + " at " + address + " is not answering (" + reason
                    + "). Start the service, or change its endpoint in Deploy.");
        }
        return problems;
    }
}
