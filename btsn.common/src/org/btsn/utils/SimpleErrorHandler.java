/*
 * $Id: $
 *
 * The contents of this file are subject to the Mozilla Public License 
 * Version 1.1 (the "License"); you may not use this file except in 
 * compliance with the License. You may obtain a copy of the License at 
 * http://www.mozilla.org/MPL/ 
 *
 * Software distributed under the License is distributed on an "AS IS" basis, 
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License 
 * for the specific language governing rights and limitations under the License.
 *
 * The Original Code is XML Hammer code. (org.xmlhammer.*)
 *
 * The Initial Developer of the Original Code is Edwin Dankert. Portions created 
 * by the Initial Developer are Copyright (C) 2005 - 2006 the Initial Developer. 
 * All Rights Reserved.
 *
 * Contributor(s): Edwin Dankert <edankert@gmail.com>
 */
package org.btsn.utils;

import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXParseException;

public class SimpleErrorHandler implements ErrorHandler {
	public void warning(SAXParseException e) {
		System.out.println(e.getMessage());
	}

	public void error(SAXParseException e) {
		System.out.println(e.getMessage());
	}

	public void fatalError(SAXParseException e) {
		System.out.println(e.getMessage());
	}
}