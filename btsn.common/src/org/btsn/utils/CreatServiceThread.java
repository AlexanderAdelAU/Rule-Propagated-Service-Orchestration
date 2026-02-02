package org.btsn.utils;

class CreateServiceThread implements Runnable {
	private String service;

	// private int port;

	CreateServiceThread(Object service) {
		// this.service = service;
		// this.port = port;

	}

	public void run() {
		System.out.println("loading class....: " + service);
		// ChannelHandler handler = new ChannelHandler(channel,port);
		// SimpleClassLoader cls = new SimpleClassLoader();
		// try {
		// ChannelHandler handler = new ChannelHandler(channel,port);
		// cls.loadClass(service);
		// } catch (ClassNotFoundException e) {
		// TODO Auto-generated catch block
		// e.printStackTrace();
		// }

	}

}
