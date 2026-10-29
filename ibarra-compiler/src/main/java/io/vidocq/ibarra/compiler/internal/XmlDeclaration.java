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
import java.util.List;
import java.util.Set;

/** Translation-unit declaration policy; output actions never produce body instructions. */
public final class XmlDeclaration {
    private static final Set<String> ATTRIBUTES = Set.of("omit-xml-declaration", "doctype-root-element",
            "doctype-public", "doctype-system");
    private Boolean omission;
    private String omissionLiteral;

    private XmlDeclaration() { }

    /** Whether to add a declaration before any template or executable page body. */
    public static boolean collect(Page page) {
        boolean root = hasDocumentRoot(page);
        return collect(page, page.syntax() == Page.Syntax.XML && !root);
    }

    /** Whether a tag file emits its declaration at each tag invocation. */
    public static boolean collectTagFile(Page page) {
        return collect(page, false);
    }

    private static boolean collect(Page page, boolean defaultEmission) {
        var declaration = new XmlDeclaration();
        declaration.collect(page.nodes());
        return declaration.omission == null ? defaultEmission : !declaration.omission;
    }

    private static boolean hasDocumentRoot(Page page) {
        return page.nodes().stream().anyMatch(node -> node instanceof PageNode.Element element
                && element.location().source().equals(page.source()) && jsp(element, "root"));
    }

    private void collect(List<PageNode> nodes) {
        for (var node : nodes) {
            if (node instanceof PageNode.Element element) {
                if (jsp(element, "output")) collectOutput(element);
                collect(element.children());
            }
        }
    }

    private void collectOutput(PageNode.Element element) {
        for (var name : element.attributes().keySet()) {
            if (!name.equals("xmlns") && !name.startsWith("xmlns:") && !ATTRIBUTES.contains(name))
                throw new PageParseException(element.location(), "Unsupported action attribute: " + name);
        }
        String value = element.attributes().get("omit-xml-declaration");
        if (value != null) {
            boolean parsed = switch (value) {
                case "yes", "true" -> true;
                case "no", "false" -> false;
                default -> throw new PageParseException(element.location(),
                        "omit-xml-declaration requires yes, no, true or false");
            };
            if (omissionLiteral != null && !omissionLiteral.equals(value))
                throw new PageParseException(element.location(), "Conflicting omit-xml-declaration values");
            omission = parsed;
            omissionLiteral = value;
        }
    }

    private static boolean jsp(PageNode.Element element, String local) {
        return element.namespace().equals(PageParser.JSP_NAMESPACE)
                && element.name().substring(element.name().lastIndexOf(':') + 1).equals(local);
    }
}
