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
import java.nio.file.Path;
import java.util.*;

/** Build-side inclusion and property-group selection without reading web resources. */
public final class TranslationUnit {
    public record Result(Page page, Map<String, String> properties) { }
    private final GenerationOptions options;
    private final Map<PageNode.Text, List<SourceLocation>> origins = new IdentityHashMap<>();
    private final Set<String> stack = new LinkedHashSet<>();
    private Page.Syntax syntax;

    public TranslationUnit(GenerationOptions options) { this.options = options; }

    public Result read(String path, String source) {
        var properties = properties(path);
        syntax = fileSyntax(path, source, properties);
        var raw = new ArrayList<PageNode>();
        for (var group : options.propertyGroups()) if (matches(group, path))
            for (String prelude : group.preludes()) raw.addAll(included(prelude, new SourceLocation(path, 1, 1), properties));
        raw.addAll(readFile(path, source, properties));
        for (var group : options.propertyGroups()) if (matches(group, path))
            for (String coda : group.codas()) raw.addAll(included(coda, new SourceLocation(path, 1, 1), properties));
        var defaults = new ParserOptions(bool(properties.get("el-ignored"), false),
                bool(properties.get("deferred-syntax-allowed-as-literal"), false));
        var nodes = new PageNormalizer(syntax, defaults, origins).normalize(raw);
        return new Result(new Page(path, syntax, nodes), Map.copyOf(properties));
    }

    private List<PageNode> readFile(String path, String source, Map<String, String> properties) {
        if (!stack.add(path)) throw new PageParseException(new SourceLocation(path, 1, 1), "Recursive translation-time include: " + path);
        try {
            var document = new SourceDocument(path, source);
            var fileSyntax = fileSyntax(path, source, properties(path));
            if (fileSyntax != syntax) throw new PageParseException(document.at(0), "Mixed-syntax translation-time includes are not supported yet");
            List<PageNode> raw;
            if (syntax == Page.Syntax.XML) {
                var parser = new XmlParser(document); raw = parser.parse(); origins.putAll(parser.origins());
            } else raw = new StandardParser(document, new ParserOptions(bool(properties.get("el-ignored"), false),
                    bool(properties.get("deferred-syntax-allowed-as-literal"), false))).parse();
            return expand(raw, properties);
        } finally { stack.remove(path); }
    }

    private List<PageNode> expand(List<PageNode> nodes, Map<String, String> properties) {
        var result = new ArrayList<PageNode>();
        for (var node : nodes) {
            if (node instanceof PageNode.Directive directive && directive.name().equals("include")) {
                String file = directive.attributes().get("file");
                if (file == null || directive.attributes().size() != 1)
                    throw new PageParseException(directive.location(), "include requires only the file attribute");
                String path = resolve(directive.location().source(), file);
                result.addAll(included(path, directive.location(), properties));
            } else if (node instanceof PageNode.Element element && element.namespace().equals(PageParser.JSP_NAMESPACE)
                    && element.name().substring(element.name().lastIndexOf(':') + 1).equals("directive.include")) {
                var attributes = new LinkedHashMap<>(element.attributes());
                attributes.keySet().removeIf(name -> name.startsWith("xmlns"));
                String file = attributes.get("file");
                if (file == null || attributes.size() != 1 || !element.children().isEmpty())
                    throw new PageParseException(element.location(), "include requires only the file attribute and an empty body");
                result.addAll(included(resolve(element.location().source(), file), element.location(), properties));
            } else if (node instanceof PageNode.Element element)
                result.add(new PageNode.Element(element.name(), element.namespace(), element.attributes(),
                        expand(element.children(), properties), element.selfClosing(), element.location()));
            else result.add(node);
        }
        return result;
    }

    private List<PageNode> included(String path, SourceLocation at, Map<String, String> properties) {
        if (stack.contains(path)) throw new PageParseException(at, "Recursive translation-time include: " + path);
        var source = options.sources().get(path);
        if (source == null) throw new PageParseException(at, "Missing translation-time include: " + path);
        return readFile(path, source, properties);
    }

    private Page.Syntax fileSyntax(String path, String source, Map<String, String> properties) {
        String configured = properties.get("is-xml");
        if (configured != null) return bool(configured, false) ? Page.Syntax.XML : Page.Syntax.STANDARD;
        return PageParser.detectSyntax(path, source);
    }

    private Map<String, String> properties(String path) {
        var result = new LinkedHashMap<String, String>();
        var specificity = new HashMap<String, Match>();
        for (var group : options.propertyGroups()) {
            Match best = null;
            for (String pattern : group.patterns()) {
                var match = match(pattern, path);
                if (match != null && (best == null || match.compareTo(best) > 0)) best = match;
            }
            if (best == null) continue;
            for (var entry : group.properties().entrySet()) {
                var previous = specificity.get(entry.getKey());
                if (previous == null || best.compareTo(previous) > 0) {
                    result.put(entry.getKey(), entry.getValue()); specificity.put(entry.getKey(), best);
                }
            }
        }
        return result;
    }

    private record Match(int category, int length) implements Comparable<Match> {
        public int compareTo(Match other) { int result = Integer.compare(category, other.category); return result == 0 ? Integer.compare(length, other.length) : result; }
    }
    private static boolean matches(JspPropertyGroup group, String path) {
        return group.patterns().stream().anyMatch(pattern -> match(pattern, path) != null);
    }
    private static Match match(String pattern, String path) {
        if (pattern.equals(path)) return new Match(3, pattern.length());
        if (pattern.endsWith("/*")) {
            String prefix = pattern.substring(0, pattern.length() - 2);
            return path.equals(prefix) || path.startsWith(prefix + "/") ? new Match(2, prefix.length()) : null;
        }
        if (pattern.startsWith("*.")) return path.endsWith(pattern.substring(1)) ? new Match(1, pattern.length()) : null;
        if (pattern.equals("/")) return new Match(0, 0);
        return null;
    }
    public static String resolve(String current, String relative) {
        if (relative.contains(":")) throw new PageParseException(new SourceLocation(current, 1, 1), "Include path must be a web resource path");
        String base = relative.startsWith("/") ? relative : current.substring(0, current.lastIndexOf('/') + 1) + relative;
        var path = Path.of("/").resolve(base).normalize().toString().replace('\\', '/');
        return path.startsWith("/") ? path : "/" + path;
    }
    public static boolean bool(String value, boolean fallback) {
        if (value == null) return fallback;
        if (value.equals("true")) return true;
        if (value.equals("false")) return false;
        throw new IllegalArgumentException("Boolean property must be true or false: " + value);
    }
}

