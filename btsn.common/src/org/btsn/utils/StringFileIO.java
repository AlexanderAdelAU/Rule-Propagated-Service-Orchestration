package org.btsn.utils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

public class StringFileIO {

	public static String readFileAsString(String filePath) {
		byte[] buffer = new byte[(int) new File(filePath).length()];
		BufferedInputStream f = null;
		try {
			f = new BufferedInputStream(new FileInputStream(filePath));
			f.read(buffer);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return null;
		} finally {
			if (f != null)
				try {
					f.close();
				} catch (IOException ignored) {
				}
		}
		return new String(buffer);
	}

	public static void writeStringToFile(String iString, String filePath, int len) {
		byte[] buffer = iString.getBytes(); // new byte[(int) new
											// File(filePath).length()];
		BufferedOutputStream f = null;
		try {
			f = new BufferedOutputStream(new FileOutputStream(filePath));
			f.write(buffer, 0, len);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} finally {
			if (f != null)
				try {
					f.close();
				} catch (IOException ignored) {
				}
		}
		// return new String(buffer);
	}

}