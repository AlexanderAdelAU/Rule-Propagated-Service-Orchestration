package org.btsn.utils;

import java.io.File;
import java.io.IOException;

public class DeleteFile {
	public static boolean deleteFile(String fileName) throws IOException {
		File f;
		f = new File(fileName);
		if (f.delete()) {
			return true;
		}
		return false;
	}

}