
package com.pnmlviewer.io;

import org.w3c.dom.*;

import com.pnmlviewer.model.PnmlModel;

import javax.xml.parsers.*;
import javax.xml.xpath.*;
import javax.xml.XMLConstants;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.awt.geom.Point2D;

public class PnmlParser2 {

    private static final String NS = "http://www.pnml.org/version-2009/grammar/pnml";

    public static PnmlModel parseFile(File f) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        DocumentBuilder db = dbf.newDocumentBuilder();
        return parseDocument(db.parse(f));
    }

    public static PnmlModel parseString(String xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        DocumentBuilder db = dbf.newDocumentBuilder();
        return parseDocument(db.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))));
    }

    private static PnmlModel parseDocument(Document doc) throws Exception {
        PnmlModel model = new PnmlModel();

        XPathFactory xpf = XPathFactory.newInstance();
        XPath xp = xpf.newXPath();
        xp.setNamespaceContext(new javax.xml.namespace.NamespaceContext() {
            public String getNamespaceURI(String prefix) {
                if ("pnml".equals(prefix)) return NS;
                return XMLConstants.NULL_NS_URI;
            }
            public String getPrefix(String uri) { return null; }
            public java.util.Iterator<String> getPrefixes(String uri) { return null; }
        });

        NodeList placeNodes = (NodeList) xp.evaluate("//pnml:place", doc, XPathConstants.NODESET);
        for (int i = 0; i < placeNodes.getLength(); i++) {
            Element e = (Element) placeNodes.item(i);
            PnmlModel.Node n = new PnmlModel.Node();
            n.id = e.getAttribute("id");
            n.place = true;
            n.name = textOrEmpty(xp, "./pnml:name/pnml:text", e);
            String mark = textOrEmpty(xp, "./pnml:initialMarking/pnml:text", e).trim();
            if (!mark.isEmpty()) try { n.tokens = Integer.parseInt(mark); } catch (NumberFormatException ignore) {}
            Point2D.Double pos = readPosition(xp, e);
            if (pos != null) n.pos = pos;
            model.nodes.put(n.id, n);
        }

        NodeList transNodes = (NodeList) xp.evaluate("//pnml:transition", doc, XPathConstants.NODESET);
        for (int i = 0; i < transNodes.getLength(); i++) {
            Element e = (Element) transNodes.item(i);
            PnmlModel.Node n = new PnmlModel.Node();
            n.id = e.getAttribute("id");
            n.place = false;
            n.name = textOrEmpty(xp, "./pnml:name/pnml:text", e);
            Point2D.Double pos = readPosition(xp, e);
            if (pos != null) n.pos = pos;
            model.nodes.put(n.id, n);
        }

        NodeList arcNodes = (NodeList) xp.evaluate("//pnml:arc", doc, XPathConstants.NODESET);
        for (int i = 0; i < arcNodes.getLength(); i++) {
            Element e = (Element) arcNodes.item(i);
            PnmlModel.Arc a = new PnmlModel.Arc();
            a.id = e.getAttribute("id");
            a.source = e.getAttribute("source");
            a.target = e.getAttribute("target");
            String w = textOrEmpty(xp, "./pnml:inscription/pnml:text", e).trim();
            if (!w.isEmpty()) try { a.weight = Integer.parseInt(w); } catch (NumberFormatException ignore) {}
            model.arcs.add(a);
        }
        return model;
    }

    private static String textOrEmpty(XPath xp, String expr, Element ctx) {
        try {
            String s = (String) xp.evaluate(expr, ctx, XPathConstants.STRING);
            return s == null ? "" : s;
        } catch (Exception e) { return ""; }
    }

    private static Point2D.Double readPosition(XPath xp, Element e) {
        String[] tries = new String[] {
                ".//pnml:position",
                ".//pnml:offset",
                ".//pnml:graphics/pnml:position"
        };
        for (String t : tries) {
            try {
                NodeList nl = (NodeList) xp.evaluate(t, e, XPathConstants.NODESET);
                if (nl.getLength() > 0 && nl.item(0) instanceof Element) {
                    Element pos = (Element) nl.item(0);
                    String xs = pos.getAttribute("x"), ys = pos.getAttribute("y");
                    if (!xs.isEmpty() && !ys.isEmpty()) {
                        double x = Double.parseDouble(xs), y = Double.parseDouble(ys);
                        return new Point2D.Double(x, y);
                    }
                }
            } catch (Exception ignore) {}
        }
        return null;
    }

    public static void writeWithPositions(PnmlModel model, File out) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        DocumentBuilder db = dbf.newDocumentBuilder();

        Document doc = db.newDocument();
        Element pnml = doc.createElementNS(NS, "pnml");
        doc.appendChild(pnml);
        Element net = doc.createElementNS(NS, "net");
        net.setAttribute("id", "pnml-export");
        net.setAttribute("type", NS + "/ptnet");
        pnml.appendChild(net);
        Element page = doc.createElementNS(NS, "page");
        page.setAttribute("id", "page1");
        net.appendChild(page);

        for (PnmlModel.Node n : model.nodes.values()) {
            Element el = doc.createElementNS(NS, n.place ? "place" : "transition");
            el.setAttribute("id", n.id);
            Element name = doc.createElementNS(NS, "name");
            Element text = doc.createElementNS(NS, "text");
            text.setTextContent(n.name == null ? n.id : n.name);
            name.appendChild(text);
            el.appendChild(name);
            if (n.place) {
                Element im = doc.createElementNS(NS, "initialMarking");
                Element t = doc.createElementNS(NS, "text");
                t.setTextContent(Integer.toString(n.tokens));
                im.appendChild(t);
                el.appendChild(im);
            }
            if (!Double.isNaN(n.pos.x)) {
                Element graphics = doc.createElementNS(NS, "graphics");
                Element pos = doc.createElementNS(NS, "position");
                pos.setAttribute("x", Double.toString(n.pos.x));
                pos.setAttribute("y", Double.toString(n.pos.y));
                graphics.appendChild(pos);
                el.appendChild(graphics);
            }
            page.appendChild(el);
        }

        for (PnmlModel.Arc a : model.arcs) {
            Element el = doc.createElementNS(NS, "arc");
            el.setAttribute("id", a.id);
            el.setAttribute("source", a.source);
            el.setAttribute("target", a.target);
            Element ins = doc.createElementNS(NS, "inscription");
            Element text = doc.createElementNS(NS, "text");
            text.setTextContent(Integer.toString(a.weight));
            ins.appendChild(text);
            el.appendChild(ins);
            page.appendChild(el);
        }

        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer tr = tf.newTransformer();
        tr.setOutputProperty(OutputKeys.INDENT, "yes");
        tr.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        tr.transform(new DOMSource(doc), new StreamResult(out));
    }
}
