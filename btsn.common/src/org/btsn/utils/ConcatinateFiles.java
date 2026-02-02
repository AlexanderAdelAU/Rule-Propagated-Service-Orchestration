package org.btsn.utils;

import java.io.*;

public class ConcatinateFiles {

	static public void main(String arg[]) throws java.io.IOException {
		PrintWriter pw = new PrintWriter(new FileOutputStream("C:/Temp/concat.txt"));
		File file = new File("C:/Text/");
		File[] files = file.listFiles();
		for (int i = 0; i < files.length; i++) {

			System.out.println("Processing " + files[i].getPath() + "... ");
			BufferedReader br = new BufferedReader(new FileReader(files[i].getPath()));
			String line = br.readLine();
			while (line != null) {
				pw.println(line);
				line = br.readLine();
			}
			br.close();
		}
		pw.close();
		System.out.println("All files have been concatenated into concat.txt");
	}
}