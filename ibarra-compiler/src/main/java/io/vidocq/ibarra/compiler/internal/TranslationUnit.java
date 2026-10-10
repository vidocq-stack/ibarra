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
import java.nio.charset.Charset;
import java.util.*;

/** Build-side inclusion and property-group selection without reading web resources. */
public final class TranslationUnit {
    public record Result(Page page, Map<String, String> properties, Map<String, Page.Syntax> sourceSyntaxes, String responseEncoding) { }
    private final GenerationOptions options;
    private final Map<PageNode.Text, List<SourceLocation>> origins = new IdentityHashMap<>();
    private final Set<String> stack = new LinkedHashSet<>();
    private Page.Syntax syntax;
    private final Map<String, Page.Syntax> sourceSyntaxes = new HashMap<>();
    private final Map<String, SourceDecoder.Decoded> decodedSources = new HashMap<>();
    private final Map<String, String> unitBindings = new LinkedHashMap<>();
    private ParserOptions unitOptions;
    private boolean strictNamespaces;

    public TranslationUnit(GenerationOptions options) { this.options = options; }

    public Result read(String path, byte[] bytes) {
        var decoded = decode(path, bytes);
        return read(path, decoded.text());
    }

    private SourceDecoder.Decoded decode(String path, byte[] bytes) {
        return decodedSources.computeIfAbsent(path, key -> SourceDecoder.decode(key, bytes, properties(key)));
    }

    public Result read(String path, String source) {
        var properties = properties(path);
        syntax = fileSyntax(path, source, properties);
        var raw = readRoot(path, source, properties);
        var defaults = new ParserOptions(bool(properties.get("el-ignored"), false),
                bool(properties.get("deferred-syntax-allowed-as-literal"), false));
        var settings = new LinkedHashMap<String, String>();
        collectUnitSettings(raw, settings);
        unitOptions = new ParserOptions(bool(settings.get("isELIgnored"), defaults.elIgnored()),
                bool(settings.get("deferredSyntaxAllowedAsLiteral"), defaults.deferredSyntaxAllowedAsLiteral()));
        strictNamespaces = bool(properties.get("error-on-undeclared-namespace"), false);
        raw = readRoot(path, source, properties);
        validateTaglibOrder(raw, new HashSet<>(Set.of("jsp")));
        var nodes = new PageNormalizer(syntax, defaults, origins, sourceSyntaxes).normalizeStructured(raw);
        return new Result(new Page(path, syntax, nodes), Map.copyOf(properties), Map.copyOf(sourceSyntaxes),
                decodedSources.containsKey(path) ? decodedSources.get(path).responseEncoding() : null);
    }

    private List<PageNode> readRoot(String path, String source, Map<String, String> properties) {
        var raw = new ArrayList<PageNode>();
        for (var group : options.propertyGroups()) if (matches(group, path))
            for (String prelude : group.preludes()) raw.addAll(included(prelude, new SourceLocation(path, 1, 1), properties));
        raw.addAll(readFile(path, source, properties));
        for (var group : options.propertyGroups()) if (matches(group, path))
            for (String coda : group.codas()) raw.addAll(included(coda, new SourceLocation(path, 1, 1), properties));
        return raw;
    }

    private void collectUnitSettings(List<PageNode> nodes, Map<String, String> settings) {
        collectUnitSettings(nodes, settings, Map.of());
    }

    private void collectUnitSettings(List<PageNode> nodes, Map<String, String> settings, Map<String, String> namespaces) {
        for (var node : nodes) {
            if (node instanceof PageNode.Directive directive) {
                if (directive.name().equals("taglib")) {
                    String prefix = directive.attributes().get("prefix");
                    String namespace = directive.attributes().getOrDefault("uri", "urn:jsptagdir:" + directive.attributes().get("tagdir"));
                    if (namespaces.containsKey(prefix) && !namespaces.get(prefix).equals(namespace))
                        throw new PageParseException(directive.location(), "Conflicting taglib prefix: " + prefix);
                    String previous = unitBindings.putIfAbsent(prefix, namespace);
                    if (previous != null && !previous.equals(namespace))
                        throw new PageParseException(directive.location(), "Conflicting taglib prefix: " + prefix);
                } else if (directive.name().equals("page") || directive.name().equals("tag")) {
                    for (String name : List.of("isELIgnored", "deferredSyntaxAllowedAsLiteral")) {
                        String value = directive.attributes().get(name);
                        if (value != null) {
                            if (!value.equals("true") && !value.equals("false"))
                                throw new PageParseException(directive.location(), name + " must be true or false");
                            String previous = settings.putIfAbsent(name, value);
                            if (previous != null && !previous.equals(value)) throw new PageParseException(directive.location(), "Conflicting " + name);
                        }
                    }
                }
            } else if (node instanceof PageNode.Element element) {
                var nested = new HashMap<>(namespaces);
                for (var attribute : element.attributes().entrySet())
                    if (attribute.getKey().startsWith("xmlns:")) nested.put(attribute.getKey().substring(6), attribute.getValue());
                collectUnitSettings(element.children(), settings, nested);
            }
        }
    }

    private void validateTaglibOrder(List<PageNode> nodes, Set<String> declared) {
        for (var node : nodes) {
            if (node instanceof PageNode.Directive directive && directive.name().equals("taglib"))
                declared.add(directive.attributes().get("prefix"));
            else if (node instanceof PageNode.Element element) {
                if (sourceSyntaxes.get(element.location().source()) == Page.Syntax.STANDARD) {
                    String prefix = element.name().substring(0, element.name().indexOf(':'));
                    if (!declared.contains(prefix)) throw new PageParseException(element.location(), "Taglib directive must precede use of prefix: " + prefix);
                }
                validateTaglibOrder(element.children(), declared);
            }
        }
    }

    private List<PageNode> readFile(String path, String source, Map<String, String> properties) {
        if (!stack.add(path)) throw new PageParseException(new SourceLocation(path, 1, 1), "Recursive translation-time include: " + path);
        try {
            var document = new SourceDocument(path, source);
            var fileSyntax = fileSyntax(path, source, properties(path));
            sourceSyntaxes.put(path, fileSyntax);
            List<PageNode> raw;
            if (fileSyntax == Page.Syntax.XML) {
                var parser = new XmlParser(document); raw = parser.parse(); origins.putAll(parser.origins());
            } else raw = new StandardParser(document, unitOptions == null
                    ? new ParserOptions(bool(properties.get("el-ignored"), false), bool(properties.get("deferred-syntax-allowed-as-literal"), false))
                    : unitOptions, unitBindings, strictNamespaces).parse();
            validateEncoding(raw, properties(path), new HashSet<>());
            raw = new PageNormalizer(fileSyntax, ParserOptions.DEFAULT, origins).structure(raw);
            return expand(raw, properties);
        } finally { stack.remove(path); }
    }

    private void validateEncoding(List<PageNode> nodes, Map<String, String> properties, Set<String> encodedFiles) {
        for (var node : nodes) {
            Map<String, String> attributes = null;
            if (node instanceof PageNode.Directive directive && directive.name().equals("page"))
                attributes = directive.attributes();
            else if (node instanceof PageNode.Element element && element.namespace().equals(PageParser.JSP_NAMESPACE)
                    && element.name().substring(element.name().lastIndexOf(':') + 1).equals("directive.page")) attributes = element.attributes();
            if (attributes != null && attributes.containsKey("pageEncoding")) {
                if (!encodedFiles.add(node.location().source()))
                    throw new PageParseException(node.location(), "pageEncoding may occur only once per file");
                var charset = charset(attributes.get("pageEncoding"), node.location());
                if (properties.containsKey("page-encoding") && !(decodedSources.containsKey(node.location().source())
                        ? SourceDecoder.compatible(charset, charset(properties.get("page-encoding"), node.location()))
                        : charset.equals(charset(properties.get("page-encoding"), node.location()))))
                    throw new PageParseException(node.location(), "pageEncoding conflicts with configuration");
            }
            if (node instanceof PageNode.Element element) validateEncoding(element.children(), properties, encodedFiles);
        }
    }

    private static Charset charset(String name, SourceLocation at) {
        try { return Charset.forName(name); }
        catch (IllegalArgumentException failure) { throw new PageParseException(at, "Invalid page encoding: " + name); }
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
        if (source == null) {
            byte[] bytes = options.byteSource(path);
            if (bytes != null) source = decode(path, bytes).text();
        }
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
            for (String name : group.properties().keySet())
                if (!Set.of("el-ignored", "is-xml", "scripting-invalid", "page-encoding",
                        "deferred-syntax-allowed-as-literal", "trim-directive-whitespaces",
                        "default-content-type", "buffer", "error-on-el-not-found", "error-on-undeclared-namespace").contains(name))
                    throw new PageParseException(new SourceLocation(path, 1, 1), "Unknown JSP property: " + name);
            if (group.properties().containsKey("page-encoding"))
                charset(group.properties().get("page-encoding"), new SourceLocation(path, 1, 1));
            for (String name : List.of("el-ignored", "is-xml", "scripting-invalid",
                    "deferred-syntax-allowed-as-literal", "trim-directive-whitespaces",
                    "error-on-el-not-found", "error-on-undeclared-namespace")) {
                String value = group.properties().get(name);
                if (value != null && !value.equals("true") && !value.equals("false"))
                    throw new PageParseException(new SourceLocation(path, 1, 1), name + " must be true or false");
            }
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

