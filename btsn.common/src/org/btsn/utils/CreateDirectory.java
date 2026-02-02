package org.btsn.utils;

import java.io.File;
import java.io.IOException;

public class CreateDirectory {
	public static boolean createDirectory(String pathName) throws IOException {
		File f;

		/*
		 * First see if a directory is needed
		 */
		File file = new File(pathName);
		boolean exists = file.exists();
		if (exists)
			return true;
		try {
			if (file.mkdirs())
				return true;
		} catch (SecurityException ie) {
			System.err.println(ie);
		}
		System.out.println("CreateDirectory: Unable to create" + pathName);
		return false;

	}
}
