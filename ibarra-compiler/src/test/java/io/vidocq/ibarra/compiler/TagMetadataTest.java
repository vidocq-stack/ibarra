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

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TagMetadataTest {
    private TagLibraryDescriptor parse(String body) {
        return new TldParser().parse("/WEB-INF/tags.tld", ("<taglib xmlns='https://jakarta.ee/xml/ns/jakartaee'>"
                + body + "</taglib>").getBytes(StandardCharsets.UTF_8));
    }
    @Test
    void handlerAndAttributeMetadataAreImmutableAndHaveSpecificationDefaults() {
        var library = parse("""
                <uri>urn:test</uri><tag><name>repeat</name><tag-class>example.Repeat</tag-class>
                <body-content>scriptless</body-content><dynamic-attributes>true</dynamic-attributes>
                <attribute><name>count</name><type>int</type><required>true</required><rtexprvalue>true</rtexprvalue></attribute>
                <attribute><name>label</name></attribute><attribute><name>runtime</name><rtexprvalue>true</rtexprvalue></attribute></tag>
                """);
        var tag = library.tags().get("repeat");
        assertEquals("example.Repeat", tag.handlerClass());
        assertEquals("scriptless", tag.bodyContent());
        assertTrue(tag.dynamicAttributes());
        assertEquals("int", tag.attributes().get("count").type());
        assertTrue(tag.attributes().get("count").required());
        assertTrue(tag.attributes().get("count").runtimeExpression());
        assertEquals("java.lang.String", tag.attributes().get("label").type());
        assertFalse(tag.attributes().get("label").required());
        assertEquals("java.lang.Object", tag.attributes().get("runtime").type());
        assertThrows(UnsupportedOperationException.class, () -> library.tags().clear());
        assertThrows(UnsupportedOperationException.class, () -> tag.attributes().clear());
    }
    @Test
    void fragmentAttributesAndUnsupportedSemanticsRemainVisibleToTranslation() {
        var library = parse("""
                <validator><validator-class>example.Validator</validator-class></validator>
                <tag><name>fragment</name><tag-class>example.Fragment</tag-class><body-content>empty</body-content>
                  <attribute><name>header</name><fragment>true</fragment></attribute>
                  <variable><name-given>value</name-given></variable>
                </tag>
                """);
        assertTrue(library.validationRequired());
        assertTrue(library.tags().get("fragment").unsupportedMetadata());
        var attribute = library.tags().get("fragment").attributes().get("header");
        assertTrue(attribute.fragment());
        assertEquals("jakarta.servlet.jsp.tagext.JspFragment", attribute.type());
        assertTrue(attribute.runtimeExpression());
    }
    @Test
    void malformedOrDuplicateDefinitionsFailWithTheTldResourceLocation() {
        for (String body : List.of(
                "<tag><name>x</name></tag>",
                "<tag><name>x</name><tag-class>example.X</tag-class><body-content>invalid</body-content></tag>",
                "<tag><name>x</name><tag-class>example.X</tag-class><attribute><name>a</name><required>maybe</required></attribute></tag>",
                "<tag><name>x</name><tag-class>example.X</tag-class><attribute><name>a</name></attribute><attribute><name>a</name></attribute></tag>",
                "<tag><name>x</name><tag-class>example.X</tag-class></tag><tag><name>x</name><tag-class>example.Y</tag-class></tag>")) {
            var failure = assertThrows(PageParseException.class, () -> parse(body));
            assertTrue(failure.getMessage().contains("/WEB-INF/tags.tld"));
        }
    }
}
