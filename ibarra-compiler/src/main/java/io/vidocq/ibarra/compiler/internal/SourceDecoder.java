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
    private static final String PAGE_ENCODING = "pageEncoding";
    private static final String PAGE_ENCODING_PROPERTY = "page-encoding";
    private static final String IS_XML_PROPERTY = "is-xml";
    private SourceDecoder() { }

    public static Decoded decode(String path, byte[] bytes, Map<String, String> properties) {
        return decode(path, bytes, properties, null);
    }

    static Decoded decode(String path, byte[] bytes, Map<String, String> properties, TagDependentBodies rawBodies) {
        Objects.requireNonNull(bytes);
        var start = new SourceLocation(path, 1, 1);
        var mark = mark(bytes);
        Charset configured = properties.containsKey(PAGE_ENCODING_PROPERTY) ? charset(properties.get(PAGE_ENCODING_PROPERTY), start) : null;
        Charset initial = charset(mark != null ? mark.encoding() : signature(bytes), start);
        var input = new Input(path, bytes, mark, initial, configured, rawBodies);
        String probe = new String(bytes, input.offset, bytes.length - input.offset, initial);
        Page.Syntax syntax = syntax(path, probe, properties);
        return syntax == Page.Syntax.XML ? decodeXml(input, probe) : decodeStandard(input, probe, properties);
    }

    /** The bytes being decoded and what their signature and the configuration say about them. */
    private static final class Input {
        private final String path;
        private final byte[] bytes;
        private final SourceLocation start;
        private final Mark mark;
        private final Charset initial;
        private final int offset;
        private final Charset configured;
        private final TagDependentBodies rawBodies;

        private Input(String path, byte[] bytes, Mark mark, Charset initial, Charset configured, TagDependentBodies rawBodies) {
            this.path = path;
            this.bytes = bytes;
            this.start = new SourceLocation(path, 1, 1);
            this.mark = mark;
            this.initial = initial;
            this.offset = mark == null ? 0 : mark.length();
            this.configured = configured;
            this.rawBodies = rawBodies;
        }
    }

    private static Page.Syntax syntax(String path, String probe, Map<String, String> properties) {
        if (!properties.containsKey(IS_XML_PROPERTY)) return PageParser.detectSyntax(path, probe);
        return TranslationUnit.bool(properties.get(IS_XML_PROPERTY), false) ? Page.Syntax.XML : Page.Syntax.STANDARD;
    }

    private static Decoded decodeXml(Input input, String probe) {
        var prolog = PROLOG.matcher(probe);
        Charset declared = prolog.find() ? charset(prolog.group(2), input.start) : null;
        if (declared != null && (input.mark != null || unicode(input.initial)) && !compatible(input.initial, declared))
            throw new PageParseException(input.start, "XML encoding conflicts with byte signature");
        String text = strict(input.path, input.bytes, input.offset, xmlCharset(input, declared));
        var parser = new XmlParser(new SourceDocument(input.path, text), input.rawBodies);
        var directives = new ArrayList<PageNode.Directive>();
        xmlDirectives(parser.parse(), directives);
        check(input.start, directives, input.configured, declared, null);
        return new Decoded(text, Page.Syntax.XML, null);
    }

    private static Charset xmlCharset(Input input, Charset declared) {
        if (input.mark != null) return input.initial;
        if (declared == null) return input.initial.equals(StandardCharsets.ISO_8859_1) ? StandardCharsets.UTF_8 : input.initial;
        return unicode(input.initial) && generic(declared) ? input.initial : declared;
    }

    private static Decoded decodeStandard(Input input, String probe, Map<String, String> properties) {
        String source = input.mark == null && input.initial.equals(StandardCharsets.ISO_8859_1) && input.configured != null
                ? new String(input.bytes, input.configured) : probe;
        var parserOptions = new ParserOptions(TranslationUnit.bool(properties.get("el-ignored"), false),
                TranslationUnit.bool(properties.get("deferred-syntax-allowed-as-literal"), false));
        var directives = new StandardParser(new SourceDocument(input.path, source), parserOptions,
                Map.of(), false, input.rawBodies, Map.of()).encodingDirectives();
        Charset directiveEncoding = directiveEncoding(directives);
        String contentEncoding = contentEncoding(directives);
        Charset detected = standardCharset(input, directiveEncoding, contentEncoding);
        String text = strict(input.path, input.bytes, input.offset, detected);
        directives = new StandardParser(new SourceDocument(input.path, text), parserOptions,
                Map.of(), false, input.rawBodies, Map.of()).encodingDirectives();
        check(input.start, directives, input.configured, null, input.mark == null ? null : detected);
        return new Decoded(text, Page.Syntax.STANDARD, input.mark == null ? null : detected.name());
    }

    /** The first pageEncoding of a page or tag directive; every declared value must name a charset. */
    private static Charset directiveEncoding(List<PageNode.Directive> directives) {
        Charset result = null;
        for (var directive : directives) {
            if (encodingDirective(directive) && directive.attributes().containsKey(PAGE_ENCODING)) {
                var candidate = charset(directive.attributes().get(PAGE_ENCODING), directive.location());
                if (result == null) result = candidate;
            }
        }
        return result;
    }

    /** The charset of the last page directive contentType that declares one. */
    private static String contentEncoding(List<PageNode.Directive> directives) {
        String result = null;
        for (var directive : directives) {
            if (directive.name().equals("page") && directive.attributes().containsKey("contentType"))
                result = contentCharset(directive.attributes().get("contentType"));
        }
        return result;
    }

    private static boolean encodingDirective(PageNode.Directive directive) {
        return directive.name().equals("page") || directive.name().equals("tag");
    }

    private static Charset standardCharset(Input input, Charset directiveEncoding, String contentEncoding) {
        if (input.mark != null) return input.initial;
        if (input.configured != null) return input.configured;
        if (directiveEncoding != null) return directiveEncoding;
        return contentEncoding != null ? charset(contentEncoding, input.start) : StandardCharsets.ISO_8859_1;
    }

    private static void check(SourceLocation start, List<PageNode.Directive> directives, Charset configured, Charset xml, Charset bom) {
        if (configured != null && xml != null && !compatible(configured, xml))
            throw new PageParseException(start,
                    "Configured page encoding conflicts with XML prolog");
        if (configured != null && bom != null && !compatible(configured, bom))
            throw new PageParseException(start,
                    "Configured page encoding conflicts with BOM");
        for (var directive : directives) if (directive.attributes().containsKey(PAGE_ENCODING)) {
            Charset value = charset(directive.attributes().get(PAGE_ENCODING), directive.location());
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
        if (matcher.group(1) != null) return matcher.group(1);
        return matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
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
        catch (IllegalArgumentException _) { throw new PageParseException(at, "Invalid page encoding: " + name); }
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

