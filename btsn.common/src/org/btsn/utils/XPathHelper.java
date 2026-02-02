package org.btsn.utils;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.TreeMap;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.TransformerFactoryConfigurationError;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.w3c.dom.DOMException;
import org.w3c.dom.Document;
import org.w3c.dom.DocumentType;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

public class XPathHelper {

	public static String modifyXMLItem(String PayLoad, String itemPath, String newItem) {

		try {

			Document doc = parseXmlString(PayLoad, false);
			XPath xpath = XPathFactory.newInstance().newXPath();
			XPathExpression expr = xpath.compile(itemPath);

			NodeList nodes = (NodeList) expr.evaluate(doc, XPathConstants.NODESET);

			for (int idx = 0; idx < nodes.getLength(); idx++) {
				nodes.item(idx).setTextContent(newItem);
			}
			Transformer xformer = TransformerFactory.newInstance().newTransformer();
			StringWriter swriter = new StringWriter();
			StreamResult result = new StreamResult(swriter);

			xformer.transform(new DOMSource(doc), new StreamResult(swriter));
			swriter.close();
			return swriter.toString();
		} // end try
		catch (IOException | XPathExpressionException | TransformerFactoryConfigurationError | TransformerException e) {
			System.err.println("Payload not well formed");
			return null;
		}
	}

	public static String AppendMLNodes(String PayLoad, NodeList nodeList) {

		try {

			Document newXmlDocument = parseXmlString(PayLoad, false);
			// XPath xpath = XPathFactory.newInstance().newXPath();
			// XPathExpression expr = xpath.compile(itemPath);

			// NodeList enodeList = (NodeList) expr.evaluate(newXmlDocument,
			// XPathConstants.NODESET);

			for (int i = 0; i < nodeList.getLength(); i++) {
				Element element = (Element) nodeList.item(i);
				Node copyNode = newXmlDocument.importNode(element, true);
				newXmlDocument.appendChild(copyNode);
			}

			Transformer xformer = TransformerFactory.newInstance().newTransformer();
			StringWriter swriter = new StringWriter();
			StreamResult result = new StreamResult(swriter);

			xformer.transform(new DOMSource(newXmlDocument), new StreamResult(swriter));
			swriter.close();
			return swriter.toString();
		} // end try
		catch (IOException | TransformerFactoryConfigurationError | TransformerException e) {
			System.err.println("Payload not well formed");
			return null;
		}
	}

	public static String MakeXMLDocumentFromNodeList(String PayLoad, String itemPath, NodeList nodeList) {

		try {
			Document xmldoc = parseXmlString(PayLoad, false);

			XPath xpath = XPathFactory.newInstance().newXPath();
			XPathExpression expr = xpath.compile(itemPath);

			Document newXmlDocument = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
			Element root = newXmlDocument.createElement("payload");
			// newXmlDocument = xmldoc;

			for (int i = 0; i < nodeList.getLength(); i++) {
				Node node = nodeList.item(i);
				Node copyNode = newXmlDocument.importNode(node, true);
				root.appendChild(copyNode);
			}
			// now add the other nodes
			NodeList dnodeList = (NodeList) expr.evaluate(xmldoc, XPathConstants.NODESET);

			Transformer xformer = TransformerFactory.newInstance().newTransformer();
			StringWriter swriter = new StringWriter();
			StreamResult result = new StreamResult(swriter);

			xformer.transform(new DOMSource(root), new StreamResult(swriter));
			swriter.close();
			return swriter.toString();
		} // end try
		catch (IOException | ParserConfigurationException | XPathExpressionException
				| TransformerFactoryConfigurationError | TransformerException e) {
			System.err.println("Payload not well formed");
			return null;
		}
	}

	public static String findXMLItem(String PayLoad, String itemPath) {

		Document xmldoc = parseXmlString(PayLoad, false);

		XPath xpath = XPathFactory.newInstance().newXPath();
		// NodeList nodes = null;
		String nodesValue = null;
		try {
			XPathExpression expr = xpath.compile(itemPath);
			NodeList nl = (NodeList) expr.evaluate(xmldoc, XPathConstants.NODESET);
			nodesValue = nl.item(0).getNodeValue();
		} catch (DOMException | XPathExpressionException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return null;
		}
		return nodesValue;

	}

	public static NodeList findXMLNodes(String PayLoad, String itemPath) {
		Document doc = null;
		NodeList nl = null;
		try {
			doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
					.parse(new InputSource(new StringReader(PayLoad)));
		} catch (SAXException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return null;
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return null;
		} catch (ParserConfigurationException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return null;
		}

		XPath xpath = XPathFactory.newInstance().newXPath();
		NodeList nodes = null;
		String nodesValues = null;
		try {
			XPathExpression expr = xpath.compile(itemPath);
			nl = (NodeList) expr.evaluate(doc, XPathConstants.NODESET);
		} catch (DOMException | XPathExpressionException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return nl;

	}

	public static TreeMap<String, String> findMultipleXMLItems(String PayLoad, String itemPath) throws Exception {
		TreeMap<String, String> treeMap3 = new TreeMap<String, String>();
		Document xmldoc = parseXmlString(PayLoad, false);

		XPath xpath = XPathFactory.newInstance().newXPath();
		NodeList nodes = (NodeList) xpath.evaluate(itemPath, xmldoc, XPathConstants.NODESET);
		String[] nodeName = new String[nodes.getLength()];

		for (int idx = 0; idx < nodes.getLength(); idx++) {
			treeMap3.put(nodes.item(idx).getNodeName(), nodes.item(idx).getTextContent());
		}
		return treeMap3;
	}

	public static String writeXMLDocument(Document doc, OutputStream out) throws IOException {
		// XXX note that this may fail to write out namespaces correctly if
		// the
		// document
		// is created with namespaces and no explicit prefixes; however no
		// code in
		// this package is likely to be doing so
		try {
			Transformer t = TransformerFactory.newInstance().newTransformer();
			DocumentType dt = doc.getDoctype();
			if (dt != null) {
				String pub = dt.getPublicId();
				if (pub != null) {
					t.setOutputProperty(OutputKeys.DOCTYPE_PUBLIC, pub);
				}
				t.setOutputProperty(OutputKeys.DOCTYPE_SYSTEM, dt.getSystemId());
			}
			t.setOutputProperty(OutputKeys.ENCODING, "UTF-8"); // NOI18N
			t.setOutputProperty(OutputKeys.INDENT, "yes"); // NOI18N
			t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4"); // NOI18N
			Source source = new DOMSource(doc);
			Result result = new StreamResult(out);
			t.transform(source, result);
			return result.toString();
		} catch (Exception e) {
			throw (IOException) new IOException(e.toString()).initCause(e);
		} catch (TransformerFactoryConfigurationError e) {
			throw (IOException) new IOException(e.toString()).initCause(e);
		}
	}

	// Parses an XML file and returns a DOM document.
	// If validating is true, the contents is validated against the DTD
	// specified in the file.
	public static Document parseXmlFile(String filename, boolean validating) {
		try {
			// Create a builder factory
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setValidating(validating);

			// Create the builder and parse the file
			Document doc = factory.newDocumentBuilder().parse(new File(filename));
			return doc;
		} catch (SAXException e) {
			// A parsing error occurred; the xml input is not valid
		} catch (ParserConfigurationException e) {
		} catch (IOException e) {
		}
		return null;
	}

	// Parses an XML file and returns a DOM document.
	// If validating is true, the contents is validated against the DTD
	// specified in the file.
	public static Document parseXmlString(String xmlString, boolean validating) {
		try {
			// Create a builder factory
			Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
					.parse(new InputSource(new StringReader(xmlString)));

			// Create the builder and parse the file
			// Document doc = factory.newDocumentBuilder().parse(new
			// File(filename));
			return doc;
		} catch (SAXException e) {
			// A parsing error occurred; the xml input is not valid
		} catch (ParserConfigurationException e) {
		} catch (IOException e) {
		}
		return null;
	}

}
