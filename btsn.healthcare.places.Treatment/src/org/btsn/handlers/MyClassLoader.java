package org.btsn.handlers;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Enumeration;

public class MyClassLoader extends ClassLoader {

	public MyClassLoader() {
		super(MyClassLoader.class.getClassLoader());
	}

	public MyClassLoader(ClassLoader parent) {
		super(parent);
	}

	@Override
	public Class<?> loadClass(String name) throws ClassNotFoundException {
		System.out.println("MyClassLoader is loading " + name + " at time=" + System.currentTimeMillis());
		return super.loadClass(name);
	}// loadClass

	@Override
	public synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
		System.out.println("MyClassLoader is loading " + name + " with resolve = " + resolve);
		return super.loadClass(name, resolve);
	}

	@Override
	protected Class<?> findClass(String name) throws ClassNotFoundException {
		System.out.println("MyClassLoader findClass " + name);
		return super.findClass(name);
	}

	@Override
	protected URL findResource(String name) {
		System.out.println("MyClassLoader findResource " + name);
		return super.findResource(name);
	}

	@Override
	protected Enumeration<URL> findResources(String name) throws IOException {
		System.out.println("MyClassLoader findResources " + name);
		return super.findResources(name);
	}

	@Override
	protected Package getPackage(String name) {
		System.out.println("MyClassLoader getPackage " + name);
		return super.getPackage(name);
	}

	@Override
	public URL getResource(String name) {
		System.out.println("MyClassLoadergetResource " + name);
		return super.getResource(name);
	}

	@Override
	public InputStream getResourceAsStream(String name) {
		System.out.println("MyClassLoader getResourceAsStream " + name);
		return super.getResourceAsStream(name);
	}

	@Override
	public Enumeration<URL> getResources(String name) throws IOException {
		System.out.println("MyClassLoader getResources " + name);
		return super.getResources(name);
	}
}// MyClassLoader