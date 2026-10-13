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
    private static final Set<String> DIRECTIVES = Set.of("page", "include", "taglib", "tag", "attribute", "variable");
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
        return nodes(null, 0);
    }

    /** Reads real directives for byte-encoding detection, without binding custom tags. */
    public List<PageNode.Directive> encodingDirectives() {
        var result = new ArrayList<PageNode.Directive>();
        while (cursor < source.text.length()) {
            if (skipRawTag()) continue;
            if (skipExpression()) continue;
            if (starts("<%--")) comment();
            else if (starts("<%@")) {
                var directive = directive();
                binding(directive);
                result.add(directive);
                if (directive.attributes().containsKey("isELIgnored"))
                    elIgnored = Boolean.parseBoolean(directive.attributes().get("isELIgnored"));
                if (directive.attributes().containsKey("deferredSyntaxAllowedAsLiteral"))
                    deferredLiteral = Boolean.parseBoolean(directive.attributes().get("deferredSyntaxAllowedAsLiteral"));
            } else if (starts("<%")) script();
            else cursor++;
        }
        return List.copyOf(result);
    }

    private void directivesOnly() {
        while (cursor < source.text.length()) {
            if (skipRawTag()) continue;
            if (skipExpression()) continue;
            if (starts("<%--")) { comment(); continue; }
            if (starts("<%@")) {
                var directive = directive();
                binding(directive);
                if (directive.name().equals("page") || directive.name().equals("tag")) {
                    if (directive.attributes().containsKey("isELIgnored"))
                        elIgnored = Boolean.parseBoolean(directive.attributes().get("isELIgnored"));
                    if (directive.attributes().containsKey("deferredSyntaxAllowedAsLiteral"))
                        deferredLiteral = Boolean.parseBoolean(directive.attributes().get("deferredSyntaxAllowedAsLiteral"));
                }
                if (directive.name().equals("taglib")) {
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
            } else if (starts("<%")) script();
            else cursor++;
        }
    }

    private List<PageNode> nodes(String closing, int opening) {
        var result = new ArrayList<PageNode>();
        int textStart = cursor;
        while (cursor < source.text.length()) {
            if (skipExpression()) continue;
            boolean token = starts("<%") || recognizedTag();
            if (!token) { cursor++; continue; }
            addText(result, textStart, cursor);
            int start = cursor;
            if (starts("<%--")) comment();
            else if (starts("<%@")) result.add(directive());
            else if (starts("<%")) result.add(script());
            else {
                boolean end = starts("</");
                cursor += end ? 2 : 1;
                String name = identifier();
                if (end) {
                    space();
                    expect(">");
                    if (!name.equals(closing)) throw error(start, "Unexpected closing element: " + name);
                    return result;
                }
                var attributes = attributes(false);
                boolean selfClosing = starts("/>");
                expect(selfClosing ? "/>" : ">");
                var children = selfClosing ? List.<PageNode>of() : raw(name, start) ? rawNodes(name, start) : nodes(name, start);
                result.add(new PageNode.Element(name, bindings.get(name.substring(0, name.indexOf(':'))),
                        attributes, children, selfClosing, source.at(start)));
            }
            textStart = cursor;
        }
        addText(result, textStart, cursor);
        if (closing != null) throw error(opening, "Unclosed element: " + closing);
        return result;
    }

    /** Exposes only lexical library bindings for a translation-time included file. */
    void scanBindings(Map<String, String> target, Map<String, SourceLocation> origins) {
        directivesOnly(); target.putAll(bindings); origins.putAll(bindingOrigins);
    }

    private void binding(PageNode.Directive directive) {
        if (directive.name().equals("taglib") && directive.attributes().containsKey("uri")) {
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
        if (rawBodies == null || !starts("<") || starts("</") || starts("<%") || starts("<!")) return false;
        int saved = cursor; cursor++;
        String name;
        try { name = identifier(); } catch (PageParseException ignored) { cursor = saved; return false; }
        if (!raw(name, saved)) { cursor = saved; return false; }
        attributes(false);
        boolean empty = starts("/>"); expect(empty ? "/>" : ">");
        if (!empty) rawNodes(name, saved);
        return true;
    }

    private List<PageNode> rawNodes(String name, int opening) {
        int bodyStart = cursor;
        int first = cursor;
        while (first < source.text.length() && rawSpace(source.text.charAt(first))) first++;
        if (rawEnvelope(first, "jsp:attribute") || rawEnvelope(first, "jsp:body"))
            throw error(first, "Tag-dependent named attributes and explicit jsp:body are not supported");
        while (cursor < source.text.length()) {
            int tag = source.text.indexOf("</" + name, cursor);
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
        int start = cursor + (starts("</") ? 2 : 1);
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
        int start = cursor;
        cursor += 2;
        int depth = 1;
        char quote = 0;
        while (cursor < source.text.length() && depth > 0) {
            char c = source.text.charAt(cursor++);
            if (quote != 0) {
                if (c == '\\' && cursor < source.text.length()) cursor++;
                else if (c == quote) quote = 0;
            } else if (c == '\'' || c == '"') quote = c;
            else if (c == '{') depth++;
            else if (c == '}') depth--;
        }
        if (depth != 0) { cursor = start; return false; }
        return true;
    }

    private PageNode.Directive directive() {
        int start = cursor;
        cursor += 3;
        space();
        String name = identifier();
        if (!DIRECTIVES.contains(name)) throw error(start, "Unknown directive: " + name);
        var attributes = attributes(true);
        expect("%>");
        return new PageNode.Directive(name, attributes, source.at(start));
    }

    private Map<String, String> attributes(boolean directive) {
        var result = new LinkedHashMap<String, String>();
        while (true) {
            int beforeSpace = cursor;
            space();
            if (starts(directive ? "%>" : ">") || (!directive && starts("/>"))) return result;
            if (cursor == beforeSpace) throw error(cursor, "Attributes must be separated by whitespace");
            int start = cursor;
            String name = identifier();
            space();
            expect("=");
            space();
            if (cursor == source.text.length()) throw error(start, "Missing attribute value");
            char quote = source.text.charAt(cursor++);
            if (quote != '\'' && quote != '"') throw error(start, "Attribute values must be quoted");
            var value = new StringBuilder();
            boolean closed = false;
            while (cursor < source.text.length()) {
                char c = source.text.charAt(cursor++);
                if (c == quote) { closed = true; break; }
                if (c == '\\' && cursor < source.text.length()) {
                    char next = source.text.charAt(cursor);
                    if (next == '\\' || next == '\'' || next == '"') {
                        value.append(next); cursor++; continue;
                    }
                }
                value.append(c);
            }
            if (!closed) throw error(start, "Unclosed attribute value");
            if (result.putIfAbsent(name, value.toString()) != null) throw error(start, "Duplicate attribute: " + name);
        }
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

