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
import io.vidocq.ibarra.spi.PageDescriptor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Build-time catalog and compiler for static XML and restricted standard tag files. */
public final class TagFileCompiler {
    private static final String TAGDIR_PREFIX = "urn:jsptagdir:";
    private static final String JSP_NAMESPACE = PageParser.JSP_NAMESPACE;
    private static final String TAGLIB_DIRECTIVE = "taglib";
    private static final String OUT_WRITE = "out.write(";

    public record Result(Map<String, TagLibraryDescriptor> libraries, List<GeneratedPage> sources) {
        public Result { libraries = Map.copyOf(libraries); sources = List.copyOf(sources); }
    }
    public record TagFileMetadata(String bodyContent, Map<String, TagAttribute> attributes, boolean dynamicAttributes,
            List<TagVariableDescriptor> variables, String infoString, String displayName, String smallIcon, String largeIcon) {
        public TagFileMetadata {
            attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
            variables = List.copyOf(variables);
        }
    }
    private record Directory(String path, SourceLocation location) { }
    private record TagLibraryUse(String prefix, String uri, TagLibraryValidatorDescriptor validator) { }
    private record StandardTagFile(String bodyContent, List<TagVariableDescriptor> variables,
            Map<String, TagAttribute> attributes, List<String> imports, String declarations, String body,
            String libraryValidationDeclarations, String dynamicAttributesName,
            List<NestedTagVariable> nestedVariables, boolean hasImmediateEl,
            boolean errorOnELNotFound, boolean emitsXmlDeclaration) { }

    private record NestedTagVariable(String name, String contextName, String className, boolean declare, int scope) { }

    private TagFileCompiler() { }

    public static boolean referencesDirectory(TranslationUnit.Result unit, String directory) {
        for (var reference : unit.tagLibraries()) if (reference.uri().startsWith(TAGDIR_PREFIX))
            try {
                if (directory(reference.uri().substring(TAGDIR_PREFIX.length()), reference.location()).equals(directory)) return true;
            } catch (PageParseException ignored) { }
        return false;
    }

    /** Reads the translation-time TagInfo view declared by a tag file, independently of its body. */
    public static TagFileMetadata tagInfoMetadata(TranslationUnit.Result unit) {
        String bodyContent = "scriptless";
        boolean dynamicAttributes = false;
        String infoString = null;
        String displayName = null;
        String smallIcon = null;
        String largeIcon = null;
        var tagDirectiveAttributes = new LinkedHashMap<String, String>();
        var attributes = new LinkedHashMap<String, TagAttribute>();
        var variables = new ArrayList<TagVariableDescriptor>();
        var variableNames = new HashSet<String>();
        var variableSources = new HashSet<String>();
        for (var node : unit.page().nodes()) {
            if (!(node instanceof PageNode.Directive directive)) continue;
            switch (directive.name()) {
                case "tag" -> {
                    for (var entry : directive.attributes().entrySet()) {
                        if (!entry.getKey().equals("import"))
                            mergeTagDirectiveAttribute(tagDirectiveAttributes, entry.getKey(), entry.getValue(), directive.location());
                        switch (entry.getKey()) {
                            case "body-content" -> {
                                if (!Set.of("empty", "scriptless", "tagdependent").contains(entry.getValue()))
                                    throw new PageParseException(directive.location(), "Invalid tag-file body-content: " + entry.getValue());
                                bodyContent = entry.getValue();
                            }
                            case "language" -> validateTagLanguage(entry.getValue(), directive.location());
                            case "dynamic-attributes" -> {
                                String name = entry.getValue();
                                if (name.equals("true")) {
                                    // Keep legacy TLD metadata fixtures that encode this flag as a boolean observable.
                                    dynamicAttributes = true;
                                } else {
                                    if (!javax.lang.model.SourceVersion.isIdentifier(name)
                                            || javax.lang.model.SourceVersion.isKeyword(name))
                                        throw new PageParseException(directive.location(),
                                                "Tag-file dynamic-attributes must name a Java identifier");
                                    dynamicAttributes = true;
                                }
                            }
                            case "description" -> infoString = entry.getValue();
                            case "display-name" -> displayName = entry.getValue();
                            case "small-icon" -> smallIcon = entry.getValue();
                            case "large-icon" -> largeIcon = entry.getValue();
                            case "import", "example", "isELIgnored", "deferredSyntaxAllowedAsLiteral", "errorOnELNotFound" -> { }
                            default -> { }
                        }
                    }
                }
                case "attribute" -> {
                    var attribute = attribute(directive);
                    if (variableNames.contains(attribute.name()))
                        throw new PageParseException(directive.location(), "Tag-file attribute and variable names conflict: " + attribute.name());
                    if (attributes.putIfAbsent(attribute.name(), attribute) != null)
                        throw new PageParseException(directive.location(), "Duplicate tag-file attribute: " + attribute.name());
                }
                case "variable" -> {
                    var variable = variable(directive);
                    String localName = tagFileVariableLocalName(directive, variable);
                    if (localName != null && attributes.containsKey(localName))
                        throw new PageParseException(directive.location(), "Tag-file attribute and variable names conflict: " + localName);
                    if (localName != null && !variableNames.add(localName))
                        throw new PageParseException(directive.location(), "Duplicate tag-file variable: " + localName);
                    if (variable.nameFromAttribute() != null && !variableSources.add(variable.nameFromAttribute()))
                        throw new PageParseException(directive.location(), "Duplicate tag-file name-from-attribute variable: "
                                + variable.nameFromAttribute());
                    variables.add(variable);
                }
                default -> { }
            }
        }
        validateNameFromAttributeVariables(attributes, variables,
                new SourceLocation(unit.page().source(), 1, 1));
        return new TagFileMetadata(bodyContent, attributes, dynamicAttributes, variables,
                infoString, displayName, smallIcon, largeIcon);
    }

    public static Result compile(Collection<TranslationUnit.Result> units, GenerationOptions options, String packageName,
            List<Path> classPath) {
        var directories = new LinkedHashMap<String, Directory>();
        var aliases = new LinkedHashMap<String, String>();
        var referencedFiles = new HashSet<String>();
        for (var unit : units) for (var reference : unit.tagLibraries()) {
            if (!reference.uri().startsWith(TAGDIR_PREFIX)) continue;
            String path = directory(reference.uri().substring(TAGDIR_PREFIX.length()), reference.location());
            directories.putIfAbsent(path, new Directory(path, reference.location()));
            aliases.put(reference.uri(), path);
        }
        for (var unit : units) {
            referencedFiles(unit.page().nodes(), referencedFiles);
            referencedTldTagFiles(unit.page().nodes(), options.tagLibraries(), referencedFiles);
        }

        var pendingFiles = new ArrayDeque<>(referencedFiles);
        var scannedFiles = new HashSet<String>();
        while (!pendingFiles.isEmpty()) {
            String path = pendingFiles.removeFirst();
            if (!scannedFiles.add(path)) continue;
            byte[] bytes = tagFileBytes(options, path);
            if (bytes == null || !(path.endsWith(".tag") || path.endsWith(".tagx"))) continue;
            try {
                var unit = tagFileUnit(options, path).read(path, bytes);
                for (var reference : unit.tagLibraries()) {
                    if (!reference.uri().startsWith(TAGDIR_PREFIX)) continue;
                    String nestedPath = directory(reference.uri().substring(TAGDIR_PREFIX.length()), reference.location());
                    directories.putIfAbsent(nestedPath, new Directory(nestedPath, reference.location()));
                    aliases.put(reference.uri(), nestedPath);
                }
                var nestedFiles = new HashSet<String>();
                referencedFiles(unit.page().nodes(), nestedFiles);
                referencedTldTagFiles(unit.page().nodes(), options.tagLibraries(), nestedFiles);
                for (String nestedFile : nestedFiles) if (referencedFiles.add(nestedFile)) pendingFiles.addLast(nestedFile);
            } catch (PageParseException ignored) {
                // The referenced tag file is diagnosed during its normal compilation pass below.
            }
        }

        var lookupLibraries = new LinkedHashMap<>(options.tagLibraries());
        for (var entry : options.tagLibraries().entrySet()) {
            var tags = new LinkedHashMap<>(entry.getValue().tags());
            boolean changed = false;
            for (var tag : entry.getValue().tags().values()) {
                String path = tag.tagFilePath();
                if (path == null) continue;
                byte[] bytes = tagFileBytes(options, path);
                if (bytes == null) continue;
                var metadata = tagInfoMetadata(tagFileUnit(options, path).read(path, bytes));
                tags.put(tag.name(), tagFileDescriptor(tag.name(), path, packageName, metadata));
                changed = true;
            }
            if (changed) lookupLibraries.put(entry.getKey(), copyLibrary(entry.getValue(), tags));
        }
        for (var alias : aliases.entrySet()) {
            String path = alias.getValue();
            var tags = new TreeMap<String, TagDescriptor>();
            String prefix = path + "/";
            for (var resource : options.byteSources().entrySet()) {
                String sourcePath = resource.getKey();
                if (!sourcePath.startsWith(prefix) || sourcePath.substring(prefix.length()).contains("/")) continue;
                String suffix = sourcePath.endsWith(".tagx") ? ".tagx" : sourcePath.endsWith(".tag") ? ".tag" : null;
                if (suffix == null) continue;
                String name = sourcePath.substring(prefix.length(), sourcePath.length() - suffix.length());
                String handler = packageName + ".TagFile_" + hash(sourcePath);
                try {
                    var metadata = suffix.equals(".tag")
                            ? tagInfoMetadata(tagFileUnit(options, sourcePath).read(sourcePath, resource.getValue()))
                            : new TagFileMetadata("empty", Map.of(), false, List.of(), null, null, null, null);
                    tags.put(name, new TagDescriptor(name, handler, metadata.bodyContent(), metadata.attributes(),
                            metadata.dynamicAttributes(), false, true, null, false, null, metadata.variables(), sourcePath,
                            metadata.infoString(), metadata.displayName(), metadata.smallIcon(), metadata.largeIcon()));
                } catch (PageParseException failure) {
                    tags.put(name, new TagDescriptor(name, handler, "scriptless", Map.of(), false, true, true,
                            failure.getMessage(), false, null, List.of(), sourcePath));
                }
            }
            lookupLibraries.put(alias.getKey(), new TagLibraryDescriptor(path, alias.getKey(), List.of(), tags, null, Map.of()));
        }
        var tagOptions = new GenerationOptions(options.propertyGroups(), options.sources(), false,
                options.byteSources(), lookupLibraries);

        var generated = new ArrayList<GeneratedPage>();
        var generatedTagFiles = new HashSet<String>();
        var byDirectory = new HashMap<String, Map<String, TagDescriptor>>();
        var classes = new BeanClasses(classPath);
        for (var directory : directories.values()) {
            var tags = new TreeMap<String, TagDescriptor>();
            var kinds = new HashMap<String, String>();
            String prefix = directory.path() + "/";
            for (var resource : new TreeMap<>(options.byteSources()).entrySet()) {
                String path = resource.getKey();
                if (!path.startsWith(prefix) || path.substring(prefix.length()).contains("/")) continue;
                String suffix = path.endsWith(".tagx") ? ".tagx" : path.endsWith(".tag") ? ".tag" : null;
                if (suffix == null) continue;
                String name = path.substring(prefix.length(), path.length() - suffix.length());
                if (name.isBlank() || name.contains("."))
                    throw new PageParseException(directory.location(), "Invalid tag-file name: " + path);
                String previousKind = kinds.putIfAbsent(name, suffix);
                if (previousKind != null)
                    throw new PageParseException(directory.location(), "Conflicting .tag and .tagx files for tag " + name);
                PageDescriptor.validatePath(path);
                String handler = packageName + ".TagFile_" + hash(path);
                if (suffix.equals(".tag")) {
                    if (referencedFiles.contains(path)) {
                        try {
                            var unit = tagFileUnit(options, path).read(path, resource.getValue());
                            var tag = compileTagFile(unit, tagOptions, classes);
                            generated.add(new GeneratedPage(handler, source(handler, tag), Map.of()));
                            generatedTagFiles.add(path);
                            tags.put(name, new TagDescriptor(name, handler, tag.bodyContent(), tag.attributes(), tag.dynamicAttributesName() != null,
                                    false, true, null, tag.emitsXmlDeclaration(), null, tag.variables()));
                        } catch (PageParseException failure) {
                            tags.put(name, new TagDescriptor(name, null, "empty", Map.of(), false, true,
                                    false, failure.getMessage()));
                        }
                    } else tags.put(name, new TagDescriptor(name, handler, "scriptless", Map.of(), false, false, true));
                    continue;
                }
                if (referencedFiles.contains(path)) {
                    try {
                        var unit = tagFileUnit(options, path).read(path, resource.getValue());
                        var tag = xmlTagFile(unit, tagOptions, classes);
                        generated.add(new GeneratedPage(handler, source(handler, tag), Map.of()));
                        generatedTagFiles.add(path);
                        tags.put(name, new TagDescriptor(name, handler, tag.bodyContent(), tag.attributes(),
                                tag.dynamicAttributesName() != null, false, true, null, tag.emitsXmlDeclaration(),
                                null, tag.variables()));
                    } catch (PageParseException failure) {
                        tags.put(name, new TagDescriptor(name, null, "empty", Map.of(), false, true, false, failure.getMessage()));
                        continue;
                    }
                }
                tags.putIfAbsent(name, new TagDescriptor(name, handler, "empty", Map.of(), false, false, true));
            }
            if (tags.isEmpty()) throw new PageParseException(directory.location(), "Tag directory contains no .tag or .tagx files: " + directory.path());
            byDirectory.put(directory.path(), Map.copyOf(tags));
        }

        var libraries = new LinkedHashMap<String, TagLibraryDescriptor>();
        for (var alias : aliases.entrySet()) {
            String path = alias.getValue();
            var implicitBytes = options.byteSources().get(path + "/implicit.tld");
            var implicit = implicitBytes == null ? null : new TldParser().parse(path + "/implicit.tld", implicitBytes);
            if (implicit != null) validateImplicitTagLibrary(path + "/implicit.tld", implicit);
            libraries.put(alias.getKey(), new TagLibraryDescriptor(path, alias.getKey(), List.of(), byDirectory.get(path), null, Map.of(),
                    implicit == null ? null : implicit.infoString(),
                    implicit == null ? null : implicit.shortName(),
                    implicit == null ? null : implicit.tlibVersion(),
                    implicit == null ? null : implicit.requiredVersion()));
        }
        for (var entry : options.tagLibraries().entrySet()) {
            var tags = new LinkedHashMap<>(entry.getValue().tags());
            boolean changed = false;
            for (var tag : entry.getValue().tags().values()) {
                String path = tag.tagFilePath();
                if (path == null || !referencedFiles.contains(path)) continue;
                byte[] bytes = tagFileBytes(options, path);
                if (bytes == null) continue;
                var unit = tagFileUnit(tagOptions, path).read(path, bytes);
                String handler = packageName + ".TagFile_" + hash(path);
                TagDescriptor compiled;
                if (path.endsWith(".tag")) {
                    var standard = compileTagFile(unit, tagOptions, classes);
                    if (generatedTagFiles.add(path))
                        generated.add(new GeneratedPage(handler, source(handler, standard), Map.of()));
                    compiled = new TagDescriptor(tag.name(), handler, standard.bodyContent(), standard.attributes(),
                            standard.dynamicAttributesName() != null, false, true, null, standard.emitsXmlDeclaration(), null,
                            standard.variables(), path, tag.infoString(), tag.displayName(), tag.smallIcon(), tag.largeIcon());
                } else if (path.endsWith(".tagx")) {
                    var standard = xmlTagFile(unit, tagOptions, classes);
                    if (generatedTagFiles.add(path))
                        generated.add(new GeneratedPage(handler, source(handler, standard), Map.of()));
                    compiled = new TagDescriptor(tag.name(), handler, standard.bodyContent(), standard.attributes(),
                            standard.dynamicAttributesName() != null, false, true, null, standard.emitsXmlDeclaration(), null,
                            standard.variables(), path, tag.infoString(), tag.displayName(),
                            tag.smallIcon(), tag.largeIcon());
                } else continue;
                tags.put(tag.name(), compiled);
                changed = true;
            }
            if (changed) libraries.put(entry.getKey(), copyLibrary(entry.getValue(), tags));
        }
        return new Result(libraries, generated);
    }

    private static TagDescriptor tagFileDescriptor(String name, String path, String packageName, TagFileMetadata metadata) {
        return new TagDescriptor(name, packageName + ".TagFile_" + hash(path), metadata.bodyContent(), metadata.attributes(),
                metadata.dynamicAttributes(), false, true, null, false, null, metadata.variables(), path,
                metadata.infoString(), metadata.displayName(), metadata.smallIcon(), metadata.largeIcon());
    }

    private static TagLibraryDescriptor copyLibrary(TagLibraryDescriptor library, Map<String, TagDescriptor> tags) {
        return new TagLibraryDescriptor(library.resourcePath(), library.uri(), library.listenerClasses(), tags,
                library.validator(), library.functions(), library.infoString(), library.shortName(), library.tlibVersion(),
                library.requiredVersion());
    }

    private static byte[] tagFileBytes(GenerationOptions options, String path) {
        byte[] bytes = options.byteSources().get(path);
        if (bytes == null && path.startsWith("/")) bytes = options.byteSources().get(path.substring(1));
        if (bytes == null && !path.startsWith("/")) bytes = options.byteSources().get("/" + path);
        return bytes;
    }

    private static TranslationUnit tagFileUnit(GenerationOptions options, String path) {
        return new TranslationUnit(options, new ParserOptions(false, tagFileDeferredSyntaxIsLiteral(options, path)));
    }

    private static boolean tagFileDeferredSyntaxIsLiteral(GenerationOptions options, String path) {
        String normalized = path.startsWith("/") ? path : "/" + path;
        String requiredVersion = null;
        for (var library : options.tagLibraries().values()) {
            for (var tag : library.tags().values()) {
                String tagPath = tag.tagFilePath();
                if (tagPath != null && (tagPath.startsWith("/") ? tagPath : "/" + tagPath).equals(normalized)) {
                    requiredVersion = library.requiredVersion();
                    break;
                }
            }
            if (requiredVersion != null) break;
        }
        if (requiredVersion == null) {
            int slash = normalized.lastIndexOf('/');
            if (slash > 0) {
                String implicitPath = normalized.substring(0, slash) + "/implicit.tld";
                byte[] implicit = tagFileBytes(options, implicitPath);
                if (implicit != null)
                    requiredVersion = new TldParser().parse(implicitPath, implicit).requiredVersion();
            }
        }
        if (requiredVersion == null || requiredVersion.isBlank()) return true; // The JSP default for tag files is 2.0.
        String[] parts = requiredVersion.split("\\.");
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return major < 2 || major == 2 && minor < 1;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static void validateImplicitTagLibrary(String path, TagLibraryDescriptor library) {
        SourceLocation at = new SourceLocation(path, 1, 1);
        String version = library.requiredVersion();
        if (version != null && !version.isBlank()) {
            String[] parts = version.split("\\.");
            try {
                if (Integer.parseInt(parts[0]) < 2)
                    throw new PageParseException(at, "implicit.tld requires JSP version 2.0 or later");
            } catch (NumberFormatException ignored) {
                throw new PageParseException(at, "Invalid JSP version in implicit.tld: " + version);
            }
        }
        if (library.uri() != null || !library.listenerClasses().isEmpty() || !library.tags().isEmpty()
                || library.validator() != null || !library.functions().isEmpty())
            throw new PageParseException(at, "implicit.tld contains elements not permitted for an implicit tag library");
    }

    private static StandardTagFile standardTagFile(TranslationUnit.Result unit, GenerationOptions options, BeanClasses classes) {
        return standardTagFile(unit, unwrapStandardTagFileRoot(unit.page().nodes()), options, classes, false);
    }

    private static StandardTagFile compileTagFile(TranslationUnit.Result unit, GenerationOptions options, BeanClasses classes) {
        return unit.page().syntax() == Page.Syntax.XML
                ? xmlTagFile(unit, options, classes)
                : standardTagFile(unit, options, classes);
    }

    private static StandardTagFile xmlTagFile(TranslationUnit.Result unit, GenerationOptions options, BeanClasses classes) {
        boolean emitsXmlDeclaration = XmlDeclaration.collectTagFile(unit.page());
        return standardTagFile(unit, xmlTagFileNodes(unit.page().nodes()), options, classes, emitsXmlDeclaration);
    }

    private static List<PageNode> unwrapStandardTagFileRoot(List<PageNode> nodes) {
        var result = new ArrayList<PageNode>();
        for (var node : nodes) {
            if (!(node instanceof PageNode.Element root) || !isJspElement(root, "root")) {
                result.add(node);
                continue;
            }
            validateJspRootVersion(root);
            for (var child : root.children()) {
                if (child instanceof PageNode.Element body && isJspElement(body, "body"))
                    result.addAll(body.children());
                else result.add(child);
            }
        }
        return List.copyOf(result);
    }

    private static List<PageNode> xmlTagFileNodes(List<PageNode> nodes) {
        var rootChildren = new ArrayList<PageNode>();
        boolean foundRoot = false;
        for (var node : nodes) {
            if (node instanceof PageNode.Element root && !foundRoot) {
                foundRoot = true;
                if (!isJspElement(root, "root")) {
                    rootChildren.add(root);
                    continue;
                }
                validateJspRootVersion(root);
                for (var child : root.children()) {
                    if (child instanceof PageNode.Element body && isJspElement(body, "body"))
                        rootChildren.addAll(body.children());
                    else rootChildren.add(child);
                }
            } else rootChildren.add(node);
        }
        if (!foundRoot)
            throw new PageParseException(nodes.isEmpty() ? new SourceLocation("<tag-file>", 1, 1) : nodes.getFirst().location(),
                    "XML tag files require jsp:root");
        return normalizeXmlTagFileNodes(rootChildren, false);
    }

    private static List<PageNode> normalizeXmlTagFileNodes(List<PageNode> nodes, boolean inMarkup) {
        var normalized = new ArrayList<PageNode>();
        for (var node : nodes) {
            if (node instanceof PageNode.Text text) {
                normalized.add(inMarkup
                        ? new PageNode.RawText(escapeText(text.value()), text.location())
                        : text);
            } else if (node instanceof PageNode.RawText raw) {
                normalized.add(raw);
            } else if (node instanceof PageNode.Directive || node instanceof PageNode.Script
                    || node instanceof PageNode.Expression) {
                normalized.add(node);
            } else if (node instanceof PageNode.Element element) {
                if (element.namespace().equals(JSP_NAMESPACE)) {
                    String local = jspLocalName(element);
                    if (local.equals("output")) continue;
                    if (local.equals("text")) {
                        normalized.addAll(normalizeXmlTagFileNodes(element.children(), inMarkup));
                    } else {
                        normalized.add(new PageNode.Element(element.name(), element.namespace(), element.attributes(),
                                normalizeXmlTagFileNodes(element.children(), false), element.selfClosing(), element.location()));
                    }
                } else {
                    var markup = new StringBuilder().append('<').append(element.name());
                    for (var attribute : element.attributes().entrySet()) {
                        if ((attribute.getKey().equals("xmlns") || attribute.getKey().startsWith("xmlns:"))
                                && attribute.getValue().equals(JSP_NAMESPACE)) continue;
                        if (attribute.getValue().contains("${") || attribute.getValue().contains("#{"))
                            throw new PageParseException(element.location(),
                                    "Expressions in XML tag-file attributes are not supported yet");
                        markup.append(' ').append(attribute.getKey()).append("=\"")
                                .append(escapeAttribute(attribute.getValue())).append('"');
                    }
                    if (element.selfClosing()) markup.append("/>");
                    else markup.append('>');
                    normalized.add(new PageNode.RawText(markup.toString(), element.location()));
                    if (!element.selfClosing()) {
                        normalized.addAll(normalizeXmlTagFileNodes(element.children(), true));
                        normalized.add(new PageNode.RawText("</" + element.name() + ">", element.location()));
                    }
                }
            }
        }
        return List.copyOf(normalized);
    }

    private static void validateJspRootVersion(PageNode.Element root) {
        String version = root.attributes().get("version");
        if (!"1.2".equals(version) && !"2.0".equals(version))
            throw new PageParseException(root.location(), "Invalid jsp:root version: " + version);
    }

    private static boolean isJspElement(PageNode.Element element, String localName) {
        return element.namespace().equals(JSP_NAMESPACE) && jspLocalName(element).equals(localName);
    }

    private static String jspLocalName(PageNode.Element element) {
        return element.name().substring(element.name().lastIndexOf(':') + 1);
    }

    private static StandardTagFile standardTagFile(TranslationUnit.Result unit, List<PageNode> nodes,
            GenerationOptions options, BeanClasses classes, boolean emitsXmlDeclaration) {
        var referencedLibraries = TldReferences.discover(List.of(unit), options);
        var translation = new TagFileTranslation(referencedLibraries, classes,
                deferredSyntaxIsLiteral(unit, nodes, options), usesImmediateEl(nodes));
        for (var node : nodes) translation.translate(node);
        translation.validateDynamicAttributesName();
        var libraryUses = unit.page().syntax() == Page.Syntax.XML
                ? tagLibraryUses(unit.page().nodes(), true, referencedLibraries)
                : tagLibraryUses(nodes, false, referencedLibraries);
        validateNameFromAttributeVariables(translation.attributes, translation.variables,
                new SourceLocation(unit.page().source(), 1, 1));
        String pageXml = PageDataXmlView.serialize(unit.page().nodes(), taglibNamespaces(unit.page().nodes()));
        return translation.result(libraryValidationDeclarations(libraryUses, pageXml), emitsXmlDeclaration);
    }

    /** The configured {@code deferredSyntaxAllowedAsLiteral}, overridden by the last tag directive that sets it. */
    private static boolean deferredSyntaxIsLiteral(TranslationUnit.Result unit, List<PageNode> nodes,
            GenerationOptions options) {
        boolean deferredSyntaxIsLiteral = tagFileDeferredSyntaxIsLiteral(options, unit.page().source());
        for (var node : nodes) if (node instanceof PageNode.Directive directive && directive.name().equals("tag")
                && directive.attributes().containsKey("deferredSyntaxAllowedAsLiteral"))
            deferredSyntaxIsLiteral = booleanValue(directive.attributes().get("deferredSyntaxAllowedAsLiteral"),
                    "deferredSyntaxAllowedAsLiteral", directive.location());
        return deferredSyntaxIsLiteral;
    }

    /**
     * Translation state of one standard or XML tag file: the directives it declares and the Java body and
     * declarations its template nodes produce, in document order.
     */
    private static final class TagFileTranslation {
        private final TldReferences.Resolved referencedLibraries;
        private final BeanClasses classes;
        private final boolean deferredSyntaxIsLiteral;
        private final Set<String> imports = new TreeSet<>();
        private final List<TagVariableDescriptor> variables = new ArrayList<>();
        private final Set<String> variableNames = new HashSet<>();
        private final Set<String> variableSources = new HashSet<>();
        private final Map<String, TagAttribute> attributes = new LinkedHashMap<>();
        private final Map<String, String> tagDirectiveAttributes = new LinkedHashMap<>();
        private final StringBuilder declarations = new StringBuilder();
        private final StringBuilder body = new StringBuilder();
        private final Map<String, NestedTagVariable> nestedVariables = new LinkedHashMap<>();
        private String bodyContent = "scriptless";
        private String dynamicAttributesName;
        private SourceLocation dynamicAttributesLocation;
        private boolean errorOnELNotFound;
        private int nestedActionIndex;
        private int immediateElIndex;
        private boolean hasImmediateEl;

        TagFileTranslation(TldReferences.Resolved referencedLibraries, BeanClasses classes,
                boolean deferredSyntaxIsLiteral, boolean hasImmediateEl) {
            this.referencedLibraries = referencedLibraries;
            this.classes = classes;
            this.deferredSyntaxIsLiteral = deferredSyntaxIsLiteral;
            this.hasImmediateEl = hasImmediateEl;
        }

        void translate(PageNode node) {
            if (node instanceof PageNode.Directive directive) translateDirective(directive);
            else if (node instanceof PageNode.Text text) writeTemplateText(text.value());
            else if (node instanceof PageNode.RawText text) writeTemplateText(text.value());
            else if (node instanceof PageNode.Script script) translateScript(script);
            else if (node instanceof PageNode.Expression expression) translateExpression(expression);
            else if (node instanceof PageNode.Element element) translateAction(element);
        }

        private void translateDirective(PageNode.Directive directive) {
            switch (directive.name()) {
                case "tag" -> tagDirective(directive);
                case "variable" -> variableDirective(directive);
                case "attribute" -> attributeDirective(directive);
                case TAGLIB_DIRECTIVE -> { }
                default -> throw new PageParseException(directive.location(),
                        "Directive in standard tag files is not supported: " + directive.name());
            }
        }

        private void tagDirective(PageNode.Directive directive) {
            for (var attribute : directive.attributes().entrySet()) {
                if (!attribute.getKey().equals("import"))
                    mergeTagDirectiveAttribute(tagDirectiveAttributes, attribute.getKey(), attribute.getValue(), directive.location());
                tagDirectiveAttribute(attribute.getKey(), attribute.getValue(), directive.location());
            }
        }

        private void tagDirectiveAttribute(String name, String value, SourceLocation at) {
            switch (name) {
                case "import" -> imports.addAll(imports(value, at));
                case "body-content" -> bodyContent = tagFileBodyContent(value, at);
                case "language" -> validateTagLanguage(value, at);
                case "isELIgnored", "deferredSyntaxAllowedAsLiteral" -> booleanValue(value, name, at);
                case "errorOnELNotFound" -> errorOnELNotFound = booleanValue(value, name, at);
                case "example" -> { }
                case "dynamic-attributes" -> declareDynamicAttributes(value, at);
                case "description", "display-name", "small-icon", "large-icon" -> { }
                default -> throw new PageParseException(at, "Tag directive attribute is not supported: " + name);
            }
        }

        private void declareDynamicAttributes(String name, SourceLocation at) {
            if (!javax.lang.model.SourceVersion.isIdentifier(name) || javax.lang.model.SourceVersion.isKeyword(name))
                throw new PageParseException(at, "Tag-file dynamic-attributes must name a Java identifier");
            if (dynamicAttributesName != null && !dynamicAttributesName.equals(name))
                throw new PageParseException(at, "Conflicting tag-file dynamic-attributes declarations");
            dynamicAttributesName = name;
            dynamicAttributesLocation = at;
        }

        private void variableDirective(PageNode.Directive directive) {
            var variable = variable(directive);
            String localName = tagFileVariableLocalName(directive, variable);
            if (localName != null && attributes.containsKey(localName))
                throw new PageParseException(directive.location(), "Tag-file attribute and variable names conflict: " + localName);
            if (localName != null && !variableNames.add(localName))
                throw new PageParseException(directive.location(), "Duplicate tag-file variable: " + localName);
            if (variable.nameFromAttribute() != null && !variableSources.add(variable.nameFromAttribute()))
                throw new PageParseException(directive.location(), "Duplicate tag-file name-from-attribute variable: "
                        + variable.nameFromAttribute());
            variables.add(variable);
        }

        private void attributeDirective(PageNode.Directive directive) {
            var attribute = attribute(directive);
            if (variableNames.contains(attribute.name()))
                throw new PageParseException(directive.location(), "Tag-file attribute and variable names conflict: " + attribute.name());
            if (attributes.putIfAbsent(attribute.name(), attribute) != null)
                throw new PageParseException(directive.location(), "Duplicate tag-file attribute: " + attribute.name());
        }

        private void writeTemplateText(String value) {
            if (!value.isEmpty()) body.append(OUT_WRITE).append(JavaSource.quote(value)).append(");\n");
        }

        private void translateScript(PageNode.Script script) {
            if (script.kind() == PageNode.ScriptKind.DECLARATION) declarations.append(script.code()).append('\n');
            else if (script.kind() == PageNode.ScriptKind.SCRIPTLET) body.append(script.code()).append('\n');
            else body.append("out.write(String.valueOf(").append(script.code()).append("));\n");
        }

        private void translateExpression(PageNode.Expression expression) {
            if (expression.deferred())
                throw new PageParseException(expression.location(), "Deferred EL in standard tag files is not supported yet");
            String result = "_jspxTagFileEl" + immediateElIndex++;
            body.append("Object ").append(result).append(" = _jspxTagFileElFactory.createValueExpression(pageContext.getELContext(), ")
                    .append(JavaSource.quote("${" + expression.code() + "}"))
                    .append(", Object.class).getValue(pageContext.getELContext());\n")
                    .append("if (").append(result).append(" != null) out.write(String.valueOf(").append(result).append("));\n");
            hasImmediateEl = true;
        }

        private void translateAction(PageNode.Element element) {
            if (element.namespace().equals(JSP_NAMESPACE)) {
                body.append(standardAction(element, nestedActionIndex++, attributes));
                return;
            }
            var exportedVariables = nestedTagVariables(element, referencedLibraries);
            for (var variable : exportedVariables) nestedVariables.putIfAbsent(variable.name(), variable);
            body.append(nestedAction(element, referencedLibraries, classes, nestedActionIndex++, deferredSyntaxIsLiteral));
            for (var variable : exportedVariables)
                body.append(variable.name()).append(" = (").append(variable.className().replace('$', '.'))
                        .append(") jspContext.getAttribute(").append(JavaSource.quote(variable.name())).append(");\n");
        }

        void validateDynamicAttributesName() {
            if (dynamicAttributesName != null
                    && (attributes.containsKey(dynamicAttributesName) || variableNames.contains(dynamicAttributesName)))
                throw new PageParseException(dynamicAttributesLocation,
                        "Tag-file dynamic-attributes name conflicts with a declared attribute or variable: " + dynamicAttributesName);
        }

        StandardTagFile result(String libraryValidationDeclarations, boolean emitsXmlDeclaration) {
            return new StandardTagFile(bodyContent, List.copyOf(variables), Collections.unmodifiableMap(new LinkedHashMap<>(attributes)),
                    List.copyOf(imports), declarations.toString(), body.toString(), libraryValidationDeclarations,
                    dynamicAttributesName, List.copyOf(nestedVariables.values()), hasImmediateEl, errorOnELNotFound,
                    emitsXmlDeclaration);
        }
    }

    private static String tagFileBodyContent(String value, SourceLocation at) {
        if (!Set.of("empty", "scriptless", "tagdependent").contains(value))
            throw new PageParseException(at, "Invalid tag-file body-content: " + value);
        return value;
    }

    private static List<NestedTagVariable> nestedTagVariables(PageNode.Element action, TldReferences.Resolved libraries) {
        var library = libraries.resolve(action.namespace(), action.location(), true);
        if (library == null) return List.of();
        String local = action.name().substring(action.name().lastIndexOf(':') + 1);
        var tag = library.tags().get(local);
        if (tag == null || !tag.tagFile()) return List.of();
        return tag.variables().stream().filter(variable -> variable.scope() != TagVariableDescriptor.NESTED)
                .map(variable -> nestedTagVariable(action, variable)).toList();
    }

    private static NestedTagVariable nestedTagVariable(PageNode.Element action, TagVariableDescriptor variable) {
        String name = variable.name();
        if (name == null) {
            name = action.attributes().get(variable.nameFromAttribute());
            if (name == null || name.contains("<%") || name.contains("${") || name.contains("#{"))
                throw new PageParseException(action.location(),
                        "Nested tag-file variable name must be a translation-time constant: "
                                + variable.nameFromAttribute());
        }
        if (!javax.lang.model.SourceVersion.isIdentifier(name) || javax.lang.model.SourceVersion.isKeyword(name))
            throw new PageParseException(action.location(),
                    "Nested tag-file variable name must be a Java identifier: " + name);
        return new NestedTagVariable(name, variable.alias() == null ? name : variable.alias(), variable.className(),
                variable.declare(), variable.scope());
    }

    private static boolean usesImmediateEl(List<PageNode> nodes) {
        for (var node : nodes) {
            if (node instanceof PageNode.Expression expression && !expression.deferred()) return true;
            if (node instanceof PageNode.Element element) {
                if (element.attributes().values().stream().anyMatch(value -> value.contains("${"))) return true;
                if (usesImmediateEl(element.children())) return true;
            }
        }
        return false;
    }

    private static String standardAction(PageNode.Element action, int index, Map<String, TagAttribute> attributes) {
        String local = action.name().substring(action.name().lastIndexOf(':') + 1);
        if (local.equals("forward")) {
            String page = action.attributes().get("page");
            if (page == null || page.isBlank())
                throw new PageParseException(action.location(), "jsp:forward in a tag file requires a page attribute");
            for (String name : action.attributes().keySet())
                if (!name.equals("page") && !name.equals("xmlns") && !name.startsWith("xmlns:"))
                    throw new PageParseException(action.location(), "Unsupported jsp:forward attribute in a tag file: " + name);
            for (var child : action.children())
                if (!(child instanceof PageNode.Text text) || !text.value().isBlank())
                    throw new PageParseException(action.location(), "jsp:param in tag-file jsp:forward is not supported yet");
            if (page.contains("${") || page.contains("#{") || page.contains("<%"))
                throw new PageParseException(action.location(), "Dynamic jsp:forward paths in tag files are not supported yet");
            String failure = "_jspxTagFileForwardFailure" + index;
            return "try { pageContext.forward(" + JavaSource.quote(page) + "); } catch (jakarta.servlet.ServletException "
                    + failure + ") { throw new JspException(\"Could not forward from tag file\", " + failure + "); }\n"
                    + "if (true) throw new jakarta.servlet.jsp.SkipPageException();\n";
        }
        if (local.equals("doBody"))
            return invokeFragment("getJspBody()", actionAttributes(action), "doBody", index, action.location());
        if (local.equals("invoke")) {
            var values = actionAttributes(action);
            String fragment = values.remove("fragment");
            if (fragment == null || !javax.lang.model.SourceVersion.isIdentifier(fragment)
                    || !attributes.containsKey(fragment) || !attributes.get(fragment).fragment())
                throw new PageParseException(action.location(), "jsp:invoke requires a fragment tag-file attribute");
            return invokeFragment(fragment, values, "invoke", index, action.location());
        }
        if (local.equals("text")) {
            if (action.attributes().keySet().stream().anyMatch(name -> !name.equals("xmlns") && !name.startsWith("xmlns:")))
                throw new PageParseException(action.location(), "jsp:text does not accept attributes in a tag file");
            return literalTagContent(action.children(), action.location());
        }
        if (local.equals("element")) return dynamicElement(action, index);
        throw new PageParseException(action.location(), "Standard action in tag files is not supported yet: " + action.name());
    }

    private static String invokeFragment(String fragment, Map<String, String> values, String actionName, int index, SourceLocation at) {
        for (String name : values.keySet())
            if (!Set.of("var", "varReader", "scope", "varReaderScope").contains(name))
                throw new PageParseException(at, "Unsupported jsp:" + actionName + " attribute in a tag file: " + name);
        String variable = values.get("var");
        String reader = values.get("varReader");
        if (variable != null && reader != null)
            throw new PageParseException(at, "jsp:" + actionName + " cannot specify both var and varReader");
        if (reader == null && values.containsKey("varReaderScope") || variable == null && reader == null && values.containsKey("scope"))
            throw new PageParseException(at, "jsp:" + actionName + " scope requires its matching variable");
        if (variable == null && reader == null) return "if (" + fragment + " != null) " + fragment + ".invoke(null);\n";
        String output = "_jspx" + (actionName.equals("doBody") ? "DoBody" : "Invoke") + "Content" + index;
        String writer = "_jspx" + (actionName.equals("doBody") ? "DoBody" : "Invoke") + "Writer" + index;
        String scope = scope(values.getOrDefault("scope", "page"), at);
        String readerScope = scope(values.getOrDefault("varReaderScope", values.getOrDefault("scope", "page")), at);
        var source = new StringBuilder("var ").append(writer).append(" = new java.io.StringWriter();\n")
                .append("if (").append(fragment).append(" != null) ").append(fragment).append(".invoke(").append(writer).append(");\n")
                .append("String ").append(output).append(" = ").append(writer).append(".toString();\n");
        if (variable != null) source.append("pageContext.setAttribute(").append(JavaSource.quote(variable)).append(", ")
                .append(output).append(", ").append(scope).append(");\n");
        if (reader != null) source.append("pageContext.setAttribute(").append(JavaSource.quote(reader))
                .append(", new java.io.StringReader(").append(output).append("), ").append(readerScope).append(");\n");
        return source.toString();
    }

    private static String scope(String scope, SourceLocation at) {
        return switch (scope) {
            case "page" -> "PageContext.PAGE_SCOPE";
            case "request" -> "PageContext.REQUEST_SCOPE";
            case "session" -> "PageContext.SESSION_SCOPE";
            case "application" -> "PageContext.APPLICATION_SCOPE";
            default -> throw new PageParseException(at, "Invalid tag-file action scope: " + scope);
        };
    }

    private static Map<String, String> actionAttributes(PageNode.Element action) {
        var result = new LinkedHashMap<String, String>();
        for (var entry : action.attributes().entrySet())
            if (!entry.getKey().equals("xmlns") && !entry.getKey().startsWith("xmlns:")) result.put(entry.getKey(), entry.getValue());
        for (var child : action.children()) {
            if (child instanceof PageNode.Text text && text.value().isBlank()) continue;
            if (!(child instanceof PageNode.Element element) || !element.namespace().equals(JSP_NAMESPACE)
                    || !element.name().endsWith(":attribute"))
                throw new PageParseException(action.location(), "Only jsp:attribute children are allowed in " + action.name());
            String name = element.attributes().get("name");
            if (name == null || !Set.of("name", "trim").containsAll(element.attributes().keySet()))
                throw new PageParseException(element.location(), "Invalid jsp:attribute in " + action.name());
            String value = literalAttributeValue(element.children(), element.location()).trim();
            if (result.putIfAbsent(name, value) != null)
                throw new PageParseException(element.location(), "Duplicate jsp:attribute: " + name);
        }
        return result;
    }

    private static String literalAttributeValue(List<PageNode> nodes, SourceLocation at) {
        var value = new StringBuilder();
        for (var node : nodes) {
            if (node instanceof PageNode.Text text) value.append(text.value());
            else if (node instanceof PageNode.RawText text) value.append(text.value());
            else throw new PageParseException(at, "EL and actions in tag-file jsp:attribute values are not supported yet");
        }
        return value.toString();
    }

    private static String literalTagContent(List<PageNode> nodes, SourceLocation at) {
        var source = new StringBuilder();
        for (var node : nodes) {
            if (node instanceof PageNode.Text text) source.append(OUT_WRITE).append(JavaSource.quote(text.value())).append(");\n");
            else if (node instanceof PageNode.RawText text) source.append(OUT_WRITE).append(JavaSource.quote(text.value())).append(");\n");
            else if (node instanceof PageNode.Expression expression && !expression.deferred()) {
                String name = "_jspxTagFileText" + Math.abs(expression.location().line() * 37 + expression.location().column());
                source.append("Object ").append(name).append(" = _jspxTagFileElFactory.createValueExpression(pageContext.getELContext(), ")
                        .append(JavaSource.quote("${" + expression.code() + "}")).append(", Object.class).getValue(pageContext.getELContext());\n")
                        .append("if (").append(name).append(" != null) out.write(String.valueOf(").append(name).append("));\n");
            } else throw new PageParseException(at, "Unsupported content inside jsp:text in a tag file");
        }
        return source.toString();
    }

    private static String dynamicElement(PageNode.Element action, int index) {
        String rawName = action.attributes().get("name");
        if (rawName == null || rawName.isBlank()) throw new PageParseException(action.location(), "jsp:element requires a name attribute");
        for (String name : action.attributes().keySet())
            if (!name.equals("name") && !name.equals("xmlns") && !name.startsWith("xmlns:"))
                throw new PageParseException(action.location(), "Unsupported jsp:element attribute: " + name);
        var attributes = new ArrayList<PageNode.Element>();
        List<PageNode> content = new ArrayList<>();
        List<PageNode> explicitBody = null;
        for (var child : action.children()) {
            if (child instanceof PageNode.Element element && element.namespace().equals(JSP_NAMESPACE)) {
                if (element.name().endsWith(":attribute")) {
                    attributes.add(element);
                    continue;
                }
                else if (element.name().endsWith(":body")) {
                    if (explicitBody != null) throw new PageParseException(element.location(), "jsp:element permits one jsp:body");
                    explicitBody = element.children();
                    continue;
                }
            }
            content.add(child);
        }
        if (explicitBody != null) content = explicitBody;
        String name = "_jspxTagFileElementName" + index;
        var source = new StringBuilder("String ").append(name).append(" = ").append(actionValue(rawName, index, action.location())).append(";\n")
                .append("out.write(\"<\"); out.write(").append(name).append(");\n");
        for (var attribute : attributes) {
            String attrName = attribute.attributes().get("name");
            if (attrName == null || attrName.isBlank()) throw new PageParseException(attribute.location(), "jsp:attribute requires a name");
            String value = literalAttributeValue(attribute.children(), attribute.location());
            source.append(OUT_WRITE).append(JavaSource.quote(" " + attrName + "=\"")).append(");\n")
                    .append(OUT_WRITE).append(JavaSource.quote(value.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;"))).append(");\n")
                    .append("out.write(\"\\\"\");\n");
        }
        if (action.selfClosing()) return source.append("out.write(\"/ >\");\n").toString().replace("/ >", "/>");
        source.append("out.write(\">\");\n").append(literalTagContent(content, action.location()))
                .append("out.write(\"</\"); out.write(").append(name).append("); out.write(\">\");\n");
        return source.toString();
    }

    private static String actionValue(String raw, int index, SourceLocation at) {
        var scriptlet = java.util.regex.Pattern.compile("^\\s*<%=\\s*(.*?)\\s*%>\\s*$", java.util.regex.Pattern.DOTALL).matcher(raw);
        if (scriptlet.matches()) return "String.valueOf(" + scriptlet.group(1).trim() + ")";
        if (raw.contains("${"))
            return "String.valueOf((Object) _jspxTagFileElFactory.createValueExpression(pageContext.getELContext(), "
                    + JavaSource.quote(raw) + ", Object.class).getValue(pageContext.getELContext()))";
        if (raw.contains("#{") || raw.contains("<%")) throw new PageParseException(at, "Unsupported dynamic jsp:element name");
        return JavaSource.quote(raw);
    }

    private static TagAttribute attribute(PageNode.Directive directive) {
        var values = directive.attributes();
        String name = values.get("name");
        if (name == null || !javax.lang.model.SourceVersion.isIdentifier(name)
                || javax.lang.model.SourceVersion.isKeyword(name))
            throw new PageParseException(directive.location(), "Tag-file attribute name must be a Java identifier");
        for (String key : values.keySet()) if (!Set.of("name", "required", "rtexprvalue", "type", "fragment", "description",
                "deferredValue", "deferredValueType", "deferredMethod", "deferredMethodSignature").contains(key))
            throw new PageParseException(directive.location(), "Invalid tag-file attribute directive field: " + key);
        boolean fragment = booleanValue(values.getOrDefault("fragment", "false"), "fragment", directive.location());
        String fragmentType = "jakarta.servlet.jsp.tagext.JspFragment";
        String type = values.getOrDefault("type", fragment ? fragmentType : "java.lang.String");
        if (fragment && !type.equals(fragmentType))
            throw new PageParseException(directive.location(), "Fragment tag-file attributes must have type " + fragmentType);
        if (!validAttributeType(type))
            throw new PageParseException(directive.location(), "Invalid tag-file attribute type: " + type);
        boolean deferredValue = booleanValue(values.getOrDefault("deferredValue", "false"), "deferredValue", directive.location());
        boolean deferredMethod = booleanValue(values.getOrDefault("deferredMethod", "false"), "deferredMethod", directive.location());
        String valueType = values.get("deferredValueType");
        String methodSignature = values.get("deferredMethodSignature");
        if (valueType != null && values.containsKey("deferredValue") && !deferredValue)
            throw new PageParseException(directive.location(), "deferredValueType conflicts with deferredValue=false");
        if (methodSignature != null && values.containsKey("deferredMethod") && !deferredMethod)
            throw new PageParseException(directive.location(), "deferredMethodSignature conflicts with deferredMethod=false");
        boolean hasDeferredValue = deferredValue || valueType != null;
        boolean hasDeferredMethod = deferredMethod || methodSignature != null;
        if (hasDeferredValue && hasDeferredMethod)
            throw new PageParseException(directive.location(), "A tag-file attribute cannot be both deferred value and deferred method");
        DeferredMethodSignature deferredMethodContract = null;
        if (hasDeferredValue) {
            valueType = valueType == null ? "java.lang.Object" : valueType.replaceAll("\\s+", "");
            if (!validDeferredType(valueType, false))
                throw new PageParseException(directive.location(), "Invalid deferred value type: " + valueType);
            type = "jakarta.el.ValueExpression";
        } else if (hasDeferredMethod) {
            deferredMethodContract = parseDeferredMethodSignature(methodSignature == null ? "void method()" : methodSignature,
                    directive.location());
            type = "jakarta.el.MethodExpression";
        }
        boolean runtimeExpression = booleanValue(values.getOrDefault("rtexprvalue", fragment ? "false" : "true"),
                "rtexprvalue", directive.location());
        if (fragment && values.containsKey("rtexprvalue"))
            throw new PageParseException(directive.location(), "Fragment tag-file attributes cannot specify rtexprvalue");
        if (fragment && (hasDeferredValue || hasDeferredMethod))
            throw new PageParseException(directive.location(), "Fragment tag-file attributes cannot be deferred");
        return new TagAttribute(name, type, booleanValue(values.getOrDefault("required", "false"), "required", directive.location()),
                runtimeExpression, fragment,
                hasDeferredValue ? valueType : null, deferredMethodContract);
    }

    private static boolean booleanValue(String value, String field, SourceLocation at) {
        if (value.equals("true")) return true;
        if (value.equals("false")) return false;
        throw new PageParseException(at, "Tag-file " + field + " must be true or false");
    }

    private static boolean validAttributeType(String type) {
        while (type.endsWith("[]")) type = type.substring(0, type.length() - 2);
        return javax.lang.model.SourceVersion.isName(type) && !javax.lang.model.SourceVersion.isKeyword(type);
    }

    private static boolean validDeferredType(String type, boolean allowVoid) {
        if (allowVoid && type.equals("void")) return true;
        while (type.endsWith("[]")) type = type.substring(0, type.length() - 2);
        return Set.of("boolean", "byte", "short", "int", "long", "float", "double", "char").contains(type)
                || javax.lang.model.SourceVersion.isName(type);
    }

    private static DeferredMethodSignature parseDeferredMethodSignature(String source, SourceLocation at) {
        var matcher = java.util.regex.Pattern.compile("\\s*(.+?)\\s+([\\p{L}_$][\\p{L}\\p{N}_$]*)\\s*\\((.*?)\\)\\s*").matcher(source);
        if (!matcher.matches() || javax.lang.model.SourceVersion.isKeyword(matcher.group(2)))
            throw new PageParseException(at, "Invalid deferred method signature: " + source);
        String returnType = matcher.group(1).replaceAll("\\s+", "");
        List<String> parameters = matcher.group(3).isBlank() ? List.of()
                : Arrays.stream(matcher.group(3).split(",", -1)).map(value -> value.replaceAll("\\s+", "")).toList();
        if (!validDeferredType(returnType, true) || parameters.stream().anyMatch(type -> !validDeferredType(type, false)))
            throw new PageParseException(at, "Invalid deferred method signature types: " + source);
        return new DeferredMethodSignature(returnType, parameters);
    }

    private static void validateTagLanguage(String language, SourceLocation at) {
        if (language != null && !language.equals("java"))
            throw new PageParseException(at, "Unsupported tag-file language: " + language);
    }

    private static void mergeTagDirectiveAttribute(Map<String, String> seen, String name, String value, SourceLocation at) {
        String previous = seen.putIfAbsent(name, value);
        if (previous != null && !previous.equals(value))
            throw new PageParseException(at, "Conflicting repeated tag directive attribute: " + name);
    }

    private static Map<String, String> taglibNamespaces(List<PageNode> nodes) {
        var namespaces = new LinkedHashMap<String, String>();
        for (var node : nodes) if (node instanceof PageNode.Directive directive && directive.name().equals(TAGLIB_DIRECTIVE)) {
            String prefix = directive.attributes().get("prefix");
            String uri = directive.attributes().get("uri");
            if (prefix != null && uri != null) namespaces.putIfAbsent(prefix, uri);
        }
        return namespaces;
    }

    /**
     * Libraries whose validators the tag file runs: those of its {@code taglib} directives and, in an XML
     * tag file, those its elements declare through {@code xmlns} attributes (read before normalization,
     * which drops the {@code jsp:root} wrapper and its attributes), as for JSP documents.
     */
    private static List<TagLibraryUse> tagLibraryUses(List<PageNode> nodes, boolean xml, TldReferences.Resolved libraries) {
        var uses = new LinkedHashMap<String, TagLibraryUse>();
        collectTagLibraryUses(nodes, xml, libraries, uses);
        return List.copyOf(uses.values());
    }

    private static void collectTagLibraryUses(List<PageNode> nodes, boolean xml, TldReferences.Resolved libraries,
            Map<String, TagLibraryUse> uses) {
        for (var node : nodes) {
            if (node instanceof PageNode.Directive directive && directive.name().equals(TAGLIB_DIRECTIVE)) {
                addTaglibUse(directive, libraries, uses);
            } else if (xml && node instanceof PageNode.Element element) {
                addXmlnsUses(element, libraries, uses);
                collectTagLibraryUses(element.children(), true, libraries, uses);
            }
        }
    }

    private static void addTaglibUse(PageNode.Directive directive, TldReferences.Resolved libraries,
            Map<String, TagLibraryUse> uses) {
        String prefix = directive.attributes().get("prefix");
        String uri = directive.attributes().get("uri");
        if (prefix != null && uri != null) addTagLibraryUse(prefix, uri, directive.location(), true, libraries, uses);
    }

    private static void addXmlnsUses(PageNode.Element element, TldReferences.Resolved libraries,
            Map<String, TagLibraryUse> uses) {
        for (var attribute : element.attributes().entrySet()) {
            String prefix = TldReferences.xmlnsPrefix(attribute.getKey());
            if (prefix != null) addTagLibraryUse(prefix, attribute.getValue(), element.location(), false, libraries, uses);
        }
    }

    private static void addTagLibraryUse(String prefix, String uri, SourceLocation at, boolean standard,
            TldReferences.Resolved libraries, Map<String, TagLibraryUse> uses) {
        if (uri.isBlank() || uri.equals(JSP_NAMESPACE)) return;
        var library = libraries.resolve(uri, at, standard);
        if (library != null && library.validator() != null)
            uses.putIfAbsent(uri, new TagLibraryUse(prefix, uri, library.validator()));
    }

    private static String libraryValidationDeclarations(List<TagLibraryUse> uses, String xmlView) {
        if (uses.isEmpty()) return "";
        // Once per class without a monitor (rule 5). The first caller runs the current FutureTask, concurrent
        // callers wait on the task until it completes, and a success or a non-Error failure is kept for every
        // later call. Each caller receives its own JspException whose cause is the single original failure, and
        // the cached Throwable is never rethrown. An Error is not cached: a fresh task replaces the failed one
        // through a compare-and-set, so the next call validates again. A waiter interrupted while waiting keeps
        // its interrupt flag and caches nothing. The task is a named nested class, not a lambda: no invokedynamic.
        var source = new StringBuilder("private static final class _jspxTagLibraryValidationTask")
                .append(" implements java.util.concurrent.Callable<Void> {\n")
                .append("@Override public Void call() throws jakarta.servlet.jsp.JspException {")
                .append(" _jspxRunTagLibraryValidators(); return null; }\n}\n")
                .append("private static final java.util.concurrent.atomic.AtomicReference<java.util.concurrent.FutureTask<Void>>")
                .append(" _jspxTagLibraryValidation = new java.util.concurrent.atomic.AtomicReference<>(")
                .append("new java.util.concurrent.FutureTask<>(new _jspxTagLibraryValidationTask()));\n")
                .append("private static void _jspxValidateTagLibraries() throws jakarta.servlet.jsp.JspException {\n")
                .append("var task = _jspxTagLibraryValidation.get();\ntask.run();\n")
                .append("try {\ntask.get();\n")
                .append("} catch (InterruptedException interrupted) {\nThread.currentThread().interrupt();\n")
                .append("throw new jakarta.servlet.jsp.JspException(\"Interrupted while validating tag libraries\", interrupted);\n")
                .append("} catch (java.util.concurrent.ExecutionException failed) {\nvar cause = failed.getCause();\n")
                .append("if (cause instanceof Error error) {\n_jspxTagLibraryValidation.compareAndSet(task,")
                .append(" new java.util.concurrent.FutureTask<>(new _jspxTagLibraryValidationTask()));\nthrow error;\n}\n")
                .append("throw new jakarta.servlet.jsp.JspException(cause.getMessage(), cause);\n}\n}\n")
                .append("private static void _jspxRunTagLibraryValidators() throws jakarta.servlet.jsp.JspException {\n");
        int serial = 0;
        for (var use : uses) {
            String suffix = String.valueOf(serial++);
            String validator = "_jspxTagLibraryValidator" + suffix;
            String xml = "_jspxPageDataXml" + suffix;
            String messages = "_jspxTagLibraryMessages" + suffix;
            source.append("var ").append(validator).append(" = new ")
                    .append(use.validator().className().replace('$', '.')).append("();\n");
            if (!use.validator().initParameters().isEmpty()) {
                String parameters = use.validator().initParameters().entrySet().stream().sorted(Map.Entry.comparingByKey())
                        .map(parameter -> "java.util.Map.<String,Object>entry(" + JavaSource.quote(parameter.getKey())
                                + ", " + JavaSource.quote(parameter.getValue()) + ")")
                        .collect(java.util.stream.Collectors.joining(", "));
                source.append(validator).append(".setInitParameters(java.util.Map.<String,Object>ofEntries(")
                        .append(parameters).append("));\n");
            }
            source.append("var ").append(xml).append(" = ").append(JavaSource.quote(xmlView)).append(";\n")
                    .append("try {\nvar ").append(messages).append(" = ").append(validator).append(".validate(")
                    .append(JavaSource.quote(use.prefix())).append(", ").append(JavaSource.quote(use.uri()))
                    .append(", new jakarta.servlet.jsp.tagext.PageData() {\n")
                    .append("@Override public java.io.InputStream getInputStream() { return new java.io.ByteArrayInputStream(")
                    .append(xml).append(".getBytes(java.nio.charset.StandardCharsets.UTF_8)); }\n});\n")
                    .append("if (").append(messages).append(" != null && ").append(messages)
                    .append(".length != 0) throw new jakarta.servlet.jsp.JspException(")
                    .append(JavaSource.quote("TagLibraryValidator validation failed for " + use.uri() + ": "))
                    .append(" + ").append(messages).append("[0].getMessage());\n")
                    .append("} finally { ").append(validator).append(".release(); }\n");
        }
        return source.append("}\n").toString();
    }

    private static String nestedAction(PageNode.Element action, TldReferences.Resolved libraries, BeanClasses classes,
            int index, boolean deferredSyntaxIsLiteral) {
        var library = libraries.resolve(action.namespace(), action.location(), true);
        if (library == null) throw new PageParseException(action.location(), "No build-time tag library for " + action.namespace());
        String local = action.name().substring(action.name().lastIndexOf(':') + 1);
        var tag = library.tags().get(local);
        if (tag == null) throw new PageParseException(action.location(), "Unknown tag " + local + " in " + library.resourcePath());
        if (tag.unsupportedMetadata())
            throw new PageParseException(action.location(), tag.unsupportedDiagnostic());
        if (tag.tagFile()) return nestedTagFile(action, tag, index, deferredSyntaxIsLiteral);
        if (!action.children().isEmpty())
            throw new PageParseException(action.location(), "Bodies on nested tags in standard tag files are not supported yet");
        if (tag.tagFile() || tag.teiClass() != null || !tag.variables().isEmpty())
            throw new PageParseException(action.location(), "Nested tag files and tag variables in standard tag files are not supported yet");
        var kind = classes.handler(tag.handlerClass(), action.location());
        var supplied = new LinkedHashMap<String, String>();
        for (var attribute : action.attributes().entrySet()) {
            if (attribute.getKey().equals("xmlns") || attribute.getKey().startsWith("xmlns:")) continue;
            if (!tag.attributes().containsKey(attribute.getKey())
                    && (!tag.dynamicAttributes() || !kind.dynamic() || attribute.getKey().contains(":")))
                throw new PageParseException(action.location(), "Unknown nested tag attribute: " + attribute.getKey());
            supplied.put(attribute.getKey(), attribute.getValue());
        }
        for (var attribute : tag.attributes().values()) if (attribute.required() && !supplied.containsKey(attribute.name()))
            throw new PageParseException(action.location(), "Required nested tag attribute: " + attribute.name());

        String handler = "_jspxNestedTag" + index;
        var source = new StringBuilder("{\nvar ").append(handler).append(" = new ").append(kind.sourceName()).append("();\n");
        if (kind.simple()) source.append(handler).append(".setJspContext(jspContext);\n");
        else source.append(handler).append(".setPageContext((jakarta.servlet.jsp.PageContext) jspContext);\n");
        if (kind.simple()) source.append(handler).append(".setParent(this);\n");
        else source.append(handler).append(".setParent(new jakarta.servlet.jsp.tagext.TagAdapter(this));\n");
        if (kind.jspIdConsumer()) source.append(handler).append(".setJspId(")
                .append(JavaSource.quote("ibarra_nested_tag_" + index)).append(");\n");
        for (var attribute : supplied.entrySet()) {
            var contract = tag.attributes().get(attribute.getKey());
            if (contract == null) {
                if (!tag.dynamicAttributes() || !kind.dynamic() || attribute.getKey().contains(":"))
                    throw new PageParseException(action.location(), "Unknown nested tag attribute: " + attribute.getKey());
                source.append(handler).append(".setDynamicAttribute(null, ")
                        .append(JavaSource.quote(attribute.getKey())).append(", ")
                        .append(JavaSource.quote(attribute.getValue())).append(");\n");
                continue;
            }
            var setter = classes.tagPropertySetter(tag.handlerClass(), contract.name(), action.location());
            String argument = nestedAttributeValue(attribute.getValue(), contract, setter.type(), action.location(),
                    deferredSyntaxIsLiteral);
            source.append(handler).append('.').append(setter.name()).append("((").append(setter.type()).append(") (")
                    .append(argument).append("));\n");
        }
        if (kind.simple()) return source.append(handler).append(".doTag();\n}\n").toString();
        if (kind.buffered() || kind.catchFinally())
            throw new PageParseException(action.location(), "Body and exception callbacks on classic nested tags are not supported yet");
        return source.append("try {\nint ").append(handler).append("Start = ").append(handler).append(".doStartTag();\n")
                .append("if (").append(handler).append("Start != jakarta.servlet.jsp.tagext.Tag.SKIP_BODY")
                .append(" && ").append(handler).append("Start != jakarta.servlet.jsp.tagext.Tag.EVAL_BODY_INCLUDE)")
                .append(" throw new jakarta.servlet.jsp.JspException(\"Invalid doStartTag result\");\n")
                .append("int ").append(handler).append("End = ").append(handler).append(".doEndTag();\n")
                .append("if (").append(handler).append("End == jakarta.servlet.jsp.tagext.Tag.SKIP_PAGE) throw new jakarta.servlet.jsp.SkipPageException();\n")
                .append("if (").append(handler).append("End != jakarta.servlet.jsp.tagext.Tag.EVAL_PAGE)")
                .append(" throw new jakarta.servlet.jsp.JspException(\"Invalid doEndTag result\");\n")
                .append("} finally { ").append(handler).append(".release(); }\n}\n").toString();
    }

    private static String nestedTagFile(PageNode.Element action, TagDescriptor tag, int index,
            boolean deferredSyntaxIsLiteral) {
        if (!action.children().isEmpty())
            throw new PageParseException(action.location(), "Bodies on nested tag files in standard tag files are not supported yet");
        String local = action.name().substring(action.name().lastIndexOf(':') + 1);
        var supplied = new LinkedHashMap<String, String>();
        for (var attribute : action.attributes().entrySet()) {
            if (attribute.getKey().equals("xmlns") || attribute.getKey().startsWith("xmlns:")) continue;
            if (!tag.attributes().containsKey(attribute.getKey())
                    && (!tag.dynamicAttributes() || attribute.getKey().contains(":")))
                throw new PageParseException(action.location(), "Unknown nested tag attribute: " + attribute.getKey());
            supplied.put(attribute.getKey(), attribute.getValue());
        }
        for (var attribute : tag.attributes().values()) if (attribute.required() && !supplied.containsKey(attribute.name()))
            throw new PageParseException(action.location(), "Required nested tag attribute: " + attribute.name());

        String handler = "_jspxNestedTagFile" + index;
        var source = new StringBuilder("{\nvar ").append(handler).append(" = new ").append(tag.handlerClass()).append("();\n")
                .append(handler).append(".setJspContext(jspContext);\n")
                .append(handler).append(".setParent(this);\n");
        for (var attribute : supplied.entrySet()) {
            var contract = tag.attributes().get(attribute.getKey());
            if (contract == null) {
                source.append(handler).append(".setDynamicAttribute(null, ")
                        .append(JavaSource.quote(attribute.getKey())).append(", ")
                        .append(JavaSource.quote(attribute.getValue())).append(");\n");
                continue;
            }
            String setter = "set" + Character.toUpperCase(contract.name().charAt(0)) + contract.name().substring(1);
            String argument = nestedAttributeValue(attribute.getValue(), contract, contract.type(), action.location(),
                    deferredSyntaxIsLiteral);
            source.append(handler).append('.').append(setter).append("((").append(contract.type()).append(") (")
                    .append(argument).append("));\n");
        }
        source.append(handler).append(".doTag();\n");
        int variableIndex = 0;
        for (var variable : tag.variables()) {
            if (variable.scope() == TagVariableDescriptor.NESTED) continue;
            var exported = nestedTagVariable(action, variable);
            String value = "_jspxNestedTagFile" + index + "_jspxVariable" + variableIndex++;
            source.append("Object ").append(value).append(" = ").append(handler)
                    .append("._jspxTagFileContext().getAttribute(").append(JavaSource.quote(exported.contextName()))
                    .append(", jakarta.servlet.jsp.PageContext.PAGE_SCOPE);\n")
                    .append("jspContext.removeAttribute(").append(JavaSource.quote(exported.name()))
                    .append(", jakarta.servlet.jsp.PageContext.PAGE_SCOPE);\n")
                    .append("if (").append(value).append(" != null) jspContext.setAttribute(")
                    .append(JavaSource.quote(exported.name())).append(", ").append(value)
                    .append(", jakarta.servlet.jsp.PageContext.PAGE_SCOPE);\n");
        }
        return source.append("}\n").toString();
    }

    private static String nestedAttributeValue(String value, TagAttribute attribute, String setterType, SourceLocation at,
            boolean deferredSyntaxIsLiteral) {
        var expression = java.util.regex.Pattern.compile("^\\s*<%=\\s*(.*?)\\s*%>\\s*$", java.util.regex.Pattern.DOTALL).matcher(value);
        if (expression.matches()) {
            if (!attribute.runtimeExpression())
                throw new PageParseException(at, "Request-time expression is not allowed for nested tag attribute: " + attribute.name());
            String code = expression.group(1).trim();
            return setterType.equals("java.lang.String") ? "java.lang.String.valueOf(" + code + ")" : code;
        }
        if (value.contains("<%") || value.contains("${") || (!deferredSyntaxIsLiteral && value.contains("#{")))
            throw new PageParseException(at, "Expressions in nested tag attributes are not supported yet");
        if (!setterType.equals("java.lang.String") && !setterType.equals("java.lang.Object"))
            throw new PageParseException(at, "Literal nested tag attributes require String setters in standard tag files");
        return JavaSource.quote(value);
    }

    private static List<String> imports(String value, SourceLocation at) {
        var result = new ArrayList<String>();
        for (String item : value.split(",", -1)) {
            String name = item.trim();
            String type = name.endsWith(".*") ? name.substring(0, name.length() - 2) : name;
            if (name.isEmpty() || !javax.lang.model.SourceVersion.isName(type))
                throw new PageParseException(at, "Invalid tag-file import: " + name);
            result.add(name);
        }
        return result;
    }

    private static TagVariableDescriptor variable(PageNode.Directive directive) {
        var attributes = directive.attributes();
        for (String key : attributes.keySet())
            if (!Set.of("name-given", "name-from-attribute", "alias", "variable-class", "declare", "scope", "description").contains(key))
                throw new PageParseException(directive.location(), "Invalid tag-file variable directive field: " + key);
        if (attributes.containsKey("alias") && !attributes.containsKey("name-from-attribute"))
            throw new PageParseException(directive.location(), "Tag-file variable alias requires name-from-attribute");
        if (attributes.containsKey("name-given") && attributes.containsKey("name-from-attribute"))
            throw new PageParseException(directive.location(), "Tag-file variable cannot specify both name-given and name-from-attribute");
        String nameFromAttribute = attributes.get("name-from-attribute");
        if (nameFromAttribute != null && (!javax.lang.model.SourceVersion.isIdentifier(nameFromAttribute)
                || javax.lang.model.SourceVersion.isKeyword(nameFromAttribute)))
            throw new PageParseException(directive.location(), "Tag-file variable name-from-attribute must be a Java identifier");
        String alias = attributes.get("alias");
        if (alias != null && (!javax.lang.model.SourceVersion.isIdentifier(alias)
                || javax.lang.model.SourceVersion.isKeyword(alias)))
            throw new PageParseException(directive.location(), "Tag-file variable alias must be a Java identifier");
        String name = attributes.get("name-given");
        if (nameFromAttribute == null && (name == null || !javax.lang.model.SourceVersion.isIdentifier(name)
                || javax.lang.model.SourceVersion.isKeyword(name)))
            throw new PageParseException(directive.location(), "Tag-file variable name-given must be a Java identifier");
        String className = attributes.getOrDefault("variable-class", "java.lang.String");
        if (!javax.lang.model.SourceVersion.isName(className))
            throw new PageParseException(directive.location(), "Invalid tag-file variable class: " + className);
        String declareValue = attributes.getOrDefault("declare", "true");
        if (!declareValue.equals("true") && !declareValue.equals("false"))
            throw new PageParseException(directive.location(), "Tag-file variable declare must be true or false");
        int scope = switch (attributes.getOrDefault("scope", "NESTED")) {
            case "NESTED" -> TagVariableDescriptor.NESTED;
            case "AT_BEGIN" -> TagVariableDescriptor.AT_BEGIN;
            case "AT_END" -> TagVariableDescriptor.AT_END;
            default -> throw new PageParseException(directive.location(), "Invalid tag-file variable scope");
        };
        return new TagVariableDescriptor(name, nameFromAttribute, className, declareValue.equals("true"), scope, alias);
    }

    private static String tagFileVariableLocalName(PageNode.Directive directive, TagVariableDescriptor variable) {
        return variable.name() != null ? variable.name() : directive.attributes().get("alias");
    }

    private static void validateNameFromAttributeVariables(Map<String, TagAttribute> attributes,
            List<TagVariableDescriptor> variables, SourceLocation at) {
        for (var variable : variables) {
            if (variable.nameFromAttribute() == null) continue;
            var attribute = attributes.get(variable.nameFromAttribute());
            if (attribute == null || !attribute.type().equals("java.lang.String") || !attribute.required()
                    || attribute.runtimeExpression() || attribute.fragment())
                throw new PageParseException(at,
                        "Tag-file variable name-from-attribute must reference a required, non-runtime java.lang.String attribute: "
                                + variable.nameFromAttribute());
        }
    }

    private static void referencedFiles(List<PageNode> nodes, Set<String> result) {
        for (var node : nodes) if (node instanceof PageNode.Element element) {
            String uri = element.namespace();
            if (uri.startsWith(TAGDIR_PREFIX)) {
                String name = element.name().substring(element.name().lastIndexOf(':') + 1);
                String directory = directory(uri.substring(TAGDIR_PREFIX.length()), element.location());
                result.add(directory + "/" + name + ".tag");
                result.add(directory + "/" + name + ".tagx");
            }
            referencedFiles(element.children(), result);
        }
    }

    private static void referencedTldTagFiles(List<PageNode> nodes,
            Map<String, TagLibraryDescriptor> libraries, Set<String> result) {
        for (var node : nodes) if (node instanceof PageNode.Element element) {
            var library = libraries.get(element.namespace());
            if (library == null) library = libraries.values().stream()
                    .filter(candidate -> Objects.equals(candidate.uri(), element.namespace())).findFirst().orElse(null);
            if (library == null && !element.namespace().contains(":")) {
                String resourcePath = TranslationUnit.resolve(element.location().source(), element.namespace());
                library = libraries.get(resourcePath);
                if (library == null) library = libraries.values().stream()
                        .filter(candidate -> Objects.equals(candidate.resourcePath(), resourcePath)).findFirst().orElse(null);
            }
            if (library != null) {
                String name = element.name().substring(element.name().lastIndexOf(':') + 1);
                var tag = library.tags().get(name);
                if (tag != null && tag.tagFilePath() != null) result.add(tag.tagFilePath());
            }
            referencedTldTagFiles(element.children(), libraries, result);
        }
    }

    private static String directory(String token, SourceLocation at) {
        if (token.isBlank()) throw new PageParseException(at, "tagdir must name a directory");
        String path = token.startsWith("/") ? java.nio.file.Path.of(token).normalize().toString().replace('\\', '/')
                : TranslationUnit.resolve(at.source(), token);
        if (path.equals("/")) throw new PageParseException(at, "tagdir must name a directory below the web root");
        try { PageDescriptor.validatePath(path + "/_tagdir_validation.tagx"); }
        catch (IllegalArgumentException invalid) { throw new PageParseException(at, "Invalid tagdir: " + token, invalid); }
        return path;
    }

    private static String source(String className, boolean declaration, String markup) {
        int split = className.lastIndexOf('.');
        String source = "package " + className.substring(0, split) + ";\n"
                + "public final class " + className.substring(split + 1)
                + " extends jakarta.servlet.jsp.tagext.SimpleTagSupport {\n"
                + "public void doTag() throws java.io.IOException {\n";
        if (declaration) source += "getJspContext().getOut().write(\"<?xml version=\\\"1.0\\\" encoding=\\\"UTF-8\\\"?>\");\n";
        source += "getJspContext().getOut().write(" + JavaSource.quote(markup) + ");\n} }\n";
        return source;
    }

    private static String source(String className, StandardTagFile tag) {
        int split = className.lastIndexOf('.');
        var source = new StringBuilder("package ").append(className, 0, split).append(";\n")
                .append("import jakarta.servlet.*;\n")
                .append("import jakarta.servlet.http.*;\n")
                .append("import jakarta.servlet.jsp.*;\n")
                .append("import jakarta.servlet.jsp.tagext.SimpleTagSupport;\n")
                .append("import io.vidocq.ibarra.spi.TagFileJspContext;\n");
        for (String name : tag.imports()) source.append("import ").append(name).append(";\n");
        source.append("public final class ").append(className.substring(split + 1))
                .append(" extends SimpleTagSupport")
                .append(tag.dynamicAttributesName() == null ? " {\n"
                        : " implements jakarta.servlet.jsp.tagext.DynamicAttributes {\n")
                .append(tag.libraryValidationDeclarations());
        if (tag.dynamicAttributesName() != null)
            source.append("private final java.util.Map<String, Object> ").append(tag.dynamicAttributesName())
                    .append(" = new java.util.LinkedHashMap<>();\n");
        for (var attribute : tag.attributes().values()) {
            String setter = "set" + Character.toUpperCase(attribute.name().charAt(0)) + attribute.name().substring(1);
            source.append("private ").append(attribute.type()).append(' ').append(attribute.name()).append(";\n");
            if (attribute.deferredValueType() != null)
                source.append("private boolean _jspxVariableSet_").append(attribute.name()).append(";\n")
                        .append("private jakarta.el.ValueExpression _jspxPreviousVariable_").append(attribute.name()).append(";\n");
            source.append("public void ").append(setter).append('(').append(attribute.type()).append(" value) {")
                    .append(" this.").append(attribute.name()).append(" = value; getJspContext().setAttribute(")
                    .append(JavaSource.quote(attribute.name())).append(", value);");
            if (attribute.deferredValueType() != null)
                source.append("var mapper = getJspContext().getELContext().getVariableMapper();\n")
                        .append("if (!_jspxVariableSet_").append(attribute.name()).append(") _jspxPreviousVariable_")
                        .append(attribute.name()).append(" = mapper.setVariable(").append(JavaSource.quote(attribute.name())).append(", value);\n")
                        .append("else mapper.setVariable(").append(JavaSource.quote(attribute.name())).append(", value);\n")
                        .append("_jspxVariableSet_").append(attribute.name()).append(" = true;");
            source.append(" }\n");
        }
        source.append("@Override public void setJspContext(JspContext context) {\n")
                .append("if (context instanceof PageContext page) super.setJspContext(new TagFileJspContext(page, ")
                .append(tag.errorOnELNotFound()).append("));\n")
                .append("else super.setJspContext(context);\n");
        if (tag.dynamicAttributesName() != null)
            source.append("getJspContext().setAttribute(").append(JavaSource.quote(tag.dynamicAttributesName())).append(", ")
                    .append(tag.dynamicAttributesName()).append(");\n");
        source.append("}\n")
                .append("public JspContext _jspxTagFileContext() { return getJspContext(); }\n");
        if (tag.dynamicAttributesName() != null)
            source.append("@Override public void setDynamicAttribute(String uri, String localName, Object value) throws JspException {\n")
                    .append("if (uri == null || uri.isEmpty()) ").append(tag.dynamicAttributesName()).append(".put(localName, value);\n}\n");
        source.append(tag.declarations())
                .append("@Override public void doTag() throws java.io.IOException, jakarta.servlet.jsp.JspException {\n")
                .append("var jspContext = getJspContext();\n")
                .append("var pageContext = (PageContext) jspContext;\n")
                .append("var out = jspContext.getOut();\n")
                .append("var application = pageContext.getServletContext();\n")
                .append(tag.hasImmediateEl() ? "var _jspxTagFileElFactory = jakarta.servlet.jsp.JspFactory.getDefaultFactory()"
                        + ".getJspApplicationContext(application).getExpressionFactory();\n" : "");
        boolean deferredValueAttributes = tag.attributes().values().stream()
                .anyMatch(attribute -> attribute.deferredValueType() != null);
        if (deferredValueAttributes) source.append("var _jspxTagFileELContext = getJspContext().getELContext();\ntry {\n");
        source.append(tag.libraryValidationDeclarations().isEmpty() ? "" : "_jspxValidateTagLibraries();\n")
                .append("var config = pageContext.getServletConfig();\n")
                .append("var request = (jakarta.servlet.http.HttpServletRequest) pageContext.getRequest();\n")
                .append("var response = (jakarta.servlet.http.HttpServletResponse) pageContext.getResponse();\n")
                .append("var session = pageContext.getSession();\n")
                .append(tag.emitsXmlDeclaration()
                        ? "out.write(\"<?xml version=\\\"1.0\\\" encoding=\\\"UTF-8\\\"?>\");\n" : "")
                .append(tag.nestedVariables().stream().filter(NestedTagVariable::declare)
                        .map(variable -> variable.className().replace('$', '.') + " " + variable.name() + " = null;\n")
                        .collect(java.util.stream.Collectors.joining()))
                .append(tag.body());
        if (deferredValueAttributes) {
            source.append("} finally {\n");
            for (var attribute : tag.attributes().values()) if (attribute.deferredValueType() != null)
                source.append("if (_jspxVariableSet_").append(attribute.name())
                        .append(") _jspxTagFileELContext.getVariableMapper().setVariable(")
                        .append(JavaSource.quote(attribute.name())).append(", _jspxPreviousVariable_")
                        .append(attribute.name()).append(");\n");
            source.append("}\n");
        }
        source.append("}\n}\n");
        return source.toString();
    }

    private static String staticMarkup(List<PageNode> nodes) {
        var result = new StringBuilder();
        append(nodes, result);
        return result.toString();
    }

    private static void append(List<PageNode> nodes, StringBuilder result) {
        for (var node : nodes) {
            if (node instanceof PageNode.Text text) result.append(escapeText(text.value()));
            else if (node instanceof PageNode.RawText text) result.append(text.value());
            else if (node instanceof PageNode.Directive directive)
                throw new PageParseException(directive.location(), "Directives in XML tag files are not supported yet");
            else if (node instanceof PageNode.Expression expression)
                throw new PageParseException(expression.location(), "Expressions in XML tag files are not supported yet");
            else if (node instanceof PageNode.Script script)
                throw new PageParseException(script.location(), "Scripting in XML tag files is not supported yet");
            else if (node instanceof PageNode.Element element) element(element, result);
        }
    }

    private static void element(PageNode.Element element, StringBuilder result) {
        String local = element.name().substring(element.name().lastIndexOf(':') + 1);
        if (element.namespace().equals(JSP_NAMESPACE)) {
            if (local.equals("output")) return;
            if (local.equals("root")) {
                String version = element.attributes().get("version");
                if (!"1.2".equals(version) && !"2.0".equals(version))
                    throw new PageParseException(element.location(), "Invalid jsp:root version: " + version);
                append(element.children(), result);
                return;
            }
            if (local.equals("text") || local.equals("body")) { append(element.children(), result); return; }
            throw new PageParseException(element.location(), "Action in XML tag files is not supported yet: " + element.name());
        }
        result.append('<').append(element.name());
        for (var attribute : element.attributes().entrySet()) {
            if (attribute.getKey().startsWith("xmlns") && attribute.getValue().equals(JSP_NAMESPACE)) continue;
            if (attribute.getValue().contains("${") || attribute.getValue().contains("#{"))
                throw new PageParseException(element.location(), "Expressions in XML tag-file attributes are not supported yet");
            result.append(' ').append(attribute.getKey()).append("=\"").append(escapeAttribute(attribute.getValue())).append('"');
        }
        if (element.selfClosing()) result.append("/>");
        else {
            result.append('>'); append(element.children(), result); result.append("</").append(element.name()).append('>');
        }
    }

    private static String escapeText(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
    private static String escapeAttribute(String text) {
        return escapeText(text).replace("\"", "&quot;");
    }
    private static String hash(String path) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(path.getBytes(StandardCharsets.UTF_8))).substring(0, 24); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
