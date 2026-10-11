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

import io.vidocq.ibarra.compiler.PageParseException;
import io.vidocq.ibarra.compiler.SourceLocation;
import java.io.ByteArrayInputStream;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import org.xml.sax.*;

/** Secure build-only XML reader; external declarations are never resolved or expanded. */
public final class DeploymentXml {
    private DeploymentXml() { }
    public static Document read(String path, byte[] bytes) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new java.io.StringReader("")));
            builder.setErrorHandler(new ErrorHandler() {
                public void warning(SAXParseException failure) { }
                public void error(SAXParseException failure) throws SAXException { throw failure; }
                public void fatalError(SAXParseException failure) throws SAXException { throw failure; }
            });
            return builder.parse(new ByteArrayInputStream(bytes));
        } catch (Exception failure) {
            var at = failure instanceof SAXParseException xml
                    ? new SourceLocation(path, xml.getLineNumber(), xml.getColumnNumber()) : new SourceLocation(path, 1, 1);
            throw new PageParseException(at, "Invalid XML deployment metadata", failure);
        }
    }
    public static List<Element> children(Element element) {
        var children = new ArrayList<Element>();
        for (Node n = element.getFirstChild(); n != null; n = n.getNextSibling()) if (n instanceof Element child) children.add(child);
        return children;
    }
}
