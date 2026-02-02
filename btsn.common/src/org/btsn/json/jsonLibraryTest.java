package org.btsn.json;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.json.simple.JSONObject;

/**
 * Procedural test suite for the jsonLibrary class to verify JSON operations, including preventing double encoding,
 * key-value operations, nested objects, and file handling.
 */
public class jsonLibraryTest {
	private static int testsPassed = 0;
	private static int testsFailed = 0;

	public static void main(String[] args) {
		System.out.println("Starting jsonLibrary Test Suite");
		System.out.println("==============================");

		// Run all tests
		testAppendKeyValueString();
		testAppendKeyValueDuplicate();
		testAppendNestedObject();
		testAppendJSONString();
		testAppendInvalidJSONString();
		testReplaceValue();
		testReplaceNonExistentKey();
		testRemoveKey();
		testRemoveNonExistentKey();
		testHasKey();
		testReset();
		testSaveAndLoadToFile();
		testLoadInvalidFile();
		testExtractValue();
		testParseString();
		DoubleEncodingTest();

		// Print summary
		System.out.println("==============================");
		System.out.println("Test Summary: " + testsPassed + " passed, " + testsFailed + " failed");
	}

	private static void assertEquals(String testName, String expected, String actual) {
		if (expected == null && actual == null || (expected != null && expected.equals(actual))) {
			System.out.println(testName + ": PASSED");
			testsPassed++;
		} else {
			System.out.println(testName + ": FAILED (Expected: " + expected + ", Actual: " + actual + ")");
			testsFailed++;
		}
	}

	private static void DoubleEncodingTest() {

		jsonLibrary jsonAttributes = new jsonLibrary();

		// Create a nested JSON object
		JSONObject nested = new JSONObject();
		nested.put("title", "Anthem");

		// Append it as a nested object
		jsonAttributes.appendNestedObject("title", nested);

		// Log the result (simulating your log statement)
		System.out.println("jsonAttributes.extractfinalJSONString() " + jsonAttributes.extractfinalJSONString());
	}

	private static void assertTrue(String testName, boolean condition) {
		if (condition) {
			System.out.println(testName + ": PASSED");
			testsPassed++;
		} else {
			System.out.println(testName + ": FAILED (Expected: true, Actual: false)");
			testsFailed++;
		}
	}

	private static void assertNull(String testName, Object actual) {
		if (actual == null) {
			System.out.println(testName + ": PASSED");
			testsPassed++;
		} else {
			System.out.println(testName + ": FAILED (Expected: null, Actual: " + actual + ")");
			testsFailed++;
		}
	}

	private static void testAppendKeyValueString() {
		jsonLibrary jsonLib = new jsonLibrary();
		String result = jsonLib.put("name", "John");
		assertEquals("testAppendKeyValueString - JSON output", "{\"name\":\"John\"}", result);
		assertEquals("testAppendKeyValueString - getI", "John", jsonLib.get("name"));
	}

	private static void testAppendKeyValueDuplicate() {
		jsonLibrary jsonLib = new jsonLibrary();
		jsonLib.put("key", "value1");
		String result = jsonLib.put("key", "value2");
		assertEquals("testAppendKeyValueDuplicate - JSON output", "{\"key\":\"value1\"}", result);
		assertEquals("testAppendKeyValueDuplicate - getI", "value1", jsonLib.get("key"));
	}

	private static void testAppendNestedObject() {
		jsonLibrary jsonLib = new jsonLibrary();
		JSONObject nested = new JSONObject();
		nested.put("title", "Anthem");
		jsonLib.appendNestedObject("book", nested);
		String result = jsonLib.extractfinalJSONString();
		assertEquals("testAppendNestedObject - JSON output", "{\"book\":{\"title\":\"Anthem\"}}", result);
		assertEquals("testAppendNestedObject - getI", "{\"title\":\"Anthem\"}", jsonLib.get("book"));
	}

	private static void testAppendJSONString() {
		jsonLibrary jsonLib = new jsonLibrary();
		String jsonString = "{\"author\":\"Ayn Rand\",\"year\":1938}";
		String result = jsonLib.appendToJSONString(jsonString);
		assertEquals("testAppendJSONString - JSON output", "{\"author\":\"Ayn Rand\",\"year\":1938}", result);
		assertEquals("testAppendJSONString - getI author", "Ayn Rand", jsonLib.get("author"));
		assertEquals("testAppendJSONString - getI year", "1938", jsonLib.get("year"));
	}

	private static void testAppendInvalidJSONString() {
		jsonLibrary jsonLib = new jsonLibrary();
		String invalidJson = "not a json string";
		String result = jsonLib.appendToJSONString(invalidJson);
		assertEquals("testAppendInvalidJSONString - JSON output", "{}", result);
	}

	private static void testReplaceValue() {
		jsonLibrary jsonLib = new jsonLibrary();
		jsonLib.put("key", "oldValue");
		String result = jsonLib.replaceValue("key", "newValue");
		assertEquals("testReplaceValue - JSON output", "{\"key\":\"newValue\"}", result);
		assertEquals("testReplaceValue - getI", "newValue", jsonLib.get("key"));
	}

	private static void testReplaceNonExistentKey() {
		jsonLibrary jsonLib = new jsonLibrary();
		String result = jsonLib.replaceValue("key", "value");
		assertEquals("testReplaceNonExistentKey - JSON output", "{\"key\":\"value\"}", result);
		assertEquals("testReplaceNonExistentKey - getI", "value", jsonLib.get("key"));
	}

	private static void testRemoveKey() {
		jsonLibrary jsonLib = new jsonLibrary();
		jsonLib.put("key", "value");
		String removedValue = jsonLib.removeKey("key");
		assertEquals("testRemoveKey - removed value", "value", removedValue);
		assertEquals("testRemoveKey - JSON output", "{}", jsonLib.extractfinalJSONString());
		assertEquals("testRemoveKey - getI", "[Key not found: key]", jsonLib.get("key"));
	}

	private static void testRemoveNonExistentKey() {
		jsonLibrary jsonLib = new jsonLibrary();
		String removedValue = jsonLib.removeKey("nonexistent");
		assertNull("testRemoveNonExistentKey - removed value", removedValue);
		assertEquals("testRemoveNonExistentKey - JSON output", "{}", jsonLib.extractfinalJSONString());
	}

	private static void testHasKey() {
		jsonLibrary jsonLib = new jsonLibrary();
		jsonLib.put("key", "value");
		assertTrue("testHasKey - existing key", jsonLib.hasKey("key"));
		assertTrue("testHasKey - nonexistent key", !jsonLib.hasKey("nonexistent"));
	}

	private static void testReset() {
		jsonLibrary jsonLib = new jsonLibrary();
		jsonLib.put("key1", "value1");
		jsonLib.put("key2", "value2");
		jsonLib.reset();
		assertEquals("testReset - JSON output", "{}", jsonLib.extractfinalJSONString());
		assertTrue("testReset - hasKey", !jsonLib.hasKey("key1"));
	}

	private static void testSaveAndLoadToFile() {
		jsonLibrary jsonLib = new jsonLibrary();
		jsonLib.put("name", "John");
		JSONObject nested = new JSONObject();
		nested.put("title", "Anthem");
		jsonLib.appendNestedObject("book", nested);

		String tempFilePath = System.getProperty("java.io.tmpdir") + "test.json";
		jsonLib.saveToFile(tempFilePath);

		jsonLibrary newJsonLib = new jsonLibrary();
		newJsonLib.loadFromFile(tempFilePath);
		String result = newJsonLib.extractfinalJSONString();
		assertEquals("testSaveAndLoadToFile - JSON output", "{\"name\":\"John\",\"book\":{\"title\":\"Anthem\"}}", result);

		// Clean up
		new File(tempFilePath).delete();
	}

	private static void testLoadInvalidFile() {
		jsonLibrary jsonLib = new jsonLibrary();
		String tempFilePath = System.getProperty("java.io.tmpdir") + "invalid.json";
		try {
			Files.write(Paths.get(tempFilePath), "not a json".getBytes());
			jsonLib.loadFromFile(tempFilePath);
			assertEquals("testLoadInvalidFile - JSON output", "{}", jsonLib.extractfinalJSONString());
		} catch (IOException e) {
			System.out.println("testLoadInvalidFile: FAILED (IOException: " + e.getMessage() + ")");
			testsFailed++;
		} finally {
			new File(tempFilePath).delete();
		}
	}

	private static void testExtractValue() {
		String jsonString = "{\"key\":\"value\",\"nested\":{\"inner\":\"data\"}}";
		assertEquals("testExtractValue - key", "value", jsonLibrary.extractValue(jsonString, "key"));
		assertEquals("testExtractValue - nested", "{\"inner\":\"data\"}", jsonLibrary.extractValue(jsonString, "nested"));
		assertEquals("testExtractValue - nonexistent", "[Key not found: nonexistent]", jsonLibrary.extractValue(jsonString, "nonexistent"));
		assertEquals("testExtractValue - invalid JSON", "[Invalid or unparsable JSON]", jsonLibrary.extractValue("invalid json", "key"));
	}

	private static void testParseString() {
		String jsonString = "{\"key\":\"value\"}";
		JSONObject parsed = jsonLibrary.parseString(jsonString);
		assertTrue("testParseString - valid JSON", parsed != null && "value".equals(parsed.get("key")));
		assertNull("testParseString - empty JSON", jsonLibrary.parseString(""));
		assertNull("testParseString - null JSON", jsonLibrary.parseString(null));
		assertNull("testParseString - invalid JSON", jsonLibrary.parseString("invalid json"));
	}
}