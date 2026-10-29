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

import io.vidocq.ibarra.compiler.internal.SourceDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SourceDecodingTest {
    private SourceDecoder.Decoded decode(String path, byte[] bytes, Map<String, String> properties) {
        return SourceDecoder.decode(path, bytes, properties);
    }

    @Test
    void standardDefaultsAndDeclarationsChooseTheActualByteEncoding() {
        assertEquals("é", decode("/p.jsp", new byte[] {(byte) 0xe9}, Map.of()).text());
        String source = "<%@ page pageEncoding='UTF-8' %>你好";
        assertEquals(source, decode("/p.jsp", source.getBytes(StandardCharsets.UTF_8), Map.of()).text());
        source = "<%@ page contentType='text/plain; charset=UTF-8' %>你好";
        assertEquals(source, decode("/p.jsp", source.getBytes(StandardCharsets.UTF_8), Map.of()).text());
        assertEquals("你好", decode("/p.jsp", "你好".getBytes(StandardCharsets.UTF_8), Map.of("page-encoding", "UTF-8")).text());
    }

    @Test
    void commentsAndElStringsCannotSupplyEncodingDeclarations() {
        String source = "<%-- <%@ page pageEncoding='UTF-8' %> --%>" + "$" + "{\"<%@ page pageEncoding='UTF-8' %>\"}é";
        assertEquals(source, decode("/p.jsp", source.getBytes(StandardCharsets.ISO_8859_1), Map.of()).text());
    }

    @Test
    void allFiveUnicodeBomsAreRemovedAndMatchedWithAliases() {
        String[] names = {"UTF-8", "UTF-16BE", "UTF-16LE", "UTF-32BE", "UTF-32LE"};
        byte[][] marks = {{(byte)0xef,(byte)0xbb,(byte)0xbf}, {(byte)0xfe,(byte)0xff},
                {(byte)0xff,(byte)0xfe}, {0,0,(byte)0xfe,(byte)0xff}, {(byte)0xff,(byte)0xfe,0,0}};
        for (int i = 0; i < names.length; i++) {
            var payload = "é😀".getBytes(Charset.forName(names[i]));
            byte[] bytes = new byte[marks[i].length + payload.length];
            System.arraycopy(marks[i], 0, bytes, 0, marks[i].length);
            System.arraycopy(payload, 0, bytes, marks[i].length, payload.length);
            var decoded = decode("/p.jsp", bytes, Map.of());
            assertEquals("é😀", decoded.text(), names[i]);
            assertEquals(names[i], decoded.responseEncoding());
        }
    }

    @Test
    void bomAndDirectiveOrConfigurationConflictsAreFatal() {
        String source = "\ufeff<%@ page pageEncoding='ISO-8859-1' %>é";
        byte[] conflicting = source.getBytes(StandardCharsets.UTF_8);
        Map<String, String> none = Map.of();
        assertThrows(PageParseException.class, () -> decode("/p.jsp", conflicting, none));
        byte[] bom = "\ufefftext".getBytes(StandardCharsets.UTF_8);
        var configured = Map.of("page-encoding", "ISO-8859-1");
        assertThrows(PageParseException.class, () -> decode("/p.jsp", bom, configured));
        String generic = "\ufeff<%@ page pageEncoding='UTF-16' %>é";
        assertTrue(decode("/p.jsp", generic.getBytes(StandardCharsets.UTF_16LE), Map.of()).text().endsWith("é"));
    }

    @Test
    void xmlPrologAndUnicodeSignaturesSelectTheEncoding() {
        for (String name : new String[] {"ISO-8859-1", "UTF-8", "UTF-16BE", "UTF-16LE", "UTF-32BE", "UTF-32LE", "IBM037"}) {
            String source = "<?xml version='1.0' encoding='" + name + "'?><p>é</p>";
            assertEquals(source, decode("/p.jspx", source.getBytes(Charset.forName(name)), Map.of()).text(), name);
        }
        assertEquals("<p>你好</p>", decode("/p.jspx", "<p>你好</p>".getBytes(StandardCharsets.UTF_8), Map.of()).text());
    }

    @Test
    void ebcdicSignatureIsAProbeAndDoesNotOverrideTheNamedVariant() {
        String source = "<?xml version='1.0' encoding='IBM500'?><p>[é]</p>";
        assertEquals(source, decode("/p.jspx", source.getBytes(Charset.forName("IBM500")), Map.of()).text());
    }

    @Test
    void jspRootDetectionUsesDecodedUnicodeBytesAndConfiguredSyntaxWins() {
        String source = "<?xml version='1.0' encoding='UTF-16LE'?><j:root xmlns:j='" + PageParser.JSP_NAMESPACE + "' version='4.0'/>";
        assertEquals(Page.Syntax.XML, decode("/p.jsp", source.getBytes(StandardCharsets.UTF_16LE), Map.of()).syntax());
        assertEquals(Page.Syntax.STANDARD, decode("/p.jspx", "plain".getBytes(StandardCharsets.UTF_8), Map.of("is-xml", "false")).syntax());
    }

    @Test
    void xmlDeclaredEncodingMustMatchConfiguredAndPageEncodings() {
        String source = "<?xml version='1.0' encoding='UTF-8'?><j:root xmlns:j='" + PageParser.JSP_NAMESPACE
                + "' version='4.0'>\n<j:directive.page pageEncoding='ISO-8859-1'/></j:root>";
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        Map<String, String> none = Map.of();
        var error = assertThrows(PageParseException.class, () -> decode("/p.jspx", bytes, none));
        assertEquals(2, error.location().line());
        byte[] declared = "<?xml version='1.0' encoding='UTF-8'?><p/>".getBytes(StandardCharsets.UTF_8);
        var configured = Map.of("page-encoding", "ISO-8859-1");
        assertThrows(PageParseException.class, () -> decode("/p.jspx", declared, configured));
    }

    @Test
    void malformedInputIsRejectedWithoutReplacementCharacters() {
        byte[] malformed = {'<','p','>',(byte)0xc3,'(','<','/','p','>'};
        Map<String, String> none = Map.of();
        var error = assertThrows(PageParseException.class, () -> decode("/p.jspx", malformed, none));
        assertEquals("/p.jspx", error.location().source());
        assertTrue(error.getMessage().contains("UTF-8"));
    }

    @Test
    void xmlDefaultEncodingDoesNotComeFromThePropertyGroup() {
        String source = "<p>你好</p>";
        assertEquals(source, decode("/p.jspx", source.getBytes(StandardCharsets.UTF_8), Map.of("page-encoding", "windows-1252")).text());
    }

    @Test
    void xmlPrologCannotContradictUnicodeByteOrder() {
        String source = "<?xml version='1.0' encoding='UTF-16BE'?><p/>";
        byte[] bytes = source.getBytes(StandardCharsets.UTF_16LE);
        Map<String, String> none = Map.of();
        assertThrows(PageParseException.class, () -> decode("/p.jspx", bytes, none));
    }

    @Test
    void configurationConflictsRetainTheRequestedResourceName() {
        byte[] declared = "<?xml version='1.0' encoding='UTF-8'?><p/>".getBytes(StandardCharsets.UTF_8);
        var configured = Map.of("page-encoding", "ISO-8859-1");
        var error = assertThrows(PageParseException.class, () -> decode("/encoded.jspx", declared, configured));
        assertEquals("/encoded.jspx", error.location().source());
    }

    @Test
    void encodingDirectiveScanningRespectsLiteralElPolicies() {
        String ignored = "${x <%@ page pageEncoding='UTF-8' %>}你好";
        assertEquals(ignored, decode("/p.jsp", ignored.getBytes(StandardCharsets.UTF_8), Map.of("el-ignored", "true")).text());
        String deferred = "#{x <%@ page pageEncoding='UTF-8' %>}你好";
        assertEquals(deferred, decode("/p.jsp", deferred.getBytes(StandardCharsets.UTF_8), Map.of("deferred-syntax-allowed-as-literal", "true")).text());
        String directive = "<%@ page deferredSyntaxAllowedAsLiteral='true' %>" + deferred;
        assertEquals(directive, decode("/p.jsp", directive.getBytes(StandardCharsets.UTF_8), Map.of()).text());
    }
}

