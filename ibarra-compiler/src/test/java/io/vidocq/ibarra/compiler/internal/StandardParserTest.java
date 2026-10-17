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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.ibarra.compiler.PageNode;
import io.vidocq.ibarra.compiler.PageParseException;
import io.vidocq.ibarra.compiler.ParserOptions;
import java.util.List;
import org.junit.jupiter.api.Test;

class StandardParserTest {
    @Test
    void allowsRepeatedImportAttributesInPageDirective() {
        var nodes = parse("<%@ page import='java.util.Properties' import='java.util.Hashtable' %>");
        var directive = assertInstanceOf(PageNode.Directive.class, nodes.getFirst());

        assertEquals("java.util.Properties,java.util.Hashtable", directive.attributes().get("import"));
    }

    @Test
    void rejectsRepeatedPageAttributesOtherThanImport() {
        var failure = assertThrows(PageParseException.class,
                () -> parse("<%@ page language='java' language='java' %>"));

        assertTrue(failure.getMessage().contains("Duplicate attribute: language"));
    }

    private static List<PageNode> parse(String source) {
        return new StandardParser(new SourceDocument("/page.jsp", source), ParserOptions.DEFAULT).parse();
    }
}
