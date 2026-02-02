package org.btsn.json;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

/**
 * jsonLibrary is a utility class that builds and manages a JSON object. It supports appending key-value pairs,
 * retrieving values, checking key existence, and reading/writing JSON files. This version prevents double encoding of
 * nested JSON structures by storing them as proper JSON objects/arrays.
 */
public class jsonLibrary {
	// Fixed logger to use correct class name
	private static final Logger logger = Logger.getLogger(jsonLibrary.class);

	/** The internal JSON object holding all key-value data. */
	private JSONObject finalJSONString;

	/** Constructor: Initializes an empty JSON object. */
	public jsonLibrary() {
		logger.setLevel(Level.INFO);
		finalJSONString = new JSONObject();
	}

	/** ---------------------- Static helper methods ---------------------- **/

	/** Encodes a string key-value pair. */
	public static String encodeKeyValue(String key, String value) {
		JSONObject obj = new JSONObject();
		obj.put(key, value);
		return obj.toJSONString();
	}

	/** Encodes an integer key-value pair. */
	public static String encodeKeyValue(String key, int value) {
		JSONObject obj = new JSONObject();
		obj.put(key, value);
		return obj.toJSONString();
	}

	/** Encodes a boolean key-value pair. */
	public static String encodeKeyValue(String key, boolean value) {
		JSONObject obj = new JSONObject();
		obj.put(key, value);
		return obj.toJSONString();
	}

	/** Parse a JSON string into a JSONObject (can be static). */
	public static JSONObject parseString(String jsonString) {
		try {
			if (jsonString == null || jsonString.trim().isEmpty()) {
				return null;
			}
			return (JSONObject) new JSONParser().parse(jsonString);
		} catch (Exception e) {
			logger.error("Error parsing JSON string: " + jsonString, e);
			return null;
		}
	}

	/** Extracts a key's value from a JSON string. */
	public static String extractValue(String jsonString, String key) {
		JSONObject jsonObject = parseString(jsonString);
		if (jsonObject != null) {
			if (jsonObject.containsKey(key)) {
				Object value = jsonObject.get(key);
				return value != null ? value.toString() : "null";
			} else {
				return "[Key not found: " + key + "]";
			}
		} else {
			return "[Invalid or unparsable JSON]";
		}
	}

	/** ---------------------- Instance methods ---------------------- **/

	/** Appends a key-value pair (string) to the internal JSON object. */
	public String appendToJSONString(String key, String value) {
		if (finalJSONString.containsKey(key)) {
			logger.warn("Key \"" + key + "\" already exists. Skipping.");
		} else if (value != null) {
			// Check if value is likely a JSON string (starts with { or [)
			if (value.trim().startsWith("{") || value.trim().startsWith("[")) {
				JSONObject parsedValue = parseString(value);
				if (parsedValue != null) {
					finalJSONString.put(key, parsedValue);
					logger.debug("Appended key \"" + key + "\" with parsed JSON object: " + parsedValue);
				} else {
					finalJSONString.put(key, value);
					logger.debug("Appended key \"" + key + "\" with value \"" + value + "\" as string (failed JSON parse).");
				}
			} else {
				finalJSONString.put(key, value);
				logger.debug("Appended key \"" + key + "\" with value \"" + value + "\" as string.");
			}
		}
		return finalJSONString.toJSONString();
	}

	/**
	 * Merges two JSON objects into a new JSONObject. Duplicate keys are resolved by keeping the value from jsonArg2.
	 * Throws an IllegalArgumentException if the same key has values of different types in the two objects.
	 *
	 * @param jsonArg1
	 *            The first JSON object to merge.
	 * @param jsonArg2
	 *            The second JSON object to merge.
	 * @return A new JSONObject containing the merged key-value pairs.
	 * @throws IllegalArgumentException
	 *             if mismatched value types are found for the same key or if inputs are null.
	 */
	private JSONObject merge(JSONObject jsonArg1, JSONObject jsonArg2) throws IllegalArgumentException {
		if (jsonArg1 == null || jsonArg2 == null) {
			logger.error("Cannot merge null JSON objects.");
			throw new IllegalArgumentException("One or both JSON objects are null.");
		}

		JSONObject merged = new JSONObject();

		// Copy all key-value pairs from jsonArg1
		for (Object keyObj : jsonArg1.keySet()) {
			String key = (String) keyObj;
			merged.put(key, jsonArg1.get(key));
		}

		// Merge jsonArg2, checking for type mismatches
		for (Object keyObj : jsonArg2.keySet()) {
			String key = (String) keyObj;
			Object value2 = jsonArg2.get(key);

			if (merged.containsKey(key)) {
				Object value1 = merged.get(key);

				// Check for type mismatch
				if (value1 != null && value2 != null && !value1.getClass().equals(value2.getClass())) {
					logger.error("Type mismatch for key \"" + key + "\": " + "jsonArg1 value type is " + value1.getClass().getSimpleName()
							+ ", jsonArg2 value type is " + value2.getClass().getSimpleName());
					throw new IllegalArgumentException("Type mismatch for key \"" + key + "\": " + "jsonArg1 value type is "
							+ value1.getClass().getSimpleName() + ", jsonArg2 value type is " + value2.getClass().getSimpleName());
				}

				// Log overwrite for duplicates
				logger.debug("Overwriting key \"" + key + "\" with value from jsonArg2: " + value2);
			}

			// Add or overwrite the key-value pair
			merged.put(key, value2);
		}

		logger.info("Successfully merged JSON objects. Result: " + merged.toJSONString());
		return merged;
	}

	/**
	 * Merges two JSON strings into a new JSONObject. Parses the input strings into JSONObjects and merges them.
	 * Duplicate keys are resolved by keeping the value from jsonString2. Throws an IllegalArgumentException if the
	 * strings are invalid JSON, null, empty, or if mismatched value types are found.
	 *
	 * @param jsonString1
	 *            The first JSON string to merge.
	 * @param jsonString2
	 *            The second JSON string to merge.
	 * @return A new JSONObject containing the merged key-value pairs.
	 * @throws IllegalArgumentException
	 *             if the input strings are invalid, null, empty, or if type mismatches occur.
	 */
	public JSONObject merge(String jsonString1, String jsonString2) throws IllegalArgumentException {
		if (jsonString1 == null || jsonString1.trim().isEmpty() || jsonString2 == null || jsonString2.trim().isEmpty()) {
			logger.error("Cannot merge null or empty JSON strings.");
			throw new IllegalArgumentException("One or both JSON strings are null or empty.");
		}

		JSONObject jsonObj1 = parseString(jsonString1);
		if (jsonObj1 == null) {
			logger.error("Failed to parse first JSON string: " + jsonString1);
			throw new IllegalArgumentException("Invalid JSON string: " + jsonString1);
		}

		JSONObject jsonObj2 = parseString(jsonString2);
		if (jsonObj2 == null) {
			logger.error("Failed to parse second JSON string: " + jsonString2);
			throw new IllegalArgumentException("Invalid JSON string: " + jsonString2);
		}

		try {
			return merge(jsonObj1, jsonObj2);
		} catch (IllegalArgumentException e) {
			logger.error("Merge failed for JSON strings: " + e.getMessage());
			throw e; // Re-throw the type mismatch exception
		}
	}

	/**
	 * Merges two JSON strings into the internal finalJSONString. Parses the input strings and merges them. Duplicate
	 * keys are resolved by keeping the value from jsonString2. Throws an IllegalArgumentException if the strings are
	 * invalid JSON, null, empty, or if type mismatches occur.
	 *
	 * @param jsonString1
	 *            The first JSON string to merge.
	 * @param jsonString2
	 *            The second JSON string to merge.
	 * @throws IllegalArgumentException
	 *             if the input strings are invalid, null, empty, or if type mismatches occur.
	 */
	public void mergeInto(String jsonString1, String jsonString2) throws IllegalArgumentException {
		JSONObject merged = merge(jsonString1, jsonString2); // Use overloaded merge
		finalJSONString.clear();
		finalJSONString.putAll(merged);
		logger.info("Merged JSON strings into finalJSONString: " + finalJSONString.toJSONString());
	}

	/** Removes a key from the internal JSON object and returns its value. */
	public String removeKey(String key) {
		if (finalJSONString.containsKey(key)) {
			Object removedValue = finalJSONString.remove(key);
			logger.info("Removed key \"" + key + "\" with value: " + removedValue);
			return removedValue != null ? removedValue.toString() : "null";
		} else {
			logger.warn("Attempted to remove non-existent key \"" + key + "\".");
			return null;
		}
	}

	/**
	 * Appends a JSON string contents to the internal object. Nested JSON objects/arrays are stored as-is to prevent
	 * double encoding.
	 */
	public String appendToJSONString(String jsonString) {
		try {
			JSONObject obj = parseString(jsonString);
			if (obj == null) {
				logger.error("Parsed JSON object is null or empty, cannot append contents from: " + jsonString);
				return finalJSONString.toJSONString();
			}

			for (Object k : obj.keySet()) {
				String key = (String) k;
				if (finalJSONString.containsKey(key)) {
					logger.warn("Key \"" + key + "\" already exists. Skipping.");
				} else {
					// Store the value as-is (JSONObject, JSONArray, or primitive)
					finalJSONString.put(key, obj.get(key));
					logger.debug("Appended key \"" + key + "\" from external JSON.");
				}
			}
		} catch (Exception e) {
			logger.error("Failed to append contents from JSON string: " + jsonString, e);
		}
		return finalJSONString.toJSONString();
	}

	/**
	 * Appends or replaces a key with a nested JSON object. The nested object is stored directly to prevent double
	 * encoding.
	 */
	public void appendNestedObject(String key, JSONObject nestedObject) {
		finalJSONString.put(key, nestedObject);
		logger.debug("Appended nested object for key \"" + key + "\".");
	}

	/** Retrieves the value associated with a key. */
	public String get(String key) {
		Object value = finalJSONString.get(key);
		if (finalJSONString.containsKey(key)) {
			return value != null ? value.toString() : "null";
		} else {
			return "[Key not found: " + key + "]";
		}
	}

	/** Checks whether a key exists. */
	public boolean hasKey(String key) {
		return finalJSONString.containsKey(key);
	}

	/** Clears all internal JSON key-value pairs. */
	public void reset() {
		finalJSONString.clear();
		logger.info("Internal JSON object reset.");
	}

	/** Extracts the internal JSON object as a string. */
	public String extractfinalJSONString() {
		return finalJSONString.toJSONString();
	}

	/** Saves the internal JSON to a file. */
	public void saveToFile(String filename) {
		try (FileWriter file = new FileWriter(filename)) {
			file.write(finalJSONString.toJSONString());
			file.flush();
			System.out.println("Saved JSON to file: " + filename);
		} catch (IOException e) {
			System.err.println("Failed to save file: " + e.getMessage());
		}
	}

	/** Loads JSON data from a file. */
	public void loadFromFile(String filename) {
		try (FileReader reader = new FileReader(filename)) {
			JSONParser parser = new JSONParser();
			Object obj = parser.parse(reader);
			if (obj instanceof JSONObject) {
				finalJSONString = (JSONObject) obj;
				System.out.println("Loaded JSON from file: " + filename);
			} else {
				System.err.println("File does not contain a valid JSON object. Content: " + obj);
			}
		} catch (IOException | ParseException e) {
			System.err.println("Error loading file: " + e.getMessage());
		}
	}

	/** Replaces a key's value with a string. */
	public String replaceValue(String key, String newValue) {
		Object oldValue = finalJSONString.put(key, newValue);
		if (oldValue != null) {
			logger.info("Replaced value for key \"" + key + "\". Old value: \"" + oldValue + "\", New value: \"" + newValue + "\".");
		} else {
			logger.debug("Key \"" + key + "\" did not exist. Added with value \"" + newValue + "\".");
		}
		return finalJSONString.toJSONString();
	}

	/** Replaces a key's value with an integer. */
	public String replaceValue(String key, int newValue) {
		Object oldValue = finalJSONString.put(key, newValue);
		if (oldValue != null) {
			logger.info("Replaced value for key \"" + key + "\". Old value: \"" + oldValue + "\", New value: \"" + newValue + "\".");
		} else {
			logger.debug("Key \"" + key + "\" did not exist. Added with value \"" + newValue + "\".");
		}
		return finalJSONString.toJSONString();
	}

	/** Replaces a key's value with a boolean. */
	public String replaceValue(String key, boolean newValue) {
		Object oldValue = finalJSONString.put(key, newValue);
		if (oldValue != null) {
			logger.info("Replaced value for key \"" + key + "\". Old value: \"" + oldValue + "\", New value: \"" + newValue + "\".");
		} else {
			logger.debug("Key \"" + key + "\" did not exist. Added with value \"" + newValue + "\".");
		}
		return finalJSONString.toJSONString();
	}
	// Add these methods to your jsonLibrary class

	/** Overloaded method: "put" - Appends a key-value pair (string) to the internal JSON object. */
	public String put(String key, String value) {
		return appendToJSONString(key, value);
	}

	/** Overloaded method: "put" - Appends a key-value pair (integer) to the internal JSON object. */
	public String put(String key, int value) {
		if (finalJSONString.containsKey(key)) {
			logger.warn("Key \"" + key + "\" already exists. Skipping.");
		} else {
			finalJSONString.put(key, value);
			logger.debug("Appended key \"" + key + "\" with value \"" + value + "\" as integer.");
		}
		return finalJSONString.toJSONString();
	}

	/** Overloaded method: "put" - Appends a key-value pair (boolean) to the internal JSON object. */
	public String put(String key, boolean value) {
		if (finalJSONString.containsKey(key)) {
			logger.warn("Key \"" + key + "\" already exists. Skipping.");
		} else {
			finalJSONString.put(key, value);
			logger.debug("Appended key \"" + key + "\" with value \"" + value + "\" as boolean.");
		}
		return finalJSONString.toJSONString();
	}

	/** Overloaded method: "put" - Appends a JSON string contents to the internal object. */
	public String put(String jsonString) {
		return appendToJSONString(jsonString);
	}

	/** Extracts the internal JSON object as a string. */
	public String getFinal() {
		return extractfinalJSONString();
	}
}