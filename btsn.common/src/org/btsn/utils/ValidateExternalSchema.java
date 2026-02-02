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
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;

import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

public class ValidateExternalSchema {

	public static void main(String[] args) {
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();

			factory.setValidating(false);
			factory.setNamespaceAware(true);

			SchemaFactory schemaFactory = SchemaFactory.newInstance("http://www.w3.org/2001/XMLSchema");

			factory.setSchema(schemaFactory.newSchema(new Source[] { new StreamSource(
					"src\\org\\rtsoa\\payload\\payload.xsd") }));

			DocumentBuilder builder = factory.newDocumentBuilder();
			builder.setErrorHandler(new SimpleErrorHandler());
			builder.parse(new InputSource("src\\org\\rtsoa\\payload\\payload.xml"));
		} catch (ParserConfigurationException e) {
			e.printStackTrace();
		} catch (SAXException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}
