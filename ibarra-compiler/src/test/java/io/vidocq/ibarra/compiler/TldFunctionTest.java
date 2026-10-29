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
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TldFunctionTest {
    @Test void functionMetadataIsImmutableAndUsesDeclaredAliases() {
        var library=parse("<function><name>alias</name><function-class>example.Functions</function-class><function-signature>int actual(int)</function-signature></function>");
        assertEquals(new TldFunction("alias","example.Functions","int actual(int)"),library.functions().get("alias"));
        var functions=library.functions();
        assertThrows(UnsupportedOperationException.class,functions::clear);
    }
    @Test void malformedAndDuplicateFunctionDeclarationsFailTranslation() {
        for(String source:java.util.List.of("<function><name>x</name></function>",
                "<function><name>x</name><function-class>bad type</function-class><function-signature>int x()</function-signature></function>",
                "<function><name>x</name><function-class>example.F</function-class><function-signature>int x()</function-signature><unknown/></function>",
                "<function><name>x</name><name>y</name><function-class>example.F</function-class><function-signature>int x()</function-signature></function>"))
            assertThrows(PageParseException.class,()->parse(source));
        String function="<function><name>x</name><function-class>example.F</function-class><function-signature>int x()</function-signature></function>";
        assertThrows(PageParseException.class,()->parse(function+function));
    }
    private TagLibraryDescriptor parse(String body){return new TldParser().parse("/WEB-INF/functions.tld",("<taglib xmlns='https://jakarta.ee/xml/ns/jakartaee'>"+body+"</taglib>").getBytes(StandardCharsets.UTF_8));}
}
