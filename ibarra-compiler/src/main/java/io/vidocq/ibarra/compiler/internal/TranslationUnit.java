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
    public record Result(Page page, Map<String, String> properties, Map<String, Page.Syntax> sourceSyntaxes,
            String responseEncoding, List<TldReferences.Reference> tagLibraries) {
        public Result { tagLibraries = List.copyOf(tagLibraries); }
        public Result(Page page, Map<String, String> properties, Map<String, Page.Syntax> sourceSyntaxes, String responseEncoding) {
            this(page, properties, sourceSyntaxes, responseEncoding, List.of());
        }
    }
    private static final String EL_IGNORED_PROPERTY = "el-ignored";
    private static final String DEFERRED_LITERAL_PROPERTY = "deferred-syntax-allowed-as-literal";
    private static final String UNDECLARED_NAMESPACE_PROPERTY = "error-on-undeclared-namespace";
    private static final String PAGE_ENCODING_PROPERTY = "page-encoding";
    private static final String IS_XML_PROPERTY = "is-xml";
    private static final String BUFFER_PROPERTY = "buffer";
    private static final List<String> BOOLEAN_PROPERTIES = List.of(EL_IGNORED_PROPERTY, IS_XML_PROPERTY, "scripting-invalid",
            DEFERRED_LITERAL_PROPERTY, "trim-directive-whitespaces", "error-on-el-not-found", UNDECLARED_NAMESPACE_PROPERTY);
    private static final Set<String> KNOWN_PROPERTIES = Set.of(EL_IGNORED_PROPERTY, IS_XML_PROPERTY, "scripting-invalid",
            PAGE_ENCODING_PROPERTY, DEFERRED_LITERAL_PROPERTY, "trim-directive-whitespaces", "default-content-type",
            BUFFER_PROPERTY, "error-on-el-not-found", UNDECLARED_NAMESPACE_PROPERTY);
    private static final String TAGLIB = "taglib";
    private static final String TAGDIR = "tagdir";
    private final GenerationOptions options;
    private final ParserOptions parserDefaults;
    private final Map<PageNode.Text, List<SourceLocation>> origins = new IdentityHashMap<>();
    private final Set<String> stack = new LinkedHashSet<>();
    private final Map<String, Page.Syntax> sourceSyntaxes = new HashMap<>();
    private final Map<String, SourceDecoder.Decoded> decodedSources = new HashMap<>();
    private final Set<String> decodingSources = new HashSet<>();
    private final Map<String, String> unitBindings = new LinkedHashMap<>();
    private ParserOptions unitOptions;
    private boolean strictNamespaces;
    private final Set<TldReferences.Reference> tagLibraries = new LinkedHashSet<>();

    private final TagDependentBodies rawBodies;
    public TranslationUnit(GenerationOptions options) {
        this(options, ParserOptions.DEFAULT);
    }
    public TranslationUnit(GenerationOptions options, ParserOptions parserDefaults) {
        this.options = options;
        this.parserDefaults = Objects.requireNonNull(parserDefaults);
        rawBodies = new TagDependentBodies(options, this::bodySource,
                document -> fileSyntax(document.name, document.text, properties(document.name)));
    }

    private SourceDocument bodySource(String path) {
        if (decodingSources.contains(path)) return null; // Normal include expansion diagnoses the cycle.
        String text = options.sources().get(path);
        if (text == null) {
            byte[] bytes = options.byteSource(path);
            if (bytes != null) text = decode(path, bytes).text();
        }
        return text == null ? null : new SourceDocument(path, text);
    }

    public Result read(String path, byte[] bytes) {
        rawBodies.resetBindings();
        for (var group : matching(path)) {
            for (String prelude : group.preludes()) rawBodies.includeBindings(
                    new PageNode.Directive("include", Map.of("file", prelude), new SourceLocation(path, 1, 1)),
                    rawBodies.bindings(), rawBodies.origins(), parserOptions(properties(path)));
        }
        var decoded = decode(path, bytes);
        return read(path, decoded.text());
    }

    private SourceDecoder.Decoded decode(String path, byte[] bytes) {
        var cached = decodedSources.get(path);
        if (cached != null) return cached;
        decodingSources.add(path);
        try {
            var decoded = SourceDecoder.decode(path, bytes, properties(path), rawBodies);
            decodedSources.put(path, decoded);
            return decoded;
        } finally { decodingSources.remove(path); }
    }

    public Result read(String path, String source) {
        var properties = properties(path);
        var defaults = parserOptions(properties);
        Page.Syntax syntax = fileSyntax(path, source, properties);
        unitOptions = defaults;
        var raw = readRoot(path, source, properties);
        var settings = new LinkedHashMap<String, String>();
        collectUnitSettings(raw, settings);
        unitOptions = new ParserOptions(bool(settings.get("isELIgnored"), defaults.elIgnored()),
                bool(settings.get("deferredSyntaxAllowedAsLiteral"), defaults.deferredSyntaxAllowedAsLiteral()));
        strictNamespaces = bool(properties.get(UNDECLARED_NAMESPACE_PROPERTY), false);
        tagLibraries.clear();
        raw = readRoot(path, source, properties);
        validateTaglibOrder(raw, new HashSet<>(Set.of("jsp")));
        var nodes = new PageNormalizer(syntax, defaults, origins, sourceSyntaxes).normalizeStructured(raw);
        return new Result(new Page(path, syntax, nodes), Map.copyOf(properties), Map.copyOf(sourceSyntaxes),
                decodedSources.containsKey(path) ? decodedSources.get(path).responseEncoding() : null, List.copyOf(tagLibraries));
    }

    private ParserOptions parserOptions(Map<String, String> properties) {
        return new ParserOptions(bool(properties.get(EL_IGNORED_PROPERTY), parserDefaults.elIgnored()),
                bool(properties.get(DEFERRED_LITERAL_PROPERTY), parserDefaults.deferredSyntaxAllowedAsLiteral()));
    }

    private List<PageNode> readRoot(String path, String source, Map<String, String> properties) {
        rawBodies.resetBindings();
        var raw = new ArrayList<PageNode>();
        for (var group : matching(path)) {
            for (String prelude : group.preludes()) raw.addAll(included(prelude, new SourceLocation(path, 1, 1), properties));
        }
        raw.addAll(readFile(path, source, properties));
        for (var group : matching(path)) {
            for (String coda : group.codas()) raw.addAll(included(coda, new SourceLocation(path, 1, 1), properties));
        }
        return raw;
    }

    private List<JspPropertyGroup> matching(String path) {
        return options.propertyGroups().stream().filter(group -> matches(group, path)).toList();
    }

    private void collectUnitSettings(List<PageNode> nodes, Map<String, String> settings) {
        collectUnitSettings(nodes, settings, Map.of());
    }

    private void collectUnitSettings(List<PageNode> nodes, Map<String, String> settings, Map<String, String> namespaces) {
        for (var node : nodes) {
            switch (node) {
                case PageNode.Directive directive when directive.name().equals(TAGLIB) -> bindUnitPrefix(directive, namespaces);
                case PageNode.Directive directive when directive.name().equals("page") || directive.name().equals("tag") ->
                        collectElSettings(directive, settings);
                case PageNode.Element(var _, var _, var attributes, var children, var _, var _) ->
                        collectUnitSettings(children, settings, nested(namespaces, attributes));
                default -> {
                    // Other nodes declare neither prefixes nor EL settings.
                }
            }
        }
    }

    private void bindUnitPrefix(PageNode.Directive directive, Map<String, String> namespaces) {
        String prefix = directive.attributes().get("prefix");
        String namespace = tagLibraryNamespace(directive.attributes());
        if (namespaces.containsKey(prefix) && !namespaces.get(prefix).equals(namespace))
            throw new PageParseException(directive.location(), "Conflicting taglib prefix: " + prefix);
        String previous = unitBindings.putIfAbsent(prefix, namespace);
        if (previous != null && !previous.equals(namespace))
            throw new PageParseException(directive.location(), "Conflicting taglib prefix: " + prefix);
    }

    private static void collectElSettings(PageNode.Directive directive, Map<String, String> settings) {
        for (String name : List.of("isELIgnored", "deferredSyntaxAllowedAsLiteral")) {
            String value = directive.attributes().get(name);
            if (value != null) {
                if (!booleanLiteral(value))
                    throw new PageParseException(directive.location(), name + " must be true or false");
                String previous = settings.putIfAbsent(name, value);
                if (previous != null && !previous.equals(value)) throw new PageParseException(directive.location(), "Conflicting " + name);
            }
        }
    }

    private static Map<String, String> nested(Map<String, String> namespaces, Map<String, String> attributes) {
        var nested = new HashMap<>(namespaces);
        for (var attribute : attributes.entrySet()) {
            if (attribute.getKey().startsWith("xmlns:")) nested.put(attribute.getKey().substring(6), attribute.getValue());
        }
        return nested;
    }

    private static String tagLibraryNamespace(Map<String, String> attributes) {
        return attributes.getOrDefault("uri", "urn:jsptagdir:" + attributes.get(TAGDIR));
    }

    private void validateTaglibOrder(List<PageNode> nodes, Set<String> declared) {
        for (var node : nodes) {
            if (node instanceof PageNode.Directive directive && directive.name().equals(TAGLIB))
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
                var parser = new XmlParser(document, rawBodies); raw = parser.parse(); origins.putAll(parser.origins());
            } else raw = new StandardParser(document, unitOptions == null
                    ? parserOptions(properties)
                    : unitOptions, unitBindings, strictNamespaces, rawBodies, Map.of()).parse();
            tagLibraries(raw);
            validateEncoding(raw, properties(path), new HashSet<>());
            raw = new PageNormalizer(fileSyntax, ParserOptions.DEFAULT, origins).structure(raw);
            return expand(raw, properties);
        } finally { stack.remove(path); }
    }

    private void tagLibraries(List<PageNode> nodes) {
        for (var node : nodes) {
            switch (node) {
                case PageNode.Directive(var name, var attributes, var location)
                        when name.equals(TAGLIB) && (attributes.containsKey("uri") || attributes.containsKey(TAGDIR)) ->
                        tagLibraries.add(new TldReferences.Reference(tagLibraryNamespace(attributes), location, false));
                case PageNode.Element(var _, var _, var attributes, var children, var _, var location) -> {
                    xmlnsTagLibraries(attributes, location);
                    tagLibraries(children);
                }
                default -> {
                    // Other nodes reference no tag library.
                }
            }
        }
    }

    private void xmlnsTagLibraries(Map<String, String> attributes, SourceLocation location) {
        for (var attribute : attributes.entrySet()) {
            if ((attribute.getKey().equals("xmlns") || attribute.getKey().startsWith("xmlns:"))
                    && attribute.getValue().startsWith("urn:jsptld:"))
                tagLibraries.add(new TldReferences.Reference(attribute.getValue().substring(11), location, true));
        }
    }

    private void validateEncoding(List<PageNode> nodes, Map<String, String> properties, Set<String> encodedFiles) {
        for (var node : nodes) {
            Map<String, String> attributes = pageDirectiveAttributes(node);
            if (attributes != null && attributes.containsKey("pageEncoding"))
                validatePageEncoding(node.location(), attributes.get("pageEncoding"), properties, encodedFiles);
            if (node instanceof PageNode.Element element) validateEncoding(element.children(), properties, encodedFiles);
        }
    }

    /** The attributes of a standard or XML page directive, otherwise {@code null}. */
    private static Map<String, String> pageDirectiveAttributes(PageNode node) {
        if (node instanceof PageNode.Directive(var name, var attributes, var _) && name.equals("page")) return attributes;
        if (node instanceof PageNode.Element element && jspAction(element, "directive.page")) return element.attributes();
        return null;
    }

    private void validatePageEncoding(SourceLocation location, String value, Map<String, String> properties, Set<String> encodedFiles) {
        if (!encodedFiles.add(location.source()))
            throw new PageParseException(location, "pageEncoding may occur only once per file");
        var charset = charset(value, location);
        if (!properties.containsKey(PAGE_ENCODING_PROPERTY)) return;
        var configured = charset(properties.get(PAGE_ENCODING_PROPERTY), location);
        boolean consistent = decodedSources.containsKey(location.source())
                ? SourceDecoder.compatible(charset, configured) : charset.equals(configured);
        if (!consistent) throw new PageParseException(location, "pageEncoding conflicts with configuration");
    }

    private static boolean jspAction(PageNode.Element element, String localName) {
        return element.namespace().equals(PageParser.JSP_NAMESPACE)
                && element.name().substring(element.name().lastIndexOf(':') + 1).equals(localName);
    }

    private static Charset charset(String name, SourceLocation at) {
        try { return Charset.forName(name); }
        catch (IllegalArgumentException _) { throw new PageParseException(at, "Invalid page encoding: " + name); }
    }

    private List<PageNode> expand(List<PageNode> nodes, Map<String, String> properties) {
        var result = new ArrayList<PageNode>();
        for (var node : nodes) {
            switch (node) {
                case PageNode.Directive(var name, var attributes, var location) when name.equals("include") ->
                        result.addAll(includeDirective(attributes, location, properties));
                case PageNode.Element element when jspAction(element, "directive.include") ->
                        result.addAll(includeAction(element, properties));
                case PageNode.Element(var name, var namespace, var attributes, var children, var selfClosing, var location) ->
                        result.add(new PageNode.Element(name, namespace, attributes, expand(children, properties), selfClosing, location));
                default -> result.add(node);
            }
        }
        return result;
    }

    private List<PageNode> includeDirective(Map<String, String> attributes, SourceLocation location, Map<String, String> properties) {
        String file = attributes.get("file");
        if (file == null || attributes.size() != 1)
            throw new PageParseException(location, "include requires only the file attribute");
        return included(resolve(location.source(), file), location, properties);
    }

    private List<PageNode> includeAction(PageNode.Element element, Map<String, String> properties) {
        var attributes = new LinkedHashMap<>(element.attributes());
        attributes.keySet().removeIf(name -> name.startsWith("xmlns"));
        String file = attributes.get("file");
        if (file == null || attributes.size() != 1 || !element.children().isEmpty())
            throw new PageParseException(element.location(), "include requires only the file attribute and an empty body");
        return included(resolve(element.location().source(), file), element.location(), properties);
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
        String configured = properties.get(IS_XML_PROPERTY);
        if (configured != null) return bool(configured, false) ? Page.Syntax.XML : Page.Syntax.STANDARD;
        return PageParser.detectSyntax(path, source);
    }

    private Map<String, String> properties(String path) {
        validateConfiguration(options, new SourceLocation(path, 1, 1));
        var result = new LinkedHashMap<String, String>();
        var specificity = new HashMap<String, Match>();
        for (var group : options.propertyGroups()) {
            Match best = bestMatch(group, path);
            if (best != null) apply(group, best, result, specificity);
        }
        return result;
    }

    private static Match bestMatch(JspPropertyGroup group, String path) {
        Match best = null;
        for (String pattern : group.patterns()) {
            var match = match(pattern, path);
            if (match != null && (best == null || match.compareTo(best) > 0)) best = match;
        }
        return best;
    }

    /** A more specific group overrides the properties it declares. */
    private static void apply(JspPropertyGroup group, Match match, Map<String, String> result, Map<String, Match> specificity) {
        for (var entry : group.properties().entrySet()) {
            var previous = specificity.get(entry.getKey());
            if (previous == null || match.compareTo(previous) > 0) {
                result.put(entry.getKey(), entry.getValue());
                specificity.put(entry.getKey(), match);
            }
        }
    }

    /** Configuration failures are deployment-wide, even if no page matches a group. */
    public static void validateConfiguration(GenerationOptions options, SourceLocation at) {
        for (var group : options.propertyGroups()) validateGroup(group.properties(), at);
    }

    private static void validateGroup(Map<String, String> properties, SourceLocation at) {
        for (String name : properties.keySet()) {
            if (!KNOWN_PROPERTIES.contains(name)) throw new PageParseException(at, "Unknown JSP property: " + name);
        }
        if (properties.containsKey(PAGE_ENCODING_PROPERTY)) charset(properties.get(PAGE_ENCODING_PROPERTY), at);
        if (properties.containsKey(BUFFER_PROPERTY)) bufferCapacity(properties.get(BUFFER_PROPERTY), at);
        for (String name : BOOLEAN_PROPERTIES) {
            String value = properties.get(name);
            if (value != null && !booleanLiteral(value)) throw new PageParseException(at, name + " must be true or false");
        }
    }

    private static boolean booleanLiteral(String value) {
        return value.equals("true") || value.equals("false");
    }

    /** Shared buffer validation for deployment defaults and page directives. */
    public static int bufferCapacity(String value, SourceLocation at) {
        if (value.equals("none")) return 0;
        try {
            if (!value.endsWith("kb")) throw new NumberFormatException();
            int capacity = Math.multiplyExact(Integer.parseInt(value.substring(0, value.length() - 2)), 1024);
            if (capacity <= 0) throw new NumberFormatException();
            return capacity;
        } catch (NumberFormatException | ArithmeticException _) {
            throw new PageParseException(at, "Invalid page buffer: " + value);
        }
    }

    private record Match(int category, int length) implements Comparable<Match> {
        public int compareTo(Match other) { int result = Integer.compare(category, other.category); return result == 0 ? Integer.compare(length, other.length) : result; }
    }
    public static boolean matches(JspPropertyGroup group, String path) {
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

