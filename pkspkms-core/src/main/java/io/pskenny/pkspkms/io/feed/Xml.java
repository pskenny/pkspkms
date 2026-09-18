package io.pskenny.pkspkms.io.feed;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

// DOM helpers for feed and OPML parsing. Namespace-aware so itunes:/dc: prefixes
// stop mattering; DOCTYPE and external entities are refused (remote content).
final class Xml {

    private Xml() {}

    static Document parse(byte[] bytes) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new InputSource(new ByteArrayInputStream(bytes)));
        } catch (Exception e) {
            throw new IOException("Malformed XML: " + e.getMessage(), e);
        }
    }

    static Element root(Document document) {
        return document.getDocumentElement();
    }

    // First child element with this local name, or null
    static Element first(Element parent, String localName) {
        for (Element child : children(parent)) {
            if (localName.equals(child.getLocalName())) {
                return child;
            }
        }
        return null;
    }

    static List<Element> all(Element parent, String localName) {
        List<Element> found = new ArrayList<>();
        for (Element child : children(parent)) {
            if (localName.equals(child.getLocalName())) {
                found.add(child);
            }
        }
        return found;
    }

    static String text(Element parent, String localName) {
        Element child = first(parent, localName);
        return child == null ? null : child.getTextContent().strip();
    }

    static String attr(Element element, String name) {
        String value = element.getAttribute(name);
        return value.isEmpty() ? null : value;
    }

    static String textOf(Element element) {
        return element.getTextContent() == null ? null : element.getTextContent().strip();
    }

    private static List<Element> children(Element parent) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                result.add((Element) node);
            }
        }
        return result;
    }
}
