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

package io.vidocq.ibarra.compiler.internal;

import io.vidocq.ibarra.compiler.PageNode;
import io.vidocq.ibarra.compiler.PageParser;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Serializes the supported page syntax as the XML view consumed by tag library validators. */
public final class PageDataXmlView {
    private static final String JSP_NAMESPACE = PageParser.JSP_NAMESPACE;

    private PageDataXmlView() { }

    public static String serialize(List<PageNode> nodes, Map<String, String> taglibNamespaces) {
        var namespaces = new TreeMap<String, String>();
        namespaces.put("jsp", JSP_NAMESPACE);
        taglibNamespaces.forEach((prefix, uri) -> {
            if (uri != null && !uri.isEmpty()) namespaces.putIfAbsent(prefix, uri);
        });
        collectNamespaces(nodes, namespaces);

        List<PageNode> content = nodes;
        if (!nodes.isEmpty() && nodes.getFirst() instanceof PageNode.Element root
                && root.namespace().equals(JSP_NAMESPACE) && localName(root.name()).equals("root")) {
            content = root.children();
        }

        var xml = new StringBuilder("<jsp:root");
        namespaces.forEach((prefix, uri) -> xml.append(prefix.isEmpty() ? " xmlns=\"" : " xmlns:" + prefix + "=\"")
                .append(attribute(uri)).append('"'));
        xml.append(" version=\"2.0\" jsp:id=\"0\">");
        int[] id = {1};
        append(content, xml, id);
        return xml.append("</jsp:root>").toString();
    }

    private static void collectNamespaces(List<PageNode> nodes, Map<String, String> namespaces) {
        for (var node : nodes) {
            if (node instanceof PageNode.Directive directive && directive.name().equals("taglib")) {
                String prefix = directive.attributes().get("prefix");
                String uri = directive.attributes().get("uri");
                if (prefix != null && uri != null) namespaces.putIfAbsent(prefix, uri);
            } else if (node instanceof PageNode.Element element) {
                collectElementNamespaces(element, namespaces);
            }
        }
    }

    private static void collectElementNamespaces(PageNode.Element element, Map<String, String> namespaces) {
        element.attributes().forEach((name, value) -> {
            if (name.startsWith("xmlns:")) namespaces.putIfAbsent(name.substring(6), value);
            else if (name.equals("xmlns")) namespaces.putIfAbsent("", value);
        });
        if (!element.namespace().isEmpty() && !element.namespace().equals(JSP_NAMESPACE)) {
            String prefix = prefix(element.name());
            namespaces.putIfAbsent(prefix, element.namespace());
        }
        collectNamespaces(element.children(), namespaces);
    }

    private static void append(List<PageNode> nodes, StringBuilder xml, int[] id) {
        for (var node : nodes) {
            if (node instanceof PageNode.Text text) appendText(text.value(), xml, id);
            else if (node instanceof PageNode.RawText text) appendText(text.value(), xml, id);
            else if (node instanceof PageNode.Expression expression)
                appendText("${" + expression.code() + "}", xml, id);
            else if (node instanceof PageNode.Script script) appendScript(script, xml, id);
            else if (node instanceof PageNode.Directive directive) appendDirective(directive, xml, id);
            else if (node instanceof PageNode.Element element) appendElement(element, xml, id);
        }
    }

    private static void appendScript(PageNode.Script script, StringBuilder xml, int[] id) {
        String element = switch (script.kind()) {
            case DECLARATION -> "jsp:declaration";
            case SCRIPTLET -> "jsp:scriptlet";
            case EXPRESSION -> "jsp:expression";
        };
        xml.append('<').append(element).append(" jsp:id=\"").append(id[0]++).append("\">")
                .append(text(script.code())).append("</").append(element).append('>');
    }

    private static void appendDirective(PageNode.Directive directive, StringBuilder xml, int[] id) {
        if (directive.name().equals("taglib") || directive.name().equals("include")) return;
        appendElement("jsp:directive." + directive.name(), directive.attributes(), List.of(), xml, id);
    }

    private static void appendElement(PageNode.Element element, StringBuilder xml, int[] id) {
        if (element.namespace().equals(JSP_NAMESPACE) && localName(element.name()).equals("root"))
            append(element.children(), xml, id);
        else appendElement(element.name(), element.attributes(), element.children(), xml, id);
    }

    private static void appendText(String value, StringBuilder xml, int[] id) {
        if (!value.isEmpty()) xml.append("<jsp:text jsp:id=\"").append(id[0]++).append("\">")
                .append(text(value)).append("</jsp:text>");
    }

    private static void appendElement(String name, Map<String, String> attributes, List<PageNode> children,
            StringBuilder xml, int[] id) {
        xml.append('<').append(name);
        attributes.forEach((key, value) -> {
            if (!key.equals("jsp:id") && !key.equals("xmlns") && !key.startsWith("xmlns:"))
                xml.append(' ').append(key).append("=\"").append(attribute(value)).append('"');
        });
        xml.append(" jsp:id=\"").append(id[0]++).append('"');
        if (children.isEmpty()) xml.append("/>");
        else {
            xml.append('>');
            append(children, xml, id);
            xml.append("</").append(name).append('>');
        }
    }

    private static String prefix(String name) {
        int separator = name.indexOf(':');
        return separator < 0 ? "" : name.substring(0, separator);
    }

    private static String localName(String name) {
        int separator = name.indexOf(':');
        return separator < 0 ? name : name.substring(separator + 1);
    }

    private static String attribute(String value) {
        var expressions = java.util.regex.Pattern.compile("<%=\\s*(.*?)\\s*%>", java.util.regex.Pattern.DOTALL).matcher(value);
        var xmlValue = new StringBuilder();
        int start = 0;
        while (expressions.find()) {
            xmlValue.append(value, start, expressions.start()).append("%= ").append(expressions.group(1).trim()).append(" %");
            start = expressions.end();
        }
        xmlValue.append(value, start, value.length());
        return xmlValue.toString().replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")
                .replace("'", "&apos;").replace(">", "&gt;");
    }

    private static String text(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
