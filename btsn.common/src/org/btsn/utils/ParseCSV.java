package org.btsn.utils;

import java.io.FileReader;
import java.io.IOException;
import java.util.TreeMap;

import au.com.bytecode.opencsv.CSVReader;

// import org.rtsoa.services.bookservice.ServiceLoader;

public class ParseCSV {

	private TreeMap<Integer, Integer> eventSequence = new TreeMap<Integer, Integer>();

	public TreeMap parseCSV(String fileName) {

		try {
			// csv file containing data
			String strFile = fileName;
			CSVReader reader = new CSVReader(new FileReader(strFile));
			String[] nextLine;
			int lineNumber = 0;
			while ((nextLine = reader.readNext()) != null) {
				lineNumber++;
				eventSequence.put(Integer.parseInt(nextLine[0]), Integer.parseInt(nextLine[2]));
			}
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			System.err.println("Failure-> Error setting InetAddress");
		}
		return eventSequence;
	}
}
