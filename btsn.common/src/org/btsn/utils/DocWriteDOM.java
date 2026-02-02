package org.btsn.utils;

/*
 * Copyright (c) Ian F. Darwin, http://www.darwinsys.com/, 1996-2002.
 * All rights reserved. Software written by Ian F. Darwin and others.
 * $Id: LICENSE,v 1.8 2004/02/09 03:33:38 ian Exp $
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR AND CONTRIBUTORS ``AS IS''
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL THE AUTHOR OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * 
 * Java, the Duke mascot, and all variants of Sun's Java "steaming coffee
 * cup" logo are trademarks of Sun Microsystems. Sun's, and James Gosling's,
 * pioneering role in inventing and promulgating (and standardizing) the Java 
 * language and environment is gratefully acknowledged.
 * 
 * The pioneering role of Dennis Ritchie and Bjarne Stroustrup, of AT&T, for
 * inventing predecessor languages C and C++ is also gratefully acknowledged.
 */
import java.io.File;
import java.io.IOException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.TransformerFactoryConfigurationError;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Node;

/**
 * Make up and write an XML document, using DOM UPDATED FOR JAXP.
 * 
 * @author Ian Darwin, http://www.darwinsys.com/
 * @version $Id: DocWriteDOM.java,v 1.6 2004/03/01 03:42:57 ian Exp $
 */
public class DocWriteDOM {

	public static void main(String[] av) throws IOException {
		DocWriteDOM dw = new DocWriteDOM();
		Document doc = dw.makeDoc();

		// Sadly, the write() method is not in the DOM spec, so we
		// have to cast the Document to its implementing class
		// in order to call the Write method.
		//
		// WARNING
		//
		// This code therefore depends upon the particular
		// parser implementation.
		//
		Transformer xformer = null;
		try {
			xformer = TransformerFactory.newInstance().newTransformer();
		} catch (TransformerConfigurationException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (TransformerFactoryConfigurationError e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		try {
			xformer.transform(new DOMSource(doc), new StreamResult(new File("data_new.xml")));
		} catch (TransformerException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		// ((org.apache.crimson.tree.XmlDocument)doc).write(System.out);
	}

	/** Generate the XML document */
	protected Document makeDoc() {
		try {
			DocumentBuilderFactory fact = DocumentBuilderFactory.newInstance();
			DocumentBuilder parser = fact.newDocumentBuilder();
			Document doc = parser.newDocument();

			Node root = doc.createElement("Poem");
			doc.appendChild(root);

			Node stanza = doc.createElement("Stanza");
			root.appendChild(stanza);

			Node line = doc.createElement("Line");
			stanza.appendChild(line);
			line.appendChild(doc.createTextNode("Once, upon a midnight dreary"));
			line = doc.createElement("Line");
			stanza.appendChild(line);
			line.appendChild(doc.createTextNode("While I pondered, weak and weary"));

			return doc;

		} catch (Exception ex) {
			System.err.println("+============================+");
			System.err.println("|        XML Error           |");
			System.err.println("+============================+");
			System.err.println(ex.getClass());
			System.err.println(ex.getMessage());
			System.err.println("+============================+");
			return null;
		}
	}
}
// demo xml file
/*
 * <?xml version="1.0"?> <people> <person> <name>Ian Darwin</name>
 * <email>http://www.darwinsys.com/</email> <country>Canada</country> </person>
 * <person> <name>Another Darwin</name> <email type="intranet">afd@node1</email>
 * <country>Canada</country> </person> </people>
 */
