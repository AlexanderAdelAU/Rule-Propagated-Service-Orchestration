package org.btsn.utils;

import java.io.File;
import java.io.IOException;

public class CreateFile {
	public static boolean createFile(String fileName) throws IOException {
		File f;

		f = new File(fileName);
		if (f.delete()) {
			System.err.println("CreatFile: file deleted");
		}
		if (!f.exists()) {
			if (f.createNewFile())
				return true;
			return false;
		}
		return true;
	}
}