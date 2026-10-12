package com.gfi.ozg.fitko.swing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.gfi.ozg.fitko.swing.model.DataFormat;
import com.gfi.ozg.fitko.swing.store.Json;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringReader;
import java.io.StringWriter;

/** Well-formedness check and pretty-printing for the payload editor. */
public final class PayloadTools {

    private PayloadTools() {
    }

    /** @return {@code null} if well-formed, otherwise the parser's message */
    public static String checkWellFormed(String payload, DataFormat format) {
        try {
            if (format == DataFormat.JSON) {
                Json.MAPPER.readTree(payload);
            } else {
                parseXml(payload);
            }
            return null;
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    public static String prettyPrint(String payload, DataFormat format) throws Exception {
        if (format == DataFormat.JSON) {
            JsonNode node = Json.MAPPER.readTree(payload);
            return Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        }
        Document document = parseXml(payload);
        stripWhitespaceNodes(document.getDocumentElement());
        TransformerFactory factory = TransformerFactory.newInstance();
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        Transformer transformer = factory.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        StringWriter out = new StringWriter();
        transformer.transform(new DOMSource(document), new StreamResult(out));
        return out.toString().replaceFirst("\\?><", "?>\n<");
    }

    private static Document parseXml(String payload) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(null);
        return builder.parse(new InputSource(new StringReader(payload)));
    }

    private static void stripWhitespaceNodes(org.w3c.dom.Node node) {
        org.w3c.dom.NodeList children = node.getChildNodes();
        for (int i = children.getLength() - 1; i >= 0; i--) {
            org.w3c.dom.Node child = children.item(i);
            if (child.getNodeType() == org.w3c.dom.Node.TEXT_NODE && child.getTextContent().isBlank()) {
                node.removeChild(child);
            } else if (child.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                stripWhitespaceNodes(child);
            }
        }
    }
}
