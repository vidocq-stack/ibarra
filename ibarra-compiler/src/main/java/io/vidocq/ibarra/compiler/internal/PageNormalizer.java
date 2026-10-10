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
    private final Page.Syntax syntax;
    private final Map<PageNode.Text, List<SourceLocation>> origins;
    private ParserOptions options;

    public PageNormalizer(Page.Syntax syntax, ParserOptions options, Map<PageNode.Text, List<SourceLocation>> origins) {
        this.syntax = syntax;
        this.options = options;
        this.origins = origins;
    }

    public List<PageNode> normalize(List<PageNode> raw) {
        var structured = structure(raw, false);
        Boolean ignored = setting(structured, "isELIgnored", null);
        Boolean deferred = setting(structured, "deferredSyntaxAllowedAsLiteral", null);
        options = new ParserOptions(ignored == null ? options.elIgnored() : ignored,
                deferred == null ? options.deferredSyntaxAllowedAsLiteral() : deferred);
        return expressions(structured);
    }

    private List<PageNode> structure(List<PageNode> nodes, boolean preserveWhitespace) {
        var result = new ArrayList<PageNode>();
        for (var node : nodes) {
            if (node instanceof PageNode.Element element) {
                String local = element.name().substring(element.name().lastIndexOf(':') + 1);
                boolean jsp = element.namespace().equals(PageParser.JSP_NAMESPACE);
                if (jsp && local.startsWith("directive.")) {
                    String name = local.substring("directive.".length());
                    if (!DIRECTIVES.contains(name)) throw new PageParseException(element.location(), "Unknown directive: " + name);
                    if (!element.children().isEmpty()) throw new PageParseException(element.location(), "Directive must be empty");
                    result.add(new PageNode.Directive(name, element.attributes(), element.location()));
                } else if (jsp && Set.of("declaration", "scriptlet", "expression").contains(local)) {
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
                    result.add(new PageNode.Script(kind, code.toString(), element.location()));
                } else {
                    if (jsp && local.equals("text") && element.children().stream().anyMatch(child -> !(child instanceof PageNode.Text)))
                        throw new PageParseException(element.location(), "jsp:text must contain only text");
                    result.add(new PageNode.Element(element.name(), element.namespace(), element.attributes(),
                            structure(element.children(), jsp && (local.equals("text") || local.equals("attribute"))), element.selfClosing(), element.location()));
                }
            } else if (node instanceof PageNode.Text text && syntax == Page.Syntax.XML && !preserveWhitespace
                    && text.value().chars().allMatch(c -> c == ' ' || c == '\t' || c == '\r' || c == '\n')) {
                // XML document indentation is not template output.
            } else result.add(node);
        }
        return result;
    }

    private Boolean setting(List<PageNode> nodes, String key, Boolean current) {
        for (var node : nodes) {
            if (node instanceof PageNode.Directive directive && Set.of("page", "tag").contains(directive.name())) {
                String value = directive.attributes().get(key);
                if (value != null) {
                    if (!value.equals("true") && !value.equals("false"))
                        throw new PageParseException(directive.location(), key + " must be true or false");
                    boolean parsed = Boolean.parseBoolean(value);
                    if (current != null && current != parsed)
                        throw new PageParseException(directive.location(), "Conflicting " + key + " directives");
                    current = parsed;
                }
            } else if (node instanceof PageNode.Element element) current = setting(element.children(), key, current);
        }
        return current;
    }

    private List<PageNode> expressions(List<PageNode> nodes) {
        var result = new ArrayList<PageNode>();
        for (var node : nodes) {
            if (node instanceof PageNode.Text text) split(text, result);
            else if (node instanceof PageNode.Element element) {
                result.add(new PageNode.Element(element.name(), element.namespace(), element.attributes(),
                        expressions(element.children()), element.selfClosing(), element.location()));
            } else {
                if (node instanceof PageNode.Directive directive) {
                    for (var attribute : directive.attributes().values())
                        if (dynamicAttribute(attribute))
                            throw new PageParseException(directive.location(), "Directive attributes must be literal");
                }
                result.add(node);
            }
        }
        return List.copyOf(result);
    }

    private boolean dynamicAttribute(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.startsWith("<\\%", i)) { i += 2; continue; }
            if (!options.elIgnored() && value.charAt(i) == '\\' && i + 1 < value.length()
                    && (value.charAt(i + 1) == '$' || value.charAt(i + 1) == '#')) { i++; continue; }
            if (value.startsWith("<%=", i) || (!options.elIgnored() && value.startsWith("${", i))
                    || (!options.elIgnored() && !options.deferredSyntaxAllowedAsLiteral() && value.startsWith("#{", i)))
                return true;
        }
        return false;
    }

    private void split(PageNode.Text text, List<PageNode> nodes) {
        String raw = text.value();
        var literal = new StringBuilder();
        int literalStart = 0;
        for (int i = 0; i < raw.length();) {
            if (syntax == Page.Syntax.STANDARD && raw.startsWith("<\\%", i)) { literal.append("<%"); i += 3; continue; }
            if (!options.elIgnored() && raw.charAt(i) == '\\' && i + 1 < raw.length()
                    && (raw.charAt(i + 1) == '$' || raw.charAt(i + 1) == '#')) {
                literal.append(raw.charAt(i + 1)); i += 2; continue;
            }
            boolean expression = !options.elIgnored() && (raw.startsWith("${", i)
                    || (!options.deferredSyntaxAllowedAsLiteral() && raw.startsWith("#{", i)));
            if (!expression) { literal.append(raw.charAt(i++)); continue; }
            appendText(nodes, literal.toString(), location(text, literalStart));
            literal.setLength(0);
            int start = i;
            i += 2;
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
            literalStart = i;
        }
        appendText(nodes, literal.toString(), location(text, literalStart));
    }

    private SourceLocation location(PageNode.Text text, int index) {
        var mapped = origins.get(text);
        if (mapped != null && index < mapped.size()) return mapped.get(index);
        return text.location().advance(text.value().substring(0, index));
    }

    private static void appendText(List<PageNode> nodes, String text, SourceLocation location) {
        if (text.isEmpty()) return;
        if (!nodes.isEmpty() && nodes.getLast() instanceof PageNode.Text previous)
            nodes.set(nodes.size() - 1, new PageNode.Text(previous.value() + text, previous.location()));
        else nodes.add(new PageNode.Text(text, location));
    }
}

