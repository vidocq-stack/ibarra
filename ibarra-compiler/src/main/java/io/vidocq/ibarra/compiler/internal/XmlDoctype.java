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

import io.vidocq.ibarra.compiler.Page;
import io.vidocq.ibarra.compiler.PageNode;
import io.vidocq.ibarra.compiler.PageParseException;
import io.vidocq.ibarra.compiler.PageParser;
import io.vidocq.ibarra.compiler.SourceLocation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Translation-time output metadata; identifiers are literals, never external resources. */
public record XmlDoctype(String declaration, SourceLocation location) {
    private static final List<String> ATTRIBUTES = List.of("doctype-root-element", "doctype-system", "doctype-public");

    public static XmlDoctype collect(Page page) {
        var values = new LinkedHashMap<String, String>();
        var locations = new LinkedHashMap<String, SourceLocation>();
        collect(page.nodes(), values, locations);
        if (!values.containsKey("doctype-system")) return new XmlDoctype("", null);
        String declaration = "<!DOCTYPE " + values.get("doctype-root-element")
                + (values.containsKey("doctype-public") ? " PUBLIC " + quoted(values.get("doctype-public"), locations.get("doctype-public")) : " SYSTEM")
                + " " + quoted(values.get("doctype-system"), locations.get("doctype-system")) + ">";
        return new XmlDoctype(declaration, locations.get("doctype-system"));
    }

    private static void collect(List<PageNode> nodes, Map<String, String> values, Map<String, SourceLocation> locations) {
        for (var node : nodes) if (node instanceof PageNode.Element element) {
            if (element.namespace().equals(PageParser.JSP_NAMESPACE)
                    && element.name().substring(element.name().lastIndexOf(':') + 1).equals("output")) {
                var attributes = element.attributes();
                boolean root = attributes.containsKey("doctype-root-element"), system = attributes.containsKey("doctype-system");
                if (root != system) throw new PageParseException(element.location(),
                        "doctype-root-element requires doctype-system and doctype-system requires doctype-root-element");
                if (attributes.containsKey("doctype-public") && !system)
                    throw new PageParseException(element.location(), "doctype-public requires doctype-system");
                for (String attribute : ATTRIBUTES) if (attributes.containsKey(attribute)) {
                    String value = attributes.get(attribute);
                    String previous = values.putIfAbsent(attribute, value);
                    if (previous != null && !previous.equals(value)) throw new PageParseException(element.location(),
                            "Conflicting " + attribute + " in translation unit");
                    locations.putIfAbsent(attribute, element.location());
                }
            }
            collect(element.children(), values, locations);
        }
    }

    private static String quoted(String literal, SourceLocation location) {
        if (!literal.contains("\"")) return "\"" + literal + "\"";
        if (!literal.contains("'")) return "'" + literal + "'";
        throw new PageParseException(location, "DOCTYPE identifier requires a quote delimiter absent from its literal value");
    }
}
