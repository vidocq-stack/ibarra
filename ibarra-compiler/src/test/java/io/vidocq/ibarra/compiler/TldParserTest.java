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

class TldParserTest {
    private TagLibraryDescriptor parse(String xml) { return new TldParser().parse("/WEB-INF/test.tld", xml.getBytes(StandardCharsets.UTF_8)); }

    @Test
    void attributeNamesUseIdentifierLexicalRulesWithoutRejectingJavaKeywords() {
        for (String name : List.of("boolean", "byte", "short", "long", "float", "double", "static", "class", "true", "null", "_", "$cash", "étiquette")) {
            var library = parse("<taglib><tag><name>x</name><tag-class>example.Handler</tag-class><attribute><name>" + name + "</name></attribute></tag></taglib>");
            assertEquals(name, library.tags().get("x").attributes().get(name).name());
        }
        for (String name : List.of("1value", "two words", "a.b", "a-b", "p:value"))
            assertThrows(PageParseException.class, () -> parse("<taglib><tag><name>x</name><tag-class>example.Handler</tag-class><attribute><name>"+name+"</name></attribute></tag></taglib>"));
    }

    @Test
    void directListenerDeclarationsAndUriAreRetainedAsImmutableMetadata() {
        var library = parse("""
                <taglib xmlns="https://jakarta.ee/xml/ns/jakartaee" version="3.0">
                  <uri>urn:test</uri>
                  <listener><description>Context setup</description><listener-class>example.Startup</listener-class></listener>
                  <listener><listener-class>example.Outer$Listener</listener-class></listener>
                  <tag><name>metadata</name><tag-class>example.Handler</tag-class><body-content>empty</body-content>
                    <tag-extension><listener><listener-class>example.Nested</listener-class></listener></tag-extension>
                  </tag>
                </taglib>
                """);
        assertEquals("/WEB-INF/test.tld", library.resourcePath());
        assertEquals("urn:test", library.uri());
        assertEquals(List.of("example.Startup", "example.Outer$Listener"), library.listenerClasses());
        assertThrows(UnsupportedOperationException.class, () -> library.listenerClasses().clear());
    }

    @Test
    void legacyDoctypeIsAcceptedWithoutFetchingItsExternalDefinition() {
        var library = parse("""
                <!DOCTYPE taglib SYSTEM "file:///unavailable/tld.dtd">
                <taglib><listener><listener-class>example.Startup</listener-class></listener></taglib>
                """);
        assertNull(library.uri());
        assertEquals(List.of("example.Startup"), library.listenerClasses());
    }

    @Test
    void invalidOrDuplicateDeclarationsHaveSourceDiagnostics() {
        for (String body : List.of(
                "<listener><listener-class>example.Bad()</listener-class></listener>",
                "<listener/>",
                "<listener><listener-class>example.A</listener-class><listener-class>example.B</listener-class></listener>",
                "<uri>urn:a</uri><uri>urn:b</uri>")) {
            var failure = assertThrows(PageParseException.class, () -> parse("<taglib>" + body + "</taglib>"));
            assertTrue(failure.getMessage().contains("/WEB-INF/test.tld"));
        }
        assertThrows(PageParseException.class, () -> parse("<taglib xmlns='urn:foreign'/>"));
        assertThrows(PageParseException.class, () -> parse("<taglib xmlns:x='urn:foreign'><x:listener><x:listener-class>example.A</x:listener-class></x:listener></taglib>"));
    }

    @Test
    void malformedXmlAndExternalEntitiesNeverBecomeListenerSourceCode() {
        assertThrows(PageParseException.class, () -> parse("<taglib><listener>"));
        assertThrows(PageParseException.class, () -> parse("""
                <!DOCTYPE taglib [<!ENTITY external SYSTEM "file:///unavailable/listener.txt">]>
                <taglib><listener><listener-class>&external;</listener-class></listener></taglib>
                """));
    }
}
