/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.ibarra.maven;

import io.vidocq.ibarra.compiler.JspPropertyGroup;
import java.nio.file.*;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import org.xml.sax.*;

/** Build-only extraction of JSP property groups; no schema/entity downloads. */
final class WebXmlProperties {
    private static final Set<String> PROPERTIES = Set.of("el-ignored", "is-xml", "scripting-invalid", "page-encoding",
            "deferred-syntax-allowed-as-literal", "trim-directive-whitespaces", "default-content-type", "buffer",
            "error-on-el-not-found", "error-on-undeclared-namespace");
    private WebXmlProperties() { }

    static List<JspPropertyGroup> read(Path descriptor) throws Exception {
        if (!Files.exists(descriptor)) return List.of();
        var factory = DocumentBuilderFactory.newDefaultInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
            @Override public void error(SAXParseException failure) throws SAXException { throw failure; }
            @Override public void fatalError(SAXParseException failure) throws SAXException { throw failure; }
        });
        Element root;
        try (var input = Files.newInputStream(descriptor)) { root = builder.parse(input).getDocumentElement(); }
        if (!name(root).equals("web-app")) throw new IllegalArgumentException("Expected web-app in " + descriptor);
        String namespace = root.getNamespaceURI();
        if (namespace != null && !Set.of("https://jakarta.ee/xml/ns/jakartaee", "http://xmlns.jcp.org/xml/ns/javaee",
                "http://java.sun.com/xml/ns/javaee", "http://java.sun.com/xml/ns/j2ee").contains(namespace))
            throw new IllegalArgumentException("Unsupported deployment descriptor namespace: " + namespace);
        var groups = new ArrayList<JspPropertyGroup>();
        for (var config : children(root)) if (name(config).equals("jsp-config")) {
            requireNamespace(config, namespace);
            for (var group : children(config)) {
                requireNamespace(group, namespace);
                if (!name(group).equals("jsp-property-group"))
                    throw new IllegalArgumentException("Unsupported jsp-config element: " + name(group));
                var patterns = new ArrayList<String>(); var preludes = new ArrayList<String>(); var codas = new ArrayList<String>();
                var properties = new LinkedHashMap<String, String>();
                for (var entry : children(group)) {
                    requireNamespace(entry, namespace);
                    String key = name(entry); String value = entry.getTextContent().trim();
                    switch (key) {
                        case "url-pattern" -> patterns.add(value);
                        case "include-prelude" -> preludes.add(value);
                        case "include-coda" -> codas.add(value);
                        case "description", "display-name", "icon" -> { }
                        default -> {
                            if (!PROPERTIES.contains(key)) throw new IllegalArgumentException("Unknown JSP property: " + key);
                            if (properties.putIfAbsent(key, value) != null) throw new IllegalArgumentException("Duplicate JSP property: " + key);
                        }
                    }
                }
                if (patterns.isEmpty()) throw new IllegalArgumentException("JSP property group needs a URL pattern");
                groups.add(new JspPropertyGroup(patterns, properties, preludes, codas));
            }
        }
        return List.copyOf(groups);
    }
    private static String name(Element element) { return element.getLocalName() == null ? element.getTagName() : element.getLocalName(); }
    private static void requireNamespace(Element element, String namespace) {
        if (!Objects.equals(namespace, element.getNamespaceURI()))
            throw new IllegalArgumentException("Foreign namespace in JSP configuration: " + element.getTagName());
    }
    private static List<Element> children(Element parent) {
        var result = new ArrayList<Element>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) if (node instanceof Element element) result.add(element);
        return result;
    }
}

