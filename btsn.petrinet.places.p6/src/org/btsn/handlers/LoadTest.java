package org.btsn.handlers;

public class LoadTest {

	public LoadTest() {
		long startTime = System.currentTimeMillis();
		int t20ms = 1000000;
		int t100ms = 100000000;
		System.err.println("Start: " + startTime);
		for (int i = 1; i < t100ms; i++) {
			long j = i ^ 2;
		}
		System.err.println("Duration: " + (System.currentTimeMillis() - startTime));
	}

	public static void main(String[] args) throws Exception {
		new LoadTest();
	}

}
