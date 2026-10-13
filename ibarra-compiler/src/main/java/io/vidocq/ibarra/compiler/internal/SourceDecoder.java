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

import io.vidocq.ibarra.compiler.*;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.regex.Pattern;

/** Build-side byte decoding. No resources or external XML documents are read. */
public final class SourceDecoder {
    public record Decoded(String text, Page.Syntax syntax, String responseEncoding) { }
    private record Mark(String encoding, int length) { }
    private static final Pattern PROLOG = Pattern.compile(
            "^<\\?xml\\s+[^?]*\\bencoding\\s*=\\s*(['\"])([^'\"]+)\\1", Pattern.DOTALL);
    private static final Pattern CHARSET = Pattern.compile(
            "(?:^|;)\\s*charset\\s*=\\s*(?:\"([^\"]+)\"|'([^']+)'|([^;\\s]+))", Pattern.CASE_INSENSITIVE);
    private SourceDecoder() { }

    public static Decoded decode(String path, byte[] bytes, Map<String, String> properties) {
        return decode(path, bytes, properties, null);
    }

    static Decoded decode(String path, byte[] bytes, Map<String, String> properties, TagDependentBodies rawBodies) {
        Objects.requireNonNull(bytes);
        var start = new SourceLocation(path, 1, 1);
        var mark = mark(bytes);
        Charset configured = properties.containsKey("page-encoding") ? charset(properties.get("page-encoding"), start) : null;
        Charset initial = charset(mark != null ? mark.encoding() : signature(bytes), start);
        int offset = mark == null ? 0 : mark.length();
        String probe = new String(bytes, offset, bytes.length - offset, initial);
        Page.Syntax syntax = properties.containsKey("is-xml")
                ? (TranslationUnit.bool(properties.get("is-xml"), false) ? Page.Syntax.XML : Page.Syntax.STANDARD)
                : PageParser.detectSyntax(path, probe);
        if (syntax == Page.Syntax.XML) {
            var prolog = PROLOG.matcher(probe);
            Charset declared = prolog.find() ? charset(prolog.group(2), start) : null;
            Charset detected = mark != null ? initial : declared != null ? declared
                    : initial.equals(StandardCharsets.ISO_8859_1) ? StandardCharsets.UTF_8 : initial;
            if (declared != null && (mark != null || unicode(initial)) && !compatible(initial, declared))
                throw new PageParseException(start, "XML encoding conflicts with byte signature");
            if (declared != null && unicode(initial) && generic(declared)) detected = initial;
            String text = strict(path, bytes, offset, detected);
            var parser = new XmlParser(new SourceDocument(path, text), rawBodies);
            var directives = new ArrayList<PageNode.Directive>();
            xmlDirectives(parser.parse(), directives);
            check(start, directives, configured, declared, null);
            return new Decoded(text, syntax, null);
        }
        if (mark == null && initial.equals(StandardCharsets.ISO_8859_1) && configured != null)
            probe = new String(bytes, configured);
        var parserOptions = new ParserOptions(TranslationUnit.bool(properties.get("el-ignored"), false),
                TranslationUnit.bool(properties.get("deferred-syntax-allowed-as-literal"), false));
        var directives = new StandardParser(new SourceDocument(path, probe), parserOptions,
                Map.of(), false, rawBodies, Map.of()).encodingDirectives();
        Charset directiveEncoding = null;
        String contentEncoding = null;
        for (var directive : directives) if (directive.name().equals("page") || directive.name().equals("tag")) {
            if (directive.attributes().containsKey("pageEncoding")) {
                var candidate = charset(directive.attributes().get("pageEncoding"), directive.location());
                if (directiveEncoding == null) directiveEncoding = candidate;
            }
            if (directive.name().equals("page") && directive.attributes().containsKey("contentType"))
                contentEncoding = contentCharset(directive.attributes().get("contentType"));
        }
        Charset detected = mark != null ? initial : configured != null ? configured : directiveEncoding != null
                ? directiveEncoding : contentEncoding != null ? charset(contentEncoding, start) : StandardCharsets.ISO_8859_1;
        String text = strict(path, bytes, offset, detected);
        directives = new StandardParser(new SourceDocument(path, text), parserOptions,
                Map.of(), false, rawBodies, Map.of()).encodingDirectives();
        check(start, directives, configured, null, mark == null ? null : detected);
        return new Decoded(text, syntax, mark == null ? null : detected.name());
    }

    private static void check(SourceLocation start, List<PageNode.Directive> directives, Charset configured, Charset xml, Charset bom) {
        if (configured != null && xml != null && !compatible(configured, xml))
            throw new PageParseException(start,
                    "Configured page encoding conflicts with XML prolog");
        if (configured != null && bom != null && !compatible(configured, bom))
            throw new PageParseException(start,
                    "Configured page encoding conflicts with BOM");
        for (var directive : directives) if (directive.attributes().containsKey("pageEncoding")) {
            Charset value = charset(directive.attributes().get("pageEncoding"), directive.location());
            if ((configured != null && !compatible(configured, value)) || (xml != null && !compatible(xml, value))
                    || (bom != null && !compatible(bom, value)))
                throw new PageParseException(directive.location(), "pageEncoding conflicts with configuration, XML prolog or BOM");
        }
    }

    private static void xmlDirectives(List<PageNode> nodes, List<PageNode.Directive> result) {
        for (var node : nodes) if (node instanceof PageNode.Element element) {
            String local = element.name().substring(element.name().lastIndexOf(':') + 1);
            if (element.namespace().equals(PageParser.JSP_NAMESPACE) && local.equals("directive.page"))
                result.add(new PageNode.Directive("page", element.attributes(), element.location()));
            xmlDirectives(element.children(), result);
        }
    }

    public static String contentCharset(String contentType) {
        var matcher = CHARSET.matcher(contentType);
        if (!matcher.find()) return null;
        return matcher.group(1) != null ? matcher.group(1) : matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
    }

    public static boolean compatible(Charset left, Charset right) {
        if (left.equals(right)) return true;
        return (generic(left) && right.name().startsWith(left.name()))
                || (generic(right) && left.name().startsWith(right.name()));
    }
    private static boolean generic(Charset value) { return value.name().equals("UTF-16") || value.name().equals("UTF-32"); }
    private static boolean unicode(Charset value) { return value.name().startsWith("UTF-16") || value.name().startsWith("UTF-32"); }
    private static Charset charset(String name, SourceLocation at) {
        try { return Charset.forName(name); }
        catch (IllegalArgumentException failure) { throw new PageParseException(at, "Invalid page encoding: " + name); }
    }

    private static String strict(String path, byte[] bytes, int offset, Charset charset) {
        var decoder = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        var input = ByteBuffer.wrap(bytes, offset, bytes.length - offset);
        var output = CharBuffer.allocate(Math.max(1, (int) Math.ceil(input.remaining() * decoder.maxCharsPerByte())));
        var result = decoder.decode(input, output, true);
        if (!result.isError()) result = decoder.flush(output);
        output.flip();
        String text = output.toString();
        if (result.isError()) throw new PageParseException(new SourceLocation(path, 1, 1).advance(text),
                "Invalid byte sequence for " + charset.name() + " at byte " + input.position());
        return text;
    }

    private static Mark mark(byte[] bytes) {
        if (starts(bytes, 0, 0, 0xfe, 0xff)) return new Mark("UTF-32BE", 4);
        if (starts(bytes, 0xff, 0xfe, 0, 0)) return new Mark("UTF-32LE", 4);
        if (starts(bytes, 0xef, 0xbb, 0xbf)) return new Mark("UTF-8", 3);
        if (starts(bytes, 0xfe, 0xff)) return new Mark("UTF-16BE", 2);
        if (starts(bytes, 0xff, 0xfe)) return new Mark("UTF-16LE", 2);
        return null;
    }
    private static String signature(byte[] bytes) {
        if (starts(bytes, 0, 0, 0, 0x3c)) return "UTF-32BE";
        if (starts(bytes, 0x3c, 0, 0, 0)) return "UTF-32LE";
        if (starts(bytes, 0, 0x3c, 0, 0x3f)) return "UTF-16BE";
        if (starts(bytes, 0x3c, 0, 0x3f, 0)) return "UTF-16LE";
        if (starts(bytes, 0x4c, 0x6f, 0xa7, 0x94)) return "IBM037";
        return "ISO-8859-1";
    }
    private static boolean starts(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if ((bytes[i] & 0xff) != prefix[i]) return false;
        return true;
    }
}

