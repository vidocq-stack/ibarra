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
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JspConfigurationParserTest {
    private List<JspPropertyGroup> parse(String xml) {
        return new JspConfigurationParser().parse("/WEB-INF/web.xml", xml.getBytes(StandardCharsets.UTF_8));
    }
    @Test
    void retainsGroupOrderPatternsIncludesAndScalarSettings() {
        var groups = parse("<web-app xmlns='https://jakarta.ee/xml/ns/jakartaee' version='6.1'><jsp-config>"
                + "<jsp-property-group><description>first</description><url-pattern>*.jsp</url-pattern><url-pattern>/views/*</url-pattern><el-ignored>true</el-ignored><include-prelude>/head.jspf</include-prelude><include-prelude>/second.jspf</include-prelude><include-coda>/tail.jspf</include-coda><buffer>16kb</buffer></jsp-property-group>"
                + "<jsp-property-group><url-pattern>/exact.jsp</url-pattern><el-ignored>false</el-ignored><is-xml>true</is-xml></jsp-property-group></jsp-config></web-app>");
        assertEquals(2, groups.size()); assertEquals(List.of("*.jsp", "/views/*"), groups.getFirst().patterns());
        assertEquals(Map.of("el-ignored", "true", "buffer", "16kb"), groups.getFirst().properties());
        assertEquals(List.of("/head.jspf", "/second.jspf"), groups.getFirst().preludes());
        assertEquals(List.of("/tail.jspf"), groups.getFirst().codas());
        var first = groups.getFirst();
        assertThrows(UnsupportedOperationException.class, () -> groups.add(first));
    }
    @Test
    void legacyDescriptorDefaultsToIgnoredElWithoutReadingExternalDtd() {
        var groups = parse("<!DOCTYPE web-app PUBLIC '-//Sun Microsystems, Inc.//DTD Web Application 2.3//EN' 'http://127.0.0.1:1/never.dtd'><web-app><display-name>legacy</display-name></web-app>");
        assertEquals(Map.of("el-ignored", "true"), groups.getFirst().properties());
        assertEquals(List.of("/*"), groups.getFirst().patterns());
        assertTrue(parse("<web-app version='6.1'/>").isEmpty());
    }
    @Test
    void rejectsWrongNamespacesMalformedGroupsAndEntitiesInValues() {
        for (String group : List.of("<url-pattern>*.jsp</url-pattern><el-ignored>true</el-ignored><el-ignored>false</el-ignored>",
                "<el-ignored>true</el-ignored>", "<url-pattern>*.jsp</url-pattern><foreign:el-ignored xmlns:foreign='urn:foreign'>true</foreign:el-ignored>",
                "<url-pattern>*.jsp</url-pattern><mystery>true</mystery>", "<url-pattern>*.jsp</url-pattern><include-prelude>relative.jspf</include-prelude>")) {
            assertThrows(PageParseException.class, () -> parse("<web-app><jsp-config><jsp-property-group>"+group+"</jsp-property-group></jsp-config></web-app>"));
        }
        assertThrows(PageParseException.class, () -> parse("<web-app xmlns='urn:foreign'/>"));
        assertThrows(PageParseException.class, () -> parse("<!DOCTYPE web-app [<!ENTITY value 'true'>]><web-app><jsp-config><jsp-property-group><url-pattern>*.jsp</url-pattern><el-ignored>&value;</el-ignored></jsp-property-group></jsp-config></web-app>"));
    }
}
