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
import java.util.*;
import javax.lang.model.SourceVersion;
import org.w3c.dom.*;

public final class TldParser {
    private static final Set<String> NAMESPACES = Set.of("", "https://jakarta.ee/xml/ns/jakartaee",
            "http://xmlns.jcp.org/xml/ns/javaee", "http://java.sun.com/xml/ns/javaee", "http://java.sun.com/xml/ns/j2ee");

    /** Reads listener metadata only; tag-handler translation is a later milestone. */
    public TagLibraryDescriptor parse(String path, byte[] bytes) {
        var at = new SourceLocation(path, 1, 1);
        var root = DeploymentXml.read(path, bytes).getDocumentElement();
        String namespace = namespace(root);
        if (!"taglib".equals(root.getLocalName()) || !NAMESPACES.contains(namespace))
            throw new PageParseException(at, "Expected a standard taglib root");
        String uri = null;
        var listeners = new ArrayList<String>();
        for (var child : DeploymentXml.children(root)) {
            String name = child.getLocalName();
            if (!name.equals("uri") && !name.equals("listener")) continue;
            requireNamespace(child, namespace, at);
            if (name.equals("uri")) {
                if (uri != null) throw new PageParseException(at, "Duplicate taglib URI");
                uri = child.getTextContent().trim();
                if (uri.isEmpty()) throw new PageParseException(at, "Empty taglib URI");
            } else {
                String type = null;
                for (var field : DeploymentXml.children(child)) {
                    requireNamespace(field, namespace, at);
                    if (field.getLocalName().equals("description")) continue;
                    if (!field.getLocalName().equals("listener-class") || type != null)
                        throw new PageParseException(at, "Expected one listener-class per TLD listener");
                    type = field.getTextContent().trim();
                }
                if (type == null || !SourceVersion.isName(type))
                    throw new PageParseException(at, "Invalid or missing TLD listener class");
                listeners.add(type);
            }
        }
        return new TagLibraryDescriptor(path, uri, listeners);
    }

    private static String namespace(Element element) { return Objects.toString(element.getNamespaceURI(), ""); }
    private static void requireNamespace(Element element, String namespace, SourceLocation at) {
        if (!namespace(element).equals(namespace)) throw new PageParseException(at, "Foreign namespace in TLD listener metadata");
    }
}
