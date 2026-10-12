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
package io.vidocq.ibarra.compiler;

import io.vidocq.ibarra.compiler.internal.DeploymentXml;
import io.vidocq.ibarra.compiler.internal.TranslationUnit;
import io.vidocq.ibarra.spi.PageDescriptor;
import java.util.*;
import org.w3c.dom.Element;

/** Build-only adapter for JSP property groups in the application's web.xml. */
public final class JspConfigurationParser {
    private static final Set<String> NAMESPACES = Set.of("", "https://jakarta.ee/xml/ns/jakartaee",
            "http://xmlns.jcp.org/xml/ns/javaee", "http://java.sun.com/xml/ns/javaee", "http://java.sun.com/xml/ns/j2ee");
    private static final Set<String> SCALARS = Set.of("el-ignored", "is-xml", "scripting-invalid", "page-encoding",
            "deferred-syntax-allowed-as-literal", "trim-directive-whitespaces", "default-content-type", "buffer",
            "error-on-el-not-found", "error-on-undeclared-namespace");

    public List<JspPropertyGroup> parse(String path, byte[] bytes) {
        var at = new SourceLocation(path, 1, 1);
        var document = DeploymentXml.read(path, bytes);
        var root = document.getDocumentElement();
        String namespace = Objects.toString(root.getNamespaceURI(), "");
        if (!DeploymentXml.name(root).equals("web-app") || !NAMESPACES.contains(namespace))
            throw new PageParseException(at, "Expected a standard web-app root");
        var groups = new ArrayList<JspPropertyGroup>();
        String version = root.getAttribute("version");
        var type = document.getDoctype();
        String publicId = type == null ? "" : Objects.toString(type.getPublicId(), "");
        if (Set.of("2.2", "2.3").contains(version) || publicId.contains("DTD Web Application 2.2//") || publicId.contains("DTD Web Application 2.3//"))
            groups.add(new JspPropertyGroup(List.of("/*"), Map.of("el-ignored", "true"), List.of(), List.of()));
        boolean seenConfig = false;
        for (var config : DeploymentXml.children(root)) if (DeploymentXml.name(config).equals("jsp-config")) {
            requireNamespace(config, namespace, at);
            if (seenConfig) throw new PageParseException(at, "Duplicate jsp-config");
            seenConfig = true;
            for (var group : DeploymentXml.children(config)) {
                requireNamespace(group, namespace, at);
                if (DeploymentXml.name(group).equals("taglib")) continue;
                if (!DeploymentXml.name(group).equals("jsp-property-group")) throw new PageParseException(at, "Unknown jsp-config element");
                var patterns = new ArrayList<String>(); var properties = new LinkedHashMap<String, String>();
                var preludes = new ArrayList<String>(); var codas = new ArrayList<String>();
                for (var field : DeploymentXml.children(group)) {
                    requireNamespace(field, namespace, at);
                    String name = DeploymentXml.name(field);
                    if (Set.of("description", "display-name", "icon").contains(name)) continue;
                    for (var node = field.getFirstChild(); node != null; node = node.getNextSibling())
                        if (node.getNodeType() == org.w3c.dom.Node.ENTITY_REFERENCE_NODE || node instanceof Element)
                            throw new PageParseException(at, "JSP configuration values must be plain text");
                    String value = field.getTextContent().trim();
                    if (value.isEmpty()) throw new PageParseException(at, "Empty JSP configuration value: " + name);
                    switch (name) {
                        case "url-pattern" -> patterns.add(value);
                        case "include-prelude", "include-coda" -> {
                            try { PageDescriptor.validatePath(value); }
                            catch (IllegalArgumentException failure) { throw new PageParseException(at, "Invalid implicit include path: " + value, failure); }
                            (name.equals("include-prelude") ? preludes : codas).add(value);
                        }
                        default -> {
                            if (!SCALARS.contains(name) || properties.putIfAbsent(name, value) != null)
                                throw new PageParseException(at, "Unknown or duplicate JSP property: " + name);
                        }
                    }
                }
                if (patterns.isEmpty()) throw new PageParseException(at, "JSP property group requires url-pattern");
                groups.add(new JspPropertyGroup(patterns, properties, preludes, codas));
            }
        }
        var result = List.copyOf(groups);
        TranslationUnit.validateConfiguration(new GenerationOptions(result, Map.of()), at);
        return result;
    }

    private static void requireNamespace(Element element, String namespace, SourceLocation at) {
        if (!Objects.toString(element.getNamespaceURI(), "").equals(namespace))
            throw new PageParseException(at, "Foreign JSP configuration namespace");
    }
}
