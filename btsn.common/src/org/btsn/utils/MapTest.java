package org.btsn.utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

public class MapTest {
	private static TreeMap<Double, ArrayList<String>> t_argValPriorityMap = new TreeMap<Double, ArrayList<String>>();
	// private static ArrayList<String> valMap = new ArrayList<String>();

	// private static ConcurrentMap<Double, ArrayList<String>>
	// c_argValPriorityMap = new ConcurrentMap<Double, ArrayList<String>>();
	private static ConcurrentMap<Double, ArrayList<String>> c_argValPriorityMap = new ConcurrentHashMap<Double, ArrayList<String>>();
	// public static ConcurrentNavigableMap<Integer,Double> costPriorityOrder =
	// new ConcurrentNavigableMap<Integer,Double>();
	private static ConcurrentNavigableMap<Double, String> map = new ConcurrentSkipListMap<Double, String>();
	private static ArrayList<String> valMap = new ArrayList<String>();
	private static ConcurrentHashMap<Integer, String> cHMap = new ConcurrentHashMap<Integer, String>();

	public static void main(String[] args) {
		HashMap m1 = new HashMap();
		m1.put("Ankit", "8");
		m1.put("Kapil", "31");
		m1.put("Saurabh", "12");
		m1.put("Apoorva", "14");
		m1.put("Apoorvaa", "14");
		// System.out.println();
		// System.out.println("Elements of Map");
		// System.out.print(m1);

		valMap.add("test");
		valMap.add("j");
		// if key = dup then
		t_argValPriorityMap.put(1.11, valMap);
		t_argValPriorityMap.put(1.10, valMap);
		t_argValPriorityMap.put(1.144444444, valMap);
		t_argValPriorityMap.put(0.0001, valMap);
		c_argValPriorityMap.put(1.11, valMap);
		c_argValPriorityMap.put(1.10, valMap);
		c_argValPriorityMap.put(1.144444444, valMap);
		c_argValPriorityMap.put(0.0001, valMap);
		map.put(-1.1, "5.23");
		map.put(1.2, "1.23");
		map.put(3.0, "1.24");
		map.put(-5.0, "1.23");
		map.put(1.3, "1.23");
		map = map.descendingMap();
		cHMap.put(-1, "123");
		cHMap.put(1, "123");
		cHMap.put(21, "123");
		cHMap.put(-4, "124");
		// map.
	}
}
