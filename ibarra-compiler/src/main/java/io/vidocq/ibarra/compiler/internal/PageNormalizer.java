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
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Applies syntax mappings and translation-unit EL settings after structural parsing. */
public final class PageNormalizer {
    private static final Set<String> DIRECTIVES = Set.of("page", "include", "taglib", "tag", "attribute", "variable");
    private static final Set<String> SCRIPTS = Set.of("declaration", "scriptlet", "expression");
    private static final Set<String> SETTING_DIRECTIVES = Set.of("page", "tag");
    private static final String DIRECTIVE_PREFIX = "directive.";
    private static final String ESCAPED_SCRIPTLET = "<\\%";
    private final Page.Syntax syntax;
    private final Map<PageNode.Text, List<SourceLocation>> origins;
    private final Map<String, Page.Syntax> sourceSyntaxes;
    private ParserOptions options;

    public PageNormalizer(Page.Syntax syntax, ParserOptions options, Map<PageNode.Text, List<SourceLocation>> origins) {
        this(syntax, options, origins, Map.of());
    }

    public PageNormalizer(Page.Syntax syntax, ParserOptions options, Map<PageNode.Text, List<SourceLocation>> origins,
            Map<String, Page.Syntax> sourceSyntaxes) {
        this.syntax = syntax;
        this.options = options;
        this.origins = origins;
        this.sourceSyntaxes = sourceSyntaxes;
    }

    public List<PageNode> normalize(List<PageNode> raw) {
        return normalizeStructured(structure(raw));
    }

    /** Maps syntax before include expansion, keeping each file's whitespace rules. */
    public List<PageNode> structure(List<PageNode> raw) { return structure(raw, false); }

    /** Applies unit-wide EL policy after files have been individually structured. */
    public List<PageNode> normalizeStructured(List<PageNode> structured) {
        Boolean ignored = setting(structured, "isELIgnored", null);
        Boolean deferred = setting(structured, "deferredSyntaxAllowedAsLiteral", null);
        options = new ParserOptions(ignored == null ? options.elIgnored() : ignored,
                deferred == null ? options.deferredSyntaxAllowedAsLiteral() : deferred);
        return expressions(structured);
    }

    private List<PageNode> structure(List<PageNode> nodes, boolean preserveWhitespace) {
        var result = new ArrayList<PageNode>();
        for (var node : nodes) {
            switch (node) {
                case PageNode.Element element -> result.add(structure(element));
                case PageNode.Text(var value, _) when syntax == Page.Syntax.XML && !preserveWhitespace && whitespace(value) -> {
                    // XML document indentation is not template output.
                }
                default -> result.add(node);
            }
        }
        return result;
    }

    private static boolean whitespace(String value) {
        return value.chars().allMatch(c -> c == ' ' || c == '\t' || c == '\r' || c == '\n');
    }

    private PageNode structure(PageNode.Element element) {
        String local = element.name().substring(element.name().lastIndexOf(':') + 1);
        if (!element.namespace().equals(PageParser.JSP_NAMESPACE)) return withChildren(element, structure(element.children(), false));
        if (local.equals("output")) {
            if (syntax != Page.Syntax.XML) throw new PageParseException(element.location(), "jsp:output requires XML syntax");
            if (!element.children().isEmpty()) throw new PageParseException(element.location(), "jsp:output requires an empty body");
        }
        if (local.startsWith(DIRECTIVE_PREFIX)) return directive(element, local.substring(DIRECTIVE_PREFIX.length()));
        if (SCRIPTS.contains(local)) return script(element, local);
        if (local.equals("text") && element.children().stream().anyMatch(child -> !(child instanceof PageNode.Text)))
            throw new PageParseException(element.location(), "jsp:text must contain only text");
        return withChildren(element, structure(element.children(), local.equals("text") || local.equals("attribute")));
    }

    private static PageNode.Directive directive(PageNode.Element element, String name) {
        if (!DIRECTIVES.contains(name)) throw new PageParseException(element.location(), "Unknown directive: " + name);
        if (!element.children().isEmpty()) throw new PageParseException(element.location(), "Directive must be empty");
        var attributes = new java.util.LinkedHashMap<>(element.attributes());
        attributes.keySet().removeIf(key -> key.equals("xmlns") || key.startsWith("xmlns:"));
        return new PageNode.Directive(name, attributes, element.location());
    }

    private static PageNode.Script script(PageNode.Element element, String local) {
        var code = new StringBuilder();
        for (var child : element.children()) {
            if (!(child instanceof PageNode.Text text)) throw new PageParseException(child.location(), "Scripting body must contain only text");
            code.append(text.value());
        }
        var kind = switch (local) {
            case "declaration" -> PageNode.ScriptKind.DECLARATION;
            case "expression" -> PageNode.ScriptKind.EXPRESSION;
            default -> PageNode.ScriptKind.SCRIPTLET;
        };
        return new PageNode.Script(kind, code.toString(), element.location());
    }

    private static PageNode.Element withChildren(PageNode.Element element, List<PageNode> children) {
        return new PageNode.Element(element.name(), element.namespace(), element.attributes(),
                children, element.selfClosing(), element.location());
    }

    private Boolean setting(List<PageNode> nodes, String key, Boolean inherited) {
        Boolean current = inherited;
        for (var node : nodes) {
            switch (node) {
                case PageNode.Directive(var name, var attributes, var location) when SETTING_DIRECTIVES.contains(name) ->
                        current = setting(attributes.get(key), location, key, current);
                case PageNode.Element element -> current = setting(element.children(), key, current);
                default -> {
                    // Other nodes carry no translation-unit setting.
                }
            }
        }
        return current;
    }

    private static Boolean setting(String value, SourceLocation location, String key, Boolean current) {
        if (value == null) return current;
        if (!value.equals("true") && !value.equals("false"))
            throw new PageParseException(location, key + " must be true or false");
        boolean parsed = Boolean.parseBoolean(value);
        if (current != null && current != parsed)
            throw new PageParseException(location, "Conflicting " + key + " directives");
        return parsed;
    }

    private List<PageNode> expressions(List<PageNode> nodes) {
        var result = new ArrayList<PageNode>();
        for (var node : nodes) {
            switch (node) {
                case PageNode.Text text -> split(text, result);
                case PageNode.Element element -> result.add(withChildren(element, expressions(element.children())));
                case PageNode.Directive(_, var attributes, var location) -> {
                    requireLiteral(attributes, location);
                    result.add(node);
                }
                default -> result.add(node);
            }
        }
        return List.copyOf(result);
    }

    private void requireLiteral(Map<String, String> attributes, SourceLocation location) {
        for (var attribute : attributes.values()) {
            if (dynamicAttribute(attribute)) throw new PageParseException(location, "Directive attributes must be literal");
        }
    }

    private boolean dynamicAttribute(String value) {
        int i = 0;
        while (i < value.length()) {
            if (value.startsWith(ESCAPED_SCRIPTLET, i)) i += 3;
            else if (escapedEl(value, i)) i += 2;
            else if (value.startsWith("<%=", i) || el(value, i)) return true;
            else i++;
        }
        return false;
    }

    /** Whether {@code \$} or {@code \#} starts at {@code i} while EL is evaluated. */
    private boolean escapedEl(String value, int i) {
        return !options.elIgnored() && value.charAt(i) == '\\' && i + 1 < value.length()
                && (value.charAt(i + 1) == '$' || value.charAt(i + 1) == '#');
    }

    /** Whether an evaluated immediate or deferred EL expression starts at {@code i}. */
    private boolean el(String value, int i) {
        return !options.elIgnored() && (value.startsWith("${", i)
                || (!options.deferredSyntaxAllowedAsLiteral() && value.startsWith("#{", i)));
    }

    private void split(PageNode.Text text, List<PageNode> nodes) {
        String raw = text.value();
        boolean standard = sourceSyntaxes.getOrDefault(text.location().source(), syntax) == Page.Syntax.STANDARD;
        var literal = new StringBuilder();
        int literalStart = 0;
        int i = 0;
        while (i < raw.length()) {
            if (standard && raw.startsWith(ESCAPED_SCRIPTLET, i)) {
                literal.append("<%");
                i += 3;
            } else if (escapedEl(raw, i)) {
                literal.append(raw.charAt(i + 1));
                i += 2;
            } else if (!el(raw, i)) {
                literal.append(raw.charAt(i));
                i++;
            } else {
                appendText(nodes, literal.toString(), location(text, literalStart));
                literal.setLength(0);
                i = expression(text, i, nodes);
                literalStart = i;
            }
        }
        appendText(nodes, literal.toString(), location(text, literalStart));
    }

    /** Adds the EL expression starting at {@code start}; returns the offset after its closing brace. */
    private int expression(PageNode.Text text, int start, List<PageNode> nodes) {
        String raw = text.value();
        int i = start + 2;
        int codeStart = i;
        int depth = 1;
        char quote = 0;
        while (i < raw.length() && depth > 0) {
            char c = raw.charAt(i++);
            if (quote != 0) {
                if (c == '\\' && i < raw.length()) i++;
                else if (c == quote) quote = 0;
            } else if (c == '\'' || c == '"') quote = c;
            else if (c == '{') depth++;
            else if (c == '}') depth--;
        }
        if (depth != 0) throw new PageParseException(location(text, start), "Unclosed EL expression");
        nodes.add(new PageNode.Expression(raw.substring(codeStart, i - 1), raw.charAt(start) == '#', location(text, start)));
        return i;
    }

    private SourceLocation location(PageNode.Text text, int index) {
        var mapped = origins.get(text);
        if (mapped != null && index < mapped.size()) return mapped.get(index);
        return text.location().advance(text.value().substring(0, index));
    }

    private static void appendText(List<PageNode> nodes, String text, SourceLocation location) {
        if (text.isEmpty()) return;
        if (!nodes.isEmpty() && nodes.getLast() instanceof PageNode.Text(var previous, var previousLocation))
            nodes.set(nodes.size() - 1, new PageNode.Text(previous + text, previousLocation));
        else nodes.add(new PageNode.Text(text, location));
    }
}

