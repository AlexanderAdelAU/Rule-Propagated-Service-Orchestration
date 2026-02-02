/*
 * Generated at : 09-May-2006 20:45:32
 *
 * Copyright (c) 2005 - 2006, Edwin Dankert 
 * All rights reserved. 
 */

package org.btsn.utils;

import java.io.IOException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

public class XMLValidator {

	public static boolean validatePayload(String payload) {
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setValidating(true);
			factory.setNamespaceAware(true);
			factory.setAttribute("http://java.sun.com/xml/jaxp/properties/schemaLanguage",
					"http://www.w3.org/2001/XMLSchema");

			DocumentBuilder builder = factory.newDocumentBuilder();
			builder.setErrorHandler(new SimpleErrorHandler());

			// builder.parse(new
			// InputSource("src\\org\\rtsoa\\payload\\payload.xml"));
			builder.parse(new InputSource(payload));
			return true;
		} catch (ParserConfigurationException e) {
			e.printStackTrace();
			return false;
		} catch (SAXException e) {
			e.printStackTrace();
			return false;
		} catch (IOException e) {
			e.printStackTrace();
			return false;
		}

	}
}
