package org.btsn.utils;

public class ExternalDelayThread {

	public static void delay(double startTime, long period, boolean randomDelay) {
		double dPeriod = period;
		if (randomDelay) {
			dPeriod = period * Math.random();
		}
		while (System.currentTimeMillis() < (startTime + dPeriod))
			;

	}

}
