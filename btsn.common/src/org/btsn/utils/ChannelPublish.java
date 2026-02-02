package org.btsn.utils;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * Rule-Consistent ChannelPublish using UDP with rule-based port calculation
 * Replaces multicast with UDP targeting correct EventReactor ports
 */
public class ChannelPublish {
	
	/**
	 * Publishes payload via UDP to the correct EventReactor port.
	 * Converts multicast channel/port to UDP localhost:calculatedPort using the same
	 * rule-based port allocation logic as ServiceLoader and EventGenerator.
	 *
	 * @param channel Original multicast channel (e.g., "224.0.1.3")
	 * @param port Original multicast port (e.g., "1025") 
	 * @param payload XML payload to send
	 */
	public void channelPublish(String channel, String port, String payload) {
		DatagramSocket udpSocket = null;
		try {
			udpSocket = new DatagramSocket();
			
			// Send to localhost instead of multicast address
			InetAddress targetAddress = InetAddress.getLoopbackAddress();
			
			// Extract channel number from multicast channel (e.g., 224.0.1.3 → 3)
			int channelNumber = 1; // default
			try {
				String[] channelParts = channel.split("\\.");
				if (channelParts.length >= 4) {
					channelNumber = Integer.parseInt(channelParts[3]);
				}
			} catch (Exception e) {
				System.err.println("Could not parse channel number from: " + channel + ", using default 1");
			}
			
			// Use rule-based calculation that matches ServiceLoader exactly!
			// EventReactor: 10000 + (channelNumber * 1000) + basePort
			int basePort = Integer.parseInt(port);
			int channelOffset = channelNumber * 1000; // Same as ServiceLoader
			int targetPort = 10000 + channelOffset + basePort; // Rule-based calculation
			
			// Log the rule-consistent calculation for debugging
			System.out.println("=== ChannelPublish Rule-Based Target Calculation ===");
			System.out.println("Original: " + channel + ":" + port);
			System.out.println("Channel Number: " + channelNumber);
			System.out.println("Base Port: " + basePort);
			System.out.println("Channel Offset: " + channelOffset);
			System.out.println("Target Port: " + targetPort + " (10000+" + channelOffset + "+" + basePort + ")");
			System.out.println("=== Should match ServiceLoader's EventReactor port ===");
			
			byte[] data = payload.getBytes();
			DatagramPacket dp = new DatagramPacket(data, data.length, targetAddress, targetPort);
			
			udpSocket.send(dp);
			System.out.println("Sent UDP inter-service message to localhost:" + targetPort + 
				" (EventReactor for channel " + channelNumber + ", original " + channel + ":" + port + ")");
			
		} catch (IOException e) {
			e.printStackTrace();
			System.err.println("Failure attempting to publish via UDP to channel " + channel + ":" + port);
		} finally {
			if (udpSocket != null) {
				udpSocket.close();
			}
		}
	}
	
	/**
	 * Publishes rule commitment messages via UDP.
	 * This method may be used for rule coordination between services.
	 */
	public void ruleCommitmentPublish(String payload) {
		// Implementation depends on your rule commitment requirements
		// Placeholder for rule commitment publishing if needed
		System.out.println("Rule commitment publish called with payload length: " + payload.length());
	}
}