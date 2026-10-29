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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Standard-syntax lexer. Ordinary markup is preserved as template text. */
public final class StandardParser {
    private static final String TAGLIB = "taglib";
    private static final Set<String> DIRECTIVES = Set.of("page", "include", TAGLIB, "tag", "attribute", "variable");
    private static final String EL_IGNORED = "isELIgnored";
    private static final String DEFERRED_LITERAL = "deferredSyntaxAllowedAsLiteral";
    private static final String COMMENT_START = "<%--";
    private static final String DIRECTIVE_START = "<%@";
    private static final String SCRIPT_START = "<%";
    private static final String END_TAG_START = "</";
    private static final String EMPTY_TAG_END = "/>";
    private static final String JSP_BODY = "jsp:body";
    private final SourceDocument source;
    private final Map<String, String> bindings = new LinkedHashMap<>();
    private int cursor;
    private boolean elIgnored;
    private boolean deferredLiteral;
    private final boolean strictNamespaces;
    private final TagDependentBodies rawBodies;
    private final Map<String, SourceLocation> bindingOrigins = new LinkedHashMap<>();

    public StandardParser(SourceDocument source, ParserOptions options) {
        this(source, options, Map.of(), false);
    }

    public StandardParser(SourceDocument source, ParserOptions options, Map<String, String> unitBindings, boolean strictNamespaces) {
        this(source, options, unitBindings, strictNamespaces, null, Map.of());
    }

    public StandardParser(SourceDocument source, ParserOptions options, Map<String, String> unitBindings, boolean strictNamespaces,
            TagDependentBodies rawBodies, Map<String, SourceLocation> unitOrigins) {
        this.source = source;
        this.rawBodies = rawBodies;
        if (rawBodies != null) {
            bindings.putAll(rawBodies.bindings()); bindingOrigins.putAll(rawBodies.origins());
        }
        bindingOrigins.putAll(unitOrigins);
        this.strictNamespaces = strictNamespaces;
        elIgnored = options.elIgnored();
        deferredLiteral = options.deferredSyntaxAllowedAsLiteral();
        bindings.putAll(unitBindings);
        bindings.put("jsp", PageParser.JSP_NAMESPACE);
    }

    public List<PageNode> parse() {
        // Taglib directives apply to the complete translation unit, including earlier tags.
        directivesOnly();
        if (rawBodies != null) rawBodies.remember(bindings, bindingOrigins);
        cursor = 0;
        var parsed = nodes(null, 0);
        rejectJspRoot(parsed);
        return parsed;
    }

    private static void rejectJspRoot(List<PageNode> nodes) {
        for (var node : nodes) {
            if (node instanceof PageNode.Element element) {
                String localName = element.name().substring(element.name().lastIndexOf(':') + 1);
                if (element.namespace().equals(PageParser.JSP_NAMESPACE) && localName.equals("root"))
                    throw new PageParseException(element.location(), "jsp:root is only valid in XML syntax");
                rejectJspRoot(element.children());
            }
        }
    }

    /** Reads real directives for byte-encoding detection, without binding custom tags. */
    public List<PageNode.Directive> encodingDirectives() {
        var result = new ArrayList<PageNode.Directive>();
        while (cursor < source.text.length()) {
            if (!skipRawTag(result) && !skipExpression()) encodingToken(result);
        }
        return List.copyOf(result);
    }

    private void encodingToken(List<PageNode.Directive> result) {
        if (starts(COMMENT_START)) comment();
        else if (starts(DIRECTIVE_START)) {
            var directive = directive();
            binding(directive);
            result.add(directive);
            applyElSettings(directive);
        } else if (starts(SCRIPT_START)) script();
        else cursor++;
    }

    private void applyElSettings(PageNode.Directive directive) {
        var attributes = directive.attributes();
        if (attributes.containsKey(EL_IGNORED)) elIgnored = Boolean.parseBoolean(attributes.get(EL_IGNORED));
        if (attributes.containsKey(DEFERRED_LITERAL)) deferredLiteral = Boolean.parseBoolean(attributes.get(DEFERRED_LITERAL));
    }

    private void directivesOnly() {
        while (cursor < source.text.length()) {
            if (!skipRawTag() && !skipExpression()) directiveToken();
        }
    }

    private void directiveToken() {
        if (starts(COMMENT_START)) comment();
        else if (starts(DIRECTIVE_START)) unitDirective(directive());
        else if (starts(SCRIPT_START)) script();
        else cursor++;
    }

    private void unitDirective(PageNode.Directive directive) {
        binding(directive);
        if (directive.name().equals("page") || directive.name().equals("tag")) applyElSettings(directive);
        if (directive.name().equals(TAGLIB)) taglibBinding(directive);
    }

    private void taglibBinding(PageNode.Directive directive) {
        String prefix = directive.attributes().get("prefix");
        String uri = directive.attributes().get("uri");
        String tagdir = directive.attributes().get("tagdir");
        if (prefix == null || prefix.isBlank() || (uri == null) == (tagdir == null))
            throw error(cursor, "taglib requires a prefix and exactly one of uri or tagdir");
        if (prefix.equals("jsp") || prefix.contains(":"))
            throw error(cursor, "Invalid taglib prefix: " + prefix);
        String namespace = uri != null ? uri : "urn:jsptagdir:" + tagdir;
        String previous = bindings.putIfAbsent(prefix, namespace);
        if (previous != null && !previous.equals(namespace))
            throw error(cursor, "Conflicting taglib prefix: " + prefix);
        bindingOrigins.put(prefix, directive.location());
    }

    private List<PageNode> nodes(String closing, int opening) {
        var result = new ArrayList<PageNode>();
        int textStart = cursor;
        while (cursor < source.text.length()) {
            if (skipExpression()) continue;
            if (!starts(SCRIPT_START) && !recognizedTag()) cursor++;
            else {
                addText(result, textStart, cursor);
                if (token(result, closing)) return result;
                textStart = cursor;
            }
        }
        addText(result, textStart, cursor);
        if (closing != null) throw error(opening, "Unclosed element: " + closing);
        return result;
    }

    /** Reads one JSP token or custom element; whether it was the closing tag of {@code closing}. */
    private boolean token(List<PageNode> result, String closing) {
        int start = cursor;
        if (starts(COMMENT_START)) comment();
        else if (starts(DIRECTIVE_START)) {
            var directive = directive();
            binding(directive);
            result.add(directive);
        } else if (starts(SCRIPT_START)) result.add(script());
        else if (starts(END_TAG_START)) {
            close(closing, start);
            return true;
        } else result.add(element(start));
        return false;
    }

    private void close(String expected, int start) {
        cursor += 2;
        String name = identifier();
        space();
        expect(">");
        if (!name.equals(expected)) throw error(start, "Unexpected closing element: " + name);
    }

    private PageNode.Element element(int start) {
        cursor++;
        String name = identifier();
        var attributes = attributes(false);
        boolean selfClosing = starts(EMPTY_TAG_END);
        expect(selfClosing ? EMPTY_TAG_END : ">");
        List<PageNode> children;
        if (selfClosing) children = List.of();
        else children = raw(name, start) ? dependentNodes(name, start) : nodes(name, start);
        return new PageNode.Element(name, bindings.get(name.substring(0, name.indexOf(':'))),
                attributes, children, selfClosing, source.at(start));
    }

    /** Exposes only lexical library bindings for a translation-time included file. */
    void scanBindings(Map<String, String> target, Map<String, SourceLocation> origins) {
        directivesOnly(); target.putAll(bindings); origins.putAll(bindingOrigins);
    }

    private void binding(PageNode.Directive directive) {
        if (directive.name().equals(TAGLIB) && directive.attributes().containsKey("uri")) {
            String prefix = directive.attributes().get("prefix");
            if (prefix != null) {
                String previous = bindings.putIfAbsent(prefix, directive.attributes().get("uri"));
                if (previous != null && !previous.equals(directive.attributes().get("uri"))) throw error(cursor, "Conflicting taglib prefix: " + prefix);
                bindingOrigins.put(prefix, directive.location());
            }
        } else if (rawBodies != null && directive.name().equals("include"))
            rawBodies.includeBindings(directive, bindings, bindingOrigins, new ParserOptions(elIgnored, deferredLiteral));
    }

    private boolean raw(String name, int start) {
        int colon = name.indexOf(':');
        if (rawBodies == null || colon < 1) return false;
        String prefix = name.substring(0, colon);
        String uri = bindings.get(prefix);
        return uri != null && rawBodies.raw(name, uri, bindingOrigins.getOrDefault(prefix, source.at(start)), true);
    }

    private boolean skipRawTag() {
        return skipRawTag(null);
    }

    private boolean skipRawTag(List<PageNode.Directive> directives) {
        if (rawBodies == null || !starts("<") || starts(END_TAG_START) || starts(SCRIPT_START) || starts("<!")) return false;
        int saved = cursor; cursor++;
        String name;
        try { name = identifier(); } catch (PageParseException _) { cursor = saved; return false; }
        if (!raw(name, saved)) { cursor = saved; return false; }
        attributes(false);
        boolean empty = starts(EMPTY_TAG_END); expect(empty ? EMPTY_TAG_END : ">");
        if (!empty) probeDirectives(dependentNodes(name, saved), directives);
        return true;
    }

    private void probeDirectives(List<PageNode> nodes, List<PageNode.Directive> result) {
        for (var node : nodes) {
            if (node instanceof PageNode.Directive directive) {
                binding(directive);
                if (result != null) result.add(directive);
                applyElSettings(directive);
            } else if (node instanceof PageNode.Element element) probeDirectives(element.children(), result);
        }
    }

    private List<PageNode> dependentNodes(String name, int opening) {
        int first = cursor;
        while (first < source.text.length() && rawSpace(source.text.charAt(first))) first++;
        if (!envelopeAt(first)) return rawNodes(name, opening);
        cursor = first;
        var result = new ArrayList<PageNode>();
        while (cursor < source.text.length()) {
            space();
            int start = cursor;
            if (starts(END_TAG_START)) {
                close(name, start);
                return result;
            }
            if (!envelopeAt(cursor))
                throw error(cursor, "Named attributes and explicit bodies require jsp:body around content");
            result.add(envelope(start));
        }
        throw error(opening, "Unclosed tag-dependent element: " + name);
    }

    private boolean envelopeAt(int start) {
        return rawEnvelope(start, "jsp:attribute") || rawEnvelope(start, JSP_BODY);
    }

    private PageNode.Element envelope(int start) {
        cursor++;
        String wrapper = identifier();
        var attributes = attributes(false);
        boolean empty = starts(EMPTY_TAG_END);
        expect(empty ? EMPTY_TAG_END : ">");
        List<PageNode> children;
        if (empty) children = List.of();
        else children = wrapper.equals(JSP_BODY) ? rawNodes(wrapper, start) : nodes(wrapper, start);
        return new PageNode.Element(wrapper, PageParser.JSP_NAMESPACE, attributes, children, empty, source.at(start));
    }

    private List<PageNode> rawNodes(String name, int opening) {
        int bodyStart = cursor;
        while (cursor < source.text.length()) {
            int tag = source.text.indexOf(END_TAG_START + name, cursor);
            if (tag < 0) break;
            int finish = tag + name.length() + 2;
            while (finish < source.text.length() && rawSpace(source.text.charAt(finish))) finish++;
            if (finish < source.text.length() && source.text.charAt(finish) == '>') {
                String value = source.text.substring(bodyStart, tag); cursor = finish + 1;
                return value.isEmpty() ? List.of() : List.of(new PageNode.RawText(value, source.at(bodyStart)));
            }
            cursor = tag + 2;
        }
        throw error(opening, "Unclosed tag-dependent element: " + name);
    }

    private boolean rawEnvelope(int start, String name) {
        if (!source.text.startsWith("<" + name, start)) return false;
        int end = start + name.length() + 1;
        return end < source.text.length() && (rawSpace(source.text.charAt(end))
                || source.text.charAt(end) == '>' || source.text.charAt(end) == '/');
    }

    private static boolean rawSpace(int c) { return c == ' ' || c == '\t' || c == '\r' || c == '\n'; }

    private boolean recognizedTag() {
        if (!starts("<")) return false;
        int start = cursor + (starts(END_TAG_START) ? 2 : 1);
        int end = start;
        while (end < source.text.length() && nameChar(source.text.charAt(end))) end++;
        String name = source.text.substring(start, end);
        int colon = name.indexOf(':');
        if (colon <= 0 || colon == name.length() - 1) return false;
        String prefix = name.substring(0, colon);
        if (!bindings.containsKey(prefix) && strictNamespaces)
            throw error(cursor, "Undeclared namespace prefix: " + prefix);
        return bindings.containsKey(prefix);
    }

    private boolean skipExpression() {
        if (elIgnored) return false;
        if (starts("\\$") || starts("\\#")) { cursor += 2; return true; }
        if (!starts("${") && (deferredLiteral || !starts("#{"))) return false;
        int end = expressionEnd(cursor + 2);
        if (end < 0) return false;
        cursor = end;
        return true;
    }

    /** The offset after the brace closing an expression whose body starts at {@code from}, or -1 if unclosed. */
    private int expressionEnd(int from) {
        int i = from;
        int depth = 1;
        char quote = 0;
        while (i < source.text.length() && depth > 0) {
            char c = source.text.charAt(i++);
            if (quote != 0) {
                if (c == '\\' && i < source.text.length()) i++;
                else if (c == quote) quote = 0;
            } else if (c == '\'' || c == '"') quote = c;
            else if (c == '{') depth++;
            else if (c == '}') depth--;
        }
        return depth == 0 ? i : -1;
    }

    private PageNode.Directive directive() {
        int start = cursor;
        cursor += 3;
        space();
        String name = identifier();
        if (!DIRECTIVES.contains(name)) throw error(start, "Unknown directive: " + name);
        var attributes = attributes(true, name);
        expect("%>");
        return new PageNode.Directive(name, attributes, source.at(start));
    }

    private Map<String, String> attributes(boolean directive) {
        return attributes(directive, null);
    }

    private Map<String, String> attributes(boolean directive, String directiveName) {
        var result = new LinkedHashMap<String, String>();
        while (true) {
            int beforeSpace = cursor;
            space();
            if (starts(directive ? "%>" : ">") || (!directive && starts(EMPTY_TAG_END))) return result;
            if (cursor == beforeSpace) throw error(cursor, "Attributes must be separated by whitespace");
            int start = cursor;
            String name = identifier();
            space();
            expect("=");
            space();
            String attributeValue = attributeValue(start);
            String previous = result.putIfAbsent(name, attributeValue);
            if (previous != null) duplicate(result, name, previous, attributeValue, directiveName, start);
        }
    }

    private String attributeValue(int start) {
        if (cursor == source.text.length()) throw error(start, "Missing attribute value");
        char quote = source.text.charAt(cursor++);
        if (quote != '\'' && quote != '"') throw error(start, "Attribute values must be quoted");
        var value = new StringBuilder();
        while (cursor < source.text.length()) {
            char c = source.text.charAt(cursor++);
            if (c == quote) return value.toString();
            if (c == '\\' && cursor < source.text.length() && escapable(source.text.charAt(cursor))) value.append(source.text.charAt(cursor++));
            else value.append(c);
        }
        throw error(start, "Unclosed attribute value");
    }

    private static boolean escapable(char c) { return c == '\\' || c == '\'' || c == '"'; }

    /** Repeated page/tag imports are combined; identical tag-directive metadata is harmless; others are errors. */
    private void duplicate(Map<String, String> result, String name, String previous, String value, String directiveName, int start) {
        if (("page".equals(directiveName) || "tag".equals(directiveName)) && name.equals("import"))
            result.put(name, previous + "," + value);
        else if (!"tag".equals(directiveName) || !previous.equals(value))
            throw error(start, "Duplicate attribute: " + name);
    }

    private PageNode.Script script() {
        int start = cursor;
        cursor += 2;
        PageNode.ScriptKind kind = PageNode.ScriptKind.SCRIPTLET;
        if (starts("!")) { kind = PageNode.ScriptKind.DECLARATION; cursor++; }
        else if (starts("=")) { kind = PageNode.ScriptKind.EXPRESSION; cursor++; }
        var code = new StringBuilder();
        while (cursor < source.text.length()) {
            if (starts("%\\>")) { code.append("%>"); cursor += 3; }
            else if (starts("%>")) { cursor += 2; return new PageNode.Script(kind, code.toString(), source.at(start)); }
            else code.append(source.text.charAt(cursor++));
        }
        throw error(start, "Unclosed scripting element");
    }

    private void comment() {
        int start = cursor;
        int end = source.text.indexOf("--%>", cursor + 4);
        if (end < 0) throw error(start, "Unclosed JSP comment");
        cursor = end + 4;
    }

    private String identifier() {
        int start = cursor;
        while (cursor < source.text.length() && nameChar(source.text.charAt(cursor))) cursor++;
        if (cursor == start) throw error(start, "Expected a name");
        return source.text.substring(start, cursor);
    }

    private static boolean nameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == ':' || c == '.';
    }

    private void addText(List<PageNode> nodes, int start, int end) {
        if (end > start) nodes.add(new PageNode.Text(source.text.substring(start, end), source.at(start)));
    }

    private boolean starts(String value) { return source.text.startsWith(value, cursor); }
    private void space() { while (cursor < source.text.length() && Character.isWhitespace(source.text.charAt(cursor))) cursor++; }
    private void expect(String value) {
        if (!starts(value)) throw error(cursor, "Expected " + value);
        cursor += value.length();
    }
    private PageParseException error(int at, String message) { return new PageParseException(source.at(at), message); }
}

