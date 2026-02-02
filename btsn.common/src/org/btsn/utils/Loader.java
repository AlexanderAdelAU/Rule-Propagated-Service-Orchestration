package org.btsn.utils;

class Loader {
	public static void main(String args[]) {
		LoadHandlerThread t = new LoadHandlerThread("224.0.0.1", 1234);
		new Thread(t).start();
		// LoadHandlerThread t2 = new LoadHandlerThread ("224.0.0.2",1234);
		// new Thread(t2). start ( );
	}
}

class LoadHandlerThread implements Runnable {
	private String channel;
	private int port;

	LoadHandlerThread(String channel, int port) {
		this.channel = channel;
		this.port = port;
	}

	public void run() {
		System.out.println("Starting.... " + channel);
		// ChannelHandler handler = new ChannelHandler(channel,port);

	}
}